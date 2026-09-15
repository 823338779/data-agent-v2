# 当前接口契约

本目录记录当前单实例实现实际提供的 REST 与 SSE 接口。删除字段和接口允许不兼容变更，契约必须与当前控制器同步。

## 权威边界

- `contracts/openapi.yaml` 是 REST 接口的唯一权威契约。
- `contracts/schemas/*.schema.json` 是 SSE 事件信封及载荷的唯一权威契约。
- `contracts/examples/*` 提供当前协议的最小可验证示例。
- 不在契约中保留尚未实现的定时任务、分布式执行或历史规划接口。

## 会话组织接口

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/agent-sidebar` | 一致性读取全部活动项目及轻量会话摘要，包含空项目 |
| `POST /api/v1/agent-projects` | 创建一级项目 |
| `PATCH /api/v1/agent-projects/{projectId}` | 提交名称和 expectedVersion 改名 |
| `DELETE /api/v1/agent-projects/{projectId}?expectedVersion=...` | 删除项目并解除归属，保留会话及置顶 |
| `PUT /api/v1/agent-sessions/{conversationSessionId}/organization` | 提交 projectId、pinned、expectedVersion 更新组织状态 |

组织请求的三个字段必须出现，`projectId: null` 表示未分组。会话创建允许省略 projectId；详情、摘要和详情 SSE 快照明确返回可空的 projectId、pinnedAt。缺失必填字段返回 400，不存在返回 404，版本冲突返回 409；无变化请求仍先校验版本。

现有 `/api/v1/agent-sessions/summary-events` 保留，当前前端未订阅它。前端通过侧栏 REST 聚合读取进行可见页一分钟轮询和聚焦刷新，仅当前运行中会话使用详情 SSE。

## 本地验证

在 V2 仓库根目录执行聚焦契约测试：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-web -Dtest='com.datagent.agentv2.contracts.*Test' test
```

执行整个后端 reactor 验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

验证必须能够在仅克隆本仓库、同级目录中不存在 `data-agent` 的环境中通过。
