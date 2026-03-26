/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.server.metrics;

import com.hutulock.spi.metrics.MetricsCollector;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prometheus Metrics 收集器（{@link MetricsCollector} 的 Prometheus 实现）
 *
 * <p>使用 Micrometer 作为门面，Prometheus 作为后端。
 * 通过 {@link MetricsHttpServer} 暴露 {@code GET /metrics} 端点供 Prometheus 抓取。
 *
 * <p>指标命名规范：{@code hutulock_{component}_{metric}_{unit}}
 *
 * <p>自动注册 JVM 指标（内存、GC、线程）和系统指标（CPU）。
 *
 * @author HutuLock Authors
 * @since 1.0.0
 * @see MetricsCollector
 */
public class PrometheusMetricsCollector implements MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsCollector.class);

    private final PrometheusMeterRegistry registry;

    // ---- 锁指标 ----
    private final Counter lockAcquiredCounter;
    private final Counter lockWaitingCounter;
    private final Counter lockGrantedFromQueueCounter;
    private final Counter lockReleasedCounter;
    private final Counter lockExpiredCounter;
    private final Timer   lockAcquireDuration;

    // ---- 会话指标 ----
    private final AtomicInteger activeSessionGauge = new AtomicInteger(0);
    private final Counter sessionCreatedCounter;
    private final Counter sessionExpiredCounter;
    private final Counter sessionClosedCounter;
    private final Counter sessionReconnectedCounter;

    // ---- ZNode 指标 ----
    private final AtomicInteger znodeTotalGauge = new AtomicInteger(0);
    private final Counter znodeCreatedCounter;
    private final Counter znodeDeletedCounter;
    private final Counter watcherRegisteredCounter;
    private final Counter watcherFiredCounter;

    // ---- Raft 指标 ----
    private final Counter raftElectionCounter;
    private final Counter raftLeaderChangeCounter;
    private final Counter raftProposeSuccessCounter;
    private final Counter raftProposeTimeoutCounter;
    private final Counter raftProposeRejectedCounter;
    private final Timer   raftProposeDuration;

    /**
     * 构造 Prometheus Metrics 收集器。
     *
     * @param nodeId 节点 ID，作为公共 tag 附加到所有指标
     */
    public PrometheusMetricsCollector(String nodeId) {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.registry.config().commonTags("node", nodeId);

        // 注册 JVM & 系统指标
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        // 初始化锁指标
        lockAcquiredCounter        = counter("hutulock.lock.acquire.total",   "result", "ok");
        lockWaitingCounter         = counter("hutulock.lock.acquire.total",   "result", "wait");
        lockGrantedFromQueueCounter= counter("hutulock.lock.acquire.total",   "result", "granted_from_queue");
        lockReleasedCounter        = counter("hutulock.lock.release.total");
        lockExpiredCounter         = counter("hutulock.lock.expire.total");
        lockAcquireDuration        = timer("hutulock.lock.acquire.duration");

        // 初始化会话指标
        sessionCreatedCounter    = counter("hutulock.session.created.total");
        sessionExpiredCounter    = counter("hutulock.session.expired.total");
        sessionClosedCounter     = counter("hutulock.session.closed.total");
        sessionReconnectedCounter= counter("hutulock.session.reconnected.total");
        Gauge.builder("hutulock.session.active", activeSessionGauge, AtomicInteger::doubleValue)
             .register(registry);

        // 初始化 ZNode 指标
        znodeCreatedCounter      = counter("hutulock.znode.created.total");
        znodeDeletedCounter      = counter("hutulock.znode.deleted.total");
        watcherRegisteredCounter = counter("hutulock.watcher.registered.total");
        watcherFiredCounter      = counter("hutulock.watcher.fired.total");
        Gauge.builder("hutulock.znode.total", znodeTotalGauge, AtomicInteger::doubleValue)
             .register(registry);

        // 初始化 Raft 指标
        raftElectionCounter      = counter("hutulock.raft.election.total");
        raftLeaderChangeCounter  = counter("hutulock.raft.leader.change.total");
        raftProposeSuccessCounter= counter("hutulock.raft.propose.total", "result", "ok");
        raftProposeTimeoutCounter= counter("hutulock.raft.propose.total", "result", "timeout");
        raftProposeRejectedCounter=counter("hutulock.raft.propose.total", "result", "rejected");
        raftProposeDuration      = timer("hutulock.raft.propose.duration");

        log.info("PrometheusMetricsCollector initialized for node={}", nodeId);
    }

    // ==================== MetricsCollector 实现 ====================

    @Override public void onLockAcquired(String l)             { lockAcquiredCounter.increment();         }
    @Override public void onLockWaiting(String l)              { lockWaitingCounter.increment();          }
    @Override public void onLockGrantedFromQueue(String l)     { lockGrantedFromQueueCounter.increment(); }
    @Override public void onLockReleased(String l)             { lockReleasedCounter.increment();         }
    @Override public void onLockExpired(String l)              { lockExpiredCounter.increment();          }
    @Override public void recordLockAcquireDuration(String l, long ms) {
        lockAcquireDuration.record(ms, TimeUnit.MILLISECONDS);
    }

    @Override public void onSessionCreated()    { sessionCreatedCounter.increment();     activeSessionGauge.incrementAndGet(); }
    @Override public void onSessionExpired()    { sessionExpiredCounter.increment();     activeSessionGauge.decrementAndGet(); }
    @Override public void onSessionClosed()     { sessionClosedCounter.increment();      activeSessionGauge.decrementAndGet(); }
    @Override public void onSessionReconnected(){ sessionReconnectedCounter.increment(); }

    @Override public void onZNodeCreated()       { znodeCreatedCounter.increment();      znodeTotalGauge.incrementAndGet(); }
    @Override public void onZNodeDeleted()       { znodeDeletedCounter.increment();      znodeTotalGauge.decrementAndGet(); }
    @Override public void onWatcherRegistered()  { watcherRegisteredCounter.increment(); }
    @Override public void onWatcherFired()       { watcherFiredCounter.increment();      }

    @Override public void onRaftElectionStarted()  { raftElectionCounter.increment();       }
    @Override public void onRaftLeaderChanged()    { raftLeaderChangeCounter.increment();   }
    @Override public void onRaftProposeSuccess()   { raftProposeSuccessCounter.increment(); }
    @Override public void onRaftProposeTimeout()   { raftProposeTimeoutCounter.increment(); }
    @Override public void onRaftProposeRejected()  { raftProposeRejectedCounter.increment();}
    @Override public void recordRaftProposeDuration(long ms) {
        raftProposeDuration.record(ms, TimeUnit.MILLISECONDS);
    }

    // ==================== Prometheus 端点 ====================

    /**
     * 获取 Prometheus 格式的 scrape 内容。
     *
     * @return Prometheus text format 字符串
     */
    public String scrape() { return registry.scrape(); }

    public PrometheusMeterRegistry getRegistry() { return registry; }

    // ==================== 工具方法 ====================

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(registry);
    }
}
