/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.metrics;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Metrics HTTP 服务器
 *
 * <p>使用 JDK 内置 {@link HttpServer}，无额外依赖，暴露以下端点：
 * <ul>
 *   <li>{@code GET /metrics} — Prometheus text format，供 Prometheus 抓取</li>
 *   <li>{@code GET /health}  — 健康检查，返回 JSON {@code {"status":"UP"}}</li>
 * </ul>
 *
 * <p>Prometheus 配置示例：
 * <pre>
 * scrape_configs:
 *   - job_name: 'hutulock'
 *     static_configs:
 *       - targets: ['localhost:9090', 'localhost:9091', 'localhost:9092']
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class MetricsHttpServer {

    private static final Logger log = LoggerFactory.getLogger(MetricsHttpServer.class);

    private final int                       port;
    private final String                    nodeId;
    private final PrometheusMetricsCollector collector;
    private HttpServer                      server;

    /**
     * 构造 Metrics HTTP 服务器。
     *
     * @param port      监听端口
     * @param nodeId    节点 ID（用于健康检查响应）
     * @param collector Prometheus 收集器
     */
    public MetricsHttpServer(int port, String nodeId, PrometheusMetricsCollector collector) {
        this.port      = port;
        this.nodeId    = nodeId;
        this.collector = collector;
    }

    /**
     * 启动 HTTP 服务器。
     *
     * @throws IOException 端口绑定失败
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // GET /metrics — Prometheus scrape endpoint
        server.createContext("/metrics", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = collector.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        // GET /health — 健康检查
        server.createContext("/health", exchange -> {
            String json = "{\"status\":\"UP\",\"node\":\"" + nodeId + "\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "hutulock-metrics-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.info("Metrics HTTP server started on port {} (GET /metrics, GET /health)", port);
    }

    /** 停止 HTTP 服务器。 */
    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("Metrics HTTP server stopped");
        }
    }
}
