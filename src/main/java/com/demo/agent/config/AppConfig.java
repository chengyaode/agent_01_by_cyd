package com.demo.agent.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.env.Environment;

/**
 * 应用配置:从 Spring {@link Environment} 读取,来源统一由框架管理——
 * jar 内 application.properties 为默认值,工作目录外部 agent.properties 经
 * {@link AgentPropertiesEnvironmentPostProcessor} 以最高优先级注入(改配置无需重新打包),
 * 系统属性/环境变量同样可覆盖。民生 API Key 不在此列,
 * 由 {@link LivelihoodProperties} 绑定、LivelihoodTools 构造器注入。
 *
 * <p>配置分三层:
 * <ol>
 *   <li>全局默认值:agent.model / agent.model.base-url / agent.workspace-dir ...</li>
 *   <li>Agent 列表:agent.list=default;name2;... ,每个 agent 用 agent.&lt;name&gt;.* 覆盖,</li>
 *   <li>MCP 定义:agent.mcp.&lt;server&gt;.* 全局只定义一次,由各 agent 通过
 *       agent.&lt;name&gt;.mcp-servers 按名选用;args 里的 {workspace} 在构建期
 *       按各自工作区替换,因此不同 agent 的文件访问天然隔离。</li>
 * </ol>
 */
public final class AppConfig {

    /** MCP 定义(原始值,{workspace} 未替换);按 agent 名选用并替换 */
    private final List<McpServerConfig> mcpServers;
    /** MCP 总开关 */
    private final boolean mcpEnabled;
    /** HTTP 接口的 X-API-Key;留空表示不鉴权 */
    private final String apiKey;
    /** 单次对话请求超时 */
    private final Duration requestTimeout;
    /** 全部 agent 蓝图 */
    private final List<AgentSpec> agents;

    public record McpServerConfig(
            String name,
            String transport, // stdio | sse | http
            String command,
            List<String> args,
            String url,
            Duration timeout, // 工具调用请求超时
            Duration initTimeout, // 连接/握手超时(stdio 首次拉包较慢)
            Map<String, String> env) { // stdio 子进程环境变量

        /** 把 {workspace} 占位符替换为指定 agent 的工作区绝对路径 */
        public McpServerConfig withWorkspace(Path workspace) {
            String w = workspace.toAbsolutePath().toString();
            Map<String, String> resolvedEnv = new LinkedHashMap<>();
            env.forEach((k, v) -> resolvedEnv.put(k, v.replace("{workspace}", w)));
            return new McpServerConfig(
                    name,
                    transport,
                    command == null ? null : command.replace("{workspace}", w),
                    args.stream().map(a -> a.replace("{workspace}", w)).toList(),
                    url == null ? null : url.replace("{workspace}", w),
                    timeout,
                    initTimeout,
                    resolvedEnv);
        }
    }

    private AppConfig(
            List<McpServerConfig> mcpServers,
            boolean mcpEnabled,
            String apiKey,
            Duration requestTimeout,
            List<AgentSpec> agents) {
        this.mcpServers = mcpServers;
        this.mcpEnabled = mcpEnabled;
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.agents = agents;
    }

