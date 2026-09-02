# Changelog

All notable changes to NexusAI will be documented in this file.

## [Unreleased]

### Fixed

- **子代理运行状况面板**：`task_started` 按 `task_type` 过滤（仅 `local_agent` / `in_process_teammate` / `remote_agent` 登记），`local_bash` 等后台命令不再误显示在子代理面板；配套后端 `emitForegroundTerminal` 补发终态事件（已完成的 bash 不再滞留"进行中"）
- **定时任务消息重复**：`finalizeBlocks` 按 `assistantMessageId` 幂等（cron idle resume + 流式块 + 重拉双通道同一条消息不再重复插入）
- **消息 token 用量**：fallback 分支「本轮 $368」为 JSX 字面 `$` + token 数 → 改「本轮输出 368 tokens」明确单位，消除金额误读
- **流式思考块收起**：「正在思考…」块加点击收起（此前恒展开 + `div` 无 `onClick`）

## [1.4.0] - 2026-08-24

### Added

- **块级流式**：按 `assistantMessageId`（turnAssistantId）分轮渲染思考/工具/正文，三字段皆可空；complete 块直接转消息（免重拉）
- **多会话并行订阅**：切走会话不取消订阅，事件持续接收；权限弹窗按会话过滤（非本会话留队列、侧栏黄点提示）
- **权限模式（6 种：default/plan/acceptEdits/bypassPermissions/dontAsk/auto）**：全局默认（环境配置胶囊）+ 会话覆盖（Composer 模型胶囊旁）+ 三态回落（会话 ?? 全局 ?? default）
- **消息 Markdown 渲染**：标题/列表/表格/引用/代码块；无空格标题自动补空格、标题+表头粘连表格修复、代码块右上角复制按钮
- **工具卡片增强**：IN/OUT 展开（ANSI 终端输出 + 复制按钮）、状态三态（执行中/已完成/失败）、默认折叠
- **侧栏会话状态点**（运行蓝 / 等待权限黄，对齐 Harness sessionStatuses）
- **子代理运行状况三态模块**（进行中/已完成/已停止 + 计数，点开弹窗时间线清单）+ 活动历史**会话级 localStorage 持久化**
- **权限弹窗增强**：user_abort 通道（删除会话中止 pending 权限 + 弹窗「中止」按钮）、workerBadge 彩色徽标、等待时长、工具参数展示
- **异步任务统一停止**：teammate/子代理/workflow kill 端点对接 + 任务 tab「异步任务」清单（4s 轮询 · 会话隔离 · 全部停止 · 查看更多弹窗）
- **Esc 连按两次停止所有任务**（turn 运行中 3s 窗口 · 对齐 CC killAllAgents 连按确认；空闲时仍为压缩/裁剪弹窗）
- **权限分类器模型**（模型页档位角色卡 · classifierModel · 留空回落主循环）
- **每会话独立输入草稿**（切换会话不串扰，切回恢复）
- **字体对齐 Harness 系统栈** + 字号提升；工具卡片/代码用现代 sans
- **项目创建/绑定绝对路径校验**（isAbsolutePath，杜绝相对路径污染会话 cwd）
- **cron 定时任务触发系统通知**（scheduled_task_fire：❋ 任务执行中横幅 · 对齐 CC SystemTextMessage.tsx:137）

### Fixed

