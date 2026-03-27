# HutuLock Go SDK

Go 1.21+ 客户端，零依赖（仅标准库）。

## 安装

```bash
go get github.com/hutulock/hutulock-go
```

## 快速上手

```go
import hutulock "github.com/hutulock/hutulock-go"

client, err := hutulock.New(hutulock.Config{
    Nodes: []string{"127.0.0.1:8881", "127.0.0.1:8882"},
})
if err != nil { log.Fatal(err) }
defer client.Close()

ctx := context.Background()

token, err := client.Lock(ctx, "order-lock")
if err != nil { log.Fatal(err) }
defer client.Unlock(ctx, token)

// 临界区
processOrder()
```

## 配置

```go
hutulock.Config{
    Nodes:            []string{"127.0.0.1:8881"},
    ConnectTimeout:   5 * time.Second,   // 默认 5s
    LockTimeout:      30 * time.Second,  // 默认 30s
    WatchdogInterval: 10 * time.Second,  // 默认 10s
}
```

## 运行测试

```bash
go test ./... -v
```
