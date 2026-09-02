# 🤝 加入我们：用 Java 重新发明 AI 编程助手

> **claude-harness-java** 正在招募 Java 开发者，一起把「Claude Code」这类的 AI 编程代理，用**纯 Java + Spring 生态**做成一个开放、可扩展、模型无关的开源 harness。
>
> 这不是一个 API 封装 demo，而是一个**完整的 agent 引擎**——已经跑起来的那些部分，你来接手会很有成就感；那些还没做的，正是舞台。

---

## ✨ 这是什么

一句话：**Anthropic Claude Code 的 Java 开源实现（harness 层）**。

它有真正的「代理大脑」而不是「聊天机器人外壳」：

- **多会话 Agent 循环** —— 队列、中断（Esc）、压缩、跨 turn 状态结转
- **完整工具系统** —— Bash（带安全 AST 解析）、文件读写、WebSearch/WebFetch、Task、ToolSearch、vision_analyze 多模态、MCP、Cron、Team、Sub-agent…
- **权限 + Hook 引擎** —— SessionStart / PreToolUse / PostToolUse / Stop，可编程拦截一切
- **记忆与压缩** —— 自动记忆提取、上下文压缩管线
- **技能 / 斜杠命令 / 插件加载** —— 已跑通插件体系雏形
- **模型无关** —— Anthropic Messages API + OpenAI-compatible（DeepSeek 等），模型/价格纯 DB 配置

后端 **Spring Boot 3.5 + SQLite + MyBatis-Flex**，前端 **React + Tauri**（`frontend/`），通过 REST + STOMP/WebSocket 实时流交互。

**上手门槛低**：你只要会 Java / Spring，就能读代码、改代码、加工具、加能力。

---

## 🚀 为什么值得加入

| 对比维度 | **claude-harness-java（我们）** | TS 脚本类 harness（如 deepseek-harness） |
|---|---|---|
| 技术栈 | 纯 Java + Spring Boot | TypeScript/Node |
| 插件/能力管理 | **Spring IoC 体系**：类型安全、依赖注入、生命周期管理、事务/安全完备 | 类「Spring 早期 XML 配 bean」的轻量自研加载器（把插件当 bean 手动组装，更简略） |
| 扩展形态 | class loader **动态加载 jar** + **GraalJS 跑 TS/JS 脚本** | 以 JS/TS 脚本为主 |
| 工程化 | Maven 统一管理、DB 主控、测试完备 | 脚本胶水层 |

**一句话讲透我们的差异化**：deepseek-harness 走的是「自己写个简版 bean 容器管插件」的老路——像 Spring 早期的 XML 时代，能跑，但安全、规范、工程化都要自己补。而 **Java 生态已经给了我们更成熟的选择**：

- **Spring IoC** 管理插件/能力 —— 依赖注入、AOP、生命周期、配置外部化，天生规范；
- **class loader 动态加载** —— 插件 jar 可热插拔，宿主与插件类隔离，安全可控；
- **GraalJS 嵌入** —— 用 Java 工程写核心，允许社区用 **TS/JS 写轻量工具/技能**，语言门槛让给脚本爱好者的同时，核心依旧类型安全。

> 所以我们不只是「又一个 AI 工具」，而是一个**想清楚插件化第二曲线**的项目——如果你对「Java 宿主 + 多语言插件」的架构有兴趣，这里有大块空白等你画。

---

## 🔭 下期路线图（Roadmap）

### ✅ 已经跑起来
Agent 循环 · 工具系统 · 权限/Hook · 技能/斜杠命令 · 记忆/压缩 · 多模态路由 · MCP · Cron · 基础 Team/Sub-agent · WebSearch/WebFetch · 实时流前端

### 🚀 下期重点（等你一起做）
1. **Workflow 编排** —— 多 Agent / 多阶段工作流的确定性编排引擎（像 CC 的 workflow：Plan → Execute → Reflect → 全局复盘），可视化 + 可恢复。
2. **Team 深度协作** —— 多成员、多会话的团队协作、消息信箱、任务认领、权限继承与冒泡。
3. **插件化体系（重头戏）** —— 上面讲的那套：**Spring 规范管理 + class loader 动态加载 + GraalJS 跑 TS/JS 脚本**，让第三方 jar 插件与社区 TS/JS 工具能像「bean」一样被发现、注入、治理。

### 你可以挑一块
- 想做**引擎核心** → workflow / 压缩 / 记忆
- 想做**工具生态** → 新工具、MCP、插件协议
- 想做**架构** → 插件 class loader 隔离、GraalJS 桥
- 想做**前端** → React 聊天界面、工具可视化、设置面板

---

## 🧰 怎么跑起来（Getting Started）

```bash
# 1. 克隆
git clone https://github.com/zouyangxiaohao111/claude-harness-java.git
cd claude-harness-java

# 2. 编译后端
mvn -q compile

# 3. 前端（可选，纯后端也能调 REST）
cd frontend
npm install
npm run dev
```

- 模型 Provider（名称、类型、base URL、API key、价格）**全在 DB / 前端设置页配置**，代码里没有硬编码 key。
- 后端 REST 在 `/api/v1`，STOMP 端点 `/ws`，启动后看日志里的端点即可联调。
- 更多架构说明见根目录 [`README.md`](README.md) 与 [`CLAUDE.md`](CLAUDE.md)。

---

## 🤝 怎么参与

1. **Fork + PR**：任何方向都欢迎，小到 typo、工具描述，大到 workflow 引擎、插件加载器。
2. **开 Issue 讨论设计**：涉及架构取舍的（比如插件隔离边界、GraalJS 桥接、DB 迁移），先开 issue 对齐再动手——这个仓库很在意「行为对齐 CC + 不搞过度设计」。
3. **认领 Roadmap**：上面积了号的任务，在 issue 里喊一声，避免重复。

### 提 PR 前的小约定
- 手术式修改：只改你任务相关的最小集合
- 日志用 slf4j/logback；改与 Claude Code 行为相关处，注释里标 `// CC original:` 溯源
- 版本变动进 `CHANGELOG.md`

---

## ❤️ 最后

我们相信：**Java 开发者值得拥有自己的 AI 编程代理生态**——不用羡慕 Node 生态的脚本 harness，因为我们有 Spring 的规范、JVM 的成熟、和一段还没人画完的插件化蓝图。

> 如果你看到这里心动了，去 [Issues](https://github.com/zouyangxiaohao111/claude-harness-java/issues) 留句话，或者直接 Fork 开干。🚀

Licensed under [MIT](LICENSE). Not affiliated with Anthropic.
