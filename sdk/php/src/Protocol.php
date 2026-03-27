<?php

declare(strict_types=1);

namespace HutuLock;

/**
 * HutuLock 文本行协议（UTF-8）
 * 格式：{TYPE} {arg0} {arg1} ...\n
 */
class Message
{
    // 客户端请求
    public const CONNECT = 'CONNECT';
    public const LOCK    = 'LOCK';
    public const UNLOCK  = 'UNLOCK';
    public const RECHECK = 'RECHECK';
    public const RENEW   = 'RENEW';

    // 服务端响应
    public const CONNECTED   = 'CONNECTED';
    public const OK          = 'OK';
    public const WAIT        = 'WAIT';
    public const RELEASED    = 'RELEASED';
    public const RENEWED     = 'RENEWED';
    public const REDIRECT    = 'REDIRECT';
    public const WATCH_EVENT = 'WATCH_EVENT';
    public const ERROR       = 'ERROR';

    public string $type;
    /** @var string[] */
    public array $args;

    public function __construct(string $type, array $args = [])
    {
        $this->type = $type;
        $this->args = $args;
    }

    public static function parse(string $line): self
    {
        $line = trim($line);
        if ($line === '') {
            throw new ProtocolException('empty message');
        }
        $parts = preg_split('/\s+/', $line, 2);
        $type  = strtoupper($parts[0]);
        $args  = isset($parts[1]) && $parts[1] !== ''
            ? preg_split('/\s+/', $parts[1])
            : [];
        return new self($type, $args);
    }

    public function serialize(): string
    {
        if (empty($this->args)) {
            return $this->type;
        }
        return $this->type . ' ' . implode(' ', $this->args);
    }

    public function arg(int $index): string
    {
        return $this->args[$index] ?? '';
    }
}
