package com.demo.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.tika.Tika;

/**
 * 文档解析工具:供 Agent 读取"上文/参考文档"文件夹里的内容。
 *
 * <p>文件来源:前端通过 HTTP 上传到 /api/agents/{name}/context,落盘到 context 目录。
 * Agent 在需要参考资料时,用 {@link #listContextFiles()} 先看有哪些文档,
 * 再用 {@link #readDocument(String)} 读取具体内容。
 *
 * <p>Web 层每轮对话前会调用 {@link #catalogBlock()} 注入轻量文档目录(只列文件名/大小),
 * 正文仍由模型按需调用工具获取——这是正规的渐进式披露流程。对不能可靠发起 tool call 的
 * 弱模型,可配置 {@code context-inline-chars>0} 改用 {@link #inlineContextBlock(int)}
 * 由服务端直接注入正文兜底。
 *
 * <p>安全约束:只允许访问当前 agent 的 context 目录,拒绝路径穿越(../)。
 */
public class DocumentTools {

    private static final int MAX_TEXT_LEN = 12000; // 单文档最多返回的字符数(超过截断)
    private static final String[] SUPPORTED = {
            "pdf", "doc", "docx", "rtf", "md", "txt", "csv",
            "html", "htm", "xml", "json",
            "ppt", "pptx", "xls", "xlsx",
            "eml", "msg"
    };
    private static final String SUPPORTED_HINT = String.join(", ", SUPPORTED);
    /** 注入块中标识指纹的前缀,便于调用方在会话历史里检索是否已注入过同一批文档 */
    public static final String FINGERPRINT_PREFIX = "ctx-fingerprint:";
    private static final int MIN_PER_FILE = 1500;

    private final String agentName;
    private final Path contextDir;
    private final Tika tika;

    public DocumentTools(String agentName, Path contextDir) {
        this.agentName = agentName;
        this.contextDir = contextDir;
        this.tika = new Tika();
        this.tika.setMaxStringLength(200_000); // Tika 内部允许解析的最大字符,返回时再二次截断
    }

