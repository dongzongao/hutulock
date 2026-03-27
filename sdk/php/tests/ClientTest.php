<?php

declare(strict_types=1);

namespace HutuLock\Tests;

use HutuLock\HutuLockClient;
use HutuLock\LockToken;
use HutuLock\LockTimeoutException;
use HutuLock\Message;
use HutuLock\ProtocolException;
use PHPUnit\Framework\TestCase;

/**
 * HutuLock PHP SDK 单元测试
 *
 * 使用 stream_socket_pair 模拟服务端，不依赖真实服务。
 */
class ClientTest extends TestCase
{
    // ---- 工具：创建 socket pair ----

    /**
     * 返回 [serverStream, clientStream]
     * @return resource[]
     */
    private function makePair(): array
    {
        $pair = stream_socket_pair(STREAM_PF_UNIX, STREAM_SOCK_STREAM, STREAM_IPPROTO_IP);
        $this->assertNotFalse($pair, 'stream_socket_pair failed');
        stream_set_blocking($pair[0], false);
        stream_set_blocking($pair[1], false);
        return $pair;
    }

    /**
     * 向 server stream 写一行
     * @param resource $server
     */
    private function serverSend($server, string $line): void
    {
        fwrite($server, $line . "\n");
    }

    /**
     * 从 server stream 读一行（阻塞等待最多 1s）
     * @param resource $server
     */
    private function serverRecv($server): string
    {
        $buf = '';
        $deadline = microtime(true) + 1.0;
        while (microtime(true) < $deadline) {
            $chunk = fread($server, 4096);
            if ($chunk !== false && $chunk !== '') {
                $buf .= $chunk;
                if (str_contains($buf, "\n")) {
                    return trim(explode("\n", $buf, 2)[0]);
                }
            }
            usleep(5_000);
        }
        return trim($buf);
    }

    /**
     * 构造一个已注入 socket 的 HutuLockClient（绕过 TCP 连接）
     * @param resource $clientStream
     */
    private function makeClient($clientStream, float $lockTimeout = 3.0): HutuLockClient
    {
        $client = new HutuLockClient([['127.0.0.1', 0]], 3.0, $lockTimeout, 60.0);

        // 通过反射注入 socket 和 sessionId
        $ref = new \ReflectionClass($client);

        $sockProp = $ref->getProperty('socket');
        $sockProp->setAccessible(true);
        $sockProp->setValue($client, $clientStream);

        $sidProp = $ref->getProperty('sessionId');
        $sidProp->setAccessible(true);
        $sidProp->setValue($client, 'sess-test');

        return $client;
    }

    // ---- 协议测试 ----

    public function testParseSimple(): void
    {
        $msg = Message::parse('CONNECTED sess-abc');
        $this->assertSame('CONNECTED', $msg->type);
        $this->assertSame('sess-abc', $msg->arg(0));
    }

    public function testParseNoArgs(): void
    {
        $msg = Message::parse('CONNECT');
        $this->assertSame('CONNECT', $msg->type);
        $this->assertSame([], $msg->args);
    }

    public function testSerialize(): void
    {
        $msg = new Message('LOCK', ['order-lock', 'sess-1']);
        $this->assertSame('LOCK order-lock sess-1', $msg->serialize());
    }

    public function testArgOutOfRange(): void
    {
        $msg = new Message('OK', ['lock-name']);
        $this->assertSame('', $msg->arg(5));
    }

    // ---- 客户端测试 ----

