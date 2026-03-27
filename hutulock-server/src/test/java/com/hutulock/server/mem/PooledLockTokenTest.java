/*
 * Copyright 2026 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hutulock.server.mem;

import com.hutulock.spi.lock.LockToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PooledLockToken 单元测试
 *
 * 覆盖：
 *   - init 后 getter 返回正确值
 *   - reset 后所有字段清零
 *   - token() 构建的 LockToken 字段一致
 *   - heldMillis 随时间增长
 *   - toString 包含关键字段
 */
class PooledLockTokenTest {

    @Test
    void init_gettersReturnCorrectValues() {
        PooledLockToken t = new PooledLockToken();
        t.init("order-lock", "/locks/order-lock/seq-0000000001", "session-abc");

        assertEquals("order-lock",                        t.getLockName());
        assertEquals("/locks/order-lock/seq-0000000001",  t.getSeqNodePath());
        assertEquals("session-abc",                       t.getSessionId());
        assertTrue(t.getAcquiredAt() > 0);
    }

    @Test
    void init_returnsThis_forChaining() {
        PooledLockToken t = new PooledLockToken();
        assertSame(t, t.init("lock", "/path", "session"));
    }

    @Test
    void reset_clearsAllFields() {
        PooledLockToken t = new PooledLockToken();
        t.init("order-lock", "/locks/order-lock/seq-0000000001", "session-abc");
        t.reset();

        assertNull(t.getLockName());
        assertNull(t.getSeqNodePath());
        assertNull(t.getSessionId());
        assertEquals(0L, t.getAcquiredAt());
    }

    @Test
    void token_fieldsMatchInit() {
        PooledLockToken t = new PooledLockToken();
        t.init("order-lock", "/locks/order-lock/seq-0000000001", "session-abc");
        LockToken token = t.token();

        assertEquals("order-lock",                       token.getLockName());
        assertEquals("/locks/order-lock/seq-0000000001", token.getSeqNodePath());
        assertEquals("session-abc",                      token.getSessionId());
    }

    @Test
    void heldMillis_isNonNegative() throws InterruptedException {
        PooledLockToken t = new PooledLockToken();
        t.init("lock", "/path", "session");
        Thread.sleep(5);
        assertTrue(t.heldMillis() >= 5, "heldMillis 应 >= 等待时间");
    }

    @Test
    void toString_containsKeyFields() {
        PooledLockToken t = new PooledLockToken();
        t.init("order-lock", "/locks/order-lock/seq-0000000001", "session-abc");
        String str = t.toString();

        assertTrue(str.contains("order-lock"),   "toString 应包含 lockName");
        assertTrue(str.contains("session-abc"),  "toString 应包含 sessionId");
    }

    @Test
    void poolIntegration_borrowInitReleaseReuse() {
        ObjectPool<PooledLockToken> pool = new ObjectPool<>(16, PooledLockToken::new);

        PooledLockToken t = pool.borrow();
        t.init("lock-a", "/locks/lock-a/seq-0000000001", "sess-1");
        assertEquals("lock-a", t.getLockName());

        pool.release(t); // 触发 reset()

        // 复用同一对象，字段应已清空
        PooledLockToken reused = pool.borrow();
        assertNull(reused.getLockName(), "复用对象的 lockName 应被 reset 为 null");
    }
}
