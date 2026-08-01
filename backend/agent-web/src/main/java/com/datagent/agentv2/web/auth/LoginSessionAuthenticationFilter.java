package com.datagent.agentv2.web.auth;

import com.datagent.agentv2.auth.application.AuthenticationService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public final class LoginSessionAuthenticationFilter extends OncePerRequestFilter {

    private final AuthenticationService authentication;
    private final String cookieName;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var principal = authentication.authenticate(cookieValue(request)).orElse(null);
        if (principal == null) {
            filterChain.doFilter(request, response);
            return;
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext current = SecurityContextHolder.createEmptyContext();
        current.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.roles().stream()
                                .map(
                                        org.springframework.security.core.authority
                                                        .SimpleGrantedAuthority
                                                ::new)
                                .toList()));
        SecurityContextHolder.setContext(current);
        try {
            TenantContext.run(
                    principal.tenantId(),
                    () ->
                            OperatorContext.run(
                                    principal.operatorIdentity(),
                                    () -> dispatch(filterChain, request, response)));
        } catch (FilterDispatchException failure) {
            if (failure.getCause() instanceof IOException io) {
                throw io;
            }
            if (failure.getCause() instanceof ServletException servlet) {
                throw servlet;
            }
            throw failure;
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private String cookieValue(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static void dispatch(
            FilterChain filterChain, HttpServletRequest request, HttpServletResponse response) {
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException failure) {
            throw new FilterDispatchException(failure);
        }
    }

    private static final class FilterDispatchException extends RuntimeException {
        private FilterDispatchException(Exception cause) {
            super(cause);
        }
    }
}
