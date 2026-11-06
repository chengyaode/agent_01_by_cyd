package com.demo.mcp.tool;

import static com.demo.mcp.tool.ToolSupport.bool;
import static com.demo.mcp.tool.ToolSupport.integer;
import static com.demo.mcp.tool.ToolSupport.requiredStr;
import static com.demo.mcp.tool.ToolSupport.str;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.demo.mcp.service.QdrantService;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * Qdrant 向量库工具(MCP 层):集合浏览、向量检索、写入、按 payload 滚动。
 */
@Component
public class QdrantTools {

    private final QdrantService qdrant;

    public QdrantTools(QdrantService qdrant) {
        this.qdrant = qdrant;
    }

    public List<SyncToolSpecification> specifications(ToolSupport support) {
        Map<String, Object> collectionProp = Map.of(
                "type", "string",
                "description", "集合(collection)名,必填");

        SyncToolSpecification list = support.spec(
                "qdrant_list_collections",
                "列出 Qdrant 中所有集合。用户问「向量库里有什么」时先调用。",
                Map.of(),
                List.of(),
                args -> qdrant.listCollections());

        SyncToolSpecification info = support.spec(
                "qdrant_collection_info",
                "查看指定集合的详情:向量维度、距离度量、点数量、状态等。",
                Map.of("collection", collectionProp),
                List.of("collection"),
                args -> qdrant.collectionInfo(requiredStr(args, "collection")));

        SyncToolSpecification search = support.spec(
                "qdrant_search",
                "向量相似度检索:传入查询向量,返回最相似的 points(含 payload 与 score)。"
                        + "调用前需知道向量维度,可先用 qdrant_collection_info 确认;"
                        + "没有现成向量时,应先让用户提供或由上游生成 embedding。",
                Map.of(
                        "collection", collectionProp,
                        "vector", Map.of(
                                "type", "array",
                                "items", Map.of("type", "number"),
                                "description", "查询向量(数字数组),维度必须与集合一致"),
                        "limit", Map.of("type", "integer", "description", "返回条数,默认 10"),
                        "filter", Map.of(
                                "type", "string",
                                "description", "可选。Qdrant filter 的 JSON 字符串,如 {\"must\":[{\"key\":\"source\",\"match\":{\"value\":\"doc\"}}]}"),
                        "with_payload", Map.of(
                                "type", "boolean",
                                "description", "是否返回 payload,默认 true")),
                List.of("collection", "vector"),
                args -> qdrant.search(
                        requiredStr(args, "collection"),
                        toVector(args.get("vector")),
                        integer(args, "limit", 10),
                        str(args, "filter", null),
                        bool(args, "with_payload", true)));

        SyncToolSpecification upsert = support.spec(
                "qdrant_upsert",
                "写入或更新 points(按 id 覆盖)。points 数组元素格式:"
                        + "{\"id\":1,\"vector\":[0.1,0.2,...],\"payload\":{\"text\":\"...\"}}。"
                        + "写入会修改向量库数据,执行前应向用户确认。",
                Map.of(
                        "collection", collectionProp,
                        "points", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object"),
                                "description", "要写入的 points,元素包含 id / vector / payload"),
                        "wait", Map.of(
                                "type", "boolean",
                                "description", "是否等待索引完成,默认 true")),
                List.of("collection", "points"),
                args -> qdrant.upsert(
                        requiredStr(args, "collection"),
                        requireList(args.get("points"), "points"),
                        bool(args, "wait", true)));

        SyncToolSpecification scroll = support.spec(
                "qdrant_scroll",
                "按 payload 条件翻页浏览 points(不需要向量)。适合「查出某类记录/看看有哪些数据」。",
                Map.of(
                        "collection", collectionProp,
                        "limit", Map.of("type", "integer", "description", "每页条数,默认 20"),
                        "filter", Map.of(
                                "type", "string",
                                "description", "可选。Qdrant filter 的 JSON 字符串"),
                        "with_payload", Map.of("type", "boolean", "description", "默认 true"),
                        "offset", Map.of(
                                "type", "string",
                                "description", "可选。上一页返回的 next_page_offset")),
                List.of("collection"),
                args -> qdrant.scroll(
                        requiredStr(args, "collection"),
                        integer(args, "limit", 20),
                        str(args, "filter", null),
                        bool(args, "with_payload", true),
                        str(args, "offset", null)));

        return List.of(list, info, search, upsert, scroll);
    }

    private static List<Double> toVector(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("vector 必须是非空数字数组");
        }
        List<Double> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Number n)) {
                throw new IllegalArgumentException("vector 内元素必须是数字");
            }
            out.add(n.doubleValue());
        }
        return out;
    }

    private static List<?> requireList(Object raw, String name) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(name + " 必须是非空数组");
        }
        return list;
    }
}