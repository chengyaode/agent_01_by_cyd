package com.demo.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.demo.agent.config.AppConfig;
import com.demo.agent.config.LivelihoodProperties;

/**
 * HTTP 服务入口:控制台 REPL 已移除,全部交互走 REST/SSE 接口。
 *
 * <p>运行: {@code mvn package} 后 {@code java -jar target/agent.jar},
 * 接口清单见 {@link com.demo.agent.web.ChatController} 与
 * {@link com.demo.agent.web.AdminController}。
 */
@SpringBootApplication
@EnableConfigurationProperties(LivelihoodProperties.class)
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    /** 业务配置(Environment 属性源:classpath application.properties + 外部 agent.properties 覆盖) */
    @Bean
    public AppConfig appConfig(Environment env) {
        return AppConfig.load(env);
    }
}
