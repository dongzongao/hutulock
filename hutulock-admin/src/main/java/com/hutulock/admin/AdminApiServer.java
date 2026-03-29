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
package com.hutulock.admin;

import com.hutulock.model.znode.ZNode;
import com.hutulock.model.znode.ZNodePath;
import com.hutulock.server.ioc.Lifecycle;
import com.hutulock.server.impl.DefaultSessionManager;
import com.hutulock.server.impl.DefaultZNodeTree;
import com.hutulock.server.raft.ClusterConfig;
import com.hutulock.server.raft.RaftNode;
import com.hutulock.server.raft.RaftPeer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Admin 控制台 HTTP 服务器
 *
 * <p>端点列表：
 * <ul>
 *   <li>{@code POST /api/admin/login}          — 登录，返回 token</li>
 *   <li>{@code POST /api/admin/logout}         — 注销</li>
 *   <li>{@code GET  /api/admin/cluster}        — 集群状态（需鉴权）</li>
 *   <li>{@code GET  /api/admin/sessions}       — 会话列表（需鉴权）</li>
 *   <li>{@code GET  /api/admin/locks}          — 锁状态（需鉴权）</li>
 *   <li>{@code POST /api/admin/members/add}    — 添加成员（需鉴权）</li>
 *   <li>{@code POST /api/admin/members/remove} — 移除成员（需鉴权）</li>
 *   <li>{@code GET  /}                         — 前端 SPA 入口（index.html）</li>
 *   <li>{@code GET  /assets/*}                 — 静态资源</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class AdminApiServer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdminApiServer.class);

    private final int                   port;
    private final String                nodeId;
    private final RaftNode              raftNode;
    private final DefaultSessionManager sessionManager;
    private final DefaultZNodeTree      zNodeTree;
    private final AdminTokenStore       tokenStore;
    private HttpServer                  server;
    private ScheduledExecutorService    cleaner;

    public AdminApiServer(int port, String nodeId, RaftNode raftNode,
                          DefaultSessionManager sessionManager, DefaultZNodeTree zNodeTree) {
        this.port           = port;
        this.nodeId         = nodeId;
        this.raftNode       = raftNode;
        this.sessionManager = sessionManager;
        this.zNodeTree      = zNodeTree;
        this.tokenStore     = new AdminTokenStore(
            com.hutulock.config.api.ServerProperties.builder().build().adminUsername,
            com.hutulock.config.api.ServerProperties.builder().build().adminPassword,
            com.hutulock.config.api.ServerProperties.builder().build().adminTokenTtlMs);
    }

    public AdminApiServer(int port, String nodeId, RaftNode raftNode,
                          DefaultSessionManager sessionManager, DefaultZNodeTree zNodeTree,
                          com.hutulock.config.api.ServerProperties props) {
        this.port           = port;
        this.nodeId         = nodeId;
        this.raftNode       = raftNode;
        this.sessionManager = sessionManager;
        this.zNodeTree      = zNodeTree;
        this.tokenStore     = new AdminTokenStore(
            props.adminUsername, props.adminPassword, props.adminTokenTtlMs);
    }

    @Override
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        // API 路由
        server.createContext("/api/admin/login",          this::handleLogin);
        server.createContext("/api/admin/logout",         this::handleLogout);
        server.createContext("/api/admin/cluster",        this::handleCluster);
        server.createContext("/api/admin/sessions",       this::handleSessions);
        server.createContext("/api/admin/locks",          this::handleLocks);
        server.createContext("/api/admin/members/add",    this::handleAddMember);
        server.createContext("/api/admin/members/remove", this::handleRemoveMember);

        // 静态资源（前端 SPA）
        server.createContext("/", this::handleStatic);

        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "hutulock-admin-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        // 定期清理过期 token
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hutulock-admin-token-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(tokenStore::evictExpired, 1, 1, TimeUnit.HOURS);

        log.info("Admin console started on port {} — user: {}",
            port, tokenStore.getUsername());
    }

    @Override
    public void shutdown() {
        if (cleaner != null) cleaner.shutdownNow();
        if (server != null) { server.stop(0); log.info("Admin console stopped"); }
    }

    // ==================== 鉴权工具 ====================

    /** 从请求头或 Cookie 中提取 token，验证有效性。 */
    private boolean authenticated(HttpExchange ex) {
        // 优先从 Authorization: Bearer <token> 读取
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return tokenStore.validate(auth.substring(7));
        }
        // 其次从 Cookie: admin_token=<token> 读取
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && "admin_token".equals(kv[0].trim())) {
                    return tokenStore.validate(kv[1].trim());
                }
            }
        }
        return false;
    }

    private String extractToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && "admin_token".equals(kv[0].trim())) return kv[1].trim();
            }
        }
        return null;
    }

    // ==================== POST /api/admin/login ====================

    private void handleLogin(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }
        Map<String, String> body = parseJsonBody(ex);
        String username = body.get("username");
        String password = body.get("password");
        String token = tokenStore.login(username, password);
        if (token == null) {
            sendJson(ex, 401, "{\"error\":\"invalid credentials\"}");
            return;
        }
        log.info("Admin login: user={}", username);
        sendJson(ex, 200, "{\"token\":\"" + token + "\",\"username\":\"" + esc(username) + "\"}");
    }

    // ==================== POST /api/admin/logout ====================

    private void handleLogout(HttpExchange ex) throws IOException {
        String token = extractToken(ex);
        if (token != null) tokenStore.logout(token);
        sendJson(ex, 200, "{\"status\":\"ok\"}");
    }

    // ==================== GET /api/admin/cluster ====================

    private void handleCluster(HttpExchange ex) throws IOException {
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        ClusterConfig cfg = raftNode.getClusterConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"nodeId\":\"").append(esc(nodeId)).append("\",");
        sb.append("\"role\":\"").append(raftNode.getRole()).append("\",");
        sb.append("\"leaderId\":\"").append(esc(str(raftNode.getLeaderId()))).append("\",");
        sb.append("\"configPhase\":\"").append(cfg.phase).append("\",");
        sb.append("\"members\":").append(toJsonArray(cfg.allVoters())).append(",");
        sb.append("\"peers\":[");
        List<RaftPeer> peers = raftNode.getPeers();
        for (int i = 0; i < peers.size(); i++) {
            RaftPeer p = peers.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"nodeId\":\"").append(esc(p.nodeId)).append("\",");
            sb.append("\"host\":\"").append(esc(p.host)).append("\",");
            sb.append("\"port\":").append(p.port).append(",");
            sb.append("\"nextIndex\":").append(p.nextIndex).append(",");
            sb.append("\"matchIndex\":").append(p.matchIndex).append(",");
            sb.append("\"inFlight\":").append(p.inFlight).append("}");
        }
        sb.append("],");
        sb.append("\"membershipChangePending\":").append(raftNode.isMembershipChangePending());
        sb.append("}");
        sendJson(ex, 200, sb.toString());
    }

    // ==================== GET /api/admin/sessions ====================

    private void handleSessions(HttpExchange ex) throws IOException {
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        List<com.hutulock.model.session.Session> sessions = sessionManager.listSessions();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sessions.size(); i++) {
            com.hutulock.model.session.Session s = sessions.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"sessionId\":\"").append(esc(s.getSessionId())).append("\",");
            sb.append("\"clientId\":\"").append(esc(s.getClientId())).append("\",");
            sb.append("\"state\":\"").append(s.getState()).append("\",");
            sb.append("\"expireTime\":").append(s.getExpireTime()).append(",");
            sb.append("\"ttlMs\":").append(Math.max(0, s.getExpireTime() - System.currentTimeMillis()));
            sb.append("}");
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    // ==================== GET /api/admin/locks ====================

    private void handleLocks(HttpExchange ex) throws IOException {
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        ZNodePath locksRoot = ZNodePath.of("/locks");
        StringBuilder sb = new StringBuilder("[");
        if (zNodeTree != null && zNodeTree.exists(locksRoot)) {
            List<ZNodePath> lockNames = zNodeTree.getChildren(locksRoot);
            for (int i = 0; i < lockNames.size(); i++) {
                ZNodePath lockPath = lockNames.get(i);
                if (i > 0) sb.append(",");
                sb.append("{");
                sb.append("\"lockName\":\"").append(esc(lockPath.name())).append("\",");
                sb.append("\"holders\":[");
                List<ZNodePath> seqNodes = zNodeTree.getChildren(lockPath);
                for (int j = 0; j < seqNodes.size(); j++) {
                    ZNodePath seqPath = seqNodes.get(j);
                    ZNode node = zNodeTree.get(seqPath);
                    if (j > 0) sb.append(",");
                    sb.append("{");
                    sb.append("\"seqPath\":\"").append(esc(seqPath.value())).append("\",");
                    sb.append("\"sessionId\":\"").append(esc(node != null ? str(node.getSessionId()) : "")).append("\",");
                    sb.append("\"isHolder\":").append(j == 0).append(",");
                    sb.append("\"createTime\":").append(node != null ? node.getCreateTime() : 0);
                    sb.append("}");
                }
                sb.append("]}");
            }
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    // ==================== POST /api/admin/members/add ====================

    private void handleAddMember(HttpExchange ex) throws IOException {
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        Map<String, String> params = parseJsonBody(ex);
        String newNodeId = params.get("nodeId");
        String host      = params.get("host");
        String portStr   = params.get("port");

        if (newNodeId == null || host == null || portStr == null) {
            sendJson(ex, 400, "{\"error\":\"missing required params: nodeId, host, port\"}");
            return;
        }
        int raftPort;
        try { raftPort = Integer.parseInt(portStr); }
        catch (NumberFormatException e) { sendJson(ex, 400, "{\"error\":\"invalid port\"}"); return; }

        try {
            raftNode.addMember(newNodeId, host, raftPort);
            sendJson(ex, 202, "{\"status\":\"accepted\",\"nodeId\":\"" + esc(newNodeId) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // ==================== POST /api/admin/members/remove ====================

    private void handleRemoveMember(HttpExchange ex) throws IOException {
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        Map<String, String> params = parseJsonBody(ex);
        String removeNodeId = params.get("nodeId");
        if (removeNodeId == null) {
            sendJson(ex, 400, "{\"error\":\"missing required param: nodeId\"}");
            return;
        }
        try {
            raftNode.removeMember(removeNodeId);
            sendJson(ex, 202, "{\"status\":\"accepted\",\"nodeId\":\"" + esc(removeNodeId) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // ==================== 静态资源服务 ====================

    private void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }
        String path = ex.getRequestURI().getPath();

        // 所有非 /api/ 路径都返回 index.html（SPA 路由）
        String resource = path.startsWith("/assets/") ? path : "/index.html";
        String resPath = "admin-ui" + resource;

        InputStream is = getClass().getClassLoader().getResourceAsStream(resPath);
        if (is == null) {
            // fallback: 返回内嵌的最小 HTML（前端未构建时的占位）
            byte[] body = buildFallbackHtml().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            return;
        }

        String contentType = guessContentType(resource);
        byte[] body = is.readAllBytes();
        is.close();
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }

    /** 前端未构建时的占位页面（提示用户构建前端）。 */
    private String buildFallbackHtml() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>HutuLock Admin</title>"
            + "<style>body{font-family:system-ui;display:flex;align-items:center;justify-content:center;"
            + "height:100vh;margin:0;background:#f5f7fa;color:#333}"
            + ".box{text-align:center;padding:40px;background:#fff;border-radius:12px;"
            + "box-shadow:0 4px 20px rgba(0,0,0,.1)}"
            + "h1{color:#1a1a2e;margin-bottom:8px}p{color:#666;margin:4px 0}"
            + "code{background:#f0f2f5;padding:2px 8px;border-radius:4px;font-size:.9em}"
            + "</style></head><body>"
            + "<div class='box'>"
            + "<h1>🔒 HutuLock Admin</h1>"
            + "<p>Node: <code>" + esc(nodeId) + "</code></p>"
            + "<p style='margin-top:16px;color:#999'>API 端点已就绪，前端资源未找到</p>"
            + "<p>API: <code>POST /api/admin/login</code></p>"
            + "</div></body></html>";
    }

    // ==================== 工具方法 ====================

    private void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    /** 解析简单 JSON body（仅支持顶层 string 字段，无需引入 JSON 库）。 */
    private Map<String, String> parseJsonBody(HttpExchange ex) throws IOException {
        String body;
        try (InputStream is = ex.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        Map<String, String> result = new LinkedHashMap<>();
        // 简单解析：匹配 "key":"value" 或 "key":number
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(body);
        while (m.find()) result.put(m.group(1), m.group(2));
        // 数字字段
        java.util.regex.Matcher m2 = java.util.regex.Pattern
            .compile("\"([^\"]+)\"\\s*:\\s*(\\d+)")
            .matcher(body);
        while (m2.find()) result.putIfAbsent(m2.group(1), m2.group(2));
        return result;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static String toJsonArray(Collection<String> items) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String item : items) {
            if (!first) sb.append(",");
            sb.append("\"").append(esc(item)).append("\"");
            first = false;
        }
        return sb.append("]").toString();
    }
}
