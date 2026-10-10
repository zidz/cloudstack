//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.amazonaws.util.CollectionUtils;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.RbdBackupExecutor;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.backup.TakeBackupCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@ResourceWrapper(handles = TakeBackupCommand.class)
public class LibvirtTakeBackupCommandWrapper extends CommandWrapper<TakeBackupCommand, Answer, LibvirtComputingResource> {
    private static final Integer EXIT_CLEANUP_FAILED = 20;
    // nasbackup.sh prints this on stdout when it could not proceed as an incremental and
    // completed a full backup instead; the orchestrator then records the backup as a full.
    private static final String INCREMENTAL_FALLBACK_MARKER = "INCREMENTAL_FALLBACK=true";
    private static final String BACKUP_TEMP_FILE_PREFIX = "csbackup";

    private static final String MODE_FULL = "full";
    private static final String MODE_INCREMENTAL = "incremental";
    // Incremental feature disabled: plain full backup with no QEMU bitmap/checkpoint and no
    // chain metadata. Matches nasbackup.sh's "legacy-full" mode (make_checkpoint=0).
    private static final String MODE_LEGACY_FULL = "legacy-full";

    @Override
    public Answer execute(TakeBackupCommand command, LibvirtComputingResource libvirtComputingResource) {
        final String vmName = command.getVmName();
        final String backupPath = command.getBackupPath();
        final String backupRepoType = command.getBackupRepoType();
        final String backupRepoAddress = command.getBackupRepoAddress();
        final String mountOptions = command.getMountOptions();
        List<PrimaryDataStoreTO> volumePools = command.getVolumePools();
        final List<String> volumePaths = command.getVolumePaths();
        KVMStoragePoolManager storagePoolMgr = libvirtComputingResource.getStoragePoolMgr();
        int timeout = command.getWait() > 0 ? command.getWait() * 1000 : libvirtComputingResource.getCmdsTimeout();

        // Pre-validate incremental args here rather than relying on the script to error out.
        // Keeps the script agnostic to caller policy (it just does what it's told).
        String validationError = validateBackupArgs(command);
        if (validationError != null) {
            return new BackupAnswer(command, false, validationError);
        }

        List<String> diskPaths = new ArrayList<>();
        if (Objects.nonNull(volumePaths)) {
            for (int idx = 0; idx < volumePaths.size(); idx++) {
                PrimaryDataStoreTO volumePool = volumePools.get(idx);
                String volumePath = volumePaths.get(idx);
                if (volumePool.getPoolType() != Storage.StoragePoolType.RBD) {
                    diskPaths.add(volumePath);
                } else {
                    KVMStoragePool volumeStoragePool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
                    String rbdDestVolumeFile = KVMPhysicalDisk.RBDStringBuilder(volumeStoragePool, volumePath);
                    diskPaths.add(rbdDestVolumeFile);
                }
            }
        }

        // Pure-RBD VMs (every volume pool is RBD) are driven directly by RbdBackupExecutor, which
        // shells out to the `rbd` CLI (the Java port of the former backup bash script). Every other pool
        // type - including mixed RBD/non-RBD VMs and the NAS provider's own commands - keeps the
        // exact nasbackup.sh path, so NAS behavior is unchanged byte-for-byte.
        if (isPureRbdBackup(volumePools)) {
            return takeBackupViaExecutor(command, diskPaths);
        }

        final String backupScriptPath = libvirtComputingResource.getNasBackupPath();
        Pair<Integer, String> result = runBackupScript(libvirtComputingResource, command, vmName, backupRepoType, backupRepoAddress,
                mountOptions, backupPath, diskPaths, command.getMode(),
                command.getBitmapNew(), command.getBitmapParent(), command.getParentPaths(), timeout, backupScriptPath);

        if (result.first() != 0) {
            logger.debug("Failed to take VM backup: " + result.second());
            BackupAnswer answer = new BackupAnswer(command, false, result.second().trim());
            if (EXIT_CLEANUP_FAILED.equals(result.first())) {
                logger.debug("Backup cleanup failed");
                answer.setNeedsCleanup(true);
            }
            return answer;
        }

        // The script self-heals to a full backup when an incremental can't proceed (e.g. the
        // parent checkpoint can't be re-registered) and signals it with INCREMENTAL_FALLBACK
        // on stdout. Detect it, then strip the marker line before parsing the backup size.
        String rawStdout = result.second();
        boolean incrementalFallback = rawStdout.contains(INCREMENTAL_FALLBACK_MARKER);
        String stdout = stripMarkerLines(rawStdout).trim();
        long backupSize = parseBackupSize(stdout, diskPaths);

        BackupAnswer answer = new BackupAnswer(command, true, stdout);
        answer.setSize(backupSize);
        // A successful run always created command.getBitmapNew() (full and incremental both do;
        // it is null for legacy-full, which the orchestrator treats as "no bitmap").
        answer.setBitmapCreated(command.getBitmapNew());
        answer.setIncrementalFallback(incrementalFallback);
        return answer;
    }

