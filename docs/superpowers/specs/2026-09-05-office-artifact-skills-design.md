# Office 文档产出 Skill 与本机沙箱设计

> 历史设计：相关 Workflow 或 Office 文件产出、沙箱实现已于 2026-10-02 移除，本文不再代表当前运行时能力。

## 1. 背景与目标

Data Agent V2 当前已经具备 Tool/Skill 分模块、bundled Skill 渐进披露、Skill 读取后原子激活 Tool，以及会话消息和 SSE 持久化投影能力。当前 `agent-skill` 只扫描 `skills/*/SKILL.md`，现有 Skill 也只承担模型指令与 Tool 激活，不具备文件构建、渲染、存储或下载链路。

本设计为系统增加 Word、PowerPoint 和 Excel 三类生产级文档产出能力。最终用户可以在对话中提出文档生成或修订要求，Agent 使用对应 Skill 构造强类型规格，经本机受控 Python 运行时生成、渲染和验证文件；成功产物作为 assistant 消息的结构化附件持久化，并在前端 Markdown 内容中的受控 UI 区域渲染为文件卡片，供用户下载到本地。

本设计遵循以下目标：

- 不新增常驻服务节点、独立端口、服务发现或外部任务队列；
- Linux 与 Windows 分别提供本机沙箱实现，并在应用启动时自动选择；
- Skill 负责工作方法，Tool 负责受控调用，Python 脚本负责确定性文件构建；
- 模型不能提交或执行 Python、Shell、JavaScript 等任意代码；
- DOCX、PPTX、XLSX 必须经过结构检查、渲染或重算以及格式专项 QA 后才能发布；
- 文件存储于本机受控目录，PostgreSQL 只保存元数据、关联关系和完整性摘要；
- 前端使用版本化 UI Block 组件体系，不执行模型输出的任意 HTML、CSS 或 JavaScript；
- 首期支持新建、内置模板和修订本系统已生成的产物，不支持上传或编辑任意用户 Office 文件。

## 2. 非目标

首期明确不实现：

- 用户上传 DOCX、PPTX、XLSX 或其他文件；
- 编辑外部来源或未知版本的 Office 文件；
- 执行模型动态生成的 Python、Shell、JavaScript 或命令行；
- 让模型指定本机路径、脚本路径、容器参数或下载 URL；
- 独立 artifact worker 服务、分布式队列、跨节点任务接管；
- Google Docs、Google Slides、在线 Office 协作或第三方文档 SaaS；
- 用户自定义模板上传、模板市场或在线模板编辑；
- 正式产物的自动到期删除；
- 任意 Markdown 原始 HTML 渲染。

## 3. 核心原则

### 3.1 ArtifactSpec 是权威源

系统将 Office 文件视为由以下输入编译得到的不可变产物：

```text
ArtifactSpec + Template Version + Skill Digest + Script Digest + Runtime Version
```

后续修订更新持久化的 ArtifactSpec 并完整重新生成文件，不直接把 DOCX/PPTX/XLSX 二进制作为可变权威源。这样能够复现历史版本、避免反向解析造成格式漂移，并使修订操作可以在服务端进行强类型校验。

### 3.2 Skill 不等于执行权限

Skill 只向模型提供工作流程、格式规则和 Tool 使用契约。Python 脚本和模板属于运行时资源，模型不可直接读取或执行。读取 Skill 后只能激活服务端明确允许的 `artifact_build` 与 `artifact_inspect` Tool。

### 3.3 失败关闭

启用文档能力后，如果所在操作系统对应的沙箱、Python、LibreOffice、字体或运行资源自检失败，应用启动失败。系统不得自动降级为不受限的 `ProcessBuilder`，也不提供 `unsafe` 或 `none` 沙箱模式。

### 3.4 通过 QA 后才发布

构建中间文件只存在于 staging 目录。只有结构、渲染或重算及格式专项 QA 全部通过，文件才会原子移动到正式目录、登记为 READY 产物并关联消息 UI Block。失败文件不能显示下载卡片。

