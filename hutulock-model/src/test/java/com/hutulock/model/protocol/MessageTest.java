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
package com.hutulock.model.protocol;

import com.hutulock.model.exception.HutuLockException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Message 协议解析单元测试
 */
class MessageTest {

    @Test
    void parseConnect() {
        Message msg = Message.parse("CONNECT");
        assertEquals(CommandType.CONNECT, msg.getType());
        assertEquals(0, msg.argCount());
    }

    @Test
    void parseLock() {
        Message msg = Message.parse("LOCK order-lock session-abc");
        assertEquals(CommandType.LOCK, msg.getType());
        assertEquals("order-lock", msg.arg(0));
        assertEquals("session-abc", msg.arg(1));
    }

    @Test
    void parseOk() {
        Message msg = Message.parse("OK order-lock /locks/order-lock/seq-0000000001");
        assertEquals(CommandType.OK, msg.getType());
        assertEquals("order-lock", msg.arg(0));
        assertEquals("/locks/order-lock/seq-0000000001", msg.arg(1));
    }

    @Test
    void parseUnknownCommand() {
        assertThrows(HutuLockException.class, () -> Message.parse("UNKNOWN arg1"));
    }

    @Test
    void parseEmptyLine() {
        assertThrows(HutuLockException.class, () -> Message.parse(""));
        assertThrows(HutuLockException.class, () -> Message.parse(null));
    }

    @Test
    void missingArgThrows() {
        assertThrows(HutuLockException.class, () -> Message.parse("LOCK order-lock"));
    }

    @Test
    void serialize() {
        Message msg = Message.of(CommandType.LOCK, "order-lock", "session-abc");
        assertEquals("LOCK order-lock session-abc", msg.serialize());
    }

    @Test
    void serializeNoArgs() {
        Message msg = Message.of(CommandType.CONNECT);
        assertEquals("CONNECT", msg.serialize());
    }

    @Test
    void caseInsensitiveParse() {
        Message msg = Message.parse("lock order-lock session-abc");
        assertEquals(CommandType.LOCK, msg.getType());
    }

    @Test
    void roundTrip() {
        Message original = Message.of(CommandType.RECHECK, "order-lock",
            "/locks/order-lock/seq-0000000001", "session-abc");
        Message parsed = Message.parse(original.serialize());
        assertEquals(original.getType(), parsed.getType());
        assertEquals(original.arg(0), parsed.arg(0));
        assertEquals(original.arg(1), parsed.arg(1));
        assertEquals(original.arg(2), parsed.arg(2));
    }
}
