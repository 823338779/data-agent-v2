# 会话标题编辑实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Data Agent V2 中实现受乐观锁保护的会话标题编辑，并提供双击侧边栏标题的完整前端交互。

**Architecture:** OpenAPI 定义 `PATCH /api/v1/agent-sessions/{conversationSessionId}` 契约；独立的 `ConversationTitleService` 负责标题规范化、版本检查和持久化，控制器复用 `ConversationApplicationService.get` 返回完整快照。前端客户端透传会话 `version`，`App` 负责请求及错误收敛，`ConversationList` 只负责局部编辑状态和键盘交互。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、MockMvc、React 19、TypeScript、Vitest、Testing Library。

## Global Constraints

- 直接在当前 `data-agent-v2/` 工作区执行，不创建或切换 Git worktree，不使用子代理。
- 保留现有架构和所有用户未提交修改；只触碰本功能所需逻辑。
- 新增字段和逻辑块使用中文注释说明用途；新增 Java/TypeScript 字符串使用静态常量。
- 标题去除首尾空格后必须为 1–120 个字符。
- 标题更新必须提交 `expectedVersion`，版本冲突返回 HTTP 409。
- 不引入新依赖，不改变标签、排序、删除或归档行为。
- 严格执行 RED → GREEN；整个计划完成并验证后创建一个实现 commit。

---

## 文件结构

- `contracts/openapi.yaml`：声明标题局部更新路径、请求 schema 和错误响应。
- `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/application/ConversationTitleService.java`：封装标题规范化和乐观锁写入。
- `backend/agent-web/src/main/java/com/datagent/agentv2/web/conversation/ConversationController.java`：暴露 PATCH 控制器入口和校验 DTO。
- `backend/agent-web/src/test/java/com/datagent/agentv2/contracts/ContractPathsTest.java`：冻结公开契约。
- `backend/agent-server/src/test/java/com/datagent/agentv2/AgentV2ApplicationTest.java`：验证真实 HTTP、数据库和并发行为。
- `frontend/src/api/backendAgentClient.ts`：传递版本并调用 PATCH 接口。
- `frontend/src/domain/agentPrototype.ts`：在前端会话模型中保留服务端版本。
- `frontend/src/api/backendAgentClient.test.ts`：验证客户端请求和响应映射。
- `frontend/src/components/ConversationList.tsx`：实现标题输入态。
- `frontend/src/components/ConversationList.test.tsx`：验证双击、保存和取消交互。
- `frontend/src/App.tsx`：连接标题编辑、后端更新和冲突恢复。
- `frontend/src/App.test.tsx`：验证保存成功、冲突刷新和普通失败行为。
- `frontend/src/styles.css`：保持输入框与现有会话行一致。

---

### Task 1: 冻结标题更新 API 契约

**Files:**
- Modify: `backend/agent-web/src/test/java/com/datagent/agentv2/contracts/ContractPathsTest.java`
- Modify: `contracts/openapi.yaml`

**Interfaces:**
- Produces: `PATCH /api/v1/agent-sessions/{conversationSessionId}`。
- Produces: `RenameConversationSessionRequest { title: string; expectedVersion: int64 }`。
- Produces: 成功返回 `ConversationSession`；失败支持 400、401、404、409。

- [ ] **Step 1: 写入失败的契约测试**

在 `openApiFreezesPerUserActivityAndOrderingContracts` 中加入对 PATCH、请求字段及响应的断言：

```java
JsonNode rename =
        api.path("paths")
                .path("/api/v1/agent-sessions/{conversationSessionId}")
                .path("patch");
assertThat(rename.path("requestBody").path("content").path("application/json")
                .path("schema").path("$ref").asText())
        .isEqualTo("#/components/schemas/RenameConversationSessionRequest");
JsonNode renameRequest = schemas.path("RenameConversationSessionRequest");
assertThat(renameRequest.path("additionalProperties").asBoolean()).isFalse();
assertThat(renameRequest.path("required").toString())
        .contains("title", "expectedVersion");
```

并把资源路径的必需方法改为 `Set.of("get", "patch")`。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  -Dtest='com.datagent.agentv2.contracts.ContractPathsTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: FAIL，原因是 PATCH 操作和 `RenameConversationSessionRequest` 尚不存在。

