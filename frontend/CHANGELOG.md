# Changelog

All notable changes to NexusAI will be documented in this file.

## [0.1.16] - 2026-09-20

### 修复
- 修复 0.1.15 安装后 app 无法启动的 bug。

## [0.1.15] - 2026-09-20

### 修复
- 修复 Busy 态下停止键卡住、无法手动终止会话循环的 bug。

## [0.1.14] - 2026-09-18

### 新增
- **设置页新增「协调者模式」开关**（环境配置 → 协调者模式）：开启后主 Claude 只负责拆解与派发任务，具体执行交给子代理完成。此前该能力只能经由接口或直接改数据库开启，界面上没有入口。默认关闭。

### 修复
- **截图粘贴只能粘一张**：判重按**文件名**，而 WebView2 给剪贴板位图的合成名恒为 `image.png` ⇒ 第二张被判重复。改为**按内容判重**（图片与小文件 = 字节 md5；拖拽路径 = 完整路径；大文件 = 名 + 大小）。
- **0.1.13 的「非图片非PDF 走 path」在拖拽入口失效**：拖拽监听 `useEffect([])` 锁死首帧闭包（`localRead=false`、`sessionId=''`）⇒ 直落 base64 ⇒ 静默丢弃；拖 >5MB 还会误弹「需先打开一个会话」。改为经 ref 取最新实现。
- **txt/csv/md 附件只给路径不给正文**（对齐 CC）：阈值内（256KB）正文内联，超阈值降级保留路径说明 + WARN。
- **桌面端图片发的是通配符 `image/*`** ⇒ `image-cache` 落盘失败（图彻底丢 + F5 拉图 cache miss）。后端按扩展名归一化为真实 MIME。

- **「展开团队收件箱」会把队友消息全部标为已读** ⇒ 消息在模型看到之前就被吞掉（只置已读位、不删元素，所以列表里还看得到，但队长此后永远读不到）。展开收件箱只是看一眼，既不是投递也不是入队，不该标已读 —— 对齐上游（上游只有两个**轮询器**在「已投递或可靠入队」之后才标读，团队界面里没有任何标读入口）。改为展开时只清本地面板未读角标，标读交回消费侧在真正注入模型上下文之后执行；相应地后端 `/inbox/read` 端点一并下线。

### 其他
- 7 处「附件被丢弃但零日志」补 WARN。
- 新增「附件**入口接线**」测试 —— 此前零覆盖，上面第 2 条正是它从未被覆盖的结果。

## [0.1.13] - 2026-09-18

### 🔴 修复：附件走不通的**三条独立病因**（+ 四处体验/契约问题）

> 用户原话：「Word/PDF 不能上传多份，现在只许一份」。调查发现**三个独立缺陷叠在一起** ——
> 每一个都能单独让附件消失。

#### ① 前端：非图片非PDF 附件改走 `path` 通道（修「小 Word/Excel 静默消失」）
- **根因**：`addPaths` 的 path 通道被 `>5MB` 挡住，而 `BASE64_LIMIT = 5MB` 本是**图片**的
  Anthropic API 硬限制（CC `apiLimits.ts:22` `API_IMAGE_MAX_BASE64_SIZE`），却被**无差别套用到所有类型**
  ⇒ ≤5MB 的 Word/Excel 被塞进 base64 通道，而后端对 `type=file` 的 base64 **没有任何消费方** ⇒ 静默丢弃。
- **反直觉现象**：同名的 >5MB 文件反而走 path、模型能拿到路径说明 ⇒ **小的丢、大的能用**。
- **修复**：`localRead=true` 时「>5MB **或（非图片且非PDF）**」都走 path（零拷贝、不落盘、不进请求体）。
  图片与 PDF 保持原语义（图片需 base64 成 image block；PDF 有自己的内联 document block 链）。
- 顺带：**PDF 不再白绕一圈 base64**（此前 ≤5MB 的 PDF 会在 `~/.nexusai/pdf-cache/` **多写一份副本**）。

#### ② 后端：busy 等候区不再丢弃非图片附件（+ 补回三道校验门）
- **根因**：agent 正在流式输出时发送的消息会入队，而 `busyQueuedImageAttachments` **只携带
  ≤5MB 的 base64 图片** ⇒ PDF/Word/Excel/大图/大文件被静默丢弃（**零日志**）；两个消费点
  （轮内 drain / 轮后 `CronIdleExecutor`）都只兜图片 ⇒ **假兜底**。
- **修复**：入队侧复用**空闲路径同源的三道门**（数量 ≤50 / `resolveAttachments` 校验解析 /
  `MediaLimitGuard` 100 项 + 5MB）⇒ 队列携带**已解析**附件；drain 侧把 `hasImage` 与
  `injectAttachments` 拆成两个独立谓词，并补 `registerRunPromptPdfs` + 媒体/大图路径说明。
- ⚠️ **两个「改了等于没改」的坑（调查中实证）**：只改入队侧 = 行为零变化（两侧是**同一组三条件的
  两份拷贝**）；`MediaLimitGuard` 全仓**仅一个调用点**、在空闲 HTTP 路径 ⇒ 不能当 busy 路径的兜底门。

#### ③ 后端：path 附件不再设扩展名白名单（按用户裁定「所有文件都允许走 path」）
- 原 `PATH_ATTACHMENT_ALLOWED_EXTENSIONS` 15 项把 `zip/txt/csv/md/ogg/m4a/bmp` warn 跳过 ⇒ 永远到不了模型。
- 安全性由**存在性 / ≤200MB / 防穿越**三道门承担（扩展名不是安全边界）。

#### ④ upload 通道白名单放开到文档类（+ ⭐ 一条**必要补门**）
- `AttachmentController.isAllowedType` 加 `doc/docx/xls/xlsx`；`verifyMagic` 改为**按扩展名定向判容器**
  （docx/xlsx = OOXML/ZIP `50 4B 03 04`；doc/xls = OLE2）。
  ⚠️ 文档类分支必须落在**显式媒体类型判定之后** —— 否则 `.docx` 声明 `application/pdf` 会被
  ZIP 恒真的分支放行并落进 pdf-cache（既有红线「非 PDF 入库污染会话」）。
- ⭐ **必要补门**：`ChatService.isMediaRecordType` 原为**正向白名单**（只认 video/audio/octet-stream），
  与它调用点 javadoc 早已写明的意图（「只拒**明显非媒体**：`image/*` 或 `application/pdf`」）**相矛盾**
  ⇒ 真 OOXML MIME 的 docx 被判「撞号」⇒ 回退 `MediaAttachmentStore`（**id 空间与附件表毫无关联**）
  ⇒ 结构性未命中 ⇒ **静默丢弃**。**若不同批修，第 ④ 批会把「响亮 400」变成「静默丢弃」。**
  改为反向排除后，顺带修好同族形态：path 通道注册的 `.docx/.doc/.xls/.xlsx` 在 F5 重拉时此前同样被静默丢弃。

#### ⑤ `addFiles` 通道（浏览器拖拽 / 原生 input / 粘贴）不再静默丢弃
- `video/audio` ≤5MB 改走 upload；`file` 类改走 upload（白名单已放开）；失败**响亮退场**（撤 chip + 提示）。
- ⛔ 核心红线：**绝不允许「显示 chip 但模型收不到」**。

