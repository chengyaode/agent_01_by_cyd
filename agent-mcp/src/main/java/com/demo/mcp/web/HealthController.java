package com.demo.mcp.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存活探针:/health,方便启动脚本与前端检查 MCP 服务是否在线。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("service", "agent-mcp");
        out.put("mcpEndpoint", "/mcp");
        return out;
    }
}