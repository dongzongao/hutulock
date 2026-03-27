<?php

declare(strict_types=1);

namespace HutuLock;

class HutuLockException extends \RuntimeException {}

class ConnectionException extends HutuLockException {}

class LockTimeoutException extends HutuLockException
{
    public function __construct(string $lockName)
    {
        parent::__construct("lock timeout: {$lockName}");
    }
}

class NotLeaderException extends HutuLockException
{
    public string $leaderId;

    public function __construct(string $leaderId = '')
    {
        $this->leaderId = $leaderId;
        parent::__construct("not leader, redirect to: {$leaderId}");
    }
}

class SessionExpiredException extends HutuLockException
{
    public function __construct()
    {
        parent::__construct('session expired');
    }
}

class ProtocolException extends HutuLockException {}