#### ⑥ busy 消息的两处一致性
- **F5 后附件胶囊消失**：busy 消息的 `user_attachments` 快照此前**恒 NULL**（drain 落库点拿不到快照）；
  现随队列携带（取自 **`resolveAttachments` 之后的已解析列表** —— 用原始请求会让 path 附件没有
  `contentId` ⇒ F5 后拼不出预览 url），并在**三条落库路径**（实时 / 补落 / 端后兜底）一致落库。
- **F5 之前气泡没有胶囊**：drained 事件此前只带 `{uuid, content}` ⇒ 现由**后端出站载荷**携带
  权威快照（`QueueItem.userAttachments` 经 `resolveAttachmentUrls` 投影），前端原样搬运、不重算
  ⇒ **live 与 F5 后逐字段一致**。

#### ⑦ 附件 bean 补接到 `build()` 汇聚点
- `AgentLoopContextFactory.freshSession()` 漏接 `attachmentService`/`mediaAttachmentStore`
  ⇒ 走 `shared()`/`forSession 3 参`/fallback 的 loop 拿到 `null` ⇒ 媒体说明的 contentId 腿**静默跳过**。
  照本文件既有范式（`SkillChangeDetector` 的接线点选 `build()`，因为它是**单一汇聚点**）补齐。

### 🧪 测试与验证
- **前端**：43 文件 / **451 单测**（新增 `attachmentDelivery` 73 条、`pathAttachment` 52 条、`queuedUserBubble` 13 条）
- **后端**：22 个测试类 / **193 用例**（新增 `BusyQueuedAttachmentSnapshotDbTest`、
  `ChatServiceBusyAttachResolveTest`、`ChatServicePathAttachmentAnyExtensionTest`、
  `ChatServiceDocMediaRecordResolveTest`、`AttachmentControllerUploadDocTest`、
  `QueueEventPublisherDrainedAttachmentsTest` 等）
- **反向实验 30+ 次**：每处修复都做了「改错 → **必须变红** → 改回」
- **真 e2e（真文件 + **隔离的**真后端 + 真模型）**：批量 **N=2/3/5 全部命中**
  （「后端注入条数」vs「模型说出个数」**差 = 0**）；busy 场景拿到 **wire 级证据**（模型实际收到的请求体）；
  两次反向验证**复现了用户原话的症状**（「我**只收到1个文件**」/「我**没有收到任何附件**」）

### ⚠️ 已知边界（本版**未修**，已登记）
- **idle 乐观气泡的 path 附件仍不可点**（请求体无 contentId ⇒ `url=null`，**F5 后才可点**）—— 需发送响应回传已解析附件
- **反向排除的固有边界**：挡不住「media-cache 旧 id 恰好撞上一条**同为文档类**的行」的巧合
  （与正常上传在请求里是**同一比特形态**，无实现可区分）
- **远端（`localRead=false`）** 无本地路径可传，该通道不适用
- **模型拿到的是「本地路径」说明而非正文**（Word/Excel）；要读内容得靠模型自己的文件工具
  —— **这一点与 CC 行为一致**（CC `FileReadTool` 同样拒读二进制）

## [0.1.12] - 2026-09-17

### 🔍 观测：附件/拖拽链路加 `[attach]` 日志（**纯日志 · 零行为变更**）
- **背景**：用户报告「Word/PDF 不能上传多份，现在只许一份」，且提示气泡写「已添加 **1** 个附件」。
  该气泡由**拖拽路径**的 `addPaths` 打出（计的是**实际加进去几个**）⇒ 说明**交给前端的路径数组本身只有 1 个**。
- **卡点**：拖拽入口 `getCurrentWebview().onDragDropEvent` → `addPaths` **此前一行日志都没有**，
  无法区分「drop 事件只给了一个路径」与「给了 N 个但中途某步丢弃」。
- **本次**：只在 7 个插入点加 `console.warn('[attach] …')`，**不改任何行为**：
  ① drop 事件入口（`enter`/`drop` 都留痕 + `paths.length`）② `addPaths` 入口（`paths.length` + 明细）
  ③ 每个文件的结局（path 大文件 / upload / base64 三通道 + 无 sessionId 跳过 + 读取失败**带真实 err**）
  ④ 去重结果 `pending → fresh` + 逐个被丢弃的 filename 与命中键 ⑤ toast 前 `fresh.length`
  ⑥ `addFiles` 入口（粘贴 / 原生 input 通道）⑦ `handleAddFiles` 的 `catch`（**原为完全静默**，现补真实 err）
- ⚠️ 为什么用 `console.warn` 而非 `console.log`：`frontLog.ts` 只劫持 `error/warn/log`，且
  `console.log` 走**关键字白名单预筛**（不命中**不上报**）⇒ 日志会静默丢失。
- ⛔ **零行为变更已逐行核验**：git diff 仅 **36 新增行 + 2 处 `} catch {` → `} catch (err) {`**（为取到 err 所必需）；
  控制流 / 分支条件 / toast 文案 / 去重逻辑 / 状态更新 / 返回值**零改动**。
  验证：`tsc --noEmit` exit=0 · `npm run build` exit=0 · 构建产物中 **12 条 `[attach]` 文案齐全** ·
  去重回放判定与原 filter 在 **1280 组用例上逐项等价**（mismatches=0）。
- **顺带查实两件事**（均**未改**，仅登记）：① `console.debug` **根本不被劫持** ⇒ 本文件既有的
  `[paste]` 三条诊断**从未落进任何日志文件**；② `addedNamesRef` 只在「发送/清空」时 clear，**会话切换不 clear**。

### 🔴 已定位但**本版不修**的后端缺陷（下批处理）
- **症状**：agent 正在流式输出（busy）时发送的消息会进「等候区」，而**入队时只携带 ≤5MB 的 base64 图片**，
  PDF / Word / 其它文件被**静默丢弃**（无日志），且**两个消费点都只兜图片**。
- **证据**：后端日志 `附件解析完成: 请求=1` 共 **9 次从无例外**；`attachments/upload` 调用 **0 次**；
  `QUEUE busy 携附件 … images=0` 在用户会话中反复出现（该会话长期跑长任务，有一轮静默 **176 秒**）
  ⇒ **用户几乎每条带 Word/PDF 的消息都撞上这条**。
- **为什么不在本版修**：勘察发现推荐方案的前提**不成立** —— `MediaLimitGuard.guard(...)` 全仓
  **只有一个调用点**（`ChatService.java:876`，空闲 HTTP 路径），两条消费路径**都不经过**；
  且入队侧 `busyQueuedImageAttachments` 与 drain 侧 `hasBase64ImageAttachments` 是**同一组三个条件**
  ⇒ **只改一侧 = 行为零变化**（会"宣称修好实际没变"）。⇒ 需按 CC 真实设计重做，留待下批。

## [0.1.11] - 2026-09-17

### 🔴 修复：装了新版本仍一直提示「有新版本」
- **症状**：装上 0.1.10 后进应用，更新提示**依然显示有新版本**。
- **根因**：应用自报的「当前版本」**不是安装包版本**。
  安装包版本由 `tauri.conf.json` 的 `version` 决定（`tauri-codegen` 逐字：有 `config.version` 就用它，没有才回退 crate 版本），
  而 `app_version()` 原先读的是 **crate 版本（`Cargo.toml`）** —— 它从 `0.1.6` 起就没再动过。
  ⇒ 应用自报 `0.1.6`、远端清单是 `0.1.10` ⇒ **比出来永远"有新版本"**。
  ⚠️ 这也解释了早前「装了 0.1.8 / 0.1.9 也照样提示」—— 因为无论装哪个版本，自报永远是 `0.1.6`。
