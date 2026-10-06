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
package org.apache.cloudstack.backup;

import com.cloud.agent.AgentManager;
import com.cloud.configuration.Resource;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.offering.DiskOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotDetailsVO;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupRepositoryDao;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.inject.Inject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.cloudstack.backup.BackupManager.BackupFrameworkEnabled;

/**
 * Self-contained backup provider for VMs whose primary storage is Ceph RBD. Mirrors the NAS
 * incremental-backup provider but drives the chain with RBD snapshots plus
 * {@code rbd export}/{@code rbd export-diff}/{@code rbd import-diff} executed by
 * {@code rbdbackup.sh} on the KVM host (the {@code rbd} CLI, not librbd-JNA diff).
 *
 * <p>Full backup = {@code rbd export} of a freshly created, protected snapshot. Incremental =
 * {@code rbd export-diff --from-snap <parentSnap>}. An incremental is only valid while the
 * parent RBD snapshot still exists on the primary pool; if the host cannot find it, the script
 * emits the {@code INCREMENTAL_FALLBACK} marker and the provider records the backup as a full
 * and restarts the chain (same semantics as the NAS provider).
 */
public class RbdBackupProvider extends AdapterBase implements BackupProvider, Configurable {
    private static final Logger LOG = LogManager.getLogger(RbdBackupProvider.class);

    ConfigKey<Boolean> RbdBackupIncrementalEnabled = new ConfigKey<>("Advanced", Boolean.class,
            RbdBackupChainKeys.CONFIG_INCREMENTAL_ENABLED,
            "false",
            "Master switch for RBD incremental backups. Defaults to false so existing zones keep the "
                    + "legacy full-only behavior on upgrade; opt in per-zone when ready to use chains. "
                    + "When false, every RBD backup is taken as a full regardless of rbd.backup.full.every.",
            true,
            ConfigKey.Scope.Zone,
            BackupFrameworkEnabled.key());

    ConfigKey<Integer> RbdBackupFullEvery = new ConfigKey<>("Advanced", Integer.class,
            RbdBackupChainKeys.CONFIG_FULL_EVERY,
            "10",
            "Take a full RBD backup every Nth backup; remaining backups in between are incremental. "
                    + "Counts backups, not days. Set to 1 to disable incrementals (every backup is full).",
            true,
            ConfigKey.Scope.Zone,
            RbdBackupIncrementalEnabled.key());

    @Inject
    private BackupDao backupDao;

    @Inject
    private BackupRepositoryDao backupRepositoryDao;

    @Inject
    private HostDao hostDao;

    @Inject
    private VolumeDao volumeDao;

    @Inject
    private VMInstanceDao vmInstanceDao;

    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;

    @Inject
    DataStoreManager dataStoreMgr;

    @Inject
    private AgentManager agentManager;

    @Inject
    private ResourceLimitService resourceLimitMgr;

    @Inject
    private VMSnapshotDao vmSnapshotDao;

    @Inject
    private VMSnapshotDetailsDao vmSnapshotDetailsDao;

    @Inject
    BackupManager backupManager;

    @Inject
    ResourceManager resourceManager;

    @Inject
    private DiskOfferingDao diskOfferingDao;

    @Inject
    private BackupDetailsDao backupDetailsDao;

    /**
     * Returned by {@link #decideChain(VirtualMachine)} to describe the next backup's place in
     * the chain: full vs incremental, the RBD snapshot to create, and (for incrementals) the
     * parent snapshot and per-volume parent backup file paths.
     */
    static final class ChainDecision {
        final String mode;            // "full", "incremental", or "legacy-full"
        final String snapNew;         // snapshot name to create with this backup
        final String snapParent;      // parent snapshot to diff from (null for full)
        final List<String> parentPaths; // per-volume parent backup file paths (deviceId order)
        final String chainId;         // chain identifier this backup belongs to
        final int chainPosition;      // 0 for full, N for the Nth incremental

        private ChainDecision(String mode, String snapNew, String snapParent, List<String> parentPaths,
                              String chainId, int chainPosition) {
            this.mode = mode;
            this.snapNew = snapNew;
            this.snapParent = snapParent;
            this.parentPaths = parentPaths;
            this.chainId = chainId;
            this.chainPosition = chainPosition;
        }

        static ChainDecision fullStart(String snapName) {
            return new ChainDecision(RbdBackupChainKeys.TYPE_FULL, snapName, null, null,
                    UUID.randomUUID().toString(), 0);
        }

        static ChainDecision legacyFull() {
            return new ChainDecision(RbdBackupChainKeys.TYPE_LEGACY_FULL, null, null, null, null, 0);
        }

        static ChainDecision incremental(String snapNew, String snapParent, List<String> parentPaths,
                                         String chainId, int chainPosition) {
            return new ChainDecision(RbdBackupChainKeys.TYPE_INCREMENTAL, snapNew, snapParent,
                    parentPaths, chainId, chainPosition);
        }

        boolean isIncremental() {
            return RbdBackupChainKeys.TYPE_INCREMENTAL.equals(mode);
        }

        boolean isLegacyFull() {
            return RbdBackupChainKeys.TYPE_LEGACY_FULL.equals(mode);
        }
    }

    // Config reads are isolated behind protected methods so decideChain can be unit-tested
    // deterministically with a Mockito spy (the real ConfigKey default is false => legacyFull).
    protected boolean isIncrementalEnabled(Long zoneId) {
        Boolean enabled = RbdBackupIncrementalEnabled.valueIn(zoneId);
        return enabled != null && enabled;
    }

    protected Integer getFullEvery(Long zoneId) {
        return RbdBackupFullEvery.valueIn(zoneId);
    }

