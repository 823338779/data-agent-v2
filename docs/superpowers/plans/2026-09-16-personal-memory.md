# 跨会话个人长记忆实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现固定身份下跨项目、跨会话的个人画像与协作偏好记忆，完成工具、上下文消费、人工维护、后台学习和可靠遗忘的完整闭环。

**Architecture:** 新增进程内 `agent-memory`，负责纯规则、统一写入边界、工具和审查规则。持久化集中在 `agent-persistence`，对话与模型适配和 REST 放在 `agent-web`；两种上下文策略共享运行时贡献契约，前端提供独立管理面板。

**Tech Stack:** Java 21、Spring Boot 4.1.0、Maven、MyBatis-Plus 3.5.16、PostgreSQL、Flyway、Lombok、MapStruct 1.6.3、Jackson 3、JUnit 5、AssertJ、Mockito、Testcontainers、Micrometer、React 19、TypeScript、Vitest、Testing Library。

**Spec:** [已确认的长记忆规格](../specs/2026-09-16-personal-memory-design.md)。实施者必须同时阅读根 `AGENTS.md` 和 spec；本计划不改变其产品范围。

## Global Constraints

- 分类只有 `person_profile`、`collaboration_preference`；不接受 `work_context` 别名。
- 使用 `TenantContext`、`OperatorContext` 固定身份；不新增认证、租户隔离或项目范围。
- 初始读取、显式写和自动学习全部开启；学习开关同时约束主 Agent 推断和后台学习，用户关闭状态跨重启保留。
- 个人画像 800、协作偏好 1500，按 Java UTF-16 正文总量计量；重复先于配额判断，不自动淘汰。
- 默认配置下注入每类最多 20 条、总计 1024 个估算 token；计入包裹和完整输入预算；不得污染原文或摘要源。
- 人工条目可由本轮明确对话请求维护，自动路径不得覆盖；模型意图判断不等同于服务端可证明授权。
- 删除物理移除活动正文与证据，同时抑制同指纹跨分类恢复；明确恢复需新证据，使用新 ID。
- 全部 Entity、Mapper、ServiceImpl、迁移留在 `agent-persistence`，其他模块不得引用 Mapper/MyBatis。
- 单表操作复用 MyBatis-Plus；Spring 注入、构造器、数据对象及转换遵循 Lombok、record、MapStruct 约定。
- 所有核心类型、字段含义、事务/CAS/租约原因和迁移注释使用中文；不机械注释语法。
- 审查默认延迟 30 秒、扫描 60 秒、启动延迟 30 秒、每轮最多领取 4 个任务；租约 120 秒，TTL/3 心跳。
- 后台最多 40 条完整正文、50000 UTF-16 单位；最多 8 次模型调用、16 次维护调用、120 秒总截止时间；失败 5 分钟重试，含首次最多 3 次。
- 模型调用不持数据库事务；所有模型写入检查冻结 revision，后台还检查 epoch、owner、token 和数据库时钟下的租约。
- 不添加向量检索、项目知识、任意同义遗忘、自动满额整理、批量语义归并或超长消息分段归纳。
- RED → GREEN → 必要整理；只格式化和提交当前任务文件，不将用户其他暂存变更带入提交。

## 执行前提、路径与任务依赖

规格基线为 `aceb6d5` 中的设计，书面评审状态已更新为通过。执行前重新检查 Git 状态及迁移序列，不提交或撤销无关用户变更。当前已有迁移 V1～V6，本计划使用 V7；若并行开发占用编号，按最新序列顺延并同时改对应迁移测试。

实施时按 using-git-worktrees 流程检查隔离情况；用户已明确选择直接在当前目录实施，因此保留develop检出，不另建worktree。相关模块测试基线已记录，环境问题与产品失败分别报告。

以下路径相对仓库根。为避免重复，路径表中的前缀是精确字符串拼接，不是模糊搜索：

| 前缀 | 实际目录 |
| --- | --- |
| D | `backend/agent-domain/src/main/java/com/datagent/agentv2/memory/` |
| DT | `backend/agent-domain/src/test/java/com/datagent/agentv2/memory/` |
| M | `backend/agent-memory/src/main/java/com/datagent/agentv2/memory/` |
| MT | `backend/agent-memory/src/test/java/com/datagent/agentv2/memory/` |
| P | `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/` |
| PT | `backend/agent-persistence/src/test/java/com/datagent/agentv2/persistence/` |
| W | `backend/agent-web/src/main/java/com/datagent/agentv2/` |
| WT | `backend/agent-web/src/test/java/com/datagent/agentv2/` |
| C | `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/context/` |
| CC | `backend/agent-context-common/src/main/java/com/datagent/agentv2/context/common/` |

任务依赖：1 → 2 → 3 → 4 → 5；6 依赖 1/4；7 依赖 5/6；8 依赖 4；9 依赖 2/4；10 依赖 5/6/9；11 依赖 8；12 依赖全部。顺序执行按编号即可。工具注册、默认启用和后台调度分别在依赖齐备的任务中装配，避免中间提交启动缺 Bean。

每项任务的示例测试方法放入所列测试类，使用 JUnit 5、AssertJ 或 Vitest 的常规导入；示例断言是最低验收，旁列场景同样需要覆盖。代码块给出关键实现及固定接口，不要求复制未使用的模板类型。

## 共享数据契约与文件责任

任务 1 创建以下领域类型，record 都防御性复制集合并验证必要不变量。业务 ID 为 String，版本及 sequence 为 long；DB 审计时间保持项目的 LocalDateTime 约定，租约使用 Instant/数据库 timestamptz。API 时间通过明确时区转 ISO 文本，禁止默用 JVM 时区。

| 文件 | 定义 |
| --- | --- |
| `D/MemoryKind.java` | PERSON_PROFILE、COLLABORATION_PREFERENCE，`value()`/`parse(String)` 固定映射 |
| `D/MemorySource.java` | USER_UI、USER_DIRECT、LLM_ACTIVE、BACKGROUND_REVIEW |
| `D/MemoryEntry.java` | memoryId、kind、content、normalizedHash、normalizationVersion、charCount、source、userProtected、version、createdAt、updatedAt、lastConfirmedAt、promptVersion |
| `D/MemoryState.java` | revision、settingsVersion、三个能力开关、learningEpoch、learningEnabledAt；初始 epoch=1、版本=0 |
| `D/MemoryQuota.java` | `record MemoryQuota(int used, int limit)` |
| `D/MemorySnapshot.java` | `record MemorySnapshot(MemoryState state, List<MemoryEntry> entries, Map<MemoryKind,MemoryQuota> quotas)` |
| `D/MemoryCommand.java` | sealed interface，嵌套 Add/Replace/Remove/Confirm；所有命令有 `reason()` 和 `evidenceMessageIds()` |
| `D/MemoryWriteReason.java` | USER_REQUESTED、AGENT_INFERRED；后台来源不从此字段获得 |
| `D/MemoryOutcome.java` | status、memoryId、version、revision、created、targetExists、quotas；`successful()` 判断五种成功态 |
| `D/MemoryStatus.java` | spec 的成功态与错误码枚举，禁止字符串散落 |
| `D/MemoryEvidence.java` | evidenceId、kind(USER_MESSAGE/UI_OPERATION/ASSISTANT_CONTEXT)、conversationId、executionId、sequence、authorUserId、messageVersion、content、createdAt；ASSISTANT_CONTEXT 只供 reviewer 理解语境，永不成为持久化新事实证据；content 只作短期输入，不在证据表复制 |
| `D/MemoryWriteContext.java` | caller（嵌套 enum Caller：UI/MAIN/BACKGROUND）、requestId、executionId、conversationId、expectedRevision（Long，人工 null）、reviewClaim(非后台 null)；身份从固定上下文取 |
| `D/MemorySettingsChange.java` | `record MemorySettingsChange(long expectedVersion, Boolean readEnabled, Boolean explicitWriteEnabled, Boolean autoLearningEnabled)`；null 表示不修改 |
| `D/MemoryException.java` | code、safeMessage，不携带个人正文或 SQL；主要用于传输/不可恢复依赖错误，业务判定优先返回 outcome |