    /**
     * True only when every volume pool is RBD (pure-RBD VM). Mixed or non-RBD pools return false so
     * the NAS path stays byte-for-byte unchanged.
     */
    private boolean isPureRbdBackup(List<PrimaryDataStoreTO> volumePools) {
        if (volumePools == null || volumePools.isEmpty()) {
            return false;
        }
        for (PrimaryDataStoreTO pool : volumePools) {
            if (pool == null || pool.getPoolType() != Storage.StoragePoolType.RBD) {
                return false;
            }
        }
        return true;
    }

    /**
     * Drive a pure-RBD backup through {@link RbdBackupExecutor}. The repo is mounted here (the
     * executor never mounts), each disk is exported under {@code <mount>/<backupPath>} with the
     * exact {@code <name>.<image>.rbd} / {@code <name>.<image>.<ts>.rbdiff} names the provider
     * expects, and the returned sizes are summed exactly like the script's {@code du} lines. Exports
     * are compressed (zstd) end-to-end. The repo's global fallback is reproduced: an incremental is
     * valid only while the parent snapshot exists on EVERY disk; if it is missing anywhere (or the
     * parent is empty) the whole backup self-heals to a full and the answer's incremental-fallback
     * flag is set - the same field the NAS stdout marker used to flip.
     */
    private Answer takeBackupViaExecutor(TakeBackupCommand command, List<String> diskPaths) {
        final String backupPath = command.getBackupPath();
        if (CollectionUtils.isNullOrEmpty(diskPaths)) {
            return new BackupAnswer(command, false, "No disks to back up");
        }

        String effectiveMode = StringUtils.isBlank(command.getMode()) ? MODE_LEGACY_FULL : command.getMode();
        final String parent = command.getBitmapParent();
        final String snapshotNew = StringUtils.isNotBlank(command.getBitmapNew())
                ? command.getBitmapNew() : "backup-" + (System.currentTimeMillis() / 1000L);
        final String ts = StringUtils.substringAfterLast(backupPath, "/").isEmpty()
                ? backupPath : StringUtils.substringAfterLast(backupPath, "/");
        final String[] diskTypes = StringUtils.isNotBlank(command.getDiskTypes())
                ? command.getDiskTypes().split(",") : new String[0];

        String mountDirectory = null;
        Path backupDir = null;
        try {
            mountDirectory = mountBackupDirectory(command.getBackupRepoAddress(), command.getBackupRepoType(), command.getMountOptions());
            backupDir = Paths.get(mountDirectory).resolve(backupPath);
            Files.createDirectories(backupDir);

            int disks = diskPaths.size();
            RbdBackupExecutor.RbdAuth[] auths = new RbdBackupExecutor.RbdAuth[disks];
            String[] pools = new String[disks];
            String[] images = new String[disks];
            for (int idx = 0; idx < disks; idx++) {
                String uri = diskPaths.get(idx);
                auths[idx] = RbdBackupExecutor.parseRbdUri(uri);
                pools[idx] = RbdBackupExecutor.getPool(uri);
                images[idx] = RbdBackupExecutor.getImage(uri);
            }

            boolean incrementalFallback = false;
            if (MODE_INCREMENTAL.equals(effectiveMode)) {
                boolean parentMissing = StringUtils.isBlank(parent);
                if (!parentMissing) {
                    for (int idx = 0; idx < disks; idx++) {
                        if (!RbdBackupExecutor.parentExists(auths[idx], pools[idx], images[idx], parent)) {
                            logger.debug(String.format("RBD incremental: parent snapshot %s missing on %s/%s - "
                                    + "falling back to full for all disks", parent, pools[idx], images[idx]));
                            parentMissing = true;
                            break;
                        }
                    }
                }
                if (parentMissing) {
                    incrementalFallback = true;
                    effectiveMode = MODE_FULL;
                }
            }

            long totalSize = 0L;
            for (int idx = 0; idx < disks; idx++) {
                String name;
                if (idx < diskTypes.length && StringUtils.isNotBlank(diskTypes[idx])) {
                    name = diskTypes[idx];
                } else if (idx == 0) {
                    name = "root";
                } else {
                    name = "datadisk";
                }

                if (MODE_INCREMENTAL.equals(effectiveMode)) {
                    Path dest = backupDir.resolve(name + "." + images[idx] + "." + ts + ".rbdiff");
                    totalSize += RbdBackupExecutor.exportDiff(auths[idx], pools[idx], images[idx], parent, snapshotNew, dest, true);
                } else {
                    Path dest = backupDir.resolve(name + "." + images[idx] + ".rbd");
                    // legacy-full exports the live image with no snapshot chain metadata; a normal
                    // full creates + protects a snapshot and exports it (the diff base).
                    String snap = MODE_LEGACY_FULL.equals(effectiveMode) ? "" : snapshotNew;
                    totalSize += RbdBackupExecutor.exportFull(auths[idx], pools[idx], images[idx], snap, dest, true);
                }
            }

            BackupAnswer answer = new BackupAnswer(command, true, String.valueOf(totalSize));
            answer.setSize(totalSize);
            answer.setBitmapCreated(command.getBitmapNew());
            answer.setIncrementalFallback(incrementalFallback);
            return answer;
        } catch (CloudRuntimeException | IOException e) {
            logger.debug("Failed to take RBD VM backup: " + e.getMessage());
            // Bash cleanup() did rm -rf on the dest dir on failure; mirror that so a mid-loop
            // export failure does not leave orphaned partial files on the NFS repo.
            if (backupDir != null) {
                try {
                    Script.executeCommand("rm", "-rf", backupDir.toString());
                } catch (Exception ignored) {
                    logger.warn("Failed to clean partial backup dir " + backupDir + ": " + ignored.getMessage());
                }
            }
            return new BackupAnswer(command, false, e.getMessage());
        } finally {
            if (mountDirectory != null) {
                // Bash ran sync before umount to flush NFS writes to the backup repo.
                Script.executeCommand("sync");
                unmountBackupDirectory(mountDirectory);
                deleteTemporaryDirectory(mountDirectory);
            }
        }
    }