    /**
     * Decides whether the next backup for {@code vm} should be a fresh full or an incremental
     * appended to the existing chain. Stopped VMs are always full. The {@code rbd.backup.full.every}
     * ConfigKey controls how many backups form one chain before a new full is forced. The decision
     * is anchored on the VM's {@code rbd.active_snap} detail (the snapshot currently on the pool);
     * after a restore that detail is cleared, so the next backup is automatically full.
     */
    protected ChainDecision decideChain(VirtualMachine vm) {
        if (!isIncrementalEnabled(vm.getDataCenterId())) {
            return ChainDecision.legacyFull();
        }

        // RBD snapshots only exist on Ceph RBD pools. A VM with any volume on non-RBD storage
        // cannot carry an RBD snapshot chain, so it stays on the legacy full-only path.
        if (!allVolumesOnRbdStorage(vm)) {
            return ChainDecision.legacyFull();
        }

        final String newSnap = "backup-" + System.currentTimeMillis() / 1000L;

        if (VirtualMachine.State.Stopped.equals(vm.getState())) {
            return ChainDecision.fullStart(newSnap);
        }

        Integer fullEvery = getFullEvery(vm.getDataCenterId());
        if (fullEvery == null || fullEvery <= 1) {
            return ChainDecision.fullStart(newSnap);
        }

        String activeSnap = readVmActiveSnap(vm.getId());
        if (activeSnap == null) {
            return ChainDecision.fullStart(newSnap);
        }

        Backup parent = findLatestBackedUpBackup(vm.getId());
        if (parent == null || !activeSnap.equals(readDetail(parent, RbdBackupChainKeys.PARENT_SNAP))) {
            LOG.debug("VM {} latest backup does not match active_snap={} — forcing full",
                    vm.getInstanceName(), activeSnap);
            return ChainDecision.fullStart(newSnap);
        }

        String parentChainId = readDetail(parent, RbdBackupChainKeys.CHAIN_ID);
        int parentChainPosition = chainPosition(parent);
        if (parentChainId == null || parentChainPosition == Integer.MAX_VALUE) {
            return ChainDecision.fullStart(newSnap);
        }

        if (parentChainPosition + 1 >= fullEvery) {
            return ChainDecision.fullStart(newSnap);
        }

        // F4(i): a volume added/removed mid-chain has no snapshot base for the export-diff, so
        // force a full when the current VM's sorted volume-path set differs from the parent
        // backup's backed volume-path set (a same-count swap of volumes still mismatches here).
        Set<String> currentVolumePaths = new TreeSet<>();
        for (VolumeVO v : volumeDao.findByInstance(vm.getId())) {
            currentVolumePaths.add(v.getPath());
        }
        Set<String> parentVolumePaths = new TreeSet<>();
        for (Backup.VolumeInfo vi : CollectionUtils.emptyIfNull(parent.getBackedUpVolumes())) {
            parentVolumePaths.add(vi.getPath());
        }
        if (!currentVolumePaths.equals(parentVolumePaths)) {
            LOG.debug("VM {} current volume-path set {} differs from parent backup {} set {} — forcing full",
                    vm.getInstanceName(), currentVolumePaths, parent.getUuid(), parentVolumePaths);
            return ChainDecision.fullStart(newSnap);
        }

        List<String> parentPaths = composeParentBackupPaths(parent, vm.getId());
        if (parentPaths == null) {
            LOG.debug("VM {} parent backup {} volume layout no longer matches current VM — forcing full",
                    vm.getInstanceName(), parent.getUuid());
            return ChainDecision.fullStart(newSnap);
        }
        return ChainDecision.incremental(newSnap, activeSnap, parentPaths, parentChainId, parentChainPosition + 1);
    }

    /**
     * Incremental RBD backups require every volume to live on a Ceph RBD pool (the only pool
     * type that carries RBD snapshots). Returns {@code true} only when EVERY volume of the VM
     * sits on an {@code RBD} pool. A volume whose pool cannot be resolved is treated as
     * incapable (safe default), forcing the legacy full-only path.
     */
    protected boolean allVolumesOnRbdStorage(VirtualMachine vm) {
        List<VolumeVO> volumes = volumeDao.findByInstance(vm.getId());
        if (volumes == null) {
            return true;
        }
        for (VolumeVO volume : volumes) {
            StoragePoolVO pool = primaryDataStoreDao.findById(volume.getPoolId());
            if (pool == null) {
                LOG.debug("VM {} volume {} has no resolvable storage pool — forcing legacy full",
                        vm.getInstanceName(), volume.getUuid());
                return false;
            }
            if (!Storage.StoragePoolType.RBD.equals(pool.getPoolType())) {
                LOG.debug("VM {} volume {} is on {} (not RBD) — forcing legacy full",
                        vm.getInstanceName(), volume.getUuid(), pool.getPoolType());
                return false;
            }
        }
        return true;
    }

    private String readVmActiveSnap(long vmId) {
        VMInstanceDetailVO d = vmInstanceDetailsDao.findDetail(vmId, RbdBackupChainKeys.VM_ACTIVE_SNAP);
        if (d == null) {
            return null;
        }
        String v = d.getValue();
        return StringUtils.isBlank(v) ? null : v;
    }

    private void upsertVmActiveSnap(long vmId, String snapName) {
        VMInstanceDetailVO existing = vmInstanceDetailsDao.findDetail(vmId, RbdBackupChainKeys.VM_ACTIVE_SNAP);
        if (existing == null) {
            vmInstanceDetailsDao.addDetail(vmId, RbdBackupChainKeys.VM_ACTIVE_SNAP, snapName, false);
            return;
        }
        existing.setValue(snapName);
        vmInstanceDetailsDao.update(existing.getId(), existing);
    }

    private void clearVmActiveSnap(long vmId) {
        VMInstanceDetailVO existing = vmInstanceDetailsDao.findDetail(vmId, RbdBackupChainKeys.VM_ACTIVE_SNAP);
        if (existing != null) {
            vmInstanceDetailsDao.removeDetail(vmId, RbdBackupChainKeys.VM_ACTIVE_SNAP);
            LOG.debug("Cleared rbd.active_snap for VM id={} (was {})", vmId, existing.getValue());
        }
    }

    private Backup findLatestBackedUpBackup(long vmId) {
        List<Backup> history = backupDao.listByVmId(null, vmId);
        if (CollectionUtils.isEmpty(history)) {
            return null;
        }
        return history.stream()
                .filter(b -> Backup.Status.BackedUp.equals(b.getStatus()))
                .max(Comparator.comparing(Backup::getDate))
                .orElse(null);
    }

    private String readDetail(Backup backup, String key) {
        BackupDetailVO d = backupDetailsDao.findDetail(backup.getId(), key);
        return d == null ? null : d.getValue();
    }

