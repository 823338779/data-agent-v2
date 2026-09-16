alter table personal_memory_operation add column settings_result text;
comment on column personal_memory_operation.settings_result is '设置操作提交时的完整 MemoryState JSON 快照；普通记忆操作为空，不包含个人正文。';
alter table personal_memory_evidence add column prompt_version varchar(64);
comment on column personal_memory_evidence.prompt_version is '首次建立该证据关联时的实际提示词版本；人工路径或未提供版本的历史调用为空，重复证据不覆盖。';
comment on column personal_memory.prompt_version is '首次创建条目时的实际提示词版本；人工路径或历史调用可为空，后续操作版本保存在各自证据关联。';
