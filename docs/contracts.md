# 阶段 0 契约基线

阶段 0 将 Data Agent V1 的有效外部行为冻结为由 V2 仓库自行持有、可独立验证的契约制品。

## 权威边界

- `contracts/openapi.yaml` 是 REST 接口的唯一权威契约。
- `contracts/schemas/*.schema.json` 是 SSE 事件信封及载荷的唯一权威契约。
- `contracts/inventories/*.yaml` 负责验证控制器操作、功能、Tool、Skill 和遗留决策的完整性及交叉引用。
- 提取行为使用的 V1 源码提交固定为 `fb9621ae`。
- V1 仓库只作为阶段 0 的提取输入，不是 V2 的运行时、构建或测试依赖。

## 本地验证

在 V2 仓库根目录执行聚焦契约测试：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server test
```

执行整个后端 reactor 验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

验证必须能够在仅克隆本仓库、同级目录中不存在 `data-agent` 的环境中通过。
