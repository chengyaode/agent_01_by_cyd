package com.demo.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 民生查询 API Key 绑定(Spring Boot 标准注入,替代手写 getProperty).
 *
 * <p>构造器绑定要求经 @EnableConfigurationProperties 注册(见 AgentApplication),
 * 不能标注 @Component。
 *
 * <p>配置键与旧版完全一致:
 * <ul>
 *   <li>agent.qweather.key —— 和风天气 Key</li>
 *   <li>agent.qweather.api-host —— 控制台「设置-凭证」里的专属 API Host
 *       (形如 abc123.qweatherapi.com;新版账户用 devapi.qweather.com 会 403 Invalid Host)</li>
 *   <li>agent.showapi.appkey —— 万维易源 ShowAPI 应用 Key(https://www.showapi.com)</li>
 * </ul>
 *
 * <p>值来源: jar 内置 application.properties, 由工作目录外部 agent.properties 覆盖
 * (桥接见 {@link AgentPropertiesEnvironmentPostProcessor}), 改 key 无需重新打包。
 * 新版快递接口只需 appKey, 旧签名方案的 appsecret 已废弃。
 */
@ConfigurationProperties(prefix = "agent")
public record LivelihoodProperties(QWeather qweather, Showapi showapi) {

    public record QWeather(@DefaultValue("") String key, @DefaultValue("") String apiHost) {}

    public record Showapi(@DefaultValue("") String appKey) {}

    /** 和风天气 Key(未配置为空串) */
    public String qweatherKey() {
        return qweather == null ? "" : qweather.key().trim();
    }

    /**
     * 和风天气 API Base:优先用配置的专属 Host,未配置回退旧版 devapi 域名。
     * 自动补 https:// 前缀并去掉尾部斜杠。
     */
    public String qweatherBase() {
        String host = qweather == null ? "" : qweather.apiHost().trim();
        if (host.isEmpty()) {
            return "https://devapi.qweather.com";
        }
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "https://" + host;
        }
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    /** ShowAPI appKey(未配置为空串) */
    public String showapiAppKey() {
        return showapi == null ? "" : showapi.appKey().trim();
    }
}