## 4. 总体架构

新增 Maven 模块 `agent-artifact`，但不新增服务进程或网络节点：

```text
用户请求
  ↓
React 前端 / agent-web
  ↓
ReAct Agent
  ↓ skill_read
Word / Presentation / Spreadsheet Skill
  ↓ 激活
artifact_build / artifact_inspect
  ↓
agent-artifact
  ├─ ArtifactSpec 校验与修订
  ├─ 构建任务生命周期
  ├─ 本机文件存储
  ├─ Linux / Windows 沙箱
  ├─ Python 构建、LibreOffice 渲染或重算
  └─ Manifest 与 QA 验证
  ↓
agent-persistence
  ↓
message_update SSE
  ↓
Markdown 中的 <agent-ui> → React 文件卡片 → REST 下载
```

模块依赖方向：

```text
agent-tool
    ↑
agent-skill
    ↑
agent-artifact
    ↑
agent-web
    ↑
agent-server
```

`agent-persistence` 继续统一承载 Entity、Mapper、MyBatis-Plus `ServiceImpl` 和数据库迁移；`agent-artifact` 只能通过持久化 Service 访问数据，不直接引用 Mapper 或 MyBatis API。

## 5. 模块职责

### 5.1 `agent-skill`

提供三个独立 bundled Skill：

- `word-artifact`：创建或修订 DOCX、Word、报告、方案、说明书等分页文档；
- `presentation-artifact`：创建或修订 PPTX、PowerPoint、汇报或演示文稿；
- `spreadsheet-artifact`：创建或修订 XLSX、Excel、计算模型、数据表及带公式图表的工作簿。

每个 Skill 读取后原子激活：

```text
artifact_build
artifact_inspect
```

### 5.2 `agent-artifact`

负责文档能力的应用编排和运行时边界，主要组件包括：

```text
ArtifactBuildService
├─ ArtifactSpecValidator
├─ ArtifactRevisionService
├─ ArtifactScriptRegistry
├─ ArtifactSandboxRunner
├─ ArtifactManifestValidator
├─ ArtifactQualityGate
├─ ArtifactStorage
└─ ArtifactPublicationService
```

该模块还提供 `ArtifactBuildTool` 和 `ArtifactInspectTool`，并向 `agent-web` 暴露下载所需的只读应用服务。

### 5.3 `agent-web`

负责：

- 文件下载 REST 门面；
- 在 MessageView 中组装 artifacts 和 uiBlocks；
- 通过现有 `message_update` SSE 发布完整消息新版本；
- 前端契约和服务级接口测试。

该模块不直接访问磁盘、不启动 Python，也不实现文档格式逻辑。

### 5.4 `agent-server`

继续作为唯一可执行模块，负责装配 `agent-artifact`、注册全局异常边界以及启动期沙箱自检，不承载文档业务门面。

### 5.5 `agent-evals`

通过公开 HTTP/SSE 契约验证 Skill 是否触发正确 Tool、成功产物是否可下载、失败时是否诚实收敛。它不得成为运行时模块依赖。

## 6. Skill 包设计

### 6.1 目录结构

```text
skills/
├─ word-artifact/
│  ├─ SKILL.md
│  ├─ references/
│  │  ├─ document-spec.md
│  │  ├─ layout-rules.md
│  │  └─ quality-gates.md
│  ├─ scripts/
│  │  ├─ build.py
│  │  ├─ render.py
│  │  └─ verify.py
│  └─ templates/
├─ presentation-artifact/
│  ├─ SKILL.md
│  ├─ references/
│  │  ├─ presentation-spec.md
│  │  ├─ layout-rules.md
│  │  └─ quality-gates.md
│  ├─ scripts/
│  └─ templates/
└─ spreadsheet-artifact/
   ├─ SKILL.md
   ├─ references/
   │  ├─ workbook-spec.md
   │  ├─ formula-rules.md
   │  └─ quality-gates.md
   ├─ scripts/
   └─ templates/
```

