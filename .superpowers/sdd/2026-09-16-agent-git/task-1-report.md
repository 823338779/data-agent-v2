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

GREEN 使用同一模块命令：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。格式验证 `backend/mvnw.cmd -f backend/pom.xml -B -pl agent-git -am spotless:check` 通过，12 个 Java 文件保持 clean。

## 自审与限制

- 真实临时裸仓库验证了初始化 main、clone、冻结前 `HEAD` 不变、旧/新 tree 内容、确定性 commit、push、非快进拒绝以及远端出现后续提交时的祖先核实。
- 文件测试验证增改删、搜索/列表、摘要冲突、路径边界、链接、二进制、单文件和单批数量上限。
- 该层只提供 Git/文件基础，不实现确认授权、候选持久化、会话锁和状态机；这些由后续任务编排。
- Windows 创建符号链接可能受系统权限限制；测试在系统明确拒绝创建链接时跳过该断言，生产代码仍使用 `NOFOLLOW_LINKS` 和逐级父目录检查拒绝链接。
