/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.model.protocol;

/**
 * 客户端 ↔ 服务端 协议命令类型
 *
 * <p>文本协议格式（行分隔，UTF-8）：{@code {TYPE} {arg1} {arg2} ...}
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public enum CommandType {
    // 客户端请求
    CONNECT, LOCK, UNLOCK, RECHECK, RENEW,
    // 服务端响应
    CONNECTED, OK, WAIT, RELEASED, RENEWED, REDIRECT, WATCH_EVENT, ERROR
}