- 多会话 activeStreams 生命周期竞争（wasRunning 推断移除 vs 登记）→ 换会话卡住根因，改 complete/cancel 明确回调
- 思考展开回到底部（滚动跟随重写为流尖签名 + 贴底记账）
- 打字光标只保留在最新流式块（多块不再全部闪烁）
- 工具状态误标「已完成」（无 OUT 时显示执行中）
- 未闭合代码块降级为普通文本（AI/系统偶发 ```text 无闭合不再吃到文本末尾）
- 打字机光标移除（AI 回复流式不再闪烁 ▍ · 输入框不受影响）

#### 追加（2026-09-01 · 未递增）

- **对话「回到最底部」按钮（输入框工具栏右对齐）**：上滚离底时 Composer 顶部工具栏（right-tools，与权限/模型同行）显示「回到底部」chip，点击滚回底部、近底自动隐藏——MessageList `onScroll` → `onNearBottomChange` 状态提升 → App `chatAtBottom` → Composer `showToBottom`；点击 `onScrollToBottom` 触发 `scrollSignal`（对齐 deepseek-harness ChatView toBottom · MessageList/Composer/App · zcw）
- **slash 分发重构（未命中命令不再吞消息）**：技能命令 / `/plugin:skill` / 带描述或完整补全的指令（如 `/import-cc 导入配置`、`/zjkycode:brainstorming `）→ **作为普通消息发送**给模型（SkillTool 处理，不再弹面板丢失）；纯命令名未命中（打错、无空格）→ 弹命令面板；修复 `remoteCmdNames` 技能命令被末尾 `return` 拦截不发请求的 bug（App.tsx · zcw）
- **`/plugin:skill` 斜杠命令支持（CC 格式）**：输入 `/zjkycode:brainstorming` 提示并触发——cmdMatches 拆冒号精确匹配插件名 + 技能名前缀（`/zjkycode:` → 该插件全部技能）· Tab/点击补全保持插件前缀（`/pluginName:skillName`）· App 分发解析冒号取技能名，命中则作为消息发送（保留原样，模型 SkillTool 触发），未命中弹命令面板（Composer/App · zcw）
- **工具卡「转后台」按钮 + Ctrl+B 快捷键（对齐 CC task:background）**：运行中的前台 Bash/Agent 工具卡按 toolUseId 关联 taskId 显示「转后台」（isBackgrounded=true 不显示）→ `POST /tasks/{id}/background`；**Ctrl+B** 全局快捷键 → `backgroundAll(activeSessionId)` 当前会话全部前台任务转后台 · 后端按类型自动分发（bash 就地翻转 / agent 翻转）· tasks.ts `background`/`backgroundAll` + MessageList ToolCard + App.tsx keydown + globals.css `.tc-bg`（zcw）
- **异步任务面板只显示真异步后台任务**：`/tasks` list 过滤 `isBackgrounded=false` 的前台同步任务（如 Bash 工具已在对话工具卡展示，不重复出现在右侧异步任务面板）· tasks.ts `BackgroundTaskDto` 补 `isBackgrounded` + AsyncTasksPanel 过滤（`!== false` 兼容旧后端；后端 `TaskDto` 补字段提示词已交付 · zcw）
- **cron 后台流式顺序修复（方案 A · 前端部分）**：新增 `message.user` 事件消费——后端推 cron/Ask 后台落库的 isMeta user 消息 → chatStore `appendMetaUser` 占位进 messages（isMeta 不显示但保 flow 顺序）· 配套后端发 cron `message.complete` 收口 → cron 块不再残留 streams、不被后续 turn 混收口倒挂（types/socket/chatStore/useChatSocket · 后端提示词已交付 · zcw）
- **当前上下文占用感知配色**：footer 当前上下文正常灰（不打扰）· ≥80% 琥珀黄（warn）醒目预警 · ≥95% 红（hot）接近上限；与金额橙红（hu-cost）天然区分（Composer/globals.css · zcw）

#### 追加（2026-08-31 · 未递增）

- **空会话判断改后端权威**：新建会话拦截改用 `session.messageCount`（sessions 表消息计数）——前端 messages 未加载的会话（有历史但本端未拉）不再误判为「空会话」误拦截（App.tsx createSession/onCreateInProject 两处）· footer 顺序定稿「t/s → ⚡累计 → 当前上下文 → ¥金额」（Composer · zcw）
- **footer 累计/上下文顺序调换**：hint-usage 由「当前上下文 → 累计」改为「⚡累计 → ¥金额 → 当前上下文」（Composer · zcw）
- **会话累计金额/Token 不被失败轮覆盖**：complete 事件 0 cost / 空 modelUsage 的失败轮不再用 0/undefined 覆盖已持久化的会话累计（chatStore updateSessionUsage 只合并非 null 字段 + useChatSocket total_cost_usd>0 才更新）· 补完 762f0e0 的流式思考收起接线（collapsedStreamReasoning 按块 id 收起，此前 state 声明未用报 TS6133）（useChatSocket/chatStore/MessageList · zcw）
- **API 错误改在对话流展示（对齐 CC assistant API error）**：message.error 事件不再走顶部通知栏，改为对话流助手回复位置渲染红色错误卡（模型调用失败 + 错误详情 · .api-error-card）· chatStore 加 apiErrors/addApiError/clearApiErrors · 按 userMessageId/assistantMessageId 锚定到对应 flow，无 flow 兜底渲染末尾 · 新 user 消息发送时清除上一轮错误卡（配套后端已补发 message.error）（chatStore/useChatSocket/MessageList/App/globals.css · zcw）
- **HTML 代码块一键运行预览**：AI 回复的 html 代码块右上角新增「运行」按钮（复制按钮左侧 · 强调色区分）→ 点击弹窗 sandbox iframe 预览（allow-scripts 但【不含】allow-same-origin → 不透明 origin 隔离宿主，脚本可跑但无法访问宿主 DOM/存储）· 弹窗支持重新加载/关闭 · 用户消息/助手正文/流式块三处代码块均生效（MessageList MdContent 注入「运行」按钮 + HtmlPreviewModal + globals.css .code-run/.html-preview-* · zcw）

#### 追加（2026-08-30 · 未递增）

- **定时任务归属标注**：设置页定时任务行显示归属徽标（会话·agent / agent·id / 项目·xxx / 全局）· types.ts Schedule 补 sessionId/agentId/boundProject 字段（zcw）
- **命令 type 徽标升级（40 命令契约）**：CommandDto 补 type 字段（prompt/local/local-jsx · 后端 M23 透出）· 面板 type 徽标中文化（提示注入/本地/面板）· 无 type 命令仍降级来源徽标 · prompt 型（commit/commit-push-pr/statusline/review 等）与 local 型（advisor/cost/files/rename 等）slash 触发统一作为消息发送由后端路由执行（prompt→模型 SkillTool / local→UserInputDispatcher 拦截）（zcw）
- **命令面板展示增强**：CommandDto 补字段（kind/aliases/argumentHint/builtin/isHidden/whenToUse/userInvocable）· 面板图标兜底（未注册命令显示终端通用图标，不再空白）· type 徽标对无 type 命令降级显示来源（内置/捆绑/插件/用户）与插件名（如 插件·zjkycode）· Composer `/` slash 菜单显示所属插件小字（zcw）
- **slack 提示合并后端技能 + 插件名匹配**：Composer `/` 即时提示合并 GET /api/command 技能命令（输入 /debug、/update-config 有提示）；输入 `/插件名`（如 /zjkycode）显示该插件全部技能（后端 CommandDto 加 pluginName 透出插件归属）；⌘K 命令面板同步合并；技能命令回车作为消息发送后端触发（不再弹空面板 · zcw）
- **压缩数值配置显示真实默认值**：11 项 placeholder 显示默认值（默认 10/5/10000/5/40000/10000/5000/3/3/3/2 · 留空即用）+ desc 补全默认值说明（会话笔记初始化 10000 / 更新间隔 5000 / 工具调用 3 · zcw）
- **环境配置页全部默认值补全**：time-based-MC gap 默认 60 / keep 默认 5 · 小模型总结默认关闭 · 自动记忆/自动精简记忆默认关闭 · Agent Swarms 默认关闭（WebSearch 引擎/baseUrl/代理/API key/域预检、记忆目录、离开摘要、autoCompactWindow 已有默认标注 · zcw）

#### 追加（2026-08-29 · 未递增）

- **Monaco 代码查看/编辑集成**（拍板 Monaco）：FileViewModal 改 Monaco（默认只读查看 · 按扩展名语法高亮 java/yaml/md/php/... · 「编辑」切写回保存）+ DiffModal 改 Monaco Diff Editor（并排/统一切换 · 行内高亮+行号+折叠导航）+ 新依赖 monaco-editor + vite worker 配置（相对路径 import 绕过 monaco-editor 0.56 exports `./esm/vs/*.js` 双重路径 bug · zcw）
- **后端写文件 API**：PUT /api/v1/projects/{id}/file（ProjectService.writeFile 复用 resolveProjectFile 防路径穿越 · 供 Monaco 编辑保存写回 · zcw）
- **UI 纯白统一**（夹带 2eb6340）：用户消息气泡淡蓝 #EEF2FF→纯白+输入框同款阴影 · 工具卡片 head 暖米→白 · team 卡/收件箱/空态暖米→白 · 主对话 canvas→纯白 + 暖色光晕降弱（zcw）
- **F4 t/s 速度迁至底部 footer**：消息作者行移除 · hint-usage 显示末条 assistant 消息速度（夹带 2eb6340 · zcw）
- **docs/code-viewer-compare.html**：代码查看器选型对比页（Prism / shiki / Monaco 三方案真实渲染 + diff 展示对比 · 主题切换 · zcw）
- **压缩配置大模块包裹**：设置页环境配置压缩区由 6 个平铺域卡片改为「压缩」外层大卡片 + 6 机制域子卡片各自阴影（.envc-group + .envc-domain 嵌套 · zcw）
- **UI 文案去「对齐 CC」术语**：压缩开关/数值 desc 由「对齐 CC xxx · 功能」改为直接解释功能（EnvConfigPanel 12 处 · 代码注释保留对照 · zcw）

#### 追加（2026-08-28 · 未递增）

- **发送后立即显示「思考中」占位**：用户消息发出即渲染 nexus 思考中气泡（脉冲动画），后端首个 chunk 到达切换打字机，消除发送后空白间隙（App/MessageList/globals.css · zcw）
- **定时任务 2s 轮询**：AI CronCreateTool 创建后自动显示，无需 F5（useSchedules 轮询对齐 AsyncTasksPanel · zcw）
- **空态背景主题化**（zcw）
- **任务模块三态统计 + 模块阴影卡片 + 弹窗样式统一**：异步任务/子代理/团队/任务清单/定时任务五模块移除折叠，进行中/已完成/已停止三态计数 + 点击弹窗（.subagent-modal 统一）· Workflow 颜色对齐 CSS 变量（zcw）
- **子代理详情（transcript 弹窗）**：GET /sessions/{sid}/subagents/{agentId}/transcript，AgentContext.unpackAgentId public 化（后端 SubagentController · zcw）
- **重试期停止键**：重试时发送键变停止键（turnRunning 驱动 · zcw）
- **complete 清除重试横幅**：api_retry 后成功不再残留「正在重试」（useChatSocket complete 分支 setRetry(null) · zcw）
- **task.notification 结构化事件对接**：/topic/tasks 订阅按会话过滤，completed/failed/stopped 徽标；后端空闲路径（CronIdleExecutor）也推终态（对齐 CC · zcw）
- **定时任务详情弹窗**：ScheduleModal 点开列表项 → ScheduleDetailModal（调度方式 cron 人话/单次/间隔 + 原始 cron + 上次运行 + 描述 + 命令 prompt · 复用 subagent-modal 样式；替代原「功能未开放」alert · zcw）
- **定时任务轮询内容守卫 + 去重**：useSchedules 数据未变不 setList（对齐 AsyncTasksPanel:58）——消除 2s 轮询新数组引用触发 RightPanel 任务 tab 整块重渲染卡顿；顺带按 id 去重（zcw）
- **V54 压缩数值配置**（EnvConfigPanel + types AppSettings）：settings 表新增 11 个压缩数值列（cached-MC 触发/保留 + SM min/max/笔记间隔 + 熔断重试上限），空=null 回落后端默认，走 buildCompressionDto 写整 DTO null 不覆盖（zcw）

#### 追加（2026-08-26 · 未递增）

- **会话 token/金额汇总**（输入框底部 footer 对称位）：会话累计 token + 金额（sessions 表持久化 · F5 不消失 · complete 事件实时覆盖）
- **AskUserQuestion 手动填写**（每题含多选 · 自定义答案优先于选项）
- **cron/Ask 实时流修复**：当前会话 stream topic 常驻订阅（cron 触发 / Ask 续跑后端主动推流不再丢）
- **AskUserQuestion 双弹窗修复**：bridge 事件 displayInput 字段错位 + 订阅 dismiss 清残留 + 渲染优先 message
- **AskUserQuestion 多选兼容**：multiple_selection / multi_select snake_case 字段渲染 checkbox
- **每轮累计金额移出消息**（错乱 143$ 修复）→ 金额集中底部 footer 汇总
- **REST 轮询统一 2s**（异步任务 / 子代理 / 团队 / 待办）

## [1.3.0] - 2026-08-23

### Added

- **主界面三栏整体重构**（对齐设置页原型 · 白底 + 橙主题 #FF7A3D/#E65C00）：
  - 顶栏纯白 + 左/右栏白底发丝线；左侧会话项 active 橙、分组展开/收起颜色区分
  - 输入框区：950px 宽 + 文本域 60px/200px 动态增高 + 顶部工具栏（文件选择 + Mode 下拉 simple/full 默认 full）+ 底部工具栏（附件/计划模式 + 模型 pill + 渐变橙发送）+ 快捷键栏左对齐
  - 欢迎页 logo 浮动动画 + quick-actions 快捷卡片；右侧三 tab 平分 + active 橙（card-box 白卡片统一内容）
- **附件功能（图片/PDF 多图拖拽）**：Composer onDrop/选择 → base64（≤5MB）/ 大文件 upload → AttachmentRequest 结构化契约；缩略图预览/删除/放大
- **effort 会话级 + ultracode**：SessionDto.ultracodeEnabled（V32）；EffortModal 淡青渐变条 + ultracode 第 6 档直接传后端；/effort 命令写当前会话
- **右栏拖拽调宽**：分割线 handle（fixed 于 .app 层），220–640px，宽度 localStorage 持久化
- **Inter 字体本地引入**（@fontsource/inter，避免运行时网络拉取）
- **Tauri 窗口按屏幕 85%** + 启动恢复
- **降级模型角色**（模型选择器第 10 角色 → settings.fallbackModelName；主模型失败/限流时降级）
- **自动记忆配置**（模型选择器环境区：autoMemoryEnabled 开关 + autoMemoryDirectory 路径，开关关 → 路径禁用）
- **Workflow 运行面板**（右栏任务 tab：GET /workflows/runs + 4s 轮询 + 点开展 phase/agent 详情）
- **/context analyze 展示**（/context slash → 前端直连 REST 分类计数弹窗：system/memory/tools）
- **重试提示 UI**（浅橙 pill + spinner + 倒计时徽章）
- **压缩警告抑制态展示**（token_warning 事件：上下文快满/压缩被抑制时提示横幅，suppressed 控制显隐）
- **设置页「环境配置」tab**（压缩窗口/自动记忆/away-summary 门控；away 门控两开关本地 localStorage，后端 features API 补后接入）
- **设置页「模型」tab**（全局档位配置含主模型：快速/子代理/弱/中/强/降级/多模态/TTS/ASR；新建会话默认用全局主模型）
- **会话历史栏相对时间**（今天/昨天/前天/N天前 · 右对齐 · hover 删除按钮顶掉时间）
- **MCP 展示名**（userFacingName 优先，回落 name）
- **away-summary 门控读后端 features**（GET /api/v1/features 两开关都开才触发）
- **token_warning 独立 topic 订阅**（/topic/sessions/{id}/token-warning）
- **/context analyze categories 展示**（分类段 + 技能统计）
- **记忆编辑器**（设置页「高级」记忆编辑 + /memory 命令 · GET /memory/files + PUT /files；marked/dompurify 依赖）
- **设置页「模型」tab 对齐 deepseek_html 8aff29**（角色卡片 + 降级单独行 + 选中阴影 + 管理 Provider footer）
- **会话操作菜单**（hover ⋯ → 重命名/删除）+ 创建会话去硬编码 title + 摘要标题自动更新
- **消息时间 HH:MM + token 用量样式**
- **对话布局对齐**（stream 全宽 + 用户右对齐 + 15px 现代字体）+ 思考 null 过滤