- [ ] **Step 3: 最小化更新 OpenAPI**

在已有会话资源路径下增加 `patch`，引用请求 schema 并声明 200/400/401/404/409；新增 schema：

```yaml
RenameConversationSessionRequest:
  type: object
  additionalProperties: false
  required: [title, expectedVersion]
  properties:
    title: { type: string, minLength: 1, maxLength: 120 }
    expectedVersion: { type: integer, format: int64, minimum: 0 }
```

- [ ] **Step 4: 运行契约测试并确认 GREEN**

重复 Step 2 命令，Expected: PASS。

---

### Task 2: 实现后端标题更新事务

**Files:**
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/application/ConversationTitleService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/web/conversation/ConversationController.java`
- Modify: `backend/agent-server/src/test/java/com/datagent/agentv2/AgentV2ApplicationTest.java`

**Interfaces:**
- Consumes: `ConversationSessionService.findByUuid(String)` 和 `updateById(ConversationSession)`。
- Produces: `void ConversationTitleService.rename(String conversationSessionId, String title, long expectedVersion)`。
- Produces: `ConversationView ConversationController.rename(...)`。

- [ ] **Step 1: 写入成功和并发冲突集成测试**

新增测试创建会话、读取初始 `version`、提交带空格标题并验证规范化结果，再使用旧版本重试：

```java
@Test
void authenticatedUserCanRenameConversationWithOptimisticLocking() throws Exception {
    Cookie session = login();
    MvcResult created = createConversation(session, "Original title");
    JsonNode body = OBJECT_MAPPER.readTree(created.getResponse().getContentAsString());
    String id = body.path("conversationSessionId").asText();
    long version = body.path("version").asLong();

    mvc.perform(patch("/api/v1/agent-sessions/{conversationSessionId}", id)
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"  Renamed title  \",\"expectedVersion\":" + version + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Renamed title"))
            .andExpect(jsonPath("$.version").value(version + 1));

    mvc.perform(patch("/api/v1/agent-sessions/{conversationSessionId}", id)
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Stale title\",\"expectedVersion\":" + version + "}"))
            .andExpect(status().isConflict());
    mvc.perform(get("/api/v1/agent-sessions/{conversationSessionId}", id).cookie(session))
            .andExpect(jsonPath("$.title").value("Renamed title"));
}
```

测试中复用或补充私有 `createConversation` helper，避免重复创建请求。

- [ ] **Step 2: 运行集成测试并确认 RED**

Run:

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dtest='com.datagent.agentv2.AgentV2ApplicationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: FAIL，PATCH 返回 405 Method Not Allowed。

- [ ] **Step 3: 实现独立标题服务和控制器**

核心服务逻辑保持单一职责：

```java
@Service
@RequiredArgsConstructor
public class ConversationTitleService {
    private static final String ERROR_NOT_FOUND = "Conversation session not found";
    private static final String ERROR_VERSION_CONFLICT = "Conversation session version is stale";

    private final ConversationSessionService sessions;

    @Transactional
    public void rename(String conversationSessionId, String title, long expectedVersion) {
        ConversationSession session = sessions.findByUuid(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_NOT_FOUND);
        }
        if (session.getVersion() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_VERSION_CONFLICT);
        }
        session.setTitle(title.trim());
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_VERSION_CONFLICT);
        }
    }
}
```

控制器注入服务并新增：

```java
@PatchMapping("/{conversationSessionId}")
ConversationView rename(
        @PathVariable String conversationSessionId,
        @Valid @RequestBody RenameConversationSessionRequest request) {
    titles.rename(conversationSessionId, request.title(), request.expectedVersion());
    return conversations.get(conversationSessionId);
}

record RenameConversationSessionRequest(
        @NotBlank @Size(max = 120) String title, @Min(0) long expectedVersion) {}
