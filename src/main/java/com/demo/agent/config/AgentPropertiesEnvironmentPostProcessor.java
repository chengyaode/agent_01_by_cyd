package com.demo.agent.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

/**
 * 把外部 agent.properties(工作目录)注入 Spring Environment, 且优先级高于 jar 内置配置。
 *
 * <p>这样所有 agent.* 键既能被 {@code @ConfigurationProperties}/{@code @Value} 直接绑定,
 * 又保留"改配置无需重新打包"的特性:文件不存在时静默跳过。
 * AppConfig 解析 agent.&lt;name&gt;.* 等动态键时读取的也是同一份合并后的 Environment。
 */
public class AgentPropertiesEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    private static final String NAME = "externalAgentProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Path external = Paths.get("agent.properties").toAbsolutePath();
        if (!Files.exists(external)) {
            return;
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(external)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            System.out.println("[config] 已加载外部配置: " + external);
        } catch (IOException e) {
            System.err.println("读取 agent.properties 失败: " + e.getMessage());
            return;
        }
        // addFirst: 外部文件覆盖 jar 内 application.properties(与原 AppConfig.load 语义一致)
        env.getPropertySources().addFirst(new PropertiesPropertySource(NAME, p));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
