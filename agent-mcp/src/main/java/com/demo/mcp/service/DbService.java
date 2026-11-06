package com.demo.mcp.service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.demo.mcp.config.McpProperties;

/**
 * 只读数据库访问:MySQL 与 PostgreSQL 共用同一套 JDBC 代码,由 mcp.db.url 决定连哪个库。
 *
 * <p>安全约束:只允许单条 SELECT/WITH/SHOW/EXPLAIN/DESC,强制行数上限与查询超时。
 */
@Service
public class DbService {

    /** 出现这些关键字直接拒绝(按单词边界匹配,避免误伤 created_at 之类标识符) */
    private static final Pattern WRITE_KEYWORDS = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|rename|replace|"
                    + "merge|call|load|lock|unlock|commit|rollback|set|use|handler|prepare|execute)\\b");

    private final McpProperties props;

    public DbService(McpProperties props) {
        this.props = props;
        DriverManager.setLoginTimeout(10);
    }

    /** 表/视图清单 */
    public Map<String, Object> listTables(String schema) throws SQLException {
        try (Connection conn = open()) {
            DatabaseMetaData md = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schemaPattern = (schema == null || schema.isBlank()) ? currentSchema(conn) : schema.trim();

            List<Map<String, Object>> tables = new ArrayList<>();
            try (ResultSet rs = md.getTables(catalog, schemaPattern, "%",
                    new String[] {"TABLE", "VIEW"})) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("catalog", rs.getString("TABLE_CAT"));
                    row.put("schema", rs.getString("TABLE_SCHEM"));
                    row.put("name", rs.getString("TABLE_NAME"));
                    row.put("type", rs.getString("TABLE_TYPE"));
                    row.put("remarks", rs.getString("REMARKS"));
                    tables.add(row);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("catalog", catalog);
            out.put("schema", schemaPattern != null ? schemaPattern : catalog);
            out.put("count", tables.size());
            out.put("tables", tables);
            return out;
        }
    }

    /** 表结构(列名/类型/是否可空/默认值) */
    public Map<String, Object> describeTable(String table, String schema) throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table 不能为空");
        }
        try (Connection conn = open()) {
            // MySQL 的 schema 概念对应 database(catalog);PostgreSQL 才有独立 schema。
            // 不指定时必须限定当前库,否则 information_schema 会命中其它库的同名表。
            String schemaName = (schema == null || schema.isBlank())
                    ? defaultSchema(conn)
                    : schema.trim();

            String sql = """
                    select column_name, data_type, is_nullable, column_default,
                           character_maximum_length, numeric_precision, numeric_scale
                      from information_schema.columns
                     where table_name = ? and table_schema = ?
                     order by ordinal_position
                    """;

            List<Map<String, Object>> columns = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, table.trim());
                ps.setString(2, schemaName);
                ps.setQueryTimeout(Math.max(5, props.getDb().getQueryTimeoutSeconds()));
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), value(rs.getObject(i)));
                        }
                        columns.add(row);
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("table", table.trim());
            out.put("schema", schemaName);
            out.put("count", columns.size());
            out.put("columns", columns);
            return out;
        }
    }

    /**
     * 读取指定表的真实数据行(不必自己写 SQL)。
     *
     * <p>同时返回 {@code totalRows}(COUNT(*))与 {@code hasMore},
     * 用来区分「表真的是空的」「条件不匹配」「还有更多行没取完」三种情况。
     */
    public Map<String, Object> tableData(String table, String schema, String columns,
            String where, String orderBy, Integer limit, Integer offset) throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table 不能为空");
        }
        int cfg = Math.max(1, props.getDb().getTableDataMaxRows());
        int cap = (limit == null || limit <= 0) ? cfg : Math.min(limit, cfg);
        int from = (offset == null || offset < 0) ? 0 : offset;

        try (Connection conn = open()) {
            String tableTrim = table.trim();
            String schemaName = (schema == null || schema.isBlank())
                    ? defaultSchema(conn) : schema.trim();
            String qualified = qualified(conn, schemaName, tableTrim);

            List<String> allColumns = columnNames(conn, tableTrim, schemaName);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("table", tableTrim);
            out.put("schema", schemaName);

            if (allColumns.isEmpty()) {
                out.put("error", "表不存在或不可读(请核对表名与库名)");
                out.put("similarTables", similarTables(conn, tableTrim, schemaName));
                return out;
            }

            // 列名白名单校验:模型猜错列名时直接给出可用列,而不是抛一句 SQL 错误
            String selectList = "*";
            if (columns != null && !columns.isBlank()) {
                List<String> picked = new ArrayList<>();
                List<String> unknown = new ArrayList<>();
                for (String raw : columns.split(",")) {
                    String c = raw.trim();
                    if (c.isEmpty() || "*".equals(c)) {
                        continue;
                    }
                    String hit = null;
                    for (String real : allColumns) {
                        if (real.equalsIgnoreCase(c)) {
                            hit = real;
                            break;
                        }
                    }
                    if (hit == null) {
                        unknown.add(c);
                    } else if (!picked.contains(hit)) {
                        picked.add(hit);
                    }
                }
                if (!unknown.isEmpty()) {
                    out.put("error", "列不存在: " + String.join(", ", unknown));
                    out.put("availableColumns", allColumns);
                    return out;
                }
                if (!picked.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String c : picked) {
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        sb.append(quoteOne(conn, c));
                    }
                    selectList = sb.toString();
                }
            }

            String filter = (where == null || where.isBlank()) ? "" : " where " + where.trim();
            String sort = (orderBy == null || orderBy.isBlank()) ? "" : " order by " + orderBy.trim();

            long start = System.currentTimeMillis();
            List<String> retColumns = new ArrayList<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            boolean hasMore = false;

            String sql = "select " + selectList + " from " + qualified + filter + sort
                    + " limit " + (cap + 1) + " offset " + from;
            assertReadOnly(sql);

            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(Math.max(1, props.getDb().getQueryTimeoutSeconds()));
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) {
                        retColumns.add(meta.getColumnLabel(i));
                    }
                    while (rs.next()) {
                        if (rows.size() >= cap) {
                            hasMore = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.put(retColumns.get(i - 1), value(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                }
            }

            long total = countRows(conn, qualified, filter);

            out.put("columns", retColumns);
            out.put("rowCount", rows.size());
            out.put("totalRows", total);
            out.put("offset", from);
            out.put("limit", cap);
            out.put("hasMore", hasMore);
            out.put("nextOffset", hasMore ? from + rows.size() : null);
            out.put("elapsedMs", System.currentTimeMillis() - start);
            out.put("sql", sql);
            out.put("rows", rows);
            return out;
        }
    }

    /** 执行只读 SQL,返回列名 + 行数据 */
    public Map<String, Object> query(String sql, Integer maxRows) throws SQLException {
        assertReadOnly(sql);
        int cap = clamp(maxRows, props.getDb().getMaxRows());

        try (Connection conn = open();
                Statement st = conn.createStatement()) {
            st.setQueryTimeout(Math.max(1, props.getDb().getQueryTimeoutSeconds()));
            // 多取一行用于判断是否被截断
            st.setMaxRows(cap + 1);
            long start = System.currentTimeMillis();

            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= cap) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(columns.get(i - 1), value(rs.getObject(i)));
                    }
                    rows.add(row);
                }

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("columns", columns);
                out.put("rowCount", rows.size());
                out.put("truncated", truncated);
                out.put("elapsedMs", System.currentTimeMillis() - start);
                out.put("rows", rows);
                return out;
            }
        }
    }

    // ---------- 内部 ----------

    private Connection open() throws SQLException {
        McpProperties.Db cfg = props.getDb();
        if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new SQLException("数据库未配置:请在 agent-mcp 的配置里设置 mcp.db.url / username / password");
        }
        Properties info = new Properties();
        if (cfg.getUsername() != null) {
            info.setProperty("user", cfg.getUsername());
        }
        if (cfg.getPassword() != null) {
            info.setProperty("password", cfg.getPassword());
        }
        try {
            if (cfg.getDriverClassName() != null && !cfg.getDriverClassName().isBlank()) {
                Class.forName(cfg.getDriverClassName());
            }
            return DriverManager.getConnection(cfg.getUrl(), info);
        } catch (ClassNotFoundException e) {
            throw new SQLException("找不到 JDBC 驱动: " + cfg.getDriverClassName(), e);
        }
    }

    private static String currentSchema(Connection conn) {
        try {
            return conn.getSchema();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 默认 schema 名:MySQL 用当前 database(catalog),PostgreSQL 用 current_schema()。
     * 用于 information_schema 过滤,避免命中同实例其它库的同名表。
     */
    private static String defaultSchema(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName();
        if (product != null && product.toLowerCase(Locale.ROOT).contains("mysql")) {
            return conn.getCatalog();
        }
        String schema = currentSchema(conn);
        return schema != null ? schema : conn.getCatalog();
    }

    private static int clamp(Integer requested, int fallback) {
        int cap = requested == null ? fallback : requested;
        if (cap <= 0) {
            cap = fallback;
        }
        return Math.min(cap, 1000);
    }

    /** 只读校验:单条语句 + 白名单前缀 + 黑名单关键字 */
    static void assertReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.contains(";")) {
            throw new IllegalArgumentException("只允许单条 SQL,不要用分号拼接多条语句");
        }
        String lower = s.toLowerCase(Locale.ROOT);
        boolean allowedPrefix = lower.startsWith("select") || lower.startsWith("with")
                || lower.startsWith("show") || lower.startsWith("explain")
                || lower.startsWith("desc");
        if (!allowedPrefix) {
            throw new IllegalArgumentException(
                    "只允许只读查询(SELECT / WITH / SHOW / EXPLAIN / DESC),当前语句被拒绝");
        }
        if (WRITE_KEYWORDS.matcher(lower).find()) {
            throw new IllegalArgumentException("SQL 包含写操作关键字,本工具只提供只读查询");
        }
    }

    /** 合法标识符:字母/数字/下划线/$/中文,防止拼接 SQL 时出现注入 */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_$\\u4e00-\\u9fa5]+$");

    private static String quoteOne(Connection conn, String identifier) throws SQLException {
        String name = identifier == null ? "" : identifier.trim();
        if (name.startsWith("`") && name.endsWith("`") && name.length() > 1) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
            name = name.substring(1, name.length() - 1);
        }
        if (!SAFE_ID.matcher(name).matches()) {
            throw new IllegalArgumentException("非法标识符: " + identifier);
        }
        String product = conn.getMetaData().getDatabaseProductName();
        boolean mysql = product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
        return mysql ? "`" + name + "`" : "\"" + name + "\"";
    }

    private static String qualified(Connection conn, String schemaName, String table) throws SQLException {
        return quoteOne(conn, schemaName) + "." + quoteOne(conn, table);
    }

    /** 表的真实列名(有序);表不存在时返回空列表 */
    private List<String> columnNames(Connection conn, String table, String schemaName)
            throws SQLException {
        String sql = "select column_name from information_schema.columns"
                + " where table_name = ? and table_schema = ? order by ordinal_position";
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, schemaName);
            ps.setQueryTimeout(Math.max(1, props.getDb().getQueryTimeoutSeconds()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        }
        return cols;
    }

    /** 表名写错时的近似表名,帮助模型自我纠正而不是反问用户 */
    private List<Map<String, Object>> similarTables(Connection conn, String table, String schemaName)
            throws SQLException {
        String key = table.replaceAll("[^A-Za-z0-9_\\u4e00-\\u9fa5]", "");
        String like = key.length() > 4 ? "%" + key.substring(0, 4) + "%" : "%" + key + "%";
        String sql = "select table_name, table_comment from information_schema.tables"
                + " where table_schema = ? and table_name like ? order by table_name limit 20";
        List<Map<String, Object>> hits = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, like);
            ps.setQueryTimeout(Math.max(1, props.getDb().getQueryTimeoutSeconds()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", rs.getString(1));
                    row.put("comment", rs.getString(2));
                    hits.add(row);
                }
            }
        }
        if (hits.isEmpty()) {
            String any = "select table_name from information_schema.tables"
                    + " where table_schema = ? order by table_name limit 30";
            try (PreparedStatement ps = conn.prepareStatement(any)) {
                ps.setString(1, schemaName);
                ps.setQueryTimeout(Math.max(1, props.getDb().getQueryTimeoutSeconds()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", rs.getString(1));
                        hits.add(row);
                    }
                }
            }
        }
        return hits;
    }

    private long countRows(Connection conn, String qualified, String filter) {
        String sql = "select count(*) from " + qualified + filter;
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(Math.max(1, props.getDb().getQueryTimeoutSeconds()));
            try (ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            // COUNT 失败不该让整次取数失败,用 -1 表示总数未知
            return -1L;
        }
    }

    private static Object value(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof byte[] bytes) {
            return "<binary " + bytes.length + " bytes>";
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof String) {
            return v;
        }
        return String.valueOf(v);
    }
}