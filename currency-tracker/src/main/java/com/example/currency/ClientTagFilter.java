package com.example.currency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
        String client = req.getHeader("X-Client");
        String user = req.getHeader("X-User");

        String clientValue = client != null && !client.isBlank() ? client : "unknown";
        String userValue = user != null && !user.isBlank() ? user : "unknown";

        req.setAttribute(CLIENT_ATTR, clientValue);
        req.setAttribute(USER_ATTR, userValue);

        log.info("ClientTagFilter: X-Client={}, X-User={}", clientValue, userValue);

        chain.doFilter(request, response);
    }
}
