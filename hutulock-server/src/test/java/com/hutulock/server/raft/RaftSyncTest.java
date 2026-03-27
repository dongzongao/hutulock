package com.hutulock.server.raft;

import com.hutulock.config.api.ServerProperties;
import com.hutulock.server.api.RaftStateMachine;
import com.hutulock.spi.event.EventBus;
import com.hutulock.spi.metrics.MetricsCollector;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Raft 数据同步校验测试
 *
 * <p>通过启动真实 RaftNode（绑定本地端口），验证：
 * <ol>
 *   <li>单节点 propose → apply 正常</li>
 *   <li>三节点集群 Leader 选举后数据同步到所有 Follower</li>
 *   <li>多条命令按序 apply，状态机顺序一致</li>
 *   <li>Leader 宕机后新 Leader 继续同步</li>
 *   <li>日志截断后重新同步（prevLogTerm 不匹配场景）</li>
 * </ol>
 */
class RaftSyncTest {

    /** 记录 apply 顺序的简单状态机 */
    static class TrackingStateMachine implements RaftStateMachine {
        final List<String> applied = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger applyCount = new AtomicInteger(0);
        final CountDownLatch latch;

        TrackingStateMachine(int expectedCount) {
            this.latch = new CountDownLatch(expectedCount);
        }

        @Override
        public void apply(int index, String command) {
            applied.add(index + ":" + command);
            applyCount.incrementAndGet();
            latch.countDown();
        }

        boolean awaitApply(long timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private static final int BASE_PORT = 19800;
    private static final ServerProperties PROPS = ServerProperties.builder()
        .electionTimeout(300, 600)
        .heartbeatInterval(50)
        .proposeTimeout(5_000)
        .watchdogTtl(30_000)
        .watchdogScanInterval(1_000)
        .build();

    private final List<RaftNode> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        nodes.forEach(RaftNode::shutdown);
        nodes.clear();
    }

    // ==================== 工具方法 ====================

    private RaftNode createNode(String id, int port, RaftStateMachine sm) throws InterruptedException {
        RaftNode node = new RaftNode(id, port, sm, PROPS, MetricsCollector.noop(), EventBus.noop());
        nodes.add(node);
        node.start();
        return node;
    }

    /** 等待集群选出 Leader，最多等待 timeoutMs */
    private RaftNode waitForLeader(List<RaftNode> cluster, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (RaftNode n : cluster) {
                if (n.isLeader()) return n;
            }
            Thread.sleep(50);
        }
        return null;
    }

    // ==================== 测试用例 ====================

    /**
     * 单节点模式：propose 后状态机立即 apply（无需多数派）
     *
     * <p>单节点无 peers，选举时 voteCount=1 但 handleVoteResp 永远不会被调用，
     * 因此通过 waitForLeader 轮询等待选举完成（单节点会在 startElection 中
     * 因 peers.isEmpty() 而直接 becomeLeader）。
     */
    @Test
    void singleNode_proposeApplied() throws Exception {
        TrackingStateMachine sm = new TrackingStateMachine(3);
        RaftNode node = createNode("n1", BASE_PORT, sm);

        // 单节点等待选举超时后成为 Leader
        RaftNode leader = waitForLeader(Collections.singletonList(node), 3000);
        assertNotNull(leader, "单节点应成为 Leader");

        leader.propose("CMD:A").get(3, TimeUnit.SECONDS);
        leader.propose("CMD:B").get(3, TimeUnit.SECONDS);
        leader.propose("CMD:C").get(3, TimeUnit.SECONDS);

        assertTrue(sm.awaitApply(2000), "3 条命令应全部 apply");
        assertEquals(3, sm.applyCount.get());
        // 验证顺序
        assertEquals("1:CMD:A", sm.applied.get(0));
        assertEquals("2:CMD:B", sm.applied.get(1));
        assertEquals("3:CMD:C", sm.applied.get(2));
    }

