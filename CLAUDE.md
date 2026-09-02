# CLAUDE.md

## 编译命令

```shell
mvn -q compile        # 编译后端
mvn -q test -Dtest='<ClassName>' -DfailIfNoTests=false   # 跑指定测试
```

前端见 `frontend/`（React + 可选 Tauri 桌面壳）。

## 项目定位

`claude-harness-java` 是 **Anthropic Claude Code 的 Java 实现**（harness 层），Spring Boot + SQLite + MyBatis-Flex。核心目标是**行为对齐 CC**：CC 有什么架构，Java 侧对应实现（接口用 Spring，因为两种语言 + Web 端）。

**模型无关**：同时支持 Anthropic Messages API 与 OpenAI-compatible（DeepSeek 等），模型/provider/价格纯 DB 配置，不硬编码 key。

**不包含 Anthropic 专有源码**：参考的是 CC 的公开行为，非其代码。

## 核心架构

```
ChatService → AgentLoop（队列/中断/压缩）→ 工具 / 权限 / hooks / 技能 / 记忆
SQLite 承载全部状态：会话、消息、工具结果、模型/provider、cron、settings
前端经 REST + STOMP/WebSocket 订阅实时流
```

### 关键设计决策（对齐 CC 的取舍）
- **多会话 vs CC 单会话**：CC 的 `appState`/全局 settings 承载的会话级状态，Java 存 `sessions` 表列（不是全局单例）。
- **`AsyncGenerator<UnionType>` → Java record + 显式 if-else**：CC 的 `type: 'stop'|'message'` 等 union 用 record + 分支表达。
- **openai_compatible 无 `tool_reference`**：deepseek 等模型无法做 CC 的 defer_loading 激活 → `ToolSearch` 返回完整 schema 文本（`<functions>`），模型直接拿到参数调用；`nexusai.toolsearch.mode` 三态控制（`search`/`activate`/`full`）。
- **多模态路由**：文本主模型（deepseek）下，图片/PDF 经独立视觉模型分析（`vision_analyze`），主模型不接触 base64；PDF 按页渲染为图片注册缓存后逐页分析。
- **DB 主控**：功能开关（memory/compact/tool-search 等）以 DB settings/sessions 列为主控，前端可配。

## 模块速览（`src/main/java/com/nexusai`）

| 包 | 职责 |
|----|------|
| `application/agent` | agent 主循环（LlmAgentLoop）、AgentState、压缩、SkillPreloader |
| `application/agent/tool` | 工具注册/执行（StreamingToolExecutor、ToolRegistry）、全部工具 impl |
| `application/agent/toolsearch` | ToolSearch 门控（懒加载/schema/激活三态） |
| `application/agent/permission` | 权限引擎 + hook（PermissionGate/HookRegistry） |
| `application/agent/attachment` | 图片/PDF 附件缓存与处理（多模态路由） |
| `application/agent/team`、`subagent` | 团队协作 / 子代理编排 |
| `apis` | REST 控制器（Chat/Command/Settings/Mcp/Team/Stats…） |
| `domain` | 业务服务 |
| `infra/llm` | LLM provider（AnthropicSdkProvider/OpenAiSdkProvider）、模型能力解析 |
| `repository` | MyBatis-Flex mappers + entity + Flyway 迁移（`resources/db/migration`） |
| `model` | DTO / 消息模型 |

## 开发约定

- **先思后码**：修改前读现有实现与调用方；不确定先提问。
- **手术式修改**：只改必要部分，不顺手重构无关代码。
- **显式失败**：跳过步骤/测试即报告，不宣称完成。
- **日志**：slf4j/logback，中文语境；`debug` 用 `if(log.isDebugEnabled())` 包裹。
- **CC 对齐**：改与 CC 行为相关的代码时，参考 CC 的公开行为；`// CC original:` 注释标注来源语义。
- **实施批次隔离**：建议在 git worktree 中实施并验证后再合 master。
- **版本变动**：CHANGELOG.md 记录 + 询问用户版本递增。

## 发布说明

- 开源版已剥离：内部探查产物、数据库文件、CC 源码副本、本地路径与密钥。
- 提交前请再扫描一遍内部标识（公司名/本地路径/token）。