- **修复**：`app_version()` 改读**安装包版本**（`package_info().version`）⇒ 与安装包**同源**，
  crate 版本再漂移也不影响比版本。
- **顺带对齐**：`Cargo.toml` 的 `version` 对齐到与安装包一致（此前长期停在 `0.1.6`）。
- **新增守护**：① 断言 `app_version` 必须读安装包版本、不得读 crate 版本；
  ② ⭐ 断言 `Cargo.toml` 与 `tauri.conf.json` 的版本**必须一致** ——
  把「发版时忘了改其中一个」变成**测试立刻红**，而不是等用户装了包发现还在提示。

### 备注
本版仅含上述修复与版本号对齐；桌面壳其余部分与 0.1.10 相同。

## [0.1.10] - 2026-09-17

### 🔴 修复：前端「整屏停止更新」（用户实测 2026-09-17）
- **症状**：对话中屏幕**整个停止刷新**，但界面上的**动画仍在动**、JS 仍在发请求；**运行期间按 F5 也无视觉变化**，而**那一轮结束后按 F5 就能看到完整内容**。
- **根因**：后端 STOMP **没有开启心跳**（`WebSocketConfig` 只 `enableSimpleBroker`，未 `setHeartbeatValue`）⇒ CONNECTED 帧不带 `heart-beat` 头 ⇒ `@stomp/stompjs` **根本不武装心跳**（`stomp-handler.js if (!headers['heart-beat']) return`）⇒ **半开连接不可察**（TCP 活着但帧不通，状态栏仍显示绿色「已连接」）；而唯一的补偿只挂在「收到 `message.complete`」上 —— complete **也走这条已死的通道** ⇒ 永不补偿。
- ⚠️ 后一半是**静默**的：不报错、不留日志。这才是本次难查之处。
- **修复**：打开 STOMP 心跳（双向 10s）⇒ 半开连接约 20s 内被判死 ⇒ 前端**已有的**重连 + 重订阅逻辑即可自愈。
  ⚠️ 调度器用 Spring 自带的 `messageBrokerTaskScheduler`（不新建 bean），且**必须延迟解析** —— 构造期直接注入会**成环**导致启动失败。
- **断线即补**：WS 连接建立（含重连成功）时按当前会话主动重拉尾页，不再只依赖 `message.complete`。
- **错误边界**：新增 `ErrorBoundary`（顶层 + 消息区整层 + **逐行**）+ React 19 的三个 root error 回调接入前端日志 ⇒ 某一条消息渲染出错**只挂那一条**，不再整屏。
- **投递可观测**：新增订阅生命周期登记（答「那一刻还有没有订阅者」）+ 出站帧计数与最后写出时刻（答「帧有没有真写出去」），每 10s 一行 `[ws-deliver]`；`[usage-push]` 补打 `topic=`。

### 🔴 修复：打开应用后点「添加工作区」卡顿
- **根因**：更新检查 `update_check` 是**同步命令**且用阻塞式 HTTP，跑在 Tauri 的**命令处理线程**上 ⇒ 阻塞期间**所有其他 Tauri IPC 排队**（包括「添加工作区」第一步要弹的文件夹选择框）。启动 3 秒后自动检查一次 ⇒ **3s~18s 这个窗口**内点什么都卡。
  ⚠️ 实测：源[0] 占位符域名 0.01s 即失败；源[1] GitHub 从国内直连需 **21 秒**，实际由 15s 超时截断。
- **修复**：两个更新命令改后台线程池执行；HTTP 超时 **15s → 3s**；默认源改为**内网 MinIO 主源**（可达时秒回）+ GitHub 外网兜底。

### 🟢 新增：前端可观测性（日志 / 心跳通道）
- 壳新增两条命令，把前端错误与**每 10s 心跳**追加写入 `~/.nexusai/logs/frontend.log`（与后端日志同目录）。
- 前端在入口**最前**安装：捕获 `console.error/warn/log`、未捕获异常、未处理的 Promise 拒绝（含 10s 限流 + 丢弃计数 + 防递归）；心跳带窗口可见性与**入站帧计数**。
  ⭐ 「心跳在跳但收帧数不涨」= **通道死了**；「帧在涨却没渲染」= **渲染链问题** —— 这是把两者分开的唯一手段。
- 后端两个内容推送点加**限流留痕**（同一轮内每秒最多 1 条）。

### 🟢 修复：源码内的字面控制字节（消除 git 判文件为二进制）
- 两个源文件含**字面控制字节**，导致 git 把它们**整个判为二进制** ⇒ 永久无法 diff / review / 合并。
- 全部改为等价转义写法，语义**逐字节不变**（四重机械化证明：展开后逐字节比对 + 编译器字面量比对 + 全码点比对 + 新旧版真实行为差分）。
- 新增**源码卫生守卫**（前端 + 后端各一条，字节级、全量断言、**含扫描器自检**）—— 因为实施过程中**三次**又把转义打成了字面字节，反证该缺陷极易复现。

### 测试
- 前端 `vitest`：**40 files / 312 tests 全绿**
- 后端定向：`SedEditParserTest` 6 + `SourceHygieneTest` 2 + `OutboundDeliveryStatsTest` 7 + `WebSocketHeartbeatConfigTest` 3 全绿
- ⚠️ **未做真机验证**：`CONNECTED` 帧的心跳头未实跑核对（下一批第一优先）；前端仍缺「N 秒无入站帧 → 主动重连」看门狗（本版只做留痕）。

## [0.1.9] - 2026-09-17

### 🔴 修复（随包发布的后端）：子代理长任务不再中途硬退
本版桌面壳无改动，**随包发布的后端**含子代理自动压缩的完整修复。

- **症状**：子代理（含后台 fork / hook agent）跑到约 200k token 时会**直接失败中断**，而主会话同一模型能跑到 1M。
- **根因**：三条子代理路径给压缩器**硬编码传了 `null`** ⇒ 主动压缩不生效；同时一条「阻塞上限预检」的判据里多了一个本该没有的「压缩器存在」条件 ⇒ **子代理在发请求之前就被本地合成错误挡下**（请求根本没发出去）⇒ 也就永远走不到「真报错 → 自动恢复」那条路。
- **修复**：让压缩器经「请求自带的基础设施容器」送到三条路径（**调用点一行都没改**）；并让阻塞预检的判据在接线后自动与上游一致。
- **配套**：压缩产物落进**子代理自己的执行记录文件**（前端详情面板可见），并修好「从记录里找当前链尾」的方式（不修的话写了也白写，重开会读回压缩前全量 ⇒ 每次重开都白压一遍）；父子共用的两处会话状态改为按 `(会话, 代理)` 双键隔离（原先子代理压缩会**静默顶掉父会话的「停止压缩」通道**）。
- **测试**：新增 24 条 + 13 个反向实验；另修 **26 条**既有红（H1 7 / H2 6 / H3 3 / I1 2 / I2 1 / I3 5 / I4 1 / I5 1），逐条判定均为「测试过期/夹具过期」而非实现缺陷（**唯一例外见下**）。
- **🔴 唯一一条真实现缺陷已修**：shell 命令失败的分派漏了「按异常类型分派」这一层 —— 原实现用**文本前缀白名单**判失败，而 shell 的真实失败形状不命中 ⇒ 该分支**恒不可达**（上游 CC 是 `e instanceof ShellError` 分派）。后果：技能内联 shell 失败时文案与上游不一致，且**返回型**错误结果可能被当成功处理。已补主分派 + 判据扩为三形状并集。

