# 会话标题编辑设计

## 目标

为 Data Agent V2 增加完整的会话标题编辑能力。用户可以在侧边栏双击会话标题进入编辑态，前端通过受版本保护的局部更新接口保存标题，并在并发冲突或请求失败时给出明确反馈。

## 范围

- 支持在侧边栏双击会话标题进入编辑态。
- 支持 Enter 或输入框失焦保存，Esc 取消。
- 标题保存前去除首尾空格，不能为空，最长为 120 个字符。
- 前后端通过会话 `version` 实现乐观并发控制。
- 保存成功后同步侧边栏标题和当前会话页标题。
- 不修改会话标签、排序或其他会话属性。

## 前端设计

`ConversationList` 管理当前正在编辑的会话标识、草稿标题和保存状态。双击 `.session-link-title` 后，将标题文本替换为自动聚焦且全选的输入框。

- Enter：触发输入框失焦，由统一的失焦路径提交，避免重复请求。
- Esc：标记本次编辑为取消并恢复原始标题，失焦时不提交。
- 失焦：对草稿执行 `trim()`；结果为空或与原标题相同时退出编辑态且不调用接口；有效变更调用上层 `onRename`。
- 保存中：输入框保持可见并禁用，阻止重复提交。
- 保存成功：应用后端返回的完整 `ConversationSession`，随后退出编辑态。
- 保存失败：保留草稿和编辑态，允许用户修正或重试；错误文本由 `App` 复用现有全局错误提示展示。
- 版本冲突：`App` 重新获取目标会话，以服务端快照恢复列表并显示“标题已在其他位置更新，已加载最新标题。”。

`BackendAgentClient` 新增以下能力：

```typescript
renameSession(
  conversationSessionId: string,
  title: string,
  expectedVersion: number,
): Promise<ConversationSession>;
```

请求使用 `PATCH /api/v1/agent-sessions/{conversationSessionId}`，请求体为：

```json
{
  "title": "新的会话标题",
  "expectedVersion": 3
}
```

## 后端设计

`ConversationController` 在现有会话资源路径上增加 `PATCH` 方法。请求 DTO 包含必填标题和非负 `expectedVersion`：标题使用 Bean Validation 限制非空且不超过 120 个字符，应用服务负责 `trim()` 后再次校验，防止纯空白标题绕过约束。

`ConversationApplicationService.rename` 在事务中完成以下操作：

1. 按当前租户和用户可见范围读取会话；不存在时保持现有 `404` 行为。
2. 比较会话当前 `version` 与 `expectedVersion`；不一致时返回 `409 Conflict`。
3. 设置规范化后的标题，并通过现有 `ConversationSessionService.updateById` 持久化。
4. 如果乐观锁更新失败，返回相同的 `409 Conflict`。
5. 返回包含新标题、新版本及原有消息和执行状态的完整 `ConversationView`。

标题修改不改变用户排序版本，也不写入新的会话用户状态。会话自身的 `updatedAt` 和 `version` 由现有持久化审计与乐观锁机制推进。

## 错误约定

- `400 Bad Request`：标题缺失、为空、仅包含空白或超过 120 个字符，沿用统一 Problem 响应。
- `404 Not Found`：会话不存在或对当前租户、用户不可见。
- `409 Conflict`：`expectedVersion` 已过期或持久化时发生并发更新。
- 其他错误：沿用现有全局异常处理；前端保留编辑草稿并允许重试。

## 测试设计

后端集成测试验证真实 HTTP、事务和数据库行为：

- 正确版本可以更新标题，响应和后续 GET 均返回去除首尾空格后的标题，且版本递增。
- 空白标题和超过 120 个字符的标题返回 `400`。
- 过期版本返回 `409`，且原始标题不变。

前端测试验证用户可观察行为：

- 双击标题后出现自动聚焦的输入框。
- Enter 和失焦分别能够保存有效变更，且单次编辑只发起一次请求。
- Esc、空白标题和未改变标题不会发起请求。
- 保存失败时保留草稿输入框，允许再次提交。
- 客户端测试验证 `PATCH` 路径、请求体和 Cookie 认证配置。

## 非目标

- 不增加右键菜单、标题旁编辑按钮或移动端长按编辑。
- 不增加会话标签编辑、删除、归档或批量重命名。
- 不引入新的拖拽、表单或状态管理依赖。
- 不改变现有会话排序协议。
