package com.datagent.agentv2.platform.web;

import com.datagent.agentv2.platform.context.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(URI.create("urn:data-agent:problem:internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("requestId", RequestContext.requestId(request));
        problem.setProperty("errors", List.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
