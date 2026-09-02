# claude-harness-java

**用 Java 实现 Claude Code —— Spring Boot 后端 + React/Tauri 前端的开源 AI 编程代理引擎。**

> `claude-harness-java` 用 Java 复刻了 [Anthropic Claude Code](https://github.com/anthropics/claude-code) 的 agent **harness**（代理循环、工具、权限、hooks、技能、记忆、团队、cron），配套 Web/桌面前端。**模型无关**：同时支持 Anthropic Messages API 与 OpenAI-compatible（DeepSeek 等），模型与价格全部 DB 配置，不硬编码任何 key。

> ⚠️ 这是参考 Claude Code 公开行为的**独立 Java 实现**，不包含 Anthropic 专有源码。

## 为什么值得关注

| | claude-harness-java | TS 脚本 harness（如 deepseek-harness） |
|---|---|---|
| 技术栈 | **纯 Java + Spring Boot** | TypeScript / Node |
| 能力/插件管理 | **Spring IoC**（类型安全、依赖注入、AOP、生命周期）+ **class loader 动态加载 jar** + **GraalJS 跑 TS/JS** | 类「Spring 早期 XML 配 bean」的轻量自研容器（把插件当 bean 手动组装，更简略） |
| 工程化 | Maven、DB 主控、测试完备 | 脚本胶水 |

**一句话**：deepseek-harness 走的是「自己写个简版 bean 容器管插件」的老路——像 Spring 早期 XML 时代，能跑但规范与安全要自己补。**而我们直接拥抱 Java 生态给的东西**：Spring IoC 管能力、class loader 做插件热加载、GraalJS 让社区用 TS/JS 写轻量工具。**Java 开发者不用羡慕 Node 生态的脚本 harness。**

## 界面

<p align="center"><img src="docs/screenshots/home.png" alt="主工作台" width="85%"></p>

主工作台：会话列表、流式对话、工具调用可视化、权限气泡、异步任务面板。

**以下全部是 DB 配置，界面可编辑，无硬编码 key：**

| | |
|---|---|
| <img src="docs/screenshots/model-config.png" width="100%"> | <img src="docs/screenshots/provider-config.png" width="100%"> |
| **模型注册与价格** | **提供商**（base URL / API key） |
| <img src="docs/screenshots/env-config.png" width="100%"> | <img src="docs/screenshots/skills-config.png" width="100%"> |
| **环境与功能开关** | **技能与斜杠命令** |
| <img src="docs/screenshots/plugin-config.png" width="100%"> | <img src="docs/screenshots/mcp-config.png" width="100%"> |
| **插件管理** | **MCP 服务器** |
| <img src="docs/screenshots/cron-config.png" width="100%"> | <img src="docs/screenshots/settings.png" width="100%"> |
| **定时任务** | **设置总览** |

## 功能一览

**Agent 核心**
- 多会话 Agent 循环：统一消息队列、运行中中断（Esc）、排队、上下文压缩
- 流式输出（STOMP/WebSocket）
- 模型无关 provider（Claude / DeepSeek…），DB 配置价格
- 多模态路由：文本模型经独立视觉模型分析图片与 PDF（PDF 逐页渲染为图片）

**工具系统**（对齐 Claude Code）
- Bash/Shell（安全 AST 解析）、文件读写、Glob/Grep/LSP、WebSearch/WebFetch
- 任务管理（Task*）、Workflow、Cron、团队与子代理、SendMessage
- `ToolSearch`（懒加载工具，含 openai_compatible 返回完整 schema 的回退）
- MCP 客户端（DB 配置）

**扩展性**
- 权限引擎 + Hook 系统（SessionStart / PreToolUse / PostToolUse / Stop）
- 技能 / 斜杠命令 / 插件加载
- 记忆自动提取 + 上下文压缩
- 会话级项目绑定、transcript 与 session 持久化

**前端**（`frontend/`）：React 聊天 UI（流式、工具可视化、权限冒泡、附件）+ 可选 Tauri 桌面壳 + 浏览器扩展。

## 架构

```
前端（React + Tauri）──REST + STOMP/WebSocket──▶ Spring Boot 后端
                                                    │  ChatService → AgentLoop
                                                    │   └ tools / permissions / hooks / skills / memory
                                                    │  SQLite（MyBatis-Flex）
                                                    ▼
                                             模型 API（Anthropic / OpenAI-compatible）
```

状态全部落在 SQLite（会话、消息、工具结果、模型/提供商、cron、settings），实时事件经 STOMP 推给前端。

## 快速开始

```bash
# 后端
mvn -q compile
mvn spring-boot:run        # REST 在 /api/v1，STOMP 在 /ws

# 前端（可选）
cd frontend
npm install && npm run dev
```

模型 Provider（名称、类型、base URL、API key、价格）在 **DB / 设置页**配置。

## 路线图与加入我们 🚀

**已完成**：Agent 循环 · 工具系统 · 权限/Hook · 技能/斜杠 · 记忆/压缩 · 多模态路由 · MCP · Cron · 团队/子代理基础 · 流式前端

**下期**：
- **Workflow 编排** —— 多 Agent / 多阶段确定性工作流（Plan → Execute → Reflect → 全局复盘），可恢复、可视化
- **Team 深度协作** —— 成员信箱、任务认领、权限继承与冒泡
- **插件化体系（重头戏）** —— Spring 管理 + class loader 热加载 + GraalJS 跑 TS/JS

**我们正在招募 Java 开发者** —— 想做引擎核心、工具生态、插件架构、前端都可以。详见 [CONTRIBUTING.md](CONTRIBUTING.md)，或直接 [Issues](https://github.com/zouyangxiaohao111/claude-harness-java/issues) 留言。

## License

[MIT](LICENSE)。项目代号 `nexusai`，与 Anthropic 无关。
