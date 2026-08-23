# Running Conversation Steering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support ChatGPT-style steering by atomically superseding an active answer turn when a new message is sent, while keeping explicit stop in the composer.

**Architecture:** Treat the conversation session as the continuous task and each `AgentExecution` as an answer turn. A `STEER_ACTIVE` submission terminally fences the old turn and creates its successor in one transaction; message-level context eligibility prevents incomplete assistant work from entering raw, LSM, or hierarchical model context.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Flyway, PostgreSQL, React, TypeScript, Vitest, Testing Library

**Spec:** `docs/superpowers/specs/2026-08-23-running-conversation-steering-design.md`

## Global Constraints

- Preserve `REJECT` as the default submission conflict behavior.
- Never allow more than one queued/running/waiting execution per session.
- Keep all user messages in model context; include assistant messages only after execution status `done`.
- Preserve cancelled partial output for UI/audit but never promote it to completed context.
- Do not create commits unless the user asks.

---

### Task 1: Persist context eligibility

**Files:**
- Create: `backend/agent-persistence/src/main/resources/db/migration/V20__add_conversation_message_context_eligibility.sql`
- Modify: `backend/agent-domain/src/main/java/com/datagent/agentv2/conversation/domain/ConversationMessage.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/entity/ConversationMessageEntity.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/mapping/ConversationPersistenceMapper.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/ConversationMessageService.java`
- Test: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/react/ConversationContextLoaderIntegrationTest.java`
- Test: context strategy tests in `backend/agent-context-lsm` and `backend/agent-context-hierarchical`

**Interfaces:**
- Produces: `ConversationMessage.isContextEligible()` and context queries that return every user row plus only eligible assistant rows.

- [ ] **Step 1: Write failing tests** asserting a cancelled assistant is excluded while its triggering user and later steering user remain ordered in raw, LSM, and hierarchical source history.
- [ ] **Step 2: Run the focused context tests** and confirm they fail because incomplete assistant rows are currently returned.
- [ ] **Step 3: Add `context_eligible boolean not null`**, backfill users and assistants whose executions are `done`, and map the field through domain/entity conversion.
- [ ] **Step 4: Initialize users as eligible, placeholders as ineligible, and set eligibility in `completeAssistantContent`; filter only model-context query methods.**
- [ ] **Step 5: Run the focused context and migration tests** and confirm they pass.

### Task 2: Add atomic steering submission

**Files:**
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/controller/ConversationController.java`
- Create: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/SubmissionConflictPolicy.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionSubmissionService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionSubmissionTransactionService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/execution/AgentExecutionControlService.java`
- Modify: `backend/agent-web/src/main/java/com/datagent/agentv2/conversation/process/AgentExecutionProcessTerminationService.java`
- Modify: `backend/agent-persistence/src/main/java/com/datagent/agentv2/persistence/service/AgentExecutionService.java`
- Test: `backend/agent-server/src/test/java/com/datagent/agentv2/conversation/AgentExecutionSubmissionIntegrationTest.java`

**Interfaces:**
- Consumes: message context eligibility from Task 1.
- Produces: request field `conflictPolicy: "REJECT" | "STEER_ACTIVE"`; `submit(..., SubmissionConflictPolicy)`.

- [ ] **Step 1: Write failing integration tests** proving default conflict remains 409, `STEER_ACTIVE` cancels the old run and creates exactly one active successor, replay is side-effect free, and a policy/content mismatch returns the idempotency conflict.
- [ ] **Step 2: Run `AgentExecutionSubmissionIntegrationTest`** and confirm the steering cases fail for missing request behavior.
- [ ] **Step 3: Add the policy contract and include its stable value in the request payload hash.**
- [ ] **Step 4: Add an owner-fenced forced terminal cancellation path** that closes process items, clears ownership, emits cancellation lifecycle/outbox data, and can be invoked inside the submission transaction.
- [ ] **Step 5: In `createOrReplay`, resolve replay first, apply steering cancellation when requested, then create the successor using the existing queued execution path.**
- [ ] **Step 6: Run the submission, control, outbox, and stream integration tests** and confirm they pass.

### Task 3: Put stop/send behavior in the composer

**Files:**
- Modify: `frontend/src/api/backendAgentClient.ts`
- Modify: `frontend/src/api/backendAgentClient.test.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/styles.css`
- Modify: `frontend/src/components/ConversationStatus.tsx`

**Interfaces:**
- Consumes: `conflictPolicy: "STEER_ACTIVE"` from Task 2.
- Produces: `submitMessage(sessionId, content, 'STEER_ACTIVE')`; composer action selected from active status and trimmed draft.

- [ ] **Step 1: Write failing client and UI tests** for the policy payload, active-empty stop button, active-nonempty send button, retained draft after stop, and absent header cancel button.
- [ ] **Step 2: Run the focused Vitest files** and confirm failure for the missing composer behavior.
- [ ] **Step 3: Send `STEER_ACTIVE` from the fetch client and update its typed interface.**
- [ ] **Step 4: Remove header cancellation and render one composer action:** stop when active and empty, send when non-empty, spinner only while submitting.
- [ ] **Step 5: Keep the textarea enabled while processing and preserve its value during explicit cancellation.**
- [ ] **Step 6: Run frontend tests and production build** and confirm both pass.

### Task 4: Cross-layer verification

**Files:**
- Modify only files required to fix failures caused by Tasks 1-3.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: verified feature with no unrelated behavior changes.

- [ ] **Step 1: Run the narrow backend module tests** for persistence, web, context strategies, and server integration.
- [ ] **Step 2: Run the parent Maven verification command** appropriate for all changed backend modules.
- [ ] **Step 3: Run `npm test` and `npm run build` in `frontend`.**
- [ ] **Step 4: Inspect `git diff --check` and `git diff --stat`, then verify every changed line is attributable to the approved design.**
