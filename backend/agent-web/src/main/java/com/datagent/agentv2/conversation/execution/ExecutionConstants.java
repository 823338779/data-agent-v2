package com.datagent.agentv2.conversation.execution;

/** 保存执行流程中不属于有限业务取值的错误文案、算法名和标识前缀。 */
public final class ExecutionConstants {

    public static final String ID_PREFIX_EXECUTION_EVENT = "execution_event";
    public static final String ID_PREFIX_EXECUTION = "execution";
    public static final String ID_PREFIX_EXECUTION_STREAM_CHECKPOINT =
            "execution_stream_checkpoint";
    public static final String ID_PREFIX_CONVERSATION_MESSAGE = "conversation_message";
    public static final String ID_PREFIX_NOTIFICATION_MESSAGE = "notification_message";
    public static final String ID_PREFIX_CONVERSATION_SESSION = "conversation_session";
    public static final String REQUEST_ID_UNKNOWN = "request_unknown";
    public static final String EMPTY_TEXT = "";
    public static final String HASH_ALGORITHM_SHA_256 = "SHA-256";
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

    private ExecutionConstants() {}
}