三个 Skill 的公共 Python 库放在 `agent-artifact` 的内部 runtime 资源中；Skill 内脚本只保留格式入口和格式专项实现。Skill digest 与 runtime digest 分别计算，并共同写入构建审计信息。

### 6.2 资源可见性

Skill 资源分为两类：

| 类型 | 路径 | 读取方 |
| --- | --- | --- |
| 模型指令 | `SKILL.md`、`references/**/*.md` | `skill_read` |
| 运行资源 | `scripts/**`、`templates/**` | `agent-artifact` 内部运行时 |

所有资源都进入 Skill digest。`skill_read` 必须拒绝脚本、模板二进制和未声明路径。`ClasspathSkillResourceSource` 改为按已经注册的 `SkillDefinition` 加载对应完整资源树，而不是仅扫描所有 `skills/*/SKILL.md`。

当 `data-agent.artifact.enabled=false` 时，三个 Artifact Skill 和两个 Tool 整体不注册；启用时才共同出现。

启用时，`artifact_build` 与 `artifact_inspect` 必须进入 execution 的 maximum Tool 集合，但不能进入 initial 集合。initial 仍只包含 `skill_read` 等基础 Tool；模型读取任一 Artifact Skill 后，两个文档 Tool 才按现有 all-or-none 规则在下一轮同时可见。任一 Tool 未注册或不在 maximum 中都应使启动校验失败。

### 6.3 Skill 共同流程

每个 `SKILL.md` 使用相同的闭环骨架：

1. 判断请求是 CREATE 还是 REVISE；
2. 提取主题、用途、受众、语言和安全文件名；
3. 选择内置模板；
4. 构造格式对应的强类型 ArtifactSpec；
5. 调用 `artifact_build`；
6. 检查结构化 QA 结果；
7. QA 未通过时根据诊断修订 Spec 并在次数上限内重试；
8. 成功后在最终 Markdown 中放置 Tool 返回的 `<agent-ui block-id="...">` 占位符。

共同硬约束：

- 不生成或执行任意代码；
- 不指定脚本、路径、容器或进程参数；
- 不把文件 Base64 内容放入模型上下文；
- 不伪造文件名、大小、页数、下载地址、artifactId、blockId 或 QA 结果；
- QA 失败时不得声称文件已经生成；
- 不暴露临时目录、内部日志和宿主绝对路径；
- 不使用普通 Markdown 链接替代结构化 UI Block；
- 不处理用户上传文件；
- 只能修订当前租户和会话可访问、且保存有原始 ArtifactSpec 的系统产物。

### 6.4 格式专项规则

Word Skill 要求真实标题层级、列表和表格结构，控制分页、页眉页脚和表格宽度，检查空白页、表格越界、断页和缺失字体。

Presentation Skill 要求每页表达一个主要结论，使用内置布局 ID 和密度预算；内容过多时优先缩短或拆页，检查画布溢出、标题换行、空白幻灯片和图表数据一致性。

Spreadsheet Skill 要求原始输入、假设和计算结果分区，推导值使用公式，数值、日期、货币和百分比使用真实类型；完成 LibreOffice 重算后检查公式错误、循环引用、外部链接、列宽和图表数据范围。

## 7. Tool 契约

### 7.1 `artifact_build`

支持 CREATE 和 REVISE，不接受脚本或命令字段。

CREATE 示例：

```json
{
  "artifactType": "pptx",
  "operation": "CREATE",
  "fileName": "季度经营分析.pptx",
  "templateId": "business-review",
  "spec": {}
}
```

REVISE 示例：

```json
{
  "artifactType": "pptx",
  "operation": "REVISE",
  "sourceArtifactId": "artifact_123",
  "expectedVersion": 1,
  "changes": [
    {
      "operation": "replace",
      "targetId": "slide_3",
      "field": "title",
      "value": "下季度行动计划"
    }
  ]
}
```

Tool 在成功时返回 artifactId、blockId、文件摘要及 QA 摘要；失败时返回稳定错误码和可修复的结构化诊断。

