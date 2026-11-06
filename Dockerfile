# Agent HTTP 服务镜像
# 构建 jar 后打包:  mvn clean package -DskipTests ;  docker build -t agent-server .
# 运行:  docker run -d -p 8080:8080 -v %cd%\agent.properties:/app/agent.properties ^
#        -v %cd%\workspace:/app/workspace -v %cd%\.agentscope:/app/.agentscope agent-server
#
# 注意:此镜像只含 JRE,没有 node/npx,stdio 型 MCP(server-filesystem)在容器内
# 接入会失败并被自动跳过(不影响启动);远程 sse/http 型 MCP 不受影响。
# 若必须用 stdio MCP,可改用带 node 的基础镜像,例如:FROM eclipse-temurin:21-jre 之上
# 安装 nodejs,或直接换 node:21 + JRE 的复合镜像。
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/agent.jar app.jar

# 外部配置/工作区/会话状态建议用卷挂载(见上方 docker run),镜像内不打包 agent.properties
ENV JAVA_OPTS="-Xms256m -Xmx1g"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