    private int chainPosition(Backup b) {
        String s = readDetail(b, RbdBackupChainKeys.CHAIN_POSITION);
        if (s == null) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Compose the on-repo path of EVERY parent backup file (one per VM volume) in the same order
     * the script iterates the current VM's disks (deviceId asc). Relative to the repo mount root,
     * matching the layout written by {@code rbdbackup.sh}:
     *   parent FULL        -> {@code <backupPath>/root.<volPath>.rbd} / {@code datadisk.<volPath>.rbd}
     *   parent INCREMENTAL -> {@code <backupPath>/root.<volPath>.<parentTs>.rbdiff} etc.
     *
     * Returns {@code null} if the parent's stored volume count doesn't match the current VM's
     * volume count (volume attach/detach while assigned), so the caller falls back to full.
     */
    private List<String> composeParentBackupPaths(Backup parent, long vmId) {
        String dir = parent.getExternalId();
        if (StringUtils.isBlank(dir)) {
            LOG.debug("Parent backup {} has no backup path recorded; cannot compose parent backup paths", parent.getUuid());
            return null;
        }

        List<Backup.VolumeInfo> parentVols = parent.getBackedUpVolumes();
        if (CollectionUtils.isEmpty(parentVols)) {
            LOG.debug("Parent backup {} has no backed up volumes recorded; cannot compose parent backup paths", parent.getUuid());
            return null;
        }

        List<VolumeVO> currentVols = volumeDao.findByInstance(vmId);
        if (CollectionUtils.isEmpty(currentVols) || currentVols.size() != parentVols.size()) {
            LOG.debug("VM id={} currently has {} volume(s) but parent backup {} recorded {}; cannot compose parent backup paths",
                    vmId, currentVols == null ? 0 : currentVols.size(), parent.getUuid(), parentVols.size());
            return null;
        }

        // Incremental parent files carry the parent backup's timestamp segment (last path element
        // of externalId, e.g. "i-2-1234-VM/2026.04.27.13.45.00"); full parent files do not.
        String parentTs = dir.contains("/") ? dir.substring(dir.lastIndexOf('/') + 1) : dir;
        boolean parentIsIncremental = "INCREMENTAL".equalsIgnoreCase(parent.getType());

        List<String> paths = new ArrayList<>(parentVols.size());
        for (int i = 0; i < parentVols.size(); i++) {
            String volPath = parentVols.get(i).getPath();
            String prefix = (i == 0) ? "root" : "datadisk";
            if (parentIsIncremental) {
                paths.add(dir + "/" + prefix + "." + volPath + "." + parentTs + ".rbdiff");
            } else {
                paths.add(dir + "/" + prefix + "." + volPath + ".rbd");
            }
        }
        return paths;
    }

    private String lookupParentBackupUuid(long vmId, String parentSnap) {
        if (parentSnap == null) {
            return null;
        }
        for (Backup b : backupDao.listByVmId(null, vmId)) {
            String sn = readDetail(b, RbdBackupChainKeys.PARENT_SNAP);
            if (parentSnap.equals(sn)) {
                return b.getUuid();
            }
        }
        return null;
    }

    protected Host getVMHypervisorHostForBackup(VirtualMachine vm) {
        Long hostId = vm.getHostId();
        if (hostId == null && VirtualMachine.State.Running.equals(vm.getState())) {
            throw new CloudRuntimeException(String.format("Unable to find the hypervisor host for %s. Make sure the virtual machine is running", vm.getName()));
        }
        if (VirtualMachine.State.Stopped.equals(vm.getState())) {
            hostId = vm.getLastHostId();
        }
        if (hostId == null) {
            throw new CloudRuntimeException(String.format("Unable to find the hypervisor host for stopped VM: %s", vm));
        }
        final Host host = hostDao.findById(hostId);
        if (host == null || !Status.Up.equals(host.getStatus()) || !Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())) {
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
        }
        return host;
    }

    protected Host getVMHypervisorHost(VirtualMachine vm) {
        Long hostId = vm.getLastHostId();
        Long clusterId = null;
        if (hostId != null) {
            Host host = hostDao.findById(hostId);
            if (host != null && host.getStatus() == Status.Up) {
                return host;
            }
            if (host != null) {
                clusterId = host.getClusterId();
            }
        } else {
            VolumeVO rootVolume = volumeDao.getInstanceRootVolume(vm.getId());
            if (rootVolume != null) {
                StoragePoolVO rootDiskPool = primaryDataStoreDao.findById(rootVolume.getPoolId());
                if (rootDiskPool != null) {
                    clusterId = rootDiskPool.getClusterId();
                }
            }
        }
        if (clusterId != null) {
            for (final Host hostInCluster : hostDao.findHypervisorHostInCluster(clusterId)) {
                if (hostInCluster.getStatus() == Status.Up) {
                    return hostInCluster;
                }
            }
        }
        return resourceManager.findOneRandomRunningHostByHypervisor(Hypervisor.HypervisorType.KVM, vm.getDataCenterId());
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, Boolean quiesceVM, boolean isolated) {
        final Host host = getVMHypervisorHostForBackup(vm);

        final BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(vm.getBackupOfferingId());
        if (backupRepository == null) {
            throw new CloudRuntimeException("No valid backup repository found for the VM, please check the attached backup offering");
        }

        if (CollectionUtils.isNotEmpty(vmSnapshotDao.findByVmAndByType(vm.getId(), VMSnapshot.Type.DiskAndMemory))) {
            logger.debug("RBD backup provider cannot take backups of a VM [{}] with disk-and-memory VM snapshots.", vm);
            throw new CloudRuntimeException(String.format("Cannot take backup of VM [%s] as it has disk-and-memory VM snapshots.", vm.getUuid()));
        }

        final Date creationDate = new Date();
        final String backupPath = String.format("%s/%s", vm.getInstanceName(),
                new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(creationDate));

        ChainDecision decision = decideChain(vm);

        BackupVO backupVO = createBackupObject(vm, backupPath, decision.isIncremental() ? "INCREMENTAL" : "FULL");
        TakeBackupCommand command = new TakeBackupCommand(vm.getInstanceName(), backupPath);
        command.setBackupRepoType(backupRepository.getType());
        command.setBackupRepoAddress(backupRepository.getAddress());
        command.setMountOptions(backupRepository.getMountOptions());
        command.setQuiesce(quiesceVM);
        command.setMode(decision.mode);
        command.setBitmapNew(decision.snapNew);
        command.setBitmapParent(decision.snapParent);
        command.setParentSnapName(decision.snapParent);
        command.setParentPaths(decision.parentPaths);

        // rbdbackup.sh drives the rbd CLI directly from the per-volume rbd: specs, so unlike the
        // NAS provider (which reads virsh domblklist for running VMs) we always pass the volume
        // pools/paths. The KVM wrapper turns each RBD pool + raw path into an rbd:<pool>/<uuid>:
        // mon_host=... spec via KVMPhysicalDisk.RBDStringBuilder.
        List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        command.setVolumePools(volumePoolsAndPaths.first());
        command.setVolumePaths(volumePoolsAndPaths.second());
        // Authoritative per-disk type list in the SAME deviceId-asc order as volumePaths, so
        // take-side filenames match restore-side vi.getType(). Additive; NAS never reads it.
        // The list is always supplied in deviceId-asc order so the script's positional fallback never triggers on the rbd path.
        command.setDiskTypes(vmVolumes.stream()
                .map(v -> Volume.Type.ROOT.equals(v.getVolumeType()) ? "root" : "datadisk")
                .collect(Collectors.joining(",")));

        BackupAnswer answer;
        try {
            answer = (BackupAnswer) agentManager.send(host.getId(), command);
        } catch (AgentUnavailableException e) {
            logger.error("Unable to contact backend control plane to initiate backup for VM {}", vm.getInstanceName());
            backupVO.setStatus(Backup.Status.Failed);
            backupDao.update(backupVO.getId(), backupVO);
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
        } catch (OperationTimedoutException e) {
            logger.error("Operation to initiate backup timed out for VM {}", vm.getInstanceName());
            backupVO.setStatus(Backup.Status.Failed);
            backupDao.update(backupVO.getId(), backupVO);
            throw new CloudRuntimeException("Operation to initiate backup timed out, please try again");
        }

        if (answer != null && answer.getResult()) {
            backupVO.setDate(new Date());
            backupVO.setSize(answer.getSize());
            backupVO.setStatus(Backup.Status.BackedUp);
            ChainDecision effective = decision;
            if (answer.getIncrementalFallback()) {
                effective = ChainDecision.fullStart(decision.snapNew);
                backupVO.setType("FULL");
            }
            List<Volume> volumes = new ArrayList<>(volumeDao.findByInstance(vm.getId()));
            backupVO.setBackedUpVolumes(backupManager.createVolumeInfoFromVolumes(volumes));
            if (backupDao.update(backupVO.getId(), backupVO)) {
                if (!decision.isLegacyFull()) {
                    persistChainMetadata(backupVO, effective, answer.getBitmapCreated());
                    String confirmedSnap = answer.getBitmapCreated();
                    if (confirmedSnap != null) {
                        upsertVmActiveSnap(vm.getId(), confirmedSnap);
                    } else {
                        clearVmActiveSnap(vm.getId());
                    }
                }
                return new Pair<>(true, backupVO);
            } else {
                throw new CloudRuntimeException("Failed to update backup");
            }
        } else {
            logger.error("Failed to take backup for VM {}: {}", vm.getInstanceName(), answer != null ? answer.getDetails() : "No answer received");
            if (answer != null && answer.getNeedsCleanup()) {
                backupVO.setStatus(Backup.Status.Error);
                backupDao.update(backupVO.getId(), backupVO);
            } else {
                backupVO.setStatus(Backup.Status.Failed);
                backupDao.update(backupVO.getId(), backupVO);
            }
            return new Pair<>(false, null);
        }
    }