命令的固定签名：

```java
record Add(MemoryKind kind, String content, MemoryWriteReason reason,
           List<String> evidenceMessageIds, boolean restore) implements MemoryCommand {}
record Replace(String memoryId, long expectedVersion, String newContent,
               MemoryWriteReason reason, List<String> evidenceMessageIds) implements MemoryCommand {}
record Remove(String memoryId, long expectedVersion, MemoryWriteReason reason,
              List<String> evidenceMessageIds) implements MemoryCommand {}
record Confirm(String memoryId, long expectedVersion,
               List<String> evidenceMessageIds) implements MemoryCommand {
    public MemoryWriteReason reason() { return MemoryWriteReason.AGENT_INFERRED; }
}
```

`MemoryReviewClaim` 由任务 1 同时声明于 `D/MemoryReviewClaim.java`：jobId、conversationId、epoch、jobToken、userToken、owner、claimedGeneration、fromSequence、throughSequence、leaseUntil。任务 9 提供其实例与校验，非后台调用不构造伪造 claim。

统一服务与对话证据端口（任务 3/4 实现，任务 5/8/10 消费）：

```java
// M/MemoryService.java
void initialize();
MemorySnapshot snapshot();
MemoryOutcome execute(MemoryWriteContext context, MemoryCommand command);
MemoryState updateSettings(String requestId, MemorySettingsChange change);

// M/MemoryEvidencePort.java，由 W/memory/WebMemoryEvidenceAdapter.java 实现
List<MemoryEvidence> resolve(MemoryWriteContext context, List<String> evidenceIds);
```

UI 命令 evidenceMessageIds 为空，由服务用本次已提交操作 ID 建立 UI_OPERATION 证据。后台证据必须属于 claim 的冻结区间；主对话证据必须是当前 execution 的真实用户消息。证据 port 的调用参与调用方事务，不能在新线程或独立事务绕过校验边界。

## 任务 1：规则、领域契约与可构建能力模块

**文件：** 创建共享表中所有 D 文件，`M/MemoryContentPolicy.java`、`M/config/MemoryProperties.java`、`backend/agent-memory/pom.xml`、`MT/MemoryContentPolicyTest.java`、`DT/MemoryKindTest.java`；修改 `backend/pom.xml` 增加 module。

**接口：** `MemoryContentPolicy.normalize(String): String`、`hash(String): String`、`validate(MemoryKind,String): void`、`limit(MemoryKind): int`。规则异常使用 MemoryException；默认属性从已确认 spec 逐项复制。

- [x] 写规范化、空内容、仅标点、UTF-16 配额、危险标记和精确分类测试；先证明能力不存在导致失败。

```java
@Test
void normalizationPreservesOriginalAndUsesUtf16Quota() {
    var policy = new MemoryContentPolicy(new MemoryProperties());
    assertThat(policy.normalize(" User\u3000prefers  Chinese！！！ "))
            .isEqualTo("user prefers chinese");
    assertThat(policy.hash("User prefers Chinese！"))
            .isEqualTo(policy.hash("user prefers chinese"));
    assertThat("😀".length()).isEqualTo(2);
    assertThatThrownBy(() -> policy.validate(MemoryKind.PERSON_PROFILE, "😀".repeat(401)))
            .isInstanceOf(MemoryException.class);
}
```

- [x] 在新模块 POM 尚未加入 reactor 前用 domain 测试验证 RED；模块创建后执行 `mvn -f backend/pom.xml -pl agent-memory -am '-Dtest=MemoryContentPolicyTest,MemoryKindTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，缺少类型时编译失败亦是目标能力缺失证据，不接受 Docker/依赖下载失败作为 RED。
- [x] 新模块依赖 domain、persistence、tool、Spring context/tx、Boot 配置绑定、Jackson、Micrometer；测试依赖 Boot Test、Testcontainers PostgreSQL。不依赖 web。使用现有父 POM Lombok/MapStruct，不自行升级依赖。
- [x] 实现以下规范化核心和有限安全校验；拒绝密钥样式、保留角色/运行时标记及危险控制字符，保留正常中文标点与换行；异常不包含输入正文。

```java
String normalized = input.toLowerCase(Locale.ROOT)
        .replaceAll("(?U)\\s+", " ").trim()
        .replaceAll("[.,!?;:。，！？；：]+$", "");
if (normalized.isBlank()) throw new MemoryException(MemoryStatus.INVALID_REQUEST, "记忆不能为空");
String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(normalized.getBytes(StandardCharsets.UTF_8)));
```

- [x] 运行上面的定向测试至 GREEN，补 enum 序列化 round-trip 和默认参数边界测试；按文件清单提交，消息 `feat(memory): 定义记忆契约与内容规则`。

## 任务 2：数据库迁移、物理删除与类型安全存储

**文件：** 创建 `backend/agent-persistence/src/main/resources/db/migration/V7__add_personal_memory.sql`；`P/entity/memory/MemoryRow.java` 及 `MemoryEntryEntity.java`、`MemoryStateEntity.java`、`MemoryOperationEntity.java`、`MemoryEvidenceEntity.java`、`MemorySuppressionEntity.java`、`MemoryReviewJobEntity.java`、`MemoryReviewLeaseEntity.java`；在 `P/mapper/memory/` 创建对应七个 Mapper，在 `P/service/memory/` 创建对应七个 Service；创建 `P/mapping/MemoryPersistenceMapper.java`、`PT/migration/PersonalMemorySchemaMigrationTest.java`、`PT/service/memory/MemoryPersistenceTest.java`、`D/MemoryReviewJob.java`、`D/MemoryReviewLease.java`、`D/MemoryOperation.java`、`D/MemorySuppression.java`。

**接口：** 各 Service 继承对应 `ServiceImpl`，对外返回领域 record，不暴露 Mapper 或 Wrapper。固定接口为 `MemoryStateService.lockCurrent(): MemoryState`、`MemoryEntryService.find(String): MemoryEntry`、`listCurrent(): List<MemoryEntry>`、`insert(MemoryEntry): MemoryEntry`、`replace(MemoryEntry,long): boolean`、`deletePhysical(String,long): boolean`。MemoryOperation 保存 requestId/payloadHash/outcome/createdAt，MemorySuppression 保存 normalizationVersion/hash/deletedRevision/deletedAt/deleteOperationId；对应 Service 为 `find(String key)`、`insert(record)`，抑制另有 `deleteByHash(String): boolean`。MemoryEvidenceService 为 `append(String memoryId,MemoryEvidence evidence): void`、`deleteByMemoryId(String): void`，存储映射显式忽略 content。审查两类 record 覆盖 spec 的任务/租约字段，两个 Service 提供 lock/find/CAS 以及在当前事务内 `validateClaim(MemoryReviewClaim): boolean`；校验读取真实数据库时钟，业务领取流程由任务 9 使用这些原语构成。

- [x] 以现有 ConversationProjectSchemaMigrationTest 为启动方式，在 PostgreSQL 17 上先迁到 V6 再升级；断言所有新表/列注释存在，精确重复受唯一约束限制，删除后 SQL 查不到正文。RED 命令：`mvn -f backend/pom.xml -pl agent-persistence -am '-Dtest=PersonalMemorySchemaMigrationTest,MemoryPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。

```java
@Test
void memoryDeletionMustNotLeaveSoftDeletedContent() {
    assertThat(MemoryRow.class.getSuperclass()).isEqualTo(Object.class);
    assertThat(Arrays.stream(MemoryRow.class.getDeclaredFields())
            .anyMatch(f -> f.isAnnotationPresent(TableLogic.class))).isFalse();
}
```

