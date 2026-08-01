package com.datagent.agentv2.conversation;

/** 统一保存对话执行协议中跨模块共享的稳定状态、事件类型、错误和标识前缀。 */
public final class AgentExecutionConstants {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_LOST = "lost";
    public static final String STATUS_WAITING_FOR_USER = "waiting_for_user";
    public static final String STATUS_WAITING_CONFIRMATION = "waiting_confirmation";
    public static final String PHASE_QUEUED = "QUEUED";
    public static final String PHASE_RECEIVED = "RECEIVED";
    public static final String PHASE_PREPARING_CONTEXT = "PREPARING_CONTEXT";
    public static final String PHASE_CONNECTING_MODEL = "CONNECTING_MODEL";
    public static final String PHASE_THINKING = "THINKING";
    public static final String PHASE_ANSWERING = "ANSWERING";
    public static final String PHASE_PERSISTING = "PERSISTING";
    public static final String PHASE_COMPLETED = "COMPLETED";
    public static final String EVENT_EXECUTION_QUEUED = "execution_queued";
    public static final String EVENT_EXECUTION_STARTED = "execution_started";
    public static final String EVENT_EXECUTION_DONE = "execution_done";
    public static final String FAILURE_EXECUTION_DEADLINE_EXCEEDED = "EXECUTION_DEADLINE_EXCEEDED";
    public static final String FAILURE_LEASE_EXPIRED = "LEASE_EXPIRED";
    public static final String FAILURE_RECOVERY_EXHAUSTED = "RECOVERY_EXHAUSTED";
    public static final String RESUME_LEASE_TAKEOVER = "LEASE_TAKEOVER";
    public static final String REQUEST_MESSAGE_SUBMISSION = "message_submission";
    public static final String REQUEST_EXECUTION_RETRY = "execution_retry";
    public static final String NOTIFICATION_EXECUTION_CANCELLATION_REQUESTED =
            "execution_cancellation_requested";
    public static final String AGGREGATE_EXECUTION = "agent_execution";
    public static final String CONTROL_CANCEL = "cancel";
    public static final String ID_PREFIX_EXECUTION_EVENT = "execution_event";
    public static final String ID_PREFIX_EXECUTION = "execution";
    public static final String ID_PREFIX_EXECUTION_STREAM_CHECKPOINT =
            "execution_stream_checkpoint";
    public static final String ID_PREFIX_CONVERSATION_MESSAGE = "conversation_message";
    public static final String ID_PREFIX_NOTIFICATION_MESSAGE = "notification_message";
    public static final String ID_PREFIX_CONVERSATION_SESSION = "conversation_session";
    public static final String REQUEST_ID_UNKNOWN = "request_unknown";
    public static final String EMPTY_TEXT = "";
    public static final String MESSAGE_ROLE_USER = "user";
    public static final String MESSAGE_ROLE_ASSISTANT = "assistant";
    public static final String MESSAGE_VISIBILITY_USER = "user";
    public static final String MESSAGE_VISIBILITY_INTERNAL = "internal";
    public static final String SESSION_TYPE_INTERACTIVE = "INTERACTIVE";
    public static final String HASH_ALGORITHM_SHA_256 = "SHA-256";
    public static final String PAYLOAD_NOTIFICATION_MESSAGE_ID = "notificationMessageId";
    public static final String PAYLOAD_TENANT_ID = "tenantId";
    public static final String PAYLOAD_EXECUTION_ID = "executionId";
    public static final String PAYLOAD_OWNER_INSTANCE_ID = "ownerInstanceId";
    public static final String PAYLOAD_EXECUTION_ATTEMPT_NUMBER = "executionAttemptNumber";
    public static final String PAYLOAD_CONTROL_VERSION = "controlVersion";
    public static final String PAYLOAD_REQUESTED_AT = "requestedAt";
    public static final String PAYLOAD_TYPE = "type";
    public static final String ERROR_IDEMPOTENCY_KEY_BLANK = "Idempotency-Key must not be blank";
    public static final String ERROR_IDEMPOTENCY_KEY_REUSED =
            "Idempotency-Key was already used for another payload";
    public static final String ERROR_ACTIVE_EXECUTION_CONFLICT =
            "Conversation session already has an active Agent execution";
    public static final String ERROR_CONVERSATION_SESSION_NOT_FOUND =
            "conversation session not found";
    public static final String ERROR_AGENT_EXECUTION_NOT_FOUND = "Agent execution not found";
    public static final String ERROR_EXECUTION_CANNOT_BE_CANCELLED =
            "Agent execution cannot be cancelled";
    public static final String ERROR_ONLY_TERMINAL_EXECUTIONS_RETRY =
            "only terminal Agent executions can be retried";
    public static final String ERROR_SESSION_CHANGED_CONCURRENTLY = "session changed concurrently";
    public static final String ERROR_EXECUTION_ALREADY_OWNED_PREFIX =
            "Agent execution already owned locally: ";
    public static final String ERROR_EXECUTION_OWNERSHIP_LOST_PREFIX =
            "Agent execution ownership was lost: ";
    public static final String ERROR_EXECUTION_BUDGET_EXHAUSTED_PREFIX =
            "Agent execution budget was exhausted: ";
    public static final String ERROR_HASH_ALGORITHM_UNAVAILABLE_SUFFIX = " is unavailable";
    public static final String DEFAULT_CONVERSATION_TITLE = "New conversation";
    public static final String EVENT_TITLE_EXECUTION_QUEUED = "Agent execution queued";
    public static final String EVENT_TITLE_EXECUTION_STARTED = "Agent execution started";
    public static final String EVENT_DETAIL_EXECUTION_QUEUED = "Waiting for execution capacity";
    public static final String EVENT_DETAIL_EXECUTION_STARTED = "Preparing the model request";
    public static final String WINDOWS_LINE_SEPARATOR = "\r\n";
    public static final String LINE_SEPARATOR = "\n";
    public static final String INSTANCE_ID_PREFIX = "instance_";

    private AgentExecutionConstants() {}
}
