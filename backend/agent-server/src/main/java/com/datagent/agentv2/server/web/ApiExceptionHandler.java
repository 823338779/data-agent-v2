package com.datagent.agentv2.server.web;

import com.datagent.agentv2.conversation.activity.SessionOrderConflictException;
import com.datagent.agentv2.conversation.execution.ActiveExecutionConflictException;
import com.datagent.agentv2.conversation.execution.IdempotencyKeyReusedException;
import com.datagent.agentv2.server.context.RequestContext;
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
     * 处理会话消息顺序与服务端状态不一致的冲突。
     *
     * @param exception 会话顺序冲突异常
     * @param request 当前 HTTP 请求
     * @return HTTP 409 问题详情响应
     */
    @ExceptionHandler(SessionOrderConflictException.class)
    ResponseEntity<ProblemDetail> handleSessionOrderConflict(
            SessionOrderConflictException exception, HttpServletRequest request) {
        // 将会话顺序冲突转换为调用方可识别的固定业务错误码。
        return problem(
                HttpStatus.CONFLICT,
                "urn:data-agent:problem:session-order-conflict",
                "Session Order Conflict",
                "SESSION_ORDER_CONFLICT",
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
     * @param exception 请求体字段校验异常
     * @param request 当前 HTTP 请求
     * @return HTTP 400 问题详情响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
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
                exception);
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
