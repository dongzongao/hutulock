# Git Hooks

## 安装

```bash
./install-hooks.sh
```

## 已启用的 Hooks

### commit-msg

检查提交信息，禁止使用营销相关词语。

**禁止的词语**：
- seo / SEO
- 搜索引擎优化
- 关键词优化
- keyword optimization

**原因**：保持提交历史的技术专业性，避免过度营销化。

**示例**：

❌ 错误：
```bash
git commit -m "优化 SEO 关键词"
git commit -m "添加 SEO 优化"
```

✅ 正确：
```bash
git commit -m "docs: 优化项目描述"
git commit -m "docs: 更新性能数据"
git commit -m "docs: 改进技术文档"
```

## 绕过 Hook（不推荐）

如果确实需要绕过检查：

```bash
git commit --no-verify -m "your message"
```

## 卸载

删除 `.git/hooks/commit-msg` 文件：

```bash
rm .git/hooks/commit-msg
```

## 自定义

编辑 `.git-hooks/commit-msg` 文件，然后重新运行 `./install-hooks.sh`。