### 7.2 `artifact_inspect`

只允许读取系统已生成且当前会话可访问的产物，返回：

- 原始生成规格及当前版本；
- 页面、幻灯片或工作表的语义结构；
- 最近 QA 报告；
- 公式错误、溢出、缺失资源等诊断；
- 可作为 REVISE targetId 的稳定元素 ID。

该 Tool 不返回本机路径、Base64 文件正文、任意二进制、内部命令或未经清洗的进程日志。

## 8. ArtifactSpec 与修订模型

三种规格共享 `schemaVersion`、`title`、`locale`、`templateId` 和安全文件名。格式内容使用封闭的判别联合：

- DocumentSpec：heading、paragraph、list、table、callout、image、pageBreak、sources；
- PresentationSpec：稳定 slideId、内置 layoutId、title、content、chart、image 和 notes；
- WorkbookSpec：稳定 sheetId、tableId、输入区域、公式、格式、图表和冻结窗格。

规格不得携带 Python 表达式、任意脚本、宏、外部工作簿链接或自由文件路径。PPT 首期以布局 ID、有限栅格位置和样式枚举控制版式，不接受自由绘图代码。

修订操作优先引用稳定的 sectionId、slideId、sheetId、tableId 等，不依赖易漂移的数组位置。服务端使用 `expectedVersion` 防止基于旧规格覆盖新版本。每次成功修订产生新的不可变 artifactId；旧消息中的旧产物继续可下载。

## 9. 构建生命周期与原子发布

单独持久化构建任务，状态为：

```text
PREPARED → RUNNING → RENDERING → VERIFYING → SUCCEEDED
              └──────────→ FAILED / CANCELLED / TIMED_OUT
```

正式 ArtifactObject 只在构建成功后发布为 READY。构建步骤：

1. 校验 Tool 请求和 ArtifactSpec；
2. 持久化 PREPARED 任务及规范化 Spec；
3. 创建 `${artifactRoot}/.staging/{jobId}`；
4. 将规范化输入和允许的模板复制到任务输入目录；
5. 在选定平台沙箱中执行预注册 Python 入口；
6. 校验 `manifest.json`、扩展名、MIME、大小、数量和 OOXML 结构；
7. 使用 LibreOffice 渲染 DOCX/PPTX，重算并渲染 XLSX；
8. 执行共同 QA 与格式专项 QA；
9. 计算 SHA-256，将文件原子移动到正式目录；
10. 数据库事务写入产物、消息关联和 UI Block，并增加消息 version；
11. 事务提交后发布 `message_update`。

移动成功但数据库提交失败时，由恢复任务清理孤立正式目录；数据库存在 READY 记录但文件缺失或哈希不一致时，下载接口拒绝返回并产生完整性告警。

用户取消 Agent execution 时同时取消其文档构建并终止完整进程树。已经发布为 READY 的产物不因后续执行取消而删除。应用启动时把遗留的非终态构建标记为失败并清理 staging，首期不跨进程续跑。

## 10. 跨平台本机沙箱

### 10.1 统一 SPI

```java
public interface ArtifactSandboxRunner {
    SandboxPlatform platform();
    SandboxCapability probe();
    ArtifactSandboxResult execute(ArtifactSandboxRequest request);
}
```

`ArtifactSandboxRequest` 是强类型对象，只能包含逻辑脚本 ID、任务输入目录、输出目录、超时和资源策略，不允许自由命令或参数数组。

### 10.2 Linux

首期使用 rootless Podman 一次性容器：

- 禁用网络；
- 根文件系统只读；
- 输入目录只读挂载，输出目录为唯一持久可写目录；
- 使用非 root 用户；
- 限制 CPU、内存、PID、临时目录和执行时间；
- 删除 Linux capabilities，启用 no-new-privileges；
- 任务完成后删除容器。

首期不同时实现 bubblewrap/cgroup 原生后端，避免维护两个 Linux 执行路径。

### 10.3 Windows