### Changed

- **设置页对齐原型**：提供商卡片/toggle 44px/logo 色块/模型 item/icon-btn/渐变橙按钮；左侧导航 active 橙条；内容区 #F9FAFB
- **编辑模型隐藏「高级 · JSON」**（注释保留）
- **模型切换修复**：pickModel 用实际会话 id（activeSessionId 与后端 storeSessions id 不匹配导致更新落空）
- **契约同步**：Model/requests 移除 contextWindow（V30）；types AttachmentRequest 结构化；SessionDto effortLevel/ultracodeEnabled
- **模型选择器 v3.1**（对齐 deepseek_html 设计：降级角色后置+分割线、环境配置 label/control 分行、彩色 model-tag 方块、Inter 字体、搜索框保留）
- **主/快速模型配置持久化**：pickModel PATCH 会话 + pickFast settings.fastModelName；配置后选择器不自动收起（用户主动关闭）
- **新会话默认主模型**：优先 settings.mainModelName（不再回落 DeepSeek-Chat）
- **编辑提供商 key 防污染**：key 未修改不发送（避免掩码 sk-**** 当明文存库）
- **away-summary 门控默认关 + 去重防抖**（useAwaySummary；后端 features API 补上后接入）
- **主模型档位继承**：选中主模型时未配置的子代理/弱/中/强自动继承（主模型多模态 → 多模态继承；tts/asr/fallback 不继承）
- **模型选择页选中阴影**：active 角色卡加橙色 ring 光环 + 更强投影（当前配置角色一眼可辨）
- **模型选择页移除环境配置区**（统一移到设置页「环境配置」）
- **模型选择重构**：对话框只选会话主模型（临时会话级，不写 settings）；全局档位（含主模型）统一在设置页「模型」tab；主模型档位继承移除
- **精简模式 bareMode**：输入框 Mode 下拉（full/simple）联动 bareMode（PATCH 会话）
- **权限弹窗危险命令警示**（PermissionRequestEvent.warning → 黄底警示块）
- **WebSearch summary 展示**（弱模型总结文本，原始结果不进模型）

