package com.demo.agent.agent;

import com.demo.agent.config.AgentSpec;
import com.demo.agent.tools.DocumentTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.nio.file.Path;
import java.util.List;

/**
 * 一个 agent 的运行期句柄:Agent 实例 + 它的状态存储 + 它独占的 MCP 连接。
 *
 * <p>ReActAgent 单实例即可服务所有会话:同一 (userId, sessionId) 的调用由框架自动串行,
 * 不同会话互不阻塞,且每次调用都会从 stateStore 重新加载会话状态。
 */
public final class AgentHolder implements AutoCloseable {

    private final AgentSpec spec;
    private final ReActAgent agent;
    private final JsonFileAgentStateStore stateStore;
    private final List<McpClientWrapper> mcpClients;
    private final Path contextDir;
    private final PermissionContextState permissionContext;
    private final DocumentTools documentTools;

    public AgentHolder(
            AgentSpec spec,
            ReActAgent agent,
            JsonFileAgentStateStore stateStore,
            List<McpClientWrapper> mcpClients,
            Path contextDir,
            PermissionContextState permissionContext,
            DocumentTools documentTools) {
        this.spec = spec;
        this.agent = agent;
        this.stateStore = stateStore;
        this.mcpClients = mcpClients;
        this.contextDir = contextDir;
        this.permissionContext = permissionContext;
        this.documentTools = documentTools;
    }

    /**
     * 文档工具实例:Web 层需要在每轮对话前直接读取参考文档正文并注入消息,
     * 不能依赖模型自己发起 tool call(小模型常把调用当文本输出)。
     */
    public DocumentTools documentTools() {
        return documentTools;
    }

    /** 配置的权限上下文:用于把 mode 覆盖到重启前已落盘的老会话上 */
    public PermissionContextState permissionContext() {
        return permissionContext;
    }

    public Path contextDir() {
        return contextDir;
    }

    public AgentSpec spec() {
        return spec;
    }

    public ReActAgent agent() {
        return agent;
    }

    public JsonFileAgentStateStore stateStore() {
        return stateStore;
    }

    @Override
    public void close() {
        try {
            agent.close();
        } catch (Exception ignored) {
            // best effort
        }
        for (McpClientWrapper client : mcpClients) {
            try {
                client.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
