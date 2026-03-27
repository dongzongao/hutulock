<?php

declare(strict_types=1);

namespace HutuLock;

/**
 * HutuLock 分布式锁 PHP 客户端
 *
 * 协议：纯文本行（UTF-8），与 Java/Python/Go 客户端完全兼容。
 *
 * 用法：
 *   $client = new HutuLockClient([['127.0.0.1', 8881]]);
 *   $client->connect();
 *
 *   $token = $client->lock('order-lock');
 *   try {
 *       // 临界区
 *   } finally {
 *       $client->unlock($token);
 *   }
 *   $client->close();
 *
 * 要求：PHP 8.0+，无第三方依赖（仅 sockets 扩展）。
 */
class HutuLockClient
{
    /** @var array<array{0:string,1:int}> */
    private array $nodes;
    private float $connectTimeout;
    private float $lockTimeout;
    private float $watchdogInterval;

    /** @var resource|null TCP socket */
    private $socket = null;
    private ?string $sessionId = null;

    /** @var array<string, array{resolve: callable, reject: callable}> key => pending request */
    private array $pending = [];
    /** @var array<string, callable> seqPath => watcher callback */
    private array $watchers = [];

    /** @var string 未处理完的读缓冲区 */
    private string $readBuf = '';

    /** @var array<string, LockToken> lockName => token */
    private array $held = [];

    /** @var array<string, bool> lockName => watchdog running */
    private array $watchdogRunning = [];

    /**
     * @param array<array{0:string,1:int}> $nodes  集群节点列表，格式 [['host', port], ...]
     * @param float $connectTimeout  连接超时（秒），默认 5
     * @param float $lockTimeout     获取锁超时（秒），默认 30
     * @param float $watchdogInterval 看门狗心跳间隔（秒），默认 10
     */
    public function __construct(
        array $nodes,
        float $connectTimeout  = 5.0,
        float $lockTimeout     = 30.0,
        float $watchdogInterval = 10.0
    ) {
        if (empty($nodes)) {
            throw new \InvalidArgumentException('at least one node required');
        }
        $this->nodes            = $nodes;
        $this->connectTimeout   = $connectTimeout;
        $this->lockTimeout      = $lockTimeout;
        $this->watchdogInterval = $watchdogInterval;
    }

    public function getSessionId(): ?string
    {
        return $this->sessionId;
    }

    // ---- 连接 ----

    public function connect(): void
    {
        $nodes = $this->nodes;
        shuffle($nodes);
        $lastErr = null;

        foreach ($nodes as [$host, $port]) {
            try {
                $this->doConnect($host, $port);
                $this->sessionId = $this->establishSession();
                return;
            } catch (\Throwable $e) {
                $lastErr = $e;
            }
        }
        throw new ConnectionException('all nodes unreachable: ' . ($lastErr?->getMessage() ?? ''));
    }

    private function doConnect(string $host, int $port): void
    {
        $errno  = 0;
        $errstr = '';
        $sock = @fsockopen($host, $port, $errno, $errstr, $this->connectTimeout);
        if ($sock === false) {
            throw new ConnectionException("connect to {$host}:{$port} failed: {$errstr} ({$errno})");
        }
        stream_set_timeout($sock, (int)$this->connectTimeout, (int)(($this->connectTimeout - floor($this->connectTimeout)) * 1_000_000));
        stream_set_blocking($sock, false);
        $this->socket  = $sock;
        $this->readBuf = '';
    }

    private function establishSession(?string $existingId = null): string
    {
        $args = $existingId !== null ? [$existingId] : [];
        $resp = $this->request('CONNECT', new Message(Message::CONNECT, $args), $this->connectTimeout);
        if ($resp->type !== Message::CONNECTED) {
            throw new ProtocolException('unexpected response to CONNECT: ' . $resp->serialize());
        }
        return $resp->arg(0);
    }

    // ---- 获取锁 ----

    /**
     * 获取锁，返回 LockToken。超时抛出 LockTimeoutException。
     */
    public function lock(string $lockName, ?float $timeout = null): LockToken
    {
        $deadline = microtime(true) + ($timeout ?? $this->lockTimeout);
        $token    = $this->doAcquire($lockName, $deadline);
        $this->startWatchdog($token);
        $this->held[$lockName] = $token;
        return $token;
    }