### Removed

- 默认项目目录字段（通用 tab）
- 分组/会话项 SVG 图标（纯文字列表）

### Added

- **项目 tab 文件树**（IDE 项目结构 · 用户拍板 · tsc 0 / vitest 31 全过 + 后端测试 5/5）：
  - 后端 `GET /api/v1/projects/{id}/files`（ProjectService.listFiles：git ls-files → 目录树，目录在前文件在后，FileNodeDto）
  - 前端 projectApi.files + RightPanel 项目 tab 新增文件树（ProjectFileTree：目录展开/折叠 + 缩进层级 + 文件点击查看 diff）
  - 保留主项目卡（绑定项目切换），移除关联项目列表

- **模型选择器 v3 落地前端**（9 角色 + 触发时机 · 用户拍板 · tsc 0 / vitest 31 全过）：
  - `ModelPickerModal.tsx` 重写：9 角色卡（主/快速/子代理/Haiku/Sonnet/Opus/视觉/多模态/ASR），每卡显示作用描述 + ⏱ 触发时机 + 「后期有用」徽标（ASR）
  - 主模型→pickCurrent / 快速→pickFast 走既有契约；其余 6 角色本地暂存（待后端配置端点）
  - 搜索框 + 类型筛选 tab（全部/对话/多模态/视频/语音TTS）+ 按 Provider 分组（p-type/p-count）
  - 已配置态：terracotta 左条 + 淡底色 + 「已配置」徽章
  - globals.css 加 mp-v3 角色卡/搜索/类型筛选样式

