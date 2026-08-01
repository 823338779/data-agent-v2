package com.datagent.agentv2.conversation;

public final class RunConstants {

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
    public static final String EVENT_RUN_QUEUED = "run_queued";
    public static final String EVENT_RUN_STARTED = "run_started";
    public static final String FAILURE_RUN_DEADLINE_EXCEEDED = "RUN_DEADLINE_EXCEEDED";
    public static final String FAILURE_LEASE_EXPIRED = "LEASE_EXPIRED";
    public static final String RESUME_LEASE_TAKEOVER = "LEASE_TAKEOVER";
    public static final String REQUEST_MESSAGE_SUBMISSION = "message_submission";
    public static final String REQUEST_RUN_RETRY = "run_retry";
    public static final String NOTIFICATION_RUN_CONTROL_REQUESTED = "run_control_requested";
    public static final String AGGREGATE_RUN = "agent_run";
    public static final String CONTROL_CANCEL = "cancel";
    public static final String ID_PREFIX_EVENT = "event";
    public static final String ID_PREFIX_RUN = "run";
    public static final String ID_PREFIX_STREAM = "stream";
    public static final String ID_PREFIX_MESSAGE = "message";
    public static final String ID_PREFIX_NOTIFICATION = "notification";
    public static final String ID_PREFIX_SESSION = "session";
    public static final String REQUEST_ID_UNKNOWN = "request_unknown";
    public static final String EMPTY_TEXT = "";
    public static final String MESSAGE_ROLE_USER = "user";
    public static final String SESSION_TYPE_INTERACTIVE = "INTERACTIVE";
    public static final String HASH_ALGORITHM_SHA_256 = "SHA-256";
    public static final String PAYLOAD_NOTIFICATION_ID = "notificationId";
    public static final String PAYLOAD_TENANT_ID = "tenantId";
    public static final String PAYLOAD_RUN_ID = "runId";
    public static final String PAYLOAD_OWNER_INSTANCE_ID = "ownerInstanceId";
    public static final String PAYLOAD_EXECUTION_ATTEMPT = "executionAttempt";
    public static final String PAYLOAD_CONTROL_VERSION = "controlVersion";
    public static final String PAYLOAD_REQUESTED_AT = "requestedAt";
    public static final String PAYLOAD_TYPE = "type";
    public static final String ERROR_IDEMPOTENCY_KEY_BLANK = "Idempotency-Key must not be blank";
    public static final String ERROR_IDEMPOTENCY_KEY_REUSED =
            "Idempotency-Key was already used for another payload";
    public static final String ERROR_ACTIVE_RUN_CONFLICT = "Session already has an active run";
    public static final String ERROR_AGENT_SESSION_NOT_FOUND = "agent session not found";
    public static final String ERROR_AGENT_RUN_NOT_FOUND = "agent run not found";
    public static final String ERROR_RUN_CANNOT_BE_CANCELLED = "run cannot be cancelled";
    public static final String ERROR_ONLY_TERMINAL_RUNS_RETRY = "only terminal runs can be retried";
    public static final String ERROR_SESSION_CHANGED_CONCURRENTLY = "session changed concurrently";
    public static final String ERROR_RUN_ALREADY_OWNED_PREFIX = "run already owned locally: ";
    public static final String ERROR_RUN_OWNERSHIP_LOST_PREFIX = "run ownership was lost: ";
    public static final String ERROR_RUN_BUDGET_EXHAUSTED_PREFIX =
            "run execution budget was exhausted: ";
    public static final String ERROR_HASH_ALGORITHM_UNAVAILABLE_SUFFIX = " is unavailable";
    public static final String DEFAULT_CONVERSATION_TITLE = "New conversation";
    public static final String EVENT_TITLE_RUN_QUEUED = "Run queued";
    public static final String EVENT_TITLE_RUN_STARTED = "Run started";
    public static final String EVENT_DETAIL_RUN_QUEUED = "Waiting for execution capacity";
    public static final String EVENT_DETAIL_RUN_STARTED = "Preparing the model request";
    public static final String WINDOWS_LINE_SEPARATOR = "\r\n";
    public static final String LINE_SEPARATOR = "\n";
    public static final String INSTANCE_ID_PREFIX = "instance_";

    private RunConstants() {}
}
