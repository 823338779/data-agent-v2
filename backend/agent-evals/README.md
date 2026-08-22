# Context Compact 黑盒评测

本模块通过 Data Agent V2 的公开 HTTP 会话接口回放长对话，分别生成 `lsm` 和
`hierarchical` 的单机制运行产物，再离线对账。它不依赖 Data Agent 运行时模块，也不调用
Metric Platform。

## 为什么分两次运行

Data Agent V2 的执行队列由同一集群内实例共享。若两个连接同一数据库的实例分别配置不同
Context 策略，请求可能被另一个实例领取，无法保证请求入口对应实际执行策略。因此 A/B
评测必须满足以下任一条件：

- 依次启动同一服务，先完整运行 `lsm`，停止后再运行 `hierarchical`；
- 使用完全隔离的数据库、Redis 命名空间和服务实例并行运行。

推荐使用第一种方式。每次启动时，集群内所有存活实例必须使用相同策略。

## 支持的语料

加载器支持两种 JSON：

1. `compact-eval/v1` 标准语料，示例见
   `src/test/resources/compact-eval/smoke-corpus.json`；
2. LongMemEval 官方 `longmemeval_s_cleaned.json`、`longmemeval_m_cleaned.json` 或
   `longmemeval_oracle.json`。

LongMemEval 的每个 `haystack_session` 会作为一轮带日期的历史记录提交。转录只包含 `role`
和 `content`，不会把 `has_answer` 标注泄漏给 Agent。官方 `question` 在历史回放完成后作为
探针提交。超过公开消息接口 8000 字符上限的 Session 会自动切成不超过 7000 字符的连续
历史分片。

可从官方数据仓库下载：

```powershell
New-Item -ItemType Directory -Force .\backend\agent-evals\target\compact-eval\data | Out-Null
curl.exe -L `
  https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_s_cleaned.json `
  -o .\backend\agent-evals\target\compact-eval\data\longmemeval_s_cleaned.json
```

## 运行单个机制

先仅启动 `lsm` 服务：

```powershell
$env:DATA_AGENT_CONTEXT_STRATEGY = 'lsm'
.\backend\mvnw.cmd -f .\backend\pom.xml -pl agent-server -am spring-boot:run
```

在另一个终端执行一条或多条语料。首次验证建议通过 `max-scenarios=1` 控制成本：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-evals `
  '-Dtest=CompactEvalAcceptanceTest' `
  '-Ddata.agent.compact-eval.enabled=true' `
  '-Ddata.agent.compact-eval.mechanism=lsm' `
  '-Ddata.agent.compact-eval.corpus=target/compact-eval/data/longmemeval_s_cleaned.json' `
  '-Ddata.agent.compact-eval.max-scenarios=1' `
  '-Ddata.agent.compact-eval.output=target/compact-eval/lsm-run.json' test
```

等待运行结束并停止服务，然后改用：

```powershell
$env:DATA_AGENT_CONTEXT_STRATEGY = 'hierarchical'
```

重新启动服务，以相同语料和 `max-scenarios` 再运行一次，只把 `mechanism` 与 `output` 改为
`hierarchical`。为了公平比较，两次启动还应保持模型、上下文窗口、超时及其他运行配置一致。

若语料会触发具有外部副作用的工具，两个机制必须使用独立的数据库和外部资源命名空间；
LongMemEval 适配器会要求 Agent 不调用工具，适合先验证纯对话 Compact。

## 生成离线对比报告

两次运行均完成后执行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-evals `
  '-Dtest=CompactEvalComparisonAcceptanceTest' `
  '-Ddata.agent.compact-eval.compare=true' `
  '-Ddata.agent.compact-eval.left=target/compact-eval/lsm-run.json' `
  '-Ddata.agent.compact-eval.right=target/compact-eval/hierarchical-run.json' `
  '-Ddata.agent.compact-eval.comparison-output=target/compact-eval/comparison.json' test
```

报告保留每道探针的参考答案和两侧原始回答。`referenceMatched` 只是经过 Unicode 归一化后的
宽松字符串包含基线，不能替代语义裁判或人工复核。每轮产物还会记录公开事件中的
`context_compaction_started`、`context_compaction_completed` 和
`context_compaction_failed` 数量；正式比较前应确认两侧确实发生了 Compact。
