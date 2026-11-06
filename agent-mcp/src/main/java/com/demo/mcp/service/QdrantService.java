package com.demo.mcp.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.stereotype.Service;

import com.demo.mcp.config.McpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Qdrant REST 客户端(不引入额外 SDK,直接打 HTTP 接口,便于跟随 Qdrant 版本)。
 * 文档:https://api.qdrant.tech/api-reference
 */
@Service
public class QdrantService {

    private final McpProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public QdrantService(McpProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public JsonNode listCollections() {
        return send("GET", "/collections", null);
    }

    public JsonNode collectionInfo(String collection) {
        return send("GET", "/collections/" + encode(collection), null);
    }

    public JsonNode search(String collection, Object vector, int limit,
            String filterJson, boolean withPayload) {
        var body = mapper.createObjectNode();
        body.set("vector", mapper.valueToTree(vector));
        body.put("limit", limit);
        body.put("with_payload", withPayload);
        setIfPresent(body, "filter", filterJson);
        return send("POST", "/collections/" + encode(collection) + "/points/search", body);
    }

    public JsonNode scroll(String collection, int limit, String filterJson,
            boolean withPayload, String offset) {
        var body = mapper.createObjectNode();
        body.put("limit", limit);
        body.put("with_payload", withPayload);
        setIfPresent(body, "filter", filterJson);
        if (offset != null && !offset.isBlank()) {
            String o = offset.trim();
            try {
                body.put("offset", Long.parseLong(o));
            } catch (NumberFormatException e) {
                body.put("offset", o);
            }
        }
        return send("POST", "/collections/" + encode(collection) + "/points/scroll", body);
    }

    public JsonNode upsert(String collection, Object points, boolean wait) {
        var body = mapper.createObjectNode();
        body.set("points", mapper.valueToTree(points));
        return send("PUT", "/collections/" + encode(collection) + "/points?wait=" + wait, body);
    }

    // ---------- 内部 ----------

    private void setIfPresent(com.fasterxml.jackson.databind.node.ObjectNode body,
            String field, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            body.set(field, mapper.readTree(json));
        } catch (IOException e) {
            throw new IllegalArgumentException(field + " 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private JsonNode send(String method, String path, Object body) {
        McpProperties.Qdrant cfg = props.getQdrant();
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            throw new IllegalStateException("Qdrant 未配置:请设置 mcp.qdrant.base-url");
        }
        String url = cfg.getBaseUrl().replaceAll("/+$", "") + path;
        String payload;
        try {
            payload = body == null ? null : mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("请求体序列化失败: " + e.getMessage(), e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(3, cfg.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
            builder.header("api-key", cfg.getApiKey());
        }
        switch (method) {
            case "GET" -> builder.GET();
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(
                    payload, StandardCharsets.UTF_8));
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(
                    payload, StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "连接 Qdrant 失败(" + url + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求 Qdrant 被中断", e);
        }

        if (response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Qdrant 返回 HTTP " + response.statusCode() + ": " + response.body());
        }
        try {
            return response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 响应不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private static String encode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("collection 不能为空");
        }
        return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8);
    }
}