---
license: mit
configs:
- config_name: multi_needle_reasoning_needle
  data_files:
  - split: test
    path:
    - multi_needle_reasoning_zh.json
    - multi_needle_reasoning_en.json
- config_name: zh_haystack_texts
  data_files:
  - split: test
    path:
    - zh_finance.jsonl
    - zh_game.jsonl
    - zh_general.jsonl
    - zh_government.jsonl
    - zh_movie.jsonl
    - zh_tech.jsonl
- config_name: en_haystack_texts
  data_files:
  - split: test
    path:
    - PaulGrahamEssays.jsonl
- config_name: atc_needles
  data_files:
  - split: test
    path:
    - names.json
- config_name: retrieval_needles
  data_files:
  - split: test
    path:
    - needles.jsonl
task_categories:
- question-answering
---

# 本地获取说明

本目录使用 Hugging Face 官方数据集 `opencompass/NeedleBench`，固定 revision
`9f76ad26e7699d914722a41924171ee1022c426f`。运行
`backend/agent-evals/scripts/download-eval-datasets.ps1` 可重新下载中文多针推理题和六类中文
haystack；下载文件均被 Git 忽略。本文件保留自官方数据集卡，便于核对许可、任务定义和引用。

# Dataset Description

## Dataset Summary

The NeedleBench dataset is a part of the OpenCompass project, designed to evaluate the capabilities of large language models (LLMs) in processing and understanding long documents. It includes a series of test scenarios that assess models' abilities in long text information extraction and reasoning. The dataset is structured to support tasks such as single-needle retrieval, multi-needle retrieval, multi-needle reasoning, and ancestral trace challenges.

<div style="text-align: center;">
    <img src="https://github.com/user-attachments/assets/b895e0cf-4307-47d8-8e5a-9a4d1c58fa37" alt="Needlebench Overview" width="900" style="margin: auto;">
</div>

## Supported Tasks and Primary Languages

- **Single-Needle Retrieval Task (S-RT)**: Extracting a single key piece of information from a long text.
- **Multi-Needle Retrieval Task (M-RT)**: Retrieving multiple related pieces of information from long texts.
- **Multi-Needle Reasoning Task (M-RS)**: Extracting and utilizing multiple key pieces of information for comprehensive understanding.
- **Ancestral Trace Challenge (ATC)**: Handling multi-layer logical challenges in real long texts.

The dataset supports multiple languages, including English and Chinese, as indicated by the presence of files like `multi_needle_reasoning_en.json` and `multi_needle_reasoning_zh.json`.

## Potential Use Cases

The NeedleBench dataset can be used to evaluate and compare the performance of different large language models in tasks involving long text processing, information extraction, and reasoning. It is useful for researchers and developers working on models that need to handle complex queries on extensive documents.

## Evaluation

Please follow the provided guidelines in the [OpenCompass documentation](https://opencompass.readthedocs.io/en/latest/advanced_guides/needleinahaystack_eval.html) to set up the environment, configure the dataset, and run evaluations.

## Additional Information

For more details on the dataset, please refer to the [NeedleBench Technical Report](https://arxiv.org/abs/2407.11963).

Github repository: https://github.com/open-compass/opencompass

## Contact

For any questions or issues related to the dataset, please contact the maintainers or contributors of the [OpenCompass project](https://github.com/open-compass/opencompass).

## Citation

If you use this dataset, please add a reference:

```
@misc{li2024needlebenchllmsretrievalreasoning,
      title={NeedleBench: Can LLMs Do Retrieval and Reasoning in 1 Million Context Window?},
      author={Mo Li and Songyang Zhang and Yunxin Liu and Kai Chen},
      year={2024},
      eprint={2407.11963},
      archivePrefix={arXiv},
      primaryClass={cs.CL},
      url={https://arxiv.org/abs/2407.11963},
}
```
