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

package org.apache.cloudstack.backup;

import com.cloud.agent.api.Command;
import com.cloud.agent.api.LogLevel;

public class DeleteBackupCommand extends Command {
    private String backupPath;
    private String backupRepoType;
    private String backupRepoAddress;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String mountOptions;

    // ADDITIVE, serialization-safe RBD snapshot-cleanup hints. The NAS provider never reads or
    // sets them, so NAS delete behavior is byte-for-byte unchanged; only the RBD provider
    // populates them so the KVM delete wrapper can unprotect+remove a now-unneeded protected
    // snapshot. Both stay null for NAS and for RBD deletes that need no snapshot cleanup.
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String rbdUri;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String rbdSnapName;

    public DeleteBackupCommand(String backupPath, String backupRepoType, String backupRepoAddress, String mountOptions) {
        super();
        this.backupPath = backupPath;
        this.backupRepoType = backupRepoType;
        this.backupRepoAddress = backupRepoAddress;
        this.mountOptions = mountOptions;
    }

    public String getBackupPath() {
        return backupPath;
    }

    public void setBackupPath(String backupPath) {
        this.backupPath = backupPath;
    }

    public String getBackupRepoType() {
        return backupRepoType;
    }

    public void setBackupRepoType(String backupRepoType) {
        this.backupRepoType = backupRepoType;
    }

    public String getBackupRepoAddress() {
        return backupRepoAddress;
    }

    public void setBackupRepoAddress(String backupRepoAddress) {
        this.backupRepoAddress = backupRepoAddress;
    }

    public String getMountOptions() {
        return mountOptions == null ? "" : mountOptions;
    }

    public void setMountOptions(String mountOptions) {
        this.mountOptions = mountOptions;
    }

    public String getRbdUri() {
        return rbdUri;
    }

    public void setRbdUri(String rbdUri) {
        this.rbdUri = rbdUri;
    }

    public String getRbdSnapName() {
        return rbdSnapName;
    }

    public void setRbdSnapName(String rbdSnapName) {
        this.rbdSnapName = rbdSnapName;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
