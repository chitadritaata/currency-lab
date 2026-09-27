package com.example.currency;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitConfig rateLimitConfig;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimitConfig rateLimitConfig, MeterRegistry meterRegistry) {
        this.rateLimitConfig = rateLimitConfig;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        // Берём client из атрибута, который установил ClientTagFilter
        Object clientAttr = request.getAttribute(ClientTagFilter.CLIENT_ATTR);
        String client = clientAttr != null ? clientAttr.toString() : request.getRemoteAddr();

        Object userAttr = request.getAttribute(ClientTagFilter.USER_ATTR);
        String user = userAttr != null ? userAttr.toString() : "unknown";

        Bucket bucket = rateLimitConfig.resolveBucket(client);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.addHeader("X-Rate-Limit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            meterRegistry.counter("rate_limit_allowed_total",
                    "client", client).increment();
            chain.doFilter(request, response);
        } else {
            long waitSec = probe.getNanosToWaitForRefill() / 1_000_000_000;
            response.addHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(waitSec));

            meterRegistry.counter("rate_limit_rejected_total",
                    "client", client,
                    "user", user,
                    "uri", request.getRequestURI()).increment();

            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Too Many Requests\",\"retry_after_seconds\":"
                            + waitSec + "}");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Не лимитируем служебные эндпоинты
        return request.getRequestURI().startsWith("/actuator");
    }
}
