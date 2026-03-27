# HutuLock PHP SDK

PHP 8.0+ 客户端，零第三方依赖（仅需 `ext-sockets`）。

## 安装

```bash
composer require hutulock/hutulock-php
```

## 快速上手

```php
use HutuLock\HutuLockClient;

$client = new HutuLockClient(
    nodes: [['127.0.0.1', 8881], ['127.0.0.1', 8882]],
    lockTimeout: 30.0,
    watchdogInterval: 10.0,
);
$client->connect();

$token = $client->lock('order-lock');
try {
    // 临界区
    // 长时间持锁时，在业务循环中调用 $client->tick() 触发看门狗续期
    processOrder();
} finally {
    $client->unlock($token);
}

$client->close();
```

## 看门狗说明

PHP 是单线程模型，无法后台定时。看门狗通过 `tick()` 方法驱动：

```php
$token = $client->lock('order-lock');
try {
    while ($hasWork) {
        doSomeWork();
        $client->tick(); // 每次循环调用，到期自动续期
    }
} finally {
    $client->unlock($token);
}
```

## 运行测试

```bash
cd sdk/php
composer install
./vendor/bin/phpunit tests/
```

## 协议兼容性

与 Java/Python/Go 服务端完全兼容，使用相同的文本行协议（UTF-8）。
