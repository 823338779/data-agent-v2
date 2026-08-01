# Data Agent V2

Data Agent V2 是独立版本化的重建仓库。当前包含后端 Maven 多模块工程、REST/SSE 契约、完整性清单以及后续前端迁移目录；旧 `data-agent` 仅作为已冻结行为的提取来源，不是本仓库的运行时或测试依赖。

## 目录

- `backend/agent-server`：服务端与契约测试
- `backend/agent-evals`：独立评测模块
- `contracts`：REST、SSE、示例与完整性清单
- `docs`：仓库内设计和验证说明
- `frontend`：阶段 1 迁移的前端快照

## 验证

在仓库根目录执行：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml -B -pl agent-server test
.\backend\mvnw.cmd -f .\backend\pom.xml -B verify
```

契约来源、权威边界和独立验证要求见 [docs/contracts.md](docs/contracts.md)。