    private BackupVO createBackupObject(VirtualMachine vm, String backupPath, String type) {
        BackupVO backup = new BackupVO();
        backup.setVmId(vm.getId());
        backup.setExternalId(backupPath);
        backup.setType(type);
        backup.setDate(new Date());
        long virtualSize = 0L;
        for (final Volume volume : volumeDao.findByInstance(vm.getId())) {
            if (Volume.State.Ready.equals(volume.getState())) {
                virtualSize += volume.getSize();
            }
        }
        backup.setProtectedSize(virtualSize);
        backup.setStatus(Backup.Status.BackingUp);
        backup.setBackupOfferingId(vm.getBackupOfferingId());
        backup.setAccountId(vm.getAccountId());
        backup.setDomainId(vm.getDomainId());
        backup.setZoneId(vm.getDataCenterId());
        backup.setName(backupManager.getBackupNameFromVM(vm));
        Map<String, String> details = backupManager.getBackupDetailsFromVM(vm);
        backup.setDetails(details);
        return backupDao.persist(backup);
    }

    private void persistChainMetadata(Backup backup, ChainDecision decision, String snapFromAgent) {
        if (StringUtils.isNotBlank(snapFromAgent)) {
            backupDetailsDao.persist(new BackupDetailVO(backup.getId(), RbdBackupChainKeys.PARENT_SNAP, snapFromAgent, true));
        }
        backupDetailsDao.persist(new BackupDetailVO(backup.getId(), RbdBackupChainKeys.CHAIN_ID, decision.chainId, true));
        backupDetailsDao.persist(new BackupDetailVO(backup.getId(), RbdBackupChainKeys.CHAIN_POSITION,
                String.valueOf(decision.chainPosition), true));
        if (decision.isIncremental()) {
            String parentUuid = lookupParentBackupUuid(backup.getVmId(), decision.snapParent);
            if (parentUuid != null) {
                backupDetailsDao.persist(new BackupDetailVO(backup.getId(), RbdBackupChainKeys.PARENT_BACKUP_ID, parentUuid, true));
            }
        }
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid, boolean quickrestore) {
        return restoreVMBackup(vm, backup);
    }

    @Override
    public boolean restoreVMFromBackup(VirtualMachine vm, Backup backup, boolean quickRestore, Long hostId) {
        return restoreVMBackup(vm, backup).first();
    }

