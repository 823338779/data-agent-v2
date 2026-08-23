# LongMemEval Cleaned

本目录用于存放评测使用的三份官方 LongMemEval Cleaned 数据：

- `longmemeval_s_cleaned.json`
- `longmemeval_m_cleaned.json`
- `longmemeval_oracle.json`

官方来源：`xiaowu0162/longmemeval-cleaned`，固定 revision
`98d7416c24c778c2fee6e6f3006e7a073259d48f`：

- 数据集：https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned
- 官方项目：https://github.com/xiaowu0162/LongMemEval

在仓库根目录运行统一下载脚本即可恢复。脚本保存官方 `.json` 原文件：

```powershell
.\backend\agent-evals\scripts\download-eval-datasets.ps1
```

加载器也支持 `.json.gz`。本地若保留 gzip 版本、`*_case-index.json` 或
`*_preview-3.json` 等派生文件，它们都属于可重建数据并被 Git 忽略。
