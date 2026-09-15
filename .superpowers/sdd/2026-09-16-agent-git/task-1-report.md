# Task 1 实施报告：Git CLI、文件边界与冻结快照

## 完成内容

- 新增独立 `agent-git` Maven 模块，并仅在 `backend/pom.xml` 增加模块声明。
- 使用固定配置和参数列表调用 Git；禁用交互、系统/全局配置、签名、本地 hook、自动换行转换和外部协议，并限制执行时间、输出大小及错误内容。
- 使用独立临时 index 生成不可变 tree，冻结过程只写对象库，不创建 commit、不 checkout、不移动 `HEAD`。
- 文件 API 拒绝绝对路径、穿越、`.git`、符号链接、非普通文件、二进制和超限内容；新增检查不存在，修改/删除检查 SHA-256。
- commit 由固定 tree、parent、message、作者和 UTC 时间生成；push 固定更新 `main`，在推送前检查远端基线且不使用强推。

## Task 2 可调用的精确 public API

```java
new GitRepositoryClient(GitProperties properties)
void cloneRepository(Path workspace)
String fetchMain(Path workspace)
String head(Path workspace)
GitSnapshot freeze(Path workspace)
GitSnapshot freeze(Path workspace, String commitMessage)
String createCommit(Path workspace, String tree, String parent, String message,
                    String authorName, String authorEmail, Instant time)
void pushMain(Path workspace, String commit, String expectedParent)
boolean isAncestorOfRemoteMain(Path workspace, String commit)
String readTreeFile(Path workspace, String treeish, String path)

new GitWorkspaceFiles(GitProperties properties)
List<String> list(Path root, String relativeDirectory)
FileReadResult read(Path root, String relativePath)
List<FileSearchMatch> search(Path root, String relativeDirectory, String query)
void apply(Path root, List<FileOperation> operations)

FileOperation.create(String path, String content)
FileOperation.update(String path, String expectedSha256, String content)
FileOperation.delete(String path, String expectedSha256)
```

公开值类型为 `GitProperties`、`GitSnapshot`、`GitFileChange`、`FileOperation`、`FileReadResult`、`FileSearchMatch`、`GitException`。稳定异常统一通过 `GitException.code()` 返回错误码。

## TDD 证据

RED 命令：

```text
backend/mvnw.cmd -f backend/pom.xml -B -pl agent-git -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GitRepositoryClientTest,GitWorkspaceFilesTest test
```

首次有效 RED 在测试编译阶段失败，原因是 `GitRepositoryClient`、`GitProperties`、`GitWorkspaceFiles` 和 model API 尚不存在。输出上限补充周期先得到 `expected GIT_OUTPUT_TOO_LARGE but was GIT_COMMAND_FAILED`，随后修正为持续排空子进程管道、保留有界内容并返回稳定错误码。

首次交付的最终 GREEN 使用同一模块命令：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。格式验证 `backend/mvnw.cmd -f backend/pom.xml -B -pl agent-git -am spotless:check` 通过，12 个 Java 文件保持 clean。

## 自审与限制

- 真实临时裸仓库验证了初始化 main、clone、冻结前 `HEAD` 不变、旧/新 tree 内容、确定性 commit、push、非快进拒绝以及远端出现后续提交时的祖先核实。
- 文件测试验证增改删、搜索/列表、摘要冲突、路径边界、链接、二进制、单文件和单批数量上限。
- 该层只提供 Git/文件基础，不实现确认授权、候选持久化、会话锁和状态机；这些由后续任务编排。
- Windows 创建符号链接可能受系统权限限制；测试在系统明确拒绝创建链接时跳过该断言，生产代码仍使用 `NOFOLLOW_LINKS` 和逐级父目录检查拒绝链接。

## Fix round 1（独立评审整改）

评审基线为 `0ffe93f`。新增回归测试后，首次 RED 共运行 12 个测试并得到 3 个明确失败：中文路径未按原名返回、Git 中断返回了错误的稳定码、正确摘要可覆盖二进制文件；其余新限额测试也开始约束忽略文件和候选范围。换用 NUL 路径协议的首轮实现还暴露了 `PatternSyntaxException: Illegal octal escape sequence`，据此改为按真实 NUL 字符拆分。

整改内容：

- 冻结时扫描工作区 UTF-8 普通文件，以 `hash-object --no-filters` 按原始字节写 blob，再用临时 index 构建 tree；保留基线文件 mode，拒绝链接、嵌套 `.git` 和子模块。
- `diff-tree --name-status -z` 与 `ls-tree -z` 使用机器格式处理中文和特殊路径；tree 元数据查询失败返回 `GIT_SNAPSHOT_FAILED`，不再按大小 0 继续。
- Git runner 默认拒绝协议，只允许 `file/http/https/ssh`，并清除可覆盖仓库、对象库、传输 helper、diff 和动态配置的继承环境变量。
- 超时、中断和异常统一终止 Git 进程树并取消输出读取；只有真实 `InterruptedException` 恢复线程中断标志并返回 `GIT_INTERRUPTED`。
- 更新和删除在摘要比较前验证旧文件确为受支持的 UTF-8 非 NUL 文本。
- 新增真实 CRLF + `.gitattributes`、中文文件名、gitignore 文件、三类候选上限、二进制更新/删除，以及中断返回码与调用线程退出回归。生产实现负责终止已发现的 Git 子进程树；测试没有把 OS 进程枚举结构固化为契约。

最终 GREEN 命令：

```text
backend/mvnw.cmd -f backend/pom.xml -B -pl agent-git -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GitRepositoryClientTest,GitWorkspaceFilesTest test
```

输出摘要：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。随后运行 `backend/mvnw.cmd -f backend/pom.xml -B -pl agent-git -am spotless:check`。

## Fix round 2（复审整改）

评审基线为 `13e3c69`。focused RED 首先在测试编译阶段确认 `removeGitOverrides` 尚不可验证；原实现运行“基线包含 PNG 和 600 KiB 文件、只修改 YAML”场景也会因逐文件读取而拒绝冻结。

整改后，runner 同时移除 `GIT_CONFIG_PARAMETERS`，关闭通过该环境变量注入 `core.sshCommand` 等动态配置的入口。冻结从 `HEAD` 填充临时 index，使用未过滤 raw blob ID 判断文件是否实际变化：未修改条目原样保留；新增/修改文本才执行 UTF-8、NUL 和大小验证并写入 raw blob；删除条目读取基线 blob，拒绝二进制或超限删除。因此仓库已有的大文件、PNG 不会阻塞无关文本维护，删除不支持文件也不会绕过边界。

focused GREEN 命令：

```text
backend/mvnw.cmd -f backend/pom.xml -B -pl agent-git -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GitRepositoryClientTest#inheritedConfigParametersCannotInjectGitConfiguration+unchangedBinaryAndOversizedBaselineFilesDoNotBlockTextSnapshot+deletingBinaryBaselineFileIsRejected test
```

focused 输出：`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`。完整 Task 1 GREEN 继续使用前述两个测试类命令，输出为 `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
