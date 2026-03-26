/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.security;

import com.hutulock.spi.security.AuthResult;
import com.hutulock.spi.security.AuthToken;
import com.hutulock.spi.security.Authenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HMAC-SHA256 认证器（{@link Authenticator} 的 HMAC 实现）
 *
 * <p>客户端使用预共享密钥对 {@code clientId:timestamp} 进行 HMAC-SHA256 签名，
 * 服务端验证签名并检查时间戳防止重放攻击。
 *
 * <p>签名算法：
 * <pre>
 *   message   = clientId + ":" + timestamp
 *   signature = Base64(HMAC-SHA256(secretKey, message))
 * </pre>
 *
 * <p>防重放：时间戳与服务端当前时间差超过 {@code replayWindowMs} 时拒绝。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class HmacAuthenticator implements Authenticator {

    private static final Logger log = LoggerFactory.getLogger(HmacAuthenticator.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 防重放时间窗口，默认 5 分钟 */
    private static final long DEFAULT_REPLAY_WINDOW_MS = 5 * 60 * 1000L;

    /** clientId → 预共享密钥（字节数组） */
    private final Map<String, byte[]> secretKeys = new ConcurrentHashMap<>();
    private final long replayWindowMs;

    public HmacAuthenticator() {
        this(DEFAULT_REPLAY_WINDOW_MS);
    }

    public HmacAuthenticator(long replayWindowMs) {
        this.replayWindowMs = replayWindowMs;
    }

    /**
     * 注册客户端及其预共享密钥。
     *
     * @param clientId  客户端 ID
     * @param secretKey 预共享密钥（建议 32 字节以上）
     * @return this（支持链式调用）
     */
    public HmacAuthenticator addClient(String clientId, String secretKey) {
        secretKeys.put(clientId, secretKey.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    @Override
    public AuthResult authenticate(AuthToken authToken) {
        if (authToken == null || authToken.getType() != AuthToken.Type.HMAC) {
            return AuthResult.failure("expected HMAC token");
        }

        String clientId  = authToken.getClientId();
        long   timestamp = authToken.getTimestamp();
        String signature = authToken.getCredential();

        // 1. 检查客户端是否已注册
        byte[] secretKey = secretKeys.get(clientId);
        if (secretKey == null) {
            log.warn("HMAC auth failed: unknown clientId={}", clientId);
            return AuthResult.failure("unknown client: " + clientId);
        }

        // 2. 防重放：检查时间戳
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > replayWindowMs) {
            log.warn("HMAC auth failed: timestamp out of window, clientId={}, ts={}, now={}",
                clientId, timestamp, now);
            return AuthResult.failure("timestamp out of replay window");
        }

        // 3. 验证签名
        String expectedSig = computeHmac(secretKey, clientId + ":" + timestamp);
        if (expectedSig == null || !expectedSig.equals(signature)) {
            log.warn("HMAC auth failed: invalid signature for clientId={}", clientId);
            return AuthResult.failure("invalid HMAC signature");
        }

        log.debug("HMAC auth success: clientId={}", clientId);
        return AuthResult.success(clientId);
    }

    /**
     * 计算 HMAC-SHA256 签名并 Base64 编码。
     *
     * @param key     密钥字节数组
     * @param message 待签名消息
     * @return Base64 编码的签名，失败时返回 null
     */
    private static String computeHmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            log.error("HMAC computation failed", e);
            return null;
        }
    }
}