    private String mountBackupDirectory(String backupRepoAddress, String backupRepoType, String mountOptions) {
        String randomChars = RandomStringUtils.random(5, true, false);
        String mountDirectory = String.format("%s.%s", BACKUP_TEMP_FILE_PREFIX, randomChars);
        try {
            mountDirectory = Files.createTempDirectory(mountDirectory).toString();
        } catch (IOException e) {
            logger.error("Failed to create the tmp mount directory {} for backup", mountDirectory, e);
            throw new CloudRuntimeException("Failed to create the tmp mount directory for backup on the KVM host");
        }
        try {
            String mountPath = Script.getExecutableAbsolutePath("mount");
            List<String> mountCmd = new ArrayList<>();
            mountCmd.add("sudo");
            mountCmd.add(mountPath);
            mountCmd.add("-t");
            mountCmd.add(backupRepoType);
            mountCmd.add(backupRepoAddress);
            mountCmd.add(mountDirectory);
            if ("cifs".equals(backupRepoType)) {
                if (StringUtils.isBlank(mountOptions)) {
                    mountOptions = "nobrl";
                } else {
                    mountOptions += ",nobrl";
                }
            }
            if (StringUtils.isNotBlank(mountOptions)) {
                mountCmd.add("-o");
                mountCmd.add(mountOptions);
            }
            Script.executeCommand(mountCmd.toArray(new String[0]));
        } catch (Exception e) {
            logger.error("Failed to mount repository {} of type {} to the directory {}", backupRepoAddress, backupRepoType, mountDirectory, e);
            throw new CloudRuntimeException("Failed to mount the backup repository on the KVM host");
        }
        return mountDirectory;
    }

    private void unmountBackupDirectory(String backupDirectory) {
        try {
            String umountPath = Script.getExecutableAbsolutePath("umount");
            String[] umountCmd = new String[] { "sudo", umountPath, backupDirectory };
            Script.executeCommand(umountCmd);
        } catch (Exception e) {
            logger.error("Failed to unmount backup directory {}", backupDirectory, e);
        }
    }

