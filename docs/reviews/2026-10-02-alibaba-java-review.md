# Agent V2 阿里巴巴 Java 开发规约审查

审查日期：2026-10-02（Asia/Shanghai）。代码基线：`cb0ec2d`。审查前工作区干净。原审查阶段仅新增报告；用户随后授权修复，整改记录见文末。下列问题位置和基线验证保留原始审查证据。

## 范围与判定方式

覆盖后端 12 个 Maven 模块，生产 Java 427 个文件、测试 Java 135 个文件、SQL 14 个文件，以及父/模块 POM、运行配置与相关契约。采用全仓模式扫描、各模块核心路径阅读、测试核对及针对性最小实验；不等同于逐行形式化证明。前端 UI、示例 dbt 项目的业务正确性和真实模型效果不属于本次 Java 规约审查范围。

以 [仓库 AGENTS.md](E:/IdeaProjects/agent-root/data-agent-v2/AGENTS.md) 优先，参考 [阿里巴巴 Java 开发规约官方页面](https://alibaba.github.io/Alibaba-Java-Coding-Guidelines/) 和已安装的 [审查技能](C:/Users/82333/.codex/skills/alibaba-java-coding-guidelines-skill/SKILL.md)。强制/推荐/参考是规则属性，P1/P2/P3 是本次整改优先级，二者分别标注；功能正确性推论不冒称官方原文。

固定身份、当前不做认证/租户隔离属于明确阶段约定。PostgreSQL 不机械套用 MySQL 的类型、编码、命名规则。Java 21 的 record、穷尽式 switch 和虚拟线程按实际语义判断。没有将枚举未加 Enum 后缀、Service 没有额外接口、已有 Entity/View 命名一概列为缺陷。

共 **23 类问题：P1 4 类、P2 14 类、P3 5 类**。同类重复位置合并记录。P1 优先修复卡死与安全边界，P2 随后修复行为/资源/事务及项目强制边界，P3 单独做规范治理。

## 问题索引

| 编号 | 优先级 | 问题 |
| --- | --- | --- |
| R01 | P1 | Codex 超时/取消可能卡在流关闭 |
| R02 | P1 | 详细 SSE 握手与增量发布存在锁反转 |
| R03 | P1 | thinking 按 delta 脱敏后拼接会恢复密钥原文 |
| R04 | P1 | web_fetch 校验的 DNS 地址没有绑定实际连接 |
| R05 | P2 | 两个 SSE 对账方法没有接入运行入口 |
| R06 | P2 | 提交与完成/取消事务先后锁定的表相反 |
| R07 | P2 | 更新已有实体时 updatedAt 不刷新 |
| R08 | P2 | 失败和取消后未释放完整回答累计器 |
| R09 | P2 | Git 排队锁忽略取消，获得锁后仍执行操作 |
| R10 | P2 | 仓库中一个二进制或大文件会使文本搜索整体失败 |
| R11 | P2 | 网页 requestTimeout 不覆盖响应正文读取 |
| R12 | P2 | 多个失败边界丢失根异常，无法可靠诊断 |
| R13 | P2 | Skill 资源流未关闭，gzip 初始化失败也存在泄漏路径 |
| R14 | P2 | 评测吞掉中断并继续，裁判进程异常退出路径不完整 |
| R15 | P2 | 对话幂等键未按数据库长度限制校验 |
| R16 | P2 | Hierarchical targetRatio 没有参与目标预算 |
| R17 | P2 | 上下文插件自带持久化，与 AGENTS 集中持久化规则冲突 |
| R18 | P2 | 上下文 Mapper 手写普通单表查询并使用 SELECT * |
| R19 | P3 | MapStruct 存在静态单例及重复同构手写转换 |
| R20 | P3 | 领域对象公开全量 setter，可绕过语义方法 |
| R21 | P3 | 存在纯赋值构造器和机械 getter |
| R22 | P3 | 关键版本、摘要及生命周期字段缺少语义说明 |
| R23 | P3 | 多处 if/for 省略大括号 |

## 详细发现

### R01 · P1 · Codex 超时/取消可能卡在流关闭

- 规则：强制 · 资源释放与取消。
- 位置：[CodexAppServerClient:580](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/model/codex/CodexAppServerClient.java:580)。
- 问题与影响：`exchange()` 阻塞于子进程管道的 `readLine()` 时，取消 Future 无法保证解除该读取。清理先执行 `reader.close()`，后执行 `process.destroy()`；关闭需要获取读取线程持有的锁，因而可能永远到不了终止进程的步骤。调用线程、子进程及执行资源会持续占用。
- 建议：先终止进程并有界等待，必要时强杀，再关闭包装流。覆盖真实子进程不返回数据时的超时和取消。
- 证据与边界：本机 JDK 21 等价最小实验中，取消读取后关闭线程 700ms 仍为 WAITING；先强制终止子进程后立即结束。该实验验证底层阻塞机制，未运行真实 Codex 服务。现有 StringReader 协议测试不能覆盖管道锁。

### R02 · P1 · 详细 SSE 握手与增量发布存在锁反转

- 规则：强制 · 并发锁顺序。
- 位置：[ConversationEventStreamFacade:423](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ConversationEventStreamFacade.java:423)；[LocalSseRegistry:128](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/LocalSseRegistry.java:128)。
- 问题与影响：HTTP 线程持有 Subscription 锁，在初始化快照第 446 行调用 resume 等待 Connection 锁；模型线程持有 Connection 锁，在 deliver 回调 acceptDelta 时等待 Subscription 锁。正在输出且已有 checkpoint 的会话首次连接时可以形成循环等待，阻塞 SSE 请求及模型输出线程。
- 建议：不要在 Connection 锁内调用外部 subscriber 回调，或让快照与 delta 使用同一串行队列；保持游标推进与事件顺序。用栅栏固定上述交错补并发测试。
- 证据与边界：完整调用链静态核验；不是已经观察到的线上事故。sendSnapshot 路径也需要一并审计。

### R03 · P1 · thinking 按 delta 脱敏后拼接会恢复密钥原文

- 规则：强制 · 敏感数据脱敏。
- 位置：[ConversationProcessCoordinator:139](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/ConversationProcessCoordinator.java:139)。
- 问题与影响：代码先对每个 delta 调用 sanitizeText，再在第 157 行拼接。把示例文本 password=audit_dummy 分成 password= 与 audit_dummy，两段单独均不匹配正则，合并后原文进入节点持久化及第 177 行 ProcessAuditEvent。审计 logger 不再脱敏。
- 建议：实现跨块脱敏状态，在持久化及审计前处理累计内容；对未闭合的敏感键值保留安全缓冲，避免先前已发布片段泄漏。覆盖键名、分隔符、值分别跨块的测试。
- 证据与边界：已调用项目实际 ProcessPayloadSanitizer 最小验证：SPLIT=password=audit_dummy；WHOLE=password=[REDACTED]。仅使用虚构数据。

### R04 · P1 · web_fetch 校验的 DNS 地址没有绑定实际连接

- 规则：强制 · 外部地址校验。
- 位置：[SafeWebPageFetcher:30](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/fetch/SafeWebPageFetcher.java:30)；[PublicWebAddressPolicy:40](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/fetch/PublicWebAddressPolicy.java:40)。
- 问题与影响：地址策略先解析并验证 IP，随后只把原始 URI 交给 HttpClient，连接层可能重新解析。攻击者控制 DNS 且两次解析之间缓存过期、被禁用或发生重新解析时，可以由公网地址切换为内网地址；逐跳重定向检查不能消除这个时差。
- 建议：将通过校验的解析结果绑定连接，保留正确的 Host、SNI 和证书校验；或使用有同等限制的受控出口。补 DNS 改绑与真实连接目标一致性的测试。
- 证据与边界：静态确认检查与连接分离，风险依赖 DNS 重解析条件；未执行真实 SSRF 攻击，也未断言当前默认缓存下每次可利用。

### R05 · P2 · 两个 SSE 对账方法没有接入运行入口

- 规则：推荐 · 运行时装配与行为正确性。
- 位置：[ConversationSummaryStreamFacade:62](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/summary/ConversationSummaryStreamFacade.java:62)；[ConversationEventStreamFacade:154](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ConversationEventStreamFacade.java:154)。
- 问题与影响：全后端只有 reconcileSubscribers 和 reconcileActiveSessions 的定义，没有调用或调度。摘要流只有 open 时发送快照，后续新建会话及执行状态变化不会推送；详细流的缺口恢复与缺口超时检查也不会按注释所述运行。
- 建议：在 agent-server 装配有生命周期管理的周期任务，或提供等价事件驱动入口；保留失败后的后续执行能力。补建立连接后发生变化、delta 缺口恢复测试。
- 证据与边界：源码全局引用搜索确认。摘要 SSE 集成测试只验证初始快照，无法发现后续更新缺失。

### R06 · P2 · 提交与完成/取消事务先后锁定的表相反

- 规则：强制 · 数据库锁顺序。
- 位置：[AgentExecutionSubmissionTransactionService:55](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionSubmissionTransactionService.java:55)；[ModelInvocationCoordinator:130](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/react/ModelInvocationCoordinator.java:130)。
- 问题与影响：提交先锁 session，再于第 65 行锁 active execution；完成先锁 execution，再于第 156 行更新 session 的事件序号。取消也先锁 execution 再更新 session。两事务交错会形成数据库死锁，PostgreSQL 中止其中一个，可能出现提交 500 或最终回答事务回滚。
- 建议：统一相关事务的锁顺序，例如 session→execution→message；补提交与完成、取消并发测试。不要只在一个入口追加重试掩盖锁顺序问题。
- 证据与边界：已核对底层 FOR UPDATE 与 UPDATE 调用链；未做数据库死锁动态复现。

### R07 · P2 · 更新已有实体时 updatedAt 不刷新

- 规则：强制 · ORM 审计字段。
- 位置：[AuditMetaObjectHandler:34](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/AuditMetaObjectHandler.java:34)。
- 问题与影响：`strictUpdateFill` 只填空值。ConversationSessionService 和 ConversationMessageService 读取已有实体后更新，保留非空旧 updatedAt，因此填充被跳过。Git 持久化服务的无 entity Wrapper 更新也没有显式刷新时间。审计时间及依赖该时间的排序失真。
- 建议：更新填充显式覆盖 updatedAt；无 entity 的条件更新显式设置审计时间或传入符合框架约定的实体。补读取旧实体→修改→重读后的时间推进测试，并覆盖 Git CAS。
- 证据与边界：核对本地 MyBatis-Plus 3.5.16 源码并调用项目实际 handler：旧值 2000-01-01T00:00 在 updateFill 后不变。现有名为 saveAndUpdateFillAuditAndTenantFields 的测试实际只验证新增。

### R08 · P2 · 失败和取消后未释放完整回答累计器

- 规则：强制 · 生命周期资源释放。
- 位置：[ExecutionStreamCheckpointCoordinator:20](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/stream/ExecutionStreamCheckpointCoordinator.java:20)。
- 问题与影响：singleton Map 在每次模型调用注册持有完整回答的 accumulator。释放方法 flushAndRelease 仅在正常 completeFinalAnswer 调用；失败、取消及截止时间终止未清理。新 executionId 不会清掉旧键，长期运行会保留历史回答和执行数据。
- 建议：提供按 executionId 幂等释放内存的方法，在 worker finally 中执行；将持久化收敛与内存释放分开，保证数据库故障也能释放。检查 activeThinkingItems 的相同生命周期。
- 证据与边界：全局追踪注册、remove 与调用方确认；尚未进行长期堆增长压测。

### R09 · P2 · Git 排队锁忽略取消，获得锁后仍执行操作

- 规则：强制 · 取消与副作用边界。
- 位置：[GitWorkspaceLocks:19](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-git/src/main/java/com/datagent/agentv2/git/application/GitWorkspaceLocks.java:19)。
- 问题与影响：`lock.lock()` 不响应工具 Future.cancel(true)，拿到锁后直接 action.get()。等待期间取消的 restartDraft 可在稍后失效候选、更新工作区状态，之后启动 Git 子进程才发现中断，已经发生的变更不会自动撤销。
- 建议：改用可中断锁获取，并在副作用前检查取消状态。补持锁→排队→取消→释放锁后回调不执行的测试。
- 证据与边界：已核对 ToolExecutionCoordinator 的取消方式与 GitWorkspaceService.restartDraft 的副作用顺序。

### R10 · P2 · 仓库中一个二进制或大文件会使文本搜索整体失败

- 规则：推荐 · 功能正确性与异常隔离。
- 位置：[GitWorkspaceFiles:46](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-git/src/main/java/com/datagent/agentv2/git/local/GitWorkspaceFiles.java:46)。
- 问题与影响：`search()` 遍历所有普通文件并直接 read；read 拒绝二进制、非 UTF-8 和超限文件。合法仓库包含 PNG 或压缩文件时，搜索普通文本也会整体异常并丢弃已找到的结果。仓库基线逻辑明确允许保留这些未修改文件。
- 建议：文本搜索跳过不适用的文件类型和超限文件，必要时报告跳过数量；不要把路径安全拒绝或真实 I/O 故障全部吞掉。补文本命中与二进制并存的用例。
- 证据与边界：已对照文件读取实现及合法二进制基线测试确认。

### R11 · P2 · 网页 requestTimeout 不覆盖响应正文读取

- 规则：推荐 · 超时语义。
- 位置：[JdkWebFetchTransport:44](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/fetch/JdkWebFetchTransport.java:44)。
- 问题与影响：`BodyHandlers.ofInputStream()` 收到响应头即可返回，之后 readNBytes 不受该 HttpRequest.timeout 完整覆盖。远端快速发头、缓慢发正文可以超过单次请求超时。外层工具超时仍存在，不能据此说整个工具没有超时。
- 建议：使用有界 BodySubscriber 在请求阶段收集正文，或为读取绑定明确截止时间并在超时时关闭流；覆盖慢正文和重定向正文。
- 证据与边界：本机 JDK 21 loopback 最小实验：timeout=500ms，响应头后正文延迟 1200ms，仍成功返回 2 字节，耗时约 1297ms。

### R12 · P2 · 多个失败边界丢失根异常，无法可靠诊断

- 规则：强制 · 异常和日志。
- 位置：[MemoryReviewWorker:233](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-memory/src/main/java/com/datagent/agentv2/memory/review/MemoryReviewWorker.java:233)；[ContextSummaryGenerator:100](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/summary/ContextSummaryGenerator.java:100)；[GitSnapshotStore:51](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-git/src/main/java/com/datagent/agentv2/git/application/GitSnapshotStore.java:51)。
- 问题与影响：后台记忆把 RuntimeException 压成 MODEL_FAILURE；调度器只记录固定文本；LSM 重试 continue 后最终抛无 cause 的通用错误；Git 快照包装异常时也不保留 cause。网络、配置、协议、磁盘或数据库故障会失去区分依据。LocalAgentExecutionLauncher 同样只传递 getMessage()。
- 建议：保留安全的异常链、失败分类、调用标识和尝试次数，在一个服务端边界记录脱敏诊断。不要把原始 provider 正文、密钥或 Git stderr 直接输出给模型或日志；对用户维持稳定泛化错误。
- 证据与边界：多个捕获点及最终包装链静态确认。预期业务拒绝不要求全部打堆栈，本项针对依赖和程序失败。

### R13 · P2 · Skill 资源流未关闭，gzip 初始化失败也存在泄漏路径

- 规则：强制 · 输入流释放。
- 位置：[ClasspathSkillResourceSource:33](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-skill/src/main/java/com/datagent/agentv2/skill/ClasspathSkillResourceSource.java:33)；[CompactEvalCorpusLoader:94](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-evals/src/main/java/com/datagent/agentv2/evals/compact/CompactEvalCorpusLoader.java:94)。
- 问题与影响：Skill 装载直接 getInputStream().readAllBytes()，没有关闭流，readAllBytes 不负责代为关闭。评测语料 open() 先打开底层流再构造 GZIPInputStream，若 gzip 头损坏、构造抛错，外层 try-with-resources 尚未获得资源，底层流遗留。
- 建议：Skill 使用 try-with-resources；gzip 包装构造失败时关闭已打开的底层流，并保留原始异常。补损坏 gzip 与资源关闭验证。
- 证据与边界：静态核验正常、异常资源所有权路径；未做文件句柄压力测试。

### R14 · P2 · 评测吞掉中断并继续，裁判进程异常退出路径不完整

- 规则：强制 · 中断处理和进程生命周期。
- 位置：[CompactEvalRunner:61](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-evals/src/main/java/com/datagent/agentv2/evals/compact/CompactEvalRunner.java:61)；[CodexCliCompactEvalJudge:121](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-evals/src/main/java/com/datagent/agentv2/evals/compact/CodexCliCompactEvalJudge.java:121)。
- 问题与影响：run 和 evaluate 的 catch(Exception) 将 InterruptedException 当普通场景或评分失败处理，继续后续任务。裁判子进程只在显式 waitFor 超时分支销毁，写 stdin 失败或 waitFor 被中断时 finally 只清理目录，可能留下进程继续运行。
- 建议：中断单独处理并传播或恢复标记后停止整个评测；子进程放进统一 finally 清理，终止及等待均有界，同时保护原始异常。
- 证据与边界：用实际 CompactEvalRunner、两个场景和抛 InterruptedException 的 stub 网关验证：创建会话调用 2 次，返回正常报告且线程中断标记 false。子进程遗留分支为静态确认。

### R15 · P2 · 对话幂等键未按数据库长度限制校验

- 规则：强制 · 参数校验。
- 位置：[ConversationController:107](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/controller/ConversationController.java:107)；[AgentExecutionSubmissionService:30](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionSubmissionService.java:30)。
- 问题与影响：Idempotency-Key 只有非空白校验，数据库列为 varchar(256)。257 个 ASCII 字符的请求键可进入 INSERT 并触发数据库错误，返回 500 而非明确的参数错误。个人记忆接口已有长度约束可参考。
- 建议：HTTP 与应用服务边界一致限制为 256，并补边界长度及超长键契约测试。
- 证据与边界：Controller、服务和 V1 数据库列定义对照确认；未向正在运行的用户服务发送请求。

### R16 · P2 · Hierarchical targetRatio 没有参与目标预算

- 规则：推荐 · 配置行为一致性。
- 位置：[HierarchicalConversationContextAssembler:166](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/runtime/HierarchicalConversationContextAssembler.java:166)。
- 问题与影响：历史摘要预算取 hardInputLimitTokens 减受保护上下文，运行链路没有调用 targetInputTokens。README 公布的目标比例从 0.70 改成 0.50，虽然能通过绑定与校验，仍不影响该压缩预算。
- 建议：把目标水位接入期望摘要预算，受硬上限约束；明确受保护原文已经超过目标水位时的降级语义。补不同 targetRatio 改变预算的测试。
- 证据与边界：全局引用及预算传递链静态确认；未做真实模型生成效果实验。

### R17 · P2 · 上下文插件自带持久化，与 AGENTS 集中持久化规则冲突

- 规则：项目强制 · 架构规则冲突。
- 位置：[LsmContextManifestMapper:1](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/mapper/LsmContextManifestMapper.java:1)；[HierarchicalCheckpointMapper:1](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalCheckpointMapper.java:1)。
- 问题与影响：两个 context 模块含 Entity、Mapper、Repository、MapperScan、Flyway 迁移并直接依赖 MyBatis。AGENTS 明确规定这些实现集中在 agent-persistence；但 README 又明确说明插件具有自有摘要持久化和独立迁移。当前正式规则与实现/说明相互矛盾。
- 建议：先统一架构决策。按现行 AGENTS 应收敛存储实现，策略依赖接口；如果决定保留插件自治，需明确修订正式边界规则及约束。不能仅凭规约把已经存在的插件设计断言为功能错误。
- 证据与边界：AGENTS、README、两个模块 POM 与源码交叉核验。本次不擅自迁移模块。

### R18 · P2 · 上下文 Mapper 手写普通单表查询并使用 SELECT *

- 规则：强制 ORM + 项目强制 · 框架复用。
- 位置：[LsmSummarySegmentMapper:17](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/mapper/LsmSummarySegmentMapper.java:17)；[HierarchicalColdMapper:15](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/persistence/mapper/HierarchicalColdMapper.java:15)。
- 问题与影响：已经继承 BaseMapper，普通等值、IN、排序查询仍重复写 @Select 且选择全部字段；同类出现在 LsmContextManifestMapper、HierarchicalCheckpointMapper。字段扩展与映射行为需要额外手工维护。
- 建议：普通 CRUD/CAS 优先 Lambda Wrapper，确需手写的查询列明字段。ON CONFLICT DO NOTHING 属于 PostgreSQL 特性，可保留合理自定义 SQL，不机械替换。
- 证据与边界：这是维护和框架约束问题；没有证据表明这些参数化查询存在 SQL 注入。

### R19 · P3 · MapStruct 存在静态单例及重复同构手写转换

- 规则：项目强制 · 对象转换。
- 位置：[ContextModelMessageStructMapper:17](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-web/src/main/java/com/datagent/agentv2/conversation/context/ContextModelMessageStructMapper.java:17)；[LsmPersistenceStructMapper:16](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/persistence/mapping/LsmPersistenceStructMapper.java:16)。
- 问题与影响：上述 mapper 及 OllamaModelStructMapper 使用默认 component model 和 Mappers.getMapper 静态 INSTANCE。HierarchicalColdRepository 同构字段逐个转换，而 POM 已具备 MapStruct processor；均偏离本项目规定。
- 建议：改为 Spring component model 和构造器注入；同构字段交给 MapStruct，默认值和覆盖范围等语义组装继续显式表达。
- 证据与边界：尚未确认实际字段错映射，按可维护性整改，不包装成数据损坏。

### R20 · P3 · 领域对象公开全量 setter，可绕过语义方法

- 规则：项目强制 · 领域不变量。
- 位置：[ConversationMessage:16](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationMessage.java:16)；[AgentExecution:13](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/AgentExecution.java:13)；[ConversationSession:11](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationSession.java:11)。
- 问题与影响：类级 @Setter 暴露 role、status、version、序号及身份字段的无约束修改；与已经存在的工厂及状态变化方法并存。ConversationMessage 类注释还称其为不可变消息，和实现不一致。
- 建议：按职责缩小 setter 可见性，领域状态变化通过语义方法；持久化还原使用受控工厂或映射入口。同步修正文档，不因框架组装方便而跳过业务不变量。
- 证据与边界：属于本地强制设计规则偏离；未发现某个现有调用已经利用 setter 造成状态破坏。

### R21 · P3 · 存在纯赋值构造器和机械 getter

- 规则：项目强制 · Lombok 复用。
- 位置：[SafeWebPageFetcher:17](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/fetch/SafeWebPageFetcher.java:17)；[WebSearchProperties:70](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/web/WebSearchProperties.java:70)。
- 问题与影响：SafeWebPageFetcher 的注入构造器只赋值 final 字段；WebSearchProperties 连续 getter 只返回字段。父 POM 已引入 Lombok，相邻类已使用对应注解。
- 建议：使用精确的 @RequiredArgsConstructor、@Getter 等；保留带真实配置校验或领域不变量的显式构造器，保持现有访问方法契约。
- 证据与边界：只属于样板代码与项目一致性问题，不建议为了规约拆出额外抽象。

### R22 · P3 · 关键版本、摘要及生命周期字段缺少语义说明

- 规则：项目强制 · 中文字段注释。
- 位置：[GitChangeView:6](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-git/src/main/java/com/datagent/agentv2/git/model/GitChangeView.java:6)；[GitWorkspaceView:6](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-git/src/main/java/com/datagent/agentv2/git/model/GitWorkspaceView.java:6)；[MemoryEntry:7](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-domain/src/main/java/com/datagent/agentv2/memory/MemoryEntry.java:7)。
- 问题与影响：draftRevision 与 version 的差异、candidateDigest 的覆盖范围、commitId/errorCode/activeChangeId 的空值条件等没有字段级说明；部分记忆 record 的版本和时间字段也只能从调用方推断。
- 建议：用中文字段 Javadoc 或 record @param 解释单位、空值、版本关系和生命周期。不要给显而易见的赋值添加机械注释。
- 证据与边界：抽查已明确的高语义密度类型作为整改入口，未把每个缺注释字段都计为独立问题。

### R23 · P3 · 多处 if/for 省略大括号

- 规则：强制 · 控制流规约。
- 位置：[MemoryWritePolicy:18](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-memory/src/main/java/com/datagent/agentv2/memory/MemoryWritePolicy.java:18)；[GitWorkspaceFiles:50](E:/IdeaProjects/agent-root/data-agent-v2/backend/agent-git/src/main/java/com/datagent/agentv2/git/local/GitWorkspaceFiles.java:50)。
- 问题与影响：存在单行及多行无括号条件/循环，例如拒绝分支和搜索命中分支。阿里控制语句规约明确要求即使单语句也使用大括号；本项目没有明确豁免。
- 建议：分批补大括号并保持行为不变，可将这一条纳入静态检查。不要与锁顺序、事务等功能修复混成一次大规模格式化。
- 证据与边界：直接源码可见。Spotless 格式检查并不等价于完整阿里规约检查。

## 验证结果

本次实际执行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
.\backend\mvnw.cmd -f .\backend\pom.xml -B -fn spotless:check
```

- **Maven verify：BUILD SUCCESS**，12 个业务模块全部通过，耗时约 5 分 8 秒。共 565 项测试：561 通过，4 跳过，0 失败，0 错误；本机 Docker 可用，PostgreSQL Testcontainers 测试确实运行。
- 跳过项为 2 项真实 Codex 登录态调用、1 项真实 Ollama 长上下文调用、1 项黑盒压缩评测，均未开启对应显式运行开关。因此不宣称真实模型链路验收通过。
- **Spotless：检查未通过**，失败模块为 agent-web，其余模块通过。违规文件为 PersonalMemoryController.java、MemoryToolFailureContractTest.java、PersonalMemoryControllerTest.java、PersonalMemoryViewTest.java。为遍历全部模块使用了 `-fn`，命令最终退出码为 0，但日志明确为 BUILD FAILURE；本报告按实际检查结果判定失败。未执行 spotless:apply。
- POM 中 Spotless 未绑定 verify 生命周期，所以 verify 成功不能证明格式合规；Spotless 本身也不覆盖完整阿里规约。没有运行 P3C 静态分析插件或依赖漏洞扫描。
- 针对性最小实验确认了旧 updatedAt 保留、分块脱敏失效、评测吞中断；另用本机等价底层实验验证了管道关闭阻塞及慢正文越过 requestTimeout。实验文件位于系统临时目录，没有加入产品源码或测试源码。

本次日志：[Maven verify](C:/Users/82333/AppData/Local/Temp/agentv2-alibaba-review-maven.log)、[Spotless](C:/Users/82333/AppData/Local/Temp/agentv2-alibaba-review-spotless.log)。日志保留在本机临时目录，未提交仓库。

## 已确认的基础与后续验证重点

现有项目已普遍使用 SLF4J、参数化 SQL、MyBatis-Plus、record、构造器注入和 CAS；记忆写入与后台租约的大部分核心代码说明了状态锁和真实数据库时钟约束。未发现运行时依赖 agent-evals，agent-server 保持唯一可执行模块。以上是本次检查结果，不代表所有场景均无风险。

建议先为 R01/R02/R03/R06/R07/R09 补充可以稳定复现的回归测试，再分别修复。SSE 应增加实际装配后的持续更新测试；资源/取消应使用真实管道、流和锁，而非只测 StringReader 或立即返回的 mock；审计测试必须执行真实更新。DNS 改绑、长期资源增长及线上负载仍需受控集成验证。


## 修复记录（2026-10-02）

用户已授权修复 R01–R23。代码保留固定身份和现有 HTTP 契约；未提交或推送。以下为实现记录，最终验收结果在末尾单独记录。

| 编号 | 已实施修复 | 回归证据 |
| --- | --- | --- |
| R01 | 先终止 Codex 进程解除管道阻塞，再回收流；取消保留中断语义 | 真实 Java 子进程超时/取消，以及继承 stdout 的子进程树测试 |
| R02 | Connection 锁只用于游标分类，subscriber 回调在锁外执行，Subscription 继续按序缓冲 | 锁交错回归及详细 SSE 测试 |
| R03 | 按累计原文重新生成安全投影，未闭合引号值也脱敏；短期原文缓冲随执行清理 | 每个 delta 切分点核对审计和持久化内容 |
| R04 | Apache HttpClient 每跳只使用已校验 IP，保留原 Host/TLS 身份，重定向回到策略重新验证 | 不可解析域名绑定本机 IP 的真实 HTTP 测试 |
| R05 | agent-server 装配可关闭、具名的周期任务，调用详细/摘要 SSE 对账 | 新建会话持续同步、详细流跳号后 snapshot→delta 恢复 |
| R06 | 提交、完成、取消统一采用 session→execution→assistant 锁顺序 | 完成和取消锁顺序回归及执行集成测试 |
| R07 | 更新时覆盖旧 updatedAt；Git Wrapper 更新传入 Entity 触发审计 | PostgreSQL 重读实体更新、Git CAS 更新和 createdAt 保留 |
| R08 | worker finally 清理完整回答累计器、过程索引和 thinking 原文缓冲 | 失败后实际集合为空的回归 |
| R09 | lockInterruptibly 等待锁，获锁后再次检查取消 | 等待中断后业务 action 不执行 |
| R10 | 搜索跳过二进制及超限普通文件，保留非法路径和真实 I/O 错误 | 混合文本、二进制、大文件仓库搜索 |
| R11 | 全程 Future 截止覆盖连接、响应头与正文，超时/取消主动断开请求 | 200/302 慢正文、取消、声明和分块超限 |
| R12 | 异常包装保留 cause，服务边界使用 SafeFailures 保留类型/堆栈并剥离敏感消息 | 摘要重试 cause、Git 快照 cause、安全诊断测试 |
| R13 | Skill 资源采用 try-with-resources；gzip 构造失败关闭尚未移交的原始流 | Skill 成功/读取失败均关闭的回归；gzip 异常路径检查 |
| R14 | 评测显式传播中断并在请求前检查；裁判输入写入和等待退出共享截止时间，finally 清理已启动进程 | 两类中断停止后续场景、真实裁判子进程取消及不读 stdin 的大输入超时 |
| R15 | HTTP 与应用服务同时限制幂等键至 256 字符 | 256/257 边界及接口测试 |
| R16 | 历史记忆预算使用冻结策略 targetRatio 计算目标水位 | 0.70→0.50 的预算差额与现有压缩测试 |
| R17 | 两种策略的 Entity/Mapper/ServiceImpl、存储配置和迁移集中至 agent-persistence，纯值类型下沉 domain | 策略层无 MyBatis 引用；迁移兼容、raw 禁用装配测试 |
| R18 | 普通查询/CAS 改用类型安全 Wrapper；仅保留已解释的 PostgreSQL ON CONFLICT 插入 | 真实数据库 JSONB、CAS、空值清空测试 |
| R19 | 生产 MapStruct 使用 Spring component model 和构造器注入；层级存储同构转换采用生成 Mapper | 全模块编译与生成映射逐字段核对 |
| R20 | 五个核心领域类型移除公共通用 setter；快照 Builder 构造校验身份和水位，业务通过语义方法变更 | 消息不能跨执行归属、用户消息不能被助手结果覆盖、终态/版本约束测试 |
| R21 | 机械构造器/getter 改用精确 Lombok；保留校验、同步及配置绑定语义 | 全模块编译与既有测试 |
| R22 | 补充核心领域、Git View、配置等非直观字段的单位、空值及生命周期说明 | 源码复核 |
| R23 | 通过 Java AST 补齐相关生产控制语句括号，统一 Spotless 格式 | Spotless 独立验收 |

迁移兼容检查：4 个 SQL 文件与基线 `HEAD` 逐字节相同，原资源路径、版本号和 Flyway 历史表保留。删除旧构建产物后再运行最终验收，以免遗留 class/SQL 掩盖模块边界。

独立复核覆盖 HTTP 地址/超时、Git 取消、领域映射、评测资源、SSE 锁序与事务清理；问题按测试和源码证据关闭，不代表完成形式化证明或真实模型服务验收。

### 最终验收

- 当前服务持有工作区 JAR，未中断该服务；在临时源码副本执行 `mvn -f backend/pom.xml -B -fae clean verify spotless:check`，**BUILD SUCCESS**，耗时 8 分 6 秒。
- 验收后重新枚举文件并核对 SHA-256，工作区与副本的 **632 个后端源码/配置文件完全一致**，没有使用旧 target 产物。
- **共 608 项测试：604 通过、4 跳过、0 失败、0 错误。** PostgreSQL Testcontainers、两种策略存储及两类 SSE 周期恢复测试实际运行。
- 跳过项仍是 2 项真实 Codex、1 项真实 Ollama 和 1 项显式启用的黑盒压缩评测；未声称这些真实模型链路通过。
- 所有模块的 Spotless 独立检查通过；工作区 `git diff --check` 通过。
- 生产 MyBatis/MyBatis-Plus 引用已仅存在于 agent-persistence；五个核心会话领域类型没有公共 setter；运行时没有 agent-evals 依赖。
- 最终日志保留在本机：[clean verify 与 Spotless](C:/Users/82333/AppData/Local/Temp/agentv2-final-verify.log)。未提交任何构建产物或日志，未创建提交。

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| agent-context-common | 15 | 0 | 0 | 0 |
| agent-context-hierarchical | 27 | 0 | 0 | 0 |
| agent-context-lsm | 55 | 0 | 0 | 0 |
| agent-domain | 15 | 0 | 0 | 0 |
| agent-evals | 18 | 0 | 0 | 1 |
| agent-git | 50 | 0 | 0 | 0 |
| agent-memory | 123 | 0 | 0 | 0 |
| agent-persistence | 47 | 0 | 0 | 0 |
| agent-server | 90 | 0 | 0 | 1 |
| agent-skill | 8 | 0 | 0 | 0 |
| agent-tool | 37 | 0 | 0 | 0 |
| agent-web | 123 | 0 | 0 | 2 |
