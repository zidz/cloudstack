# RBD Backup Provider

A CloudStack backup provider that stores VM disk backups as **Ceph RBD** images and
exports them to a backup repository, with **incremental** backups built on RBD
snapshot diffs (`rbd export-diff`). It targets KVM VMs whose volumes live on an
RBD primary storage pool.

This file documents how to **enable and wire up** the provider, including the
manual database steps that the stock CloudStack API cannot perform today, so that
anyone evaluating this work (or building their own provider) can reproduce it
exactly.

---

## 1. What the provider does

| Operation | Mechanism |
| --- | --- |
| **Full backup** | `rbd snap create` + `rbd snap protect` + `rbd export` → `<diskType>.<volPath>.rbd` in the repo |
| **Incremental backup** | `rbd export-diff --from-snap <parent>` → `<diskType>.<volPath>.<ts>.rbdiff` (only changed extents) |
| **Restore** | `rbd import` the base full, recreate the base snapshot, then `rbd import-diff` each incremental link in order |
| **Restore-as-template** | Rebuild a chain into a **new, freely-named** RBD image (never touches the source VM), optionally `rbd export` a raw file to register as a template |
| **Delete** | Best-effort `rbd snap unprotect`/`snap rm` when no remaining chain member needs the snapshot |

The host-side engine is `scripts/vm/hypervisor/kvm/rbdbackup.sh`; the management
side is `RbdBackupProvider.java`. The KVM agent wrappers route a VM to
`rbdbackup.sh` **only when every one of its pools is RBD**; NAS/qcow2 VMs keep
using `nasbackup.sh` unchanged.

---

## 2. Prerequisites

- **Ceph** cluster reachable by the KVM hosts, with an RBD pool, e.g. `cloudstack`
  (`rbd pool init cloudstack`).
- A **Ceph client user** for CloudStack, e.g. `client.cloudstack`:
  ```
  mon 'allow r'
  osd 'allow class-read object_prefix rbd_children, allow rwx pool=cloudstack'
  ```
- An **RBD primary storage pool** added to CloudStack with a `rbd://` URL, e.g.
  `rbd://<mon-host>:6789/cloudstack/<user>/<key>`.
- A **backup repository** — a shared filesystem the management server and hosts
  can mount (NFS here). Example export:
  ```
  /export/sec  *(rw,async,no_root_squash,no_subtree_check)
  ```
- **KVM** hypervisor host(s) with the `rbd` client tools installed.

---

## 3. Enable the backup framework (configuration)

Three global settings control the provider. Set them via the API
(`updateConfiguration`) or the DB (`configuration` table):

| Setting | Value | Meaning |
| --- | --- | --- |
| `backup.framework.enabled` | `true` | Master switch. **Defaults to `false`**, which hides all backup APIs. |
| `backup.framework.provider.plugin` | `rbd` | Selects this provider (the registry also contains `dummy`, `kboss`, `nas`, `networker`). |
| `rbd.backup.incremental.enabled` | `true` | Enables the incremental chain. **Defaults to `false`**, so every backup is a legacy full. |

```sql
UPDATE cloud.configuration SET value='true'  WHERE name='backup.framework.enabled';
UPDATE cloud.configuration SET value='rbd'    WHERE name='backup.framework.provider.plugin';
UPDATE cloud.configuration SET value='true'   WHERE name='rbd.backup.incremental.enabled';
```

Restart `cloudstack-management` after changing these.

---

## 4. Create the backup repository and offering (manual DB)

> **Why manual?** CloudStack's `createBackupOffering` API is **hard-gated to the
> KBOSS provider** (`BackupManagerImpl` rejects any other provider), and there is
> no `createBackupRepository` API at all. So for a non-KBOSS provider like `rbd`
> the offering and repository must be inserted directly. `createBackup` itself
> resolves the provider from the **offering's own `provider` column**, so once the
> rows exist the normal API/UI backup path works unchanged.

### 4.1 The linkage rule

```
backup_offering.external_id  ==  backup_repository.uuid
```

`createBackup` finds the repository through the offering's `external_id`. Both
rows must carry `provider='rbd'` and the same `zone_id`.

### 4.2 Insert the repository

`backup_repository.mount_opts` is **NOT NULL**. If the KMS plugin is installed it
will try to *decrypt* this column, so leave it an **empty string** (KMS skips
empty values; `rbdbackup.sh` treats empty as "no extra mount options").

```sql
-- Look up the numeric zone id first:
--   SELECT id FROM cloud.data_center WHERE uuid='<ZONE_UUID>';
INSERT INTO cloud.backup_repository
  (uuid, name, zone_id, provider, type, address, mount_opts, capacity_bytes, created)
VALUES
  (UUID(), 'rbd-repo', <ZONE_ID>, 'rbd', 'nfs', '<HOST>:/export/sec', '', 0, UTC_TIMESTAMP());
```

