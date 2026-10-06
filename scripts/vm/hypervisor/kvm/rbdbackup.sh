#!/usr/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set -euo pipefail

# CloudStack B&R Ceph RBD Backup and Recovery Tool for KVM.
#
# Drives the `rbd` CLI on the KVM host (NOT librbd-JNA diff). Full backup = `rbd export` of a
# freshly created, protected snapshot. Incremental = `rbd export-diff --from-snap <parentSnap>`.
# Restore = `rbd import` the base full then `rbd import-diff` each incremental link in order.
# Mirrors the argument/env surface of nasbackup.sh so the KVM command wrapper can branch to it.

### Declare variables ###

OP=""
VM=""
REPO_TYPE=""
REPO_ADDRESS=""
MOUNT_OPTS=""
BACKUP_DIR=""
DISK_PATHS=""
QUIESCE=""
# Incremental backup parameters (all optional; legacy callers omit them)
MODE=""               # "full", "incremental", or "legacy-full"; empty => legacy full-only
SNAP_NEW=""           # RBD snapshot name to create with this backup (e.g. "backup-1711586400")
SNAP_PARENT=""        # For incremental: parent snapshot name to diff from (--from-snap)
PARENT_SNAP=""        # Alias of SNAP_PARENT (set by the RBD provider's parentSnapName field)
PARENT_PATHS=""       # For incremental: comma-separated list of parent backup file paths,
                      # one per VM volume in the same order as DISK_PATHS.
RESTORE_FILES=""      # For restore: comma-separated list of backup files (base full first,
                      # then each incremental link) to replay onto the restore target.
DISK_TYPES=""         # For take: comma-separated per-disk type list ("root"/"datadisk") in
                      # deviceId order, supplied by the provider so take-side naming matches
                      # restore-side vi.getType(). Falls back to positional root/datadisk when
                      # absent or shorter than the disk list.
SNAP_NAME=""          # For delete-snap: the protected snapshot name to unprotect+rm on the
                      # primary pool (passed by the RBD delete wrapper only when no remaining
                      # chain member still needs it). Unused by backup/restore/delete.
BASE_SNAP=""          # For restore: the base snapshot name the first .rbdiff was exported
                      # --from-snap. rbd import-diff requires the destination image to already
                      # contain this snapshot, so restore creates it on the destination after the
                      # base import. Empty for a single-file (base-only) restore with no diffs.
verb=0
logFile="/var/log/cloudstack/agent/agent.log"
UNMOUNT_TIMEOUT=60
EXIT_CLEANUP_FAILED=20

log() {
  [[ "${verb:-0}" -eq 1 ]] && builtin echo "$@"
  if [[ "$1" == "-ne"  || "$1" == "-e" || "$1" == "-n" ]]; then
    builtin echo -e "$(date '+%Y-%m-%d %H-%M-%S>')" "${@: 2}" >> "$logFile"
  else
    builtin echo "$(date '+%Y-%m-%d %H-%M-%S>')" "$@" >> "$logFile"
  fi
}

# rbd connection options are parsed per-disk from the qualified RBD URI produced by
# KVMPhysicalDisk.RBDStringBuilder. That URI escapes ':' and ';' inside mon_host and '=' inside
# the cephx key, so we unescape them and pass --mon-host/--id/--key directly to the rbd CLI
# instead of synthesizing a ceph.conf. Globals are reset by parse_rbd_uri on every call.
RBD_MON=""
RBD_ID=""
RBD_KEY=""