- [x] `MemoryRow` 仅包含数据库 id、固定 tenantId/userId、createdAt/updatedAt，无 TableLogic；Lombok Getter/Setter、protected 无参构造器。现有 AuditMetaObjectHandler 会填日期，但只给 TenantAuditedEntity 填身份，因此新行在持久化工厂中显式填两个固定身份，不修改通用身份框架。
- [x] 迁移创建七表；MemoryEntry 从 spec 字段完整落库，MemoryState 保存三个开关/三种版本及 `learning_enabled_at`，任务、租约保存冻结边界、epoch、代数、token、到期时间及失败原因，唯一键落实到数据库。证据不对可删除聊天正文设置阻止会话删除的外键，只引用稳定业务 ID；对活动 memory 关联可级联物理删除。每列包括主键/审计列都写 comment。

```sql
create unique index uq_personal_memory_content
    on personal_memory(tenant_id, user_id, kind, normalized_hash);
create unique index uq_personal_memory_operation
    on personal_memory_operation(tenant_id, user_id, request_id);
create unique index uq_personal_memory_suppression
    on personal_memory_suppression(tenant_id, user_id, normalization_version, normalized_hash);
```

- [x] 用 LambdaQueryWrapper `.last("FOR UPDATE")` 锁定 state；原子初始化使用 mapper 的 PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`，写完整固定列，不在调用方捕获唯一约束异常后继续已失败事务。只有这一数据库特性允许自定义 SQL。普通查询、CAS、物理 remove 用 Wrapper；MapStruct 使用 Spring component model，显式忽略/映射审计字段。
- [x] GREEN 后检查唯一键、UTF-16 charCount 与 SQL 字符长度不是同一口径、MapStruct protected 构造工厂；定向提交 `feat(memory): 建立记忆持久化与唯一约束`。

## 任务 3：原子新增、修订、配额和请求幂等

**文件：** 创建 `M/MemoryService.java`、`M/MemoryEvidencePort.java`、`M/MemoryWritePolicy.java`、`MT/MemoryServiceTest.java`、`MT/MemoryWriteConcurrencyTest.java`、`MT/MemoryIntegrationTestConfiguration.java`；按任务 2 接口补存储方法，不添加新 Mapper 入口给业务层。

**接口：** 实现共享服务签名；`MemoryWritePolicy.authorize(context,command,target,state): void`，target 对 add 可为空。`MemoryIntegrationTestConfiguration` 是模块内测试配置，导入现有 MybatisPlusConfiguration/AuditMetaObjectHandler、七个持久化 Service、生成的 MapStruct Mapper、MemoryService/Policy/ContentPolicy；提供 MemoryProperties、现有 PrefixedUuidIdGenerator、Mock MemoryEvidencePort 和真实 PostgreSQL，不依赖 web 测试 jar。参考已存在的 ConversationOrganizationPersistenceTest 设置 DynamicPropertySource 与容器生命周期。BACKGROUND 写入在本事务中依次锁/验证任务与用户租约，只有双方 validateClaim 通过才继续，不依赖工具层先前的租约检查。

- [x] 测试先覆盖规范化重复在满额时成功、替换碰撞不改旧值、同 requestId 不同 payload 冲突、同请求重试、revision/条目版本 CAS、并发抢额度。测试类通过 `@Autowired MemoryService memory` 获得真实事务代理。

```java
@Test
void sameRequestCannotCreateTwice() {
    var ctx = new MemoryWriteContext(MemoryWriteContext.Caller.UI, "req-1", null, null, null, null);
    var cmd = new MemoryCommand.Add(MemoryKind.PERSON_PROFILE, "用户主要使用中文",
            MemoryWriteReason.USER_REQUESTED, List.of(), false);
    MemoryOutcome first = memory.execute(ctx, cmd);
    MemoryOutcome retry = memory.execute(ctx, cmd);
    assertThat(first.status()).isEqualTo(MemoryStatus.CREATED);
    assertThat(retry.memoryId()).isEqualTo(first.memoryId());
    assertThat(memory.snapshot().entries()).hasSize(1);
}
```

- [x] RED/GREEN 命令：`mvn -f backend/pom.xml -pl agent-memory -am '-Dtest=MemoryServiceTest,MemoryWriteConcurrencyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；并发测试使用两个线程、独立事务及 CountDownLatch 同时开始，测试方法本身不要外包一个未提交事务。
- [x] `@Transactional` 服务按以下顺序实现；内部 policy 的可预期 MemoryException 转成 outcome 后记录并返回，工具/HTTP 适配器在事务外映射失败，不抛异常回滚已记录的确定性拒绝。意外数据库异常整事务回滚。`initialize()` 用同一短事务幂等建立 state，不改已有设置。

```text
lockCurrent → 查询幂等操作及比较完整语义 payloadHash
→ 校验开关/证据/目标版本/冻结 revision
→ 检查抑制/精确重复
→ 对最终正文集合求 sum(String.length)，拒绝超限
→ 保存正文与证据/增加 entry.version 与 state.revision
→ 保存不含正文的操作结果 → COMMIT
```

- [x] 原请求结果保持不变，返回时附目标当前存在性；主模型旧 revision 冲突不可通过重放成新请求绕过。操作 payloadHash 包含 reason、证据 ID、restore、目标及预期版本，不包含会随重试改变的瞬时时钟。
- [x] snapshot 在一个一致性读取边界获得 state 与 entries；不更新确认时间。GREEN 后提交 `feat(memory): 实现原子配额与写入幂等`。

## 任务 4：人工保护、删除恢复、设置与证据边界

**文件：** 修改 `M/MemoryService.java`、`M/MemoryWritePolicy.java`；创建 `MT/MemoryLifecycleTest.java`、`MT/MemorySettingsTest.java`、`W/memory/WebMemoryEvidenceAdapter.java`、`WT/memory/WebMemoryEvidenceAdapterTest.java`；修改 `backend/agent-web/pom.xml` 引入 agent-memory；补 `P/service/ConversationMessageService.java` 的领域查询方法。

**接口：** MemoryEvidencePort 如共享签名。WebMemoryEvidenceAdapter 另提供 `currentUserEvidence(String executionId): List<MemoryEvidence>` 给请求工厂冻结可引用 ID；resolve 在 MAIN 验证当前 execution 的 USER 作者，BACKGROUND 验证会话及 claim 区间。ConversationMessageService 增加 `findEvidenceByIds(List<String>): List<ConversationMessage>`，按执行读取复用已存在的 `findByExecutionId(String)`，不向 agent-memory 泄漏 Entity。

- [x] RED 测试受保护条目自动修改失败、用户显式修改成功、重复显式 add 提升保护、旧快照删除后恢复失败、新用户证据恢复成功、恢复超额仍保留抑制、关闭后重启设置仍为 false。

```java
@Test
void disablingLearningDoesNotDisableExplicitMemory() {
    MemoryState before = memory.snapshot().state();
    MemoryState changed = memory.updateSettings("settings-1",
            new MemorySettingsChange(before.settingsVersion(), null, null, false));
    assertThat(changed.autoLearningEnabled()).isFalse();
    assertThat(changed.readEnabled()).isTrue();
    assertThat(changed.explicitWriteEnabled()).isTrue();
    assertThat(changed.learningEpoch()).isGreaterThan(before.learningEpoch());
}
```

- [x] 实现保护矩阵，只有 UI/MAIN USER_REQUESTED 能编辑人工条目。事务内重新 resolve 真实证据，不接受模型给出的正文/作者；消息失效或被删除返回明确拒绝。后台 Confirm 有新证据才更新 lastConfirmedAt，普通注入/自动重复不刷新。
- [x] 删除在事务内物理删 entry/evidence，写跨 kind 抑制及操作记录；恢复验证新消息时间和 revision，先验证完整预算，再撤抑制和新建。实体更新/CAS 不可复用删除前 ID。

```java
boolean protectedTarget = target != null && target.userProtected();
boolean explicit = context.caller() == MemoryWriteContext.Caller.UI
        || (context.caller() == MemoryWriteContext.Caller.MAIN
            && command.reason() == MemoryWriteReason.USER_REQUESTED);
if (protectedTarget && !explicit) {
    throw new MemoryException(MemoryStatus.PROTECTED, "该记忆由用户维护");
}
```

