# 评测数据目录

每套官方评测数据使用独立子目录，避免来源、格式和更新方式相互混淆：

- `longmemeval/`：LongMemEval cleaned S、M 和 Oracle；
- `clongeval-lcvmem/`：CLongEval Long Conversation Memory 的 small、medium 和 large；
- `needlebench-zh/`：NeedleBench 中文推理链及六类中文 haystack。

原始数据以及本地生成的预览、索引等派生文件均被 Git 忽略，仓库只保留来源说明和下载脚本。
Maven 不会将本目录复制到 `target/test-classes`，加载器通过显式文件或目录路径直接读取。
三套数据可使用以下命令按固定官方 revision 重新下载：

```powershell
.\backend\agent-evals\scripts\download-eval-datasets.ps1 -Force
```

脚本下载的是官方原始文件。LongMemEval 加载器同时支持 `.json` 和 `.json.gz`；如需节省本地
磁盘空间，可以自行将下载后的 JSON gzip 压缩，压缩文件同样不会被 Git 跟踪。