```

- [ ] **Step 4: 写入标题边界测试**

在同一集成测试类增加空白标题和 121 字符标题请求，分别断言 `400`，并 GET 验证原始标题未变。

- [ ] **Step 5: 运行集成与契约测试并确认 GREEN**

先重复 Step 2，再运行 Task 1 Step 2。Expected: 两组均 PASS。

---

### Task 3: 扩展前端会话版本和 PATCH 客户端

**Files:**
- Modify: `frontend/src/domain/agentPrototype.ts`
- Modify: `frontend/src/api/backendAgentClient.ts`
- Modify: `frontend/src/api/backendAgentClient.test.ts`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/components/ConversationList.test.tsx`

**Interfaces:**
- Produces: `ConversationSession.version: number`。
- Produces: `AgentBackendClient.renameSession(id, title, expectedVersion): Promise<ConversationSession>`。

- [ ] **Step 1: 写入客户端失败测试**

在后端 fixture 中增加 `version: 4`，调用：

```typescript
const renamed = await client.renameSession('session_1', '新的标题', 4);
expect(renamed.version).toBe(5);
expect(fetchImpl).toHaveBeenCalledWith(
  '/api/v1/agent-sessions/session_1',
  expect.objectContaining({
    method: 'PATCH',
    credentials: 'include',
    body: JSON.stringify({ title: '新的标题', expectedVersion: 4 }),
  }),
);
```

模拟响应必须包含完整 `BackendSession`，仅把标题改为“新的标题”、版本改为 5。

- [ ] **Step 2: 运行客户端测试并确认 RED**

Run:

```powershell
npm.cmd test -- --run src/api/backendAgentClient.test.ts
```

Expected: TypeScript/Vitest FAIL，原因是 `renameSession` 不存在或 `version` 未映射。

- [ ] **Step 3: 实现版本映射和客户端方法**

在 `ConversationSession`、`BackendSession` 和 `toSession` 中加入必填 `version`；接口及实现增加：

```typescript
renameSession(conversationSessionId, title, expectedVersion) {
  return sessionRequest(`/${encodeURIComponent(conversationSessionId)}`, {
    method: 'PATCH',
    body: JSON.stringify({ title, expectedVersion }),
  });
}
```

为 `App.test.tsx` 和 `ConversationList.test.tsx` 的完整会话 fixture 增加字面量 `version`，不改变其他字段。

- [ ] **Step 4: 运行客户端测试并确认 GREEN**

重复 Step 2，Expected: PASS。

---

### Task 4: 实现侧边栏双击编辑与错误收敛

