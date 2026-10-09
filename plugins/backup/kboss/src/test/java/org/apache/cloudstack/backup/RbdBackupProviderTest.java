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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.host.dao.HostDao;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.ResourceLimitService;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupRepositoryDao;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

@RunWith(MockitoJUnitRunner.Silent.class)
public class RbdBackupProviderTest {

    @Spy
    @InjectMocks
    private RbdBackupProvider rbdBackupProvider;

    @Mock
    private BackupDao backupDao;

    @Mock
    private BackupRepositoryDao backupRepositoryDao;

    @Mock
    private VMInstanceDao vmInstanceDao;

    @Mock
    private AgentManager agentManager;

    @Mock
    private VolumeDao volumeDao;

    @Mock
    private HostDao hostDao;

    @Mock
    private BackupManager backupManager;

    @Mock
    private ResourceManager resourceManager;

    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;

    @Mock
    private VMSnapshotDao vmSnapshotDao;

    @Mock
    private VMSnapshotDetailsDao vmSnapshotDetailsDao;

    @Mock
    private BackupDetailsDao backupDetailsDao;

    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Mock
    private ResourceLimitService resourceLimitMgr;

    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private DataStoreManager dataStoreMgr;

    private static final long VM_ID = 1L;
    private static final long ZONE_ID = 10L;
    private static final long POOL_ID = 20L;
    private static final long PARENT_BACKUP_ID = 30L;

