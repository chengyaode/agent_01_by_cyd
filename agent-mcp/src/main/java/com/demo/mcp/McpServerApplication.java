package com.demo.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.demo.mcp.config.McpProperties;

/**
 * 独立 MCP 服务入口(默认 8082)。
 *
 * <p>启动:{@code mvn spring-boot:run} 或 {@code java -jar target/agent-mcp.jar}。
 * MCP 端点:{@code http://localhost:8082/mcp}(Streamable HTTP)。
 */
@SpringBootApplication
@EnableConfigurationProperties(McpProperties.class)
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}