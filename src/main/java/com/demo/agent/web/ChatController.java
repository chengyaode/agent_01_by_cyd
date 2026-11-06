package com.demo.agent.web;

import com.demo.agent.agent.AgentHolder;
import com.demo.agent.agent.AgentRegistry;
import com.demo.agent.config.AppConfig;
import com.demo.agent.tools.DocumentTools;
import com.demo.agent.web.dto.ChatRequest;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * 对话接口:
 * <ul>
 *   <li>POST /api/agents/{name}/chat        同步一问一答</li>
 *   <li>POST /api/agents/{name}/chat/stream SSE 流式(事件: start/delta/tool/done/error)</li>
 * </ul>
 * 会话记忆由 sessionId 隔离;不传 sessionId 时服务端生成并回传,客户端保存后续传即可续聊。
 *
 * <p>每次请求前会把配置的权限模式覆盖到该会话的状态上,并自动批准历史遗留的待确认(ASKING)
 * 工具调用,避免会话被 human-in-the-loop 卡死。
 */
@RestController
@RequestMapping("/api/agents/{name}")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 自动批准残留 ASKING 调用的最大轮数,防止异常情况下无限循环 */
    private static final int MAX_APPROVE_ROUNDS = 3;

    private final AgentRegistry registry;
    private final AppConfig cfg;

    public ChatController(AgentRegistry registry, AppConfig cfg) {
        this.registry = registry;
        this.cfg = cfg;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(
            @PathVariable String name, @Valid @RequestBody ChatRequest req) {
        AgentHolder holder = requireAgent(name);
        String sessionId = orGenerateSessionId(req.sessionId());
        String userId = orDefault(req.userId(), "api");
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
        prepareSession(holder, userId, sessionId, ctx);
        String message = withContextDocs(holder, userId, sessionId, req.message());

        long start = System.currentTimeMillis();
        Msg reply;
        try {
            reply = holder.agent().call(message, ctx).block(cfg.requestTimeout());
        } catch (IllegalStateException e) {
            // Mono.block 超时表现为 IllegalStateException(Timeout on blocking read)
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "模型调用超时(" + cfg.requestTimeout().toSeconds() + "s),可改用流式接口",
                    e);
        }
        String text = reply == null ? "" : reply.getTextContent();
        if (log.isDebugEnabled()) {
            log.debug("[chat] agent={} session={} elapsed={}ms", name, sessionId,
                    System.currentTimeMillis() - start);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", name);
        out.put("sessionId", sessionId);
        out.put("userId", userId);
        out.put("reply", text);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter chatStream(
            @PathVariable String name, @Valid @RequestBody ChatRequest req) {
        AgentHolder holder = requireAgent(name);
        String sessionId = orGenerateSessionId(req.sessionId());
        String userId = orDefault(req.userId(), "api");
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
        prepareSession(holder, userId, sessionId, ctx);
        String message = withContextDocs(holder, userId, sessionId, req.message());

        SseEmitter emitter = new SseEmitter(cfg.requestTimeout().toMillis());
        AtomicReference<Disposable> subRef = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Runnable cancel =
                () -> {
                    cancelled.set(true);
                    Disposable d = subRef.get();
                    if (d != null) {
                        d.dispose();
                    }
                };
        emitter.onTimeout(cancel);
        emitter.onCompletion(cancel);
        emitter.onError(t -> cancel.run());

        try {
            // 首帧回传会话 ID,客户端未指定时据此保存后续续聊
            emitter.send(SseEmitter.event().name("start")
                    .data(Map.of("agent", name, "sessionId", sessionId, "userId", userId)));
        } catch (IOException e) {
            cancel.run();
            return emitter; // 客户端已断开
        }

        Disposable sub =
                holder.agent()
                        .streamEvents(message, ctx)
                        .subscribe(
                                event -> {
                                    try {
                                        switch (event.getType()) {
                                            case TEXT_BLOCK_DELTA -> emitter.send(
                                                    SseEmitter.event().name("delta").data(
                                                            Map.of("text",
                                                                    ((TextBlockDeltaEvent) event)
                                                                            .getDelta())));
                                            case TOOL_CALL_START -> emitter.send(
                                                    SseEmitter.event().name("tool").data(
                                                            Map.of("tool",
                                                                    ((ToolCallStartEvent) event)
                                                                            .getToolCallName())));
                                            default -> {}
                                        }
                                    } catch (Exception e) {
                                        cancel.run(); // 写失败=客户端断开,停止生成
                                    }
                                },
                                err -> {
                                    try {
                                        emitter.send(SseEmitter.event().name("error").data(
                                                Map.of("message",
                                                        err.getMessage() == null
                                                                ? err.getClass().getSimpleName()
                                                                : err.getMessage())));
                                    } catch (Exception ignored) {
                                        // 客户端已断开
                                    }
                                    emitter.complete();
                                },
                                () -> {
                                    try {
                                        emitter.send(SseEmitter.event().name("done")
                                                .data(Map.of("sessionId", sessionId)));
                                    } catch (Exception ignored) {
                                        // 客户端已断开
                                    }
                                    emitter.complete();
                                });
        subRef.set(sub);
        if (cancelled.get()) {
            sub.dispose();
        }
        return emitter;
    }

    /**
     * 会话预处理:把配置的权限模式覆盖到已落盘的老会话上,并自动批准遗留的待确认工具调用。
     *
     * <p>builder 上的 permissionContext 只对新建会话生效,重启前已保存的会话仍带着旧的(默认)
     * 权限上下文,会继续把工具调用挂成 ASKING,因此这里逐会话强制刷新。
     */
    private void prepareSession(AgentHolder holder, String userId, String sessionId,
            RuntimeContext ctx) {
        ReActAgent agent = holder.agent();
        try {
            agent.replacePermissionContext(userId, sessionId, holder.permissionContext());
        } catch (Exception e) {
            log.debug("[permission] session={} 刷新权限模式失败: {}", sessionId, e.toString());
        }
        autoApproveAsking(holder, userId, sessionId, ctx);
    }

    /**
     * 每轮消息前注入上下文提示,策略由 {@code context-inline-chars} 控制:
     * <ul>
     *   <li>budget &lt;= 0(默认,正规 agent 流程):只注入轻量文档目录(几十~几百字符),
     *       正文由模型自己调用 {@code list_context_files / read_document} 按需获取;</li>
     *   <li>budget &gt; 0(弱模型兜底):把预算内的文档正文直接注入消息,适合不能可靠
     *       发起 tool call、只会把调用当文本输出的小模型。</li>
     * </ul>
     * 两种策略都在注入块里带文档集合的内容指纹,同一指纹在同一会话只注入一次,
     * 文档有增删改(指纹变化)时会自动重新注入。
     *
     * @return 注入后的消息;无文档或该批文档已注入过时原样返回用户文本
     */
    private String withContextDocs(
            AgentHolder holder, String userId, String sessionId, String userText) {
        String fingerprint = holder.documentTools().contextFingerprint();
        if (fingerprint.isEmpty()) {
            return userText;
        }
        if (injectedBefore(holder.agent(), userId, sessionId, fingerprint)) {
            log.debug("[context] session={} 文档集合 fp={} 已注入过,跳过", sessionId, fingerprint);
            return userText;
        }
        int budget = holder.spec().contextInlineChars();
        String block = budget > 0
                ? holder.documentTools().inlineContextBlock(budget)
                : holder.documentTools().catalogBlock();
        if (block.isEmpty()) {
            return userText;
        }
        log.info("[context] session={} 自动注入{} (fp={})", sessionId,
                budget > 0 ? "参考文档正文 " + block.length() + " 字符" : "文档目录",
                fingerprint);
        return block + "\n\n【用户本轮提问】\n" + userText;
    }

    /** 扫描会话历史,判断该指纹对应的文档正文是否已经注入过 */
    private static boolean injectedBefore(
            ReActAgent agent, String userId, String sessionId, String fingerprint) {
        String marker = DocumentTools.FINGERPRINT_PREFIX + fingerprint;
        AgentState state;
        try {
            state = agent.getAgentState(userId, sessionId);
        } catch (Exception e) {
            log.debug("[context] session={} 读取会话状态失败: {}", sessionId, e.toString());
            return false;
        }
        if (state == null || state.getContext() == null) {
            return false;
        }
        for (Msg msg : state.getContext()) {
            String text = msg.getTextContent();
            if (text != null && text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自动批准该会话中处于 ASKING 状态的工具调用,让 agent 把中断的那一步执行完。
     *
     * <p>框架的恢复协议要求下一条消息在 metadata {@code agentscope_confirm_results} 里携带
     * {@link ConfirmResult},且这条消息本身不会写入上下文,所以这里先恢复旧步骤,再由调用方
     * 投递用户真正的新文本。
     */
    private void autoApproveAsking(AgentHolder holder, String userId, String sessionId,
            RuntimeContext ctx) {
        for (int round = 0; round < MAX_APPROVE_ROUNDS; round++) {
            List<ToolUseBlock> asking = askingToolCalls(holder.agent(), userId, sessionId);
            if (asking.isEmpty()) {
                return;
            }
            List<ConfirmResult> results = new ArrayList<>(asking.size());
            StringBuilder names = new StringBuilder();
            for (ToolUseBlock call : asking) {
                results.add(new ConfirmResult(true, call));
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(call.getName());
            }
            log.info("[permission] session={} 自动批准 {} 个待确认工具调用: {}",
                    sessionId, results.size(), names);
            Msg resume = UserMessage.builder()
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                    .build();
            try {
                holder.agent().call(List.of(resume), ctx).block(cfg.requestTimeout());
            } catch (Exception e) {
                log.warn("[permission] session={} 恢复待确认调用失败: {}", sessionId, e.toString());
                return;
            }
        }
    }

    /** 扫描会话上下文,收集所有仍处于 ASKING 状态的工具调用 */
    private static List<ToolUseBlock> askingToolCalls(
            ReActAgent agent, String userId, String sessionId) {
        List<ToolUseBlock> found = new ArrayList<>();
        AgentState state;
        try {
            state = agent.getAgentState(userId, sessionId);
        } catch (Exception e) {
            log.debug("[permission] session={} 读取会话状态失败: {}", sessionId, e.toString());
            return found;
        }
        if (state == null || state.getContext() == null) {
            return found;
        }
        for (Msg msg : state.getContext()) {
            for (ToolUseBlock call : msg.getContentBlocks(ToolUseBlock.class)) {
                if (call.getState() == ToolCallState.ASKING) {
                    found.add(call);
                }
            }
        }
        return found;
    }

    private AgentHolder requireAgent(String name) {
        return registry.get(name)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "未知 agent: " + name + ",可先调用 GET /api/agents 查看列表"));
    }

    private static String orGenerateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "sess-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        return sessionId.trim();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
