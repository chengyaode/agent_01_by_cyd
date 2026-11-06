package com.demo.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * 把「工具名 + 参数 schema + 处理函数」组装成 MCP 工具描述,
 * 统一做 JSON 序列化与异常转 isError 处理。
 */
@Component
public class ToolSupport {

    private final ObjectMapper mapper;

    public ToolSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SyncToolSpecification spec(String name, String description,
            Map<String, Object> properties, List<String> required,
            Function<Map<String, Object>, Object> handler) {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, required, false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();

        return new SyncToolSpecification(tool, (exchange, args) -> {
            try {
                Object result = handler.apply(args == null ? Map.of() : args);
                String text = result instanceof String s
                        ? s
                        : mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
                return McpSchema.CallToolResult.builder()
                        .addTextContent(text)
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("调用失败: " + rootMessage(e))
                        .isError(true)
                        .build();
            }
        });
    }

    // ---------- 参数读取 ----------

    public static String str(Map<String, Object> args, String key, String fallback) {
        Object v = args.get(key);
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    public static String requiredStr(Map<String, Object> args, String key) {
        String v = str(args, key, null);
        if (v == null) {
            throw new IllegalArgumentException("缺少必填参数: " + key);
        }
        return v;
    }

    public static Integer integer(Map<String, Object> args, String key, Integer fallback) {
        Object v = args.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        Object v = args.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static String rootMessage(Throwable t) {
        // 业务异常(如「未配置」「连接失败」)保留原始描述,不要被底层 ClosedChannelException 之类覆盖
        if (t instanceof IllegalStateException || t instanceof IllegalArgumentException) {
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank()) {
                return msg;
            }
        }
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }
}