    private VirtualMachine runningVm() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getDataCenterId()).thenReturn(ZONE_ID);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getInstanceName()).thenReturn("i-2-1234-VM");
        return vm;
    }

    private void stubAllVolumesOnRbd() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(POOL_ID);
        // F4(i) compares the current VM's volume-path set against the parent backup's backed set;
        // an incremental requires them to match, so the current volume path mirrors the parent's
        // backed "volpath" used by the incremental test.
        when(volume.getPath()).thenReturn("volpath");
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(volume));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        when(primaryDataStoreDao.findById(POOL_ID)).thenReturn(pool);
    }

    private BackupDetailVO detail(String value) {
        // Real value object: this helper is called inside thenReturn(...), so stubbing here
        // would nest a when() inside an open stubbing and fail with UnfinishedStubbing.
        return new BackupDetailVO(0L, "test-detail", value, true);
    }

    @Test
    public void testDecideChainConfigOffReturnsLegacyFull() {
        VirtualMachine vm = runningVm();
        doReturn(false).when(rbdBackupProvider).isIncrementalEnabled(any());

        RbdBackupProvider.ChainDecision decision = rbdBackupProvider.decideChain(vm);

        Assert.assertEquals(RbdBackupChainKeys.TYPE_LEGACY_FULL, decision.mode);
        Assert.assertNull(decision.snapNew);
        Assert.assertNull(decision.chainId);
    }

    @Test
    public void testDecideChainNoActiveSnapReturnsFull() {
        VirtualMachine vm = runningVm();
        doReturn(true).when(rbdBackupProvider).isIncrementalEnabled(any());
        doReturn(10).when(rbdBackupProvider).getFullEvery(any());
        stubAllVolumesOnRbd();
        when(vmInstanceDetailsDao.findDetail(VM_ID, RbdBackupChainKeys.VM_ACTIVE_SNAP)).thenReturn(null);

        RbdBackupProvider.ChainDecision decision = rbdBackupProvider.decideChain(vm);

        Assert.assertEquals(RbdBackupChainKeys.TYPE_FULL, decision.mode);
        Assert.assertNotNull(decision.snapNew);
        Assert.assertNull(decision.snapParent);
        Assert.assertNotNull(decision.chainId);
        Assert.assertEquals(0, decision.chainPosition);
    }

    @Test
    public void testDecideChainIncrementalWhenParentSnapMatches() {
        VirtualMachine vm = runningVm();
        doReturn(true).when(rbdBackupProvider).isIncrementalEnabled(any());
        doReturn(10).when(rbdBackupProvider).getFullEvery(any());
        stubAllVolumesOnRbd();

        VMInstanceDetailVO activeSnap = mock(VMInstanceDetailVO.class);
        when(activeSnap.getValue()).thenReturn("snap-1");
        when(vmInstanceDetailsDao.findDetail(VM_ID, RbdBackupChainKeys.VM_ACTIVE_SNAP)).thenReturn(activeSnap);

        Backup parent = mock(Backup.class);
        when(parent.getId()).thenReturn(PARENT_BACKUP_ID);
        when(parent.getStatus()).thenReturn(Backup.Status.BackedUp);
        when(parent.getDate()).thenReturn(new Date());
        when(parent.getType()).thenReturn("FULL");
        when(parent.getExternalId()).thenReturn("i-2-1234-VM/2026.04.27.13.45.00");
        Backup.VolumeInfo volInfo = mock(Backup.VolumeInfo.class);
        when(volInfo.getPath()).thenReturn("volpath");
        when(parent.getBackedUpVolumes()).thenReturn(Collections.singletonList(volInfo));
        when(backupDao.listByVmId(null, VM_ID)).thenReturn(Collections.singletonList(parent));

        when(backupDetailsDao.findDetail(PARENT_BACKUP_ID, RbdBackupChainKeys.PARENT_SNAP)).thenReturn(detail("snap-1"));
        when(backupDetailsDao.findDetail(PARENT_BACKUP_ID, RbdBackupChainKeys.CHAIN_ID)).thenReturn(detail("chain-1"));
        when(backupDetailsDao.findDetail(PARENT_BACKUP_ID, RbdBackupChainKeys.CHAIN_POSITION)).thenReturn(detail("0"));

        RbdBackupProvider.ChainDecision decision = rbdBackupProvider.decideChain(vm);

        Assert.assertEquals(RbdBackupChainKeys.TYPE_INCREMENTAL, decision.mode);
        Assert.assertEquals("snap-1", decision.snapParent);
        Assert.assertEquals("chain-1", decision.chainId);
        Assert.assertEquals(1, decision.chainPosition);
        Assert.assertNotNull(decision.parentPaths);
        Assert.assertEquals(1, decision.parentPaths.size());
        Assert.assertEquals("i-2-1234-VM/2026.04.27.13.45.00/root.volpath.rbd", decision.parentPaths.get(0));
    }

    @Test
    public void testDecideChainFallsBackToFullWhenParentSnapMissing() {
        VirtualMachine vm = runningVm();
        doReturn(true).when(rbdBackupProvider).isIncrementalEnabled(any());
        doReturn(10).when(rbdBackupProvider).getFullEvery(any());
        stubAllVolumesOnRbd();

        VMInstanceDetailVO activeSnap = mock(VMInstanceDetailVO.class);
        when(activeSnap.getValue()).thenReturn("snap-1");
        when(vmInstanceDetailsDao.findDetail(VM_ID, RbdBackupChainKeys.VM_ACTIVE_SNAP)).thenReturn(activeSnap);

        Backup parent = mock(Backup.class);
        when(parent.getId()).thenReturn(PARENT_BACKUP_ID);
        when(parent.getStatus()).thenReturn(Backup.Status.BackedUp);
        when(parent.getDate()).thenReturn(new Date());
        when(backupDao.listByVmId(null, VM_ID)).thenReturn(Collections.singletonList(parent));

        // Parent snapshot detail is gone (e.g. snapshot deleted on the pool) — chain is broken.
        when(backupDetailsDao.findDetail(PARENT_BACKUP_ID, RbdBackupChainKeys.PARENT_SNAP)).thenReturn(null);

        RbdBackupProvider.ChainDecision decision = rbdBackupProvider.decideChain(vm);

        Assert.assertEquals(RbdBackupChainKeys.TYPE_FULL, decision.mode);
        Assert.assertNull(decision.snapParent);
    }

    @Test
    public void testDecideChainNonRbdStorageReturnsLegacyFull() {
        VirtualMachine vm = runningVm();
        doReturn(true).when(rbdBackupProvider).isIncrementalEnabled(any());

        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(POOL_ID);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(volume));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(primaryDataStoreDao.findById(POOL_ID)).thenReturn(pool);

        RbdBackupProvider.ChainDecision decision = rbdBackupProvider.decideChain(vm);

        Assert.assertEquals(RbdBackupChainKeys.TYPE_LEGACY_FULL, decision.mode);
    }

    @Test
    public void testBuildVolumeChainFilesReturnsBaseThenDiffsRootToLeaf() throws Exception {
        // FULL root member: writes <externalId>/<diskType>.<volPath>.rbd
        Backup parent = mock(Backup.class);
        when(parent.getId()).thenReturn(PARENT_BACKUP_ID);
        when(parent.getType()).thenReturn("FULL");
        when(parent.getExternalId()).thenReturn("i-2-1234-VM/2026.04.27.13.45.00");
        Backup.VolumeInfo parentVol = mock(Backup.VolumeInfo.class);
        when(parentVol.getPath()).thenReturn("volpath");
        when(parentVol.getType()).thenReturn(Volume.Type.ROOT);
        when(parentVol.getDeviceId()).thenReturn(0L);
        when(parent.getBackedUpVolumes()).thenReturn(Collections.singletonList(parentVol));

        // INCREMENTAL leaf member: writes <externalId>/<diskType>.<volPath>.<ts>.rbdiff where
        // <ts> is the basename of the member's own externalId (the dotted date).
        Backup target = mock(Backup.class);
        when(target.getId()).thenReturn(40L);
        when(target.getVmId()).thenReturn(VM_ID);
        when(target.getType()).thenReturn("INCREMENTAL");
        when(target.getExternalId()).thenReturn("i-2-1234-VM/2026.04.28.13.45.00");
        Backup.VolumeInfo targetVol = mock(Backup.VolumeInfo.class);
        when(targetVol.getPath()).thenReturn("volpath");
        when(targetVol.getType()).thenReturn(Volume.Type.ROOT);
        when(targetVol.getDeviceId()).thenReturn(0L);
        when(target.getBackedUpVolumes()).thenReturn(Collections.singletonList(targetVol));

        when(backupDao.listByVmId(null, VM_ID)).thenReturn(Arrays.asList(parent, target));
        when(backupDetailsDao.findDetail(PARENT_BACKUP_ID, RbdBackupChainKeys.CHAIN_ID)).thenReturn(detail("chain-1"));
        when(backupDetailsDao.findDetail(40L, RbdBackupChainKeys.CHAIN_ID)).thenReturn(detail("chain-1"));
        when(backupDetailsDao.findDetail(PARENT_BACKUP_ID, RbdBackupChainKeys.CHAIN_POSITION)).thenReturn(detail("0"));
        when(backupDetailsDao.findDetail(40L, RbdBackupChainKeys.CHAIN_POSITION)).thenReturn(detail("1"));

        java.lang.reflect.Method m = RbdBackupProvider.class.getDeclaredMethod("buildVolumeChainFiles", Backup.class, String.class);
        m.setAccessible(true);
        String chain = (String) m.invoke(rbdBackupProvider, target, "volpath");

        Assert.assertEquals(
                "i-2-1234-VM/2026.04.27.13.45.00/root.volpath.rbd,"
                        + "i-2-1234-VM/2026.04.28.13.45.00/root.volpath.2026.04.28.13.45.00.rbdiff",
                chain);
    }

    @Test
    public void testIsSnapStillNeededTrueWhenSiblingSharesSnap() throws Exception {
        Backup target = mock(Backup.class);
        when(target.getId()).thenReturn(40L);
        when(target.getVmId()).thenReturn(VM_ID);

        Backup sibling = mock(Backup.class);
        when(sibling.getId()).thenReturn(PARENT_BACKUP_ID);
        when(sibling.getStatus()).thenReturn(Backup.Status.BackedUp);

        when(backupDao.listByVmId(null, VM_ID)).thenReturn(Arrays.asList(target, sibling));
        when(backupDetailsDao.findDetail(40L, RbdBackupChainKeys.CHAIN_ID)).thenReturn(detail("chain-1"));
        when(backupDetailsDao.findDetail(PARENT_BACKUP_ID, RbdBackupChainKeys.CHAIN_ID)).thenReturn(detail("chain-1"));
        when(backupDetailsDao.findDetail(PARENT_BACKUP_ID, RbdBackupChainKeys.PARENT_SNAP)).thenReturn(detail("snap-1"));

        java.lang.reflect.Method m = RbdBackupProvider.class.getDeclaredMethod("isSnapStillNeeded", Backup.class, String.class);
        m.setAccessible(true);
        boolean needed = (boolean) m.invoke(rbdBackupProvider, target, "snap-1");

        Assert.assertTrue(needed);
    }

    @Test
    public void testIsSnapStillNeededFalseWhenSiblingRemoved() throws Exception {
        Backup target = mock(Backup.class);
        when(target.getId()).thenReturn(40L);
        when(target.getVmId()).thenReturn(VM_ID);

        Backup sibling = mock(Backup.class);
        when(sibling.getId()).thenReturn(PARENT_BACKUP_ID);
        when(sibling.getStatus()).thenReturn(Backup.Status.Removed);

        when(backupDao.listByVmId(null, VM_ID)).thenReturn(Arrays.asList(target, sibling));
        when(backupDetailsDao.findDetail(40L, RbdBackupChainKeys.CHAIN_ID)).thenReturn(detail("chain-1"));

        java.lang.reflect.Method m = RbdBackupProvider.class.getDeclaredMethod("isSnapStillNeeded", Backup.class, String.class);
        m.setAccessible(true);
        boolean needed = (boolean) m.invoke(rbdBackupProvider, target, "snap-1");

        Assert.assertFalse(needed);
    }
}
