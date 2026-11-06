package com.demo.agent.mcp;

import com.demo.agent.config.AppConfig.McpServerConfig;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按配置创建 MCP 客户端。支持三种传输:
 * <ul>
 *   <li>stdio — 本地进程(Windows 下自动用 cmd.exe /c 包装 npx/npm/node 等命令)</li>
 *   <li>sse   — 远程 HTTP 长连接</li>
 *   <li>http  — Streamable HTTP</li>
 * </ul>
 *
 * <p>新增一个 MCP Server 只需在 agent.properties 里增加配置,无需改代码。
 */
public final class McpFactory {

    private McpFactory() {}

    public static McpClientWrapper create(McpServerConfig cfg) {
        McpClientBuilder builder = McpClientBuilder.create(cfg.name());
        switch (cfg.transport()) {
            case "stdio" -> {
                Map<String, String> env = cfg.env() == null ? Map.of() : cfg.env();
                if (isWindows() && needsShellWrapper(cfg.command())) {
                    // Windows: npx 等是 .cmd 脚本,ProcessBuilder 无法直接启动
                    List<String> full = new ArrayList<>();
                    full.add("/c");
                    full.add(cfg.command());
                    full.addAll(cfg.args());
                    builder.stdioTransport("cmd.exe", full, env);
                } else {
                    builder.stdioTransport(cfg.command(), cfg.args(), env);
                }
            }
            case "sse" -> builder.sseTransport(cfg.url());
            case "http" -> builder.streamableHttpTransport(cfg.url());
            default -> throw new IllegalArgumentException(
                    "未知的 MCP transport: " + cfg.transport());
        }
        if (cfg.timeout() != null) {
            builder.timeout(cfg.timeout());
        }
        if (cfg.initTimeout() != null) {
            // stdio 首次运行可能要下载包,握手超时需要放宽
            builder.initializationTimeout(cfg.initTimeout());
        }
        return builder.buildAsync().block();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean needsShellWrapper(String command) {
        return switch (command) {
            case "npx", "npm", "node", "uvx", "uv", "python", "pip" -> true;
            default -> false;
        };
    }
}
