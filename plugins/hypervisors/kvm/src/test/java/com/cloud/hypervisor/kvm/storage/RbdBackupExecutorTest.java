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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;

import com.cloud.hypervisor.kvm.storage.RbdBackupExecutor.RbdAuth;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Unit tests for {@link RbdBackupExecutor}. These exercise the pure-logic pieces (escaped-RBD-URI
 * parsing and the zstd frame sniff) plus a shell-free {@code ProcessBuilder} smoke test. No Ceph,
 * network, rbd CLI or mount is required, matching the contract's acceptance criteria.
 */
public class RbdBackupExecutorTest {

    // Exact single-quoted URI from rbdbackup.sh --selftest. In Java every backslash is doubled.
    private static final String ESCAPED_URI =
            "rbd:pool/img:mon_host=10.0.1.2\\:6789\\;10.0.1.3\\:6789:auth_client_required=cephx:id=admin"
                    + ":key=AQBr5\\=\\=:rbd_default_format=2";

    @Test
    public void testParseRbdUriEscapedSample() {
        RbdAuth auth = RbdBackupExecutor.parseRbdUri(ESCAPED_URI);
        assertEquals("10.0.1.2:6789,10.0.1.3:6789", auth.mon);
        assertEquals("admin", auth.id);
        assertEquals("AQBr5==", auth.key);
    }

    @Test
    public void testParseRbdUriPlainUnescaped() {
        RbdAuth auth = RbdBackupExecutor.parseRbdUri("rbd:cloudstack/abc:mon_host=10.0.1.2:6789");
        assertEquals("10.0.1.2", auth.mon);
        assertEquals("", auth.id);
        assertEquals("", auth.key);
    }

    @Test
    public void testParseRbdUriNoMonHost() {
        RbdAuth auth = RbdBackupExecutor.parseRbdUri("rbd:cloudstack/abc:something_else=1");
        assertEquals("", auth.mon);
        assertEquals("", auth.id);
        assertEquals("", auth.key);
    }

    @Test
    public void testGetPoolAndImage() {
        assertEquals("pool", RbdBackupExecutor.getPool(ESCAPED_URI));
        assertEquals("img", RbdBackupExecutor.getImage(ESCAPED_URI));
        assertEquals("cloudstack", RbdBackupExecutor.getPool("rbd:cloudstack/abc:mon_host=1.2.3.4:6789"));
        assertEquals("abc", RbdBackupExecutor.getImage("rbd:cloudstack/abc:mon_host=1.2.3.4:6789"));
    }

    @Test
    public void testIsZstdTrue() {
        assertTrue(RbdBackupExecutor.isZstd(new byte[] {(byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD}));
    }

    @Test
    public void testIsZstdFalse() {
        assertFalse(RbdBackupExecutor.isZstd(new byte[] {0x00, 0x01, 0x02, 0x03}));
        assertFalse(RbdBackupExecutor.isZstd(new byte[] {(byte) 0x28, (byte) 0xB5, (byte) 0x2F, 0x00}));
    }

    @Test
    public void testIsZstdTooShortOrNull() {
        assertFalse(RbdBackupExecutor.isZstd(new byte[] {(byte) 0x28, (byte) 0xB5}));
        assertFalse(RbdBackupExecutor.isZstd(new byte[0]));
        assertFalse(RbdBackupExecutor.isZstd(null));
    }

    @Test
    public void testRunCapturesStdout() {
        // Runs /bin/echo via argv (no shell, no Ceph): proves the ProcessBuilder drain path returns stdout.
        String out = RbdBackupExecutor.run(Arrays.asList("echo", "rbd-exec-test"), RbdBackupExecutor.NO_TIMEOUT);
        assertEquals("rbd-exec-test", out.trim());
    }

    @Test
    public void testRunNonZeroExitThrows() {
        // `cat` of a missing path exits non-zero and writes to stderr: proves a hard failure surfaces as
        // CloudRuntimeException carrying captured stderr (again with no Ceph or network).
        try {
            RbdBackupExecutor.run(Arrays.asList("cat", "/no/such/file/rbd-exec-test-does-not-exist"),
                    RbdBackupExecutor.NO_TIMEOUT);
            fail("Expected CloudRuntimeException on non-zero exit");
        } catch (CloudRuntimeException e) {
            assertTrue("message should reference the failing command",
                    e.getMessage().contains("exited with"));
        }
    }
}