- **模型选择器设计稿 v2**（`docs/design-model-picker-v2.html` · Claude+Mac 融合 · minimax 9/10 定稿）：
  - **Claude 基调**（暖米色 #FAF9F5 + 陶土橙 #CC785C + serif 标题）**融合 Mac**（磨玻璃弹窗 blur24 + 大圆角 20px + 柔和阴影 + spring 动效 + 克制留白）
  - 6 角色独立配置：主模型 / 快速 / 子代理 + **Cascade 模型族**（Haiku/Sonnet/Opus 折叠为一张卡，内部 pill 切换协议档位，均可单独绑定 provider 实现）
  - 「协议 ≠ provider」心智：配置后卡内显示 `Sonnet 协议 · 当前实现：MiniMax-M3`，点透档位与实现分离
  - 已配置态：terracotta 左竖条 + 淡底色 + 「已配置」徽章三重反馈
  - 筛选：搜索框 + 类型 tab（多模态/视频/语音TTS）+ 按提供商分组

- **模型选择器设计稿**（`docs/design-model-picker.html` · 用户需求 + minimax 8/10 定稿）：
  - 角色区：6 个后端 env 可配置模型角色卡（主模型 ANTHROPIC_MODEL / 快速 SMALL_FAST / 子代理 SUBAGENT / Haiku / Sonnet / Opus），未配置角色显示「跟随主模型」+ 级联链路（主模型 → Haiku）避免同名困惑
  - 按提供商分组选择（Provider 头 + 模型数，去 API key 泄露）+ 类型筛选 tab（全部/对话/多模态/视频/语音TTS/向量）+ 搜索框
  - 模型类型支持多模态/视频/TTS/向量（对齐后端 ModelType：chat/text/vision/multimodal/image_generation/embedding/audio）
  - 交互：点击模型 → 应用到当前选中角色（实时更新角色卡）；「管理模型 / Provider →」入口

- **设计稿 v7 落地前端·布局修正**（用户反馈 · minimax 确认居中/尺寸合理）：
  - 空态：MessageList 返回 null 释放空间 + `.stream:empty` 高度塌陷 + hero 标题移入 Composer empty 态，欢迎语+输入框一体垂直居中（实测 diff 17px）
  - RightPanel 移除「当前会话/上下文」right-header
  - project-binder 移入 composer-inner 内部，与输入框左对齐（实测均 left 320）
  - 对话态 composer 底部间距 16→32px（对齐设计稿）

- **设计稿 v7 落地前端**（用户拍板 · tsc 0 / vitest 31 全过）：
  - 布局：移除 StatusBar + grid 改 2 行（topbar + main），左右栏保持 Mac 圆角
  - 顶栏：MenuBar 菜单包 `.menu-wrap` 圆角容器，移除右侧搜索/皮肤设置 icon-btn（菜单项保留）
  - 中心区：新增「对话/轨迹」双标签（active accent 下划线 + 轨迹计数），轨迹 = 新建 `TraceView` 组件（从 storeMessages 派生 dsh 式记录列表：user/assistant/tool 按 turn 分组，多工具类型色条 bash/edit/read/glob/write/task）
  - 右栏：`tracks` tab → `tasks` 任务 tab（RightTab 类型 + reducer 同步改），展示子代理运行状况（subagentStore 身份）+ 定时任务（useSchedules 真实数据），点击 alert「功能未开放」占位；保留文件/项目 tab
  - 验证：`npx tsc --noEmit` 0 错误、`npx vitest run` 31/31 全过、playwright 实测 center-tabs=2 / right-tabs=3 / statusbar=0

- **设计稿 v7 交接文档**（`docs/设计稿-v7-交接.md`）：记录全部已拍板 UI 决策（布局/顶栏/左栏磨玻璃/对话轨迹双标签/输入框复用现有前端/右栏三 tab 圆角阴影/数据源/落地任务清单），下个会话可直接读取开始设计
- **设计稿 v7 定版**（`docs/design-mockup-v7.html` · bubble 四角全圆角 + assistant meta-line 在上气泡在下 + nexusai 标识）：
  - 用户全部反馈已合入：输入框 760px / 左栏磨玻璃 / 新会话居中 / 添加工作区 tooltip / 菜单圆角 / right-tabs 圆角阴影 / project-binder 与 input-box 左对齐
- **设计稿 v7**（`docs/design-mockup-v7.html` · 用户反馈定版 · minimax 8/10）：
  - 中心区「对话/轨迹」双标签；轨迹 = dsh 式记录列表（按 turn 分组，user/assistant/tool 彩色标签 + 工具图标 + 时间戳）
  - 右栏恢复「文件/任务/项目」三 tab；任务 tab = 子代理运行状况 + 定时任务卡（点击 alert「功能未开放」占位）
  - 移除 statusbar / 顶栏搜索 / 皮肤设置图标；保留模型选择器（provider+模型+type 角标）
  - 对话 tab 内嵌 composer 输入框（附件/计划模式 chip + 发送按钮）

- **设计稿 v6**（`docs/design-mockup-v6.html` · 后端真实能力探查后重做）：
  - 中心区「对话/轨迹」双标签头（复用未挂载的 CenterTabs 范式，active 下划线 + 轨迹计数徽标）
  - 模型选择器展示真实 providers/models 结构（provider 名 + 模型名 + type 角标如 multimodal）
  - 右栏「追踪视图」：子代理运行状况卡（running/done/failed 三态 + 汉字 avatar + token/时长 mono 元数据）+ 定时任务卡（lastRunStatus 状态点 + cron 人话 + 子代理归属标签）+ 实时追踪流（统一 type 徽标 schema）
  - 状态栏信息密度：git 分支 + 模型名 + token 计数
  - 设计依据：后端探查（models/telemetry/subagents/schedules 4 项真实能力 + 前端现状），minimax 视觉评分 8-8.7/10 定版

