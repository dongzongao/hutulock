/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.config.api;

/**
 * 服务端配置属性（不可变值对象）
 *
 * <p>通过 {@link Builder} 构建，支持链式调用。
 * 所有字段均有合理的默认值，可按需覆盖。
 *
 * <p>配置分组：
 * <ul>
 *   <li>Raft 共识参数</li>
 *   <li>看门狗 / Session 参数</li>
 *   <li>网络参数</li>
 *   <li>Metrics 参数</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class ServerProperties {

    // ---- Raft 参数 ----

    /** 选举超时最小值（毫秒），默认 150ms */
    public final long electionTimeoutMinMs;
    /** 选举超时最大值（毫秒），默认 300ms */
    public final long electionTimeoutMaxMs;
    /** 心跳间隔（毫秒），默认 50ms，应 < electionTimeoutMin/3 */
    public final long heartbeatIntervalMs;
    /** Propose 超时（毫秒），默认 10s */
    public final long proposeTimeoutMs;
    /** Propose 失败重试次数，默认 3 */
    public final int  proposeRetryCount;
    /** Propose 重试间隔（毫秒），默认 500ms */
    public final long proposeRetryDelayMs;

    // ---- 看门狗 / Session 参数 ----

    /** 看门狗 TTL（毫秒），默认 30s，超时无心跳则强制释放锁 */
    public final long watchdogTtlMs;
    /** 看门狗扫描间隔（毫秒），默认 1s */
    public final long watchdogScanIntervalMs;

    // ---- 网络参数 ----

    /** TCP backlog 队列大小，默认 128 */
    public final int soBacklog;
    /** Raft 节点间连接超时（毫秒），默认 3s */
    public final int raftConnectTimeoutMs;
    /** 最大帧长度（字节），默认 4096 */
    public final int maxFrameLength;

    // ---- Metrics 参数 ----

    /** 是否启用 Metrics，默认 true */
    public final boolean metricsEnabled;
    /** Metrics HTTP 端口，默认 9090 */
    public final int     metricsPort;

    // ---- 安全参数 ----

    /** 是否启用安全认证，默认 false（开发模式） */
    public final boolean securityEnabled;
    /** 是否启用 TLS，默认 false */
    public final boolean tlsEnabled;
    /** TLS 证书文件路径（PEM 格式），tlsEnabled=true 时必须配置 */
    public final String  tlsCertFile;
    /** TLS 私钥文件路径（PEM 格式），tlsEnabled=true 时必须配置 */
    public final String  tlsKeyFile;
    /** 是否使用自签名证书（仅开发/测试），tlsEnabled=true 且未配置证书文件时生效 */
    public final boolean tlsSelfSigned;
    /** 限流：每个客户端每秒最大请求数，默认 100 */
    public final double  rateLimitQps;
    /** 限流：突发容量，默认 200 */
    public final long    rateLimitBurst;

    private ServerProperties(Builder b) {
        this.electionTimeoutMinMs  = b.electionTimeoutMinMs;
        this.electionTimeoutMaxMs  = b.electionTimeoutMaxMs;
        this.heartbeatIntervalMs   = b.heartbeatIntervalMs;
        this.proposeTimeoutMs      = b.proposeTimeoutMs;
        this.proposeRetryCount     = b.proposeRetryCount;
        this.proposeRetryDelayMs   = b.proposeRetryDelayMs;
        this.watchdogTtlMs         = b.watchdogTtlMs;
        this.watchdogScanIntervalMs= b.watchdogScanIntervalMs;
        this.soBacklog             = b.soBacklog;
        this.raftConnectTimeoutMs  = b.raftConnectTimeoutMs;
        this.maxFrameLength        = b.maxFrameLength;
        this.metricsEnabled        = b.metricsEnabled;
        this.metricsPort           = b.metricsPort;
        this.securityEnabled       = b.securityEnabled;
        this.tlsEnabled            = b.tlsEnabled;
        this.tlsCertFile           = b.tlsCertFile;
        this.tlsKeyFile            = b.tlsKeyFile;
        this.tlsSelfSigned         = b.tlsSelfSigned;
        this.rateLimitQps          = b.rateLimitQps;
        this.rateLimitBurst        = b.rateLimitBurst;
    }

    /** 使用全部默认值创建配置。 */
    public static ServerProperties defaults() {
        return builder().build();
    }

    public static Builder builder() { return new Builder(); }

    /** 服务端配置构建器 */
    public static final class Builder {
        private long    electionTimeoutMinMs   = 150;
        private long    electionTimeoutMaxMs   = 300;
        private long    heartbeatIntervalMs    = 50;
        private long    proposeTimeoutMs       = 10_000;
        private int     proposeRetryCount      = 3;
        private long    proposeRetryDelayMs    = 500;
        private long    watchdogTtlMs          = 30_000;
        private long    watchdogScanIntervalMs = 1_000;
        private int     soBacklog              = 128;
        private int     raftConnectTimeoutMs   = 3_000;
        private int     maxFrameLength         = 4096;
        private boolean metricsEnabled         = true;
        private int     metricsPort            = 9090;
        // 安全参数
        private boolean securityEnabled        = false;
        private boolean tlsEnabled             = false;
        private String  tlsCertFile            = null;
        private String  tlsKeyFile             = null;
        private boolean tlsSelfSigned          = false;
        private double  rateLimitQps           = 100.0;
        private long    rateLimitBurst         = 200;

        public Builder electionTimeout(long minMs, long maxMs) {
            this.electionTimeoutMinMs = minMs;
            this.electionTimeoutMaxMs = maxMs;
            return this;
        }
        public Builder heartbeatInterval(long ms)    { heartbeatIntervalMs    = ms;  return this; }
        public Builder proposeTimeout(long ms)       { proposeTimeoutMs       = ms;  return this; }
        public Builder proposeRetry(int n, long ms)  { proposeRetryCount = n; proposeRetryDelayMs = ms; return this; }
        public Builder watchdogTtl(long ms)          { watchdogTtlMs          = ms;  return this; }
        public Builder watchdogScanInterval(long ms) { watchdogScanIntervalMs = ms;  return this; }
        public Builder soBacklog(int n)              { soBacklog              = n;   return this; }
        public Builder raftConnectTimeout(int ms)    { raftConnectTimeoutMs   = ms;  return this; }
        public Builder maxFrameLength(int n)         { maxFrameLength         = n;   return this; }
        public Builder metricsEnabled(boolean b)     { metricsEnabled         = b;   return this; }
        public Builder metricsPort(int port)         { metricsPort            = port;return this; }
        public Builder securityEnabled(boolean b)    { securityEnabled        = b;   return this; }
        public Builder tlsEnabled(boolean b)         { tlsEnabled             = b;   return this; }
        public Builder tlsCertFile(String f)         { tlsCertFile            = f;   return this; }
        public Builder tlsKeyFile(String f)          { tlsKeyFile             = f;   return this; }
        public Builder tlsSelfSigned(boolean b)      { tlsSelfSigned          = b;   return this; }
        public Builder rateLimit(double qps, long burst) {
            rateLimitQps   = qps;
            rateLimitBurst = burst;
            return this;
        }

        public ServerProperties build() {
            validate();
            return new ServerProperties(this);
        }

        private void validate() {
            if (heartbeatIntervalMs >= electionTimeoutMinMs / 3) {
                throw new IllegalArgumentException(
                    "heartbeatInterval should be < electionTimeoutMin/3 to avoid false elections");
            }
            if (electionTimeoutMinMs >= electionTimeoutMaxMs) {
                throw new IllegalArgumentException("electionTimeoutMin must be < electionTimeoutMax");
            }
        }
    }
}