- [x] updateSettings 在 state 锁内幂等和 CAS；设置始终可管理，不因 explicitWriteEnabled=false 锁死设置入口。autoLearningEnabled 切换增加 epoch/revision/settingsVersion，开启记录数据库时间 `learningEnabledAt`，已存在值不被启动默认覆盖。任务 9 接入后台资格时使用此时间和 epoch。
- [x] GREEN：分别运行 `mvn -f backend/pom.xml -pl agent-memory -am '-Dtest=MemoryLifecycleTest,MemorySettingsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 与 `mvn -f backend/pom.xml -pl agent-web -am '-Dtest=WebMemoryEvidenceAdapterTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；提交 `feat(memory): 实现人工保护与可靠遗忘`。

## 任务 5：记忆工具、可信调用快照与结构化失败

**文件：** 创建 `M/tool/MemoryTool.java`、`M/tool/MemoryToolDispatcher.java`、`M/tool/MemoryToolPrompts.java`、`M/tool/MemoryInvocationRegistry.java`、`MT/tool/MemoryToolTest.java`、`MT/tool/MemoryInvocationRegistryTest.java`、`WT/memory/MemoryToolFailureContractTest.java`；修改 `W/conversation/context/ConversationContextRequestFactory.java`、`W/conversation/react/ReactExecutionEngine.java`、`W/conversation/runtime/LocalAgentExecutionLauncher.java`、`backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/exception/ToolBusinessException.java`、`W/conversation/tool/ToolFailureSanitizer.java`、`W/conversation/tool/ToolExecutionResult.java`。

**接口：** MemoryTool 实现现有 AgentTool，`supportsIdempotency()` 返回 true；MemoryToolDispatcher 的 `dispatch(MemoryWriteContext, JsonNode): MemoryOutcome` 供主工具和后台共享参数校验/动作分派。list 走独立只读分支，返回快照分页数据。MemoryInvocationRegistry 提供 `open(String executionId,long invocationSequence,MemorySnapshot snapshot,List<MemoryEvidence> evidence)`、`contextFor(AgentTool.InvocationContext): MemoryWriteContext`、`advance(String,long,long expected,long committed): void`、`close(String,long): void`、`closeExecution(String): void`。

- [x] 写工具 schema 的条件参数测试、普通调用私有 confirm 拒绝、伪造身份/review_mode 拒绝、冻结 revision 在跨线程工具执行中保持、同轮自增允许但外部 revision 改变不允许。使用 Mockito mock MemoryService，缺参数时断言没有调用服务。

```java
@Test
void structuredBusinessFailureRemainsFailed() {
    ObjectMapper json = new ObjectMapper();
    var details = json.createObjectNode().put("status", "QUOTA_EXCEEDED").put("used", 800);
    var failure = new ToolBusinessException("QUOTA_EXCEEDED", "个人画像容量已满", details);
    ToolExecutionResult result = new ToolFailureSanitizer(json).sanitize(failure);
    assertThat(result.status()).isEqualTo(ToolExecutionResult.Status.FAILED);
    assertThat(result.content().path("status").asString()).isEqualTo("QUOTA_EXCEEDED");
}
```

- [x] RED/GREEN：`mvn -f backend/pom.xml -pl agent-web -am '-Dtest=MemoryToolTest,MemoryInvocationRegistryTest,MemoryToolFailureContractTest,ToolExecutionCoordinatorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- [x] Registry 用 `(executionId, invocationSequence)` 显式键，不用 ThreadLocal；工具在虚拟线程中执行。冻结 snapshot/revision 和允许证据，多个 tool call 仅以自身成功提交值 CAS 前进；list 获取的新快照不偷偷刷新冻结写入资格。每轮 try/finally close，launcher 的 finally 额外 closeExecution 防异常泄漏；不做内容跨轮缓存。
- [x] 扩展 ToolBusinessException 的可选安全 `JsonNode details`，保留现有两参数构造器。新增 `ToolExecutionResult.failed(String,String,JsonNode)` 重载，sanitizer 转交 details；现有流程已经支持 FAILED 节点同时有 output，不改为 SUCCEEDED。details 防御性复制且不包含正文、SQL 或证据。主工具在 MemoryService 事务已返回后映射失败：

```java
MemoryWriteContext writeContext = registry.contextFor(context);
long expectedRevision = writeContext.expectedRevision();
MemoryOutcome outcome = dispatcher.dispatch(writeContext, arguments);
if (!outcome.successful()) {
    throw new ToolBusinessException(outcome.status().name(), "记忆操作未完成",
            json.valueToTree(outcome));
}
registry.advance(context.executionId(), context.modelInvocationSequence(),
        expectedRevision, outcome.revision());
return json.valueToTree(outcome);
```

- [x] ToolPrompts 明确一条一事实、条件保留、先对照已有条目、当前指令优先、目标含糊先澄清、以结果确认成功。暂不改默认可见集合，任务 7 在证据元数据可用后一起启用。GREEN 提交 `feat(memory): 接入记忆工具与可信执行快照`。

## 任务 6：独立运行时贡献与预算选择

**文件：** 创建 `C/RuntimeContextContribution.java`、`C/RuntimeContextInput.java`、`C/ContextInputEstimator.java`、`CC/runtime/RuntimeContextComposer.java`、`backend/agent-context-common/src/test/java/com/datagent/agentv2/context/common/runtime/RuntimeContextComposerTest.java`、`M/context/MemoryContextRenderer.java`、`MT/context/MemoryContextRendererTest.java`；修改 `C/ConversationContextRequest.java`、`backend/agent-context-common/pom.xml` 增加 domain 依赖。

**接口：**

```java
record RuntimeContextContribution(String key, String wrapper, List<String> blocks, int maxTokens) {}
record RuntimeContextInput(List<ContextMessage> metadata, List<RuntimeContextContribution> contributions) {
    public static RuntimeContextInput empty() { return new RuntimeContextInput(List.of(), List.of()); }
}
interface ContextInputEstimator {
    String strategyId();
    int estimateMessages(List<ContextMessage> messages);
    int estimateCompleteInput(ConversationContextRequest request, List<ContextMessage> messages);
}
// ConversationContextRequest 增加 runtimeInput，保留原四参数构造器/工厂委托 empty()
// 每条 block 是一个完整记忆条目，renderer 保证稳定排序、分类标题及保留标记转义
RuntimeContextContribution MemoryContextRenderer.render(MemorySnapshot snapshot);
RuntimeContextComposer.Composition compose(ConversationContextRequest request,
        List<ContextMessage> dynamic, int preferredInputLimit,
        ToIntFunction<List<ContextMessage>> completeEstimator,
        ToIntFunction<List<ContextMessage>> messageEstimator);
record Composition(List<ContextMessage> dynamicMessages, int injected, int omitted) {}
```

- [x] 先测原消息不可变、以最后一个真实 USER 为锚、连续 assistant/tool 配对不拆开、整条省略、wrapper 放不下全部省略、仅元数据也计预算。测试构造 request 使用新五参数构造器，不修改 raw message。

```java
@Test
void noRuntimeInputLeavesProtocolUntouched() {
    var raw = List.of(ContextMessage.user("问题"), ContextMessage.assistant("回答", null));
    var request = new ConversationContextRequest(new ContextExecutionRef("tenant_local", "s", "e", 2),
            new ContextBudget(1000, 100, 10, 0), List.of(), List.of(), RuntimeContextInput.empty());
    var result = new RuntimeContextComposer().compose(request, raw, 890,
            ms -> ms.stream().mapToInt(m -> m.content().length() + 8).sum(),
            ms -> ms.stream().mapToInt(m -> m.content().length() + 8).sum());
    assertThat(result.dynamicMessages()).isEqualTo(raw);
    assertThat(result.injected()).isZero();
}
```

- [x] RED：`mvn -f backend/pom.xml -pl agent-context-common,agent-memory -am '-Dtest=RuntimeContextComposerTest,MemoryContextRendererTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；确认因贡献类型或组合行为尚未实现而失败，排除环境失败。
- [x] 先构造必要 metadata＋原 dynamic，再对每个完整 block 试装；完整输入和贡献自身同时满足预算才接受。estimate 函数必须纯计算，不能调用会持久化压缩结果的 `prepare()`。

