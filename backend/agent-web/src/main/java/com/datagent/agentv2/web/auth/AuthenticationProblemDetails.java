package com.datagent.agentv2.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class AuthenticationProblemDetails {

    private AuthenticationProblemDetails() {}

    public static ProblemDetail required(HttpServletRequest request, HttpServletResponse response) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNAUTHORIZED, "Authentication is required");
        problem.setType(URI.create("urn:data-agent:problem:authentication-required"));
        problem.setTitle("Unauthorized");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "AUTHENTICATION_REQUIRED");
        String requestId = response.getHeader("X-Request-Id");
        problem.setProperty(
                "requestId",
                requestId == null || requestId.isBlank() ? "request_unknown" : requestId);
        problem.setProperty("errors", List.of());
        return problem;
    }
}
