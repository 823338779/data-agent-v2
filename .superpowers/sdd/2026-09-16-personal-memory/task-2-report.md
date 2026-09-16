# 任务 2 实施报告：长记忆迁移与类型安全存储

## 状态

已完成。实现范围严格停留在迁移、领域记录、Entity、Mapper、MapStruct 转换、ServiceImpl 存储原语及真实 PostgreSQL 验证；未实现统一 `MemoryService`、领取业务流程或后台 worker。

## 具体变更

- 新增 Flyway V7 迁移，创建 `personal_memory`、`personal_memory_state`、`personal_memory_operation`、`personal_memory_evidence`、`personal_memory_suppression`、`personal_memory_review_job`、`personal_memory_review_lease` 七张表。
- 所有表和所有列均包含中文数据库注释；数据库唯一键覆盖活动正文、状态、操作、抑制、证据、会话任务和用户租约。
- `MemoryRow` 直接继承 `Object`，仅承载数据库主键、固定 tenant/user 和创建/更新时间，没有 `TableLogic`。正文删除使用物理删除，证据通过活动 memory 外键级联物理删除，不对可删除会话正文建立阻塞外键。
- 新增四个领域 record：`MemoryOperation`、`MemorySuppression`、`MemoryReviewJob`、`MemoryReviewLease`。审查任务明确保存学习 epoch、连续游标、冻结上界、dirty/claimed generation、状态、失败原因、owner、任务 token 和租约时间。
- 新增七组 Entity、`BaseMapper` 和继承 `ServiceImpl` 的持久化服务。固定身份由各 Entity 的受保护构造器配合语义工厂显式写入，不修改通用身份填充器。
- MapStruct 使用 Spring component model 和 `@ObjectFactory` 调用实体工厂；显式处理枚举/字符串、审计字段和证据正文忽略。`MemoryEvidence.content` 不写入长期表。
- 普通查询、业务版本 CAS 和物理删除使用 MyBatis-Plus Lambda Wrapper。自定义 SQL 仅用于 PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` 原子初始化状态，以及基于 `CURRENT_TIMESTAMP` 的租约校验；前者避免唯一键异常使当前事务 aborted，后者避免 worker 时钟漂移。
- `compareAndSet` 用于调用方已经按既定顺序持有同一行锁后的条件更新。实现通过 Wrapper 对所有可变字段显式 `.set(...)`，因此 `owner`、`leaseUntil`、`failureReason` 等可空字段能可靠清空，不受 MyBatis-Plus 默认非空更新策略影响。完整 epoch/owner/token 校验和单调 token 策略由任务 9 编排。
- `MemoryReviewClaim.fromSequence` 按排除下界、`throughSequence` 按包含上界验证。任务 claim 同时核验 job、conversation、epoch、任务/用户 token、owner、claimed generation、冻结边界、claim 租约值以及数据库当前时钟下两级租约有效性。
- `char_count` 只存储上层按 Java UTF-16 得出的值；没有使用 PostgreSQL `length` 计算或约束该口径。

## 实际接口签名

- `MemoryStateService.lockCurrent(): MemoryState`
- `MemoryEntryService.find(String): MemoryEntry`
- `MemoryEntryService.listCurrent(): List<MemoryEntry>`
- `MemoryEntryService.insert(MemoryEntry): MemoryEntry`
- `MemoryEntryService.replace(MemoryEntry, long): boolean`
- `MemoryEntryService.deletePhysical(String, long): boolean`
- `MemoryOperationService.find(String): MemoryOperation`
- `MemoryOperationService.insert(MemoryOperation): MemoryOperation`
- `MemorySuppressionService.find(String): MemorySuppression`
- `MemorySuppressionService.insert(MemorySuppression): MemorySuppression`
- `MemorySuppressionService.deleteByHash(String): boolean`
- `MemoryEvidenceService.append(String, MemoryEvidence): void`
- `MemoryEvidenceService.deleteByMemoryId(String): void`
- `MemoryReviewJobService.find(String): MemoryReviewJob`
- `MemoryReviewJobService.lock(String): MemoryReviewJob`
- `MemoryReviewJobService.insert(MemoryReviewJob): MemoryReviewJob`
- `MemoryReviewJobService.compareAndSet(MemoryReviewJob, long): boolean`
- `MemoryReviewJobService.validateClaim(MemoryReviewClaim): boolean`
- `MemoryReviewLeaseService.findCurrent(): MemoryReviewLease`
- `MemoryReviewLeaseService.lockCurrent(): MemoryReviewLease`
- `MemoryReviewLeaseService.insert(MemoryReviewLease): MemoryReviewLease`
- `MemoryReviewLeaseService.compareAndSet(MemoryReviewLease, long): boolean`
- `MemoryReviewLeaseService.validateClaim(MemoryReviewClaim): boolean`

## TDD 证据

RED 1：

```text
mvn -f backend/pom.xml -pl agent-persistence -am '-Dtest=PersonalMemorySchemaMigrationTest,MemoryPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
BUILD FAILURE：MemoryRow、MemoryPersistenceMapperImpl、MemoryEntryService、MemoryStateService 不存在，共 7 个预期编译错误。
```

RED 2（可空租约释放）：

```text
mvn -f backend/pom.xml -pl agent-persistence -am '-Dtest=MemoryPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 4, Failures: 1；owner/lease_until 未被默认非空更新策略清空。
```

RED 3（操作结果完整往返）：

```text
mvn -f backend/pom.xml -pl agent-persistence -am '-Dtest=MemoryPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 5, Failures: 1；预期两类 quota，实际为空 Map。
```

GREEN 定向：

```text
mvn -f backend/pom.xml -pl agent-persistence -am '-Dtest=PersonalMemorySchemaMigrationTest,MemoryPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS。
```

范围回归：

```text
mvn -f backend/pom.xml -pl agent-persistence -am test
agent-domain：11 tests；agent-persistence：29 tests；Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS。
```

格式与静态检查：`spotless:apply` 后执行 `spotless:check`；`git diff --check` 无错误。

## 文件范围

- `backend/agent-domain/src/main/java/com/datagent/agentv2/memory/`：4 个领域 record。
- `backend/agent-persistence/src/main/resources/db/migration/V7__add_personal_memory.sql`。
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/memory/`：8 个类型（共享基类加七实体）。
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapper/memory/`：7 个 Mapper。
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/memory/`：7 个 ServiceImpl。
- `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapping/MemoryPersistenceMapper.java`。
- 两个真实 PostgreSQL 测试类及本报告。

