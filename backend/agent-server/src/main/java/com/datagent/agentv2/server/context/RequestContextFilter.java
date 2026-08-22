package com.datagent.agentv2.server.context;

import com.datagent.agentv2.http.RequestContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component("agentRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public final class RequestContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final IdGenerator idGenerator;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        request.setAttribute(RequestContext.ATTRIBUTE_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(RequestContext.MDC_REQUEST_ID, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.MDC_REQUEST_ID);
        }
    }

    private String resolveRequestId(String inbound) {
        if (inbound != null && VALID_REQUEST_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return idGenerator.next("request");
    }
}
