# 快速测试指南

## 问题：GitHub Actions 没有执行测试

### 原因分析

最初的工作流配置了路径过滤：
```yaml
paths:
  - 'hutulock-server/**'
  - 'hutulock-client/**'
  - 'hutulock-model/**'
  - 'pom.xml'
```

第一次提交只修改了文档和工作流文件，不匹配这些路径，所以没有触发。

### 解决方案

已添加工作流文件本身到触发路径：
```yaml
paths:
  - '.github/workflows/performance-benchmark.yml'
```

现在工作流应该会在下次提交时触发。

## 验证方法

### 方法 1: 查看 GitHub Actions 页面

访问：https://github.com/dongzongao/hutulock/actions

应该能看到 "Performance Benchmark" 工作流正在运行或已完成。

### 方法 2: 手动触发测试

1. 访问 https://github.com/dongzongao/hutulock/actions/workflows/performance-benchmark.yml
2. 点击 "Run workflow" 按钮
3. 选择 `main` 分支
4. 点击绿色的 "Run workflow" 按钮

### 方法 3: 本地测试工作流逻辑

运行本地测试脚本验证逻辑是否正确：

```bash
cd hutulock
./test-workflow-locally.sh
```

这会：
- 编译项目
- 启动 3 节点集群
- 运行性能测试（简化版，每个测试 10 秒）
- 生成图表
- 停止集群

总耗时约 2-3 分钟。

## 触发一次测试的最简单方法

修改任意代码文件并提交：

```bash
# 方法 1: 修改一个注释
echo "// trigger test" >> hutulock-server/src/main/java/com/hutulock/server/LockServer.java
git add -A
git commit -m "test: 触发性能测试"
git push

# 方法 2: 修改 README（现在也会触发）
echo "" >> README.md
git add README.md
git commit -m "test: 触发性能测试"
git push
```

## 预期结果

工作流运行后，会自动提交以下文件：
- `docs/benchmark-history.json` - 更新的历史数据
- `docs/benchmark-chart.png` - 更新的性能图表
- `docs/benchmark-badge.json` - 性能徽章数据

提交信息：`chore: update performance benchmark results [skip ci]`

注意 `[skip ci]` 标记防止无限循环触发。

## 如果还是没有触发

检查以下几点：

1. **仓库 Actions 是否启用**
   - 访问仓库 Settings → Actions → General
   - 确保 "Allow all actions and reusable workflows" 已选中

2. **工作流文件语法是否正确**
   ```bash
   # 本地验证 YAML 语法
   python3 -c "import yaml; yaml.safe_load(open('.github/workflows/performance-benchmark.yml'))"
   ```

3. **查看工作流运行历史**
   - 访问 Actions 页面
   - 查看是否有失败的运行
   - 点击查看详细日志

4. **GitHub Actions 配额**
   - 公开仓库：无限制
   - 私有仓库：每月 2000 分钟（免费账户）
   - 检查 Settings → Billing

## 下一步

一旦工作流成功运行：
1. 查看生成的图表：`docs/benchmark-chart.png`
2. 查看历史数据：`docs/benchmark-history.json`
3. README 会自动显示最新的性能图表
4. 每次提交都会自动更新性能数据

## 需要帮助？

如果遇到问题，查看：
- 工作流日志：https://github.com/dongzongao/hutulock/actions
- 本地测试：`./test-workflow-locally.sh`
- 文档：`docs/benchmark-automation.md`
