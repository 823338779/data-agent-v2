package com.datagent.agentv2.server.web;

import com.datagent.agentv2.conversation.exception.ActiveExecutionConflictException;
import com.datagent.agentv2.conversation.exception.IdempotencyKeyReusedException;
import com.datagent.agentv2.git.GitErrorMessages;
import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.http.RequestContext;
import com.datagent.agentv2.memory.MemoryException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 服务端统一异常处理器。
 *
 * <p>将控制器调用过程中抛出的异常转换为符合 RFC 9457 结构的 {@link ProblemDetail} 响应，并在响应中补充业务错误码、请求标识和错误明细。
 */
@RestControllerAdvice
@Slf4j
public final class ApiExceptionHandler {

    private static final String VALIDATION_PROBLEM_TYPE =
            "urn:data-agent:problem:validation-failed";
    private static final String VALIDATION_PROBLEM_TITLE = "Validation Failed";
    private static final String VALIDATION_PROBLEM_CODE = "VALIDATION_FAILED";
    private static final String ERROR_REQUEST_VALIDATION_FAILED = "Request validation failed";
    private static final String CLIENT_DISCONNECTED_MESSAGE =
            "Client disconnected from an asynchronous response";

    /** 开发操作的稳定状态和安全诊断直接沿用领域用例。 */
    @ExceptionHandler(com.datagent.agentv2.development.model.DevelopmentException.class)
    ResponseEntity<ProblemDetail> handleDevelopment(
            com.datagent.agentv2.development.model.DevelopmentException failure,
            HttpServletRequest request) {
        return problem(
                HttpStatus.valueOf(failure.getStatus()),
                DEVELOPMENT_PROBLEM_TYPE,
                DEVELOPMENT_PROBLEM_TITLE,
                failure.getCode(),
                failure.getMessage(),
                request);
    }

    private static final String DEVELOPMENT_PROBLEM_TYPE = "urn:data-agent:problem:development";
    private static final String DEVELOPMENT_PROBLEM_TITLE = "Development operation failed";

    private static final String NOT_FOUND_TYPE = "urn:data-agent:problem:not-found";
    private static final String NOT_FOUND_CODE = "NOT_FOUND";

    /** 退役接口没有MVC映射时保持404，不被通用异常处理器误报为500。 */
    @ExceptionHandler({
        org.springframework.web.servlet.resource.NoResourceFoundException.class,
        org.springframework.web.servlet.NoHandlerFoundException.class
    })
    ResponseEntity<ProblemDetail> handleNotFound(Exception failure, HttpServletRequest request) {
        return problem(
                HttpStatus.NOT_FOUND,
                NOT_FOUND_TYPE,
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                NOT_FOUND_CODE,
                "Requested endpoint does not exist",
                request);
    }

    /** 记忆稳定业务码决定 HTTP 状态；响应仅使用领域层已审定的安全消息。 */
    @ExceptionHandler(MemoryException.class)
    ResponseEntity<ProblemDetail> handleMemory(
            MemoryException exception, HttpServletRequest request) {
        HttpStatus status =
                switch (exception.code()) {
                    case INVALID_REQUEST, UNSAFE_CONTENT -> HttpStatus.BAD_REQUEST;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case CONFLICT,
                            DUPLICATE_CONFLICT,
                            PROTECTED,
                            SUPPRESSED,
                            LEARNING_DISABLED,
                            LEASE_LOST ->
                            HttpStatus.CONFLICT;
                    case QUOTA_EXCEEDED -> HttpStatus.UNPROCESSABLE_CONTENT;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                    case CREATED, ALREADY_EXISTS, UPDATED, REMOVED, CONFIRMED ->
                            HttpStatus.INTERNAL_SERVER_ERROR;
                };
        return problem(
                status,
                "urn:data-agent:problem:memory",
                "Memory operation failed",
                exception.code().name(),
                exception.safeMessage(),
                request);
    }

    /** Git 稳定错误码决定 HTTP 状态，原始命令 stderr 和服务器路径永不进入响应。 */
    @ExceptionHandler(GitException.class)
    ResponseEntity<ProblemDetail> handleGit(GitException exception, HttpServletRequest request) {
        HttpStatus status =
                switch (exception.code()) {
                    case "GIT_CONFLICT",
                            "GIT_CONTENT_CONFLICT",
                            "GIT_BASE_CHANGED",
                            "GIT_NON_FAST_FORWARD",
                            "GIT_WORKSPACE_CORRUPT",
                            "GIT_SNAPSHOT_CORRUPT",
                            "GIT_COMMIT_CORRUPT" ->
                            HttpStatus.CONFLICT;
                    case "GIT_CHANGE_NOT_FOUND",
                            "GIT_WORKSPACE_NOT_FOUND",
                            "GIT_FILE_NOT_FOUND",
                            "GIT_EXECUTION_NOT_FOUND" ->
                            HttpStatus.NOT_FOUND;
                    case "GIT_NOT_CONFIGURED",
                            "GIT_COMMAND_FAILED",
                            "GIT_RESULT_UNKNOWN",
                            "GIT_TASK_REJECTED" ->
                            HttpStatus.SERVICE_UNAVAILABLE;
                    case "GIT_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
                    case "GIT_LIMIT_EXCEEDED", "GIT_FILE_TOO_LARGE", "GIT_OUTPUT_TOO_LARGE" ->
                            HttpStatus.PAYLOAD_TOO_LARGE;
                    case "GIT_INVALID_REQUEST",
                            "GIT_PATH_REJECTED",
                            "GIT_BINARY_FILE",
                            "GIT_EMPTY_CHANGE" ->
                            HttpStatus.BAD_REQUEST;
                    default -> HttpStatus.INTERNAL_SERVER_ERROR;
                };
        return problem(
                status,
                "urn:data-agent:problem:git",
                "Git operation failed",
                exception.code(),
                GitErrorMessages.summary(exception.code()),
                request);
    }

