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
package com.hutulock.client.example;

import com.hutulock.client.HutuLockClient;
import com.hutulock.client.LockContext;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HutuLock 客户端使用示例
 *
 * <p>演示：ZooKeeper 风格分布式锁 + 看门狗机制
 *
 * <p>运行前请先启动 HutuLockServer：
 * <pre>
 *   java -jar hutulock-server.jar node1 8881 9881
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class LockClientExample {

    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> runClient("client-1"));
        Thread t2 = new Thread(() -> runClient("client-2"));
        Thread t3 = new Thread(() -> runClient("client-3"));

        t1.start();
        Thread.sleep(100);
        t2.start();
        Thread.sleep(100);
        t3.start();

        t1.join();
        t2.join();
        t3.join();
    }

    private static void runClient(String name) {
        AtomicBoolean aborted = new AtomicBoolean(false);

        try (HutuLockClient client = HutuLockClient.builder()
                .addNode("127.0.0.1", 8881)
                .addNode("127.0.0.1", 8882)
                .addNode("127.0.0.1", 8883)
                .build()) {

            client.connect();
            System.out.printf("[%s] session=%s%n", name, client.getSessionId());

            // 构建带看门狗的锁上下文
            LockContext ctx = LockContext.builder("order-lock", client.getSessionId())
                .ttl(30, TimeUnit.SECONDS)           // 服务端 30s 无心跳则强制释放
                .watchdogInterval(9, TimeUnit.SECONDS) // 每 9s 发一次心跳（< ttl/3）
                .onExpired(lockName -> {
                    System.err.printf("[%s] lock [%s] expired! aborting%n", name, lockName);
                    aborted.set(true);
                })
                .build();

            System.out.printf("[%s] acquiring lock...%n", name);
            boolean acquired = client.lock(ctx, 30, TimeUnit.SECONDS);

            if (!acquired) {
                System.out.printf("[%s] failed to acquire lock%n", name);
                return;
            }

            System.out.printf("[%s] lock acquired (seq=%s), working...%n",
                name, ctx.getSeqNodePath());

            for (int i = 1; i <= 3; i++) {
                if (aborted.get()) {
                    System.out.printf("[%s] aborted at step %d%n", name, i);
                    return;
                }
                System.out.printf("[%s] step %d/3%n", name, i);
                Thread.sleep(1000);
            }

            client.unlock(ctx);
            System.out.printf("[%s] lock released%n", name);

        } catch (Exception e) {
            System.err.printf("[%s] error: %s%n", name, e.getMessage());
        }
    }
}
