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
package com.hutulock.model.watcher;

/**
 * Watcher 接口
 *
 * <p>Watcher 是一次性的（One-shot）：触发后自动注销，需重新注册。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
@FunctionalInterface
public interface Watcher {
    void process(WatchEvent event);
}