    /** 客户端关闭 SSE 等异步响应后无需再写错误响应。 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleDisconnectedClient(AsyncRequestNotUsableException exception) {
        // 响应已经提交或连接已经关闭，只保留低级别诊断信息。
        log.debug(CLIENT_DISCONNECTED_MESSAGE, exception);
    }

    /**
     * 处理同一会话存在活动执行任务时产生的冲突。
     *
     * @param exception 活动执行冲突异常
     * @param request 当前 HTTP 请求
     * @return HTTP 409 问题详情响应
     */
    @ExceptionHandler(ActiveExecutionConflictException.class)
    ResponseEntity<ProblemDetail> handleActiveExecutionConflict(
            ActiveExecutionConflictException exception, HttpServletRequest request) {
        // 组装活动执行冲突的标准问题详情，并保留请求标识以便调用方排查。
        String requestId = RequestContext.requestId(request);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setType(URI.create("urn:data-agent:problem:active-execution-conflict"));
        problem.setTitle("Active Execution Conflict");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "ACTIVE_EXECUTION_CONFLICT");
        problem.setProperty("requestId", requestId);
        problem.setProperty("errors", List.of());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 处理幂等键被不同请求内容重复使用的冲突。
     *
     * @param exception 幂等键复用异常
     * @param request 当前 HTTP 请求
     * @return HTTP 409 问题详情响应
     */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyKeyReused(
            IdempotencyKeyReusedException exception, HttpServletRequest request) {
        // 通过通用构造方法生成幂等键冲突响应，确保错误结构保持一致。
        return problem(
                HttpStatus.CONFLICT,
                "urn:data-agent:problem:idempotency-key-reused",
                "Idempotency Key Reused",
                "IDEMPOTENCY_KEY_REUSED",
                exception.getMessage(),
                request);
    }

    /**
     * 处理请求缺少必填请求头的校验异常。
     *
     * @param exception 缺少请求头异常
     * @param request 当前 HTTP 请求
     * @return HTTP 400 问题详情响应
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException exception, HttpServletRequest request) {
        // 将 Spring 的请求头校验异常统一转换为参数校验失败响应。
        return problem(
                HttpStatus.BAD_REQUEST,
                VALIDATION_PROBLEM_TYPE,
                VALIDATION_PROBLEM_TITLE,
                VALIDATION_PROBLEM_CODE,
                exception.getMessage(),
                request);
    }

    /**
     * 处理请求体字段违反 Bean Validation 约束的异常。
     *
     * @param exception 请求解析、必填参数或字段校验异常
     * @param request 当前 HTTP 请求
     * @return HTTP 400 问题详情响应
     */
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        IllegalArgumentException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            Exception exception, HttpServletRequest request) {
        // 请求体校验错误统一返回稳定错误码，避免被兜底处理器误报为服务端故障。
        return problem(
                HttpStatus.BAD_REQUEST,
                VALIDATION_PROBLEM_TYPE,
                VALIDATION_PROBLEM_TITLE,
                VALIDATION_PROBLEM_CODE,
                ERROR_REQUEST_VALIDATION_FAILED,
                request);
    }

    /**
     * 处理携带 HTTP 状态的通用 Web 异常。
     *
     * @param exception HTTP 状态异常
     * @param request 当前 HTTP 请求
     * @return 与异常状态一致的问题详情响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> handleResponseStatus(
            ResponseStatusException exception, HttpServletRequest request) {
        // 保留异常指定的 HTTP 状态；未提供原因时使用该状态的标准描述。
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return problem(
                status,
                "urn:data-agent:problem:request-failed",
                status.getReasonPhrase(),
                "REQUEST_FAILED",
                exception.getReason() == null ? status.getReasonPhrase() : exception.getReason(),
                request);
    }

    /**
     * 兜底处理未被其他处理器识别的异常。
     *
     * @param exception 未处理异常
     * @param request 当前 HTTP 请求
     * @return HTTP 500 问题详情响应
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        // 记录完整异常和请求上下文，响应中仅返回安全的通用错误信息。
        String requestId = RequestContext.requestId(request);
        log.error(
                "Unhandled exception for {} {} (requestId={})",
                request.getMethod(),
                request.getRequestURI(),
                requestId,
                com.datagent.agentv2.diagnostics.SafeFailures.sanitized(exception));
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(URI.create("urn:data-agent:problem:internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("requestId", requestId);
        problem.setProperty("errors", List.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * 构造统一的问题详情响应。
     *
     * @param status HTTP 状态
     * @param type 问题类型 URI
     * @param title 问题标题
     * @param code 业务错误码
     * @param detail 错误详情
     * @param request 当前 HTTP 请求
     * @return 包含标准扩展字段的问题详情响应
     */
    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String type,
            String title,
            String code,
            String detail,
            HttpServletRequest request) {
        // 填充公共问题字段和扩展字段，保证各异常处理器返回一致的响应格式。
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("requestId", RequestContext.requestId(request));
        problem.setProperty("errors", List.of());
        return ResponseEntity.status(status).body(problem);
    }
}