    private Pair<Boolean, String> restoreVMBackup(VirtualMachine vm, Backup backup) {
        List<Backup.VolumeInfo> backedVolumes = backup.getBackedUpVolumes();

        // Pair each backed volume with the CURRENT VM volume that has the SAME UUID. The RBD image
        // name IS the volume UUID, so matching by UUID (not positional deviceId) guarantees each
        // per-volume chain replays onto the exact destination image it was exported from, even when
        // the VM's device_id values have drifted since the backup was taken. backedVolumesUUIDs,
        // restoreVolumes and restoreBackupFiles are built in ONE loop so they stay index-aligned.
        Map<String, VolumeVO> currentByUuid = volumeDao.findByInstance(vm.getId()).stream()
                .collect(Collectors.toMap(VolumeVO::getUuid, v -> v, (a, b) -> a));

        List<String> backedVolumesUUIDs = new ArrayList<>();
        List<VolumeVO> restoreVolumes = new ArrayList<>();
        List<String> restoreBackupFiles = new ArrayList<>();
        for (Backup.VolumeInfo vi : backedVolumes.stream()
                .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                .collect(Collectors.toList())) {
            VolumeVO current = currentByUuid.get(vi.getUuid());
            if (current == null) {
                throw new CloudRuntimeException(String.format(
                        "Cannot restore backup %s to VM %s: volume %s from the backup is no longer attached to the VM",
                        backup.getUuid(), vm.getUuid(), vi.getUuid()));
            }
            backedVolumesUUIDs.add(vi.getUuid());
            restoreVolumes.add(current);
            restoreBackupFiles.add(buildVolumeChainFiles(backup, vi.getPath()));
        }

        LOG.debug("Restoring vm {} from backup {} on the RBD Backup Provider", vm, backup);
        BackupRepository backupRepository = getBackupRepository(backup);

        final Host host = getVMHypervisorHost(vm);
        RestoreBackupCommand restoreCommand = new RestoreBackupCommand();
        restoreCommand.setBackupPath(backup.getExternalId());
        restoreCommand.setBackupRepoType(backupRepository.getType());
        restoreCommand.setBackupRepoAddress(backupRepository.getAddress());
        restoreCommand.setMountOptions(backupRepository.getMountOptions());
        restoreCommand.setVmName(vm.getName());
        restoreCommand.setBackupVolumesUUIDs(backedVolumesUUIDs);
        Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(restoreVolumes);
        restoreCommand.setRestoreVolumePools(volumePoolsAndPaths.first());
        restoreCommand.setRestoreVolumePaths(volumePoolsAndPaths.second());
        // restoreBackupFiles was built in the UUID-pairing loop above, index-aligned with
        // backedVolumesUUIDs and restoreVolumePaths: entry idx is the comma-joined chain (base full +
        // every incremental link, root->leaf) for the volume whose SOURCE path is backedVolumes[idx]
        // .getPath(), replayed onto the destination volume with the SAME UUID. See buildVolumeChainFiles.
        restoreCommand.setBackupFiles(restoreBackupFiles);
        // rbd import-diff requires the destination image to ALREADY contain the snapshot the first
        // .rbdiff was exported --from-snap. That snapshot is the FULL anchor's PARENT_SNAP detail, so
        // pass it to the host script, which recreates it on the destination before replaying the
        // diffs. Only needed when the chain carries at least one incremental (a lone full needs none).
        List<Backup> restoreChain = getChainOrderedRootToLeaf(backup);
        if (restoreChain.size() > 1) {
            String baseSnap = readDetail(restoreChain.get(0), RbdBackupChainKeys.PARENT_SNAP);
            if (StringUtils.isNotBlank(baseSnap)) {
                restoreCommand.setBaseSnap(baseSnap);
            }
        }
        restoreCommand.setVmExists(vm.getRemoved() == null);
        restoreCommand.setVmState(vm.getState());

        BackupAnswer answer;
        try {
            answer = (BackupAnswer) agentManager.send(host.getId(), restoreCommand);
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate restore");
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation to restore backup timed out, please try again");
        }
        if (answer == null) {
            throw new CloudRuntimeException(String.format("No answer received from the backend control plane while restoring VM %s from backup %s", vm.getInstanceName(), backup.getUuid()));
        }
        if (answer.getResult()) {
            clearVmActiveSnap(vm.getId());
        }
        return new Pair<>(answer.getResult(), answer.getDetails());
    }

    /**
     * Build the explicit, ordered restore chain for ONE volume across the whole chain (root full
     * anchor down to {@code target}), as a comma-joined list of repo-relative paths. The host
     * replays them with {@code rbd import} (base) + {@code rbd import-diff} (each link) in order.
     *
     * <p>The filename rule is the single source of truth shared with {@code rbdbackup.sh}: a FULL
     * member writes {@code <externalId>/<diskType>.<sourceVolPath>.rbd}; an INCREMENTAL member
     * writes {@code <externalId>/<diskType>.<sourceVolPath>.<ts>.rbdiff} where {@code <ts>} is the
     * basename of {@code externalId} (the dotted {@code yyyy.MM.dd.HH.mm.ss} date), matching the
     * script's {@code ts} (basename of {@code BACKUP_DIR}). Members are ordered root->leaf; within a member,
     * volumes are ordered by deviceId. Only members carrying {@code sourceVolumePath} contribute.
     */
    private String buildVolumeChainFiles(Backup target, String sourceVolumePath) {
        List<Backup> chain = getChainOrderedRootToLeaf(target);
        List<String> files = new ArrayList<>();
        for (Backup member : chain) {
            List<Backup.VolumeInfo> matchingVolumes = CollectionUtils.emptyIfNull(member.getBackedUpVolumes()).stream()
                    .filter(vi -> sourceVolumePath.equals(vi.getPath()))
                    .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                    .collect(Collectors.toList());
            for (Backup.VolumeInfo vi : matchingVolumes) {
                String externalId = member.getExternalId();
                String diskType = vi.getType().name().toLowerCase(Locale.ROOT);
                if ("INCREMENTAL".equalsIgnoreCase(member.getType())) {
                    String ts = externalId.substring(externalId.lastIndexOf('/') + 1);
                    files.add(externalId + "/" + diskType + "." + vi.getPath() + "." + ts + ".rbdiff");
                } else {
                    files.add(externalId + "/" + diskType + "." + vi.getPath() + ".rbd");
                }
            }
        }
        // F4(ii): a replay chain must start with a full .rbd base. If the first file is a .rbdiff
        // the chain was mis-assembled (e.g. a mid-chain volume imported as base) — fail fast
        // instead of letting the host script import a diff as a base image.
        if (!files.isEmpty() && files.get(0).endsWith(".rbdiff")) {
            throw new CloudRuntimeException(String.format(
                    "Backup chain for volume %s of backup %s starts with a .rbdiff instead of a .rbd base",
                    sourceVolumePath, target.getUuid()));
        }
        return String.join(",", files);
    }

    private List<Backup> getChainOrderedRootToLeaf(Backup member) {
        String chainId = readDetail(member, RbdBackupChainKeys.CHAIN_ID);
        if (chainId == null) {
            return Collections.singletonList(member);
        }
        List<Backup> chain = new ArrayList<>();
        for (Backup b : backupDao.listByVmId(null, member.getVmId())) {
            if (chainId.equals(readDetail(b, RbdBackupChainKeys.CHAIN_ID))) {
                chain.add(b);
            }
        }
        chain.sort(Comparator.comparingInt(this::chainPosition));
        return chain;
    }

    private List<String> getBackupFiles(List<Backup.VolumeInfo> backedVolumes) {
        List<String> backupFiles = new ArrayList<>();
        for (Backup.VolumeInfo backedVolume : backedVolumes) {
            backupFiles.add(backedVolume.getPath());
        }
        return backupFiles;
    }

