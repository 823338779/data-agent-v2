package com.datagent.agentv2.server.web;

import com.datagent.agentv2.conversation.activity.SessionOrderConflictException;
import com.datagent.agentv2.conversation.application.ActiveExecutionConflictException;
import com.datagent.agentv2.conversation.application.IdempotencyKeyReusedException;
import com.datagent.agentv2.server.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@Slf4j
public final class ApiExceptionHandler {

    @ExceptionHandler(ActiveExecutionConflictException.class)
    ResponseEntity<ProblemDetail> handleActiveExecutionConflict(
            ActiveExecutionConflictException exception, HttpServletRequest request) {
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

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyKeyReused(
            IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "urn:data-agent:problem:idempotency-key-reused",
                "Idempotency Key Reused",
                "IDEMPOTENCY_KEY_REUSED",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(SessionOrderConflictException.class)
    ResponseEntity<ProblemDetail> handleSessionOrderConflict(
            SessionOrderConflictException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "urn:data-agent:problem:session-order-conflict",
                "Session Order Conflict",
                "SESSION_ORDER_CONFLICT",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "urn:data-agent:problem:validation-failed",
                "Validation Failed",
                "VALIDATION_FAILED",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> handleResponseStatus(
            ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return problem(
                status,
                "urn:data-agent:problem:request-failed",
                status.getReasonPhrase(),
                "REQUEST_FAILED",
                exception.getReason() == null ? status.getReasonPhrase() : exception.getReason(),
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception, HttpServletRequest request) {
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

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String type,
            String title,
            String code,
            String detail,
            HttpServletRequest request) {
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
