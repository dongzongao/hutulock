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
import com.hutulock.client.fast.ReadWriteSplitClient;

import java.util.concurrent.TimeUnit;

/**
 * 金融交易场景示例（强一致性）
 *
 * <p>场景：账户转账，要求强一致性保证
 *
 * <p>特点：
 * <ul>
 *   <li>强一致性：所有读写操作都通过 Raft 共识</li>
 *   <li>无数据丢失：即使网络分区也能保证数据一致性</li>
 *   <li>适用场景：金融交易、支付、账户操作</li>
 * </ul>
 *
 * @author HutuLock Authors
 * @since 1.1.0
 */
public class FinancialTransactionExample {

    public static void main(String[] args) throws Exception {
        // 1. 创建客户端（连接到 3 节点集群）
        HutuLockClient client = HutuLockClient.builder()
            .addNode("127.0.0.1", 8881)
            .addNode("127.0.0.1", 8882)
            .addNode("127.0.0.1", 8883)
            .build();
        client.connect();

        // 2. 创建强一致性客户端（金融场景）
        ReadWriteSplitClient strongClient = new ReadWriteSplitClient(client, true);

        System.out.println("=== 金融交易场景示例（强一致性） ===");
        System.out.println("一致性模式: " + (strongClient.isStrongConsistency() ? "强一致性" : "最终一致性"));

        // 3. 账户转账示例
        String fromAccount = "account-A";
        String toAccount = "account-B";
        String transferLock = "transfer-" + fromAccount + "-" + toAccount;

        System.out.println("\n开始转账: " + fromAccount + " -> " + toAccount);

        // 4. 检查锁是否可用（强一致性读取）
        if (strongClient.isLockAvailable(transferLock)) {
            System.out.println("✓ 转账锁可用，尝试获取...");

            // 5. 获取转账锁（通过 Raft 共识）
            boolean locked = strongClient.tryLockAsync(transferLock, 10, TimeUnit.SECONDS)
                .get(10, TimeUnit.SECONDS);

            if (locked) {
                System.out.println("✓ 成功获取转账锁");

                try {
                    // 6. 执行转账操作（临界区）
                    System.out.println("  - 检查账户余额...");
                    Thread.sleep(100);  // 模拟数据库查询

                    System.out.println("  - 扣减源账户...");
                    Thread.sleep(100);  // 模拟数据库更新

                    System.out.println("  - 增加目标账户...");
                    Thread.sleep(100);  // 模拟数据库更新

                    System.out.println("  - 记录交易日志...");
                    Thread.sleep(100);  // 模拟日志写入

                    System.out.println("✓ 转账成功");

                } finally {
                    // 7. 释放锁
                    strongClient.unlockAsync(transferLock).get(5, TimeUnit.SECONDS);
                    System.out.println("✓ 释放转账锁");
                }
            } else {
                System.out.println("✗ 获取转账锁失败（可能有其他转账正在进行）");
            }
        } else {
            System.out.println("✗ 转账锁不可用（有其他转账正在进行）");
        }

        // 8. 并发转账示例
        System.out.println("\n=== 并发转账测试 ===");
        int concurrentTransfers = 5;

        for (int i = 0; i < concurrentTransfers; i++) {
            final int transferId = i;
            new Thread(() -> {
                try {
                    String lockName = "transfer-concurrent-" + transferId;
                    boolean success = strongClient.tryLockAsync(lockName, 5, TimeUnit.SECONDS)
                        .get(5, TimeUnit.SECONDS);

                    if (success) {
                        System.out.println("  [Transfer-" + transferId + "] 获取锁成功，执行转账...");
                        Thread.sleep(500);  // 模拟转账处理
                        strongClient.unlockAsync(lockName).get(5, TimeUnit.SECONDS);
                        System.out.println("  [Transfer-" + transferId + "] 转账完成");
                    } else {
                        System.out.println("  [Transfer-" + transferId + "] 获取锁失败");
                    }
                } catch (Exception e) {
                    System.err.println("  [Transfer-" + transferId + "] 错误: " + e.getMessage());
                }
            }).start();
        }

        // 等待所有转账完成
        Thread.sleep(3000);

        // 9. 关闭客户端
        client.close();
        System.out.println("\n客户端已关闭");
    }
}
