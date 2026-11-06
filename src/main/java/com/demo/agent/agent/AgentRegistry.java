package com.demo.agent.agent;

import com.demo.agent.config.AppConfig;
import com.demo.agent.config.AgentSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 注册表:启动时按 agent.list 批量构建全部 agent,按名字对外提供。
 *
 * <p>新增 agent 零代码:改 agent.properties 的 agent.list(加名字)即可,
 * 每个 agent 拥有独立的 Toolkit/MCP 连接/会话目录,互不干扰。
 */
@Component
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppConfig cfg;
    private final AgentFactory agentFactory;
    private final Map<String, AgentHolder> holders = new LinkedHashMap<>();

    public AgentRegistry(AppConfig cfg, AgentFactory agentFactory) {
        this.cfg = cfg;
        this.agentFactory = agentFactory;
    }

    @PostConstruct
    public void init() {
        if (cfg.apiKey().isBlank()) {
            log.warn("未配置 agent.api.key:接口无需鉴权即可访问,仅建议在可信内网这样部署");
        }
        List<AgentSpec> specs = cfg.agents();
        // SystemTools.get_workspace_info 的展示值:取第一个 agent 的工作区
        System.setProperty("agent.workspace", specs.get(0).workspaceDir().toString());

        preflightModels(specs);

        for (AgentSpec spec : specs) {
            try {
                AgentHolder holder = agentFactory.build(spec);
                holders.put(spec.name(), holder);
                log.info("[agent] 构建完成: {} (model={}, workspace={})",
                        spec.name(), spec.modelId(), spec.workspaceDir());
            } catch (Exception e) {
                log.error("[agent] 构建失败: {} -> {}", spec.name(), e.toString());
            }
        }
        if (holders.isEmpty()) {
            throw new IllegalStateException("没有任何 agent 构建成功,服务无法启动");
        }
        log.info("[agent] 就绪,共 {} 个: {}", holders.size(), holders.keySet());
    }

    @PreDestroy
    public void shutdown() {
        holders.values().forEach(AgentHolder::close);
    }

    public Optional<AgentHolder> get(String name) {
        return Optional.ofNullable(holders.get(name));
    }

    public Collection<AgentHolder> all() {
        return holders.values();
    }

    public int size() {
        return holders.size();
    }

    /**
     * Ollama 模型预检:确认服务可达、模型已拉取且支持工具调用,提前给出明确提示。
     * 非致命检查,失败只告警不退出;同一 (model, baseUrl) 组合只检查一次。
     */
    private void preflightModels(List<AgentSpec> specs) {
        Set<String> checked = new HashSet<>();
        for (AgentSpec spec : specs) {
            String modelId = spec.modelId();
            if (!modelId.startsWith("ollama:")) {
                continue;
            }
            String baseUrl =
                    spec.modelBaseUrl() != null && !spec.modelBaseUrl().isBlank()
                            ? spec.modelBaseUrl()
                            : System.getenv().getOrDefault(
                                    "OLLAMA_BASE_URL", "http://localhost:11434");
            if (!checked.add(modelId + "|" + baseUrl)) {
                continue;
            }
            checkOllama(spec.name(), modelId.substring("ollama:".length()), baseUrl);
        }
    }

    private void checkOllama(String agentName, String model, String baseUrl) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> resp =
                    client.send(
                            HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                                    .timeout(Duration.ofSeconds(5))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[preflight][{}] Ollama /api/tags 返回 {}", agentName, resp.statusCode());
                return;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            List<String> models = new ArrayList<>();
            for (JsonNode m : root.path("models")) {
                models.add(m.path("name").asText());
            }
            String installedName =
                    models.stream()
                            .filter(n -> n.equals(model) || n.startsWith(model + ":"))
                            .findFirst()
                            .orElse(null);
            if (installedName == null) {
                log.warn("[preflight][{}] Ollama 中未找到模型 {}(已装: {}),可执行: ollama pull {}",
                        agentName, model, models, model);
                return;
            }
            if (!probeToolSupport(client, baseUrl, installedName)) {
                log.warn("[preflight][{}] 模型 {} 不支持工具调用,建议改用 qwen3/qwen2.5/llama3.1 等",
                        agentName, installedName);
            }
        } catch (Exception e) {
            log.warn("[preflight][{}] Ollama 预检失败({}): {}", agentName, baseUrl, e.getMessage());
        }
    }

    /** 通过 /api/show 判断模型是否具备 tools 能力(capabilities 缺失时回退检查模板) */
    private static boolean probeToolSupport(HttpClient client, String baseUrl, String model)
            throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("model", model));
        HttpResponse<String> resp =
                client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/api/show"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return true; // 查询失败时不误报,交给实际调用阶段暴露问题
        }
        JsonNode show = MAPPER.readTree(resp.body());
        for (JsonNode cap : show.path("capabilities")) {
            if ("tools".equals(cap.asText())) {
                return true;
            }
        }
        // 老版本 Ollama 的 /api/show 可能没有 capabilities 字段,退回看模板是否含 tools 分支
        return show.path("template").asText("").contains("tools");
    }
}