> ⚠️ 顺带修正一处**误判**：`PowerShellV3Wf5AlignmentTest` 曾被判为「权限语义实现缺陷」（重定向命中 deny 却给 ask），经查实是**测试夹具的规则前缀写错**（上游/本仓语义：单 `/` = 项目根、**仅 `//` = 文件系统根**），**上游在同样夹具下也是 ask** ⇒ 本仓无缺陷，已修夹具前缀。

## [0.1.8] - 2026-09-16

### 🔴 修复：关闭独立预览窗口不再连带关闭本地后端
- **根因**：Tauri 壳的 `Builder::on_window_event` 是**应用级**钩子（对**所有**窗口触发），而 `CloseRequested` 分支**未判定 `window.label()`** ⇒ 关闭 `standalone-preview` 独立预览窗（PDF / Word / Excel / 图片 / 音视频）时，也会**整树回收**本地后端 java（端口 3458）。
  - 用户可见症状：点开 PDF/Word 预览再关掉 ⇒ 后端被杀 ⇒ 前端每 2 秒轮询失败弹一次 `Network Error`（视觉上持续闪）；后端**无守护** ⇒ 不会自愈，只能重启应用。用户原话：「点击阅读 pdf word，然后关闭的时候，会连带将后端也关闭」。
- **修复**：只有主窗口（label `"main"`）关闭才回收后端，子窗口一律不动后端。判定逻辑抽为纯函数 `reclaim_target_on_close`（5 条行为级单测）。
  - ⭐ **顺带修掉一条既有孤儿缺陷**：原实现在钩子里**无条件** `take()` ⇒ 关子窗口会把 pid 消费掉，导致之后关主窗口时后端**回收不掉**（与「防孤儿」初衷相反）。
- **关预览不再弹黑窗**：GUI 壳里 spawn 控制台程序会新分配控制台并弹出 cmd 黑窗 —— 关预览时用户看到的正是 `taskkill`。新增 `backend::silent_command` 统一设 `CREATE_NO_WINDOW`（`taskkill` / `powershell` / `tasklist` 三处）。
- **可观测性**：回收后端的日志改走 `backend::log_launcher`（`~/.nexusai/logs/tauri-launcher.log`）—— 原 `eprintln` 在 release 无控制台时**完全丢失**，是本次排障困难的主因（故障现场**零留痕**）。
- **测试**：8 条（行为级 5 + **源码级接线守护** 1 + 既有 updater 3）。⭐ 源码级守护是为「Tauri 钩子不可单测」付的**平台性妥协** —— 实测：把钩子改成绕过判定函数时，其余 7 条**全绿**、只有它变红。局限已写入测试注释。

### 🔴 修复：一键重启脚本 bat 版（kill 分支此前从未真正工作过）
- `scripts/restart-backend.bat` 在「端口上已有监听」时**从未成功过**（三个叠加缺陷，任一存在都 `EXIT=1`）：
  1. `echo` 文案里未转义的 `)` **提前闭合 `for` 块** ⇒ `taskkill /F /PID %%P` 掉出循环体 ⇒ `%%P` 退化为字面量 ⇒ 报「没有找到进程 "%P"」。（⭐ 实测只有 `)` 致命，单独的 `(` 无害）
  2. `FindPids` **不去重**（姊妹脚本 `.sh` 有 `| sort -u`，`.bat` 漏了）⇒ 双栈监听（`0.0.0.0:PORT` + `[::]:PORT`）同 PID 出现两次 ⇒ 第一次 taskkill 成功、**第二次必失败**。
  3. `timeout /t` 被 PATH 里的 **MSYS coreutils 劫持** ⇒ 等待循环退化成**忙轮询**（实测：报错 89~92 次、就绪 55~61s ⇒ 改为绝对路径后 **0 次、8s**）。
- **验收**（安全端口 3463 真机两轮 + 反向实验；⛔ 全程未触碰 3458）：无监听 ⇒ `READY 8s`；有监听 ⇒ 打印**真 PID** + taskkill 成功 + 重启 + `READY 8s`；回退原版可复现 `%P` 失败。

### 其它
- 版本号 `0.1.7 → 0.1.8`（`tauri.conf.json` / `package.json` / `backend/pom.xml` 三处同步）。

## [0.1.7] - 2026-09-14

### 新增 / 变更
- **会话必绑项目**：新建会话强制携带项目 —— `SessionCreateRequest.mainProjectId` 前端改**必填**（`api/types.ts`），`createSession` 改必传参；无项目时点「+ 新会话」**不再建未绑定会话**，改为引导先选项目目录。
- **空串三态显式收口**：新增纯函数 `utils/newSessionProject.ts` 把 `EMPTY_PROJECT.id === ''` 与纯空白**显式归 null**（⛔ 不用 `??` —— nullish 不挡空串）；`onCreateInProject` 加空串守卫，不再发出 `"mainProjectId": ""`。
- 服务端 `mainProjectId` 必填（`@NotBlank` + 服务层深守卫）⇒ 缺失/纯空白一律 **400**（不再静默落库空项目）。

### 测试
- 新增 `utils/__tests__/newSessionProject.test.ts`（5 例，含**空串三态**与纯空白）；`npm run build` 先红 3 处后转绿；`npx vitest run` 30 files / 270 tests 全绿。
- **本版前端改动集中在会话创建链路**；其余为后端批次（见 backend/CHANGELOG.md 0.1.7）。

### 定时任务（会话锚 · 2026-09-15）
- **创建定时任务必须归属一个会话**：创建请求新增 `sessionId`（后端从它解析任务的项目锚 `boundProject`，且会**忽略**请求体里传的 `boundProject`）。`useSchedules(activeSessionId)` 统一注入当前活动会话；**无活动会话时前端先拦截并提示**（不再发一次注定 400 的请求）。
- **`UpdateScheduleRequest` 不再携带 `scope`/`sessionId`**：两者创建后不可变（后端带值一律 400），hook 层再剥一层防将来误带。
- ~~说明（待用户复核的产品决策）：`SchedulesPanel` **暂无 scope 选择器**~~ → **已被下方「定时任务生命周期选择器」取代**（2026-09-15 用户裁定已实施，见下）。

### 后端地址单一来源（2026-09-15）
- **后端地址收口到一处**：此前「后端在哪」写死在 10 处常量里（`localhost:3458` 分别出现在主 API 客户端、WebSocket、SockJS、agent/command/market 三条 API 与两个面板里）。现统一由 `src/api/base.ts` 一处给出（连 host:port 也只在此文件出现一次），改地址只改一行。
- **开发时走相对路径 + vite 代理**：dev 下前端请求 `/api/*`、`/ws`、`/ws-sockjs` 由 `vite.config.ts` 的 `server.proxy` 转发到本机后端。好处是换主机/局域网调试不必改前端代码。
- **⚠️ 打包版行为不变（刻意保留绝对地址）**：Tauri 打包后页面不再由 vite 开发服务器提供，相对路径会指向应用自身而不是后端 —— 所以打包版仍使用绝对地址，与改造前逐字相同。**这一步不能省**。
- 浏览器扩展的连接说明文案改为引用同一个地址来源（扩展是独立客户端，仍连后端真实地址，不走 vite 代理）。