    private Pair<List<PrimaryDataStoreTO>, List<String>> getVolumePoolsAndPaths(List<VolumeVO> volumes) {
        List<PrimaryDataStoreTO> volumePools = new ArrayList<>();
        List<String> volumePaths = new ArrayList<>();
        for (VolumeVO volume : volumes) {
            StoragePoolVO storagePool = primaryDataStoreDao.findById(volume.getPoolId());
            if (Objects.isNull(storagePool)) {
                throw new CloudRuntimeException("Unable to find storage pool associated to the volume");
            }
            DataStore dataStore = dataStoreMgr.getDataStore(storagePool.getId(), DataStoreRole.Primary);
            volumePools.add(dataStore != null ? (PrimaryDataStoreTO) dataStore.getTO() : null);
            String volumePathPrefix = getVolumePathPrefix(storagePool);
            String volumePathSuffix = getVolumePathSuffix(storagePool);
            volumePaths.add(String.format("%s%s%s", volumePathPrefix, volume.getPath(), volumePathSuffix));
        }
        return new Pair<>(volumePools, volumePaths);
    }

    private String getVolumePathPrefix(StoragePoolVO storagePool) {
        String volumePathPrefix;
        if (ScopeType.HOST.equals(storagePool.getScope())
                || Storage.StoragePoolType.SharedMountPoint.equals(storagePool.getPoolType())
                || Storage.StoragePoolType.RBD.equals(storagePool.getPoolType())) {
            volumePathPrefix = storagePool.getPath() + "/";
        } else if (Storage.StoragePoolType.Linstor.equals(storagePool.getPoolType())) {
            volumePathPrefix = "/dev/drbd/by-res/cs-";
        } else {
            volumePathPrefix = String.format("/mnt/%s/", storagePool.getUuid());
        }
        return volumePathPrefix;
    }

    private String getVolumePathSuffix(StoragePoolVO storagePool) {
        if (Storage.StoragePoolType.Linstor.equals(storagePool.getPoolType())) {
            return "/0";
        } else {
            return "";
        }
    }

