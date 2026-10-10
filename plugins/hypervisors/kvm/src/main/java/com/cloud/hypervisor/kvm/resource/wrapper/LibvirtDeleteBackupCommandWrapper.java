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

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.RbdBackupExecutor;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.backup.DeleteBackupCommand;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

@ResourceWrapper(handles = DeleteBackupCommand.class)
public class LibvirtDeleteBackupCommandWrapper extends CommandWrapper<DeleteBackupCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(DeleteBackupCommand command, LibvirtComputingResource libvirtComputingResource) {
        final String backupPath = command.getBackupPath();
        final String backupRepoType = command.getBackupRepoType();
        final String backupRepoAddress = command.getBackupRepoAddress();
        final String mountOptions = command.getMountOptions();

        List<String[]> commands = new ArrayList<>();
        commands.add(new String[]{
                libvirtComputingResource.getNasBackupPath(),
                "-o", "delete",
                "-t", backupRepoType,
                "-s", backupRepoAddress,
                "-m", mountOptions,
                "-p", backupPath
        });

        Pair<Integer, String> result = Script.executePipedCommands(commands, libvirtComputingResource.getCmdsTimeout());

        logger.debug(String.format("Backup delete result: %s , exit code: %s", result.second(), result.first()));

        if (result.first() != 0) {
            logger.debug(String.format("Failed to delete VM backup: %s", result.second()));
            return new BackupAnswer(command, false, result.second());
        }
        // F1c: the repo-file delete (nasbackup.sh, storage-agnostic) succeeded and is unchanged.
        // If the provider flagged a now-unneeded protected snapshot, attempt unprotect+rm on the
        // primary pool via the RBD executor delete-snap. A failure here only logs a WARNING: the repo
        // files are already gone, so a leaked snapshot is strictly better than a stuck delete.
        cleanupRbdSnapshot(command, libvirtComputingResource);
        return new BackupAnswer(command, true, null);
    }

    /**
     * Best-effort unprotect+remove of a now-unneeded protected snapshot on the primary pool via
     * {@link RbdBackupExecutor} (the Java port of the former bash delete-snap). A failure
     * only logs a WARNING: the repo files are already gone, so a leaked snapshot is strictly better
     * than a stuck delete. The executor swallows unprotect failures internally and throws only if
     * the snap rm itself fails, which this catch turns into the same warning as before.
     */
    private void cleanupRbdSnapshot(DeleteBackupCommand command, LibvirtComputingResource libvirtComputingResource) {
        final String snapName = command.getRbdSnapName();
        final String rbdUri = command.getRbdUri();
        if (StringUtils.isBlank(snapName) || StringUtils.isBlank(rbdUri)) {
            return;
        }
        try {
            RbdBackupExecutor.RbdAuth auth = RbdBackupExecutor.parseRbdUri(rbdUri);
            String pool = RbdBackupExecutor.getPool(rbdUri);
            String image = RbdBackupExecutor.getImage(rbdUri);
            RbdBackupExecutor.unprotectAndRemoveSnap(auth, pool, image, snapName);
            logger.debug(String.format("RBD snapshot cleanup succeeded for snap %s", snapName));
        } catch (Exception e) {
            logger.warn(String.format("RBD snapshot cleanup (unprotect+rm) failed for snap %s; repo files already deleted, leaving snapshot leaked: %s",
                    snapName, e.getMessage()));
        }
    }
}
