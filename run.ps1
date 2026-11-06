# 构建并启动 Agent HTTP 服务(JDK 21;本机默认 JDK 11,这里显式指定)
$env:JAVA_HOME = "D:\dev\jdk\jdk-21.0.9+10"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# 打包(fat-jar,含全部依赖;首次会从阿里云仓库下载 Spring 依赖,较慢)
mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "构建失败" -ForegroundColor Red
    exit 1
}

# 在当前目录运行:读取外部 agent.properties、日志写 logs\、工作区/会话目录落在项目内
java -jar target\agent.jar
