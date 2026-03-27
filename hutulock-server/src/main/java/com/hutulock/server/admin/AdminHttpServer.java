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

/**
 * Web 管理控制台 HTTP 服务器
 *
 * <p>使用 JDK 内置 {@link HttpServer}，无额外依赖，暴露以下端点：
 * <ul>
 *   <li>{@code GET  /admin/}              — 内嵌 HTML 控制台页面</li>
 *   <li>{@code GET  /admin/cluster}       — 集群状态 JSON</li>
 *   <li>{@code GET  /admin/sessions}      — 活跃会话列表 JSON</li>
 *   <li>{@code GET  /admin/locks}         — 当前锁状态 JSON</li>
 *   <li>{@code POST /admin/members/add}   — 动态添加成员（body: nodeId=n4&host=127.0.0.1&port=9884）</li>
 *   <li>{@code POST /admin/members/remove}— 动态移除成员（body: nodeId=n4）</li>
 * </ul>
 *
 * <p>安全：默认仅允许 localhost 访问，可通过构造函数自定义白名单。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class AdminHttpServer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdminHttpServer.class);

    private final int                  port;
    private final String               nodeId;
    private final RaftNode             raftNode;
    private final DefaultSessionManager sessionManager;
    private final DefaultZNodeTree     zNodeTree;
    private final Set<String>          allowedHosts;
    private HttpServer                 server;

    public AdminHttpServer(int port, String nodeId, RaftNode raftNode,
                           DefaultSessionManager sessionManager, DefaultZNodeTree zNodeTree) {
        this(port, nodeId, raftNode, sessionManager, zNodeTree,
            Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1"));
    }

    public AdminHttpServer(int port, String nodeId, RaftNode raftNode,
                           DefaultSessionManager sessionManager, DefaultZNodeTree zNodeTree,
                           Set<String> allowedHosts) {
        this.port           = port;
        this.nodeId         = nodeId;
        this.raftNode       = raftNode;
        this.sessionManager = sessionManager;
        this.zNodeTree      = zNodeTree;
        this.allowedHosts   = allowedHosts != null && !allowedHosts.isEmpty()
            ? allowedHosts : Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    }

    @Override
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/admin/",        this::handleUi);
        server.createContext("/admin/cluster", this::handleCluster);
        server.createContext("/admin/sessions",this::handleSessions);
        server.createContext("/admin/locks",   this::handleLocks);
        server.createContext("/admin/members/add",    this::handleAddMember);
        server.createContext("/admin/members/remove", this::handleRemoveMember);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "hutulock-admin-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.info("Admin HTTP server started on port {} (GET /admin/)", port);
    }

    @Override
    public void shutdown() {
        if (server != null) {
            server.stop(0);
            log.info("Admin HTTP server stopped");
        }
    }

    // ==================== 访问控制 ====================

    private boolean checkAccess(HttpExchange ex) throws IOException {
        String remoteIp = ex.getRemoteAddress().getAddress().getHostAddress();
        if (!allowedHosts.contains(remoteIp)) {
            log.warn("Admin access denied from {}", remoteIp);
            sendJson(ex, 403, "{\"error\":\"forbidden\"}");
            return false;
        }
        return true;
    }

    // ==================== GET /admin/ — HTML 控制台 ====================

    private void handleUi(HttpExchange ex) throws IOException {
        if (!checkAccess(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }
        byte[] body = buildHtml().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ==================== GET /admin/cluster ====================

    private void handleCluster(HttpExchange ex) throws IOException {
        if (!checkAccess(ex)) return;
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

    // ==================== GET /admin/sessions ====================

    private void handleSessions(HttpExchange ex) throws IOException {
        if (!checkAccess(ex)) return;
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

    // ==================== GET /admin/locks ====================

    private void handleLocks(HttpExchange ex) throws IOException {
        if (!checkAccess(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        ZNodePath locksRoot = ZNodePath.of("/locks");
        StringBuilder sb = new StringBuilder("[");
        if (zNodeTree.exists(locksRoot)) {
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

    // ==================== POST /admin/members/add ====================

    private void handleAddMember(HttpExchange ex) throws IOException {
        if (!checkAccess(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        Map<String, String> params = parseBody(ex);
        String newNodeId = params.get("nodeId");
        String host      = params.get("host");
        String portStr   = params.get("port");

        if (newNodeId == null || host == null || portStr == null) {
            sendJson(ex, 400, "{\"error\":\"missing required params: nodeId, host, port\"}");
            return;
        }
        int port;
        try { port = Integer.parseInt(portStr); }
        catch (NumberFormatException e) { sendJson(ex, 400, "{\"error\":\"invalid port\"}"); return; }

        try {
            raftNode.addMember(newNodeId, host, port)
                .whenComplete((v, err) -> log.info("addMember({}) complete, err={}", newNodeId, err));
            sendJson(ex, 202, "{\"status\":\"accepted\",\"nodeId\":\"" + esc(newNodeId) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // ==================== POST /admin/members/remove ====================

    private void handleRemoveMember(HttpExchange ex) throws IOException {
        if (!checkAccess(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }

        Map<String, String> params = parseBody(ex);
        String removeNodeId = params.get("nodeId");
        if (removeNodeId == null) {
            sendJson(ex, 400, "{\"error\":\"missing required param: nodeId\"}");
            return;
        }
        try {
            raftNode.removeMember(removeNodeId)
                .whenComplete((v, err) -> log.info("removeMember({}) complete, err={}", removeNodeId, err));
            sendJson(ex, 202, "{\"status\":\"accepted\",\"nodeId\":\"" + esc(removeNodeId) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // ==================== 工具方法 ====================

    private void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    /** 解析 application/x-www-form-urlencoded 请求体。 */
    private Map<String, String> parseBody(HttpExchange ex) throws IOException {
        String body;
        try (InputStream is = ex.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) params.put(decode(kv[0]), decode(kv[1]));
        }
        return params;
    }

    private static String decode(String s) {
        try { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    /** JSON 字符串转义（防止注入）。 */
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

    // ==================== 内嵌 HTML 控制台 ====================

    private String buildHtml() {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head>" +
            "<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>HutuLock Admin — " + esc(nodeId) + "</title>" +
            "<style>" +
            "body{font-family:system-ui,sans-serif;margin:0;background:#f5f7fa;color:#222}" +
            "header{background:#1a1a2e;color:#fff;padding:14px 24px;display:flex;align-items:center;gap:12px}" +
            "header h1{margin:0;font-size:1.2rem;font-weight:600}" +
            "#badge{padding:3px 10px;border-radius:12px;font-size:.8rem;font-weight:700;background:#555}" +
            "#badge.LEADER{background:#22c55e}" +
            "#badge.FOLLOWER{background:#3b82f6}" +
            "#badge.CANDIDATE{background:#f59e0b}" +
            "main{padding:20px 24px;display:grid;gap:16px;grid-template-columns:1fr 1fr}" +
            "@media(max-width:800px){main{grid-template-columns:1fr}}" +
            "section{background:#fff;border-radius:8px;box-shadow:0 1px 4px rgba(0,0,0,.08);padding:16px}" +
            "section h2{margin:0 0 12px;font-size:.95rem;color:#555;text-transform:uppercase;letter-spacing:.05em}" +
            "table{width:100%;border-collapse:collapse;font-size:.85rem}" +
            "th{text-align:left;padding:6px 8px;background:#f0f2f5;color:#666;font-weight:600}" +
            "td{padding:6px 8px;border-bottom:1px solid #f0f2f5}" +
            "tr:last-child td{border-bottom:none}" +
            ".tag{display:inline-block;padding:2px 8px;border-radius:10px;font-size:.75rem;font-weight:600}" +
            ".tag.holder{background:#dcfce7;color:#166534}" +
            ".tag.waiting{background:#fef9c3;color:#854d0e}" +
            ".form-row{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}" +
            "input{padding:6px 10px;border:1px solid #d1d5db;border-radius:6px;font-size:.85rem;flex:1;min-width:80px}" +
            "button{padding:6px 14px;border:none;border-radius:6px;cursor:pointer;font-size:.85rem;font-weight:600}" +
            ".btn-add{background:#22c55e;color:#fff}" +
            ".btn-remove{background:#ef4444;color:#fff}" +
            ".btn-refresh{background:#e5e7eb;color:#374151}" +
            "#msg{margin-top:8px;font-size:.82rem;color:#555}" +
            "</style></head><body>" +
            "<header>" +
            "<h1>HutuLock Admin</h1>" +
            "<span id=\"badge\">...</span>" +
            "<span style=\"margin-left:auto;font-size:.8rem;opacity:.7\">node: " + esc(nodeId) + "</span>" +
            "</header>" +
            "<main>" +

            // 集群状态
            "<section style=\"grid-column:1/-1\">" +
            "<h2>集群状态 <button class=\"btn-refresh\" onclick=\"loadAll()\">刷新</button></h2>" +
            "<div id=\"cluster-info\" style=\"font-size:.85rem;color:#555;margin-bottom:10px\"></div>" +
            "<table><thead><tr><th>节点 ID</th><th>Host</th><th>Port</th><th>nextIndex</th><th>matchIndex</th><th>inFlight</th></tr></thead>" +
            "<tbody id=\"peers-body\"></tbody></table>" +
            "</section>" +

            // 成员变更
            "<section>" +
            "<h2>成员变更</h2>" +
            "<div class=\"form-row\">" +
            "<input id=\"add-id\" placeholder=\"节点 ID (如 n4)\"/>" +
            "<input id=\"add-host\" placeholder=\"Host\"/>" +
            "<input id=\"add-port\" placeholder=\"Port\" style=\"max-width:80px\"/>" +
            "<button class=\"btn-add\" onclick=\"addMember()\">添加</button>" +
            "</div>" +
            "<div class=\"form-row\" style=\"margin-top:10px\">" +
            "<input id=\"rm-id\" placeholder=\"节点 ID\"/>" +
            "<button class=\"btn-remove\" onclick=\"removeMember()\">移除</button>" +
            "</div>" +
            "<div id=\"msg\"></div>" +
            "</section>" +

            // 会话
            "<section>" +
            "<h2>活跃会话</h2>" +
            "<table><thead><tr><th>Session ID</th><th>Client</th><th>状态</th><th>剩余 TTL</th></tr></thead>" +
            "<tbody id=\"sessions-body\"></tbody></table>" +
            "</section>" +

            // 锁
            "<section style=\"grid-column:1/-1\">" +
            "<h2>锁状态</h2>" +
            "<table><thead><tr><th>锁名</th><th>顺序节点</th><th>Session</th><th>角色</th><th>创建时间</th></tr></thead>" +
            "<tbody id=\"locks-body\"></tbody></table>" +
            "</section>" +

            "</main>" +
            "<script>" +
            "async function api(path,opts){" +
            "  try{const r=await fetch(path,opts);return await r.json()}" +
            "  catch(e){return{error:e.message}}" +
            "}" +
            "async function loadCluster(){" +
            "  const d=await api('/admin/cluster');" +
            "  if(d.error){document.getElementById('cluster-info').textContent='Error: '+d.error;return;}" +
            "  const badge=document.getElementById('badge');" +
            "  badge.textContent=d.role;badge.className=d.role;" +
            "  document.getElementById('cluster-info').innerHTML=" +
            "    '<b>Leader:</b> '+(d.leaderId||'—')+' &nbsp; <b>Config:</b> '+d.configPhase+" +
            "    ' &nbsp; <b>Members:</b> '+d.members.join(', ')+" +
            "    (d.membershipChangePending?' &nbsp; <span style=\"color:#f59e0b\">⚠ 变更进行中</span>':'');" +
            "  const tb=document.getElementById('peers-body');tb.innerHTML='';" +
            "  d.peers.forEach(p=>{" +
            "    const tr=document.createElement('tr');" +
            "    tr.innerHTML=`<td>${p.nodeId}</td><td>${p.host}</td><td>${p.port}</td>" +
            "      <td>${p.nextIndex}</td><td>${p.matchIndex}</td>" +
            "      <td>${p.inFlight?'<span style=\"color:#f59e0b\">●</span>':'<span style=\"color:#22c55e\">●</span>'}</td>`;" +
            "    tb.appendChild(tr);" +
            "  });" +
            "}" +
            "async function loadSessions(){" +
            "  const d=await api('/admin/sessions');" +
            "  if(!Array.isArray(d))return;" +
            "  const tb=document.getElementById('sessions-body');tb.innerHTML='';" +
            "  d.forEach(s=>{" +
            "    const tr=document.createElement('tr');" +
            "    const ttl=Math.max(0,Math.round(s.ttlMs/1000));" +
            "    tr.innerHTML=`<td style=\"font-size:.78rem\">${s.sessionId.substring(0,12)}…</td>" +
            "      <td>${s.clientId}</td><td>${s.state}</td><td>${ttl}s</td>`;" +
            "    tb.appendChild(tr);" +
            "  });" +
            "}" +
            "async function loadLocks(){" +
            "  const d=await api('/admin/locks');" +
            "  if(!Array.isArray(d))return;" +
            "  const tb=document.getElementById('locks-body');tb.innerHTML='';" +
            "  d.forEach(lock=>{" +
            "    lock.holders.forEach((h,i)=>{" +
            "      const tr=document.createElement('tr');" +
            "      const role=h.isHolder?'<span class=\"tag holder\">持有</span>':'<span class=\"tag waiting\">等待</span>';" +
            "      const t=new Date(h.createTime).toLocaleTimeString();" +
            "      tr.innerHTML=`<td>${i===0?lock.lockName:''}</td><td style=\"font-size:.78rem\">${h.seqPath.split('/').pop()}</td>" +
            "        <td style=\"font-size:.78rem\">${h.sessionId.substring(0,12)}…</td><td>${role}</td><td>${t}</td>`;" +
            "      tb.appendChild(tr);" +
            "    });" +
            "    if(lock.holders.length===0){" +
            "      const tr=document.createElement('tr');" +
            "      tr.innerHTML=`<td>${lock.lockName}</td><td colspan=4 style=\"color:#aaa\">无等待者</td>`;" +
            "      tb.appendChild(tr);" +
            "    }" +
            "  });" +
            "}" +
            "function loadAll(){loadCluster();loadSessions();loadLocks();}" +
            "async function addMember(){" +
            "  const id=document.getElementById('add-id').value.trim();" +
            "  const host=document.getElementById('add-host').value.trim();" +
            "  const port=document.getElementById('add-port').value.trim();" +
            "  if(!id||!host||!port){setMsg('请填写完整信息');return;}" +
            "  const r=await api('/admin/members/add',{method:'POST'," +
            "    headers:{'Content-Type':'application/x-www-form-urlencoded'}," +
            "    body:`nodeId=${encodeURIComponent(id)}&host=${encodeURIComponent(host)}&port=${encodeURIComponent(port)}`});" +
            "  setMsg(r.error?'错误: '+r.error:'已提交添加请求，等待 Raft 确认…');loadCluster();" +
            "}" +
            "async function removeMember(){" +
            "  const id=document.getElementById('rm-id').value.trim();" +
            "  if(!id){setMsg('请填写节点 ID');return;}" +
            "  const r=await api('/admin/members/remove',{method:'POST'," +
            "    headers:{'Content-Type':'application/x-www-form-urlencoded'}," +
            "    body:`nodeId=${encodeURIComponent(id)}`});" +
            "  setMsg(r.error?'错误: '+r.error:'已提交移除请求，等待 Raft 确认…');loadCluster();" +
            "}" +
            "function setMsg(m){document.getElementById('msg').textContent=m;}" +
            "loadAll();" +
            "setInterval(loadAll,3000);" +
            "</script></body></html>";
    }
}
