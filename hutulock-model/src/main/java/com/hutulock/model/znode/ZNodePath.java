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
package com.hutulock.model.znode;

import com.hutulock.model.exception.ErrorCode;
import com.hutulock.model.exception.HutuLockException;

/**
 * ZNode 路径（不可变值对象）
 *
 * <p>规则：必须以 {@code /} 开头，不能以 {@code /} 结尾（根路径除外），路径段不能为空。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ZNodePath {

    public static final ZNodePath ROOT = new ZNodePath("/");

    private final String path;

    private ZNodePath(String path) { this.path = path; }

    public static ZNodePath of(String path) {
        validate(path);
        return new ZNodePath(path.replaceAll("/+", "/"));
    }

    public static ZNodePath of(ZNodePath parent, String child) {
        if (child == null || child.isBlank()) {
            throw new HutuLockException(ErrorCode.INVALID_COMMAND, "child path segment cannot be blank");
        }
        return of(parent.isRoot() ? "/" + child : parent.path + "/" + child);
    }

    public ZNodePath parent() {
        if (isRoot()) throw new HutuLockException(ErrorCode.INVALID_COMMAND, "root has no parent");
        int last = path.lastIndexOf('/');
        return last == 0 ? ROOT : new ZNodePath(path.substring(0, last));
    }

    public String name() {
        if (isRoot()) return "/";
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public boolean isRoot()  { return "/".equals(path); }
    public String  value()   { return path; }

    private static void validate(String path) {
        if (path == null || path.isBlank())
            throw new HutuLockException(ErrorCode.INVALID_COMMAND, "path cannot be blank");
        if (!path.startsWith("/"))
            throw new HutuLockException(ErrorCode.INVALID_COMMAND, "path must start with /: " + path);
        if (path.length() > 1 && path.endsWith("/"))
            throw new HutuLockException(ErrorCode.INVALID_COMMAND, "path must not end with /: " + path);
        for (String seg : path.substring(1).split("/"))
            if (seg.isEmpty())
                throw new HutuLockException(ErrorCode.INVALID_COMMAND, "path has empty segment: " + path);
    }

    @Override public boolean equals(Object o) {
        return o instanceof ZNodePath && path.equals(((ZNodePath) o).path);
    }
    @Override public int hashCode() { return path.hashCode(); }
    @Override public String toString() { return path; }
}
