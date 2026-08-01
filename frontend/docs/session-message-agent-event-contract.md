# session/message/agent-event 前端契约

DAT-2 前端按以下 TypeScript 契约消费后端事件。源文件为 `src/api/agentEvents.ts`，mock 流实现为 `src/mocks/agentEventStream.ts`，真实后端 HTTP 适配器为 `src/api/backendAgentClient.ts`。

## 通用字段

每个事件都包含：

- `eventId`：事件唯一 ID。
- `type`：事件类型。
- `sessionId`：会话 ID。
- `runId`：Agent run ID。
- `sequence`：会话内递增序号，前端按此顺序投影状态。
- `createdAt`：ISO 时间戳。
- `payload`：事件负载。

## 事件类型

- `run.started`：`payload.objective`。
- `message.created`：`payload.messageId`、`role`、`author`、`content`、`timestamp`。
- `reasoning.delta`：`payload.stepId`、`title`、`summary`。
- `tool.call.started`：`payload.toolCallId`、`toolName`、`title`、`summary`、`input`。
- `tool.call.completed`：`payload.toolCallId`、`output`、可选 `summary`。
- `tool.call.failed`：`payload.toolCallId`、`error`。
- `approval.requested`：`payload.approvalId`、`title`、`description`、`riskLevel`。
- `approval.resolved`：`payload.approvalId`、`decision`、`resolvedAt`。
- `run.completed`：`payload.answer`。

## 前端投影规则

- `message.created` 追加到当前会话消息区。
- `reasoning.delta` 进入 assistant 消息内的可折叠思考流。
- `tool.call.started/completed/failed` 进入思考流中的浅色工具调用块。
- `approval.requested` 在会话流内展示确认框。
- 用户点击允许或拒绝后，前端把 approval 状态更新为 `approved` 或 `rejected`，确认框隐藏，并展示一条会话内结果状态。

## 后端接入说明

真实后端当前通过 REST 返回 session/run/events，并暴露 SSE 事件流。前端已接入 REST 路径：

- `GET /api/v1/agent-sessions`
- `POST /api/v1/agent-sessions`
- `POST /api/v1/agent-sessions/{sessionId}/messages`
- `POST /api/v1/agent-sessions/{sessionId}/confirmations/{confirmationId}`

开发环境 `vite.config.ts` 会把 `/api` 代理到 `http://127.0.0.1:8080`。UI 组件只依赖 `AgentBackendClient` 返回的前端会话模型，不直接依赖 mock 数据或后端 DTO。
