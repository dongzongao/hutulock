/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.security;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * TLS 上下文工厂
 *
 * <p>为 Netty 服务端和客户端创建 {@link SslContext}，支持：
 * <ul>
 *   <li>自签名证书（开发/测试环境，自动生成）</li>
 *   <li>PEM 文件（生产环境，提供证书链和私钥）</li>
 *   <li>JKS/PKCS12 KeyStore（企业环境）</li>
 * </ul>
 *
 * <p>使用示例（生产环境）：
 * <pre>{@code
 *   SslContext sslCtx = TlsContextFactory.serverContext(
 *       new File("/etc/hutulock/server.crt"),
 *       new File("/etc/hutulock/server.key")
 *   );
 * }</pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public final class TlsContextFactory {

    private static final Logger log = LoggerFactory.getLogger(TlsContextFactory.class);

    private TlsContextFactory() {}

    /**
     * 创建服务端 TLS 上下文（PEM 格式证书和私钥）。
     *
     * @param certChainFile PEM 格式的证书链文件（含中间证书）
     * @param keyFile       PEM 格式的私钥文件（PKCS#8 格式）
     * @return 服务端 SslContext
     * @throws Exception 证书加载失败
     */
    public static SslContext serverContext(File certChainFile, File keyFile) throws Exception {
        log.info("Creating server TLS context from cert={}, key={}", certChainFile, keyFile);
        return SslContextBuilder.forServer(certChainFile, keyFile)
            .sslProvider(SslProvider.JDK)
            .protocols("TLSv1.2", "TLSv1.3")
            .ciphers(null) // 使用 JDK 默认安全密码套件
            .build();
    }

    /**
     * 创建服务端 TLS 上下文（自签名证书，仅用于开发/测试）。
     *
     * <p>警告：自签名证书不应用于生产环境。
     *
     * @return 服务端 SslContext
     * @throws Exception 证书生成失败
     */
    public static SslContext serverContextSelfSigned() throws Exception {
        log.warn("Using self-signed certificate — NOT for production use!");
        SelfSignedCertificate ssc = new SelfSignedCertificate("hutulock");
        return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
            .sslProvider(SslProvider.JDK)
            .protocols("TLSv1.2", "TLSv1.3")
            .build();
    }

    /**
     * 创建客户端 TLS 上下文（信任指定 CA 证书）。
     *
     * @param trustCertFile PEM 格式的 CA 证书文件（用于验证服务端证书）
     * @return 客户端 SslContext
     * @throws Exception 证书加载失败
     */
    public static SslContext clientContext(File trustCertFile) throws Exception {
        log.info("Creating client TLS context with trust cert={}", trustCertFile);
        return SslContextBuilder.forClient()
            .trustManager(trustCertFile)
            .sslProvider(SslProvider.JDK)
            .protocols("TLSv1.2", "TLSv1.3")
            .build();
    }

    /**
     * 创建客户端 TLS 上下文（信任 JKS KeyStore 中的 CA 证书）。
     *
     * @param trustStorePath     JKS 文件路径
     * @param trustStorePassword KeyStore 密码
     * @return 客户端 SslContext
     * @throws Exception 加载失败
     */
    public static SslContext clientContextFromKeyStore(String trustStorePath,
                                                        char[] trustStorePassword) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(trustStorePath)) {
            ks.load(fis, trustStorePassword);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        return SslContextBuilder.forClient()
            .trustManager(tmf)
            .sslProvider(SslProvider.JDK)
            .protocols("TLSv1.2", "TLSv1.3")
            .build();
    }

    /**
     * 创建客户端 TLS 上下文（信任所有证书，仅用于开发/测试）。
     *
     * <p>警告：此模式容易受到中间人攻击，禁止用于生产环境。
     *
     * @return 客户端 SslContext
     * @throws Exception 创建失败
     */
    public static SslContext clientContextTrustAll() throws Exception {
        log.warn("Using trust-all TLS context — NOT for production use!");
        return SslContextBuilder.forClient()
            .trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE)
            .build();
    }
}