**Files:**
- Modify: `frontend/src/components/ConversationList.test.tsx`
- Modify: `frontend/src/components/ConversationList.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: `AgentBackendClient.renameSession(id, title, expectedVersion)`。
- Produces: `ConversationListProps.onRename(id, title): Promise<boolean>`；`true` 表示退出编辑态，`false` 表示保留草稿。

- [ ] **Step 1: 写入 ConversationList 失败交互测试**

使用有状态测试包装器验证真实可见结果：

```tsx
function RenameHarness() {
  const [items, setItems] = useState(sessions);
  return (
    <ConversationList
      sessions={items}
      activeSessionId="first"
      onOpen={vi.fn()}
      onReorder={vi.fn()}
      onRename={async (id, title) => {
        setItems((current) =>
          current.map((item) =>
            item.conversationSessionId === id ? { ...item, title, version: item.version + 1 } : item,
          ),
        );
        return true;
      }}
    />
  );
}
```

双击“第一个会话”，断言输入框聚焦；修改为带空格的新标题并按 Enter，断言显示去空格后的新标题。另写测试覆盖 Esc、空白输入不保存，以及 `onRename` 返回 `false` 时输入框和草稿仍存在。

- [ ] **Step 2: 运行组件测试并确认 RED**

Run:

```powershell
npm.cmd test -- --run src/components/ConversationList.test.tsx
```

Expected: FAIL，双击后没有标题输入框。

- [ ] **Step 3: 实现最小编辑状态机**

增加 `editingId`、`draftTitle`、`savingTitle` 和取消标记。非编辑态继续渲染当前可拖拽按钮；编辑态用同尺寸输入框替换按钮，避免在 `<button>` 中嵌套交互控件。

关键提交函数：

```typescript
async function finishTitleEdit(session: ConversationSession) {
  if (cancelTitleEdit.current) {
    cancelTitleEdit.current = false;
    setEditingId(null);
    return;
  }
  const title = draftTitle.trim();
  if (!title || title === session.title) {
    setEditingId(null);
    return;
  }
  setSavingTitle(true);
  const shouldClose = await onRename(session.conversationSessionId, title);
  setSavingTitle(false);
  if (shouldClose) setEditingId(null);
}
```

Enter 只调用 `blur()`，由 `onBlur` 统一保存；Esc 设置取消标记后调用 `blur()`。输入框设置 `maxLength={120}`、自动聚焦和全选。

- [ ] **Step 4: 实现 App 保存、冲突恢复并写失败测试**

先在 `App.test.tsx` 增加失败测试：成功时页面 `<h1>` 更新；409 时调用 `getSession` 并显示最新标题；500 时输入框保留草稿且显示“标题保存失败，请稍后重试。”。

再实现：

```typescript
async function renameSession(conversationSessionId: string, title: string): Promise<boolean> {
  const session = sessions.find((item) => item.conversationSessionId === conversationSessionId);
  if (!session) return true;
  setError(null);
  try {
    replaceSession(await backend.renameSession(conversationSessionId, title, session.version));
    return true;
  } catch (failure) {
    if (failure instanceof BackendApiError && failure.status === 409) {
      replaceSession(await backend.getSession(conversationSessionId));
      setError('标题已在其他位置更新，已加载最新标题。');
      return true;
    }
    handleFailure(failure, '标题保存失败，请稍后重试。');
    return false;
  }
}
```

把 `onRename={(id, title) => renameSession(id, title)}` 传给 `ConversationList`。

- [ ] **Step 5: 增加与现有会话行一致的输入样式**

新增 `.session-title-input`，保持现有行高、边距、圆角、字体和焦点色；保存时通过 `:disabled` 降低透明度并使用等待光标。不得改动拖拽插入线和拖拽浮层样式。

- [ ] **Step 6: 运行组件、App 和完整前端验证并确认 GREEN**

Run:

```powershell
npm.cmd test -- --run src/components/ConversationList.test.tsx src/App.test.tsx
npm.cmd test
npm.cmd run build
```

Expected: 定向测试、全部前端测试和生产构建均 PASS。

---

### Task 5: 全量验证并创建实现提交

**Files:**
- Verify only: 根仓库与 `data-agent-v2/` 状态、上述所有变更文件。

**Interfaces:**
- Consumes: Tasks 1–4 的完整实现。
- Produces: 一个不包含用户无关修改的实现 commit。

- [ ] **Step 1: 运行后端相关模块验证**

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -am `
  -Dtest='com.datagent.agentv2.contracts.*Test' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server -am `
  -Dtest='com.datagent.agentv2.AgentV2ApplicationTest' `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 全部 PASS、零失败。

- [ ] **Step 2: 运行前端完整验证**

```powershell
Set-Location .\frontend
npm.cmd test
npm.cmd run build
```

Expected: 全部 PASS，格式检查、TypeScript 和 Vite 构建退出码均为 0。

- [ ] **Step 3: 检查变更边界**

```powershell
git status --short
git diff --check
git diff -- contracts/openapi.yaml backend/agent-web/src/main/java/com/datagent/agentv2/conversation/application/ConversationTitleService.java backend/agent-web/src/main/java/com/datagent/agentv2/web/conversation/ConversationController.java backend/agent-server/src/test/java/com/datagent/agentv2/AgentV2ApplicationTest.java frontend/src/api/backendAgentClient.ts frontend/src/api/backendAgentClient.test.ts frontend/src/domain/agentPrototype.ts frontend/src/components/ConversationList.tsx frontend/src/components/ConversationList.test.tsx frontend/src/App.tsx frontend/src/App.test.tsx frontend/src/styles.css
git -C .. status --short --ignore-submodules=none
```

确认没有覆盖工作区中与本功能无关的已有修改。对于本任务开始前已修改的文件，只暂存本计划新增的 hunk。

- [ ] **Step 4: 创建单一实现提交**

仅暂存本计划变更并提交：

```powershell
git commit -m "feat: 支持编辑会话标题"
```

提交后再次执行 `git status --short`，确认用户原有暂存和未暂存修改仍被保留。