# parse_rbd_uri <fullpath> -> sets RBD_MON/RBD_ID/RBD_KEY from the escaped RBD URI.
parse_rbd_uri() {
  local fullpath="$1"
  local after prot monp id k
  after="${fullpath#*mon_host=}"
  if [[ "$after" == "$fullpath" ]]; then
    RBD_MON=""
  else
    prot="${after//\\:/$(printf '\001')}"
    monp="${prot%%:*}"
    monp="${monp//$'\001'/\\:}"
    RBD_MON="${monp//\\;/,}"
    RBD_MON="${RBD_MON//\\:/:}"
  fi
  if [[ "$fullpath" == *":id="* ]]; then
    id="${fullpath#*:id=}"
    RBD_ID="${id%%:*}"
    RBD_ID="${RBD_ID//\\=/=}"
  else
    RBD_ID=""
  fi
  if [[ "$fullpath" == *":key="* ]]; then
    k="${fullpath#*:key=}"
    RBD_KEY="${k%%:*}"
    RBD_KEY="${RBD_KEY//\\=/=}"
  else
    RBD_KEY=""
  fi
}

rbd_cmd() {
  local a=()
  [[ -n "${RBD_MON:-}" ]] && a+=(--mon-host "$RBD_MON")
  [[ -n "${RBD_ID:-}" ]]  && a+=(--id "$RBD_ID")
  [[ -n "${RBD_KEY:-}" ]] && a+=(--key "$RBD_KEY")
  # ${a[@]+...} guards an EMPTY array under `set -u` on bash < 4.4 (RHEL7/CentOS7 agents),
  # where a bare "${a[@]}" on an empty array aborts with 'unbound variable'.
  rbd ${a[@]+"${a[@]}"} "$@"
}

# --selftest: validate the escaped-URI parser without requiring rbd, mount, or Ceph.
# Must run before the argument parser so --selftest is not treated as an invalid option.
if [[ "${1:-}" == "--selftest" ]]; then
  fullpath='rbd:pool/img:mon_host=10.0.1.2\:6789\;10.0.1.3\:6789:auth_client_required=cephx:id=admin:key=AQBr5\=\=:rbd_default_format=2'
  parse_rbd_uri "$fullpath"
  echo "MON=$RBD_MON ID=$RBD_ID KEY=$RBD_KEY"
  (
    IFS=','
    rbd() {
      local args=("$@")
      local argc="${#args[@]}"
      local i
      for ((i = 0; i < argc; i++)); do
        if [[ "${args[$i]}" == "--mon-host" && "${args[$((i + 1))]:-}" == "$RBD_MON" ]]; then
          echo "ARGV_OK argc=$argc"
          return 0
        fi
      done
      echo "ARGV_BAD argc=$argc"
      return 1
    }
    rbd_cmd snap ls --pool pool image
  )
  # delete-snap argv construction (no rbd call): the wrapper invokes
  #   rbdbackup.sh -o delete-snap -d <rbd-uri> --snap-name <snap>
  # which runs `rbd_cmd snap unprotect/rm --pool <pool> <uuid>@<snap>`. Print the argv shape
  # (with the parsed mon/id/key prefix) to prove the delete-snap path builds a valid rbd argv.
  (
    rbd() {
      echo "DELETE_SNAP_ARGV_OK argc=$# argv=$*"
      return 0
    }
    rbd_cmd snap unprotect --pool pool image@backup-1
  )
  exit 0
fi

### Path parsing (mirrors nasbackup.sh get_ceph_uuid_from_path) ###
# disk for rbd => rbd:<pool>/<uuid>:mon_host=<monitor_host>...
# sample: rbd:cloudstack/53d5c355-d726-4d3e-9422-046a503a0b12:mon_host=10.0.1.2:6789
get_rbd_pool() {
  local fullpath="$1"
  local after_scheme="${fullpath#rbd:}"
  local pool_image="${after_scheme%%:*}"   # <pool>/<uuid>
  echo "${pool_image%%/*}"
}

get_rbd_uuid() {
  # The embedded uuid equals the provider's VolumeInfo.path, so take/restore filenames match.
  local fullpath="$1"
  local after_scheme="${fullpath#rbd:}"
  local pool_image="${after_scheme%%:*}"   # <pool>/<uuid>
  echo "${pool_image##*/}"
}

### Mount / cleanup (mirrors nasbackup.sh) ###

