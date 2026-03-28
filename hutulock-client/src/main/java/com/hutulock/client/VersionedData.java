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
package com.hutulock.client;

import java.nio.charset.StandardCharsets;

/**
 * Versioned data returned by {@link HutuLockClient#getData}.
 *
 * <p>Used for optimistic locking: read data + version, modify locally,
 * then call {@link HutuLockClient#setData} with the same version.
 * If another writer modified the data in between, the server rejects
 * the write with VERSION_MISMATCH and the caller should retry.
 *
 * <p>Replaces MySQL optimistic locking pattern:
 * <pre>
 *   // MySQL pattern:
 *   SELECT data, version FROM t WHERE id = ?
 *   UPDATE t SET data = ?, version = version+1 WHERE id = ? AND version = ?
 *
 *   // HutuLock pattern:
 *   VersionedData vd = client.getData("/resources/order-123");
 *   boolean ok = client.setData("/resources/order-123", newData, vd.getVersion());
 *   if (!ok) { // retry }
 * </pre>
 */
public final class VersionedData {

    private final String path;
    private final byte[] data;
    private final int    version;

    public VersionedData(String path, byte[] data, int version) {
        this.path    = path;
        this.data    = data;
        this.version = version;
    }

    public String getPath()    { return path;    }
    public byte[] getData()    { return data;    }
    public int    getVersion() { return version; }

    /** Convenience: data as UTF-8 string. */
    public String getDataAsString() {
        return data == null || data.length == 0 ? "" : new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "VersionedData{path=" + path + ", version=" + version +
               ", data=" + getDataAsString() + "}";
    }
}
