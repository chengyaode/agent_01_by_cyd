package com.demo.mcp.tool;

import static com.demo.mcp.tool.ToolSupport.integer;
import static com.demo.mcp.tool.ToolSupport.requiredStr;
import static com.demo.mcp.tool.ToolSupport.str;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.demo.mcp.service.DbService;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * 数据库工具(MCP 层):只读查询 MySQL / PostgreSQL。
 * 工具名会在 agent 侧被加上前缀,如 mcp__agent-mcp__db_query。
 */
@Component
public class DbTools {

    private final DbService db;

    public DbTools(DbService db) {
        this.db = db;
    }

    public List<SyncToolSpecification> specifications(ToolSupport support) {
        Map<String, Object> schemaProp = Map.of(
                "type", "string",
                "description", "可选。库/schema 名。MySQL 一般是数据库名,PostgreSQL 一般是 public");

        SyncToolSpecification listTables = support.spec(
                "db_list_tables",
                "列出数据库中当前 schema 下的所有表和视图。用户问「有哪些表/库结构」时先调用它。"
                        + "用户要看某张表里的具体数据,请直接用 db_table_data,不要自己写 SQL。",
                Map.of("schema", schemaProp),
                List.of(),
                args -> {
                    try {
                        return db.listTables(str(args, "schema", null));
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });

        SyncToolSpecification describeTable = support.spec(
                "db_describe_table",
                "查看指定表的字段结构(列名/类型/是否可空/默认值)。写 SQL 前先用它确认列名,避免猜错。",
                Map.of(
                        "table", Map.of("type", "string", "description", "表名,必填"),
                        "schema", schemaProp),
                List.of("table"),
                args -> {
                    try {
                        return db.describeTable(requiredStr(args, "table"), str(args, "schema", null));
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });

        SyncToolSpecification query = support.spec(
                "db_query",
                "执行只读 SQL 查询并返回结果。只支持单条 SELECT/WITH/SHOW/EXPLAIN/DESC;"
                        + "写操作(INSERT/UPDATE/DELETE/DDL)会被拒绝。"
                        + "建议先用 db_list_tables / db_describe_table 确认表结构,再写查询。",
                Map.of(
                        "sql", Map.of("type", "string", "description", "单条只读 SQL,不要带分号拼接多条"),
                        "max_rows", Map.of(
                                "type", "integer",
                                "description", "返回行数上限,默认取服务端配置(200),最大 1000")),
                List.of("sql"),
                args -> {
                    try {
                        return db.query(requiredStr(args, "sql"), integer(args, "max_rows", null));
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });

        SyncToolSpecification tableData = support.spec(
                "db_table_data",
                "读取指定表的真实数据行。用户说「看看某张表里有什么/把这张表的数据展示出来/这张表有几条数据」时,"
                        + "优先调用本工具,不要自己写 SQL。"
                        + "返回字段含义:totalRows=该表(或该条件)下的总行数,rowCount=本次返回的行数,"
                        + "hasMore=true 表示还有更多,用 offset=nextOffset 继续取。"
                        + "判定规则:rowCount=0 且 totalRows=0 说明这张表确实是空表,"
                        + "请直接告诉用户「表 X 当前没有数据」,不要反问用户想查什么;"
                        + "返回 error 字段说明表名或列名不对,按 availableColumns / similarTables 纠正后重试一次。",
                Map.of(
                        "table", Map.of("type", "string", "description", "表名,必填,如 map_service"),
                        "schema", schemaProp,
                        "columns", Map.of("type", "string",
                                "description", "可选,逗号分隔的列名,如 id,name,create_time;留空返回全部列"),
                        "where", Map.of("type", "string",
                                "description", "可选,SQL 条件片段(不要带 where 关键字),如 del_flag = '0'"),
                        "order_by", Map.of("type", "string",
                                "description", "可选,排序片段(不要带 order by 关键字),如 id desc"),
                        "limit", Map.of("type", "integer",
                                "description", "可选,本次最多返回多少行,默认取服务端配置(1000)"),
                        "offset", Map.of("type", "integer",
                                "description", "可选,跳过多少行,用于翻页,默认 0")),
                List.of("table"),
                args -> {
                    try {
                        return db.tableData(requiredStr(args, "table"), str(args, "schema", null),
                                str(args, "columns", null), str(args, "where", null),
                                str(args, "order_by", null), integer(args, "limit", null),
                                integer(args, "offset", null));
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });

        return List.of(listTables, describeTable, query, tableData);
    }
}