- **Phase 0 对接**（cron 修复 / 会话主链路 / MCP 审批 / /topic/tasks）：
  - 会话历史回放：切换会话调 `chatApi.listMessages` 加载后端历史（含竞态防护）
  - 生成中取消：Composer 停止按钮 + Esc → `chatApi.cancel`
  - 消息删除：消息 hover 删除入口 → `removeMessage` 后端 + 本地同步
  - 创建会话改走后端 `sessionApi.create`
  - MCP：`import`/`approve`/`reject` 端点 + 「导入 .mcp.json」+「待审批」弹窗
  - STOMP `/topic/tasks` 订阅（task_* 事件 → 本地通知，兼容数组/单对象载荷）
  - types：Schedule 去 `enabled`、McpStatus 加 `pending`、TaskEvent 类型族、`TASKS_TOPIC`
- **测试**：chatStore 补 3 个（删除消息 / 跨会话隔离 / clearStream）
- **Phase 1 对接**（消息渲染 / AskUser / 输入压缩 / MCP 启停）：
  - 消息渲染：isMeta 元消息隐藏（续写/nudge）、模型降级警示横幅（role=system+informational）、apiError/errorDetails 错误展示
  - AskUser：权限弹窗支持单选/多选问题（toolInput.questions）+ answers/annotations 回传闭环
  - 输入压缩：`sendMessage` 支持 attachments（PDF 路径通道）、双击 Esc 门控补「输入框为空」、partial-compact 后 conversationId 落 store、`up_to` 方向按 build 隐藏
  - MCP 启停：`start`/`stop`/`test` 真实端点接线 + 逐行启停/测试按钮 + 批量启用/停用
- **测试**：chatStore 补 setConversationId
- **Phase 2 对接**（命令框架 / 状态 UI / Settings / Project）：
  - 命令面板（CommandPalette · Raycast 风格）：`/` 或 ⌘K 打开，搜索过滤，↑↓/Enter 执行内置命令
  - 通用 slash 解析：命中内置命令直接执行，未知 `/xxx` 打开命令面板（不再 toast）
  - `session.status` 驱动 StreamHeader 状态点（思考中/进行中/就绪）+ StatusBar WS 连接徽标（四态）
  - Settings REST：新建 `api/settings.ts`，SettingsModal 主题/字号/动画落后端读写
  - Project REST：AddProjectPanel/RightPanel 用真实项目列表（list/create/remove）
- **测试**：chatStore 补 setAgentStatus
- **Phase 3 对接**（子代理 / hooks 已就绪项）：
  - 子代理：消息作者区显示子代理名 + 颜色点（本地 `SUBAGENT_THEME_COLORS` 映射）
  - `/agents` 命令 → AgentsPanel（`GET /api/agents` 展示）
  - `task_started` 显示「子代理启动」通知
  - skill improvement survey：订阅建议事件 + SkillSurvey 弹窗 + suggestion/decision REST
  - matchedRule「已自动批准」徽标（`ChatMessageDto` 顶层字段，对齐后端）
  - getAllHooks 展示：`api/hooks.ts` + SettingsModal「Hooks」tab + HookPanel
- **测试**：无新增（matchedRule 字段对齐既有构造）
- **Phase 4 对接**（P2 增强）：
  - tool 渲染：MCP 显示名剥离 `mcp__<server>__` 前缀（FM-14）、`isDestructive` 危险标红（F21）、outputTokens compact 用量小字（F37）、finishReason 退出角标（F30/F33）
  - StreamHeader 增加 tokens 用量 + 重试状态次级展示
  - SockJS 回退：`webSocketFactory` 原生 WS 优先、失败回退 `/ws-sockjs`（新依赖 `sockjs-client`）
  - cron 人类可读文案：`cronToHuman`（每天/每月/每 N 分钟，未识别回退原文）
  - `/resume` `/color` 命令就地反馈（fail loud）
- **测试**：新增 `utils/format.test.ts`（compactNumber 9 断言）
- **Phase 5 对接**（前端可做 P3 项）：
  - 输入框 token target 高亮：Composer overlay 技术高亮 `+500k` 等关键词（F36）
  - MCP 通道白名单管理（FM-17）+ 导入 blocked/suppressed 明细（FM-9）+ trust dialog 接受入口（FM-15）
  - 业务面板 BusinessPanel：Branch 列表/创建/保留/删除 + Export markdown 导出/复制/分享 + Doctor 诊断（`api/business.ts`）
- **测试**：无新增（Phase 5 功能为 UI/API 接线，既有 31 用例全过）
- **Phase 6 对接**（前端可立即执行 5 项 · 2026-08-19 拍板）：
  - 会话-项目绑定策略（#1）：未绑定拦截发送 + createSession 带 mainProjectId + SessionList 按项目分组 + 项目内「+」新建 + `handleBind`/`handlePromote` 走 `projectApi.bind` + `unbindProject` 纯本地（后端单 main 模型）+ realProjects 缓存
  - 事件类型补齐（#2）：CompactProgressEvent/TurnDurationEvent/MessageTombstoneEvent/McpConnectivityEvent/HookProgress/AttachmentMessageDto/ToolMeta/PromptSuggestion（未接线项仅声明，优雅降级）
  - 子代理身份渲染（#4）：`subagentStore` 从 /topic/tasks task_started 按 tool_use_id 登记 + MessageList 按 `msg.toolCallId` join 渲染 `● @agentName` 带色
  - token 显示（#5）：output_token_usage attachment 三值（turn/session/budget）compact + MessageCompleteEvent/chatStore 透传 attachments + 回落 outputTokens
  - 零遗留修复：onCreateInProject 签名错（阻断）/ unbind 语义错配 / handlePromote 后端漂移 / 子代理 join key 断裂 / subs 无界增长 / 右面板 main 漂移 / attachments 链路断 / selector 注释不一致