    private function doAcquire(string $lockName, float $deadline): LockToken
    {
        $remaining = $deadline - microtime(true);
        if ($remaining <= 0) {
            throw new LockTimeoutException($lockName);
        }

        $resp = $this->request(
            "LOCK:{$lockName}",
            new Message(Message::LOCK, [$lockName, $this->sessionId]),
            $remaining
        );

        if ($resp->type === Message::REDIRECT) {
            $this->handleRedirect($resp->arg(0));
            return $this->doAcquire($lockName, $deadline);
        }

        if ($resp->type === Message::OK) {
            return new LockToken($lockName, $resp->arg(1), $this->sessionId);
        }

        if ($resp->type === Message::WAIT) {
            return $this->waitForLock($lockName, $resp->arg(1), $deadline);
        }

        throw new ProtocolException('unexpected response to LOCK: ' . $resp->serialize());
    }

    private function waitForLock(string $lockName, string $seqPath, float $deadline): LockToken
    {
        $remaining = $deadline - microtime(true);
        if ($remaining <= 0) {
            throw new LockTimeoutException($lockName);
        }

        $eventType = null;
        $this->watchers[$seqPath] = function (string $type) use (&$eventType): void {
            $eventType = $type;
        };

        // 轮询读取，等待 WATCH_EVENT
        $waitUntil = microtime(true) + $remaining;
        while (microtime(true) < $waitUntil) {
            $this->readAndDispatch(0.05);
            if ($eventType !== null) {
                break;
            }
        }

        if ($eventType === null) {
            unset($this->watchers[$seqPath]);
            throw new LockTimeoutException($lockName);
        }
        if ($eventType === 'SESSION_EXPIRED') {
            throw new SessionExpiredException();
        }

        return $this->recheck($lockName, $seqPath, $deadline);
    }

    private function recheck(string $lockName, string $seqPath, float $deadline): LockToken
    {
        $remaining = $deadline - microtime(true);
        if ($remaining <= 0) {
            throw new LockTimeoutException($lockName);
        }

        $resp = $this->request(
            "RECHECK:{$seqPath}",
            new Message(Message::RECHECK, [$lockName, $seqPath, $this->sessionId]),
            $remaining
        );

        if ($resp->type === Message::OK) {
            return new LockToken($lockName, $resp->arg(1), $this->sessionId);
        }
        if ($resp->type === Message::WAIT) {
            return $this->waitForLock($lockName, $resp->arg(1), $deadline);
        }
        throw new ProtocolException('unexpected response to RECHECK: ' . $resp->serialize());
    }

    // ---- 释放锁 ----

    public function unlock(LockToken $token): void
    {
        $token->stopWatchdog();
        unset($this->held[$token->lockName], $this->watchdogRunning[$token->lockName]);

        try {
            $this->request(
                "UNLOCK:{$token->lockName}",
                new Message(Message::UNLOCK, [$token->seqNodePath, $this->sessionId]),
                10.0
            );
        } catch (LockTimeoutException $e) {
            // 超时静默处理，服务端 TTL 会清理
        }
    }

    // ---- 看门狗（同步轮询实现） ----

    /**
     * PHP 单线程无法后台定时，看门狗通过 tick 函数模拟。
     * 调用 lock() 后，每次调用 tick() 会检查是否需要续期。
     * 也可在业务循环中定期调用 tick()。
     */
    private array $watchdogNextRenew = [];

    private function startWatchdog(LockToken $token): void
    {
        $this->watchdogRunning[$token->lockName]  = true;
        $this->watchdogNextRenew[$token->lockName] = microtime(true) + $this->watchdogInterval;

        $token->setStopWatchdog(function () use ($token): void {
            unset(
                $this->watchdogRunning[$token->lockName],
                $this->watchdogNextRenew[$token->lockName]
            );
        });
    }

    /**
     * 驱动看门狗续期，在持锁期间的业务循环中调用。
     * 若不调用，看门狗不会自动续期（PHP 单线程限制）。
     */
    public function tick(): void
    {
        $now = microtime(true);
        foreach ($this->watchdogRunning as $lockName => $_) {
            if (isset($this->watchdogNextRenew[$lockName]) && $now >= $this->watchdogNextRenew[$lockName]) {
                try {
                    $this->request(
                        "RENEW:{$lockName}",
                        new Message(Message::RENEW, [$lockName, $this->sessionId]),
                        5.0
                    );
                } catch (\Throwable $e) {
                    // 续期失败不抛出，下次再试
                }
                $this->watchdogNextRenew[$lockName] = $now + $this->watchdogInterval;
            }
        }
    }

    // ---- 重定向 ----

    private function handleRedirect(string $leaderId): void
    {
        if ($this->socket !== null) {
            fclose($this->socket);
            $this->socket = null;
        }
        $nodes = $this->nodes;
        shuffle($nodes);
        $lastErr = null;
        foreach ($nodes as [$host, $port]) {
            try {
                $this->doConnect($host, $port);
                $this->sessionId = $this->establishSession($this->sessionId);
                return;
            } catch (\Throwable $e) {
                $lastErr = $e;
            }
        }
        throw new ConnectionException('reconnect failed: ' . ($lastErr?->getMessage() ?? ''));
    }

