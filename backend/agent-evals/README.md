# Context Compact 黑盒评测

本模块只针对**当前已启动的 Data Agent 服务配置**执行评测：通过公开 HTTP 会话接口回放长
对话、执行记忆探针、计算本次运行自身的分数，并输出一份完整 JSON 评测结果文档。

模块不会读取另一份结果，也不在工程内进行 A/B 或跨配置比对。需要比较不同服务配置时，
应分别运行评测，再由外部系统使用各自的结果文档进行比较。

## 支持的语料

加载器支持四种语料：

1. `compact-eval/v1` 标准语料，示例见
   `src/test/resources/compact-eval/smoke-corpus.json`；
2. [LongMemEval](https://github.com/xiaowu0162/LongMemEval) 官方
   `longmemeval_s_cleaned.json`、`longmemeval_m_cleaned.json` 或
   `longmemeval_oracle.json`（也支持 `.json.gz`）；
3. [CLongEval](https://github.com/zexuanqiu/CLongEval) 的 LCvMem
   `small.jsonl`、`medium.jsonl`、`large.jsonl`；
4. [NeedleBench](https://huggingface.co/datasets/opencompass/NeedleBench) 中文版原始目录，
   包含 `multi_needle_reasoning_zh.json` 和 `zh_*.jsonl`。

LongMemEval 的每个 `haystack_session` 会作为一轮带日期的历史记录提交。转录只包含 `role`
和 `content`，不会把 `has_answer` 标注泄漏给 Agent。官方 `question` 在历史回放完成后作为
探针提交。超过公开消息接口 8000 字符上限的 Session 会自动切成不超过 7000 字符的连续
历史分片。

评测数据不进入 Git；本地文件统一放在
`src/test/resources/compact-eval/data/`。加载器可直接读取官方 `.json`，也可流式读取自行压缩的
`.json.gz`，评测运行期间不会在线获取语料。

CLongEval/LCvMem 的 `context` 会按公开消息接口限制切成连续历史轮，`query` 和 `answer`
分别作为探针和参考答案。NeedleBench 适配器会从六类中文 haystack 中确定性构造指定字符数的
长文档，将同一道题的多条 `derivations` 均匀埋入文档，再以官方 `question` 和 `answer`
执行多针推理评测。

## 下载官方评测数据

下载脚本使用固定的官方 revision，将 LongMemEval、CLongEval 和 NeedleBench 数据写入按数据集
划分的测试资源目录：

```powershell
.\backend\agent-evals\scripts\download-eval-datasets.ps1
```

本地目录为：

```text
backend/agent-evals/src/test/resources/compact-eval/data/
├── longmemeval/
│   ├── longmemeval_s_cleaned.json
│   ├── longmemeval_m_cleaned.json
│   └── longmemeval_oracle.json
├── clongeval-lcvmem/
│   ├── small.jsonl
│   ├── medium.jsonl
│   ├── large.jsonl
│   └── long_conversation_memory_with_pos.jsonl
└── needlebench-zh/
    ├── multi_needle_reasoning_zh.json
    ├── zh_finance.jsonl
    ├── zh_game.jsonl
    ├── zh_general.jsonl
    ├── zh_government.jsonl
    ├── zh_movie.jsonl
    └── zh_tech.jsonl
```

脚本默认跳过已经存在的文件；需要重新下载时添加 `-Force`。

## 运行并生成评测结果

先按需要的配置启动服务。评测模块不会设置或推断服务内部的 Context 策略、模型、上下文窗口
等配置，只把它当作当前黑盒目标：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -pl agent-server -am spring-boot:run
```

在另一个终端运行评测。`configuration` 只是写入文档的外部标识，不会改变服务配置；建议使用
能唯一描述本次配置的名称。首次验证可通过 `max-scenarios=1` 控制成本：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-evals `
  '-Dtest=CompactEvalAcceptanceTest' `
  '-Ddata.agent.compact-eval.enabled=true' `
  '-Ddata.agent.compact-eval.configuration=local-current-config' `
  '-Ddata.agent.compact-eval.target=http://127.0.0.1:8080' `
  '-Ddata.agent.compact-eval.corpus=src/test/resources/compact-eval/data/longmemeval/longmemeval_s_cleaned.json' `
  '-Ddata.agent.compact-eval.max-scenarios=1' `
  '-Ddata.agent.compact-eval.output=target/compact-eval/evaluation-result.json' test
```

未提供 `configuration` 时使用 `current-server-config`，未提供 `output` 时写入
`target/compact-eval/evaluation-result.json`。

运行 CLongEval/LCvMem 小规模集时，把 `corpus` 改为：

```powershell
'-Ddata.agent.compact-eval.corpus=src/test/resources/compact-eval/data/clongeval-lcvmem/small.jsonl'
```

运行 NeedleBench 中文版时，`corpus` 指向整个目录。默认生成 32,000 字符上下文，也可以通过
`needle-context-characters` 调整；建议先限制场景数：

```powershell
'-Ddata.agent.compact-eval.corpus=src/test/resources/compact-eval/data/needlebench-zh' `
'-Ddata.agent.compact-eval.needle-context-characters=32000' `
'-Ddata.agent.compact-eval.max-scenarios=1'
```

## 结果文档与评分

每个成功执行的探针会调用一次独立 Codex CLI Judge。默认使用 `gpt-5.6-sol`、`medium`
effort 和
5 分钟超时，可通过以下参数覆盖：

```text
data.agent.compact-eval.judge.executable
data.agent.compact-eval.judge.model
data.agent.compact-eval.judge.effort
data.agent.compact-eval.judge.timeout
```

Judge 使用本机 Codex 登录态；运行前应确保 `codex exec` 可用。结果文档使用
`compact-eval-report/v4`，包含：

- 被测目标地址和配置标识；
- 场景、轮次及探针的完整输入、原始回答、执行状态和耗时；
- Compact 开始、完成、失败事件计数；
- 场景完成率、轮次完成率、数据集协议主分和辅助语义分；
- 每个探针的参考答案、官方协议分、LLM 语义分、通过状态、评分理由和裁判模型；
- Judge 的 provider、model、effort、promptVersion 和 rubricVersion；
- 可复现的字符串包含基线。
- 每个场景完整的数据集标签：题型、推理类型、难度、问题日期、历史日期范围和上下文长度；
- 每条证据的会话/消息索引、日期、正文、历史轮位置、相对位置、早中近分桶、六段位置及记忆天数；
- 按数据集、子集、题型、推理类型、难度、证据时期和证据位置拆分的得分汇总。

报告将评分分成三类，禁止混成一个不可解释的总分：

1. `officialScore` 是当前数据集协议的主分。LongMemEval 根据官方题型规则做 LLM 二分类并统计
   accuracy；CLongEval/LCvMem 使用兼容官方实现的中文分词 QA F1；当前加载的 NeedleBench
   多针推理集使用参考答案关键词命中率。由于 LongMemEval 默认 Judge 已改为 GPT-5.6 Sol，结果
   遵循官方 rubric，但不冒充官方固定 Judge 模型的排行榜复现值。
2. `semanticJudgeScore` 是 0、0.25、0.5、0.75、1.0 五档辅助语义分，达到 0.75 记为语义
   通过。它用于诊断表达等价、轻微遗漏和规则指标盲区，不替代官方协议分。
3. 场景/轮次完成率、Judge 覆盖和失败数、Compact 事件及耗时是服务运行质量，独立报告。

`officialScore` 以支持官方协议的全部应执行探针为分母；未执行或 LongMemEval 裁判失败按 0 分
计。`semanticJudgeScore` 以全部应执行探针为分母；未执行或裁判失败同样按 0 分计。与此同时，
报告保留 `officialScoredProbeCount`、`semanticJudgedProbeCount` 和 `judgeFailureCount`，外部系统
可以区分回答错误、服务未执行和裁判失败。裁判失败不会退回字符串分数冒充 LLM 评分。

`normalized-reference-containment/v1` 仍作为辅助基线保留：对 Unicode、大小写和非字母数字
字符归一化后，回答包含参考答案记 1 分，否则记 0 分。

若要比较两套配置，请分别启动对应配置并各运行一次，保留两份 `compact-eval-report/v4` 文档，
由外部评测系统按文档中的分数、完成度、事件统计及逐题原始回答进行比较。
