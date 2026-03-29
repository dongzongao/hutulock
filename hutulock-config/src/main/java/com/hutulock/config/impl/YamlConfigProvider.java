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
package com.hutulock.config.impl;

import com.hutulock.config.api.ClientProperties;
import com.hutulock.config.api.ConfigProvider;
import com.hutulock.config.api.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

/**
 * YAML 配置提供者（{@link ConfigProvider} 的 YAML 实现）
 *
 * <p>从 classpath 或文件系统加载 {@code hutulock.yml} 配置文件。
 *
 * <p>配置文件示例（{@code hutulock.yml}）：
 * <pre>
 * hutulock:
 *   server:
 *     raft:
 *       electionTimeoutMin: 150
 *       electionTimeoutMax: 300
 *       heartbeatInterval: 50
 *       proposeTimeout: 10000
 *     watchdog:
 *       ttl: 30000
 *       scanInterval: 1000
 *     network:
 *       soBacklog: 128
 *       maxFrameLength: 4096
 *     metrics:
 *       enabled: true
 *       port: 9090
 *   client:
 *     connectTimeout: 3000
 *     lockTimeout: 30
 *     watchdog:
 *       ttl: 30000
 *       interval: 10000
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class YamlConfigProvider implements ConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(YamlConfigProvider.class);

    private static final String DEFAULT_CONFIG_FILE = "hutulock.yml";

    private final ServerProperties serverProperties;
    private final ClientProperties clientProperties;

    /**
     * 从 classpath 加载默认配置文件 {@code hutulock.yml}。
     * 若文件不存在，使用全部默认值。
     */
    public YamlConfigProvider() {
        this(DEFAULT_CONFIG_FILE);
    }

    /**
     * 从指定 classpath 路径加载配置文件。
     *
     * @param classpathResource classpath 资源路径
     */
    public YamlConfigProvider(String classpathResource) {
        Map<String, Object> root = loadYaml(classpathResource);
        this.serverProperties = parseServer(root);
        this.clientProperties = parseClient(root);
    }

    @Override
    public ServerProperties getServerProperties() { return serverProperties; }

    @Override
    public ClientProperties getClientProperties() { return clientProperties; }

    // ==================== 解析 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(String resource) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                log.info("Config file '{}' not found on classpath, using defaults", resource);
                return Map.of();
            }
            Yaml yaml = new Yaml();
            Map<String, Object> all = yaml.load(is);
            Object hutulock = all.get("hutulock");
            if (hutulock instanceof Map) {
                log.info("Loaded config from '{}'", resource);
                return (Map<String, Object>) hutulock;
            }
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to load config from '{}', using defaults: {}", resource, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private ServerProperties parseServer(Map<String, Object> root) {
        ServerProperties.Builder b = ServerProperties.builder();
        Object serverObj = root.get("server");
        if (!(serverObj instanceof Map)) return b.build();

        Map<String, Object> server = (Map<String, Object>) serverObj;

        // Raft
        Map<String, Object> raft = getMap(server, "raft");
        if (raft != null) {
            long minMs = getLong(raft, "electionTimeoutMin", 150L);
            long maxMs = getLong(raft, "electionTimeoutMax", 300L);
            b.electionTimeout(minMs, maxMs);
            b.heartbeatInterval(getLong(raft, "heartbeatInterval", 50L));
            b.proposeTimeout(getLong(raft, "proposeTimeout", 10_000L));
            b.proposeRetry(getInt(raft, "proposeRetryCount", 3),
                           getLong(raft, "proposeRetryDelay", 500L));
        }

        // 看门狗
        Map<String, Object> watchdog = getMap(server, "watchdog");
        if (watchdog != null) {
            b.watchdogTtl(getLong(watchdog, "ttl", 30_000L));
            b.watchdogScanInterval(getLong(watchdog, "scanInterval", 1_000L));
        }

        // 网络
        Map<String, Object> network = getMap(server, "network");
        if (network != null) {
            b.soBacklog(getInt(network, "soBacklog", 128));
            b.raftConnectTimeout(getInt(network, "raftConnectTimeout", 3_000));
            b.maxFrameLength(getInt(network, "maxFrameLength", 4096));
        }

        // Metrics
        Map<String, Object> metrics = getMap(server, "metrics");
        if (metrics != null) {
            b.metricsEnabled(getBool(metrics, "enabled", true));
            b.metricsPort(getInt(metrics, "port", 9090));
        }

        // Admin
        Map<String, Object> admin = getMap(server, "admin");
        if (admin != null) {
            b.adminEnabled(getBool(admin, "enabled", true));
            b.adminPort(getInt(admin, "port", 9091));
            String u = getString(admin, "username", null);
            String p = getString(admin, "password", null);
            if (u != null) b.adminUsername(u);
            if (p != null) b.adminPassword(p);
            long ttl = getLong(admin, "tokenTtlHours", -1L);
            if (ttl > 0) b.adminTokenTtl(ttl * 3600_000L);
        }

        // 安全
        Map<String, Object> security = getMap(server, "security");
        if (security != null) {
            b.securityEnabled(getBool(security, "enabled", false));
            Map<String, Object> tls = getMap(security, "tls");
            if (tls != null) {
                b.tlsEnabled(getBool(tls, "enabled", false));
                b.tlsCertFile(getString(tls, "certFile", null));
                b.tlsKeyFile(getString(tls, "keyFile", null));
                b.tlsSelfSigned(getBool(tls, "selfSigned", false));
            }
            Map<String, Object> rl = getMap(security, "rateLimit");
            if (rl != null) {
                double qps   = getDouble(rl, "qps",   100.0);
                long   burst = getLong(rl,   "burst", 200L);
                b.rateLimit(qps, burst);
            }
        }

        return b.build();
    }

    @SuppressWarnings("unchecked")
    private ClientProperties parseClient(Map<String, Object> root) {
        ClientProperties.Builder b = ClientProperties.builder();
        Object clientObj = root.get("client");
        if (!(clientObj instanceof Map)) return b.build();

        Map<String, Object> client = (Map<String, Object>) clientObj;
        b.connectTimeout(getInt(client, "connectTimeout", 3_000));
        b.lockTimeout(getInt(client, "lockTimeout", 30));

        Map<String, Object> watchdog = getMap(client, "watchdog");
        if (watchdog != null) {
            b.watchdogTtl(getLong(watchdog, "ttl", 30_000L));
            // interval 未配置时不设置，由 Builder 自动推导为 ttl/4
            long interval = getLong(watchdog, "interval", -1L);
            if (interval > 0) b.watchdogInterval(interval);
        }

        return b.build();
    }

    // ==================== 工具方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private long getLong(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        return def;
    }

    private int getInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    private boolean getBool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        return def;
    }

    private String getString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v instanceof String && !((String) v).isBlank()) return (String) v;
        return def;
    }

    private double getDouble(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return def;
    }
}
