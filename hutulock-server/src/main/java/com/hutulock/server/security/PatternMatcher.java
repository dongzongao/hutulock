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
package com.hutulock.server.security;

import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * ACL 模式匹配策略接口
 *
 * <p>决定 pattern 是否匹配 value，供 {@link AclAuthorizer} 使用。
 *
 * <p>内置实现：
 * <ul>
 *   <li>{@link #WILDCARD} — 精确匹配 + 前缀通配符（{@code prefix*}）+ 全通配符（{@code *}），默认</li>
 *   <li>{@link #REGEX}    — 正则表达式匹配</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@FunctionalInterface
public interface PatternMatcher {

    /**
     * 判断 {@code pattern} 是否匹配 {@code value}。
     *
     * @param pattern 规则中定义的模式字符串
     * @param value   实际值（clientId 或 lockName）
     * @return true 表示匹配
     */
    boolean matches(String pattern, String value);

    // ==================== 内置策略 ====================

    /**
     * 通配符匹配（默认策略）。
     * <ul>
     *   <li>{@code *}        — 匹配任意值</li>
     *   <li>{@code prefix*}  — 前缀匹配</li>
     *   <li>其他             — 精确匹配</li>
     * </ul>
     */
    PatternMatcher WILDCARD = (pattern, value) -> {
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    };

    /**
     * 正则表达式匹配（带超时保护，防止 ReDoS）。
     * pattern 直接作为 Java 正则表达式使用，超过 100ms 视为不匹配。
     */
    PatternMatcher REGEX = (pattern, value) -> {
        Future<Boolean> future = ForkJoinPool.commonPool().submit(
            () -> Pattern.matches(pattern, value));
        try {
            return future.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return false;
        } catch (Exception e) {
            return false;
        }
    };
}
