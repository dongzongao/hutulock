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
package com.hutulock.server.metrics;

import com.sun.net.httpserver.HttpServer;
import com.hutulock.server.ioc.Lifecycle;
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
public class MetricsHttpServer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(MetricsHttpServer.class);

    private final int                       port;
    private final String                    nodeId;
    private final PrometheusMetricsCollector collector;
    /** 允许访问 /metrics 的 IP 白名单，空表示仅允许 localhost */
    private final java.util.Set<String>     allowedHosts;
    private HttpServer                      server;

    /**
     * 构造 Metrics HTTP 服务器（仅允许 localhost 访问）。
     */
    public MetricsHttpServer(int port, String nodeId, PrometheusMetricsCollector collector) {
        this(port, nodeId, collector, java.util.Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1"));
    }

    /**
     * 构造 Metrics HTTP 服务器（自定义 IP 白名单）。
     *
     * @param allowedHosts 允许访问的 IP 集合，null 或空表示仅 localhost
     */
    public MetricsHttpServer(int port, String nodeId, PrometheusMetricsCollector collector,
                              java.util.Set<String> allowedHosts) {
        this.port         = port;
        this.nodeId       = nodeId;
        this.collector    = collector;
        this.allowedHosts = allowedHosts != null && !allowedHosts.isEmpty()
            ? allowedHosts
            : java.util.Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    }

    /**
     * 启动 HTTP 服务器。
     *
     * @throws IOException 端口绑定失败
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // GET /metrics — Prometheus scrape endpoint（仅允许白名单 IP）
        server.createContext("/metrics", exchange -> {
            String remoteIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!allowedHosts.contains(remoteIp)) {
                log.warn("Metrics access denied from {}", remoteIp);
                exchange.sendResponseHeaders(403, -1);
                return;
            }
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

    /** {@link com.hutulock.server.ioc.Lifecycle} 关闭钩子，委托给 {@link #stop()}。 */
    @Override
    public void shutdown() {
        stop();
    }
}
