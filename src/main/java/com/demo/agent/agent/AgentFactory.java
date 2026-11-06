package com.demo.agent.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.demo.agent.config.AgentSpec;
import com.demo.agent.config.AppConfig;
import com.demo.agent.config.LivelihoodProperties;
import com.demo.agent.mcp.McpFactory;
import com.demo.agent.tools.DocumentTools;
import com.demo.agent.tools.LivelihoodTools;
import com.demo.agent.tools.SystemTools;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/**
 * 按蓝图组装一个 Agent:模型 + Toolkit(Java 工具 / MCP / Skill) + 会话存储。
 *
 * <p>
 * 扩展点:
 * <ul>
 * <li>Java 工具:{@link Toolkit#registerTool(Object)} 注册更多 @Tool 类</li>
 * <li>MCP:全局定义一次,agent 通过 agent.&lt;name&gt;.mcp-servers 按名选用; {workspace}
 * 在本类构建期按各自工作区替换,文件访问互相隔离</li>
 * <li>Skill:往对应 skills 目录加 &lt;name&gt;/SKILL.md 即可,运行期可动态加载</li>
 * <li>模型:agent.&lt;name&gt;.model 换 provider 只需改配置与依赖</li>
 * <li>新增 agent:改 agent.list 即可,无需新增本类或任何代码</li>
 * </ul>
 */
@Component
public class AgentFactory {

    private static final String DEFAULT_SYS_PROMPT_TEMPLATE
            = """
            你是一个具备工具调用能力的智能助手。
            可用能力:自定义 Java 工具、MCP 工具(数据库/向量库等)、以及技能(Skill)目录。
            共享工作区目录: %s
            使用文件系统类工具时,优先把文件读写限制在该工作区内。

            工具选择规则(按用户意图直接选择,不要凭猜测绕路):
            - 提到 天气/气温/下雨/下雪/要不要带伞/未来几天天气 → 直接调用 query_weather。
            - 提到 快递/物流/包裹/单号/到哪了/发货/签收 → 直接调用 query_express。
            - 提到 数据库/表/字段/记录/统计 → 用 db_ 开头的工具;提到 向量库/集合/相似检索 → 用 qdrant_ 开头的工具。
            - 参考文档工具只在两种情况下使用:用户明确提到"上传的文件/文档/附件/资料",
              或本轮消息里出现【参考文档目录】块。此时先调用 list_context_files,再用 read_document。
              其它情况不要调用 list_context_files / read_document。
            - 当某个技能与当前任务相关时,先加载/阅读该技能再按其指引执行。
            回答使用简体中文,除非用户另有要求。
            """;

    private final AppConfig cfg;
    private final LivelihoodTools livelihoodTools;
    private final LivelihoodProperties livelihoodProps;

    public AgentFactory(AppConfig cfg, LivelihoodTools livelihoodTools,
            LivelihoodProperties livelihoodProps) {
        this.cfg = cfg;
        this.livelihoodTools = livelihoodTools;
        this.livelihoodProps = livelihoodProps;
    }

    public AgentHolder build(AgentSpec spec) {
        Toolkit toolkit = new Toolkit();

        // 1) 自定义 Java 工具(常驻 basic 组)
        toolkit.registerTool(new SystemTools());

        // 1.1) 文档解析工具:每个 agent 独立的 context(参考文档)目录
        Path contextDir = spec.workspaceDir().getParent().resolve("context-" + spec.name());
        ensureDir(contextDir);
        DocumentTools documentTools = new DocumentTools(spec.name(), contextDir);
        toolkit.registerTool(documentTools);
        System.out.println("[context][" + spec.name() + "] 参考文档目录: " + contextDir
                + (spec.contextInlineChars() > 0
                ? ", 正文注入模式(预算 " + spec.contextInlineChars() + " 字符)"
                : ", 目录注入模式(正文由模型按需调用工具获取)"));

        // 1.2) 民生查询工具:天气(和风天气) + 快递轨迹(万维易源),API Key 为全局配置
        toolkit.registerTool(livelihoodTools);
        System.out.println("[livelihood][" + spec.name() + "] 天气/快递工具已注册, qweather.key "
                + (livelihoodProps.qweatherKey().isEmpty() ? "未配置" : "已配置") + ", showapi.appkey "
                + (livelihoodProps.showapiAppKey().isEmpty() ? "未配置" : "已配置"));

        // 2) MCP 工具(尽力而为:单个 server 连接失败不阻塞启动)
        List<McpClientWrapper> clients = new ArrayList<>();
        if (cfg.mcpEnabled() && !spec.mcpNames().isEmpty()) {
            Map<String, AppConfig.McpServerConfig> byName
                    = cfg.mcpServers().stream()
                            .collect(Collectors.toMap(
                                    AppConfig.McpServerConfig::name, Function.identity()));
            for (String mcpName : spec.mcpNames()) {
                AppConfig.McpServerConfig def = byName.get(mcpName);
                if (def == null) {
                    System.err.println(
                            "[mcp][" + spec.name() + "] 引用了未定义的 server: " + mcpName + ",跳过");
                    continue;
                }
                try {
                    McpClientWrapper client
                            = McpFactory.create(def.withWorkspace(spec.workspaceDir()));
                    clients.add(client);
                    toolkit.registerMcpClient(client).block();
                    System.out.println(
                            "[mcp][" + spec.name() + "] 已接入: " + def.name()
                            + " (" + def.transport() + ")");
                } catch (Exception e) {
                    System.err.println(
                            "[mcp][" + spec.name() + "] 接入失败: " + def.name()
                            + " -> " + rootMessage(e));
                }
            }
        }

        // 3) Agent(工作区/会话目录必须存在)
        ensureDir(spec.workspaceDir());
        ensureDir(spec.sessionDir());
        JsonFileAgentStateStore store
                = new JsonFileAgentStateStore(spec.sessionDir());
        PermissionContextState permissionContext = buildPermissionContext(spec);
        ReActAgent.Builder builder
                = ReActAgent.builder()
                        .name(spec.name())
                        .description("AgentScope agent: " + spec.name())
                        .sysPrompt(resolveSysPrompt(spec))
                        .model(resolveModel(spec))
                        .toolkit(toolkit)
                        .maxIters(spec.maxIters())
                        // 会话状态落盘,重启后可续聊;换 Redis/MySQL 只需替换此实现
                        .stateStore(store)
                        // 工具权限:仅对新建会话生效,已落盘的会话由 ChatController 覆盖
                        .permissionContext(permissionContext);

        GenerateOptions.Builder gen = GenerateOptions.builder();
        if (spec.temperature() != null) {
            gen.temperature(spec.temperature());
        }
        builder.generateOptions(gen.build());

        // 4) Skill 仓库(目录存在且非空才注册;Agent 运行期按需加载技能内容)
        if (Files.isDirectory(spec.skillsDir())) {
            try {
                builder.skillRepository(new FileSystemSkillRepository(spec.skillsDir()));
                System.out.println("[skill][" + spec.name() + "] 技能目录: " + spec.skillsDir());
            } catch (IllegalArgumentException e) {
                System.out.println("[skill][" + spec.name() + "] 技能目录为空,跳过: " + spec.skillsDir());
            }
        } else {
            System.out.println("[skill][" + spec.name() + "] 未发现技能目录,跳过: " + spec.skillsDir());
        }

        return new AgentHolder(
                spec, builder.build(), store, clients, contextDir, permissionContext,
                documentTools);
    }

