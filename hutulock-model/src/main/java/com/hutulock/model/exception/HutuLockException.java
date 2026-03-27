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
package com.hutulock.model.exception;

/**
 * HutuLock 基础异常
 *
 * <p>所有业务异常均继承此类，携带结构化的 {@link ErrorCode}。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class HutuLockException extends RuntimeException {

    private final ErrorCode code;

    public HutuLockException(ErrorCode code, String message) {
        super("[" + code + "] " + message);
        this.code = code;
    }

    public HutuLockException(ErrorCode code, String message, Throwable cause) {
        super("[" + code + "] " + message, cause);
        this.code = code;
    }

    public ErrorCode getCode() { return code; }
}
