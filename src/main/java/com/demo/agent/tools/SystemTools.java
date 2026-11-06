package com.demo.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 自定义 Java 工具示例(注解式,注册后进入常驻的 basic 工具组)。
 *
 * <p>扩展方式:在此类继续添加 @Tool 方法,或新建工具类后在
 * {@link com.demo.agent.agent.AgentFactory} 中 registerTool 即可。
 */
public class SystemTools {

    @Tool(
            name = "get_current_time",
            description = "Returns the current date and time in a given IANA timezone.",
            readOnly = true,
            concurrencySafe = true)
    public String getCurrentTime(
            @ToolParam(name = "timezone", description = "IANA timezone, e.g. Asia/Shanghai")
                    String timezone) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone);
        } catch (Exception e) {
            return "无效的时区: " + timezone;
        }
        return LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " (" + zone.getId() + ")";
    }

    @Tool(
            name = "get_workspace_info",
            description =
                    "Returns the absolute path of the shared workspace directory."
                            + " MCP file tools are rooted here, so other file paths used by the"
                            + " agent should be relative to this directory.",
            readOnly = true,
            concurrencySafe = true)
    public String getWorkspaceInfo() {
        Path ws = Path.of(System.getProperty("agent.workspace", "workspace")).toAbsolutePath();
        boolean exists = Files.isDirectory(ws);
        return "workspace=" + ws + ", exists=" + exists
                + (exists ? ", fileCount=" + countFiles(ws) : "");
    }

    private static long countFiles(Path dir) {
        try (var stream = Files.walk(dir, 2)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            return -1;
        }
    }

    @Tool(
            name = "calc_text_stats",
            description = "Counts characters, lines and words of the given text.",
            readOnly = true,
            concurrencySafe = true)
    public String calcTextStats(
            @ToolParam(name = "text", description = "The text to analyze") String text) {
        if (text == null || text.isBlank()) {
            return "文本为空";
        }
        long lines = text.split("\n").length;
        long words = text.trim().split("\\s+").length;
        return "字符数=" + text.length() + ", 行数=" + lines + ", 词数=" + words;
    }
}
