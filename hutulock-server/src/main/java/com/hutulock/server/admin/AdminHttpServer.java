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
package com.hutulock.server.admin;

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
 * Admin console HTTP server — serves the Vue 3 frontend API at /api/admin/*.
 *
 * Endpoints:
 *   POST /api/admin/login          — login, returns token
 *   POST /api/admin/logout         — logout
 *   GET  /api/admin/cluster        — cluster status (auth required)
 *   GET  /api/admin/sessions       — session list (auth required)
 *   GET  /api/admin/locks          — lock status (auth required)
 *   POST /api/admin/members/add    — add member (auth required)
 *   POST /api/admin/members/remove — remove member (auth required)
 */
public class AdminHttpServer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdminHttpServer.class);

    private final int                    port;
    private final String                 nodeId;
    private final RaftNode               raftNode;
    private final DefaultSessionManager  sessionManager;
    private final DefaultZNodeTree       zNodeTree;
    private final AdminTokenStore        tokenStore = new AdminTokenStore();
    private HttpServer                   server;
    private ScheduledExecutorService     cleaner;

    public AdminHttpServer(int port, String nodeId, RaftNode raftNode,
                           DefaultSessionManager sessionManager, DefaultZNodeTree zNodeTree) {
        this.port           = port;
        this.nodeId         = nodeId;
        this.raftNode       = raftNode;
        this.sessionManager = sessionManager;
        this.zNodeTree      = zNodeTree;
    }

    @Override
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        server.createContext("/api/admin/login",          this::handleLogin);
        server.createContext("/api/admin/logout",         this::handleLogout);
        server.createContext("/api/admin/cluster",        this::handleCluster);
        server.createContext("/api/admin/sessions",       this::handleSessions);
        server.createContext("/api/admin/locks",          this::handleLocks);
        server.createContext("/api/admin/members/add",    this::handleAddMember);
        server.createContext("/api/admin/members/remove", this::handleRemoveMember);

        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "hutulock-admin-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hutulock-admin-token-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(tokenStore::evictExpired, 1, 1, TimeUnit.HOURS);

        log.info("Admin HTTP server started on port {} — default user: {}/{}",
            port, AdminTokenStore.DEFAULT_USERNAME, AdminTokenStore.DEFAULT_PASSWORD);
    }

    @Override
    public void shutdown() {
        if (cleaner != null) cleaner.shutdownNow();
        if (server != null) { server.stop(0); log.info("Admin HTTP server stopped"); }
    }

    // ==================== Auth ====================

    private boolean authenticated(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return tokenStore.validate(auth.substring(7));
        }
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
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }
        Map<String, String> body = parseJsonBody(ex);
        String token = tokenStore.login(body.get("username"), body.get("password"));
        if (token == null) { sendJson(ex, 401, "{\"error\":\"invalid credentials\"}"); return; }
        log.info("Admin login: user={}", body.get("username"));
        sendJson(ex, 200, "{\"token\":\"" + token + "\",\"username\":\"" + esc(body.get("username")) + "\"}");
    }

    // ==================== POST /api/admin/logout ====================

    private void handleLogout(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        String token = extractToken(ex);
        if (token != null) tokenStore.logout(token);
        sendJson(ex, 200, "{\"status\":\"ok\"}");
    }

    // ==================== GET /api/admin/cluster ====================

    private void handleCluster(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        ClusterConfig cfg = raftNode.getClusterConfig();
        StringBuilder sb = new StringBuilder("{");
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
        sb.append("],\"membershipChangePending\":").append(raftNode.isMembershipChangePending()).append("}");
        sendJson(ex, 200, sb.toString());
    }

    // ==================== GET /api/admin/sessions ====================

    private void handleSessions(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        List<com.hutulock.model.session.Session> sessions = sessionManager.listSessions();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sessions.size(); i++) {
            com.hutulock.model.session.Session s = sessions.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"sessionId\":\"").append(esc(s.getSessionId())).append("\",");
            sb.append("\"clientId\":\"").append(esc(s.getClientId())).append("\",");
            sb.append("\"state\":\"").append(s.getState()).append("\",");
            sb.append("\"expireTime\":").append(s.getExpireTime()).append(",");
            sb.append("\"ttlMs\":").append(Math.max(0, s.getExpireTime() - System.currentTimeMillis())).append("}");
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    // ==================== GET /api/admin/locks ====================

    private void handleLocks(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        ZNodePath locksRoot = ZNodePath.of("/locks");
        StringBuilder sb = new StringBuilder("[");
        if (zNodeTree != null && zNodeTree.exists(locksRoot)) {
            List<ZNodePath> lockNames = zNodeTree.getChildren(locksRoot);
            for (int i = 0; i < lockNames.size(); i++) {
                ZNodePath lockPath = lockNames.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"lockName\":\"").append(esc(lockPath.name())).append("\",\"holders\":[");
                List<ZNodePath> seqNodes = zNodeTree.getChildren(lockPath);
                for (int j = 0; j < seqNodes.size(); j++) {
                    ZNodePath seqPath = seqNodes.get(j);
                    ZNode node = zNodeTree.get(seqPath);
                    if (j > 0) sb.append(",");
                    sb.append("{\"seqPath\":\"").append(esc(seqPath.value())).append("\",");
                    sb.append("\"sessionId\":\"").append(esc(node != null ? str(node.getSessionId()) : "")).append("\",");
                    sb.append("\"isHolder\":").append(j == 0).append(",");
                    sb.append("\"createTime\":").append(node != null ? node.getCreateTime() : 0).append("}");
                }
                sb.append("]}");
            }
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    // ==================== POST /api/admin/members/add ====================

    private void handleAddMember(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        Map<String, String> params = parseJsonBody(ex);
        String newNodeId = params.get("nodeId");
        String host      = params.get("host");
        String portStr   = params.get("port");
        if (newNodeId == null || host == null || portStr == null) {
            sendJson(ex, 400, "{\"error\":\"missing required params: nodeId, host, port\"}"); return;
        }
        int raftPort;
        try { raftPort = Integer.parseInt(portStr); }
        catch (NumberFormatException e) { sendJson(ex, 400, "{\"error\":\"invalid port\"}"); return; }
        try {
            raftNode.addMember(newNodeId, host, raftPort);
            sendJson(ex, 202, "{\"status\":\"accepted\",\"nodeId\":\"" + esc(newNodeId) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }    }

    // ==================== POST /api/admin/members/remove ====================

    private void handleRemoveMember(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
        if (!authenticated(ex)) { sendJson(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        Map<String, String> params = parseJsonBody(ex);
        String removeNodeId = params.get("nodeId");
        if (removeNodeId == null) { sendJson(ex, 400, "{\"error\":\"missing required param: nodeId\"}"); return; }
        try {
            raftNode.removeMember(removeNodeId);
            sendJson(ex, 202, "{\"status\":\"accepted\",\"nodeId\":\"" + esc(removeNodeId) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // ==================== Utilities ====================

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private Map<String, String> parseJsonBody(HttpExchange ex) throws IOException {
        String body;
        try (InputStream is = ex.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        Map<String, String> result = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        while (m.find()) result.put(m.group(1), m.group(2));
        java.util.regex.Matcher m2 = java.util.regex.Pattern
            .compile("\"([^\"]+)\"\\s*:\\s*(\\d+)").matcher(body);
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