    /**
     * 构建工具权限上下文。
     *
     * <p>
     * 框架只在权限上下文"非平凡"(mode 非 default,或配置了工作目录/规则)时才启用 PermissionEngine;默认的
     * default 模式 + 空规则会退化为逐个工具询问用户,会话因此挂起, 后续请求若不带 ConfirmResult
     * 就直接报错。因此这里始终按配置显式设置 mode,并把工作区 注册为可信目录(accept_edits 只对内置文件工具生效,对 MCP
     * 工具无效)。
     */
    private static PermissionContextState buildPermissionContext(AgentSpec spec) {
        PermissionMode mode = parseMode(spec.name(), spec.permissionMode());
        String ws = spec.workspaceDir().toAbsolutePath().normalize().toString();
        PermissionContextState ctx
                = PermissionContextState.builder()
                        .mode(mode)
                        .addWorkingDirectory(
                                ws, new AdditionalWorkingDirectory(ws, "userSettings"))
                        .build();
        System.out.println(
                "[permission][" + spec.name() + "] 工具权限模式: " + mode.getValue()
                + " (工作目录 " + ws + ")");
        return ctx;
    }

    private static PermissionMode parseMode(String agentName, String value) {
        if (value == null || value.isBlank()) {
            return PermissionMode.BYPASS;
        }
        return switch (value.trim().toLowerCase()) {
            case "default" ->
                PermissionMode.DEFAULT;
            case "accept_edits" ->
                PermissionMode.ACCEPT_EDITS;
            case "explore" ->
                PermissionMode.EXPLORE;
            case "dont_ask" ->
                PermissionMode.DONT_ASK;
            case "bypass" ->
                PermissionMode.BYPASS;
            default -> {
                System.err.println(
                        "[permission][" + agentName + "] 未知权限模式: " + value + ",按 bypass 处理");
                yield PermissionMode.BYPASS;
            }
        };
    }

    /**
     * 系统提示词:agent 自定义优先,支持 {workspace} 占位符;否则用内置模板
     */
    private static String resolveSysPrompt(AgentSpec spec) {
        Path ws = spec.workspaceDir();
        if (spec.sysPrompt() != null) {
            return spec.sysPrompt().replace("{workspace}", ws.toString());
        }
        return DEFAULT_SYS_PROMPT_TEMPLATE.formatted(ws);
    }

    /**
     * 解析模型:配置了 base-url(如内网 Ollama/在线 API)时, 通过 ModelCreationContext 注入服务地址;否则走
     * provider 默认(环境变量/localhost)。
     */
    private static Model resolveModel(AgentSpec spec) {
        if (spec.modelBaseUrl() != null) {
            System.out.println(
                    "[model][" + spec.name() + "] 使用配置的服务地址: " + spec.modelBaseUrl());
            return ModelRegistry.resolve(
                    spec.modelId(),
                    ModelCreationContext.builder().baseUrl(spec.modelBaseUrl()).build());
        }
        return ModelRegistry.resolve(spec.modelId());
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            System.err.println("[warn] 创建目录失败: " + dir + " -> " + e.getMessage());
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }
}
