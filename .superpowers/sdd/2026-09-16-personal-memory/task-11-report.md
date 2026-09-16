# 任务 11 实现报告：个人记忆管理前端面板及稳定操作

## 状态

DONE。实现基于 `develop@9d93b48`，按用户要求直接在当前 develop 工作区完成，未创建 worktree，未派生实现或评审代理。外部修改 `docs/git-repository-setup.md` 未读取、未修改、未暂存、未回滚。

## 实现内容

- 新增个人记忆前端领域契约，严格使用 `person_profile`、`collaboration_preference` 两个小写 wire 值；条目保留大写来源枚举、独立 entry version、带时区的 `updatedAt` 文本，设置使用独立 `settingsVersion`。
- 新增固定同源 `MemoryClient`，覆盖列表、创建、替换、DELETE query 删除、设置读取和更新六个方法。所有写请求携带调用方提供的 `Idempotency-Key`，DELETE 无 body；非 2xx 响应转换为只含 status、稳定 code 和安全 detail 的 `MemoryApiError`。
- 新增独立个人记忆面板，展示两个分类、服务端完整库存用量、来源、人工保护状态和更新时间；支持分类分页“加载更多”、新增、编辑、二次确认删除、明确恢复和自动学习开关。
- 正文始终通过普通 React 文本节点渲染；测试以含 HTML 标签的正文验证未产生图片节点，未使用 `dangerouslySetInnerHTML`。
- 首次列表读取使用服务端 `groups/quotas/revision`；分类续页只合并目标分类，保留另一分类已加载内容，配额始终替换为服务端完整用量，未按当前页正文累加。
- 创建返回 `ALREADY_EXISTS/created=false` 时只提示“已有相同记忆，已确认现有条目”，刷新并展示服务端真实条目，不声称新建或恢复成功。
- 编辑冲突时按分类刷新服务端条目和最新 version，保留本地 draft，禁用保存，要求用户点击“使用保留内容重新编辑”后才可按新 version 再次提交。
- 自动学习设置先乐观显示，保存失败后重新读取权威设置；若读取也失败则回滚到请求前已确认状态。界面明确说明关闭学习仍会使用已有记忆并允许手动保存。
- 幂等键生命周期按一次用户操作管理：同 payload 的网络异常、HTTP 408/未知 5xx 等不确定结果继续复用 key；只有契约中已识别的 400/404/409/422 Memory 业务拒绝结束旧操作。正文、entry version、restore 或设置版本/目标变化会生成新 key；明确业务拒绝后用户以同正文重新提交也生成新 key。
- App 壳层新增“个人记忆”入口。面板作为独立覆盖层挂载，不改写工作区路由，不重建会话主体；关闭后卸载，重开重新读取服务端列表和设置，当前 SSE 保持连接。
- 配额超限与内容拒绝均明确提示“未保存”并保留草稿；普通传输失败保留可重试输入。

## 前端接口

`MemoryClient` 暴露：

```text
list(kind?, cursor?): Promise<MemoryListView>
create(request, key): Promise<MemoryOutcome>
replace(id, request, key): Promise<MemoryOutcome>
remove(id, version, key): Promise<void>
settings(): Promise<MemorySettings>
updateSettings(request, key): Promise<MemorySettings>
```

HTTP 映射：

- `GET /api/user/memories[?kind=...&cursor=...]`
- `POST /api/user/memories`
- `PUT /api/user/memories/{id}`
- `DELETE /api/user/memories/{id}?expectedVersion=...`
- `GET /api/user/memories/settings`
- `PUT /api/user/memories/settings`

## 变更文件

- `frontend/src/domain/memory.ts`
- `frontend/src/api/memoryClient.ts`
- `frontend/src/api/memoryClient.test.ts`
- `frontend/src/components/memory/PersonalMemoryPanel.tsx`
- `frontend/src/components/memory/PersonalMemoryPanel.test.tsx`
- `frontend/src/components/shell/AppShell.tsx`
- `frontend/src/App.tsx`
- `frontend/src/App.test.tsx`
- `frontend/src/styles.css`
- `.superpowers/sdd/2026-09-16-personal-memory/task-11-report.md`

## TDD 证据

### 初始 RED

命令：

```text
npm --prefix frontend test -- src/api/memoryClient.test.ts src/components/memory/PersonalMemoryPanel.test.tsx src/App.test.tsx
```

结果：exit 1。`memoryClient.test.ts` 和 `PersonalMemoryPanel.test.tsx` 因生产模块尚不存在而各为 0 test；`App.test.tsx` 的新增集成场景真实失败于找不到“个人记忆”按钮。总计 15 个既有测试通过、1 个新增 App 场景失败，符合功能尚未实现的预期。

### 迭代 RED → GREEN

- 首轮实现后定向测试暴露两个测试响应复用了已消费的 `Response`，以及 crypto stub 未清理导致后续 key 为 undefined；修正测试隔离后，网络层、面板和 App 共 29/29 GREEN。
- 配额拒绝文案 RED：预期“容量不足，记忆未保存”，实际为通用“保存失败，请重试”；增加稳定业务提示后面板 8/8 GREEN。
- 内容拒绝文案 RED：预期“内容不符合保存要求，记忆未保存”，实际为通用失败；按 `UNSAFE_CONTENT/INVALID_REQUEST` 映射后 9/9 GREEN。
- 明确业务拒绝幂等边界 RED：配额拒绝后同正文重新提交实际仍使用 `quota-key-1`，预期 `quota-key-2`；完成操作键结束规则后 GREEN。
- HTTP 408 边界 RED：超时后同正文再次重试实际使用 `key-2`，预期继续使用 `key-1`；改为 HTTP 状态与已知 Memory code 双白名单判断明确业务拒绝后 GREEN。网络异常、未知 500、408 均复用 key，正文变化使用新 key。