### 定时任务生命周期选择器（2026-09-15）
- **新增「任务生命周期」选择，创建时可自选**：
  - **仅本会话（默认）**：只在这个会话活着时触发；会话一关，任务被自动清理、不再运行。
  - **持久（跨重启保留）**：会话关闭后仍继续触发（无界面后台运行）；任务归属创建它的会话，用于记录运行上下文。
  - 默认改为「仅本会话」（此前一律为「持久」）。**存量任务不受影响** —— 生命周期在创建时已随任务落库，读侧以落库值为准（详见 backend/CHANGELOG.md 同名条目）。
- **任务列表徽标改为「生命周期 · 归属」**：此前只显示归属，而两种生命周期都会带会话 id ⇒ 看起来完全一样、分不出「会话关了还跑不跑」。现在一眼可辨。
- **没有打开会话时「+ 添加调度」按钮置灰**并就地说明原因（两种生命周期都要求任务归属一个会话；让用户填完整张表单再吃报错没有意义）。
- **修复：选「仅本会话」时未带上会话 id** ⇒ 后端必然 400。现两种生命周期都注入当前活动会话（`buildCreatePayload` 单一出口，且忽略调用方自带的 sessionId，防把任务锚到别的会话）。
- **`/agents` 面板总是携带当前活动会话**：此前不传时后端只能给进程默认的 agent 列表，多项目下可能与当前会话对不上。

### 测试（2026-09-15）
- 新增 `api/__tests__/base.test.ts`（4 例：打包分支 4 个地址全为绝对 + dev 分支相对/带页源 host + https 变体 + 端口单一来源）与 `hooks/__tests__/useSchedules.test.ts`（5 例：两 scope 都必带活动会话 / 缺省为 SESSION / 调用方自带 sessionId 被覆盖 / 无会话两 scope 都抛）。两条反向实验均有红灯读数。
- `npx vitest run` 33 files / **283 tests 全绿**；`npx tsc --noEmit` 与 `npm run build` 均 EXIT 0。
- **打包分支直接证据**：产物 `dist/assets/index-*.js` 中该调用被编译为 `mRe(!1, …)`（`!1` = `false`）⇒ 打包时 `import.meta.env.DEV` 确为 **false**，走的正是绝对地址分支。

## [0.1.6] - 2026-09-09

### Fixed / Changed

- **「Snip 提示」的阈值改成按「上下文剩余百分比」计算（设置 → 环境配置 → Snip 提示剩余上下文阈值（%））**：这一项以前填的是「消息条数」，而且会随模型窗口大小自动在 180 / 360 / 600 / 900 之间变档 —— 结果是大窗口的会话才用到四成左右就开始反复提示压缩，很吵。现在改为直接填「**上下文剩余百分比**」（有效范围 1–100，留空默认 30）：只有当上下文剩余量降到这个百分比以下时才会提示压缩，窗口大小不用再猜。输入框加了 1–100 的范围限制，填了超范围的值会当场提示错误、不会被保存。**注意：这一项的含义变了** —— 如果你以前填过消息条数（比如 900），它会超出新范围而被按默认值 30 处理，请按百分比重新填一次；想彻底关掉这个提示，用上方的 Snip 开关。
- **新增「延迟加载公告用增量方式」开关（设置 → 环境配置 → 工具加载）**：开启后只公告**变化的**工具清单（省 token）；关闭则每轮发送**完整**清单。默认关闭。此前这项后端虽有配置列，但界面上完全没有入口，用户改不了。现在开关状态会随设置保存并在下次进入设置页时正确回填。
- **压缩进度条现在跟着会话走**：此前进度条是「全局」的 —— A 会话在压缩、切到 B，B 的输入框上方照样显示 A 的进度、连发送键都变成了「停止」，点下去取消的还是 B。根因是后端推的是**会话级**事件，前端却存进了一个**不带会话标识的全局状态**。现改为按会话分别记录：进度条只显示当前会话那一条；「压缩中」的停止键只属于真正在压缩的会话；切换会话/取消后不再残留；顺带修掉「取消后进度条又弹回已完成」。
- **上下文用量警告不再串台**：同类的全局状态问题（`token_warning` 也是全局一份），现同样按会话区分 —— A 会话的上下文告警不会出现在 B 的界面上。
- **「对话操作 → 压缩」（部分压缩）现在也有进度条**：此前这条通道的进度回调被写成了"只打日志"，所以进度条**从来不出现**。现与另外两条压缩路径统一。
- **压缩后上下文用量会降下来了**：此前压缩**确实生效**，但界面上的上下文条与「用量与花费」弹窗**仍显示压缩前的旧值**（刷新也一样，因为重算时取的是压缩前那条消息），看起来像"压缩没成功"。现在 `/compact` 完成后会重新拉取，压缩后的用量归零重算，界面与真实状态一致。
- **一轮里多次工具调用时，上下文用量不再虚高**：此前「本轮完成」上报的用量是**这一轮所有模型调用的累加**（调 5 次就约等于 5 倍），且刷新后又会变回正常值 —— 表现为数字在回复结束时跳变。现改为取**最后一次调用的真实用量**。
- **上下文告警横幅不再显示会误导的百分比**：告警里的百分比口径（相对压缩阈值）此前被当成"剩余百分比"直接显示，与服务端真实用量口径混用。现在横幅只提示状态；用量条统一使用服务端真实口径。
- **压缩后轨迹里的 token 数字不再翻倍**：手动压缩时没有传当前会话的模型，系统按另一种计费口径（4 项相加）计算，而 DeepSeek 返回的输入 token 本身已包含缓存 → 数字正好翻了 ~2 倍（实测 188374 vs 真值 94625）。现手动压缩、部分压缩、以及「打开历史会话直接压缩」三条路径都带上了正确的会话模型。
- **不再出现「压缩过一次之后就不再检查上下文上限」**：单轮内压缩过一次后，该轮后续的上限预检会被永久跳过（粘滞）。现按每轮迭代独立判断；同时压缩相关的"熔断计数"改为**按会话隔离**（此前是全局一份：A 会话压缩失败会影响 B 会话的判定）。