    private void deleteTemporaryDirectory(String backupDirectory) {
        try {
            Files.deleteIfExists(Paths.get(backupDirectory));
        } catch (IOException e) {
            logger.error("Failed to delete backup directory: {}}", backupDirectory, e);
        }
    }

    /**
     * Run nasbackup.sh once with the given args. Returns the exit code + captured stdout.
     */
    private Pair<Integer, String> runBackupScript(LibvirtComputingResource libvirtComputingResource,
            TakeBackupCommand command, String vmName, String backupRepoType, String backupRepoAddress,
            String mountOptions, String backupPath, List<String> diskPaths, String mode,
            String bitmapNew, String bitmapParent, List<String> parentPaths, int timeout, String backupScriptPath) {
        List<String> argv = new ArrayList<>(Arrays.asList(
                backupScriptPath,
                "-o", "backup",
                "-v", vmName,
                "-t", backupRepoType,
                "-s", backupRepoAddress,
                "-m", Objects.nonNull(mountOptions) ? mountOptions : "",
                "-p", backupPath,
                "-q", command.getQuiesce() != null && command.getQuiesce() ? "true" : "false",
                "-d", diskPaths.isEmpty() ? "" : String.join(",", diskPaths)
        ));
        if (mode != null && !mode.isEmpty()) {
            argv.add("-M");
            argv.add(mode);
        }
        if (bitmapNew != null && !bitmapNew.isEmpty()) {
            argv.add("--bitmap-new");
            argv.add(bitmapNew);
        }
        if (bitmapParent != null && !bitmapParent.isEmpty()) {
            argv.add("--bitmap-parent");
            argv.add(bitmapParent);
        }
        if (parentPaths != null && !parentPaths.isEmpty()) {
            argv.add("--parent-paths");
            argv.add(String.join(",", parentPaths));
        }

        List<String[]> commands = new ArrayList<>();
        commands.add(argv.toArray(new String[0]));
        return Script.executePipedCommands(commands, timeout);
    }

    /** Remove nasbackup.sh's stdout signalling marker lines so they don't pollute size parsing. */
    private String stripMarkerLines(String stdout) {
        if (stdout == null || stdout.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : stdout.split("\n", -1)) {
            if (line.contains(INCREMENTAL_FALLBACK_MARKER)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * Return a human-readable validation error string, or {@code null} if the command's
     * incremental-backup args are internally consistent.
     */
    private String validateBackupArgs(TakeBackupCommand command) {
        String mode = command.getMode();
        if (mode == null || mode.isEmpty()) {
            return null; // legacy full-only - no extra args expected
        }
        if (MODE_INCREMENTAL.equals(mode)) {
            if (command.getBitmapNew() == null || command.getBitmapNew().isEmpty()) {
                return "incremental mode requires bitmapNew";
            }
            if (command.getBitmapParent() == null || command.getBitmapParent().isEmpty()) {
                return "incremental mode requires bitmapParent";
            }
            if (command.getParentPaths() == null || command.getParentPaths().isEmpty()) {
                return "incremental mode requires parentPaths";
            }
            return null;
        }
        if (MODE_FULL.equals(mode)) {
            if (command.getBitmapNew() == null || command.getBitmapNew().isEmpty()) {
                return "full mode requires bitmapNew (the bitmap to create for the next incremental)";
            }
            return null;
        }
        if (MODE_LEGACY_FULL.equals(mode)) {
            return null; // feature-off full backup - no bitmap or chain args expected
        }
        return "Unknown backup mode: " + mode;
    }

    /**
     * Sum the per-disk size lines emitted by nasbackup.sh. Single-volume mode emits one
     * line containing just the byte count; multi-volume mode emits one line per disk
     * whose first whitespace-separated token is the byte count.
     */
    private long parseBackupSize(String stdout, List<String> diskPaths) {
        long backupSize = 0L;
        if (CollectionUtils.isNullOrEmpty(diskPaths)) {
            List<String> outputLines = Arrays.asList(stdout.split("\n"));
            if (!outputLines.isEmpty()) {
                backupSize = Long.parseLong(outputLines.get(outputLines.size() - 1).trim());
            }
        } else {
            for (String line : stdout.split("\n")) {
                backupSize = backupSize + Long.parseLong(line.split(" ")[0].trim());
            }
        }
        return backupSize;
    }
}
