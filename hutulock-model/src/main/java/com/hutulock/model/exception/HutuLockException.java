/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