使用 Windows 原生受限进程：

- Restricted Token；
- Low Integrity Level；
- Job Object 限制 CPU、内存、进程数和时间；
- 输入目录只读 ACL，输出目录有限写 ACL；
- 防火墙策略或 AppContainer capability 禁止网络；
- 关闭 Job Object 时终止 Python、LibreOffice 及其全部子进程；
- 任务结束清理 ACL、临时目录和临时 profile。

Java 可通过 JNA 调用 Windows API；如果 Token/AppContainer 操作需要固定原生 launcher，该 launcher 与应用一起发布，仅接受结构化固定参数，不监听端口，因此不构成新的服务节点。

### 10.4 自动选择与自检

启动期按规范化操作系统选择唯一 Runner：

```text
Linux   → LinuxArtifactSandboxRunner
Windows → WindowsArtifactSandboxRunner
其他    → 不支持
```

选择后必须自检：

- Python、LibreOffice 和必需字体版本；
- Skill、脚本、模板及 runtime digest；
- 网络隔离、目录隔离和资源限制是否生效；
- 测试 DOCX/PPTX/XLSX 是否可以生成、打开并渲染；
- staging 与正式目录是否支持预期的原子移动语义。

同一应用进程生命周期内不热切换沙箱实现。

## 11. Python 构建运行时

推荐依赖：

- DOCX：python-docx、lxml 和受控 OOXML 辅助函数；
- PPTX：python-pptx、lxml 和内置布局模板；
- XLSX：xlsxwriter 用于创建，openpyxl 用于检查及受控修订；
- 渲染、转换和 XLSX 重算：LibreOffice headless；
- 图片尺寸和基础检查：Pillow；
- PDF/页面检查：PyMuPDF 或部署时固定的 Poppler 工具。

依赖、LibreOffice 和字体版本必须固定在 Linux runtime 镜像或 Windows 发布包中。模型不能控制依赖版本，也不能在任务期间安装包或访问网络。

脚本以规范化 JSON 文件作为输入，只在输出目录写入文件和 Manifest。stdout/stderr 仅用于有界诊断，不能作为成功协议；成功结果必须由经过 Schema 校验的 `manifest.json` 表达。

## 12. 本机存储与持久化

### 12.1 文件目录

```text
${DATA_AGENT_ARTIFACT_ROOT}/
├─ .staging/
│  └─ {jobId}/
└─ {tenantId}/
   └─ {artifactId}/
      ├─ final/
      │  └─ output.{docx|pptx|xlsx}
      ├─ preview/
      │  └─ page-*.png
      └─ manifest.json
```

数据库只保存相对 storage key。用户文件名不得参与真实目录结构，只作为经过清洗的显示和 Content-Disposition 元数据。

### 12.2 数据表

`artifact_build_job` 保存 jobId、tenantId、sessionId、executionId、格式、操作、规范化 Spec、模板/Skill/脚本/runtime digest、平台、状态、阶段时间、失败码和版本。

`artifact_object` 保存 artifactId、tenantId、sessionId、executionId、sourceArtifactId、ArtifactSpec 版本、类型、显示文件名、MIME、storage key、大小、SHA-256、页/幻灯片/工作表数量、READY 状态、创建时间和乐观锁版本。

`conversation_message_artifact` 保存 messageId、artifactId、displayOrder 和创建时间，支持一条 assistant 消息关联多个文件。

`message_ui_block` 保存 blockId、messageId、type、schemaVersion、variant、受控 payload、displayOrder、创建时间和版本。

数据库迁移必须为表及全部业务、审计字段增加中文 comment。

### 12.3 生命周期

正式产物首期跟随会话长期保存，不自动按时间删除。系统只自动清理失败、取消、超时和崩溃遗留的 staging 文件。磁盘低于配置的安全阈值时拒绝新构建，不删除已有正式产物。

## 13. REST、消息与 SSE 契约

### 13.1 MessageView

assistant 消息增加：