### Fixed

- cron 更新动词 `PATCH`→`POST`（后端为 POST 部分更新，修复 405）
- `SchedulesPanel` 停止发送/展示后端已删除的 `enabled` 字段（移除启停 toggle）
- `rest.ts` 空响应体解析（`POST /cancel` 返回 202 无 body 不再抛错）
- AskUser 数据流：`useChatSocket` 透传 `toolInput` 进 permissionQueue + `App.tsx` 转发 answers/annotations（修复端到端不可达）
- 未知 `/xxx` 命令行为：由「未知命令 toast」改为打开命令面板（⌘K 从搜索面板改绑命令面板，搜索仍可从 MenuBar 进入）
- matchedRule 字段对齐：从 `ToolCallDto`（后端无该字段）移至 `ChatMessageDto` 顶层（后端 `ChatMessageDto.java:155` 出站），修复「已自动批准」徽标形状漂移
- **MCP 命令行快速解析**：MCP 面板添加卡顶部「解析填入」（`src/utils/mcpCliParse.ts` 纯函数：shell 分词 + 形态 A `claude mcp add <name> [flags] [--] <cmd|url>` / 形态 B 纯命令自动推导 name）；支持 `--transport/--scope/-s/--env/--header/--`，解析结果填入现有表单走既有 POST /mcp（zcw）
- **C1 leader 队友权限确认弹窗**：权限请求识别 `reason.reason==='leader_inbox'` → 弹窗加「swarm · 队友请求」徽标 + worker 名（后端补 workerName 前从 description 启发式提取）；允许/拒绝仍走既有 `/app/sessions/{id}/permission-response`（zcw）
- **Team/swarm 面板设计文档**：`docs/team-panel-design.md`（实测核验）——C1 已实现；B1 teammate 消息流 / Team 生命周期 REST / 会话级 teamContext 三项设计 + 后端待补清单（含完整文件路径）（zcw）
- **Team 协作面板（右栏任务 tab）**：`SessionDto.teamContext` 门控（非 null 才显示）→ `teamsApi` 8 方法（list/get/create/remove/addMember/removeMember/inbox/markRead）+ `useTeamStore`；创建/解散（409 文案透出）/移除成员/收件箱消息流；STOMP 订阅 `/topic/teams/{name}/status`+`/messages`（created 刷新 / deleted 清空 / 消息气泡+未读角标）；SVG 团队头像对齐项目图标规范（zcw）
- **Team STOMP 方案3 适配**：订阅 topic 改 `/topic/sessions/{leadSessionId}/team-status`+`/team-messages`（按 lead 会话推送，多会话互不干扰）；`useTeamStore` 加 `leadSessionId` 订阅键，`SessionTeamContext` 透出 leadSessionId（zcw）
- **消息列表滚动跟随**：流式增量仅贴近底部时跟随（看历史不拽）；用户发送无条件跳底部；上滚查历史停止跟随（阈值 60px）（zcw）
- **Agent Swarms 设置化门控**：设置页「环境配置」Agent Swarms 开关（写 settings.agentSwarmsEnabled）+ `useTeamStore.agentSwarms` 乐观同步；TeamPanel 门控 `features.agentSwarms && teamContext`，开关实时显隐（zcw）
- **Team 添加成员 + 门控实时响应**：状态卡「+ 添加」→ FormModal（agentId/name/agentType）→ POST /members；门控移入 store（设置页开关切换 TeamPanel 立即隐藏，不再挂载一次性）（zcw）
- **Todo 状态展示面板**：右栏任务 tab「任务清单」（`todosApi` REST 兜底 + STOMP `/topic/sessions/{id}/todos` 整体替换）；三态渲染 pending ○ / in_progress ● + activeForm / completed ✓（zcw）
- **ANSI 终端输出渲染**：移植 DeepSeek `ansi.ts`（MIT，依赖 anser）→ `src/utils/ansi.ts`（主题 token 适配 NexusAI 变量）；ToolCard OUT 行彩色渲染（parseAnsiLines）+ 超长 16 行 head-tail 截断 + 复制按钮（zcw）

## [1.2.0] - 2026-08-22

### Added

- **cron 调度命令（prompt）可编辑**（SchedulesPanel · 用户拍板）：命令字段从硬编码 `test（v1 硬编码）` 改为可编辑 textarea，添加/编辑表单 + 请求构造补 `command` 字段（后端 Schedule.command 接受）
- **轨迹视图不展示对话输入框**（App · 用户拍板）：Composer 仅在 `centerView === 'chat'` 渲染，轨迹 tab 只显示 TraceView 自身空态「该会话暂无轨迹」
- **窗口最大化**（TitleBar / tauri-bridge / capabilities）：capabilities 补 `core:window:allow-toggle-maximize` 权限（此前被权限拒绝静默失败）；tauri-bridge 修复时序 bug（每次调用实时检测 Tauri 环境）；绿色按钮单击切换最大化 + 双击标题栏触发 + 最大化状态图标切换（方形 ⇄ 双块还原）

### Changed

- **Mac 柔和化落地**（globals.css · DeepSeek 视觉模型评估后落地）：暖白毛玻璃面板（--glass 变量，dark 同步覆盖）+ 输入框 20px 大圆角 + 暖弥散阴影 + 陶土橙 focus 光晕 + 发送按钮胶囊化 + hero 标题加大 + 设置弹窗毛玻璃化（可拖拽 resize）+ 导航选中态重构（淡橙底 + 左侧橙条）
- **业务面板精简**（BusinessPanel）：移除分支/worktree 区块，聚焦导出 + Doctor 扫描

### Removed

- **worktree 前端展示移除**（产品决策 · 用户拍板）：BranchWorktrees 组件删除，RightPanel 项目 tab 还原，business.ts / BranchController 的 projectPath 改动全部还原

