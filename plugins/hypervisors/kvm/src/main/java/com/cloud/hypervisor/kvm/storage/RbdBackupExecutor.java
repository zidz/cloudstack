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
package com.cloud.hypervisor.kvm.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Java replacement for {@code scripts/vm/hypervisor/kvm/rbdbackup.sh}: drives the {@code rbd} CLI on the
 * KVM host for Ceph RBD full/incremental backups and restores.
 *
 * <p>Every {@code rbd} invocation is built as an argv list and executed with {@link ProcessBuilder}
 * (never through a shell), so pool/image/snapshot names never need quoting. Streams are drained on
 * background threads to avoid the 64 KB pipe deadlock, and a non-zero exit is reported as a
 * {@link CloudRuntimeException} carrying the captured stderr.
 *
 * <p>Connection options are derived per call from the qualified RBD URI produced by
 * {@link KVMPhysicalDisk#RBDStringBuilder(KVMStoragePool, String)} — the same input the bash script
 * received as {@code -d}. {@link #parseRbdUri(String)} ports the script's {@code parse_rbd_uri} and
 * {@code --mon-host}/{@code --id}/{@code --key} are forwarded only when non-empty, exactly as the
 * script's {@code rbd_cmd} did.
 *
 * <p>Soft vs hard failure semantics mirror the script: hard failures throw (snap create, export,
 * export-diff, import, import-diff, {@code rbd rm}, {@code snap rm} on delete); soft steps are logged
 * and swallowed (snap protect, snap unprotect, per-snap cleanup while purging an image).
 *
 * <p>Compression is baked into the two chokepoints: a backup export is streamed
 * {@code rbd ... - --no-progress | zstd -q -o <dest> -}; a restore sniffs the file magic and streams
 * {@code zstd -dc <src> | rbd import - ...} for a zstd frame, otherwise feeding the raw file directly
 * for backward compatibility with existing uncompressed {@code .rbd}/{@code .rbdiff} files.
 */
public class RbdBackupExecutor {

    private static final Logger logger = LogManager.getLogger(RbdBackupExecutor.class);

    private static final String RBD = "rbd";
    private static final String ZSTD = "zstd";

    /** zstd frame magic {@code 28 B5 2F FD}; a backup file starting with it is a compressed frame. */
    private static final int[] ZSTD_MAGIC = {0x28, 0xB5, 0x2F, 0xFD};

    /**
     * segment plus the connection auth parsed from the escaped URI options.
     *
     * <p>Empty (never null) fields mean the URI omitted them, matching the bash globals that
     * {@code parse_rbd_uri} reset on every call.
     */
    public static class RbdAuth {
        public final String mon;
        public final String id;
        public final String key;

        public RbdAuth(String mon, String id, String key) {
            this.mon = mon == null ? "" : mon;
            this.id = id == null ? "" : id;
            this.key = key == null ? "" : key;
        }
    }

    /** Sentinel for {@code timeoutMs}: wait until the process exits, like rbdbackup.sh. */
    public static final long NO_TIMEOUT = 0L;

    private RbdBackupExecutor() {
    }

    // ---------------------------------------------------------------- URI parsing

    /** Port of {@code rbdbackup.sh get_rbd_pool}: the pool of {@code rbd:<pool>/<image>:...}. */
    public static String getPool(String rbdUri) {
        return poolAndImage(rbdUri)[0];
    }

    /**
     * Port of {@code rbdbackup.sh get_rbd_uuid}: the image of {@code rbd:<pool>/<image>:...}.
     * That name is the volume uuid, so take- and restore-side file names line up.
     */
    public static String getImage(String rbdUri) {
        return poolAndImage(rbdUri)[1];
    }

    private static String[] poolAndImage(String rbdUri) {
        String value = rbdUri == null ? "" : rbdUri;
        if (value.startsWith("rbd:")) {
            value = value.substring("rbd:".length());
        }
        int options = value.indexOf(':');
        if (options >= 0) {
            value = value.substring(0, options);
        }
        int slash = value.indexOf('/');
        if (slash < 0) {
            return new String[] {value, ""};
        }
        return new String[] {value.substring(0, slash), value.substring(slash + 1)};
    }

    /**
     * Port of {@code rbdbackup.sh parse_rbd_uri}: extract {@code mon_host}, {@code id} and
     * {@code key} from an escaped qualified RBD URI.
     *
     * <p>{@link KVMPhysicalDisk#RBDStringBuilder(KVMStoragePool, String)} escapes {@code \:} inside
     * {@code mon_host}, {@code \;} between monitors and {@code \=} inside the cephx key. The port
     * mirrors the script's placeholder technique: escaped colons are swapped for a sentinel byte so
     * the field split only sees <em>unescaped</em> colons, then the sentinel is restored before the
     * {@code \;}→{@code ,} and {@code \:}→{@code :} unescaping.
     *
     * @return never {@code null}; absent options come back as empty strings, like the bash globals
     */
    public static RbdAuth parseRbdUri(String rbdUri) {
        String uri = rbdUri == null ? "" : rbdUri;
        String mon = "";
        int marker = uri.indexOf("mon_host=");
        if (marker >= 0) {
            String after = uri.substring(marker + "mon_host=".length());
            String escaped = after.replace("\\:", "\u0001");
            int field = escaped.indexOf(':');
            String monHosts = field >= 0 ? escaped.substring(0, field) : escaped;
            monHosts = monHosts.replace("\u0001", "\\:");
            mon = monHosts.replace("\\;", ",").replace("\\:", ":");
        }
        return new RbdAuth(mon, extractOption(uri, ":id="), extractOption(uri, ":key="));
    }

    /** {@code ${fullpath#*:opt=}} followed by {@code ${value%%:*}} and a {@code \=}→{@code =} unescape. */
    private static String extractOption(String uri, String option) {
        int at = uri.indexOf(option);
        if (at < 0) {
            return "";
        }
        String rest = uri.substring(at + option.length());
        int field = rest.indexOf(':');
        String value = field >= 0 ? rest.substring(0, field) : rest;
        return value.replace("\\=", "=");
    }

    /** argv joined for messages, with any {@code --key} value masked so cephx secrets stay private. */
    private static String describe(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String arg = argv.get(i);
            if ("--key".equals(arg) && i + 1 < argv.size()) {
                sb.append("--key ***");
                i++;
                continue;
            }
            sb.append(arg);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- process execution

    /**
     * Execute {@code argv} with {@link ProcessBuilder} — no shell, so arguments need no quoting.
     * Stdout and stderr are drained on separate threads so neither pipe can fill and deadlock the
     * child (the 64 KB trap a naive {@code waitFor()} hits with large output).
     *
     * @param timeoutMs positive hard limit, or {@link #NO_TIMEOUT} to wait for exit like rbdbackup.sh
     * @return captured stdout
     * @throws CloudRuntimeException on a non-zero exit (message carries captured stderr), a timeout,
     *             or a failure to launch
     */
    public static String run(List<String> argv, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(false).start();
            OutputCollector stdout = new OutputCollector(process.getInputStream());
            OutputCollector stderr = new OutputCollector(process.getErrorStream());
            Thread outThread = new Thread(stdout, "rbd-exec-stdout");
            Thread errThread = new Thread(stderr, "rbd-exec-stderr");
            outThread.start();
            errThread.start();

            int exit = waitFor(process, timeoutMs, argv);
            outThread.join();
            errThread.join();
            if (exit != 0) {
                throw new CloudRuntimeException(String.format("Command [%s] exited with %d: %s", describe(argv), exit,
                        stderr.text().trim()));
            }
            return stdout.text();
        } catch (IOException e) {
            throw new CloudRuntimeException(String.format("Failed to launch [%s]: %s", describe(argv), e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException(String.format("Interrupted running [%s]", describe(argv)), e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * Stream one producer's stdout into one consumer's stdin (the zstd pipes): {@code A ... | B ...}.
     * Each stream is handled on its own thread; both children are awaited and a non-zero exit from
     * <em>either</em> fails the call, which is what {@code set -o pipefail} gave the bash version.
     */
    public static void streamRun(List<String> producerArgv, List<String> consumerArgv, long timeoutMs) {
        Process producer = null;
        Process consumer = null;
        try {
            producer = new ProcessBuilder(producerArgv).redirectErrorStream(false).start();
            consumer = new ProcessBuilder(consumerArgv).redirectErrorStream(false).start();

            final InputStream source = producer.getInputStream();
            final OutputStream target = consumer.getOutputStream();
            Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        copy(source, target);
                    } catch (IOException e) {
                        // A closed downstream pipe means the consumer died; its exit code decides.
                        logger.debug("rbd stream pump stopped: " + e.getMessage());
                    } finally {
                        closeQuietly(target);
                    }
                }
            }, "rbd-stream-pump");
            pump.start();

            OutputCollector producerErr = new OutputCollector(producer.getErrorStream());
            OutputCollector consumerOut = new OutputCollector(consumer.getInputStream());
            OutputCollector consumerErr = new OutputCollector(consumer.getErrorStream());
            Thread producerErrThread = new Thread(producerErr, "rbd-stream-producer-stderr");
            Thread consumerOutThread = new Thread(consumerOut, "rbd-stream-consumer-stdout");
            Thread consumerErrThread = new Thread(consumerErr, "rbd-stream-consumer-stderr");
            producerErrThread.start();
            consumerOutThread.start();
            consumerErrThread.start();

            int producerExit = waitFor(producer, timeoutMs, producerArgv);
            int consumerExit = waitFor(consumer, timeoutMs, consumerArgv);
            pump.join();
            producerErrThread.join();
            consumerOutThread.join();
            consumerErrThread.join();

            if (producerExit != 0) {
                throw new CloudRuntimeException(String.format("Producer [%s | %s] exited with %d: %s",
                        describe(producerArgv), describe(consumerArgv), producerExit, producerErr.text().trim()));
            }
            if (consumerExit != 0) {
                throw new CloudRuntimeException(String.format("Consumer [%s | %s] exited with %d: %s",
                        describe(producerArgv), describe(consumerArgv), consumerExit, consumerErr.text().trim()));
            }
        } catch (IOException e) {
            throw new CloudRuntimeException(String.format("Failed to launch [%s | %s]: %s", describe(producerArgv),
                    describe(consumerArgv), e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException(String.format("Interrupted streaming [%s | %s]", describe(producerArgv),
                    describe(consumerArgv)), e);
        } finally {
            if (producer != null) {
                producer.destroy();
            }
            if (consumer != null) {
                consumer.destroy();
            }
        }
    }

    private static int waitFor(Process process, long timeoutMs, List<String> argv) throws IOException {
        try {
            if (timeoutMs > 0) {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new CloudRuntimeException(String.format("Command [%s] timed out after %d ms", describe(argv),
                            timeoutMs));
                }
            } else {
                process.waitFor();
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException(String.format("Interrupted running [%s]", describe(argv)), e);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                logger.trace("Ignoring close failure: " + e.getMessage());
            }
        }
    }

    /** Drains a process stream to EOF so the child never blocks on a full pipe. */
    private static class OutputCollector implements Runnable {
        private final InputStream stream;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        OutputCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException e) {
                logger.debug("Stream drain stopped: " + e.getMessage());
            } finally {
                closeQuietly(stream);
            }
        }

        String text() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- rbd argv construction

    /**
     * Build {@code rbd [--mon-host m] [--id i] [--key k] <subcommand...>} exactly like the script's
     * {@code rbd_cmd}: each auth flag is present only when the parsed value is non-empty.
     */
    private static List<String> rbdArgv(RbdAuth auth, String... sub) {
        List<String> argv = new ArrayList<>();
        argv.add(RBD);
        RbdAuth effective = auth == null ? new RbdAuth("", "", "") : auth;
        if (!effective.mon.isEmpty()) {
            argv.add("--mon-host");
            argv.add(effective.mon);
        }
        if (!effective.id.isEmpty()) {
            argv.add("--id");
            argv.add(effective.id);
        }
        if (!effective.key.isEmpty()) {
            argv.add("--key");
            argv.add(effective.key);
        }
        argv.addAll(Arrays.asList(sub));
        return argv;
    }

    private static String snapRef(String image, String snap) {
        return snap == null || snap.isEmpty() ? image : image + "@" + snap;
    }

    /** Parse {@code rbd snap ls} stdout: skip the header row, keep the snapshot-name column. */
    private static List<String> listSnapNames(List<String> argv, long timeoutMs) {
        List<String> names = new ArrayList<>();
        String stdout = run(argv, timeoutMs);
        String[] lines = stdout.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = line.split("\\s+");
            if (cols.length >= 2 && !cols[0].isEmpty()) {
                names.add(cols[1]);
            }
        }
        return names;
    }

    // ---------------------------------------------------------------- existence probes

    /**
     * Port of {@code rbdbackup.sh parent_snap_exists}: true when {@code snap} is listed on
     * {@code pool/image}. A failed/absent {@code snap ls} is treated as "missing" (exactly how the
     * script's {@code ... | grep -qx} pipeline behaved under {@code set -o pipefail}), so a Ceph hiccup
     * makes the caller fall back to a full backup rather than abort — the safe direction.
     *
     * <p>Exposed so the pass-2 take wrapper can apply the script's global rule: if
     * {@code mode==incremental} and the parent snapshot is missing on <em>any</em> disk, the whole
     * backup is recorded as a full.
     */
    public static boolean parentExists(RbdAuth auth, String pool, String image, String snap) {
        return snapExists(auth, pool, image, snap);
    }

    /** True when {@code snap} exists on {@code pool/image}; probe errors read as "absent". */
    public static boolean snapExists(RbdAuth auth, String pool, String image, String snap) {
        if (snap == null || snap.isEmpty()) {
            return false;
        }
        try {
            return listSnapNames(rbdArgv(auth, "snap", "ls", "--pool", pool, image), NO_TIMEOUT).contains(snap);
        } catch (CloudRuntimeException e) {
            logger.debug(String.format("snap ls probe failed for %s/%s (treating snapshot '%s' as missing): %s", pool,
                    image, snap, e.getMessage()));
            return false;
        }
    }

    /** True when image {@code pool/image} exists (port of the script's {@code rbd info} guard). */
    public static boolean imageExists(RbdAuth auth, String pool, String image) {
        try {
            run(rbdArgv(auth, "info", "--pool", pool, image), NO_TIMEOUT);
            return true;
        } catch (CloudRuntimeException e) {
            logger.debug(String.format("rbd info probe failed for %s/%s (treating as absent): %s", pool, image,
                    e.getMessage()));
            return false;
        }
    }

    // ---------------------------------------------------------------- backup (take side)

    /**
     * Create snapshot {@code snap} on {@code pool/image} (hard) and protect it so it survives until
     * the chain is deleted (soft — a protect failure is logged and swallowed, exactly like the script).
     */
    public static void createProtectedSnap(RbdAuth auth, String pool, String image, String snap) {
        run(rbdArgv(auth, "snap", "create", "--pool", pool, snapRef(image, snap)), NO_TIMEOUT);
        try {
            run(rbdArgv(auth, "snap", "protect", "--pool", pool, snapRef(image, snap)), NO_TIMEOUT);
        } catch (CloudRuntimeException e) {
            logger.warn(String.format("rbd snap protect failed for %s/%s@%s (non-fatal): %s", pool, image, snap,
                    e.getMessage()));
        }
    }

    /**
     * Export a full backup of {@code pool/image} to {@code dest} and return its size in bytes
     * (compressed size on disk when {@code compress} is set). A non-empty {@code snap} is created and
     * protected first (modern {@code full} mode); an empty {@code snap} exports the live image with no
     * snapshot chain metadata (the script's {@code legacy-full} mode).
     */
    public static long exportFull(RbdAuth auth, String pool, String image, String snap, Path dest, boolean compress) {
        boolean useSnap = snap != null && !snap.isEmpty();
        if (useSnap) {
            createProtectedSnap(auth, pool, image, snap);
        }
        String source = useSnap ? snapRef(image, snap) : image;
        if (compress) {
            streamRun(rbdArgv(auth, "export", "--pool", pool, source, "-", "--no-progress"),
                    Arrays.asList(ZSTD, "-q", "-o", dest.toString(), "-"), NO_TIMEOUT);
        } else {
            run(rbdArgv(auth, "export", "--pool", pool, source, dest.toString()), NO_TIMEOUT);
        }
        return fileSize(dest);
    }

    /**
     * Export a single incremental: create snapshot {@code newSnap} (hard) and stream
     * {@code rbd export-diff --from-snap fromSnap} to {@code dest}; returns the written size.
     */
    public static long exportDiff(RbdAuth auth, String pool, String image, String fromSnap, String newSnap, Path dest,
            boolean compress) {
        run(rbdArgv(auth, "snap", "create", "--pool", pool, snapRef(image, newSnap)), NO_TIMEOUT);
        if (compress) {
            streamRun(rbdArgv(auth, "export-diff", "--from-snap", fromSnap, "--pool", pool,
                    snapRef(image, newSnap), "-", "--no-progress"),
                    Arrays.asList(ZSTD, "-q", "-o", dest.toString(), "-"), NO_TIMEOUT);
        } else {
            run(rbdArgv(auth, "export-diff", "--from-snap", fromSnap, "--pool", pool,
                    snapRef(image, newSnap), dest.toString()), NO_TIMEOUT);
        }
        return fileSize(dest);
    }

    /** Size of the written backup file (compressed bytes on disk), mirroring the script's {@code du -sb}. */
    private static long fileSize(Path dest) {
        try {
            return Files.size(dest);
        } catch (IOException e) {
            logger.warn(String.format("WARNING: could not stat backup file %s: %s", dest, e.getMessage()));
            return 0L;
        }
    }

    // ---------------------------------------------------------------- restore (import side)

    /**
     * True when {@code header} begins with the zstd frame magic {@code 28 B5 2F FD}. Public so tests
     * and callers can assert the sniff without touching Ceph.
     */
    public static boolean isZstd(byte[] header) {
        if (header == null || header.length < 4) {
            return false;
        }
        for (int i = 0; i < ZSTD_MAGIC.length; i++) {
            if ((header[i] & 0xFF) != ZSTD_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** Read the first four bytes of {@code src} and report whether it is a zstd frame. */
    private static boolean isZstdFile(Path src) {
        try (java.io.InputStream in = Files.newInputStream(src)) {
            return isZstd(in.readNBytes(4));
        } catch (IOException e) {
            logger.debug(String.format("Could not sniff magic of %s (treating as raw): %s", src, e.getMessage()));
            return false;
        }
    }

    /**
     * Import a base full backup {@code src} into {@code pool/image}. A zstd-framed file is decompressed
     * into {@code rbd import -}; a raw file is fed as a direct path argument for backward compatibility
     * with existing uncompressed {@code .rbd} backups.
     */
    public static void importBase(RbdAuth auth, String pool, String image, Path src) {
        if (isZstdFile(src)) {
            streamRun(Arrays.asList(ZSTD, "-dc", src.toString()),
                    rbdArgv(auth, "import", "-", "--dest-pool", pool, image), NO_TIMEOUT);
        } else {
            run(rbdArgv(auth, "import", src.toString(), "--dest-pool", pool, image), NO_TIMEOUT);
        }
    }

    /**
     * Import an incremental link {@code src} onto {@code pool/image}. Sniffs zstd vs raw exactly like
     * {@link #importBase}; requires the diff's base snapshot to already exist on the destination
     * (create it first with {@link #ensureSnap}).
     */
    public static void importDiff(RbdAuth auth, String pool, String image, Path src) {
        if (isZstdFile(src)) {
            streamRun(Arrays.asList(ZSTD, "-dc", src.toString()),
                    rbdArgv(auth, "import-diff", "-", "--dest-pool", pool, image), NO_TIMEOUT);
        } else {
            run(rbdArgv(auth, "import-diff", src.toString(), "--dest-pool", pool, image), NO_TIMEOUT);
        }
    }

    /**
     * Create snapshot {@code snap} on the destination {@code pool/image} before the first
     * {@code import-diff} applies ({@code rbd import-diff} requires that base snapshot to exist).
     * Skipped when {@code snap} is empty (a lone base-only restore needs no snapshot). Hard failure.
     */
    public static void ensureSnap(RbdAuth auth, String pool, String image, String snap) {
        if (snap == null || snap.isEmpty()) {
            return;
        }
        run(rbdArgv(auth, "snap", "create", "--pool", pool, snapRef(image, snap)), NO_TIMEOUT);
    }

    // ---------------------------------------------------------------- delete / purge

    /**
     * Remove {@code pool/image} entirely: list its snapshots, unprotect then remove each one (soft,
     * so a snapshot that was never protected or already gone does not abort), then {@code rbd rm} the
     * image (hard). This is the bash {@code restore_vm} pre-import purge, needed because
     * {@code rbd import} cannot overwrite an existing image and {@code rbd rm} refuses while snapshots
     * exist.
     */
    public static void purgeImage(RbdAuth auth, String pool, String image) {
        // Mirror rbdbackup.sh: the whole purge is guarded by an existence check, so restoring onto a
        // fresh/nonexistent image skips it instead of failing on 'rbd rm' of a missing image.
        if (!imageExists(auth, pool, image)) {
            logger.debug(String.format("purgeImage: %s/%s does not exist; nothing to purge", pool, image));
            return;
        }
        List<String> snaps;
        try {
            snaps = listSnapNames(rbdArgv(auth, "snap", "ls", "--pool", pool, image), NO_TIMEOUT);
        } catch (CloudRuntimeException e) {
            logger.debug(String.format("snap ls failed while purging %s/%s (assuming no snapshots): %s", pool, image,
                    e.getMessage()));
            snaps = new ArrayList<>();
        }
        for (String snap : snaps) {
            try {
                run(rbdArgv(auth, "snap", "unprotect", "--pool", pool, snapRef(image, snap)), NO_TIMEOUT);
            } catch (CloudRuntimeException e) {
                logger.debug(String.format("snap unprotect during purge of %s/%s@%s (benign): %s", pool, image, snap,
                        e.getMessage()));
            }
            try {
                run(rbdArgv(auth, "snap", "rm", "--pool", pool, snapRef(image, snap)), NO_TIMEOUT);
            } catch (CloudRuntimeException e) {
                logger.debug(String.format("snap rm during purge of %s/%s@%s (benign): %s", pool, image, snap,
                        e.getMessage()));
            }
        }
        run(rbdArgv(auth, "rm", "--pool", pool, image), NO_TIMEOUT);
    }

    /**
     * Unprotect (soft) then remove (hard) one snapshot on the primary pool — the bash {@code delete-snap}
     * path used when no remaining chain member still needs it. A {@code snap rm} failure throws so the
     * caller can log a leak warning; repo files are already gone at that point.
     */
    public static void unprotectAndRemoveSnap(RbdAuth auth, String pool, String image, String snap) {
        try {
            run(rbdArgv(auth, "snap", "unprotect", "--pool", pool, snapRef(image, snap)), NO_TIMEOUT);
        } catch (CloudRuntimeException e) {
            logger.warn(String.format("delete-snap: snap unprotect failed for %s/%s@%s (may not be protected; "
                    + "continuing): %s", pool, image, snap, e.getMessage()));
        }
        run(rbdArgv(auth, "snap", "rm", "--pool", pool, snapRef(image, snap)), NO_TIMEOUT);
    }
}