### 最终 GREEN

定向命令：

```text
npm --prefix frontend test -- src/api/memoryClient.test.ts src/components/memory/PersonalMemoryPanel.test.tsx src/App.test.tsx
```

结果：exit 0；3 个测试文件、31 个测试通过，0 失败。分别为网络层 6、面板 9、App 16。

前端范围回归：

```text
npm --prefix frontend test
```

结果：exit 0；15 个测试文件、99 个测试通过，0 失败；输出无 React act、未处理 Promise 或控制台告警。

构建：

```text
npm --prefix frontend run build
```

结果：exit 0。Prettier 全仓检查通过，TypeScript project build 通过，Vite 成功转换 1852 个模块并生成产物；无告警。

格式化仅对本任务 9 个前端变更文件执行精确路径 `prettier --write`，未格式化无关文件。`git diff --check` 通过。

## 自审与顾虑

- 逐项核对了任务 8 的 URL、字段、状态码、DELETE 204、entry/settings 版本边界以及 POST/PUT `MemoryOutcome` 语义。
- 行为测试覆盖空状态、双分类和服务端配额、分类分页、普通文本渲染、增改删、冲突保留 draft、恢复新 key、`ALREADY_EXISTS` 文案、明确业务拒绝后新 key、不确定传输结果复用 key、设置失败回滚、面板重开刷新以及 SSE 不被中断。
- 首轮自审未发现遗留正确性顾虑；后续独立评审发现的分页、冲突刷新和模态键盘问题已按下述 fix round 修复，并只拆出对应的两个窄职责辅助文件。

## Fix round 1/5：分页冲突恢复与模态键盘边界

### 评审核实与修复

- 核实二页条目问题：原 `refreshKind(kind)` 只读取分类首屏，二页编辑目标找不到时继续保留旧 version，删除冲突则直接用首屏覆盖已加载组。新增 `memoryPanelSupport.ts`，冲突恢复从首屏开始沿 `nextCursor` 查找目标；找到后继续补足冲突前已加载条数，确保二页编辑和删除目标及相邻已加载内容不丢失。游标走到末尾仍找不到目标时才判定为服务端已删除；重复游标或任一页失败按刷新未完成处理，不能误报已删除。
- 核实冲突 key 与异常问题：原编辑和删除都在 `await refreshKind(...)` 之后才清理已被服务端明确拒绝的 key，刷新失败会产生未处理 Promise，并允许按旧 version/key 再次提交。现在收到 `CONFLICT` 后先立即结束旧 key 并锁住操作，再在独立 `try/catch` 中刷新。编辑刷新失败保留 draft、禁用保存，只暴露“重试刷新服务器版本”；刷新成功后要求用户再次确认，并按新 version 和新 key 提交。删除刷新失败隐藏确认删除，只允许重试读取目标；目标已删除和刷新失败使用不同提示及状态。
- 核实模态行为：原 `aria-modal=true` 没有初始焦点、Tab 约束、Escape 关闭和关闭后焦点恢复。新增窄职责 `useMemoryModal.ts`，打开时聚焦关闭按钮，Tab/Shift+Tab 在当前面板可用控件内循环，Escape 关闭，卸载时恢复打开按钮焦点。关闭回调通过 ref 读取，主焦点 effect 不依赖回调身份；App 同时使用稳定 `useCallback` 打开/关闭函数，SSE 或父组件更新不会重新执行初始聚焦并抢走正在输入的焦点。
- 为避免继续扩大原 516 行组件，只拆出两个确定职责：`memoryPanelSupport.ts` 负责分类游标恢复，`useMemoryModal.ts` 负责模态焦点生命周期；未引入通用分页框架或其他产品能力。

### TDD 证据

RED 命令：

```text
npm --prefix frontend test -- src/components/memory/PersonalMemoryPanel.test.tsx
```

结果：exit 1；13 个测试中 9 个通过、4 个失败，并捕获 1 个未处理拒绝。二页编辑和删除场景都没有调用 `list('person_profile', 'server-page-1')`；刷新失败场景找不到恢复提示且抛出未处理 `TypeError: refresh failed`；模态场景打开后焦点仍停在触发按钮。四个失败分别证明三个 Important 在原实现中真实存在。

GREEN 面板命令：

```text
npm --prefix frontend test -- src/components/memory/PersonalMemoryPanel.test.tsx
```

结果：exit 0；13/13 通过，0 失败、0 未处理错误。

最终聚焦验证：

```text
npm --prefix frontend test -- src/components/memory/PersonalMemoryPanel.test.tsx src/App.test.tsx
```

结果：exit 0；2 个测试文件、29 个测试通过，0 失败。面板 13 个场景覆盖二页编辑/删除冲突、刷新失败后恢复新 version/key，以及焦点循环、Escape、恢复焦点和父级回调身份变化；App 16 个场景保持通过。

构建：

```text
npm --prefix frontend run build
```

结果：exit 0；Prettier 检查、TypeScript project build 与 Vite 构建全部通过，1854 个模块转换成功，无告警。

本轮只对 `PersonalMemoryPanel.tsx`、对应测试、`memoryPanelSupport.ts`、`useMemoryModal.ts` 和 `App.tsx` 执行精确路径 Prettier。外部修改 `docs/git-repository-setup.md` 仍未读取、修改、暂存或回滚。本轮自审未发现遗留正确性顾虑。