    public function testLockOK(): void
    {
        [$server, $client] = $this->makePair();
        $hutu = $this->makeClient($client);

        // 服务端：读 LOCK，回 OK
        $serverThread = function () use ($server): void {
            $this->serverRecv($server); // LOCK
            $this->serverSend($server, 'OK order-lock /locks/order-lock/seq-0000000001');
        };

        // 异步执行服务端逻辑（PHP 单线程，用 pcntl_fork 或直接在同一进程里交替）
        // 这里用 tick 方式：先注册服务端响应，再调用 lock
        $responded = false;
        register_tick_function(function () use ($server, &$responded): void {
            if (!$responded) {
                $line = $this->serverRecv($server);
                if (str_starts_with($line, 'LOCK')) {
                    $this->serverSend($server, 'OK order-lock /locks/order-lock/seq-0000000001');
                    $responded = true;
                }
            }
        });

        // 直接用反射调用 request 测试底层
        $ref = new \ReflectionClass($hutu);
        $requestMethod = $ref->getMethod('request');
        $requestMethod->setAccessible(true);

        // 先让服务端发响应
        $this->serverSend($server, 'OK order-lock /locks/order-lock/seq-0000000001');

        $resp = $requestMethod->invoke(
            $hutu,
            'LOCK:order-lock',
            new Message('LOCK', ['order-lock', 'sess-test']),
            2.0
        );

        $this->assertSame('OK', $resp->type);
        $this->assertSame('/locks/order-lock/seq-0000000001', $resp->arg(1));

        fclose($server);
        $hutu->close();
    }

    public function testConnectResponse(): void
    {
        [$server, $client] = $this->makePair();
        $hutu = $this->makeClient($client);

        $ref = new \ReflectionClass($hutu);
        $requestMethod = $ref->getMethod('request');
        $requestMethod->setAccessible(true);

        $this->serverSend($server, 'CONNECTED sess-xyz');

        $resp = $requestMethod->invoke(
            $hutu,
            'CONNECT',
            new Message('CONNECT'),
            2.0
        );

        $this->assertSame('CONNECTED', $resp->type);
        $this->assertSame('sess-xyz', $resp->arg(0));

        fclose($server);
        $hutu->close();
    }

    public function testRequestTimeout(): void
    {
        [$server, $client] = $this->makePair();
        $hutu = $this->makeClient($client, 0.1);

        $ref = new \ReflectionClass($hutu);
        $requestMethod = $ref->getMethod('request');
        $requestMethod->setAccessible(true);

        $this->expectException(LockTimeoutException::class);
        $requestMethod->invoke(
            $hutu,
            'LOCK:no-reply',
            new Message('LOCK', ['no-reply', 'sess-test']),
            0.1
        );

        fclose($server);
        $hutu->close();
    }

    public function testErrorResponse(): void
    {
        [$server, $client] = $this->makePair();
        $hutu = $this->makeClient($client);

        $ref = new \ReflectionClass($hutu);
        $requestMethod = $ref->getMethod('request');
        $requestMethod->setAccessible(true);

        $this->serverSend($server, 'ERROR PERMISSION_DENIED');

        $this->expectException(ProtocolException::class);
        $requestMethod->invoke(
            $hutu,
            'LOCK:order-lock',
            new Message('LOCK', ['order-lock', 'sess-test']),
            2.0
        );

        fclose($server);
        $hutu->close();
    }

    public function testWatchEventDispatch(): void
    {
        [$server, $client] = $this->makePair();
        $hutu = $this->makeClient($client);

        $ref = new \ReflectionClass($hutu);

        // 注册 watcher
        $watchersProp = $ref->getProperty('watchers');
        $watchersProp->setAccessible(true);

        $received = null;
        $watchersProp->setValue($hutu, [
            '/locks/order-lock/seq-1' => function (string $type) use (&$received): void {
                $received = $type;
            },
        ]);

        // 服务端发 WATCH_EVENT
        $this->serverSend($server, 'WATCH_EVENT NODE_DELETED /locks/order-lock/seq-1');

        // 触发读取
        $readMethod = $ref->getMethod('readAndDispatch');
        $readMethod->setAccessible(true);
        $readMethod->invoke($hutu, 0.2);

        $this->assertSame('NODE_DELETED', $received);

        fclose($server);
        $hutu->close();
    }

    public function testUnlockSilentOnTimeout(): void
    {
        [$server, $client] = $this->makePair();
        $hutu = $this->makeClient($client);

        $token = new LockToken('ghost-lock', '/locks/ghost-lock/seq-1', 'sess-test');

        // 服务端不响应，unlock 应静默处理
        $this->expectNotToPerformAssertions();
        $hutu->unlock($token);

        fclose($server);
        $hutu->close();
    }
}
