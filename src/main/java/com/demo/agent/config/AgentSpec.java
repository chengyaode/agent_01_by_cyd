package com.demo.agent.config;

import java.nio.file.Path;
import java.util.List;

/**
 * 单个 Agent 的构建蓝图:由 {@link AppConfig#load()} 从配置解析而来。
 *
 * <p>新增一个 Agent 只需在 agent.properties 中:
 * {@code agent.list=default;新名字} 再按需添加 {@code agent.新名字.xxx} 覆盖项,
 * 未设置的项自动继承全局值(agent 名为 default 时目录也沿用全局)。
 *
 * @param name          agent 名(URL 路径 /api/agents/{name}/chat)
 * @param modelId       模型标识,如 ollama:qwen2.5:7b
 * @param modelBaseUrl  模型服务地址,null 表示用 provider 默认
 * @param temperature   采样温度,null 表示不显式设置
 * @param maxIters      ReAct 最大迭代轮数
 * @param sysPrompt     系统提示词,null 表示用内置默认模板;支持 {workspace} 占位符
 * @param workspaceDir  该 agent 的工作区(文件系统 MCP 根目录)
 * @param skillsDir     该 agent 的技能目录
 * @param sessionDir    该 agent 的会话状态目录
 * @param mcpNames      该 agent 启用的 MCP server 名(对应全局 agent.mcp.xxx 定义)
 * @param permissionMode 工具权限模式:default/accept_edits/explore/bypass/dont_ask
 * @param contextInlineChars 每轮自动注入的参考文档正文字符上限,<=0 表示不注入(只靠工具读取)
 */
public record AgentSpec(
        String name,
        String modelId,
        String modelBaseUrl,
        Double temperature,
        int maxIters,
        String sysPrompt,
        Path workspaceDir,
        Path skillsDir,
        Path sessionDir,
        List<String> mcpNames,
        String permissionMode,
        int contextInlineChars) {}
