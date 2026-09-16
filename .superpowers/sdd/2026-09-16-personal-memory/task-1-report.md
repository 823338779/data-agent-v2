# 任务 1 实施报告：规则、领域契约与可构建能力模块

## 实现内容

- 在 `agent-domain` 中定义个人记忆的分类、来源、状态、条目、快照、命令、结果、证据、写入上下文、设置变更和后台领取凭证等共享领域契约。
- `MemoryKind` 仅接受 `person_profile` 与 `collaboration_preference`，明确拒绝历史别名 `work_context`；集合型 record 在构造时执行防御性复制，版本、配额和领取边界执行必要的不变量校验。
- 新增可独立构建的 `agent-memory` 模块，依赖 domain、persistence、tool、Spring context/tx、Boot 配置绑定、Jackson 与 Micrometer，测试依赖 Boot Test 和 Testcontainers PostgreSQL；未依赖 web，也未注册尚不完整的服务 Bean。
- `MemoryContentPolicy` 实现规范化版本 1 的小写、Unicode 空白折叠、首尾整理和末尾标点移除，基于规范化 UTF-8 正文生成 SHA-256 指纹，按 Java UTF-16 单位执行 800/1500 分类限制。
- 内容规则拒绝空白或仅尾部标点正文、常见密钥形态、角色伪装标记、运行时控制标记、ISO 危险控制字符及双向文本控制字符；正常中文句内标点和换行可通过校验，所有异常使用不含输入正文的安全消息。
- `MemoryProperties` 收敛规格已确认的注入、后台批次、调用次数、截止时间、扫描、领取、租约和重试默认值，并在配置绑定入口拒绝非法边界。

## 改动文件

- `backend/pom.xml`
- `backend/agent-memory/pom.xml`
- `backend/agent-memory/src/main/java/com/datagent/agentv2/memory/MemoryContentPolicy.java`
- `backend/agent-memory/src/main/java/com/datagent/agentv2/memory/config/MemoryProperties.java`
- `backend/agent-memory/src/test/java/com/datagent/agentv2/memory/MemoryContentPolicyTest.java`
- `backend/agent-domain/src/main/java/com/datagent/agentv2/memory/` 下任务共享表列出的全部 14 个领域文件
- `backend/agent-domain/src/test/java/com/datagent/agentv2/memory/MemoryKindTest.java`
- 本报告

并行外部改动 `docs/git-repository-setup.md` 未修改、未暂存、未提交。

## RED 证据

命令：

```powershell
mvn -f backend/pom.xml -pl agent-domain '-Dtest=MemoryKindTest' test
```

关键失败输出：

```text
[ERROR] COMPILATION ERROR
[ERROR] .../MemoryKindTest.java:[103,20] 找不到符号
  符号: 类 MemoryEntry
[INFO] BUILD FAILURE
```

失败发生在尚未创建领域能力时的测试编译阶段，原因是目标记忆契约不存在；不是 Docker、网络或依赖下载失败。

## GREEN 与回归证据

定向验收命令：

```powershell
mvn -f backend/pom.xml -pl agent-memory -am '-Dtest=MemoryContentPolicyTest,MemoryKindTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

通过输出：

```text
MemoryKindTest: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
MemoryContentPolicyTest: Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
Reactor Summary: data-agent-v2/agent-domain/agent-persistence/agent-tool/agent-memory 全部 SUCCESS
[INFO] BUILD SUCCESS
```

领域全量回归命令：

```powershell
mvn -f backend/pom.xml -pl agent-domain test
```

通过输出：

```text
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 自审

- 对照任务简报与共享签名逐项检查了 15 个领域文件、4 个 `MemoryContentPolicy` 方法、模块依赖和父 reactor 注册，没有提前实现服务、存储业务或 Bean 装配。
- 共享领域 record 的字段名称、声明顺序和类型以及 `MemoryCommand` 四种固定签名均与 `global-context.md` 一致；`MemoryEvidence.Kind` 和 `MemoryWriteContext.Caller` 按共享表要求声明为所属 record 的嵌套枚举，没有影响任务 2 复用的契约差异。
- 哈希测试使用独立计算的固定 SHA-256 字面量，避免通过同一被测实现生成期望值；安全和配额测试均验证真实行为而非实现结构。
- 所有集合型领域契约均执行不可变复制；命令版本、状态版本、配额、证据序号和租约领取范围均有最小必要不变量。
- 异常只包含固定安全消息，不拼接正文；安全扫描保持有限规则，没有用关键词匹配替代自然语言授权判断。
- Maven 格式化仅作用于 `agent-domain` 与 `agent-memory`，之后重新执行了定向验收和领域全量测试。
- 检查提交范围时将只显式暂存本任务文件，不使用 `git add .`。

## 疑虑

无阻塞疑虑。安全扫描属于规格要求的有限纵深防御，不能证明任意自然语言不存在提示注入风险；后续上下文格式化仍需按规格转义保留标记。
