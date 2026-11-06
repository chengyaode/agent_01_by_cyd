package com.demo.agent.web;

import com.demo.agent.agent.AgentHolder;
import com.demo.agent.agent.AgentRegistry;
import com.demo.agent.config.AgentSpec;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理与健康检查接口:
 * <ul>
 *   <li>GET    /api/health                                存活探针(无需 API Key)</li>
 *   <li>GET    /api/agents                                全部 agent 概览</li>
 *   <li>DELETE /api/agents/{name}/sessions/{sessionId}    清空某个会话的全部记忆</li>
 *   <li>POST   /api/agents/{name}/context                 上传参考文档(multipart,支持多文件)</li>
 *   <li>GET    /api/agents/{name}/context                 列出已上传的参考文档</li>
 *   <li>DELETE /api/agents/{name}/context/{filename}      删除某个参考文档</li>
 * </ul>
 */
@RestController
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    /** 单文件最大 50MB,总计不限(磁盘满由 OS 兜底) */
    private static final long MAX_FILE_BYTES = 50 * 1024L * 1024L;

    private final AgentRegistry registry;

    public AdminController(AgentRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("agents", registry.size());
        out.put("uptimeSeconds",
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return out;
    }

    @GetMapping("/api/agents")
    public List<Map<String, Object>> agents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentHolder h : registry.all()) {
            AgentSpec s = h.spec();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("model", s.modelId());
            m.put("baseUrl", s.modelBaseUrl());
            m.put("temperature", s.temperature());
            m.put("maxIters", s.maxIters());
            m.put("workspace", s.workspaceDir().toString());
            m.put("contextDir", h.contextDir().toString());
            m.put("skillsDir", s.skillsDir().toString());
            m.put("sessionDir", s.sessionDir().toString());
            m.put("mcpServers", s.mcpNames());
            m.put("chatPath", "/api/agents/" + s.name() + "/chat");
            m.put("streamPath", "/api/agents/" + s.name() + "/chat/stream");
            out.add(m);
        }
        return out;
    }

    @DeleteMapping("/api/agents/{name}/sessions/{sessionId}")
    public Map<String, Object> deleteSession(
            @PathVariable String name,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "api") String userId) {
        AgentHolder holder = require(name);
        boolean existed = holder.stateStore().exists(userId, sessionId);
        holder.stateStore().delete(userId, sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", name);
        out.put("userId", userId);
        out.put("sessionId", sessionId);
        out.put("existed", existed);
        out.put("deleted", true);
        return out;
    }

    // ---------- context 参考文档 ----------

    /** 上传(可多文件):前端用 FormData 字段名 "files" 传 */
    @PostMapping(value = "/api/agents/{name}/context",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadContext(
            @PathVariable String name,
            @RequestPart("files") MultipartFile[] files) {
        AgentHolder holder = require(name);
        ensureCtxDir(holder.contextDir());

        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0, fail = 0;

        for (MultipartFile file : files) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("originalName", file.getOriginalFilename());
            r.put("size", file.getSize());
            String safeName = sanitize(file.getOriginalFilename());
            r.put("savedName", safeName);

            if (safeName == null || safeName.isEmpty()) {
                r.put("status", "failed");
                r.put("reason", "文件名为空");
                fail++;
                results.add(r);
                continue;
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                r.put("status", "failed");
                r.put("reason", "超过 " + (MAX_FILE_BYTES / 1024 / 1024) + "MB 限制");
                fail++;
                results.add(r);
                continue;
            }

            Path target = holder.contextDir().resolve(safeName);
            try {
                // 同名自动加后缀避免覆盖
                if (Files.exists(target)) {
                    target = uniquePath(holder.contextDir(), safeName);
                    r.put("savedName", target.getFileName().toString());
                    r.put("renamed", true);
                }
                Files.copy(file.getInputStream(), target);
                ok++;
                r.put("status", "uploaded");
                r.put("path", target.toString());
            } catch (IOException e) {
                fail++;
                r.put("status", "failed");
                r.put("reason", e.getMessage());
                log.warn("[context][{}] 上传 {} 失败: {}", name, safeName, e.getMessage());
            }
            results.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", name);
        out.put("uploaded", ok);
        out.put("failed", fail);
        out.put("files", results);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/agents/{name}/context")
    public Map<String, Object> listContext(@PathVariable String name) {
        AgentHolder holder = require(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", name);
        out.put("contextDir", holder.contextDir().toString());

        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(holder.contextDir())) {
            try (var stream = Files.list(holder.contextDir())) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", p.getFileName().toString());
                    try {
                        f.put("size", Files.size(p));
                        f.put("mtime", Files.getLastModifiedTime(p).toMillis());
                    } catch (IOException ignored) { /* best effort */ }
                    files.add(f);
                });
            } catch (IOException e) {
                out.put("error", e.getMessage());
            }
        }
        out.put("files", files);
        out.put("count", files.size());
        return out;
    }

    @DeleteMapping("/api/agents/{name}/context/{filename}")
    public Map<String, Object> deleteContext(
            @PathVariable String name,
            @PathVariable String filename) {
        AgentHolder holder = require(name);
        Path target = holder.contextDir().resolve(sanitize(filename)).normalize();
        // 防路径穿越
        if (!target.startsWith(holder.contextDir().normalize())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名非法");
        }
        boolean existed;
        try { existed = Files.deleteIfExists(target); }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除失败: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", name);
        out.put("filename", filename);
        out.put("existed", existed);
        out.put("deleted", existed);
        return out;
    }

    // ---------- 辅助 ----------

    private AgentHolder require(String name) {
        return registry.get(name).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未知 agent: " + name));
    }

    private void ensureCtxDir(Path dir) {
        try { Files.createDirectories(dir); }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "创建 context 目录失败: " + e.getMessage());
        }
    }

    /** 只保留最后一个路径段,禁止路径分隔符和 '..' */
    private static String sanitize(String raw) {
        if (raw == null) return null;
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        // 去空格、去路径非法字符(Windows 友好)
        name = name.trim().replaceAll("[<>:\"|?*]", "_");
        if (name.equals("..")) return null;
        return name;
    }

    private static Path uniquePath(Path dir, String name) {
        Path p = dir.resolve(name);
        int i = 1;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        while (Files.exists(p)) {
            p = dir.resolve(base + " (" + i + ")" + ext);
            i++;
        }
        return p;
    }
}