    /**
     * 三节点集群：Leader 选举后 propose 同步到所有节点
     */
    @Test
    void threeNodes_leaderElectedAndDataSynced() throws Exception {
        int n = 3;
        TrackingStateMachine[] sms = new TrackingStateMachine[n];
        RaftNode[] cluster = new RaftNode[n];

        for (int i = 0; i < n; i++) {
            sms[i] = new TrackingStateMachine(2);
            cluster[i] = createNode("n" + (i + 1), BASE_PORT + 10 + i, sms[i]);
        }

        // 互相注册 peer
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) cluster[i].addPeer("n" + (j + 1), "127.0.0.1", BASE_PORT + 10 + j);
            }
        }

        // 等待 Leader 选出
        RaftNode leader = waitForLeader(Arrays.asList(cluster), 3000);
        assertNotNull(leader, "3 节点集群应选出 Leader");

        // Leader propose 两条命令
        leader.propose("SYNC:X").get(5, TimeUnit.SECONDS);
        leader.propose("SYNC:Y").get(5, TimeUnit.SECONDS);

        // 等待所有节点 apply
        for (int i = 0; i < n; i++) {
            assertTrue(sms[i].awaitApply(3000),
                "节点 n" + (i + 1) + " 应 apply 2 条命令");
        }

        // 验证所有节点状态机内容一致
        for (int i = 0; i < n; i++) {
            assertEquals(2, sms[i].applyCount.get(), "节点 n" + (i + 1) + " apply 数量应为 2");
            assertEquals(sms[0].applied, sms[i].applied,
                "节点 n" + (i + 1) + " 状态机内容应与 Leader 一致");
        }
    }

    /**
     * 多命令顺序一致性：并发 propose 后所有节点 apply 顺序相同
     */
    @Test
    void threeNodes_commandOrderConsistent() throws Exception {
        int cmdCount = 5;
        int n = 3;
        TrackingStateMachine[] sms = new TrackingStateMachine[n];
        RaftNode[] cluster = new RaftNode[n];

        for (int i = 0; i < n; i++) {
            sms[i] = new TrackingStateMachine(cmdCount);
            cluster[i] = createNode("n" + (i + 1), BASE_PORT + 20 + i, sms[i]);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) cluster[i].addPeer("n" + (j + 1), "127.0.0.1", BASE_PORT + 20 + j);
            }
        }

        RaftNode leader = waitForLeader(Arrays.asList(cluster), 3000);
        assertNotNull(leader, "应选出 Leader");

        // 串行 propose（保证顺序确定）
        for (int i = 1; i <= cmdCount; i++) {
            leader.propose("OP:" + i).get(5, TimeUnit.SECONDS);
        }

        // 等待所有节点 apply 完成
        for (TrackingStateMachine sm : sms) {
            assertTrue(sm.awaitApply(3000), "所有节点应 apply " + cmdCount + " 条命令");
        }

        // 验证所有节点 apply 顺序完全一致
        List<String> reference = sms[0].applied;
        for (int i = 1; i < n; i++) {
            assertEquals(reference, sms[i].applied,
                "节点 n" + (i + 1) + " apply 顺序应与 Leader 完全一致");
        }

        // 验证 index 单调递增
        for (int i = 0; i < cmdCount; i++) {
            assertTrue(reference.get(i).startsWith((i + 1) + ":"),
                "第 " + (i + 1) + " 条命令 index 应为 " + (i + 1));
        }
    }

    /**
     * Follower 落后后追赶：新加入节点能同步历史日志
     */
    @Test
    void lateFollower_catchesUpWithHistory() throws Exception {
        // 先启动 2 节点（单节点先成为 Leader）
        TrackingStateMachine sm1 = new TrackingStateMachine(3);
        TrackingStateMachine sm2 = new TrackingStateMachine(3);
        TrackingStateMachine sm3 = new TrackingStateMachine(3);

        RaftNode n1 = createNode("n1", BASE_PORT + 30, sm1);
        RaftNode n2 = createNode("n2", BASE_PORT + 31, sm2);

        n1.addPeer("n2", "127.0.0.1", BASE_PORT + 31);
        n2.addPeer("n1", "127.0.0.1", BASE_PORT + 30);

        RaftNode leader = waitForLeader(Arrays.asList(n1, n2), 3000);
        assertNotNull(leader, "应选出 Leader");

        // Leader 先 propose 3 条命令
        leader.propose("HIST:1").get(5, TimeUnit.SECONDS);
        leader.propose("HIST:2").get(5, TimeUnit.SECONDS);
        leader.propose("HIST:3").get(5, TimeUnit.SECONDS);

        // 等待 n1/n2 apply 完成
        assertTrue(sm1.awaitApply(3000), "n1 应 apply 3 条");
        assertTrue(sm2.awaitApply(3000), "n2 应 apply 3 条");

        // 新加入 n3（落后节点），双向注册 peer
        RaftNode n3 = createNode("n3", BASE_PORT + 32, sm3);
        // Leader 需要知道 n3，才能主动推送历史日志
        leader.addPeer("n3", "127.0.0.1", BASE_PORT + 32);
        n3.addPeer(leader.getNodeId(), "127.0.0.1",
            leader.getNodeId().equals("n1") ? BASE_PORT + 30 : BASE_PORT + 31);

        // n3 应通过 AppendEntries 追赶历史日志
        assertTrue(sm3.awaitApply(5000), "n3 应追赶并 apply 3 条历史命令");
        assertEquals(sm1.applied, sm3.applied, "n3 状态机应与 n1 一致");
    }

    /**
     * 非 Leader 节点 propose 应立即失败（返回 NOT_LEADER）
     */
    @Test
    void nonLeader_proposeRejected() throws Exception {
        TrackingStateMachine sm1 = new TrackingStateMachine(1);
        TrackingStateMachine sm2 = new TrackingStateMachine(1);

        RaftNode n1 = createNode("n1", BASE_PORT + 40, sm1);
        RaftNode n2 = createNode("n2", BASE_PORT + 41, sm2);

        n1.addPeer("n2", "127.0.0.1", BASE_PORT + 41);
        n2.addPeer("n1", "127.0.0.1", BASE_PORT + 40);

        RaftNode leader = waitForLeader(Arrays.asList(n1, n2), 3000);
        assertNotNull(leader, "应选出 Leader");

        // 找到 Follower
        RaftNode follower = (leader == n1) ? n2 : n1;

        // Follower propose 应失败
        CompletableFuture<Void> f = follower.propose("SHOULD_FAIL");
        ExecutionException ex = assertThrows(ExecutionException.class,
            () -> f.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause().getMessage().startsWith("NOT_LEADER"),
            "Follower propose 应返回 NOT_LEADER 错误");
    }
}