```json
{
  "artifacts": [
    {
      "artifactId": "artifact_123",
      "type": "pptx",
      "fileName": "项目季度汇报.pptx",
      "mediaType": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "sizeBytes": 2841032,
      "pageOrSheetCount": 12,
      "status": "READY",
      "downloadUrl": "/api/v1/agent-sessions/session_1/artifacts/artifact_123/content",
      "createdAt": "..."
    }
  ],
  "uiBlocks": [
    {
      "blockId": "block_123",
      "type": "artifact-card",
      "schemaVersion": 1,
      "variant": "compact",
      "payload": {
        "artifactId": "artifact_123"
      }
    }
  ]
}
```

产物发布后复用现有 `message_update` 事件发送完整消息新版本。前端继续按 messageId 与 version 幂等替换，不新增独立 artifact SSE 协议。

### 13.2 下载接口

```http
GET /api/v1/agent-sessions/{conversationSessionId}/artifacts/{artifactId}/content
```

接口必须校验当前固定租户、URL 会话、artifactId 和 READY 状态的一致性；通过 `ArtifactStorage` 读取文件，返回真实 Content-Length、MIME、安全 UTF-8 Content-Disposition 和基于 SHA-256 的 ETag。接口不接受路径参数之外的任何磁盘位置，也不泄露 storage key。

文件缺失、SHA-256 不一致或 OOXML 完整性异常时拒绝下载并记录审计事件。跨会话 artifactId 对调用者表现为不存在。

## 14. Markdown UI Block 协议

### 14.1 占位语法

Markdown 中只允许：

```html
<agent-ui block-id="block_123"></agent-ui>
```

该语法是受控占位协议，不是可执行 HTML。前端不启用 `rehype-raw`，不使用 `dangerouslySetInnerHTML`，也不接受 style、class、onclick、href、src 等任意属性。

### 14.2 结构化数据

占位符只提供 blockId。组件类型、版本、样式变体和 payload 必须来自后端 MessageView 的 `uiBlocks`。artifact 卡片的文件名、MIME、大小、数量和下载地址必须来自同一消息的 `artifacts`，不能相信 Markdown 属性。

前端维护版本化注册表：

```text
artifact-card@1   → ArtifactDownloadCard
metric-summary@1  → MetricSummaryBlock
data-table@1      → DataTableBlock
chart@1           → ChartBlock
```

首期只实现 `artifact-card@1`，注册表为后续不同样式 UI 保留扩展点。交互动作必须调用明确后端 API；payload 不能包含脚本或任意 CSS。

### 14.3 降级与兜底

- Markdown 中引用有效 blockId 时，在对应位置渲染 React 组件；
- 未识别 type/schemaVersion 时显示安全的“不支持此交互内容”提示；
- blockId 不属于当前消息时不得跨消息查找；
- 重复引用同一 blockId 时只渲染一次；
- artifacts 中没有被 Markdown 引用的 READY 文件自动追加到正文底部，避免模型遗漏卡片；
- 失败或非 READY 产物不渲染下载卡片。

文件卡片显示格式图标、文件名、大小以及页数、幻灯片数或工作表数，下载按钮使用普通链接访问后端内容接口，避免前端把整个文件先读入内存。组件必须支持键盘操作和明确的可访问性名称。

## 15. 安全边界

- 模型只能提交强类型 ArtifactSpec 和修订操作；
- 服务端根据 artifactType 与 operation 选择预注册脚本；
- 输入附件功能首期不存在；
- 每个任务使用随机独立目录，拒绝绝对路径、`..`、符号链接和硬链接逃逸；
- Python 子进程不继承数据库、模型、搜索等服务端凭据；
- 网络默认且强制关闭；
- 限制 CPU、内存、PID、时间、输入输出大小、文件数量、页面和工作表数量；
- 输出只接受规定扩展名、MIME 和 OOXML 类型；
- 拒绝宏、外部链接、嵌入对象、ZIP bomb 和异常压缩比；
- 下载再次验证租户、会话、状态、文件存在性和哈希；
- 日志限制长度并清理绝对路径、正文及潜在敏感数据；
- UI Block 不执行任意 HTML、CSS、脚本或未校验 URL。

