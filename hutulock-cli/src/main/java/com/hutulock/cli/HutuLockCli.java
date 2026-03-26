/*
 * Copyright 2024 HutuLock Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hutulock.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

/**
 * HutuLock 交互式命令行工具（REPL）
 *
 * <p>启动方式：
 * <pre>
 *   java -jar hutulock-cli.jar
 *   java -jar hutulock-cli.jar 127.0.0.1:8881 127.0.0.1:8882  # 启动时自动连接
 * </pre>
 *
 * <p>支持命令：
 * <pre>
 *   connect 127.0.0.1:8881 127.0.0.1:8882   连接集群
 *   lock order-lock                           获取锁（默认 30s 超时）
 *   lock order-lock 60                        获取锁（60s 超时）
 *   unlock order-lock                         释放锁
 *   renew order-lock                          手动续期
 *   status                                    查看状态
 *   disconnect                                断开连接
 *   help                                      显示帮助
 *   exit                                      退出
 * </pre>
 *
 * @author HutuLock Authors
 * @since 1.0.0
 */
public class HutuLockCli {

    private static final String PROMPT    = "hutulock> ";
    private static final String VERSION   = "1.0.0";
    private static final String ANSI_RESET  = "\u001B[0m";
    private static final String ANSI_GREEN  = "\u001B[32m";
    private static final String ANSI_RED    = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN   = "\u001B[36m";
    private static final String ANSI_BOLD   = "\u001B[1m";

    public static void main(String[] args) throws Exception {
        printBanner();

        try (CliContext ctx = new CliContext()) {
            // 启动时自动连接（如果提供了节点参数）
            if (args.length > 0) {
                try {
                    ctx.connect(Arrays.asList(args));
                    printSuccess("Connected to: " + String.join(", ", args));
                } catch (Exception e) {
                    printError("Failed to connect: " + e.getMessage());
                }
            }

            // 主 REPL 循环
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                System.out.print(buildPrompt(ctx));
                System.out.flush();

                String line = reader.readLine();
                if (line == null) break; // EOF（Ctrl+D）

                line = line.trim();
                if (line.isEmpty()) continue;

                if (!processCommand(line, ctx)) break;
            }
        }