| Column | Notes |
| --- | --- |
| `provider` | `rbd` — associates the repo with this provider. |
| `type` | Storage transport: `nfs` (or `smb`/`imagestore` as supported). |
| `address` | `host:/export/path` for NFS. |
| `mount_opts` | **NOT NULL**; use `''` under KMS. |

### 4.3 Insert the offering

Note the DB column is **`user_driven_backup`** (the API/UI name for the same
concept is `allowuserdrivenbackups`).

```sql
-- Capture the repository uuid you just created:
--   SELECT uuid FROM cloud.backup_repository WHERE name='rbd-repo' AND removed IS NULL;
INSERT INTO cloud.backup_offering
  (uuid, name, description, external_id, user_driven_backup, zone_id, provider, created)
VALUES
  (UUID(), 'rbd-off', 'rbd incremental test offering',
   '<REPO_UUID>', 1, <ZONE_ID>, 'rbd', UTC_TIMESTAMP());
```

| Column | Notes |
| --- | --- |
| `external_id` | **Must equal the repository's `uuid`** (the linkage). |
| `user_driven_backup` | `1` lets users take ad-hoc backups / schedules. |
| `provider` | `rbd` — `createBackup` reads the provider from here. |

Restart `cloudstack-management` so the offering/repository are picked up.

---

## 5. Take and restore backups

Once the offering exists, the **normal** API and UI paths work — no further
provider-specific calls:

1. `assignVirtualMachineToBackupOffering` (UI: instance → *Assign Backup Offering*).
2. `createBackup` (UI: *Take Backup*). First backup is **FULL**; the next is
   **INCREMENTAL** (tiny if nothing wrote to disk in between).
3. `restoreBackup` (UI: Storage → Backups → *Restore Backup*). **The VM must be
   Stopped** — the RBD image is overwritten in place.
4. `createVMFromBackup` (UI: *Create Instance From Backup*) deploys a **new,
   freely-named** VM from a backup — the framework-native "restore to a new VM".

---

## 6. Verify

- **In the UI:** the Backups list **Type** and **Size** columns — a genuine
  incremental shows `Incremental` and a size far smaller than the full.
- **On the repository:** `.rbd` files (full) and `.rbdiff` files (incremental).
- **On Ceph:** `rbd -p cloudstack snap ls <volume-uuid>` shows the protected
  snapshots that anchor the chain.
- **Script-level (no CloudStack needed):**
  ```
  scripts/vm/hypervisor/kvm/rbdbackup.sh --selftest
  ```

---

## 7. Gotchas and design notes

- **Restore resets the chain anchor.** A restore calls `clearVmActiveSnap`, so the
  *next* backup after a restore is a **full** again. To observe an incremental,
  take two backups back-to-back **before** restoring.
- **Restore needs the VM stopped.** Restoring a Running VM fails because the
  destination RBD image is in use.
- **Restore pairs volumes by UUID, not deviceId.** Each backed volume is matched
  to the current VM volume with the **same UUID** (the RBD image name *is* the
  volume UUID), so a restore still works if the VM's `device_id` values have
  drifted since the backup. It fails fast only if a backed volume is no longer
  attached.
- **`mount_opts` NOT NULL + KMS.** With the KMS plugin installed, a non-empty
  `mount_opts` is treated as ciphertext and fails to decrypt; use `''`.
- **Silent fallback.** If `rbdbackup.sh` is not deployed alongside `nasbackup.sh`,
  the wrappers fall back to `nasbackup.sh` rather than erroring — deploy both
  scripts together.
- **Global build coupling.** The provider is registered unconditionally in
  `plugins/pom.xml` and its jar is copied in `debian/rules`, so a defect in this
  module breaks the whole build / management startup, not just RBD.

---

## 8. Reference values from the test deployment

These are the concrete values used to validate the provider on a single
Debian 13 / CloudStack 4.23.0.0 / Ceph Reef node (disposable test host). They are
examples, not required values.

| Item | Value |
| --- | --- |
| Zone | `testzone` (`2aa527d4-4fcb-428a-8b72-d8f2a7f2dfc9`) |
| RBD pool | `cloudstack` |
| Repository | `rbd-repo`, type `nfs`, address `10.0.3.213:/export/sec`, uuid `11ef4e12-c126-11f1-9006-88aedd0fb79c` |
| Offering | `rbd-off`, provider `rbd`, `external_id` = repo uuid above, `user_driven_backup=1`, uuid `459b5e49-c125-11f1-84c6-88aedd0fb79c` |
| Config | `backup.framework.enabled=true`, `backup.framework.provider.plugin=rbd`, `rbd.backup.incremental.enabled=true` |

Verified end-to-end through the management API: full backup → incremental backup
→ restore (byte-identical disk), plus the restore-as-template path.
