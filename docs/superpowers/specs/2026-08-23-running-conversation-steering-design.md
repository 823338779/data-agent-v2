# Running Conversation Steering Design

## Goal

Allow a user to send a new message while the current answer is processing. The new message steers the same conversation, while the composer button remains an explicit stop control only when the input is empty.

## User experience

- While an execution is queued or running and the composer is empty, the composer action is a stop button.
- As soon as the composer contains non-blank text, the same action becomes send and remains enabled (except while that HTTP submission itself is pending).
- Sending during processing immediately appends the new user message, terminates the obsolete active turn, and queues a successor turn in one server transaction.
- Explicit stop never submits the draft. It retains the draft text and uses the existing cancellation endpoint.
- The top-right `取消运行` control is removed; status and retry remain there.
- The already streamed partial answer remains available for display/audit on the cancelled turn, but it is not treated as a completed assistant answer.

## Server semantics

The product-level task is the conversation session. In this codebase an `AgentExecution` is a single answer turn, so steering is represented as an atomic terminal transition of the old turn followed by creation of one queued successor turn. There is never more than one active execution for the session, preserving `uq_agent_run_one_active` and existing SSE assumptions.

`POST /api/v1/agent-sessions/{id}/messages` accepts `conflictPolicy`:

- `REJECT` (default): retain the existing 409 behavior for existing callers.
- `STEER_ACTIVE`: if an active execution exists, fence and cancel it before persisting the new user message and successor execution.

The transaction order is:

1. Lock the conversation session.
2. Resolve idempotent replay before changing the active execution.
3. Lock the active execution, if any.
4. Close open process items, transition the old execution to `cancelled`, release ownership/lease, write its cancellation event, and enqueue a cross-instance cancellation signal.
5. Allocate two message sequences and one execution-event sequence.
6. Persist the new user message, queued successor execution, assistant placeholder, queued event, and initial stream checkpoint.

Any failure rolls back both cancellation and successor submission. A retry with the same idempotency key replays the successor and must not cancel it.

## Context boundary

- User messages, including the prompt from a cancelled turn and every steering message, remain eligible for later context.
- Assistant messages become context-eligible only when their execution reaches `done`.
- Cancelled, failed, queued, and running assistant placeholders, partial answers, thinking nodes, and tool process data are excluded from future model context.
- User-visible history still contains those assistant records for display and audit.
- Raw, LSM, and hierarchical context strategies must share this eligibility rule.

The persistence model stores a `context_eligible` flag on `conversation_message`. Existing user messages and completed assistant messages migrate to `true`; all other assistant messages migrate to `false`. `completeAssistantContent` promotes the assistant message to eligible in the same completion transaction.

## Streaming and concurrency

The old worker is fenced by the terminal database transition. Its later checkpoint or completion writes fail ownership checks. A cancellation outbox signal interrupts local or remote provider work promptly, but correctness does not depend on delivery because database fencing is authoritative.

The response and subsequent session SSE projection expose the successor as the current execution. The cancelled assistant remains associated with the old execution and may show its last checkpoint without becoming the current streaming row.

## Compatibility and errors

- Missing `conflictPolicy` behaves exactly as before (`REJECT`).
- `STEER_ACTIVE` with no active execution behaves like a normal submission.
- Unknown policy values fail request validation with HTTP 400.
- Payload hashing includes the message content and conflict policy so an idempotency key cannot be replayed with different steering semantics.
- Explicit cancellation retains the current endpoint and behavior.

## Verification

- Integration tests prove atomic steering, idempotent replay, one-active-execution invariant, old-worker fencing, and context eligibility.
- Context tests cover raw, LSM, and hierarchical loaders.
- Frontend tests prove empty-active composer stops, non-empty-active composer sends, drafts survive explicit stop, and the header cancel control is absent.
- Frontend unit tests, production build, and the Maven modules matching the change all pass.