        System.out.println("\nBye!");
    }

    /**
     * 处理一条命令，返回 false 表示退出。
     */
    private static boolean processCommand(String line, CliContext ctx) {
        String[] parts = line.split("\\s+");
        String   cmdName = parts[0].toLowerCase();

        CliCommand cmd = CliCommand.of(cmdName);
        if (cmd == null) {
            printError("Unknown command: " + cmdName + ". Type 'help' for available commands.");
            return true;
        }

        try {
            switch (cmd) {
                case CONNECT:    return handleConnect(parts, ctx);
                case LOCK:       return handleLock(parts, ctx);
                case UNLOCK:     return handleUnlock(parts, ctx);
                case RENEW:      return handleRenew(parts, ctx);
                case STATUS:     return handleStatus(ctx);
                case DISCONNECT: return handleDisconnect(ctx);
                case HELP:       return handleHelp(parts);
                case EXIT:       return false;
                default:
                    printError("Command not implemented: " + cmd.getName());
                    return true;
            }
        } catch (IllegalStateException e) {
            printError(e.getMessage());
            return true;
        } catch (Exception e) {
            printError("Error: " + e.getMessage());
            return true;
        }
    }

    // ==================== 命令处理器 ====================

    private static boolean handleConnect(String[] parts, CliContext ctx) throws Exception {
        if (parts.length < 2) {
            printUsage(CliCommand.CONNECT);
            return true;
        }
        List<String> nodes = Arrays.asList(parts).subList(1, parts.length);
        ctx.connect(nodes);
        printSuccess("Connected to: " + String.join(", ", nodes));
        printInfo("Session ID: " + ctx.getSessionId());
        return true;
    }

    private static boolean handleLock(String[] parts, CliContext ctx) throws Exception {
        if (parts.length < 2) {
            printUsage(CliCommand.LOCK);
            return true;
        }
        String lockName = parts[1];
        int timeout = parts.length >= 3 ? Integer.parseInt(parts[2]) : 30;

        printInfo("Acquiring lock [" + lockName + "] (timeout=" + timeout + "s)...");
        long start = System.currentTimeMillis();
        boolean acquired = ctx.lock(lockName, timeout);
        long elapsed = System.currentTimeMillis() - start;

        if (acquired) {
            String seqPath = ctx.getHeldLocks().get(lockName).getSeqNodePath();
            printSuccess("Lock acquired: " + lockName + " [" + seqPath + "] in " + elapsed + "ms");
        } else {
            printError("Failed to acquire lock [" + lockName + "] within " + timeout + "s");
        }
        return true;
    }

    private static boolean handleUnlock(String[] parts, CliContext ctx) throws Exception {
        if (parts.length < 2) {
            printUsage(CliCommand.UNLOCK);
            return true;
        }
        String lockName = parts[1];
        ctx.unlock(lockName);
        printSuccess("Lock released: " + lockName);
        return true;
    }

    private static boolean handleRenew(String[] parts, CliContext ctx) throws Exception {
        if (parts.length < 2) {
            printUsage(CliCommand.RENEW);
            return true;
        }
        ctx.renew(parts[1]);
        printSuccess("Lock renewed: " + parts[1]);
        return true;
    }

    private static boolean handleStatus(CliContext ctx) {
        System.out.println(ANSI_CYAN + ctx.getStatus() + ANSI_RESET);
        return true;
    }

    private static boolean handleDisconnect(CliContext ctx) {
        ctx.disconnect();
        printSuccess("Disconnected");
        return true;
    }

    private static boolean handleHelp(String[] parts) {
        if (parts.length >= 2) {
            // 显示特定命令的帮助
            CliCommand cmd = CliCommand.of(parts[1]);
            if (cmd == null) {
                printError("Unknown command: " + parts[1]);
            } else {
                System.out.println(ANSI_BOLD + cmd.getName() + " " + cmd.getArgs() + ANSI_RESET);
                System.out.println(cmd.getDescription());
            }
        } else {
            // 显示所有命令
            System.out.println(ANSI_BOLD + "Available commands:" + ANSI_RESET);
            for (CliCommand cmd : CliCommand.values()) {
                System.out.println(cmd.toHelpLine());
            }
            System.out.println("\nType 'help <command>' for detailed usage.");
        }
        return true;
    }

    // ==================== 输出工具 ====================

    private static void printBanner() {
        System.out.println(ANSI_CYAN + ANSI_BOLD);
        System.out.println("  _   _       _         _                _    ");
        System.out.println(" | | | |_   _| |_ _   _| |    ___   ___| | __");
        System.out.println(" | |_| | | | | __| | | | |   / _ \\ / __| |/ /");
        System.out.println(" |  _  | |_| | |_| |_| | |__| (_) | (__|   < ");
        System.out.println(" |_| |_|\\__,_|\\__|\\__,_|_____\\___/ \\___|_|\\_\\");
        System.out.println(ANSI_RESET);
        System.out.println(" Distributed Lock CLI v" + VERSION);
        System.out.println(" Type 'help' for available commands, 'exit' to quit.");
        System.out.println();
    }

    private static String buildPrompt(CliContext ctx) {
        if (ctx.isConnected()) {
            int lockCount = ctx.getHeldLocks().size();
            String lockInfo = lockCount > 0 ? ANSI_YELLOW + "[" + lockCount + " lock(s)]" + ANSI_RESET + " " : "";
            return ANSI_GREEN + "hutulock" + ANSI_RESET + "(" +
                ANSI_CYAN + ctx.getSessionId().substring(0, 8) + ANSI_RESET + ")> " + lockInfo;
        }
        return ANSI_RED + "hutulock(disconnected)" + ANSI_RESET + "> ";
    }

    private static void printSuccess(String msg) {
        System.out.println(ANSI_GREEN + "✓ " + msg + ANSI_RESET);
    }

    private static void printError(String msg) {
        System.out.println(ANSI_RED + "✗ " + msg + ANSI_RESET);
    }

    private static void printInfo(String msg) {
        System.out.println(ANSI_CYAN + "  " + msg + ANSI_RESET);
    }

    private static void printUsage(CliCommand cmd) {
        System.out.println(ANSI_YELLOW + "Usage: " + cmd.getName() + " " + cmd.getArgs() + ANSI_RESET);
    }
}