    public static AppConfig load(Environment env) {
        String modelId = env.getProperty("agent.model", "ollama:qwen3:4b").trim();
        String modelBaseUrl = trimToNull(env.getProperty("agent.model.base-url", ""));
        Double temperature =
                env.getProperty("agent.temperature") == null
                        ? null
                        : Double.parseDouble(env.getProperty("agent.temperature").trim());
        int maxIters = Integer.parseInt(env.getProperty("agent.max-iters", "10").trim());
        Path sessionDir = Paths.get(env.getProperty("agent.session-dir", ".agentscope/sessions"));
        Path skillsDir = Paths.get(env.getProperty("agent.skills-dir", "skills"));
        Path workspaceDir =
                Paths.get(env.getProperty("agent.workspace-dir", "workspace")).toAbsolutePath();
        boolean mcpEnabled =
                Boolean.parseBoolean(env.getProperty("agent.mcp.enabled", "true"));

        String apiKey = env.getProperty("agent.api.key", "").trim();
        Duration requestTimeout =
                Duration.ofSeconds(
                        Long.parseLong(
                                env.getProperty("agent.request-timeout-seconds", "300").trim()));
        // 工具权限模式:未配置时默认 bypass,避免工具调用挂起等待人工确认
        String permissionMode = prop(env, "agent.permission-mode", "bypass").toLowerCase();
        // 参考文档自动注入预算:小模型常把工具调用当文本输出,所以正文由 Web 层直接塞进消息
        int contextInlineChars =
                Integer.parseInt(prop(env, "agent.context-inline-chars", "16000").trim());

        // MCP 定义(保留原始 {workspace} 占位符,构建期再按各 agent 替换)
        List<McpServerConfig> servers = new ArrayList<>();
        if (mcpEnabled) {
            for (String name : split(env.getProperty("agent.mcp.servers", ""))) {
                String prefix = "agent.mcp." + name + ".";
                String transport = env.getProperty(prefix + "transport", "stdio").trim();
                String command = env.getProperty(prefix + "command", "").trim();
                List<String> args = split(env.getProperty(prefix + "args", ""));
                String url = trimToNull(env.getProperty(prefix + "url", ""));
                Duration timeout =
                        Duration.ofSeconds(
                                Long.parseLong(env.getProperty(prefix + "timeout-seconds", "60")));
                Duration initTimeout =
                        Duration.ofSeconds(
                                Long.parseLong(
                                        env.getProperty(prefix + "init-timeout-seconds", "180")));
                Map<String, String> env2 = new LinkedHashMap<>();
                for (String kv : split(env.getProperty(prefix + "env", ""))) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        env2.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                    }
                }
                servers.add(
                        new McpServerConfig(
                                name, transport, command, args, url, timeout, initTimeout, env2));
            }
        }

        // Agent 注册表:agent.list 声明名字,agent.<name>.* 覆盖全局值
        List<String> names = split(env.getProperty("agent.list", "default"));
        if (names.isEmpty()) {
            names = List.of("default");
        }
        List<String> globalMcpNames = servers.stream().map(McpServerConfig::name).toList();
        List<AgentSpec> agents = new ArrayList<>();
        for (String name : names) {
            agents.add(parseAgent(env, name, modelId, modelBaseUrl, temperature, maxIters,
                    sessionDir, skillsDir, workspaceDir, globalMcpNames, permissionMode,
                    contextInlineChars));
        }

        return new AppConfig(servers, mcpEnabled, apiKey, requestTimeout, agents);
    }

    private static AgentSpec parseAgent(
            Environment env, String name,
            String modelId, String modelBaseUrl, Double temperature, int maxIters,
            Path sessionDir, Path skillsDir, Path workspaceDir, List<String> globalMcpNames,
            String permissionMode, int contextInlineChars) {
        String prefix = "agent." + name + ".";
        boolean isDefault = name.equals("default");

        String m = prop(env, prefix + "model", modelId);
        String baseUrl = prop(env, prefix + "base-url", modelBaseUrl);

        String tv = trimToNull(env.getProperty(prefix + "temperature"));
        Double temp = tv == null ? temperature : Double.parseDouble(tv);

        int iters = Integer.parseInt(prop(env, prefix + "max-iters", String.valueOf(maxIters)));

        String sysPrompt = trimToNull(env.getProperty(prefix + "sys-prompt"));

        // 目录约定:default 沿用全局目录;其它 agent 派生平行目录
        // (如 workspace-report / sessions-report),避免互相读写,可用 xxx.workspace-dir 等覆盖
        Path ws = isDefault ? workspaceDir : sibling(workspaceDir, name, "workspace");
        ws = Paths.get(prop(env, prefix + "workspace-dir", ws.toString()));
        Path skills = Paths.get(prop(env, prefix + "skills-dir", skillsDir.toString()));
        Path sessions = isDefault ? sessionDir : sibling(sessionDir, name, "sessions");
        sessions = Paths.get(prop(env, prefix + "session-dir", sessions.toString()));

        // mcp-servers:key 存在则显式指定(空串=该 agent 不接 MCP),否则继承全局
        String declared = env.getProperty(prefix + "mcp-servers");
        List<String> mcpNames = declared == null ? globalMcpNames : split(declared);

        String mode = prop(env, prefix + "permission-mode", permissionMode).toLowerCase();
        int inlineChars =
                Integer.parseInt(
                        prop(env, prefix + "context-inline-chars",
                                String.valueOf(contextInlineChars))
                                .trim());

        return new AgentSpec(
                name, m, baseUrl, temp, iters, sysPrompt,
                ws.toAbsolutePath(), skills.toAbsolutePath(), sessions.toAbsolutePath(),
                mcpNames, mode, inlineChars);
    }

    /** parent/(base文件名-后缀-name),如 workspace -> workspace-report */
    private static Path sibling(Path base, String name, String fallbackPrefix) {
        Path abs = base.toAbsolutePath();
        String fileName =
                abs.getFileName() == null ? fallbackPrefix : abs.getFileName().toString();
        return abs.getParent().resolve(fileName + "-" + name);
    }

    /** 取属性:未配置或空白时返回 fallback */
    private static String prop(Environment env, String key, String fallback) {
        String v = trimToNull(env.getProperty(key));
        return v == null ? fallback : v;
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(";")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    public boolean mcpEnabled() {
        return mcpEnabled;
    }

    public List<McpServerConfig> mcpServers() {
        return mcpServers;
    }

    public String apiKey() {
        return apiKey;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public List<AgentSpec> agents() {
        return agents;
    }
}