## 自审

- 唯一键与 spec 一致；抑制唯一键不包含 kind，确保跨分类生效。
- 证据不复制 content；活动 memory 删除后 SQL 无正文，证据外键级联删除。
- 受保护无参构造器只能由 Entity 自身工厂和 MapStruct `@ObjectFactory` 安全取得。
- 服务对外只返回领域 record，不暴露 Mapper、Entity 或 Wrapper。
- 自定义 SQL 均有数据库特性理由；普通 CRUD/CAS 仍使用 MyBatis-Plus。
- 用户无关变更 `docs/git-repository-setup.md` 未修改、未暂存、未提交。

## 疑虑

无阻塞疑虑。`MemoryReviewLeaseService.validateClaim` 只能验证用户级 token、owner 和数据库租约；完整任务关联、epoch、generation 与冻结边界由同一事务内的 `MemoryReviewJobService.validateClaim` 验证，任务 9 应同时调用两者或以 job 校验为完整门禁。

## 第 1 轮评审修复

评审指出三项 Important，均已按 TDD 修复：

- 两个租约校验 Mapper 从事务固定的 `CURRENT_TIMESTAMP` 改用实际调用时刻的 `clock_timestamp()`。新增测试先在同一事务中读取 `current_timestamp` 固定事务时间，再创建短租约并等待实际到期；修复前 job/lease 校验仍返回有效，修复后均返回无效。
- Job claim 不再要求数据库任务租约与不可变 claim 的 `leaseUntil` 完全相等，而是要求任务和用户当前租约均不早于 claim 原到期值。新增测试验证两级租约正常延长后原 claim 仍有效，任务 token、用户 token 或 owner 任一变化均无效，且实际数据库时间过期后无效。
- `MemoryEntryService.replace` 改为使用 MyBatis-Plus Wrapper 显式设置完整可变字段，避免实体非空策略和 Wrapper 重复赋值，并允许 `lastConfirmedAt`、`promptVersion` 从非空清为 `null`。新增持久化往返测试验证两个字段确实清空。

本轮 RED：

```text
mvn -f backend/pom.xml -pl agent-persistence -am '-Dtest=MemoryPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 8, Failures: 3；三个失败分别对应可空字段残留、续租使原 claim 失效、同事务中实际过期仍被 CURRENT_TIMESTAMP 放行。
```

本轮 GREEN：

```text
mvn -f backend/pom.xml -pl agent-persistence -am '-Dtest=MemoryPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS。
```