- **定时任务「日+周双约束」不再显示"未识别调度"**：后端为「每月 1 日 **或** 每周一」这类 dom+dow 双约束任务存的是 `||` 连接的变体串（如 `0 0 9 1 * ?||0 0 9 ? * 2`），而 `SchedulesPanel.cronToHuman` 按空白切分会把 `?||0` 当成一个 token → 字段数变 12 → 落入「未识别调度」。现先按 `||` 拆分、逐个识别后用「 或 」拼接；并补周几识别（`? * 2-6` → `每周一至周五`，**Quartz 编号 1=周日**，防按 ISO 误标成「1=周一」）；任一侧识别不了仍整体回退原文。设置面板与右侧面板共用该函数，一并生效。
- **停止任务后卡片卡"运行中"**：`/tasks/{id}/kill` 对**已结束/已移除**的任务返回 404（`task not found`），而前端只在成功/失败时弹 toast、不更新本地态 → 子代理卡片永久"运行中"。现：**成功 → 乐观置 stopped**（终态事件丢失也不卡）；**404 → 视为已结束**（子代理卡片清身份 / 任务面板刷新清单）。
- **轨迹徽标实时刷新**：每 5s 轮询后端 `GET /sessions/{id}/messages/count`（DB `sessions.messageCount` 非 meta 口径）更新轨迹 tab 消息总数，覆盖新消息/删除/裁剪；不再只是进会话时的快照。
- **FinishReason 读回容错（后端）**：DB 某条 `finish_reason='max_tokens'` 曾致消息读取 500 → 轨迹页"暂无轨迹"；后端加 `max_tokens` 常量 + 容错解析，现正常。
- **对话流式渲染对齐 deepseek**：rAF 帧合并替固定定时、行级订阅（打字只重渲所在行）；超长单块自动降级纯文本（不再逐帧全量解析卡死）；频繁切换会话不再「字不吐 / 抖动」。
- **markdown 渲染对齐 CC**：移除 settled 前 dirty patches（围栏代码 `#include/#define` 不再被改写，「流式对、收口错」消除）；settled 缓存键补 onRunHtml。
- **消息历史改为有界窗口**：打开/刷新只拉尾页 50 + 顶部「加载更早」逐页向上；Trace 全程独立（不受窗口限制）；不再一次全量拉 + 一次全量渲染。
- **tool_use_summary 改实时展示**（不再写 transcript，对齐 CC）。
- **自动压缩改真实用量判定**：不再因本地粗估虚高误触发压缩。
- **auto-memory / snip 提醒修复**：default 会话记忆注入正常、snip 提醒按模型真实可见消息计数。
- **轨迹 tab 消息数改全量**：0.1.6 有界窗口化后，「轨迹」徽标误把已加载页（page）条数（≈50）当全程数。现 `GET /messages/page` 新增 `total`（= 会话非 meta 消息总数，读 DB `sessions.messageCount` 零额外查询），chatStore 存会话总数，徽标 = total（未拉到回落窗口条数）；打开/切会话/F5/停止/重连即刷新到 DB 真值。
- **轨迹补「已裁剪 / 已压缩」标记**：Snip 裁剪的消息此前在轨迹里与普通消息无异，Compact 压缩也看不出边界在哪。现轨迹左侧竖线区分 `.trace-record.snipped`（灰）+「已裁剪」标签，`compact_boundary` 渲染为「已压缩 #N（摘要看下一行）」分隔标记 + 折叠摘要详情（`is_compact_summary` 从 DB 读回，不再恒 false）。会话消息总数徽标按 `seq` 排序取。
- **聊天区不再显示 transcript-only 摘要**：压缩摘要中"仅入 transcript、不进对话"的那种（对齐 CC `isVisibleInTranscriptOnly`）此前会作为普通用户气泡出现在聊天区。现 `MessageList` 按该标记过滤，只在轨迹中可读。
- **「轨迹」里的压缩标记点开能看到真实摘要**：此前在「保留此前（from）」等压缩方向下，标记点开只显示英文占位文本（`Conversation compacted`）而非真正的压缩摘要；现按「压缩边界 ↔ 摘要」一一配对取值，各方向都能读到摘要。
- **更早的压缩记录与其摘要正确一一对应**：连续压缩（多次压缩叠加）时，每条压缩记录对应自己的那份摘要，不会串到相邻的压缩记录上。
- **压缩切点候选不再出现压缩摘要本身**：手动压缩/恢复的选点列表只列真实对话消息，压缩摘要与「仅入 transcript」的消息不再混入候选（选中它们会切出无意义的边界）。

- **`/compact` 现在可以带自定义要求**：此前只支持裸 `/compact`，输入 `/compact 只保留结论` 里的要求会被丢掉。现支持在命令后写要求，压缩时会按它执行。
- **压缩摘要有了专门的卡片样式**：此前压缩产生的摘要按普通用户气泡渲染，看不出它是"系统压缩的产物"。现单独成卡片（标题「对话已压缩以释放上下文」），不再混在对话气泡里。
- **刷新（F5）后「已裁剪」标记不再丢失**：此前被裁剪消息的「已裁剪」标记只存在于实时推送里，一刷新就全没了 —— 因为刷新只拉最近一页，而裁剪记录可能不在这一页里。现打开轨迹时会一并把裁剪记录读回来，标记恢复。
- **轨迹里能看出「这里做过裁剪」**：此前轨迹里只有压缩边界有标记，裁剪边界会被当成一条普通回复显示（内容是它的摘要文本）。现轨迹补上「已裁剪」标记条，和对话区的显示一致。
- **停止钩子的执行摘要可见**：一次对话结束前跑的停止钩子，如果报错或阻止了继续，会在对话里留一条摘要（正常结束时按原有习惯不显示，避免刷屏）。

## [0.1.5] - 2026-09-09

### Added

- **自动更新**：桌面端启动自动检查（也可右下角「更新」手动），发现新版本即提示（对外 latest 化），支持查看更新说明 → 下载（进度/sha256 校验）→ 重启安装。更新源可配置，默认内网 MinIO，兼容 GitHub。
- 发版侧新增 `scripts/release-update.mjs`（生成 latest.json + 上传 MinIO/GitHub）。

### Fixed / Changed

- **get_page_text 不再扩展侧截断（整页全交）**：此前 content script 将整页可见文本一刀切到 20k 字符，长文/长对话页后半被吞。现整页 `innerText` 全量回传，超 CC 单结果内联上限 `DEFAULT_MAX_RESULT_SIZE_CHARS=50_000` 的部分交由后端落盘+文件路径预览承接（聚合 200k 兜底），对齐 CC tool-result 尺寸语义。

## [0.1.4] - 2026-09-08

### Fixed / Changed

- **前缀缓存修复（skill_listing 稳定化，对齐 CC）**：可用 skills 清单（~7.8k）此前经附件通道每轮追加到消息队尾，随对话增长位置漂移 → DeepSeek 前缀缓存每轮在清单处断裂（缓存利用率可低至 6%）。现改为**恒定置请求头部的 meta user 块**（紧跟 system、字节稳定、compact 后仍头部、变化随当前清单更新），缓存命中恢复高值。
- 移除定位用的 `[CACHEFP]` 临时埋点（OpenAiSdkProvider）与 yml 开关。

## [0.1.3] - 2026-09-08

### Fixed / Changed

- **后端 java 生命周期加固**：随包后端 pid 落盘 `~/.nexusai/backend.pid`；正常关窗/退出即时整树回收；被强杀后下次启动“身份校验”（命令行含 `nexusai-backend.jar`）才清理，不误杀其它 java。
- **auto-memory 目录以 DB 绑定为主路径**：模型主动写记忆的目录来自会话绑定项目（DB path）；修复此前绑定 `D:\code\ai_project\nexusai` 却写入 `~/.nexusai/projects/C--Users-WIN--nexusai/memory` 的假目录；无绑定项目 → fail loud（error 日志）不回落、不拼假目录；autoDream/ExtractMemories 维持参数直传正确路径。
- **SendMessage 对齐 CC**：OpenAI 兼容请求（deepseek/moonshot）初始 tools 含 SendMessage；fork/后台子代理每轮消费 inbox（主代理可实时通知运行中的子代理）。
- **openai 懒加载通用豁免**：非 anthropic 清空 deferred，deferred 工具恒进初始 schema。
- **会话标题自动生成**：provider/model 形态默认标题视为占位可被覆盖。
- 子代理后台任务等待提示（Layer-1）；snip 档位说明同步；blocking 预检 stub 修复。

### Fixed

