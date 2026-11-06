package com.demo.agent.web;

import com.demo.agent.config.AppConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API Key 鉴权:请求头 X-API-Key 必须等于 agent.api.key。
 * key 留空表示关闭鉴权(仅适合可信内网);/api/health 始终放行,便于负载均衡探活。
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final String apiKey;

    public ApiKeyFilter(AppConfig cfg) {
        this.apiKey = cfg.apiKey();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws ServletException, IOException {
        if (apiKey.isEmpty() || "/api/health".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String given = request.getHeader("X-API-Key");
        if (apiKey.equals(given)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("[401] {} {} 缺少或错误的 X-API-Key",
                request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
                .write("{\"error\":\"unauthorized\",\"message\":\"缺少或错误的请求头 X-API-Key\"}");
    }
}
