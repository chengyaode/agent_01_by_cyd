package com.demo.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 服务配置。数据库与 Qdrant 都走「配置驱动」,换环境只改 application.properties,
 * 不需要动代码。
 */
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    /** 服务名,会出现在 MCP initialize 的 serverInfo 里 */
    private String name = "agent-mcp";
    /** Streamable HTTP 端点(servlet 映射路径) */
    private String endpoint = "/mcp";
    private Db db = new Db();
    private Qdrant qdrant = new Qdrant();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Db getDb() {
        return db;
    }

    public void setDb(Db db) {
        this.db = db;
    }

    public Qdrant getQdrant() {
        return qdrant;
    }

    public void setQdrant(Qdrant qdrant) {
        this.qdrant = qdrant;
    }

    /** 数据库连接(MySQL / PostgreSQL 通用,靠 url 区分) */
    public static class Db {

        private String url = "";
        private String username = "";
        private String password = "";
        /** 可选:驱动类名,留空由 DriverManager 按 url 自动选择 */
        private String driverClassName = "";
        /** 单条查询超时(秒) */
        private int queryTimeoutSeconds = 15;
        /** 单次返回行数上限(防止把整表塞进模型上下文) */
        private int maxRows = 200;
        /** db_table_data 单次返回行数上限(看整表数据用,比 maxRows 宽松,超出走 offset 翻页) */
        private int tableDataMaxRows = 1000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public int getQueryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }

        public int getTableDataMaxRows() {
            return tableDataMaxRows;
        }

        public void setTableDataMaxRows(int tableDataMaxRows) {
            this.tableDataMaxRows = tableDataMaxRows;
        }
    }

    /** Qdrant REST 连接 */
    public static class Qdrant {

        private String baseUrl = "http://localhost:6333";
        /** Qdrant Cloud 或开启了鉴权时填写 */
        private String apiKey = "";
        private int timeoutSeconds = 20;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}