mount_operation() {
  mount_point=$(mktemp -d -t csbackup.XXXXX)
  dest="$mount_point/${BACKUP_DIR}"
  if [[ "${REPO_TYPE}" == "cifs" ]]; then
    MOUNT_OPTS="${MOUNT_OPTS},nobrl"
  fi
  if ! mount -t "${REPO_TYPE}" "${REPO_ADDRESS}" "${mount_point}" $([[ -n "${MOUNT_OPTS}" ]] && echo -o "${MOUNT_OPTS}") 2>>"$logFile"; then
    echo "Failed to mount ${REPO_TYPE} store"
    exit 1
  fi
  log -ne "Successfully mounted ${REPO_TYPE} store"
}

cleanup() {
  local status=0
  rm -rf "$dest" || { echo "Failed to delete $dest"; status=1; }
  umount "$mount_point" || { echo "Failed to unmount $mount_point"; status=1; }
  rmdir "$mount_point" || { echo "Failed to remove mount point $mount_point"; status=1; }

  if [[ $status -ne 0 ]]; then
    echo "Backup cleanup failed"
    exit $EXIT_CLEANUP_FAILED
  fi
}

parent_snap_exists() {
  local pool="$1" uuid="$2" snap="$3"
  rbd_cmd snap ls --pool "$pool" "$uuid" 2>/dev/null | awk 'NR>1{print $2}' | grep -qx "$snap"
}

### Backup ###