## [1.1.0] - 2026-08-13

### Added

- **前后端对接**：会话/消息主链路走 STOMP 实时流，替换 `data.ts` mock
  - 会话列表/创建/删除（`sessionApi`）、消息历史/发送/取消/后台化/partial-compact（`chatApi`）
  - STOMP 事件流：`message.chunk` 流式累积 + `message.complete` 落库 + `session.status` 状态 + `api_retry` 重试
  - 权限冒泡 3 种（message/bridge/channel）+ 持久队列 + 超时留痕（用户离场回来可见）
  - `/compact` 命令 + partial-compact 消息选择器（双击 Esc）+ away-summary（blur 5min）
  - user 消息右对齐气泡 + toolCalls 列表渲染 + `X-Client-Env: react` 请求头
- **vitest 测试框架**：22 个测试（事件类型解析 / chatStore 流式累积 / 权限超时留痕 / 通知队列）
- **useNotifications 本地通知 hook**：对齐 CC `context/notifications.tsx` 纯客户端状态机（priority 队列 + fold + invalidates + dedup + timeout），前端本地通知无后端推送通道

### Changed

- **类型系统统一**：`Provider`/`Model` 统一到 `api/types`（后端 DTO），消除 13 个预存 tsc 错误
- **tsconfig**：静默 baseUrl 弃用（TS 6.0 `ignoreDeprecations`）

### Fixed

- 修复 4 个对接引入的类型错误（isRetry 类型守卫 / setMessages 参数 / socket 死形参 / chatStore 死形参）

## [Unreleased] - 2026-06-07

### Added

- **macOS 交通灯按钮交互**（`TitleBar.tsx`）— 14×14 圆点 + hover 淡入图标（×/—/⤢）
  + active scale(0.92) 按压感 + tooltip + aria-label
- **Per-tab 主模型**（`Session.modelName`）— 每个 tab 独立模型，切换 tab 自动换
  + 派生 `currentModel` 从 `activeSession.modelName`
  + 新 session 随机选模型（tag + name 都写入）
- **快速模型**（`fastModel`）— 全局轻量任务模型（标题生成等）
  + 对应 Java `AgentDefaults.fastModel`（可为 null 回退到本 tab 模型）
  + ModelPickerModal 第二个分段："快速模型 (⚡)"
- **ModelInfo 补全 javaclawbot 真实参数**（`types.ts`）：
  - 9 个 `ModelType` 枚举（chat / text / vision / multimodal / image_generation / embedding / audio / rerank / moderation）
  - 7 个新字段：`alias` / `type` / `maxTokens=65536` / `temperature=-1`（sentinel）/ `topP=null` / `contextWindow=512000` / `enabled`
  - 默认值来自 `ProvidersConfig.initDefaultModelConfigs`
  - 12 处 mock data 全部补全
- **JSON 字段类型**（`FormField` 加 `json`）— 实时 JSON 校验
  + 红边框 + 错误信息提示
  - 应用于 `ModelInfo.think`（对应 Java `Map<String, Object> think`）和 `ModelInfo.extraBody`
- **FormModal 完整重设计**（craft-studio 决策融合：Linear + Raycast + Apple）：
  - status dot header + 衬线 title + mono 风格 subtitle
  - 衬线 section title + `·` 装饰
  - toggle 改为带状态点的 pill（`● 启用` / `○ 停用`）
  - password 字段加眼睛图标按钮切换显隐
  - footer 删除按钮居左（italic 红色透明边框）+ 右侧 spacer + cancel + primary
  - backdrop blur 8→14px，圆角 12→14px
  - 6 字段类型：text / mono / password / textarea / select / toggle / number / json
- **6 个 settings 面板真实 CRUD**（`ProvidersPanel` / `SkillsPanel` / `MCPPanel` / `DatabasePanel` / `SchedulesPanel`）：
  - 全部走 FormModal 的 `sections[]` API
  - ProvidersPanel 加嵌套 model 编辑（展开/折叠 + 内嵌 model 列表）
  - File row 加 rollback (rotate-ccw) + confirm 图标，22→28px

### Changed

- **tab 自适应缩小**（`.tabs` 用 `@container` queries）：
  - 改 `flex-shrink: 0` → `1`（关键）
  - `min-width: 80px` → `64px`
  - 三档断点：normal (≥720px) / compact (<720px) / mini (<500px)
  - mini 模式 padding/font/gap 进一步压缩
  - 滚动条隐藏（保留滚动能力）
- **ModelPickerModal 双角色分段控件**：
  - 旧："主 Agent 模型"（全局 main）+ "快速模型"（全局 fast）
  - 新："本标签页"（per-tab active session）+ "快速模型"（全局 fast）
  - 模型行右侧 `★` 徽章（per-tab）+ `⚡` 徽章（fast）
  - footer 切换到 fast 角色时显示"清除快速模型"按钮
- **number 字段加 `nullable?: boolean` 标志**：
  - nullable=true：清空 input → `null`（不再误存 0）
  - 应用于 temperature / topP

### Fixed

- **0 个新 tsc 错误**（8 个 pre-existing 错误与本次无关；其中 `App.tsx(95) 'activeSession' unused` 反而被修复了）
- 修一个潜在的 null 漏洞：number 字段清空不再误存 0
- ModelFormModal 的 think/extraBody 改 JSON 后跟 Java `Map<String, Object>` 语义对齐

### Performance / Verification

- `npx tsc --noEmit --ignoreDeprecations "6.0"` → 0 新错误
- `npx vite --port 5173` 可正常启动（esbuild 缺失为 pre-existing 环境问题，与代码无关）
- 文件改动 12 个：
  - 新增：docs/{form-redesign-spec,add-pages-redesign}.md, src/components/{ui,modals,center,right,layout,left,common}/*
  - 修改：src/{App,types,data,hooks,reducers,icons,styles/globals.css}.tsx

## [版本] - yyyy-MM-dd

### Added

### Changed

### Fix