```text
baseline = 在最后真实 USER 前插入 metadata 的 dynamic 副本
selected = baseline
按保护、确认时间、kind/ID 排序的 blocks 逐个试装 wrapper + 已选 blocks + 新 block
若 contribution估算≤1024 且 complete估算≤min(硬限, preferredInputLimit) 则接受
否则只省略新 block；wrapper 无法容纳则无记忆贡献
返回 selected 及完整省略统计，输入 raw 和 runtimeInput 均不变
```

- [x] renderer 每类筛前 20 条，明确记录预筛省略数量，与 composer 省略相加；无条目返回空贡献，不单发空 wrapper。内容转义后计量。必要证据元数据与可选记忆分离，读取关闭只禁用记忆正文，不隐式授权写入。
- [x] GREEN：重跑 `mvn -f backend/pom.xml -pl agent-context-common,agent-memory -am '-Dtest=RuntimeContextComposerTest,MemoryContextRendererTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认完整块边界、预算与原输入不可变的断言通过，再提交 `feat(context): 增加有预算的运行时记忆贡献`。

## 任务 7：同时接入 LSM 与 hierarchical，启用真实工具可见性

**文件：** 修改 `W/conversation/context/ConversationContextRequestFactory.java`；新增 `W/conversation/context/ContextInputEstimatorResolver.java`；修改两模块各自的 `runtime/LsmConversationContextAssembler.java`、`runtime/HierarchicalConversationContextAssembler.java`、`planning/ConversationTokenEstimator.java`、`configuration/LsmContextAutoConfiguration.java`、`configuration/HierarchicalContextAutoConfiguration.java`；测试修改同模块的 `runtime/*ConversationContextAssemblerTest.java`、`planning/ConversationTokenEstimatorTest.java`；新增 `WT/memory/MemoryContextRequestFactoryTest.java`；修改 `backend/agent-tool/src/main/java/com/datagent/agentv2/conversation/tool/runtime/ToolPolicyProperties.java` 以及 `backend/agent-server/src/main/resources/application.yml` 中已有工具集合配置。

两模块根路径分别为 `backend/agent-context-lsm/src/main/java/com/datagent/agentv2/context/lsm/` 和 `backend/agent-context-hierarchical/src/main/java/com/datagent/agentv2/context/hierarchical/`；测试目录只将 src/main 改为 src/test。上述已有文件均已在规划时核实。

**接口：** 两套 assembler 各自增加私有 `composeRuntime(request,dynamic,preferredLimit): Composition`，调用任务 6 composer 并传本策略现有 estimator；不改变现有策略 ID。两个 ConversationTokenEstimator 实现 ContextInputEstimator，hierarchical 的 estimateCompleteInput 直接委托已有 estimate。ContextInputEstimatorResolver 的 `current(): ContextInputEstimator` 按现有 ContextStrategyProperties 选择唯一实例；后台只调用此纯估算接口。Factory 读取 MemorySnapshot、currentUserEvidence 后 open registry，并给 request 附 runtimeInput。

- [x] 为两策略分别写完整成功路径和紧预算用例，断言 USER 角色、当前消息前的位置、准备前后数据库消息不变，摘要模型捕获的源文档不含注入正文。还测开启新会话、删除后下一 invocation 生效。

```java
// 放入已存在的 HierarchicalConversationContextAssemblerTest，复用其 request/policy/history/turns。
@Test
void personalMemoryIsTransientUserContext() {
    var runtime = new RuntimeContextInput(List.of(), List.of(
            new RuntimeContextContribution("personal-memory", "运行时历史数据", List.of("用户主要使用中文"), 1024)));
    var enriched = new ConversationContextRequest(request.execution(), request.budget(),
            request.fixedSystemMessages(), request.toolDefinitions(), runtime);
    when(history.load(request.execution())).thenReturn(turns("old", "latest"));
    var state = assembler.loadAndEstimate(enriched, policy);
    var prepared = assembler.prepareCurrentView(state);
    assertThat(prepared.messages()).filteredOn(m -> m.content().contains("用户主要使用中文"))
            .hasSize(1).allMatch(m -> m.role() == ContextMessage.Role.USER);
    assertThat(prepared.estimatedInputTokens()).isLessThanOrEqualTo(request.budget().hardInputLimitTokens());
    assertThat(state.turns().stream().flatMap(turn -> turn.messages().stream()))
            .noneMatch(m -> m.content().contains("用户主要使用中文"));
}
```

LSM 的相同边界使用已存在的 `assembler(segments)`、`request(budget)`、`turns(int)`、`policy()` 和 `ContextState` 测试工厂；在 `prepareCurrentView(state)` 捕获输出，在 `toCompactionRequest(state).candidateInputs()` 逐项检查 sourceDocument 不含注入正文。两个策略都需独立断言位置、token 硬限及 tool-call/result 配对，不能以仅测 composer 代替策略接入测试。

- [x] RED/GREEN：`mvn -f backend/pom.xml -pl agent-context-lsm,agent-context-hierarchical,agent-web -am '-Dtest=LsmConversationContextAssemblerTest,HierarchicalConversationContextAssemblerTest,ConversationTokenEstimatorTest,MemoryContextRequestFactoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- [x] 在 loadAndEstimate、最终组装和 historyMemoryBudget 中统一使用 composition 的实际贡献；所有 composer 调用都传未注入的 dynamic，不能重复装入 metadata。不得仅在最终 prepared 之后附加。软阈值先尽可能减少可选记忆；必要输入仍超限才沿原策略压缩/失败。压缩 planning/source 只用原始 turn，不把 runtime messages 放入 rawMessages 或持久化摘要。

```java
var composition = composer.compose(request, dynamic, preferredLimit,
        ms -> estimator.estimateCompleteInput(request, ms), estimator::estimateMessages);
int completeTokens = estimator.estimateCompleteInput(request, composition.dynamicMessages());
// hierarchical 对应调用 estimator.estimate；不替换两者现有估算口径。
```

- [x] memory 加入默认 initial/maximum；配置如果显式不含 memory 则保留该禁用，不向已有工具策略外偷偷加工具。Factory 加载失败时不猜 revision，标记该 invocation 记忆写入不可用，仍继续无记忆主请求；清理 registry 的 finally 必须覆盖模型异常与工具 continue。
- [x] 通过正式 toolDefinitions 捕获一次模型调用，断言实际 schema 含 memory 及可引用消息元数据；无元数据时不能用模型猜 ID。GREEN 后提交 `feat(memory): 接入两种上下文策略与工具可见性`。

## 任务 8：管理 API、输出对象与错误映射

**文件：** 创建 `W/controller/PersonalMemoryController.java`、`W/memory/PersonalMemoryApplicationService.java`、`W/memory/PersonalMemoryView.java`、`W/memory/PersonalMemoryListView.java`、`W/memory/MemorySettingsView.java`；新增 `WT/memory/PersonalMemoryControllerTest.java`、`WT/memory/PersonalMemoryViewTest.java`；修改 `backend/agent-server/src/main/java/com/datagent/agentv2/server/web/ApiExceptionHandler.java`，新增相邻测试 `backend/agent-server/src/test/java/com/datagent/agentv2/memory/MemoryProblemDetailTest.java`。MVC slice 测试依赖 `spring-boot-starter-webmvc-test` 加在 agent-web 的 test scope，符合 HTTP 契约测试边界。

**接口：** 与 spec §6 六个端点一致；ApplicationService 只组装 UI context、调用 MemoryService、处理 outcome，不访问 Entity。View 提供 `composeFromMemory(MemoryEntry)`、`composeFromSnapshot(MemorySnapshot)`，只输出 UI 必需字段，不输出 normalizedHash、删除抑制或证据原文。列表 wire 结构固定为 `{groups: {person_profile: {entries,nextCursor}, collaboration_preference: {entries,nextCursor}}, quotas: {person_profile: {used,limit}, collaboration_preference: {used,limit}}, revision}`；kind 过滤时未请求的组为空。分页从一致快照派生，以 memoryId 稳定升序的游标为界，两个组分别保留 nextCursor。

- [x] 写成功列表/创建、缺 Idempotency-Key、enum 别名拒绝、DELETE 无 body 且带 expectedVersion、重复键不同 payload、版本冲突、容量/抑制错误、settings partial update 的 HTTP 测试。

```java
@Test
void createRequiresIdempotencyHeader() throws Exception {
    mvc.perform(post("/api/user/memories").contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"person_profile\",\"content\":\"用户主要使用中文\"}"))
            .andExpect(status().isBadRequest());
}
```

- [x] RED/GREEN：`mvn -f backend/pom.xml -pl agent-server -am '-Dtest=PersonalMemoryControllerTest,PersonalMemoryViewTest,MemoryProblemDetailTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。MockMvc 在 web 用 standalone/slice 验证路由与 DTO，server 测试验证全局 ProblemDetail；不移动业务 controller 到 server。
- [x] Controller 请求 record 使用精确 Bean Validation，禁止 tenantId/userId/reviewMode 字段影响上下文。API 列表用稳定 ID 游标按类别分页；界面持续请求每类 nextCursor 至结束或按需加载，不能误把首 20 条当全部。返回完整库存 used/limit，与当前页条数独立。

```java
@DeleteMapping("/{id}")
ResponseEntity<Void> remove(@PathVariable String id, @RequestParam @Min(0) long expectedVersion,
        @RequestHeader("Idempotency-Key") String requestId) {
    application.remove(requestId, id, expectedVersion);
    return ResponseEntity.noContent().build();
}
```

- [x] ApplicationService 增加明确方法 `remove(String requestId,String id,long expectedVersion): void`，构造 UI Remove 并将失败映射 MemoryException。ApiExceptionHandler 增加 MemoryException → spec HTTP 状态/ProblemDetail 的分支；消息只用稳定安全文案，失败可附非敏感 quotas 字段。不改变其他业务异常分支。
- [x] settings 的 expectedVersion 指 settingsVersion，不混入 memory.entry.version；读取关闭不阻止 UI 列表，显式写关闭不阻止重新打开设置。GREEN 提交 `feat(memory): 增加个人记忆管理接口`。

## 任务 9：可靠审查任务、租约及学习代际

**文件：** 创建 `M/review/MemoryReviewCoordinator.java`、`M/review/MemoryReviewProgress.java`、`MT/review/MemoryReviewCoordinatorTest.java`、`PT/service/memory/MemoryReviewLeaseConcurrencyTest.java`；修改 `P/service/memory/MemoryReviewJobService.java`、`P/service/memory/MemoryReviewLeaseService.java` 及对应 Mapper；创建 `W/memory/MemoryReviewMarker.java`、`WT/memory/MemoryReviewMarkerTest.java`，修改 `W/conversation/react/ModelInvocationCoordinator.java` 的 completeFinalAnswer。

**接口：**

```java
// W/memory/MemoryReviewMarker：只在调用方的完成事务中标记，不发异步内存事件
void markCompleted(String executionId);
// M/review/MemoryReviewCoordinator：每个公开方法为独立短事务
List<MemoryReviewClaim> claimDue(String owner, int limit);
boolean renew(MemoryReviewClaim claim);
void finish(MemoryReviewClaim claim, MemoryReviewProgress progress);
void fail(MemoryReviewClaim claim, String reason, boolean blocked);
void release(MemoryReviewClaim claim);
record MemoryReviewProgress(long reviewedThroughSequence, int rejectedOperations) {}
```

- [x] 先以数据库集成测试证明：首次任务从触发用户消息开始、DONE 与任务标记同成败；RUNNING 新标记保留 owner/token 而增加 generation；过期重新领取的 token 递增；用户租约释放后 token 不归零；旧 epoch/旧 owner 完成失败。

```sql
-- 并发测试在两个连接分别领取和接管，随后以旧 owner/token 执行此 CAS，必须影响 0 行。
update personal_memory_review_job
set last_reviewed_sequence = 40
where job_id = 'job-1' and owner = 'old-worker' and fencing_token = 1
  and lease_until > current_timestamp;
```

- [x] RED/GREEN：`mvn -f backend/pom.xml -pl agent-web -am '-Dtest=MemoryReviewCoordinatorTest,MemoryReviewLeaseConcurrencyTest,MemoryReviewMarkerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。使用 PostgreSQL 原子更新时间模拟过期，不依赖 sleep 等待两分钟。
- [x] marker 与 completeFinalAnswer 同事务（`MANDATORY` 或无新事务，不能 REQUIRES_NEW）；先查固定 state、execution 和用户消息，只有成功轮次、学习开启且用户消息 createdAt 不早于 learningEnabledAt 才入当前 epoch，避免跨关闭期间的长运行轮次恢复后被追溯。旧 epoch 事件不得覆盖新 epoch，SQL upsert 的条件显式比较 epoch。

```text
当前 epoch 相同且 RUNNING：targetSequence=max(旧值,完成边界)，dirtyGeneration+1，保留 owner/token
当前 epoch 相同且非 RUNNING：更新上界、dueAt，置 PENDING；按规则重置失败计数
更高新 epoch：旧任务作取消收敛，建立新起点和更高 token；旧 worker 提交必定失效
更低事件 epoch：记录取消，不扩大新任务边界
```

- [x] claimDue 先无锁选候选，再按 state→job→userLease 顺序短事务竞争，冻结 claimedGeneration/claimedThroughSequence。任务表及租约表 token 都要验证；不能只检查其中一个。renew/finish/fail/release 也使用同序锁和 token，过期/关闭后续租不复活旧 owner。
- [x] finish 只能把连续已处理上界推进到不大于 claim.throughSequence 的位置；dirtyGeneration 增长或 target 未覆盖则回到 PENDING，否则 COMPLETED。state 切换 epoch 后无论后台模型是否结束都 CANCELLED，不混成 COMPLETED。GREEN 提交 `feat(memory): 增加可靠审查队列与租约控制`。

## 任务 10：有界后台 reviewer、消息切片与可恢复执行

**文件：** 创建 `M/review/MemoryReviewInput.java`、`M/review/MemoryReviewOutput.java`、`M/review/MemoryReviewModelPort.java`、`M/review/MemoryReviewHistoryPort.java`、`M/review/MemoryReviewMessage.java`、`M/review/MemoryReviewSliceSelector.java`、`M/review/MemoryReviewWorker.java`、`M/review/MemoryReviewPrompts.java`、`MT/review/MemoryReviewSliceSelectorTest.java`、`MT/review/MemoryReviewWorkerTest.java`；创建 `W/memory/MemoryReviewModelAdapter.java`、`W/memory/WebMemoryReviewHistoryAdapter.java`、`W/memory/MemoryReviewScheduler.java`、`WT/memory/MemoryReviewModelAdapterTest.java`；创建 `backend/agent-server/src/main/java/com/datagent/agentv2/server/memory/MemoryRuntimeConfiguration.java`，修改 `P/FixedIdentityBootstrap.java` 的初始化顺序；补 P/ConversationMessageService 的领域分页查询。

**接口：**

```java
// input 含固定快照、带 ID/sequence/role 的正文证据及 reviewer 自己的消息历史
record MemoryReviewInput(MemorySnapshot snapshot, List<MemoryEvidence> evidence,
                         List<Map<String,Object>> history, int maxInputTokens) {}
sealed interface MemoryReviewOutput {
    record Calls(List<Call> calls) implements MemoryReviewOutput {}
    record Done() implements MemoryReviewOutput {}
    record Call(String providerCallId, Map<String,Object> arguments) {}
}
interface MemoryReviewModelPort { MemoryReviewOutput complete(MemoryReviewInput input); }
record MemoryReviewMessage(long sequence, State state, MemoryEvidence evidence) {
    enum State { ELIGIBLE, EXCLUDED, PENDING }
}
interface MemoryReviewHistoryPort {
    List<MemoryReviewMessage> read(MemoryReviewClaim claim, long afterSequence, int limit);
}
// selector 接收按 sequence 排序的原始扫描结果及真实完整输入估算器
MemoryReviewSliceSelector.Slice select(List<MemoryReviewMessage> messages, long fromSequence,
        long throughSequence, ToIntFunction<List<MemoryEvidence>> estimate, int maxInputTokens);
record Slice(List<MemoryEvidence> messages, long reviewedThroughSequence, boolean blocked) {}
void MemoryReviewWorker.run(MemoryReviewClaim claim);
```

- [x] RED 测试第 41 条仍在下一批、工具/思考不进入证据、单条超限 blocked 不推进、先显式保存 A 后后台仍看到 B、各种业务拒绝的游标分类、部分成功后重试、deadline/续租失败停止、模型配置缺失阻塞。所有测试使用模型 port stub，不调用真实付费模型。

```java
@Test
void anOversizedFirstMessageCannotAdvanceTheCursor() {
    var evidence = new MemoryEvidence("u1", MemoryEvidence.Kind.USER_MESSAGE, "s", "e", 7,
            "user_admin", 0, "内容".repeat(100), LocalDateTime.of(2026, 9, 16, 12, 0));
    var row = new MemoryReviewMessage(7, MemoryReviewMessage.State.ELIGIBLE, evidence);
    var slice = new MemoryReviewSliceSelector(new MemoryProperties())
            .select(List.of(row), 6, 7,
                    rows -> rows.stream().mapToInt(message -> message.content().length()).sum() + 20, 100);
    assertThat(slice.blocked()).isTrue();
    assertThat(slice.reviewedThroughSequence()).isEqualTo(6);
}
```

- [x] RED/GREEN：`mvn -f backend/pom.xml -pl agent-web -am '-Dtest=MemoryReviewSliceSelectorTest,MemoryReviewWorkerTest,MemoryReviewModelAdapterTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- [x] WebMemoryReviewHistoryAdapter 使用 ConversationMessageService 的 `findReviewPage(String sessionId,long after,long through,int limit): List<ConversationMessage>` 和现有 `AgentExecutionService.findByExecutionIds(Collection<String>)` 批量查询，映射为 ELIGIBLE/EXCLUDED/PENDING；执行状态不做每条 N+1 查询。明确删除或不存在的范围可以标 EXCLUDED，仍在运行的必须 PENDING，不能过滤掉后假装连续完成。selector 在 PENDING 或首个放不下的完整消息前停止，EXCLUDED 不提炼但可推进已判断边界。
- [x] 分页从持久化 sequence 正序读，批次上界固定。assistant 只以 ASSISTANT_CONTEXT 辅助输入，MemoryEvidencePort 拒绝其用作事实证据。对后台输入同样转义运行时标记；提示词、完整小库、schema 和正文全部通过 ContextInputEstimatorResolver.current() 计预算，硬限取 ContextModelBudgetProvider.currentBudget()，不调用主会话 prepare()。无容量装首条时 BLOCKED/INPUT_TOO_LARGE。
- [x] reviewer 适配现有 AgentModelGateway，只传 memory 定义，输出 delta 丢弃，不用主会话 tools/SSE/process checkpoint。后台 prompt 要求保守提炼、对照已有快照、只引用用户证据、保护人工条目、无合格信息时不调用工具。模型端口返回 Calls 或 Done，异常传给 worker，不能异常转 Done。
- [x] worker 每次创建 BACKGROUND context，将 command payload 规范化后用 task/epoch/证据批次/动作摘要作 requestId。每次 submit 在 MemoryService 同事务再次 fence；成功后读取返回 revision 只推进自己的预期值。明确业务拒绝记录但可判断完成；无效参数/证据、冲突和基础设施失败停止当前批次并保留游标。

```text
领取 claim → 启动 TTL/3 续租与截止时间守卫
→ 加载快照/完整连续切片 → 仅 memory 的模型循环
→ 对每项动作执行统一 dispatcher，保存安全结构化 observation 供下一次模型调用
→ 自身成功更新 expectedRevision，外部冲突停止并重读
→ Done 且所有未解决动作已收敛：finish(实际连续边界)
→ 失败：fail，不推进未完成范围；finally 取消心跳/释放合法租约
```

- [x] server 只装配最多 4 个后台工作线程、单独心跳 scheduler 和销毁钩子；调度编排在 web。FixedIdentityBootstrap 标记 `@Order(0)`，MemoryRuntimeConfiguration 提供 `@Order(1)` ApplicationRunner 调用 `memory.initialize()`（短事务执行 state 幂等初始化）；scheduler 等应用 ready 后开始，保证 learningEnabledAt 在第一轮请求之前固定，不能每次读取重置。provider 不响应 interrupt 时停止提交权仍由数据库租约保证，未退出任务占用受限槽位，不继续无限创建线程。GREEN 提交 `feat(memory): 实现后台提炼与失败恢复`。

## 任务 11：前端管理面板和稳定的用户操作

**文件：** 创建 `frontend/src/domain/memory.ts`、`frontend/src/api/memoryClient.ts`、`frontend/src/api/memoryClient.test.ts`、`frontend/src/components/memory/PersonalMemoryPanel.tsx`、`frontend/src/components/memory/PersonalMemoryPanel.test.tsx`；修改 `frontend/src/components/shell/AppShell.tsx`、`frontend/src/App.tsx`、`frontend/src/App.test.tsx`、`frontend/src/styles.css`。

**接口：** MemoryClient 的 `list(kind?:MemoryKind,cursor?:string):Promise<MemoryListView>`、`create(request,key)`、`replace(id,request,key)`、`remove(id,version,key)`、`settings()`、`updateSettings(request,key)`。请求与响应类型逐项对应任务 8；MemoryKind 是两个小写 wire 值的 union，列表使用任务 8 的 groups/quotas/revision 结构，groups 内有 entries/nextCursor；面板 props 为 `{ client: MemoryClient; onClose(): void }`。

- [x] 写网络层所有方法/Idempotency-Key/DELETE query 断言，UI 测试空状态、两类列表与容量、增改删、版本冲突保留 draft、抑制后明确恢复使用新 key、关闭保存失败回滚。用真实用户交互驱动测试，不只 snapshot DOM。

```tsx
it('设置保存失败后仍显示服务端的开启状态', async () => {
  const client = createMemoryClientStub();
  client.updateSettings.mockRejectedValue(new Error('不可用'));
  render(<PersonalMemoryPanel client={client} onClose={() => {}} />);
  const toggle = await screen.findByRole('checkbox', { name: '自动学习' });
  fireEvent.click(toggle);
  await screen.findByText('设置保存失败，请重试。');
  expect(toggle).toBeChecked();
});
```

- [x] 在该测试文件定义以下 fixture，mutation 场景按任务 8 响应替换对应 mock，不能从其他任务引用不存在的全局 fixture：

```tsx
function createMemoryClientStub() {
  return {
    list: vi.fn().mockResolvedValue({
      groups: { person_profile: { entries: [], nextCursor: null },
        collaboration_preference: { entries: [], nextCursor: null } },
      quotas: { person_profile: { used: 0, limit: 800 },
        collaboration_preference: { used: 0, limit: 1500 } }, revision: 0,
    }),
    create: vi.fn(), replace: vi.fn(), remove: vi.fn().mockResolvedValue(undefined),
    settings: vi.fn().mockResolvedValue({ readEnabled: true, explicitWriteEnabled: true,
      autoLearningEnabled: true, settingsVersion: 0 }),
    updateSettings: vi.fn(),
  };
}
```
- [x] RED/GREEN：`npm --prefix frontend test -- src/api/memoryClient.test.ts src/components/memory/PersonalMemoryPanel.test.tsx src/App.test.tsx`。
- [x] 复用 `createIdempotencyKey()`，同操作的传输重试复用 key；正文/版本/restore 改变即新操作。每类分页持续加载或明确“加载更多”，used 从服务端用量取值，不能用当前页累加冒充全部。内容用普通 React 文本输出，不用 dangerouslySetInnerHTML。

```tsx
<label>
  <input type="checkbox" aria-label="自动学习" checked={settings.autoLearningEnabled}
    disabled={savingSettings} onChange={handleLearningChange} />
  自动学习
</label>
<p>关闭后停止主动提炼和后台学习，仍会使用已有记忆，也可以手动保存。</p>
```

- [x] 新增壳层“个人记忆”按钮打开独立面板，不重写路由或现有 App 会话逻辑。面板负责自己的加载/编辑状态；从 App 关闭和重开后取真实最新列表，退出时不影响当前 SSE。冲突刷新服务端条目但保留 draft，提供重新编辑确认动作。
- [x] 运行定向测试与 `npm --prefix frontend run build`，只对变更文件执行 Prettier；提交 `feat(memory): 增加个人记忆管理面板`。

## 任务 12：完整装配、指标、评测材料与使用文档

**文件：** 创建 `M/observability/MemoryMetrics.java`、`MT/observability/MemoryMetricsTest.java`；新增 `backend/agent-server/src/test/java/com/datagent/agentv2/memory/PersonalMemoryFlowIntegrationTest.java`、`PersonalMemoryRestartIntegrationTest.java`、`PersonalMemoryConfigurationIntegrationTest.java`；新增 `backend/agent-evals/src/test/resources/memory-eval/cases.jsonl`、`backend/agent-evals/src/test/java/com/datagent/agentv2/evals/memory/MemoryEvalCorpusTest.java`、`docs/个人长记忆使用与验收.md`；修改 MemoryService/renderer/worker 指标接入点及 `backend/agent-evals/README.md`。

**接口：** `MemoryMetrics.recordOperation(MemorySource,MemoryStatus)`、`recordInjection(int candidates,int injected,int tokens)`、`recordReview(String status,Duration duration)`。指标标签只允许枚举低基数值，不带用户、会话、记忆 ID 或正文。

- [x] 使用现有 server 集成测试风格和独立 PostgreSQL，stub AgentModelGateway 完成一次真实 execution→memory tool→新会话输入捕获→页面修改→删除→后台旧结果拒绝。分别以 lsm、hierarchical 配置执行，不用 mock MemoryService 跳过事务。
- [x] 重启测试保留同一测试数据库，关闭并重新创建 Spring context：记忆和三个开关保持，过期任务可接管、旧 token 无效。失败后重新实例化 Java 对象不算进程恢复验收。只使用测试容器，不连接用户业务数据库。

```java
@Test
void memoryMetricLabelsNeverContainPersonalContent() {
    var registry = new SimpleMeterRegistry();
    new MemoryMetrics(registry).recordOperation(MemorySource.USER_UI, MemoryStatus.CREATED);
    assertThat(registry.getMeters()).allSatisfy(meter ->
            assertThat(meter.getId().getTags()).allSatisfy(tag ->
                    assertThat(tag.getKey()).isIn("source", "status")));
}
```

- [x] Corpus 至少包含下列 10 个案例，每行存 `id/messages/expectedActions/expectedKind/requiredConditions/forbiddenActions`；MemoryEvalCorpusTest 用 Jackson 2（eval 模块现有版本）读取全部行，校验 ID 唯一、字段完整、分类枚举正确。不得把这些结构测试报告为真实模型语义通过。

```json
{"id":"temporary-language","messages":["这次用英文写"],"expectedActions":[],"expectedKind":null,"requiredConditions":[],"forbiddenActions":["add"]}
{"id":"conditional-evidence","messages":["以后排查生产事故时先列证据，再分析原因"],"expectedActions":["add"],"expectedKind":"collaboration_preference","requiredConditions":["排查生产事故时"],"forbiddenActions":["remove"]}
```

其余案例固定为 stable-role、language-profile、two-facts、weak-praise、semantic-duplicate、explicit-correction、ambiguous-forget、explicit-restore。后三者的执行材料包含既有记忆状态和最新用户消息；同义重复要记录模型是否复用已有条目，不能只看总条数。
- [x] 中文验收文档给出在隔离测试部署手工执行 corpus 的顺序：清理测试数据→准备案例前态→发送实际对话→记录工具状态/保存内容/跨会话行为→人工标注正确保存、过度记忆、漏记、误修改。报告模型/provider/prompt 版本和分母；未跑真实模型明确记“未执行”，没有标注的样本不计算准确率。报告模板不附生产正文或凭据，不新增运行时对 evals 的依赖。
- [x] 文档同时说明：两类用途、默认开启、三个开关边界、关闭/重开不补扫旧 epoch、对话/API/页面操作、容量公式、超大消息阻塞、删除与聊天历史/备份的边界。加入本计划定义的默认参数表、稳定错误码及最小 curl/PowerShell 请求示例。
- [x] 完成以下分层验证，检查 Surefire 报告的执行/跳过数，不能把没有运行的数据库或真实模型验证标成成功。

```powershell
mvn -f backend/pom.xml -pl agent-server -am '-Dtest=PersonalMemoryFlowIntegrationTest,PersonalMemoryRestartIntegrationTest,PersonalMemoryConfigurationIntegrationTest,MemoryMetricsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -f backend/pom.xml -pl agent-evals '-Dtest=MemoryEvalCorpusTest' test
mvn -f backend/pom.xml -pl agent-server -am test
npm --prefix frontend test
npm --prefix frontend run build
mvn -f pom.xml verify
git diff --check
```

- [x] Maven 输出若依赖外部服务失败，区分基线失败与本变更失败并修复相关问题；不得无说明加 skipTests 或 disabledIfNoDocker。完成实际所需验证后提交 `test(memory): 验证完整记忆闭环并补充使用说明`。

## 提交方式与检查点

每个任务完成 RED/GREEN 和自身 review 后，只暂存该任务列出的文件；禁止 `git add .`。任务 7 后检查主工具与跨会话闭环；任务 10 后检查后台、关闭学习与删除竞争；任务 12 后进行完整规格与代码评审。提交消息已在各任务末尾指定。

最终集成遵循 finishing-a-development-branch 流程，由实际执行模式与用户选择决定合并方式；本计划不授权推送、部署、修改生产设置或清理用户数据。

## 规格覆盖与计划自审

| spec 验收 | 实现/验证任务 |
| --- | --- |
| M01、M20 | 5、7、12：真实工具链、两策略、重启与回归 |
| M02、M17 | 4、9、10、12：持久设置、epoch、在途拒绝、不回扫 |
| M03、M04、M05 | 1、2、3：规范化、唯一键、配额竞争、幂等与 CAS |
| M06、M07 | 4、5：保护与真实用户证据，不把模型 reason 当独立授权 |
| M08、M09 | 4、9、10、12：抑制、物理删除、新证据恢复与旧 token |
| M10 | 8、11：API 契约及前端冲突/失败体验 |
| M11、M12 | 6、7：预算、协议位置、无历史/摘要污染 |
| M13、M14、M15、M16 | 9、10、12：连续游标、代数、租约、部分成功与重试 |
| M18、M19 | 1、5、7、8、10：内容/标记、结构化失败、降级 |
| 语义评测、中文说明、指标 | 12：独立样本与明确人工评估，低敏统计 |

执行检查清单：

- [x] 实施任务之前核对全局约束与前置接口，新增域对象不绕过框架能力。
- [x] 任务 code/test 中新增方法名和 record 字段与共享契约一致；接口变更同步更新调用任务。
- [x] 20 个规格验收均有执行结果或明确环境限制，真实模型结果独立报告。
- [x] 完成后回填各任务 checkbox，不将仅写计划误标为功能完成。

执行状态（2026-09-17）：用户已选择subagent-driven-development，并直接在develop实施；任务1–12已完成独立评审，最终整体评审提出的四项问题已集中修复并通过限定复审，无未解决阻断项。修复前server reactor实际585/跳过3；最终修复后根verify实际615/跳过4、前端108例及构建通过，各轮单列不累计。勾选表示验收目标已落实，实际类名、命令与窄接口修订以实现及使用与验收文档为准，不要求逐字复制示例。真实模型语义评测未执行（10条材料已准备、人工标注0），不将stub通过计为语义通过。提交保留在当前本地develop，未推送。
