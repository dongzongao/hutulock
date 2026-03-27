<?php

declare(strict_types=1);

namespace HutuLock;

/**
 * 持有锁的凭证，由 HutuLockClient::lock() 返回。
 */
class LockToken
{
    public string $lockName;
    public string $seqNodePath;
    public string $sessionId;

    /** @var callable|null 停止看门狗的回调 */
    private $stopWatchdog = null;

    public function __construct(string $lockName, string $seqNodePath, string $sessionId)
    {
        $this->lockName    = $lockName;
        $this->seqNodePath = $seqNodePath;
        $this->sessionId   = $sessionId;
    }

    public function setStopWatchdog(callable $fn): void
    {
        $this->stopWatchdog = $fn;
    }

    public function stopWatchdog(): void
    {
        if ($this->stopWatchdog !== null) {
            ($this->stopWatchdog)();
            $this->stopWatchdog = null;
        }
    }
}