    @Override
    public Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp, String dataStoreUuid,
            Pair<String, VirtualMachine.State> vmNameAndState, VirtualMachine vm, boolean quickRestore) {
        final VolumeVO volume = volumeDao.findByUuid(backupVolumeInfo.getUuid());
        final DiskOffering diskOffering = diskOfferingDao.findByUuid(backupVolumeInfo.getDiskOfferingId());
        final StoragePoolVO pool = primaryDataStoreDao.findByUuid(dataStoreUuid);
        final HostVO hostVO = hostDao.findByIp(hostIp);

        Backup.VolumeInfo matchingVolume = getBackedUpVolumeInfo(backup.getBackedUpVolumes(), volume.getUuid());
        if (matchingVolume == null) {
            throw new CloudRuntimeException(String.format("Unable to find volume %s in the list of backed up volumes for backup %s, cannot proceed with restore", volume.getUuid(), backup));
        }
        Long backedUpVolumeSize = matchingVolume.getSize();

        BackupRepository backupRepository = getBackupRepository(backup);

        VolumeVO restoredVolume = new VolumeVO(Volume.Type.DATADISK, null, backup.getZoneId(),
                backup.getDomainId(), backup.getAccountId(), 0, null,
                backup.getSize(), null, null, null);
        String volumeUUID = UUID.randomUUID().toString();
        String volumeName = volume != null ? volume.getName() : backupVolumeInfo.getUuid();
        restoredVolume.setName("RestoredVol-" + volumeName);
        restoredVolume.setProvisioningType(diskOffering.getProvisioningType());
        restoredVolume.setUpdated(new Date());
        restoredVolume.setUuid(volumeUUID);
        restoredVolume.setRemoved(null);
        restoredVolume.setDisplayVolume(true);
        restoredVolume.setPoolId(pool.getId());
        restoredVolume.setPoolType(pool.getPoolType());
        restoredVolume.setPath(restoredVolume.getUuid());
        restoredVolume.setState(Volume.State.Copying);
        restoredVolume.setSize(backedUpVolumeSize);
        restoredVolume.setDiskOfferingId(diskOffering.getId());
        if (pool.getPoolType() != Storage.StoragePoolType.RBD) {
            restoredVolume.setFormat(Storage.ImageFormat.QCOW2);
        } else {
            restoredVolume.setFormat(Storage.ImageFormat.RAW);
        }

        RestoreBackupCommand restoreCommand = new RestoreBackupCommand();
        restoreCommand.setBackupPath(backup.getExternalId());
        restoreCommand.setBackupRepoType(backupRepository.getType());
        restoreCommand.setBackupRepoAddress(backupRepository.getAddress());
        restoreCommand.setVmName(vmNameAndState.first());
        String restoreVolumePath = String.format("%s%s%s", getVolumePathPrefix(pool), volumeUUID, getVolumePathSuffix(pool));
        restoreCommand.setRestoreVolumePaths(Collections.singletonList(restoreVolumePath));
        restoreCommand.setRestoreVolumeSizes(Collections.singletonList(backedUpVolumeSize));
        DataStore dataStore = dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
        restoreCommand.setRestoreVolumePools(Collections.singletonList(dataStore != null ? (PrimaryDataStoreTO) dataStore.getTO() : null));
        restoreCommand.setDiskType(backupVolumeInfo.getType().name().toLowerCase(Locale.ROOT));
        restoreCommand.setMountOptions(backupRepository.getMountOptions());
        restoreCommand.setVmExists(null);
        restoreCommand.setVmState(vmNameAndState.second());
        restoreCommand.setBackupFiles(Collections.singletonList(buildVolumeChainFiles(backup, matchingVolume.getPath())));

        BackupAnswer answer;
        try {
            answer = (BackupAnswer) agentManager.send(hostVO.getId(), restoreCommand);
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate restore");
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation to restore backed up volume timed out, please try again");
        }

        if (answer.getResult()) {
            try {
                volumeDao.persist(restoredVolume);
            } catch (Exception e) {
                throw new CloudRuntimeException("Unable to create restored volume due to: " + e);
            }
            VirtualMachine restoreTargetVm = vmInstanceDao.findVMByInstanceName(vmNameAndState.first());
            if (restoreTargetVm != null) {
                clearVmActiveSnap(restoreTargetVm.getId());
            }
        }
        return new Pair<>(answer.getResult(), answer.getDetails());
    }

    private BackupRepository getBackupRepository(Backup backup) {
        BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(backup.getBackupOfferingId());
        if (backupRepository == null) {
            throw new CloudRuntimeException(String.format("No valid backup repository found for the backup %s, please check the attached backup offering", backup.getUuid()));
        }
        return backupRepository;
    }

    private Backup.VolumeInfo getBackedUpVolumeInfo(List<Backup.VolumeInfo> backedUpVolumes, String volumeUuid) {
        return backedUpVolumes.stream()
                .filter(v -> v.getUuid().equals(volumeUuid))
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean handlesChainDeleteResourceAccounting() {
        return true;
    }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        final BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(backup.getBackupOfferingId());
        if (backupRepository == null) {
            throw new CloudRuntimeException("No valid backup repository found for the VM, please check the attached backup offering");
        }
        final Host host;
        final VirtualMachine vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm != null) {
            host = getVMHypervisorHost(vm);
        } else {
            host = resourceManager.findOneRandomRunningHostByHypervisor(Hypervisor.HypervisorType.KVM, backup.getZoneId());
        }
        if (host == null) {
            throw new CloudRuntimeException(String.format("Unable to find a running KVM host in zone %d to delete backup %s", backup.getZoneId(), backup.getUuid()));
        }
        DeleteBackupCommand command = new DeleteBackupCommand(backup.getExternalId(), backupRepository.getType(),
                backupRepository.getAddress(), backupRepository.getMountOptions());
        // F1b: if this member's protected snapshot is no longer needed by any remaining chain member,
        // attach the RBD URI + snap name so the host wrapper can unprotect+rm it AFTER the repo-file
        // delete succeeds. Computed BEFORE backupDao.remove so the predicate still sees the full chain.
        String deletedSnap = readDetail(backup, RbdBackupChainKeys.PARENT_SNAP);
        if (StringUtils.isNotBlank(deletedSnap) && !isSnapStillNeeded(backup, deletedSnap)) {
            String rbdUri = resolveRbdUriForSnapCleanup(backup);
            if (rbdUri != null) {
                command.setRbdUri(rbdUri);
                command.setRbdSnapName(deletedSnap);
            }
        }
        BackupAnswer answer;
        try {
            answer = (BackupAnswer) agentManager.send(host.getId(), command);
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation to delete backup timed out, please try again");
        }
        if (answer == null || !answer.getResult()) {
            logger.error("Failed to delete backup file for {} ({}); leaving DB row intact",
                    backup.getUuid(), backup.getExternalId());
            return false;
        }
        backupDao.remove(backup.getId());
        if (deletedSnap != null && deletedSnap.equals(readVmActiveSnap(backup.getVmId()))) {
            clearVmActiveSnap(backup.getVmId());
        }
        long size = backup.getSize() != null ? backup.getSize() : 0L;
        resourceLimitMgr.decrementResourceCount(backup.getAccountId(), Resource.ResourceType.backup);
        resourceLimitMgr.decrementResourceCount(backup.getAccountId(), Resource.ResourceType.backup_storage, size);
        return true;
    }

    /**
     * F1b predicate: a protected snapshot is still needed if ANY other non-removed backup in the
     * SAME chain (same vmId, same CHAIN_ID, B != this) records the same PARENT_SNAP value — i.e. a
     * later member still uses it as its export-diff base. Returns true when the snapshot must be
     * kept; false when it is safe to unprotect+rm.
     *
     * <p>Note: PARENT_SNAP stores the snapshot THIS member created (persistChainMetadata writes
     * snapFromAgent=answer.getBitmapCreated()). A later incremental member records its OWN created
     * snap, so a snap is reported 'still needed' only when another member literally shares the same
     * snap name (e.g. a re-recorded full). This is the conservative reading of the contract's
     * predicate; it never removes a snap that a same-named member still references.
     */
    private boolean isSnapStillNeeded(Backup backup, String snapName) {
        String chainId = readDetail(backup, RbdBackupChainKeys.CHAIN_ID);
        for (Backup b : backupDao.listByVmId(null, backup.getVmId())) {
            if (b.getId() == backup.getId()) {
                continue;
            }
            Backup.Status st = b.getStatus();
            if (Backup.Status.Removed.equals(st) || Backup.Status.Expunged.equals(st)) {
                continue;
            }
            if (!Objects.equals(chainId, readDetail(b, RbdBackupChainKeys.CHAIN_ID))) {
                continue;
            }
            if (snapName.equals(readDetail(b, RbdBackupChainKeys.PARENT_SNAP))) {
                return true;
            }
        }
        return false;
    }

    /**
     * F1b: resolve the root volume's primary RBD pool from the backup's VolumeInfo uuid and build
     * the RBD URI (mirroring KVMPhysicalDisk.RBDStringBuilder escaping) so the host script can
     * unprotect+rm the snapshot. Returns null (skip cleanup, debug log) when the volume is gone or
     * its pool is not a resolvable RBD pool — the image is gone anyway.
     */
    private String resolveRbdUriForSnapCleanup(Backup backup) {
        List<Backup.VolumeInfo> vols = backup.getBackedUpVolumes();
        if (CollectionUtils.isEmpty(vols)) {
            LOG.debug("Backup {} has no backed-up volumes; skipping RBD snapshot cleanup", backup.getUuid());
            return null;
        }
        Backup.VolumeInfo rootVol = vols.stream()
                .min(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                .orElse(vols.get(0));
        VolumeVO volume = null;
        for (VolumeVO v : volumeDao.findByInstance(backup.getVmId())) {
            if (rootVol.getUuid().equals(v.getUuid())) {
                volume = v;
                break;
            }
        }
        if (volume == null || volume.getRemoved() != null) {
            LOG.debug("Volume {} for backup {} is gone/removed; skipping RBD snapshot cleanup (image is gone anyway)",
                    rootVol.getUuid(), backup.getUuid());
            return null;
        }
        StoragePoolVO pool = primaryDataStoreDao.findById(volume.getPoolId());
        if (pool == null || !Storage.StoragePoolType.RBD.equals(pool.getPoolType())) {
            LOG.debug("Volume {} for backup {} is not on a resolvable RBD pool; skipping snapshot cleanup",
                    rootVol.getUuid(), backup.getUuid());
            return null;
        }
        String image = getVolumePathPrefix(pool) + volume.getPath() + getVolumePathSuffix(pool);
        return buildRbdUri(pool, image);
    }

    /**
     * Build an RBD URI for {@code image} on {@code pool}, mirroring
     * {@code KVMPhysicalDisk.RBDStringBuilder} so {@code rbdbackup.sh} parse_rbd_uri consumes it
     * identically to the take path. The rbd plugin cannot import the kvm plugin, so the escaping is
     * reproduced here (mon_host ':'->'\:' per host joined by '\;'; cephx id/key from userInfo).
     */
    private String buildRbdUri(StoragePoolVO pool, String image) {
        StringBuilder rbdOpts = new StringBuilder("rbd:").append(image);
        rbdOpts.append(":mon_host=").append(composeMonHosts(pool.getHostAddress(), pool.getPort()));
        String userInfo = pool.getUserInfo();
        String[] userInfoParts = userInfo == null ? new String[0] : userInfo.split(":");
        if (userInfoParts.length == 2) {
            rbdOpts.append(":auth_client_required=cephx");
            rbdOpts.append(":id=").append(userInfoParts[0]);
            rbdOpts.append(":key=").append(userInfoParts[1]);
        } else {
            rbdOpts.append(":auth_client_required=none");
        }
        rbdOpts.append(":rbd_default_format=2");
        return rbdOpts.toString();
    }

    private String composeMonHosts(String monHost, int monPort) {
        List<String> hosts = new ArrayList<>();
        if (monHost != null) {
            for (String host : monHost.split(",")) {
                if (monPort > 0) {
                    hosts.add(replaceHostAddress(host) + "\\:" + monPort);
                } else {
                    hosts.add(replaceHostAddress(host));
                }
            }
        }
        return String.join("\\;", hosts);
    }

    private String replaceHostAddress(String hostIp) {
        if (hostIp != null && hostIp.startsWith("[") && hostIp.endsWith("]")) {
            return hostIp.replaceAll("\\:", "\\\\:");
        }
        return hostIp;
    }

    public void syncBackupMetrics(Long zoneId) {
    }

    @Override
    public List<Backup.RestorePoint> listRestorePoints(VirtualMachine vm) {
        return null;
    }

    @Override
    public Backup createNewBackupEntryForRestorePoint(Backup.RestorePoint restorePoint, VirtualMachine vm) {
        return null;
    }

    @Override
    public boolean assignVMToBackupOffering(VirtualMachine vm, BackupOffering backupOffering) {
        for (VMSnapshotVO vmSnapshotVO : vmSnapshotDao.findByVmAndByType(vm.getId(), VMSnapshot.Type.Disk)) {
            List<VMSnapshotDetailsVO> vmSnapshotDetails = vmSnapshotDetailsDao.listDetails(vmSnapshotVO.getId());
            if (vmSnapshotDetails.stream().anyMatch(d -> VolumeApiServiceImpl.KVM_FILE_BASED_STORAGE_SNAPSHOT.equals(d.getName()))) {
                logger.warn("VM [{}] has VM snapshots using the KvmFileBasedStorageVmSnapshot Strategy; this provider does not support backups on VMs with these snapshots!", vm);
                return false;
            }
        }
        return Hypervisor.HypervisorType.KVM.equals(vm.getHypervisorType());
    }

    @Override
    public boolean removeVMFromBackupOffering(VirtualMachine vm) {
        clearVmActiveSnap(vm.getId());
        return true;
    }

    @Override
    public boolean willDeleteBackupsOnOfferingRemoval() {
        return false;
    }

    @Override
    public boolean supportsInstanceFromBackup() {
        return true;
    }

    @Override
    public boolean supportsMemoryVmSnapshot() {
        return false;
    }

    @Override
    public Pair<Long, Long> getBackupStorageStats(Long zoneId) {
        final List<BackupRepository> repositories = backupRepositoryDao.listByZoneAndProvider(zoneId, getName());
        Long totalSize = 0L;
        Long usedSize = 0L;
        for (final BackupRepository repository : repositories) {
            if (repository.getCapacityBytes() != null) {
                totalSize += repository.getCapacityBytes();
            }
            if (repository.getUsedBytes() != null) {
                usedSize += repository.getUsedBytes();
            }
        }
        return new Pair<>(usedSize, totalSize);
    }

    @Override
    public void syncBackupStorageStats(Long zoneId) {
        final List<BackupRepository> repositories = backupRepositoryDao.listByZoneAndProvider(zoneId, getName());
        if (CollectionUtils.isEmpty(repositories)) {
            return;
        }
        final Host host = resourceManager.findOneRandomRunningHostByHypervisor(Hypervisor.HypervisorType.KVM, zoneId);
        if (host == null) {
            logger.warn("Unable to find a running KVM host in zone {} to sync backup storage stats", zoneId);
            return;
        }
        for (final BackupRepository repository : repositories) {
            GetBackupStorageStatsCommand command = new GetBackupStorageStatsCommand(repository.getType(), repository.getAddress(), repository.getMountOptions());
            BackupStorageStatsAnswer answer;
            try {
                answer = (BackupStorageStatsAnswer) agentManager.send(host.getId(), command);
                backupRepositoryDao.updateCapacity(repository, answer.getTotalSize(), answer.getUsedSize());
            } catch (AgentUnavailableException e) {
                logger.warn("Unable to contact backend control plane to get backup stats for repository: {}", repository.getName());
            } catch (OperationTimedoutException e) {
                logger.warn("Operation to get backup stats timed out for the repository: " + repository.getName());
            }
        }
    }

    @Override
    public List<BackupOffering> listBackupOfferings(Long zoneId) {
        // No RBD-specific BackupOffering implementation exists on this plugin's classpath and the
        // contract forbids inventing one; offerings are surfaced through the shared backup
        // repository/offering plumbing instead. Reported as a deviation from the NAS template.
        return Collections.emptyList();
    }

    @Override
    public boolean isValidProviderOffering(Long zoneId, String uuid) {
        return true;
    }

    @Override
    public Boolean crossZoneInstanceCreationEnabled(BackupOffering backupOffering) {
        final BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(backupOffering.getId());
        if (backupRepository == null) {
            throw new CloudRuntimeException("Backup repository not found for the backup offering" + backupOffering.getName());
        }
        return Boolean.TRUE.equals(backupRepository.crossZoneInstanceCreationEnabled());
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                RbdBackupFullEvery,
                RbdBackupIncrementalEnabled
        };
    }

    @Override
    public String getName() {
        return "rbd";
    }

    @Override
    public String getDescription() {
        return "RBD Backup Plugin";
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }
}