    @Tool(
            name = "list_context_files",
            description = "Lists all reference / context documents uploaded by the user for this agent. "
                    + "Call this BEFORE reading any document so you know what references are available. "
                    + "Returns each file's name, size, and last modified time.",
            readOnly = true,
            concurrencySafe = true)
    public String listContextFiles() {
        if (!Files.isDirectory(contextDir)) {
            return "参考文档目录不存在(尚未上传任何文件)";
        }
        List<String> lines = new ArrayList<>();
        lines.add("=== Agent[" + agentName + "] 参考文档 ===");
        try (var stream = Files.list(contextDir)) {
            var files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isSupported(p))
                    .toList();
            if (files.isEmpty()) {
                return "参考文档目录为空,用户还没上传任何文件。支持格式: " + SUPPORTED_HINT;
            }
            for (Path p : files) {
                String sizeStr;
                try { sizeStr = humanSize(Files.size(p)); } catch (Exception e) { sizeStr = "?"; }
                String mtime;
                try {
                    mtime = Files.getLastModifiedTime(p).toString().replace("T", " ").substring(0, 19);
                } catch (Exception e) { mtime = "?"; }
                lines.add("- " + p.getFileName() + "  (" + sizeStr + ", " + mtime + ")");
            }
        } catch (IOException e) {
            return "读取参考文档目录失败: " + e.getMessage();
        }
        return String.join("\n", lines);
    }

    @Tool(
            name = "read_document",
            description = "Parses a reference/context document (PDF, Word, PPT, Excel, Markdown, CSV, "
                    + "plain text, HTML, etc.) and returns its textual content. "
                    + "File must be in the agent's context folder (use list_context_files first). "
                    + "Returns up to " + MAX_TEXT_LEN + " characters per document; longer files are truncated.",
            readOnly = true,
            concurrencySafe = true)
    public String readDocument(
            @ToolParam(name = "filename",
                    description = "Exact filename of the document inside the context folder, "
                            + "e.g. 'weekly-report-2026-11-03.docx' (no path, just the file name)")
                    String filename) {
        if (filename == null || filename.isBlank()) {
            return "错误:filename 不能为空。请先用 list_context_files 查看可用文件。";
        }
        // 安全:只允许纯文件名,拒绝路径穿越
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return "错误:filename 只能是纯文件名,不能包含路径分隔符或 '..'。";
        }
        Path target = contextDir.resolve(filename).normalize();
        if (!target.startsWith(contextDir.normalize())) {
            return "错误:文件路径越权,禁止访问 context 目录之外的文件。";
        }
        if (!Files.isRegularFile(target)) {
            return "错误:文件不存在 —— '" + filename + "'。请先用 list_context_files 确认文件名。";
        }
        if (!isSupported(target)) {
            return "不支持的文件格式: " + ext(target) + "。支持: " + SUPPORTED_HINT;
        }

        try {
            String raw = tika.parseToString(target.toFile());
            String clean = raw == null ? "" : raw.replace("\r\n", "\n").trim();
            if (clean.length() > MAX_TEXT_LEN) {
                return clean.substring(0, MAX_TEXT_LEN)
                        + "\n\n[内容已截断:文件超过 " + MAX_TEXT_LEN + " 字符,建议分段读取或让用户精简]";
            }
            String mime;
            try { mime = tika.detect(target.toFile()); } catch (Exception e) { mime = "?"; }
            return "--- 文档:" + filename + "  MIME:" + mime + "  字数:" + clean.length()
                    + " ---\n" + clean;
        } catch (Exception e) {
            return "解析失败:" + rootMessage(e)
                    + "。文件可能损坏、加密或格式异常。";
        }
    }

    // ---------- 供 Web 层主动注入 ----------

    /**
     * 轻量文档目录:只列文件名与大小,并指引模型按需调用 {@link #readDocument(String)} 取正文。
     *
     * <p>正规 agent 流程不默认塞全文:让模型知道"有哪些参考文档"即可,只有问题确实涉及
     * 文档内容时才支付正文的 token。块内带内容指纹标记,便于调用方在会话历史里对同一批文档去重。
     *
     * @return 目录文本块;无文档时返回空串
     */
    public String catalogBlock() {
        List<Path> files = supportedFiles();
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【参考文档目录】本 agent 现有 ").append(files.size()).append(" 个用户上传的参考文档:\n");
        for (Path p : files) {
            sb.append("- ").append(p.getFileName());
            try {
                sb.append(" (").append(humanSize(Files.size(p))).append(')');
            } catch (Exception ignored) {
                // 大小读不到就只列文件名
            }
            sb.append('\n');
        }
        sb.append("当用户的问题涉及上述文档内容时,先调用 read_document 工具(参数填上表中的精确文件名)")
                .append("获取正文后再作答,不要凭猜测回答、也不要把工具调用当成文本输出;")
                .append("与文档无关的问题请忽略本目录。\n")
                .append("标记: ").append(FINGERPRINT_PREFIX).append(contextFingerprint());
        return sb.toString();
    }

    /**
     * 把 context 目录里的全部文档解析成一段可直接塞进本轮用户消息的文本块。
     *
     * <p>预算分配:先按文件名字典序排序(保证同一批文件顺序稳定),每个文件平分总预算
     * (下限 {@value #MIN_PER_FILE} 字符),超出部分标注已截断;预算耗尽后剩余文件只列名。
     *
     * @param maxChars 正文总字符上限,<=0 表示不注入
     * @return 注入文本块;无文档或预算为 0 时返回空串
     */
    public String inlineContextBlock(int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        List<Path> files = supportedFiles();
        if (files.isEmpty()) {
            return "";
        }
        int perFile = Math.max(MIN_PER_FILE, maxChars / files.size());
        StringBuilder sb = new StringBuilder();
        sb.append("【参考文档·系统自动注入】以下 ")
                .append(files.size())
                .append(" 个文档由用户上传到本 agent 的参考文档目录,正文已随本条消息一并提供。\n")
                .append("请直接基于这些正文作答:不要再向用户索要文件名、不要让用户先上传、")
                .append("也不要输出 list_context_files / read_document 的调用文本")
                .append("(只有在正文被截断且确需完整内容时才可真正调用工具)。\n")
                .append("标记: ").append(FINGERPRINT_PREFIX).append(contextFingerprint()).append('\n');

        int used = 0;
        for (int i = 0; i < files.size(); i++) {
            Path p = files.get(i);
            String name = p.getFileName().toString();
            if (used >= maxChars) {
                sb.append("\n===== 文件: ").append(name)
                        .append(" 正文未注入(已超出注入预算,可调用 read_document 读取) =====\n");
                continue;
            }
            sb.append("\n===== 文件: ").append(name);
            try {
                sb.append(" (").append(humanSize(Files.size(p))).append(')');
            } catch (Exception ignored) {
                // 大小读不到就只列文件名
            }
            sb.append(" =====\n");

            String body;
            try {
                String raw = tika.parseToString(p.toFile());
                body = raw == null ? "" : raw.replace("\r\n", "\n").trim();
            } catch (Exception e) {
                body = "[解析失败: " + rootMessage(e) + "]";
            }
            if (body.isEmpty()) {
                body = "[未解析出文本内容]";
            }
            int take = Math.min(body.length(), Math.min(perFile, maxChars - used));
            sb.append(body, 0, take);
            if (take < body.length()) {
                sb.append("\n[⚠ 本文件正文超出注入预算,仅注入前 ").append(take)
                        .append(" 字符;如需其余部分请调用 read_document(filename=\"")
                        .append(name).append("\")]");
            }
            used += take;
        }
        return sb.toString();
    }

    /**
     * 当前文档集合的指纹(文件名 + 大小 + 修改时间)。
     *
     * <p>调用方把它写进注入块里,下一轮对话时扫会话上下文即可判断同一批文档是否已经注入过,
     * 避免每轮重复塞入全文、把上下文窗口撑爆。目录为空时返回空串。
     */
    public String contextFingerprint() {
        List<Path> files = supportedFiles();
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sig = new StringBuilder();
        for (Path p : files) {
            sig.append(p.getFileName()).append('|');
            try {
                sig.append(Files.size(p)).append('|').append(Files.getLastModifiedTime(p).toMillis());
            } catch (Exception e) {
                sig.append("?|?");
            }
            sig.append(';');
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(sig.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(sig.toString().hashCode());
        }
    }

    /** 目录内受支持的文档,按文件名排序;目录不存在时返回空列表 */
    private List<Path> supportedFiles() {
        if (!Files.isDirectory(contextDir)) {
            return List.of();
        }
        try (var stream = Files.list(contextDir)) {
            List<Path> files = new ArrayList<>(
                    stream.filter(Files::isRegularFile).filter(this::isSupported).toList());
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return files;
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---------- 辅助 ----------

    private boolean isSupported(Path p) {
        String ext = ext(p).toLowerCase();
        for (String s : SUPPORTED) if (s.equals(ext)) return true;
        return false;
    }

    private static String ext(Path p) {
        String name = p.getFileName().toString();
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