## 16. 质量门禁

共同检查：文件可重新打开、ZIP/OOXML 结构合法、无宏/外部链接/嵌入对象、大小和数量在限额内、字体存在、LibreOffice 可以处理、最终文件与 Manifest 哈希一致。

DOCX 专项检查空白页、表格越界、异常分页、缺失标题层级、页眉页脚和渲染缺字。

PPTX 专项检查元素超出画布、标题意外换行、文本溢出、空白幻灯片、连接线异常和图表数据不一致。

XLSX 专项检查公式错误、循环引用、外部链接、列宽和行高异常、空白默认工作表、工作表命名、冻结窗格及图表数据范围。LibreOffice 必须完成重算后再扫描错误。

规则可判定的错误由脚本或服务端自动检查；视觉审美首期只能由模板、布局预算和渲染规则近似保证，不宣称已具备通用人类级视觉审稿。未来可以在不改变 Tool 和存储契约的前提下增加可选 VisionQualityPort。

## 17. 配置

```yaml
data-agent:
  artifact:
    enabled: false
    root-directory: ./data/artifacts
    max-concurrent-builds: 1
    max-build-duration: 180s
    max-build-attempts-per-execution: 3
    max-input-bytes: 20MB
    max-output-bytes: 50MB
    min-free-disk-bytes: 1GB
    staging-retention: 24h
    sandbox:
      mode: auto
      linux:
        backend: rootless-podman
        image: data-agent-artifact-runtime:1.0
      windows:
        backend: restricted-process
```

`mode` 正式环境只允许 auto；测试通过独立 test configuration 注入 FakeArtifactSandboxRunner，不把 fake 作为业务配置选项。文档沙箱网络固定禁用，不提供可以在业务配置中打开网络的开关。

## 18. 错误模型

稳定错误码包括：

- `artifact_spec_invalid`；
- `artifact_template_not_found`；
- `artifact_source_not_found`；
- `artifact_version_conflict`；
- `artifact_sandbox_unavailable`；
- `artifact_build_timed_out`；
- `artifact_build_cancelled`；
- `artifact_build_failed`；
- `artifact_render_failed`；
- `artifact_qa_failed`；
- `artifact_storage_full`；
- `artifact_integrity_failed`。

返回模型的诊断只包含 retryable、稳定 location、rule 和清洗后的 message。Python traceback、绝对路径、环境变量、内部命令和未截断 stdout/stderr 不进入 Tool result、SSE 或持久化过程正文。

## 19. 观测与审计

记录 artifactId、jobId、executionId、格式、模板版本、Spec 版本、Skill/脚本/runtime digest、沙箱平台、阶段耗时、输出大小、QA 规则和终态。

不记录文档正文、用户显示文件名、单元格原始数据、完整 Python 输出、本机绝对路径、凭据和环境变量。

指标包括：

```text
artifact_build_total{type,status}
artifact_build_duration_seconds{type,phase}
artifact_build_active
artifact_qa_failure_total{type,rule}
artifact_output_bytes{type}
artifact_download_total{type,status}
artifact_sandbox_probe_success{platform}
```

## 20. 测试与验收

### 20.1 单元测试

- 三种 ArtifactSpec 与修订操作校验；
- expectedVersion 冲突；
- 文件名、storage key 和路径逃逸；
- Manifest、MIME 和 SHA-256；
- 操作系统探测和 Runner 选择；
- UI Block Schema 和前端 Renderer 路由。

### 20.2 持久化测试

- Artifact、消息关联和 UI Block 原子写入；
- 单表 CRUD 使用 MyBatis-Plus 基类；
- READY 正式产物不可变；
- 修订生成新 artifactId，不覆盖旧文件；
- 数据库迁移和中文注释完整。

### 20.3 契约测试