backup_vm() {
  mount_operation
  mkdir -p "$dest" || { echo "Failed to create backup directory $dest"; exit 1; }

  local effective_mode="${MODE:-legacy-full}"
  case "$effective_mode" in
    incremental|full|legacy-full)
      ;;
    *)
      echo "Unknown mode: $effective_mode"
      cleanup
      exit 1
      ;;
  esac

  # Timestamp segment for incremental file names (matches the provider's externalId basename).
  local ts="${BACKUP_DIR##*/}"

  # Per-disk mon/id/key are parsed inside the take loop so each disk's own URI applies.
  local -a disk_types_arr=()
  if [[ -n "$DISK_TYPES" ]]; then
    IFS=',' read -ra disk_types_arr <<< "$DISK_TYPES"
    unset IFS
  fi
  local name="root"
  local disk_idx=0
  local parent_snap="${PARENT_SNAP:-$SNAP_PARENT}"

  # Global fallback (D3): an incremental is only valid while the parent snapshot exists on EVERY
  # disk's pool. If ANY disk lacks it, self-heal the WHOLE backup to full (emit the marker once)
  # so the orchestrator records a full and no .rbdiff is ever written without its .rbd base.
  if [[ "$effective_mode" == "incremental" ]]; then
    local fallback=0
    if [[ -z "$parent_snap" ]]; then
      fallback=1
    else
      IFS=','
      for disk in $DISK_PATHS; do
        if [[ "$disk" != rbd:* ]]; then
          continue
        fi
        local p u
        p="$(get_rbd_pool "$disk")"
        u="$(get_rbd_uuid "$disk")"
        parse_rbd_uri "$disk"
        if ! parent_snap_exists "$p" "$u" "$parent_snap"; then
          log -e "incremental: parent snapshot $parent_snap missing on $p/$u — falling back to full for all disks"
          fallback=1
          break
        fi
      done
      unset IFS
    fi
    if [[ "$fallback" -eq 1 ]]; then
      echo "INCREMENTAL_FALLBACK=true"
      effective_mode="full"
    fi
  fi

  IFS=','
  for disk in $DISK_PATHS; do
    if [[ "$disk" != rbd:* ]]; then
      echo "rbdbackup.sh only supports rbd: disk paths, got: $disk"
      cleanup
      exit 1
    fi
    local pool uuid
    pool="$(get_rbd_pool "$disk")"
    uuid="$(get_rbd_uuid "$disk")"
    parse_rbd_uri "$disk"

    # Type-driven naming: prefer the provider-supplied --disk-types list (deviceId order) so
    # take-side filenames match restore-side vi.getType(); fall back to positional root/datadisk
    # only when the list is absent or shorter than the disk list.
    if [[ $disk_idx -lt ${#disk_types_arr[@]} && -n "${disk_types_arr[$disk_idx]}" ]]; then
      name="${disk_types_arr[$disk_idx]}"
    elif [[ $disk_idx -eq 0 ]]; then
      name="root"
    else
      name="datadisk"
    fi

    # The global fallback above already decided the mode for every disk; a fallback backup is
    # forced to full for ALL disks, so no per-disk re-check (and no per-disk marker) is needed.
    local disk_mode="$effective_mode"

    if [[ "$disk_mode" == "incremental" ]]; then
      local new_snap="${SNAP_NEW:-backup-$(date +%s)}"
      local out="$dest/$name.$uuid.$ts.rbdiff"
      if ! rbd_cmd snap create --pool "$pool" "$uuid@$new_snap" >>"$logFile" 2>&1; then
        echo "rbd snap create failed for $pool/$uuid@$new_snap"
        cleanup
        exit 1
      fi
      if ! rbd_cmd export-diff --from-snap "$parent_snap" --pool "$pool" "$uuid@$new_snap" "$out" >>"$logFile" 2>&1; then
        echo "rbd export-diff failed for $pool/$uuid@$new_snap"
        cleanup
        exit 1
      fi
      local sz
      sz=$(du -sb "$out" 2>>"$logFile" | cut -f1) || { log -ne "WARNING: du failed for $out"; sz=0; }
      echo "$sz"
    else
      # full / legacy-full: create + protect a snapshot, then export it.
      local out="$dest/$name.$uuid.rbd"
      local snap="${SNAP_NEW:-backup-$(date +%s)}"
      if [[ "$effective_mode" != "legacy-full" ]]; then
        if ! rbd_cmd snap create --pool "$pool" "$uuid@$snap" >>"$logFile" 2>&1; then
          echo "rbd snap create failed for $pool/$uuid@$snap"
          cleanup
          exit 1
        fi
        # Protect so the snapshot (and thus the diff base) survives until the chain is deleted.
        rbd_cmd snap protect --pool "$pool" "$uuid@$snap" >>"$logFile" 2>&1 || \
          log -e "WARNING: rbd snap protect failed for $pool/$uuid@$snap (non-fatal)"
        if ! rbd_cmd export --pool "$pool" "$uuid@$snap" "$out" >>"$logFile" 2>&1; then
          echo "rbd export failed for $pool/$uuid@$snap"
          cleanup
          exit 1
        fi
      else
        # Legacy full: export the live image, no snapshot chain metadata.
        if ! rbd_cmd export --pool "$pool" "$uuid" "$out" >>"$logFile" 2>&1; then
          echo "rbd export failed for $pool/$uuid"
          cleanup
          exit 1
        fi
      fi
      local sz
      sz=$(du -sb "$out" 2>>"$logFile" | cut -f1) || { log -ne "WARNING: du failed for $out"; sz=0; }
      echo "$sz"
    fi

    disk_idx=$((disk_idx + 1))
  done
  unset IFS
  sync
  # Success tail (mirrors nasbackup.sh): unmount the repo WITHOUT deleting $dest (it holds the
  # just-written backup). cleanup() (rm -rf dest) is reserved for FAILURE paths only.
  timeout "$UNMOUNT_TIMEOUT" umount "$mount_point" 2>>"$logFile" || { log "WARNING: umount of $mount_point failed or timed out"; true; }
  rmdir "$mount_point" 2>>"$logFile" || { log "WARNING: rmdir of $mount_point failed"; true; }
}

### Restore (base full via rbd import, then rbd import-diff per link) ###

# Explicit-chain restore (the ONLY restore path). The backup directory is ALREADY MOUNTED by the
# KVM wrapper, which passes each chain member as an ALREADY-ABSOLUTE file path via --restore-files
# (base .rbd first, then each .rbdiff in root->leaf order). The source volume uuid is embedded in
# the file names; -d carries ONLY the import DESTINATION (rbd:<pool>/<uuid>:mon_host=...), so we
# parse the destination pool/uuid from -d and parse its mon_host/id/key via parse_rbd_uri. No globbing.
restore_vm() {
  if [[ -z "$RESTORE_FILES" ]]; then
    echo "Restore requires --restore-files with an explicit ordered chain"
    exit 1
  fi

  local dest_disk="${DISK_PATHS%%,*}"
  parse_rbd_uri "$dest_disk"

  local pool uuid
  pool="$(get_rbd_pool "$dest_disk")"
  uuid="$(get_rbd_uuid "$dest_disk")"

  local -a files=()
  IFS=',' read -ra files <<< "$RESTORE_FILES"
  unset IFS

  local idx=0
  for f in "${files[@]}"; do
    if [[ ! -f "$f" ]]; then
      echo "Restore backup file does not exist: $f"
      exit 1
    fi
    if [[ $idx -eq 0 ]]; then
      # Restore-to-existing-VM: the destination image is the live VM's current volume. A full
      # restore replaces the whole disk, and `rbd import` cannot overwrite an existing image
      # (fails with (17) File exists), so purge any existing image first. `rbd rm` refuses while
      # snapshots exist, so unprotect+remove them before removing the image.
      if rbd_cmd info --pool "$pool" "$uuid" >/dev/null 2>&1; then
        for s in $(rbd_cmd snap ls --pool "$pool" "$uuid" 2>/dev/null | awk 'NR>1 {print $2}'); do
          # Benign failures (already unprotected / already gone) must not abort under set -e.
          rbd_cmd snap unprotect --pool "$pool" "$uuid@$s" >>"$logFile" 2>&1 || true
          rbd_cmd snap rm --pool "$pool" "$uuid@$s" >>"$logFile" 2>&1 || true
        done
        if ! rbd_cmd rm --pool "$pool" "$uuid" >>"$logFile" 2>&1; then
          echo "rbd rm failed for existing $pool/$uuid (required before base import)"
          exit 1
        fi
      fi
      # Base full: import the raw image into the destination RBD image.
      if ! rbd_cmd import "$f" --pool "$pool" "$uuid" >>"$logFile" 2>&1; then
        echo "rbd import failed for $f into $pool/$uuid"
        exit 1
      fi
      # rbd import-diff requires the destination image to ALREADY contain the snapshot the first
      # .rbdiff was exported --from-snap. Create that base snapshot here so the first diff applies;
      # each subsequent import-diff then creates the end snapshot the next link needs, so the whole
      # chain self-sustains from this single creation. Only needed when a diff chain follows.
      if [[ -n "$BASE_SNAP" ]]; then
        if ! rbd_cmd snap create --pool "$pool" "$uuid@$BASE_SNAP" >>"$logFile" 2>&1; then
          echo "rbd snap create failed for $pool/$uuid@$BASE_SNAP (required as diff base)"
          exit 1
        fi
      fi
    else
      # Incremental link: replay the diff onto the destination image.
      if ! rbd_cmd import-diff "$f" --pool "$pool" "$uuid" >>"$logFile" 2>&1; then
        echo "rbd import-diff failed for $f into $pool/$uuid"
        exit 1
      fi
    fi
    idx=$((idx + 1))
  done

  sync
}

delete_backup() {
  mount_operation
  rm -frv "$dest"
  sync
  umount "$mount_point"
  rmdir "$mount_point"
}

# delete-snap: unprotect + remove a now-unneeded protected snapshot on the primary pool. Invoked
# by the RBD delete wrapper ONLY when no remaining chain member still needs the snapshot. Operates
# directly on the pool (no repo mount). Unprotect failures are ignored (the snap may never have
# been protected); a snap rm failure exits non-zero so the wrapper logs a WARNING (repo files are
# already gone, so a leaked snap is better than a stuck delete).
delete_snap() {
  if [[ -z "$DISK_PATHS" || -z "$SNAP_NAME" ]]; then
    echo "delete-snap requires -d <rbd-uri> and --snap-name <snap>"
    exit 1
  fi
  local disk="$DISK_PATHS"
  local pool uuid
  pool="$(get_rbd_pool "$disk")"
  uuid="$(get_rbd_uuid "$disk")"
  parse_rbd_uri "$disk"
  rbd_cmd snap unprotect --pool "$pool" "$uuid@$SNAP_NAME" >>"$logFile" 2>&1 || \
    log -e "delete-snap: snap unprotect failed for $pool/$uuid@$SNAP_NAME (may not be protected; continuing)"
  if ! rbd_cmd snap rm --pool "$pool" "$uuid@$SNAP_NAME" >>"$logFile" 2>&1; then
    echo "delete-snap: rbd snap rm failed for $pool/$uuid@$SNAP_NAME"
    exit 1
  fi
}

get_backup_stats() {
  mount_operation
  echo "$mount_point"
  df -P "$mount_point" 2>/dev/null | awk 'NR==2 {print $2, $3}'
  umount "$mount_point"
  rmdir "$mount_point"
}

function usage {
  echo ""
  echo "Usage: $0 -o <operation> -v <domain name> -t <storage type> -s <storage address> -m <mount options> -p <backup path> -d <disks path> -q <true|false>"
  echo "         [-M <full|incremental|legacy-full>] [--snap-new <name>] [--snap-parent <name>] [--parent-snap <name>] [--parent-paths <p1,p2,...>] [--restore-files <f1,f2,...>] [--disk-types <t1,t2,...>]"
  echo ""
  echo "Incremental backup options (Ceph RBD primary storage only):"
  echo "  -M full          Create + protect a snapshot (--snap-new) and rbd export it."
  echo "  -M incremental   rbd export-diff --from-snap <parent-snap> of a new snapshot --snap-new."
  echo "                   Falls back to a full export (and prints INCREMENTAL_FALLBACK=true) if the"
  echo "                   parent snapshot no longer exists on the pool."
  echo "  Without -M, behaves as legacy full-only backup with no snapshot chain."
  echo ""
  exit 1
}

while [[ $# -gt 0 ]]; do
  case $1 in
    -o|--operation)
      OP="$2"; shift; shift ;;
    -v|--vm)
      VM="$2"; shift; shift ;;
    -t|--type)
      REPO_TYPE="$2"; shift; shift ;;
    -s|--storage)
      REPO_ADDRESS="$2"; shift; shift ;;
    -m|--mount)
      MOUNT_OPTS="$2"; shift; shift ;;
    -p|--path)
      BACKUP_DIR="$2"; shift; shift ;;
    -q|--quiesce)
      QUIESCE="$2"; shift; shift ;;
    -d|--diskpaths)
      DISK_PATHS="$2"; shift; shift ;;
    -M|--mode)
      MODE="$2"; shift; shift ;;
    --snap-new|--bitmap-new)
      SNAP_NEW="$2"; shift; shift ;;
    --snap-parent|--bitmap-parent)
      SNAP_PARENT="$2"; shift; shift ;;
    --parent-snap)
      PARENT_SNAP="$2"; shift; shift ;;
    --parent-paths)
      PARENT_PATHS="$2"; shift; shift ;;
    --restore-files)
      RESTORE_FILES="$2"; shift; shift ;;
    --disk-types)
      DISK_TYPES="$2"; shift; shift ;;
    --snap-name)
      SNAP_NAME="$2"; shift; shift ;;
    --base-snap)
      BASE_SNAP="$2"; shift; shift ;;
    -h|--help)
      usage; shift ;;
    *)
      echo "Invalid option: $1"
      usage
      ;;
  esac
done

if [[ "$OP" == "backup" ]]; then
  backup_vm
elif [[ "$OP" == "restore" ]]; then
  restore_vm
elif [[ "$OP" == "delete" ]]; then
  delete_backup
elif [[ "$OP" == "delete-snap" ]]; then
  delete_snap
elif [[ "$OP" == "stats" ]]; then
  get_backup_stats
else
  echo "Unknown operation: $OP"
  usage
fi
