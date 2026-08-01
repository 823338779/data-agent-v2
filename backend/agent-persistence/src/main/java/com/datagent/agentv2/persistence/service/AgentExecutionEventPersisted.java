package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.entity.AgentExecutionEventEntity;

/** 通知同进程投递适配器：一个 Agent 执行事件已经成功持久化。 */
public record AgentExecutionEventPersisted(
        /** Persisted event whose identifiers and audit fields have already been populated. */
        AgentExecutionEventEntity event) {}