- MessageView 的 artifacts/uiBlocks；
- `message_update` 按 version 替换；
- 下载 MIME、Content-Disposition、长度和 ETag；
- 跨会话 artifactId 不可访问；
- 非 READY 或完整性失败产物不可下载。

### 20.4 沙箱安全测试

- Linux 容器无法联网或读取宿主其他目录；
- Windows 进程无法越过任务 ACL；
- 超时和取消终止完整进程树；
- CPU、内存和进程数限制生效；
- 符号链接、路径逃逸、ZIP bomb 和异常输出被拒绝；
- Python 环境无法获得服务端凭据。

### 20.5 格式集成测试

每种格式使用固定 Spec 生成文件，验证文件可被对应 Python 库重新打开、LibreOffice 可渲染或重算、数量正确、QA 无阻断错误、最终哈希与 Manifest 一致。

### 20.6 前端测试

- `<agent-ui>` 渲染正确注册组件；
- 任意 HTML、脚本和未知属性不执行；
- 未引用 artifact 自动追加；
- 同一 block 不重复；
- 文件卡片显示名称、类型、大小和数量；
- 下载按钮键盘可访问；
- SSE 更新后卡片出现；
- 刷新历史会话后卡片仍存在。

### 20.7 Skill 行为测试

按照 RED-GREEN-REFACTOR 分别验证三个 Skill，不能批量写完后一次验证：

1. 无 Skill 基线，记录模型只输出文字、伪造链接、跳过 QA 或尝试执行代码等失败行为；
2. 加入单个 Skill，验证模型选择正确 Tool、生成合法 Spec、处理 QA 诊断并使用真实 blockId；
3. 使用时间压力、连续 QA 失败、用户要求跳过检查、用户要求执行自定义 Python 等压力场景；
4. 修订 Skill 以关闭发现的漏洞并重新验证；
5. 当前 Skill 完成后才开始下一个 Skill。

## 21. 实施切片

### 切片 1：Artifact 基础设施

完成持久化、本机存储、下载接口、UI Block 协议、前端文件卡片、Sandbox SPI、Linux/Windows 环境探测和测试 Fake Runner。该切片使用固定测试文件验证端到端下载，不提供正式文档生成 Skill。

### 切片 2：Word Skill

完成 DocumentSpec、内置模板、Python Builder、LibreOffice 渲染、DOCX QA、`word-artifact` 和独立 Skill 行为验证。

### 切片 3：Spreadsheet Skill

完成 WorkbookSpec、公式与图表、LibreOffice 重算、XLSX QA、`spreadsheet-artifact` 和独立 Skill 行为验证。

### 切片 4：Presentation Skill

完成 PresentationSpec、布局模板、PPTX 渲染与溢出检测、`presentation-artifact` 和独立 Skill 行为验证。PPT 排版复杂度最高，最后复用已经稳定的沙箱、存储、发布和 UI 链路。

每个切片先运行修改范围对应的 Maven/前端测试，再运行 `agent-server -am` 验证。全部完成后执行后端 `verify`、前端完整测试和生产构建。每个切片使用独立实施计划，避免形成不可评审的单次大改动。

## 22. 完成标准

全部切片完成后，系统应满足：

- Linux 与 Windows 启动时自动选择并自检正确沙箱，不能静默降级；
- 模型读取对应 Skill 后才能看到文档 Tool；
- 用户可以要求创建 DOCX、PPTX、XLSX，并在 QA 通过后看到文件卡片；
- 用户可以基于系统已有产物提出修订，生成新的不可变版本；
- 文件卡片能在 Markdown 指定位置渲染，遗漏时自动追加；
- 用户可以通过卡片按钮下载真实文件，刷新或 SSE 重连后仍可下载；
- 任意代码、路径逃逸、跨会话下载、未经 QA 的文件和不受控 HTML 均被拒绝；
- 取消、超时、崩溃和磁盘空间不足都有稳定失败语义且不发布半成品；
- 三个 Skill 分别通过行为测试，后端、前端、契约、沙箱和格式集成验证全部通过。
