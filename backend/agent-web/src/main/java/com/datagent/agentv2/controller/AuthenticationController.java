package com.datagent.agentv2.controller;

import com.datagent.agentv2.auth.application.AuthenticationService;
import com.datagent.agentv2.auth.config.AuthProperties;
import com.datagent.agentv2.auth.security.AuthenticatedUser;
import com.datagent.agentv2.security.AuthenticationProblemDetails;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final AuthenticationService authentication;
    private final AuthProperties properties;

    @PostMapping("/login")
    ResponseEntity<?> login(
            @RequestBody(required = false) LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String username = request == null ? null : request.username();
        String password = request == null ? null : request.password();
        return authentication
                .login(username, password)
                .<ResponseEntity<?>>map(
                        token ->
                                ResponseEntity.noContent()
                                        .header(
                                                HttpHeaders.SET_COOKIE,
                                                cookie(token.value(), token.lifetime()).toString())
                                        .build())
                .orElseGet(
                        () ->
                                ResponseEntity.status(401)
                                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                                        .body(
                                                AuthenticationProblemDetails.required(
                                                        httpRequest, httpResponse)));
    }

    @GetMapping("/session")
    AuthSession session(@AuthenticationPrincipal AuthenticatedUser principal) {
        return new AuthSession(
                principal.tenantId(),
                principal.subject(),
                principal.roles().stream().sorted().toList());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        authentication.logout(sessionCookie(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        AuthProperties.Session session = properties.getSession();
        return ResponseCookie.from(session.getCookieName(), value)
                .path("/")
                .httpOnly(true)
                .secure(session.isSecureCookie())
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
    }

    private String sessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (properties.getSession().getCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    record LoginRequest(String username, String password) {}

    record AuthSession(String tenantId, String subject, List<String> roles) {}
}