- **无会话时「欢迎页绑定项目」此前只注册项目不建会话 → 左侧永远不出现（误以为没绑上）**：现改为与左栏「添加工作区 +」共用同一 `openWorkspaceFromFolder` 路径——选目录 → 注册/取项目 → **自动新建该项目首个会话并置顶左栏**并选中；有会话（空新会话）时维持绑定当前会话。左栏 `+ 添加工作区` 也复用此路径，前端不再按 name 猜复用（避免同名异目录误配）。
- **后端配套（同批，见 backend changelog）**：`POST /api/v1/projects` 按「目录绝对路径」幂等（同目录重复注册返回已有，不再撞 projects.name UNIQUE → 500）；`projects` 唯一键由 name 迁到 path（Flyway V69），落库 path 统一正斜杠。
- **绑定到已删除会话报 Session not found**：删除**最后一个**会话后 `activeSessionId` 残留已删 id（无 else 重置）→ 空态欢迎页「绑定项目」被误判为「有会话」→ bind 已删除 session 404。现删除最后一个会话时激活态重置为空；绑定入口改为校验会话真实存在于列表，不存在即走「建工作区」闭环。
- **模型标签半动态（KIMI 等不再显示 DS）**：`ModelTag` 类型扩展 `'KIMI'`；新增 `brandTagFromName` 按模型全名推断品牌（kimi/moonshot→KIMI 等，未识别返回 null 保留配置 tag）；新建会话默认模型若存储 tag 是 DS 兜底但品牌可推断（如 kimi）→ 用推断值，不再发 `model=DS`。`tagToClass` 补 kimi、未知兜底 `other` 中性色（不再误标 DS 蓝）；`--model-kimi/--model-other` 主题色。

### Added / Changed

- **会话文件面板真打通（对齐 CC）**：无会话 = 干净零态（不再渲染演示假会话/文件，保留原首页 welcome hero）；左右栏折叠（收起保留窄 rail、顶部右栏开关，启动一律展开）；右栏「文件」tab 显示 agent **本会话真实改动的文件** + 点击看**真实 diff** + 可**回滚到改动前**（后端新增 `SessionFilesRecorder` 写盘捕获 + `files.changed` STOMP 推送 + REST `GET /files · GET /files/diff · POST /files/revert`；`session_files` 表孤儿 controller 清理路由冲突）。
- **@ 引用文件（对齐 CC @file）**：输入框 `@` 补全绑定项目文件；项目文件树**右键「引用到对话」**与 @ 同效；发送后用户气泡内 `@pack.sh` 渲染为**内联阴影 chip**（可点开文件预览、悬停动效）；后端在 `ChatService` 把被引用文件读入**本轮模型上下文**（绑定项目根内路径约束，越界/缺失/超限 skip+warn fail loud，零 schema 改动）。
- **轨迹视图看内容**：点击任意轨迹记录行弹出完整内容浮层（用户/助手全文、工具完整 arguments）。
- **输入框引用 chip**：`@` token 以阴影框展示，hover 加深、点击从草稿移除。
- **dev 启动修复**：`tauri dev` 不再因缺少随包后端而 panic（dev 交由 IDE 起 3458；release 仍 fail loud）。
- 视觉对齐 deepseek-harness 侧栏折叠语义；monaco 静态入主包 + diff 编辑器单实例复用（首开零拉取）。

## [Unreleased]

### Added

- **技能市场弹窗**（骨架，腾讯 workbuddy 数据）：Composer 顶部胶囊改为「技能市场」入口，点开全屏弹窗；三 Tab——专家（本地/远程混排带来源徽标）、技能（3 列卡 + 分类 + 精选/推荐）、连接器（3 列平铺）；搜索 / 已安装 N / 市场源下拉（默认腾讯）。远端专家「使用」→ 后端构造 `wb-` agent 注册进会话 → 设为会话主线程 agent 驱动整轮对话（与本地专家同链路）。配套后端 `GET/POST /api/market/*`（见后端 changelog）
- **会话级主线程 agent（专家）选择器**（V58 `main_thread_agent`）：Composer 顶部新增 agent 胶囊，点开下拉可选「专家」（agentType + whenToUse 简介），选中即 PATCH 会话 `mainThreadAgent`，整轮对话由该 agent 的 systemPrompt 驱动（对齐 CC `--agent` / `mainThreadAgentDefinition`）；对话进行中锁定不可切，可选「默认（清除）」恢复；配套后端 `GET /api/agents/list` 结构化端点（active winners，跳过 deny 过滤对齐 CC 用户侧选择器）

- **对话正文渲染迁移为增量 mdast（deepseek-harness 对齐）**：新增 `src/markdown/` —— micromark/mdast 结构化 AST + React 元素直渲，替换对话正文的 marked+DOMPurify+整段 innerHTML 重写。`IncrementalMarkdownParser` 流式只重解析「尾部不稳定块」（冻结前部块缓存 React 元素），根治「打字到代码块/长内容主线程卡顿数秒、要等 turn 结束才追平」。代码 fence 由真实语法树渐进成形（未闭合也产 code 节点、lang 从 fence 打开就有）→ ` ```html ` 代码块「运行」按钮流式中即出现；代码块 banner 化（语言徽标 + 复制/运行，复制/运行从 absolute-over-pre 改为 banner 内）。附带能力：shiki 语法高亮（JS 正则引擎 + 懒加载语言分包 + 浅/深 `--shiki-*` token，settled 才高亮）、KaTeX 数学（settled 才渲染；streaming 用无 math 语法防闪错）、CJK 强调（`**注意：**内容` 可闭合）、保留单换行→`<br>` 软换行。安全自持（替代 DOMPurify）：raw HTML 一律字面量文本、URL 协议白名单（http/https/mailto）、图片要求绝对 http(s)。历史消息精排、DB 重拉路径不变；`##核心`/粘连表/未闭合代码的文本修正移入 `patches.ts` 且只在 settled 全量渲染跑（流式预览与结束态允许一次「终跳」收口纠正，测试显式 pin）。（zcw）

- **HTML 代码块「另起页面打开」独立窗口/标签页**：右栏 HTML 运行预览标题栏新增「⧉ 独立打开」——浏览器 dev/web 用同名标签页（window.open 复用）、Tauri 用 `WebviewWindow('html-standalone')`（已存在则聚焦）。独立查看器 = 整屏 sandbox iframe（`#/standalone-html` 路由，main.tsx 顶层分流，跳过 LaunchGate/STOMP，不打扰主会话）；对话里再次点击该代码块「运行」会经 localStorage 通道自动刷新独立页（窗口开着就跟着跑，用户可继续聊天）。新增 `front/src/utils/htmlStandalone.ts`、`front/src/components/standalone/StandaloneHtmlView.tsx`；Tauri 补 `core:window:allow-create`/`core:webview:allow-create-webview-window` 权限 + `standalone` capability（windows: html-standalone）。（zcw）

### Fixed

