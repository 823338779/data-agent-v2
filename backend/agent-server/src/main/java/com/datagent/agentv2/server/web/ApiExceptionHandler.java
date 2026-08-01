package com.datagent.agentv2.server.web;

import com.datagent.agentv2.conversation.application.ActiveRunConflictException;
import com.datagent.agentv2.server.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public final class ApiExceptionHandler {

    @ExceptionHandler(ActiveRunConflictException.class)
    ResponseEntity<ProblemDetail> handleActiveRunConflict(
            ActiveRunConflictException exception, HttpServletRequest request) {
        String requestId = RequestContext.requestId(request);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setType(URI.create("urn:data-agent:problem:active-run-conflict"));
        problem.setTitle("Active Run Conflict");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "ACTIVE_RUN_CONFLICT");
        problem.setProperty("requestId", requestId);
        problem.setProperty("errors", List.of());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
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
}
