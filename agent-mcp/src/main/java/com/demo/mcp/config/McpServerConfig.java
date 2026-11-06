package com.demo.mcp.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.demo.mcp.tool.DbTools;
import com.demo.mcp.tool.QdrantTools;
import com.demo.mcp.tool.ToolSupport;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;

/**
 * 把 MCP 服务端挂到 Spring MVC 容器上:
 * 一个 servlet 负责 /mcp(Streamable HTTP),一个 McpSyncServer 持有全部工具。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(
            ObjectMapper objectMapper, McpProperties props) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(props.getEndpoint())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> mcpServlet(
            HttpServletStreamableServerTransportProvider provider, McpProperties props) {
        ServletRegistrationBean<HttpServlet> registration =
                new ServletRegistrationBean<>(provider, props.getEndpoint());
        registration.setName("mcpServlet");
        // SSE 长连接需要异步支持
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpSyncServer(
            HttpServletStreamableServerTransportProvider provider,
            McpProperties props,
            ToolSupport toolSupport,
            DbTools dbTools,
            QdrantTools qdrantTools) {

        List<SyncToolSpecification> tools = new ArrayList<>();
        tools.addAll(dbTools.specifications(toolSupport));
        tools.addAll(qdrantTools.specifications(toolSupport));

        McpSyncServer server = McpServer.sync(provider)
                .serverInfo(props.getName(), "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .instructions("数据库只读查询(MySQL/PostgreSQL)与 Qdrant 向量库工具。"
                        + "写操作前必须先向用户确认。")
                .tools(tools)
                .build();

        System.out.println("[mcp] " + props.getName() + " 已启动,端点 http://localhost:"
                + System.getProperty("server.port", "8082") + props.getEndpoint()
                + ",工具 " + tools.size() + " 个: "
                + tools.stream().map(t -> t.tool().name()).toList());
        return server;
    }
}