- **打开含超大单条消息的会话卡死**：窗口化只限「消息条数(150)」不限「单条 DOM 体积」——`sess-6e5e8ba8` 里有一条 ~350KB 的 user 消息，单条整段 mdast 解析建树即可把主线程钉死到渲染完。新增 `ContentGuard`：单条正文 >20KB 时初始只渲染前 5KB 纯文本预览 + 「查看完整内容」按钮，展开才整段 mdast（防初始加载被病理大消息阻塞）。（zcw）
- **历史会话反复点击切换卡死**：① `switchSession` 点击「当前已激活会话」改 no-op（不再每次 dispatch+toast+触发切会话清空重拉/整列表重渲染排队压主线程）；② `MarkdownText` settled 渲染加 LRU 缓存（内容不变的消息跨会话重开不重新 mdast 解析 + KaTeX/shiki），快速在历史会话间切换不再整列表全量重排。（zcw）
- **打字机流式代码块卡顿 + 代码块/运行按钮延迟显示（S1/S2）**：S1 根因=流式每 ~200ms 对整条增长全文 `renderMd` + `dangerouslySetInnerHTML` 整段重写、无 DOM 增量（代码块放大 DOM ~1.6×，未闭合 html 被当裸标签透传），叠加 40ms 滚底布局 → 单 tick 超帧预算后 setTimeout 节流被饿死、WS 帧积压到 turn 结束才追平。S2 根因=`fixUnclosedCodeBlocks` 全局奇数 ``` 剥光 fence → 流式中代码块整个不可见（按钮只注入存在 `code.language-html` 的 pre），直到 close fence（常在消息末尾）才成形。均随增量 mdast 渲染根治（见 Added）。
- **对话工具卡空输出成功命令假「执行中」**：空输出成功 Bash（`cmd //c start` 开浏览器等零 stdout）后端实时推回空 result，ToolCard 旧判定 `result.trim()!==''` 判「已完成」→ 永久「执行中」转圈（实际已 0.7s 完成、刷新后反而正常，实时/重拉两路不一致）。完成判定改 `result != null`（收到 `tool_result` 含空串即完成）→ 空输出成功显示「已完成」（配套后端空输出占位 `(tool completed with no output)`，见后端 changelog 2026-09-05）
- **后台任务终态收不到 / 子代理「运行中」虚高**：`/topic/tasks` `type=task.notification` wire 键实为 `task_id`（后端 `@JsonProperty("task_id")`），`TaskNotificationWireEvent` 误声明 camelCase `taskId` 且消费端读 `evt.taskId` → 恒 undefined → 终态 `addActivity` 永不执行；types.ts 字段改 `task_id`（+ `userMessageId` StreamEvent 槽兜底），useChatSocket 读 `evt.task_id ?? evt.userMessageId`
- **子代理运行状况陈旧「运行中」**：AsyncTasksPanel REST 兜底补录不再因身份已登记（`if (existing) continue`）跳过终态——STOMP 已登记但实际已完成/失败（终态事件在断连窗口丢失）的子代理，2s 轮询按 REST 权威状态补 `done`/`stopped`；`subagentStore.addActivity` 加终态幂等，防事件 + 补录双路径重复追加
- **子代理运行状况面板**：`task_started` 按 `task_type` 过滤（仅 `local_agent` / `in_process_teammate` / `remote_agent` 登记），`local_bash` 等后台命令不再误显示在子代理面板；配套后端 `emitForegroundTerminal` 补发终态事件（已完成的 bash 不再滞留"进行中"）
- **定时任务消息重复**：`finalizeBlocks` 按 `assistantMessageId` 幂等（cron idle resume + 流式块 + 重拉双通道同一条消息不再重复插入）
- **消息 token 用量**：fallback 分支「本轮 $368」为 JSX 字面 `$` + token 数 → 改「本轮输出 368 tokens」明确单位，消除金额误读
- **流式思考块收起**：「正在思考…」块加点击收起（此前恒展开 + `div` 无 `onClick`）
- **nexusai-in-chrome 扩展健壮性（B-1，Phase 0 实测定标）**：`front/extension/background.js` 截图去 OS 抢焦点——新增免聚焦 `screenshotTab`（删 `windows.update({focused})+sleep(200)`；目标 tab 已 active 跳过 `tabs.update`；实测单次截图 ~1.2s→~50-200ms 且不再把 Chrome 拉前台）；minimized 显式返回 `ERR_MINIMIZED` 不再 60s 干等；SW 连接加固——alarm 无条件 connect、心跳 25s→20s 且每 tick 补 `chrome.storage` 写（Chrome<116 也重置 SW 空闲计时）、指数退避重连、`idle/onStartup/onInstalled` 唤醒重连、会话 tab 持久化 awaited + 恢复时 `tabs.get` 校验 + `autoDiscardable:false`（治 1001 断连后重开标签）；修 computer `zoom` 从未路由到 background 的死路（`isBackgroundTool` 补 zoom）。配套后端断连 close-code 埋点见后端 changelog 2026-09-06。（zcw）
- **nexusai-in-chrome 截图改 CDP per-tab（跟进）**：`captureVisibleTab` 要求「窗口前台 + 该标签为活动标签」，B-1 去 raise 后在 NexusAI 里截图即失焦 → 报 `Cannot access contents of url ""` / 超时。改为优先 CDP `Page.captureScreenshot(fromSurface)` 按 tabId 截（与窗口前台/活动标签无关，renderer 级），DevTools 占用调试器时回退 `captureVisibleTab` 并给可读提示；manifest 加 `debugger` 权限。治「在 NexusAI 里让 AI 截图报 host 错/卡死」。（zcw）
- **nexusai-in-chrome 动作正确性（B-2）**：`content.js` 引入单源可交互索引——`read_page/find/computer` 共用同一份编号（`ref`=数组下标，元素存模块级数组+WeakMap 反向校验），杜绝「find/computer 各自 querySelectorAll 推导编号」导致的误点错位；`find` 支持 text/description/selector 并在同一快照匹配；`computer` 定位优先级 ref（校验 `isConnected`，失效明确报「请重新 read_page/find」）> selector > 坐标 elementFromPoint。点击改为真实序列：`scrollIntoView` + `elementFromPoint` 命中最深层真实元素 + 最近可聚焦祖先 `focus` + 派发 pointer/mouse 事件后**补原生 `el.click()` 默认激活**（链接导航/表单提交/checkbox 切换真正生效，治「点了没反应」）；右键不补 click 防误触发。`background.js` `navigate` 加就绪门（`waitForTabComplete` 轮询 tab complete / readyState 上限 15s，返回 `state:'complete'|'timeout'`，不再点了立刻打旧文档）。版本 0.1.6。（zcw）

## [0.1.0] - 2026-09-04

### Chore

- **仓库单仓化 + Tauri 桌面打包 + 版本统一 0.1.0**：front/backend 并入 nexusai 单仓（本仓结构）；Tauri 桌面打包——jlink 裁剪 JRE 随包分发、Rust 后端进程生命周期托管、单实例锁、NSIS 安装包；三处版本统一对齐 tauri.conf 0.1.0（package.json 1.4.0→0.1.0、后端 pom 0.5.0→0.1.0）（zcw）

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

#### 追加（2026-09-03 · 未递增）

- **响应式字号分级（窗口逻辑宽度三档）**：globals.css 末尾新增 `:root` 字号变量（`--fs-base/--fs-body/--fs-input/--fs-list`，默认=现值零回归）+ 两个 `@media(min-width: 1440/1920)` 三档覆盖（+1px/档）；核心阅读区选择器改变量引用——body 根字号、消息正文 `.msg .content`/`.msg.assistant .body`、用户气泡 `.msg.user .user-bubble`、输入框 `.composer textarea`、会话列表 `.session-item`。高分屏（Mac16≈1469 / 1920 屏≈1632 / 4K@150%≈2176 逻辑宽）字号自动 +1~2px；已验证 `vite build` 通过（globals.css · zcw）

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
