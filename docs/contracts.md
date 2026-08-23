# 当前接口契约

本目录记录当前单实例实现实际提供的 REST 与 SSE 接口。删除字段和接口允许不兼容变更，契约必须与当前控制器同步。

## 权威边界

- `contracts/openapi.yaml` 是 REST 接口的唯一权威契约。
- `contracts/schemas/*.schema.json` 是 SSE 事件信封及载荷的唯一权威契约。
- `contracts/examples/*` 提供当前协议的最小可验证示例。
- 不在契约中保留尚未实现的定时任务、分布式执行或历史规划接口。

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