    // ---- 底层 request/dispatch ----

    /**
     * 发送消息并同步等待对应 key 的响应。
     */
    private function request(string $key, Message $msg, float $timeout): Message
    {
        $this->send($msg);

        $result   = null;
        $error    = null;
        $deadline = microtime(true) + $timeout;

        $this->pending[$key] = [
            'resolve' => function (Message $m) use (&$result): void { $result = $m; },
            'reject'  => function (\Throwable $e) use (&$error): void { $error = $e; },
        ];

        while ($result === null && $error === null && microtime(true) < $deadline) {
            $this->readAndDispatch(min(0.05, $deadline - microtime(true)));
        }

        unset($this->pending[$key]);

        if ($error !== null) {
            throw $error;
        }
        if ($result === null) {
            throw new LockTimeoutException($key);
        }
        return $result;
    }

    private function send(Message $msg): void
    {
        if ($this->socket === null) {
            throw new ConnectionException('not connected');
        }
        $line = $msg->serialize() . "\n";
        $written = fwrite($this->socket, $line);
        if ($written === false) {
            throw new ConnectionException('write failed');
        }
    }

    /**
     * 非阻塞读取并分发响应，最多等待 $maxWait 秒。
     */
    private function readAndDispatch(float $maxWait): void
    {
        if ($this->socket === null) {
            return;
        }

        $read   = [$this->socket];
        $write  = null;
        $except = null;
        $sec    = (int)$maxWait;
        $usec   = (int)(($maxWait - $sec) * 1_000_000);

        $n = stream_select($read, $write, $except, $sec, $usec);
        if ($n === false || $n === 0) {
            return;
        }

        $chunk = fread($this->socket, 8192);
        if ($chunk === false || $chunk === '') {
            return;
        }
        $this->readBuf .= $chunk;

        while (($pos = strpos($this->readBuf, "\n")) !== false) {
            $line          = substr($this->readBuf, 0, $pos);
            $this->readBuf = substr($this->readBuf, $pos + 1);
            $this->dispatch(trim($line));
        }
    }

    private function dispatch(string $line): void
    {
        if ($line === '') {
            return;
        }

        // WATCH_EVENT 单独处理
        if (str_starts_with($line, Message::WATCH_EVENT)) {
            $this->handleWatchEvent($line);
            return;
        }

        try {
            $msg = Message::parse($line);
        } catch (\Throwable $e) {
            return;
        }

        switch ($msg->type) {
            case Message::CONNECTED:
                $this->complete('CONNECT', $msg);
                break;
            case Message::OK:
                $lockName = $msg->arg(0);
                $seqPath  = $msg->arg(1);
                $this->complete("LOCK:{$lockName}", $msg);
                $this->complete("RECHECK:{$seqPath}", $msg);
                break;
            case Message::WAIT:
                $this->complete('LOCK:' . $msg->arg(0), $msg);
                break;
            case Message::RELEASED:
                $this->complete('UNLOCK:' . $msg->arg(0), $msg);
                break;
            case Message::RENEWED:
                $this->complete('RENEW:' . $msg->arg(0), $msg);
                break;
            case Message::REDIRECT:
                $leaderId = $msg->arg(0);
                $err = new NotLeaderException($leaderId);
                foreach ($this->pending as $key => $cb) {
                    ($cb['reject'])($err);
                }
                $this->pending = [];
                break;
            case Message::ERROR:
                $errMsg = $msg->arg(0) ?: 'server error';
                $err = new ProtocolException($errMsg);
                foreach ($this->pending as $key => $cb) {
                    ($cb['reject'])($err);
                }
                $this->pending = [];
                break;
        }
    }

    private function handleWatchEvent(string $line): void
    {
        // 格式：WATCH_EVENT {type} {path}
        $parts = preg_split('/\s+/', $line, 3);
        if (count($parts) < 3) {
            return;
        }
        [, $eventType, $path] = $parts;
        if (isset($this->watchers[$path])) {
            $cb = $this->watchers[$path];
            unset($this->watchers[$path]);
            $cb($eventType);
        }
    }

    private function complete(string $key, Message $msg): void
    {
        if (isset($this->pending[$key])) {
            ($this->pending[$key]['resolve'])($msg);
            unset($this->pending[$key]);
        }
    }

    // ---- 关闭 ----

    public function close(): void
    {
        foreach ($this->held as $token) {
            $token->stopWatchdog();
        }
        $this->held    = [];
        $this->pending = [];
        $this->watchers = [];
        if ($this->socket !== null) {
            fclose($this->socket);
            $this->socket = null;
        }
    }

    public function __destruct()
    {
        $this->close();
    }
}
