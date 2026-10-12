package com.example.currency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

@Component
@Order(0)
public class ClientTagFilter implements jakarta.servlet.Filter {

    private static final Logger log = LoggerFactory.getLogger(ClientTagFilter.class);

    public static final String CLIENT_ATTR = "x-client-attr";
    public static final String USER_ATTR = "x-user-attr";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        ContentCachingResponseWrapper wrappedResp =
                new ContentCachingResponseWrapper((HttpServletResponse) response);

        String client = req.getHeader("X-Client");
        String clientValue = client != null && !client.isBlank() ? client : "unknown";
        String user = req.getHeader("X-User");
        String userValue = user != null && !user.isBlank() ? user : "unknown";

        req.setAttribute(CLIENT_ATTR, clientValue);
        req.setAttribute(USER_ATTR, userValue);

        String uri = req.getRequestURI();
        boolean isApi = uri.startsWith("/api/");

        if (isApi) {
            log.info(">> from={} Received request uri={}", clientValue, uri);
        }

        try {
            chain.doFilter(request, wrappedResp);
        } finally {
            if (isApi) {
                byte[] bodyBytes = wrappedResp.getContentAsByteArray();
                String bodyStr = bodyBytes.length > 0
                        ? new String(bodyBytes, wrappedResp.getCharacterEncoding())
                        : "empty";
                log.info("<< to={} Sending response status={} body={}",
                        clientValue, wrappedResp.getStatus(), oneLine(bodyStr));
            }
            wrappedResp.copyBodyToResponse();
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "null";
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}
