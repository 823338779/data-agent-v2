package com.datagent.agentv2.persistence.service;

import com.datagent.agentv2.persistence.entity.AgentRunEventEntity;

/** Announces a successfully persisted Run event to same-process delivery adapters. */
public record AgentRunEventSaved(
        /** Persisted event whose identifiers and audit fields have already been populated. */
        AgentRunEventEntity event) {}
