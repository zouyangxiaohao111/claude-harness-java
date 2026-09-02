package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;

import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 工具抽象 · 对齐 CC {@code Tool.ts:402-456}（{@code buildTool()} 生成的 Tool 对象）。
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li><b>单方法 dispatch</b>：所有工具都是 {@link #execute(ToolUseBlock)}，
 *       由 {@link ToolRegistry} 按 name 查表分发（对齐 s02 TOOL_HANDLERS 模式）。</li>
 *   <li><b>JSON Schema 输入</b>：{@link #inputSchema} 用 OpenAI function calling 格式
 *       描述参数，LLM 按 schema 生成 JSON 输入。</li>
 *   <li><b>per-input concurrency 判断</b>：{@link #isConcurrencySafe} 对<b>每次具体
 *       调用</b>判断，不是 per-tool 静态判断。CC 的关键差异：{@code Bash "ls"} 算只读
 *       可并发，{@code Bash "rm"} 不可并发；{@code TaskCreate} 改状态但写不同文件可并发。
 *       对齐 s02 README 深入 CC 源码 第二节"并发安全判断"。</li>
 *   <li><b>错误不抛</b>：{@link #execute} 内部 catch 异常并返回
 *       {@link ToolResult#error}。这样 {@link com.nexusai.application.agent.LlmAgentLoop}
 *       不会因为一个工具错误挂掉整个 turn。</li>
 * </ul>
 *
 * <h2>CC 对齐（5 个核心 + 18 个默认方法）</h2>
 * <table>
 *   <tr><th>CC Tool.ts 字段</th><th>本接口方法</th><th>PR</th></tr>
 *   <tr><td>name</td><td>{@link #name()}</td><td>1</td></tr>
 *   <tr><td>description</td><td>{@link #description()}</td><td>1</td></tr>
 *   <tr><td>inputSchema</td><td>{@link #inputSchema()}</td><td>1</td></tr>
 *   <tr><td>isConcurrencySafe(input)</td><td>{@link #isConcurrencySafe(JsonNode)}</td><td>1</td></tr>
 *   <tr><td>call(input)</td><td>{@link #execute(ToolUseBlock)}</td><td>1</td></tr>
 *   <tr><td>outputSchema</td><td>{@link #outputSchema()}</td><td><b>2.4</b></td></tr>
 *   <tr><td>checkPermissions(input, ctx)</td><td>{@link #checkPermissions(JsonNode, ToolUseContext)}</td><td><b>2</b></td></tr>
 *   <tr><td>validateInput(input, ctx)</td><td>{@link #validateInput(JsonNode, ToolUseContext)}</td><td><b>2</b></td></tr>
 *   <tr><td>isReadOnly(input)</td><td>{@link #isReadOnly(JsonNode)}</td><td><b>2</b></td></tr>
 *   <tr><td>isDestructive(input)</td><td>{@link #isDestructive(JsonNode)}</td><td><b>2</b></td></tr>
 *   <tr><td>isSearchOrReadCommand(input)</td><td>{@link #searchReadKind(JsonNode)} (4 态 enum, R32-b8 #2)</td><td><b>2</b> / <b>R32-b8 #2</b></td></tr>
 *   <tr><td>mapToolResultToToolResultBlockParam(content, id)</td><td>{@link #mapToToolResultBlockParam(AgentToolResult)}</td><td><b>R32-b8 #1</b></td></tr>
 *   <tr><td>isResultTruncated?(output)</td><td>{@link #isResultTruncated(String)}</td><td><b>OPD-TOOL-07-6</b></td></tr>
 *   <tr><td>isOpenWorld(input)</td><td>{@link #isOpenWorld(JsonNode)}</td><td><b>2</b></td></tr>
 *   <tr><td>requiresUserInteraction()</td><td>{@link #requiresUserInteraction()}</td><td><b>2</b></td></tr>
 *   <tr><td>maxResultSizeChars</td><td>{@link #maxResultSizeChars()}</td><td><b>2</b></td></tr>
 *   <tr><td>backfillObservableInput(input)</td><td>{@link #backfillObservableInput(JsonNode)}</td><td><b>2</b></td></tr>
 *   <tr><td>interruptBehavior</td><td>{@link #interruptBehavior()}</td><td><b>2</b></td></tr>
 *   <tr><td>isEnabled()</td><td>{@link #isEnabled()}</td><td><b>2.1</b></td></tr>
 *   <tr><td>shouldDefer(input)</td><td>{@link #shouldDefer(JsonNode)}</td><td><b>2.2</b></td></tr>
 *   <tr><td>alwaysLoad()</td><td>{@link #alwaysLoad()}</td><td><b>2.2</b></td></tr>
 *   <tr><td>mcpInfo()</td><td>{@link #mcpInfo()}</td><td><b>2.2</b></td></tr>
 *   <tr><td>isMcp()</td><td>{@link #isMcp()}</td><td><b>2.2</b></td></tr>
 *   <tr><td>isLsp()</td><td>{@link #isLsp()}</td><td><b>2.2</b></td></tr>
 *   <tr><td>strict()</td><td>{@link #strict()}</td><td><b>2.2</b></td></tr>
 *   <tr><td>inputsEquivalent(a, b)</td><td>{@link #inputsEquivalent(JsonNode, JsonNode)}</td><td><b>2.2</b></td></tr>
 *   <tr><td>getActivityDescription(input)</td><td>{@link #getActivityDescription(JsonNode)}</td><td><b>tool_v4 IMP-0</b></td></tr>
 *   <tr><td>userFacingNameBackgroundColor(input)</td><td>{@link #userFacingNameBackgroundColor(JsonNode)}</td><td><b>tool_v4 IMP-0</b></td></tr>
 *   <tr><td>isTransparentWrapper()</td><td>{@link #isTransparentWrapper()}</td><td><b>tool_v4 IMP-0</b></td></tr>
 *   <tr><td>extractSearchText(out)</td><td>{@link #extractSearchText(Object)}</td><td><b>tool_v4 IMP-0</b></td></tr>
 * </table>
 *
 * <h2>PR 2 设计要点</h2>
 * <p>18 个新方法全部为 {@code default}，工具实现零修改仍可工作。Phase 1 后续 PR 集成
 * {@code hasPermissionsToUseToolInner} 时，按需选择性 {@code @Override}。
 *
 * @see ToolUseContext
 * @see ToolRegistry
 * @see ToolUseBlock
 * @see ToolResult
 */
public interface Tool {

    // ════════════════════════════════════════════════════════════════════════
    // 现有 5 方法（保留不动 · PR 1 已完成）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 工具名。必须唯一，作为 {@link ToolRegistry} 的 key。
     * 命名规范：对齐 CC 工具名（camelCase/PascalCase，e.g. "Read"/"Edit"/"Write"/"Glob"/"Grep"，
     * 见 ToolNameConstants 各 CC 真源）。旧 snake_case 名降级 {@link #aliases()} 兜底。
     */
    String name();

    /**
     * 人类可读描述。会发给 LLM 帮它理解什么时候调用这个工具。
     */
    String description();

    /**
     * 输入感知描述 · 对齐 CC {@code Tool.ts:386-393 description(input, options)}。
     *
     * <p>s02 [P2] 修补：CC 的 description 是 async 函数，接收 input 和 options
     * （含 isNonInteractiveSession、toolPermissionContext 等），能根据会话类型/输入
     * 返回不同描述。Java 原 description() 是静态字符串，无法区分交互式/非交互式会话。
     *
     * <p>新工具可 override 本方法返回 input 相关的动态描述（如 BashTool 根据命令内容
     * 生成不同的描述）。default 实现回退到无参 {@link #description()}，确保向后兼容。
     *
     * @param input 工具输入参数（已解析 JSON），用于生成 input-aware 描述
     * @return 输入感知的工具描述
     */
    default String description(JsonNode input) {
        return description();
    }

    /**
     * JSON Schema（OpenAI function calling 格式）。描述 input 参数结构。
     * 示例：
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "path":  {"type": "string", "description": "..."},
     *     "limit": {"type": "integer"}
     *   },
     *   "required": ["path"]
     * }
     * </pre>
     */
    JsonNode inputSchema();

    /**
     * 工具的路径扩展点 · CC original: {@code getPath?}（{@code Tool.ts:506}）。
     *
     * <p>文件/路径类工具（Read/Edit/Write/Glob/Grep/NotebookEdit/LSP）提供本次输入
     * 对应的路径；权限管线（CC {@code filesystem.ts:1035-1041/:1211-1217}）在
     * {@code typeof tool.getPath !== 'function'} 时直接 ask，否则用
     * {@code tool.getPath(input)} 作为权限检查路径。
     *
     * @param input 工具输入（已解析 JSON）
     * @return 本次调用的路径；未实现（无路径概念的工具）返回 {@code null}（等价 CC
     *         {@code getPath} 未定义 → 权限检查走 ask）
     */
    default String getPath(JsonNode input) {
        return null;
    }

    /**
     * 权限规则内容匹配器扩展点 · CC original: {@code preparePermissionMatcher?}
     * （{@code Tool.ts:514}，返回 {@code (pattern) => boolean}）。
     *
     * <p>hook if 条件（如 {@code Bash(git *)}）的 ruleContent 匹配由各工具自行 prepare 一次
     * 闭包（CC {@code hooks.ts:1406-1419}：{@code await tool.preparePermissionMatcher(input)}，
     * 之后对每个 hook if 复用）。CC 语义：工具未实现 → matcher undefined → ruleContent 非空即
     * false（过滤）；ruleContent 为空 → true（纯工具名匹配）。
     *
     * @param input 工具输入（已解析 JSON），hook 事件 tool_input
     * @return 内容匹配谓词（pattern → boolean）；未实现返回 {@code null}（等价 CC matcher
     *         undefined → ruleContent 非空即过滤）
     */
    default Predicate<String> preparePermissionMatcher(JsonNode input) {
        return null;
    }

    /**
     * 直接 JSON Schema 声明扩展点 · CC original: {@code inputJSONSchema?}
     * （{@code Tool.ts:397}，{@code ToolInputJSONSchema}）。
     *
     * <p>MCP 等直接以 JSON Schema 声明 input 的工具提供；序列化层（CC {@code api.ts:157-160}
     * {@code inputJSONSchema in tool && tool.inputJSONSchema ? tool.inputJSONSchema :
     * zodToJsonSchema(tool.inputSchema)}）<b>优先</b>使用本值，其次才走 {@link #inputSchema()}。
     *
     * @return 直接 JSON Schema；未声明返回 {@code null}（等价 CC 无 inputJSONSchema → 用
     *         {@link #inputSchema()} 转换）
     */
    default JsonNode inputJSONSchema() {
        return null;
    }

    /**
     * 给定具体输入，判断本次调用能否与其他 concurrency-safe 调用<b>并行执行</b>。
     *
     * <p>对齐 CC {@code isConcurrencySafe(input)}（{@code Tool.ts:402}）。关键：
     * <ul>
     *   <li>Read / Glob → 始终 true</li>
     *   <li>Write / Edit → 始终 false（写操作要串行）</li>
     *   <li>Bash → 看具体命令：{@code ls / cat / grep} 算只读 → true；{@code rm / mv / echo >} 算写 → false</li>
     * </ul>
     *
     * @param input  LLM 给的参数（已解析 JSON）
     * @return true = 可并发，false = 必须独占（等当前 batch 跑完才执行）
     */
    default boolean isConcurrencySafe(JsonNode input) {
        return false;  // CC 默认 false（保守，假设不安全）
    }

    /**
     * s07-P1-3 终极重构: 执行工具 · 返回 {@link AgentToolResult} (sealed interface).
     *
     * <p>对齐 CC SkillTool.ts:735-860 call() 返回 union 类型 (text + newMessages + contextModifier).
     * 工具返回 {@link ToolResult ToolResult&lt;T&gt;} (newMessages / contextModifier / mcpMeta
     * 已折入, 对齐 CC Tool.ts:323/330/331-335). 调用方按需读 {@code data()} / {@code newMessages()} 等,
     * 不再用 instanceof 分流 (旧 ExtendedToolResult 已退役).
     *
     * <p>30+ 旧工具仍返回 {@code ToolResult<String>} — Java 子类型协变, 自动适配新签名.
     * SkillTool 等需要 newMessages 的工具重写返回 {@code ToolResult<String>} (经 {@link ToolResult#successWithNewMessages}).
     *
     * <p>不应抛异常, 所有错误转 {@link ToolResult#error()} 返回.
     *
     * @param call LLM 的工具调用请求（id + name + input）
     * @return 执行结果 ({@link ToolResult ToolResult&lt;T&gt;}; newMessages/contextModifier/mcpMeta 已折入)
     */
    AgentToolResult<?> execute(ToolUseBlock call);

    /**
     * 执行工具（含运行时上下文）· 对齐 CC {@code Tool.ts:379-385 call(args, ToolUseContext, ...)}.
     *
     * <p>s02 [P1] 修补: 原 {@link #execute(ToolUseBlock)} 仅接收 id/name/input,
     * 缺少 CC 的 {@link ToolUseContext}（含 permission mode、working directories、
     * abortController、availableTools 等运行时上下文）和进度回调.
     *
     * <p>新工具应 override 本方法获取完整上下文；旧工具不改亦可运行——
     * default 实现回退到 {@link #execute(ToolUseBlock)}.
     *
     * @param ctx  运行时上下文（可能为 null，调用方按需传入）
     * @return 执行结果 ({@link ToolResult ToolResult&lt;T&gt;}; newMessages/contextModifier/mcpMeta 已折入)
     */
    default AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        return execute(call);
    }

    /**
     * 执行工具（含进度回调）· 对齐 CC {@code Tool.ts:379-385 onProgress}。
     *
     * <p>需要报告进度的工具 override 本方法并调用 {@code onProgress.accept(...)}；
     * 默认回退到二参方法，确保既有工具实现和 caller 无需修改。
     *
     * @param call       LLM 的工具调用请求
     * @param ctx        运行时上下文（可能为 null）
     * @param onProgress 进度回调（可能为 null）
     * @return 执行结果
     */
    default AgentToolResult<?> execute(
            ToolUseBlock call,
            ToolUseContext ctx,
            Consumer<ToolProgress> onProgress) {
        return execute(call, ctx);
    }

    /**
     * 工具输出 schema（§2.4 outputSchema）
     *
     * <p>对齐 CC {@code Tool.ts:400 — ZodType<unknown>}。
     * 定义工具输出的 JSON Schema，用于：
     * <ul>
     *   <li>输出验证（确保工具返回符合预期格式）</li>
     *   <li>文档生成（自动列出工具输出字段）</li>
     *   <li>类型安全（编译时检查）</li>
     * </ul>
     *
     * <p>默认 null（不校验输出格式）。
     * 工具可 override 返回自己的 JSON Schema。
     *
     * @return JSON Schema（Jackson JsonNode），null 表示不校验
     */
    default JsonNode outputSchema() {
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // PR 2 新增 10 个 default 方法（对齐 CC Tool.ts:402-456）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 工具自己的权限表态。默认返回 {@link PermissionResult.Allow}（保守放行）——
     * 工具未声明特殊策略时按允许处理，由 10 层规则中其他层把关。
     *
     * <p>对齐 CC {@code Tool.ts:checkPermissions(input, context)} 的默认行为：
     * 无显式声明的工具应放行，让上层规则（ask rule / deny rule 等）继续决策。
     *
     * <h2>4 种返回值语义</h2>
     * <ul>
     *   <li>{@link PermissionResult.Allow} — 工具说"我同意"（默认；updatedInput 回传原 input）</li>
     *   <li>{@link PermissionResult.Deny} — 工具说"我拒绝"（基于内容明确知道危险）</li>
     *   <li>{@link PermissionResult.Ask} — 工具说"我拿不准，问用户"</li>
     *   <li>{@link PermissionResult.Passthrough} — 工具说"我没意见"，交给通用管线（极少用）</li>
     * </ul>
     *
     * <h2>关键不变式</h2>
     * <p>如果工具返回 {@link PermissionResult.Deny}，会被 PR 5+ 的 10 层规则第 1d 层
     * 捕获（bypass-immune）—— 即使在 {@code bypassPermissions} 模式也强制 deny。
     *
     * @param input  LLM 给的参数（已解析 JSON，schema 验证通过）
     * @param ctx    工具调用上下文
     * @return       权限决策，默认 allow（updatedInput 回传原 input）
     */
    default PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("default allow"),
            null,
            false,
            null,
            java.util.List.of());
    }

    /**
     * 工具级语义验证。对齐 CC {@code toolExecution.ts:683 validateInput} 阶段。
     *
     * <p>区别于 Zod schema 验证（参数类型检查）：
     * <ul>
     *   <li>Zod: 参数类型正确（如 path 是 string）</li>
     *   <li>{@code validateInput}: 参数值合法（如 path 不越狱 workspace）</li>
     * </ul>
     *
     * <p>默认返回 {@link ValidationResult#pass()}——具体工具按需 override（如
     * 文件类工具在 pathGuard 失败时返回 fail）。
     *
     * @return OK 表示通过；非 OK 含 errorCode + message 注入 LLM 让模型自纠
     */
    default ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        return ValidationResult.pass();
    }

    /**
     * 是否只读。对齐 CC {@code Tool.ts:404 isReadOnly(input)}。
     *
     * <p>只读工具可并发执行且不需要写入权限检查。读工具（Read / Glob）应 override 为 true。
     */
    default boolean isReadOnly(JsonNode input) {
        return false;
    }

    /**
     * 是否破坏性操作。对齐 CC {@code Tool.ts:405-406 isDestructive(input)}。
     *
     * <p><b>仅 UI 标签</b>，不参与权限决策。CC 源码注释明确：
     * <em>"Defaults to false. Only set when the tool performs irreversible operations
     * (delete, overwrite, send)."</em>
     *
     * <p>只用于在 UI 工具列表显示 <code>[destructive]</code> 标签。
     *
     * <p><b>语义核查（G2 v3）</b>: CC buildTool 默认 {@code false}
     * （Tool.ts:761 {@code TOOL_DEFAULTS.isDestructive = () => false}），Java default false 对齐；
     * 消费方 {@code print.ts:1661 tool.isDestructive?.({}) || undefined} 是 <b>MCP server 列表
     * 渲染标记</b>（UI-family）——Java 无前端，不虚构消费方，登记 RES-G2-01 受控残留。
     */
    default boolean isDestructive(JsonNode input) {
        return false;
    }


    /**
     * [R32-b8 #2] 4 态搜索/读取类型 · 对齐 CC {@code Tool.ts:429-433 isSearchOrReadCommand(input)}
     * 返回的 {@code {isSearch, isRead, isList?}} 联合字段.
     *
     * <p>CC 端 3 个 builtin tool 实现:
     * <ul>
     *   <li>{@code FileReadTool.ts:382-384} → {@code {isSearch: false, isRead: true}} → {@link SearchReadKind#IS_READ}</li>
     *   <li>{@code GlobTool.ts:85-87}      → {@code {isSearch: true,  isRead: false}} → {@link SearchReadKind#IS_SEARCH}</li>
     *   <li>{@code GrepTool.ts:192-194}    → {@code {isSearch: true,  isRead: false}} → {@link SearchReadKind#IS_SEARCH}</li>
     * </ul>
     *
     * <p><b>D1 校正（按 CC 源）</b>: 任务 brief 曾描述 "Glob/LS → IS_LIST"，但 CC
     * {@code GlobTool.ts:85-87} 实际为 {@code isSearch: true, isRead: false} —— Glob 是
     * "文件名模式搜索"（不是目录列表），语义对齐 {@link SearchReadKind#IS_SEARCH}。
     *
     * <p><b>C2 校正（按 CC 源）</b>: CC 端 {@code isList?: boolean} 字段由 BashTool 生产使用
     * （BashTool.tsx:72 {@code BASH_LIST_COMMANDS = {ls, tree, du}} → {@code isList: true}）。
     * Java {@link SearchReadKind#IS_LIST} 是对齐 CC {@code isList} 的忠实映射，非 Java 独有扩展、
     * 非未使用预留。BashTool.searchReadKind 对 ls/tree/du 生产返回 {@link SearchReadKind#IS_LIST}。
     *
     * <p>默认 {@link SearchReadKind#NONE} —— 与 CC 端默认 {@code isSearch=false, isRead=false}
     * 折叠语义一致 (非搜索/读取, 不折叠到一行).
     *
     * @param input  LLM 给的参数（已解析 JSON）
     * @return 4 态分类（NONE / IS_READ / IS_SEARCH / IS_LIST）
     */
    default SearchReadKind searchReadKind(JsonNode input) {
        return SearchReadKind.NONE;
    }

    /**
     * 是否开放世界（网络/外部系统）。对齐 CC {@code Tool.ts:434 isOpenWorld(input)}。
     *
     * <p>开放世界工具可能产生外部副作用（Bash 跑 curl、API 调用）。
     *
     * <p><b>语义核查（G2 v3）</b>: CC 的 {@code isOpenWorld} <b>不在</b>
     * {@code DefaultableToolKeys}（Tool.ts:709-717），无 buildTool 默认 → 未实现时
     * {@code print.ts:1662 tool.isOpenWorld?.({}) || undefined} 恒缺省（undefined）；
     * Java default {@code false} 为 fail-closed 等价。消费方是 <b>MCP server 列表渲染标记</b>
     * （UI-family）——Java 无前端，不虚构消费方，登记 RES-G2-01 受控残留。
     */
    default boolean isOpenWorld(JsonNode input) {
        return false;
    }

    /**
     * 是否强制用户交互。对齐 CC {@code Tool.ts:435 requiresUserInteraction()}。
     *
     * <p>返回 true 时，工具返回 {@code ask} 会被 PR 5+ 的 10 层规则第 1e 层捕获
     * （bypass-immune）—— 即使在 bypass 模式也必须问用户。
     */
    default boolean requiresUserInteraction() {
        return false;
    }

    /**
     * [Session H5] 工具使用摘要 · 对齐 CC {@code tool.getToolUseSummary?.(processedInput)}
     * (Open-ClaudeCode/src/services/tools/toolHooks.ts:475).
     *
     * <p>CC 真源: {@code tool.getToolUseSummary} 是 optional 方法 ({@code ?.}), 给 PreToolUse hook
     * 提供 tool input 的可读摘要 (用于 prompt-based hook 决策). Java 端 default 返回 {@code null}
     * (对齐 CC optional {@code ?.} 语义 - 无 override 时 hook 拿不到摘要).
     *
     * @param processedInput 工具输入 (已解析的 Map 形式)
     * @return 工具使用摘要文本; {@code null} = 未提供 (对齐 CC undefined)
     */
    default String getToolUseSummary(java.util.Map<String, Object> processedInput) {
        return null;
    }

    /**
     * 工具活动描述 · 对齐 CC {@code Tool.ts:546-548 getActivityDescription?(input)}。
     *
     * <p>返回 human-readable 现在时活动描述，供 spinner 显示
     * （CC JSDoc 例："Reading src/foo.ts" / "Running bun test" / "Searching for pattern"）。
     *
     * <p><b>CC 真源</b>（{@code Tool.ts:546-548}）：
     * <pre>
     * getActivityDescription?(input: Partial&lt;z.infer&lt;Input&gt;&gt; | undefined): string | null
     * </pre>
     * 返回 {@code null} = 回退到工具名。CC 消费方（{@code structuredIO.ts:101-109}
     * {@code buildRequiresActionDetails}）按链回退：
     * <pre>
     * tool.getActivityDescription?.(input) ?? tool.getToolUseSummary?.(input) ??
     * tool.userFacingName(input)   // catch → tool.name
     * </pre>
     *
     * <p><b>各工具 override 真源</b>（本批次只补基类 default，override 由各工具实施批次负责）：
     * BashTool.tsx:517 / GlobTool.ts:66 / FileReadTool.ts:369 / FileEditTool.ts:99 /
     * FileWriteTool.ts:104 / WebFetchTool.ts:85 / WebSearchTool.ts:164 / AgentTool.tsx:1278 /
     * PowerShellTool.tsx:342 / GrepTool.ts:173 / NotebookEditTool.ts:105 / MonitorTool.tsx:99。
     *
     * <p>默认 {@code null}（CC optional 缺省语义：消费方回退 getToolUseSummary →
     * userFacingName → name）。
     *
     * @param input 工具输入（已解析 JSON；允许 null —— CC {@code Partial&lt;Input&gt; | undefined}）
     * @return 现在时活动描述；{@code null} = 未实现（回退工具名）
     */
    default String getActivityDescription(JsonNode input) {
        if (LoggerFactory.getLogger(Tool.class).isDebugEnabled()) {
            LoggerFactory.getLogger(Tool.class).debug(
                "Tool.getActivityDescription 接口默认返回 null：工具 {} 未 override 活动描述（对齐 CC Tool.ts:546 optional 缺省语义，消费方回退工具名）",
                name());
        }
        return null;
    }

    /**
     * 结果落盘阈值（字符数）。对齐 CC {@code Tool.ts:466 maxResultSizeChars}。
     *
     * <p>工具结果超过此阈值会落盘到文件，模型收到的是文件路径 + 预览。
     * Read 工具应设为 {@link Long#MAX_VALUE}（防止 Read→file→Read 循环）。
     *
     * <p>默认 100_000 字符（对齐 CC 工具族声明值：FileEditTool.ts:89 / FileWriteTool.ts:97 /
     * GlobTool.ts:60 / MCPTool.ts:35 / AgentTool.tsx:229 / SkillTool.ts:334 等均为 100_000；
     * 组 2-3 拍板「基类默认 maxResultSizeChars=100k」）。系统级 cap 仍由
     * {@link ToolResultStorage#DEFAULT_MAX_RESULT_SIZE_CHARS}=50_000 施加
     * （CC toolLimits.ts:13 DEFAULT_MAX_RESULT_SIZE_CHARS + toolResultStorage.ts:77
     * {@code Math.min(declared, DEFAULT_MAX_RESULT_SIZE_CHARS)}），故未 override 工具的
     * <b>有效</b>落盘阈值 = min(100_000, 50_000) = 50_000，与 CC 一致。
     */
    default long maxResultSizeChars() {
        return 100_000L;
    }

    /**
     * 补全遗留字段。对齐 CC {@code Tool.ts:475-484 backfillObservableInput(input)}。
     *
     * <p>hook / canUseTool / observer 看到 input 前可补全字段（如 file_path 展开），
     * 但<b>不影响</b>最终 {@code call()} 的入参（保护 prompt cache 一致性）。
     *
     * <p>默认 identity（不动 input）。
     */
    default JsonNode backfillObservableInput(JsonNode input) {
        return input;
    }

    /**
     * 中断行为。对齐 CC {@code Tool.ts:416 interruptBehavior()}。
     *
     * <p>用户发新消息时工具的行为：
     * <ul>
     *   <li>{@code "block"} — 工具继续运行，新消息等待（默认）</li>
     *   <li>{@code "cancel"} — 停止工具并丢弃结果</li>
     * </ul>
     */
    default String interruptBehavior() {
        return "block";
    }

    /**
     * 是否启用。对齐 CC {@code Tool.ts:403} {@code isEnabled()}。
     *
     * <p>ToolRegistry 在分发工具前会调用此方法；返回 {@code false} 的工具不会出现在
     * 候选集，也不会被 LLM 看见。用于运行时禁用某些工具（如管理员临时关闭
     * Bash 或危险工具），不需要修改系统提示或工具描述。
     *
     * <p>默认 {@code true}（绝大多数工具启用）。具体工具按需 override：
     * <ul>
     *   <li>权限策略禁用 → 工具可 override 为 {@code false}（如全局禁 Bash）</li>
     *   <li>环境检测禁用 → 工具可 override（如检测到无网络时 WebFetch 返回 false）</li>
     *   <li>用户偏好禁用 → 工具可 override（如用户在 settings.json 写 disabled: ["Bash"]）</li>
     * </ul>
     *
     * <p>Phase 1 补充：PR 2 实施时遗漏（HTML §2.17 标 P1），PR 2.1 补回。
     */
    default boolean isEnabled() {
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // PR 2.2 新增 7 个 default 方法（§2.18–§2.24）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 是否延迟执行（§2.18 shouldDefer）
     *
     * <p>某些工具（如 Plan）在特定模式下应延迟执行。
     * 默认 false（立即执行）。
     *
     * @param input 工具输入
     * @return true = 延迟执行
     */
    default boolean shouldDefer(JsonNode input) {
        return false;
    }

    /**
     * 是否总是加载到工具列表（§2.19 alwaysLoad）
     *
     * <p>某些核心工具（如 Bash、Read）即使在受限模式也应加载。
     * 默认 false（按需加载）。
     *
     * @return true = 总是加载
     */
    default boolean alwaysLoad() {
        return false;
    }

    /**
     * MCP 服务器信息（§2.20 mcpInfo）
     *
     * <p>如果工具来自 MCP 服务器，返回服务器信息。
     * 默认 null（非 MCP 工具）。
     *
     * @return MCP 服务器信息，null 表示非 MCP
     */
    default McpServerInfo mcpInfo() {
        return null;
    }

    /**
     * 是否 MCP 工具（§2.21 isMcp）
     *
     * @return true = 来自 MCP 服务器
     */
    default boolean isMcp() {
        return mcpInfo() != null;
    }

    /**
     * 是否 LSP 工具（§2.22 isLsp）
     *
     * @return true = 来自 LSP 服务器
     */
    default boolean isLsp() {
        return false;
    }

    /**
     * 是否严格模式（§2.23 strict）
     *
     * <p>严格模式下，工具对输入验证更严格。
     * 默认 false。
     *
     * @return true = 严格模式
     */
    default boolean strict() {
        return false;
    }

    /**
     * [IT-5] 输入未知键运行时策略 · 对齐 CC zod v4 {@code unknownKeys} 三态语义。
     *
     * <p>CC 每个工具用 zod schema 声明运行时未知键行为（{@code toolOrchestration.ts:97-107}
     * {@code inputSchema.safeParse(input)}）：
     * <ul>
     *   <li>{@code z.object(...)} → 默认 <b>strip</b>：未知键不报错，safeParse 后从
     *       typed value 剔除（不参与校验）</li>
     *   <li>{@code z.strictObject(...)} → <b>strict</b>：未知键报
     *       {@code unrecognized_keys}（toolErrors.ts:114 逐字）</li>
     *   <li>{@code z.object(...).passthrough()} → <b>passthrough</b>：未知键接受且保留</li>
     * </ul>
     *
     * <p>Java 端 JSON Schema 广告层（{@code additionalProperties}）与运行时校验
     * （{@link com.nexusai.application.agent.permission.ToolInputValidator} 第 3 步）分离：
     * 广告层永远输出 CC {@code toJSONSchema()} 逐字结果（zod v4.4.3 实测：z.object /
     * z.strictObject → {@code additionalProperties:false}，passthrough → {@code {}}），
     * 运行时是否拒绝未知键由本方法三态策略决定：
     * <ul>
     *   <li>{@link UnknownKeysPolicy#UNSPECIFIED}（默认）— 跟随广告层：
     *       {@code additionalProperties=false} 即拒绝。覆盖 CC 全部
     *       {@code z.strictObject} 工具（TodoWrite / TaskCreate / Bash / FileEdit 等 36 处），
     *       零回归。</li>
     *   <li>{@link UnknownKeysPolicy#STRIP} — 放行未知键（等价 CC {@code z.object}）。</li>
     *   <li>{@link UnknownKeysPolicy#PASSTHROUGH} — 放行未知键（等价 CC
     *       {@code .passthrough()}）。</li>
     *   <li>{@link UnknownKeysPolicy#STRICT} — 强制拒绝（等价 CC {@code z.strictObject}，
     *       即使广告层未声明 false）。</li>
     * </ul>
     *
     * <p>注意：本方法与 {@link #strict()}（:476）语义不同——{@code strict()} 对齐 CC
     * {@code ToolDef.strict} SDK 严格模式标志（BashTool.tsx:425），与 zod unknownKeys 无关，
     * 不得复用。
     *
     * @return 未知键运行时策略，默认 {@link UnknownKeysPolicy#UNSPECIFIED}（跟随广告层）
     */
    default UnknownKeysPolicy unknownKeysPolicy() {
        return UnknownKeysPolicy.UNSPECIFIED;
    }

    /**
     * 两个输入是否等价（§2.24 inputsEquivalent）
     *
     * <p>用于去重：如果两次 tool_call 的输入等价，只执行一次。
     * 默认用 JSON equals 比较。
     *
     * @param a 输入 A
     * @param b 输入 B
     * @return true = 等价
     */
    default boolean inputsEquivalent(JsonNode a, JsonNode b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    // ════════════════════════════════════════════════════════════════════════
    // s05 新增 4 个 default 方法 · 对齐 CC Tool.ts buildTool 字段
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 用户可见的工具名称 · 对齐 CC {@code Tool.ts userFacingName()}
     *
     * <p>用于 UI 显示（工具列表、权限对话框等）。与 {@link #name()} 不同，
     * name() 是内部唯一标识，userFacingName() 是给人看的。
     *
     * <p>CC 示例：TaskCreate 工具的 userFacingName() 返回 'TaskCreate'，
     * TodoWrite 返回 ''（空字符串 = 不显示）。
     *
     * <p>默认返回 {@link #name()}（回退到工具名）。
     */
    default String userFacingName() {
        return name();
    }

    /**
     * 用户可见名称的背景色 · 对齐 CC {@code Tool.ts:525-527 userFacingNameBackgroundColor?(input)}。
     *
     * <p><b>CC 真源</b>（{@code Tool.ts:525-527}）：
     * <pre>
     * userFacingNameBackgroundColor?(input: Partial&lt;z.infer&lt;Input&gt;&gt; | undefined):
     *   keyof Theme | undefined
     * </pre>
     * 返回主题色名（CC {@code keyof Theme}，如 AgentTool UI.tsx:776 实现），UI 消费方
     * {@code AssistantToolUseMessage.tsx:78 userFacingToolNameBackgroundColor:
     * tool.userFacingNameBackgroundColor?.(data)}。Java 无前端渲染层，default {@code null}
     * 对齐 CC {@code undefined} 缺省语义（受控残留，与 isDestructive/isOpenWorld 同类处置）。
     *
     * @param input 工具输入（已解析 JSON；允许 null）
     * @return 主题色名；未实现返回 {@code null}
     */
    default String userFacingNameBackgroundColor(JsonNode input) {
        return null;
    }

    /**
     * 是否透明包装工具 · 对齐 CC {@code Tool.ts:533 isTransparentWrapper?()}。
     *
     * <p><b>CC 真源</b>（{@code Tool.ts:529-533} JSDoc）：
     * <em>"Transparent wrappers (e.g. REPL) delegate all rendering to their progress
     * handler, which emits native-looking blocks for each inner tool call. The wrapper
     * itself shows nothing."</em>——透明包装工具（如 REPLTool.ts:52 实现）把渲染委托给
     * 内部工具调用的 progress handler，自身不显示任何内容。UI 消费方
     * {@code AssistantToolUseMessage.tsx:79/:123 isTransparentWrapper:
     * tool.isTransparentWrapper?.() ?? false}（可选链 + {@code ?? false} 兜底）。
     *
     * <p>Java 无前端渲染层，default {@code false}（对齐 CC 消费方 {@code ?? false} 兜底，
     * 受控残留）。
     *
     * @return true = 透明包装工具（自身不渲染）；默认 false
     */
    default boolean isTransparentWrapper() {
        return false;
    }

    /**
     * 工具提示词（prompt）· 对齐 CC {@code Tool.ts prompt()}
     *
     * <p>返回工具的详细使用指南文本。CC 中 prompt() 为 async 函数，
     * 返回长文本（如 TodoWrite 的 PROMPT 有 180+ 行）。
     *
     * <p>此文本会注入系统提示词中，指导 LLM 何时及如何使用此工具。
     *
     * <p>默认返回 {@link #description()}（安全默认，非 null）：CC 端序列化工具描述时直接取
     * prompt()（api.ts:171 {@code description: await tool.prompt(...)}），Java 端
     * ToolRegistry.toOpenAiToolsArray / InboundMcpToolProvider / ToolSearchService 亦做
     * {@code prompt() ?? description()} 兜底。基类给出非 null 默认后，未 override 的工具
     * LLM 至少看到 description() 文本（组 2-3 拍板「基类补安全默认」）。
     */
    default String prompt() {
        if (LoggerFactory.getLogger(Tool.class).isDebugEnabled()) {
            LoggerFactory.getLogger(Tool.class).debug(
                "Tool.prompt 接口默认回退 description()：工具 {} 未 override 专属提示词（对齐 CC api.ts:171 prompt() 作为工具描述）",
                name());
        }
        return description();
    }

    /**
     * 搜索提示 · 对齐 CC {@code Tool.ts:378 searchHint?: string}（G5 提升为接口契约成员）。
     *
     * <p>CC 语义：可选能力短语，供 ToolSearch 关键词匹配（ToolSearchTool.ts 消费方待
     * OPD-23 接线）。约束：3-10 词、无尾句号、优先用工具名不含的词（如 NotebookEdit
     * 用 'jupyter'）。值必须逐字对齐 CC 工具真源（各 override 标 CC 原名 + 行号）。
     *
     * <p>默认返回 {@code null} = CC absent 语义（未声明的工具无搜索提示），向后兼容
     * 既有 20+ 未声明工具，零修改。
     */
    default String searchHint() {
        if (LoggerFactory.getLogger(Tool.class).isDebugEnabled()) {
            LoggerFactory.getLogger(Tool.class).debug(
                "Tool.searchHint 接口默认返回 null：工具 {} 未声明搜索提示（对齐 CC Tool.ts:378 可选字段 absent 语义）",
                name());
        }
        return null;
    }


    /**
     * 自动分类器输入 · 对齐 CC {@code Tool.ts toAutoClassifierInput(input)}
     *
     * <p>将工具输入转换为简短分类文本，供自动分类器使用。
     * 用于决定工具调用是否需要用户确认。
     *
     * <p>CC 示例：
     * <ul>
     *   <li>TodoWrite: {@code `${input.todos.length} items`}</li>
     *   <li>TaskCreate: {@code input.subject}</li>
     *   <li>TaskUpdate: {@code `${input.taskId} ${input.status} ${input.subject}`}</li>
     * </ul>
     *
     * <p><b>默认返回 {@code ""}</b>（对齐 CC Tool.ts:767 TOOL_DEFAULTS
     * {@code toAutoClassifierInput: (_input?: unknown) => ''}，buildTool 合并
     * {@code {...TOOL_DEFAULTS, ...def}} 时未 override 的工具继承空串）。
     * 空串语义 = 无安全相关性：消费侧 yoloClassifier.ts:411 {@code encoded === ''}
     * 跳过转录 block，:1021-1024 {@code actionCompact === ''} 直接放行
     * （reason 'Tool declares no classifier-relevant input'）。安全相关工具必须 override。
     */
    default String toAutoClassifierInput(JsonNode input) {
        // CC 默认 ''（Tool.ts:767 TOOL_DEFAULTS）；消费侧 yoloClassifier.ts:411/:1021-1024
        // 空串=无安全相关性，跳过转录与分类。已接线：YoloClassifierImpl 建 name+alias
        // 投影 lookup 消费本方法（yoloClassifier.ts:400 tool.toAutoClassifierInput(input) ?? input）；
        // '' 语义 = action 短路 ALLOW（CC :1021-1028）。未 override 的工具 = 无安全相关性。
        if (LoggerFactory.getLogger(Tool.class).isDebugEnabled()) {
            LoggerFactory.getLogger(Tool.class).debug(
                "Tool.toAutoClassifierInput 接口默认返回空串：工具 {} 未 override 分类器输入（对齐 CC Tool.ts:767 TOOL_DEFAULTS）",
                name());
        }
        return "";
    }

    /**
     * 工具使用消息渲染 · 对齐 CC {@code Tool.ts renderToolUseMessage(input)}
     *
     * <p>生成工具使用时的用户可见消息。返回 null 表示不产生用户可见消息。
     *
     * <p>CC 中大部分任务管理工具返回 null（不产生用户可见消息），
     * 而 Read/Write/Bash 等工具返回具体操作描述。
     *
     * <p>默认返回 {@code null}（无用户可见消息）。
     */
    default String renderToolUseMessage(JsonNode input) {
        return null;
    }

    /**
     * 转录搜索索引文本 · 对齐 CC {@code Tool.ts:599 extractSearchText?(out: Output): string}。
     *
     * <p><b>CC 真源</b>（{@code Tool.ts:582-599} JSDoc）：转录模式（verbose=true,
     * isTranscriptMode=true）下 {@code renderToolResultMessage} 显示文本的扁平化提取，
     * 供转录搜索索引计数；必须返回最终可见文本，不得返回模型侧序列化
     * （{@code mapToolResultToToolResultBlockParam} 添加 system-reminders/persisted 包装）。
     * 可选：省略 → transcriptSearch.ts:58 字段名启发式。
     *
     * <p><b>CC 实现工具</b>：GlobTool.ts:151 / GrepTool.ts:250 / BashTool.tsx:549 /
     * FileReadTool.ts:414 / FileWriteTool.ts:146 / WebSearchTool.ts:229（Output 各异）。
     *
     * <p><b>Java 签名映射</b>：CC 参数 {@code out: Output} 依赖 per-tool 泛型，Java {@link Tool}
     * 接口无 Output 泛型（与 {@link #isResultTruncated(String)} 同问题）——本方法取
     * {@code Object} 兜底（兼容各工具结果类型），default {@code null} 对齐 CC optional
     * 省略语义（transcriptSearch 字段名启发式）。Java 无转录搜索消费方，受控残留。
     *
     * @param output 工具结果（各工具 Output 的具体类型）
     * @return 转录搜索索引文本；{@code null} = 未实现（省略 → 字段名启发式）
     */
    default String extractSearchText(Object output) {
        if (LoggerFactory.getLogger(Tool.class).isDebugEnabled()) {
            LoggerFactory.getLogger(Tool.class).debug(
                "Tool.extractSearchText 接口默认返回 null：工具 {} 未 override 转录搜索索引文本（对齐 CC Tool.ts:599 optional 省略语义）",
                name());
        }
        return null;
    }

    /**
     * 结果截断判定 · 对齐 CC {@code Tool.ts:615 isResultTruncated?(output: Output): boolean}
     * （可选契约成员，OPD-TOOL-07-6 提升为 Tool 接口契约）。
     *
     * <p>CC 语义：返回 true 当该 output 的 non-verbose 渲染被截断（点击展开会显示更多内容），
     * 用于 fullscreen 点击展开门控——消费方 Messages.tsx:593
     * {@code tool?.isResultTruncated?.(toolUseResult) ?? false}（可选链 + {@code ?? false} 兜底）。
     * JSDoc 原文 <em>"Unset means never truncated"</em>，即<b>未实现 = 永不截断</b>，故 Java
     * default {@code false}。
     *
     * <p>算法对齐 CC {@code terminal.ts:119-133 isOutputLineTruncated(content: string)}：内容需多于
     * {@code MAX_LINES_TO_SHOW(3)} 个换行（占满 &gt; 3 行），且第 4 个换行后仍有内容（尾随换行是
     * 终止符不是新行，对齐 renderTruncatedContent 的 trimEnd 行为）。MCP 实现 CC
     * {@code MCPTool.ts:67-69 isResultTruncated(output) { return isOutputLineTruncated(output) }}
     * 的 Output = {@code z.string()}（MCPTool.ts:20 outputSchema），故 Java 签名取 {@code String}
     * （Tool 接口无 Output 泛型）。
     *
     * <p><b>消费方归属 N/A</b>：CC 唯一消费方是 UI（Messages.tsx fullscreen 点击展开门控），
     * Java 无前端，不虚构生产消费方（与 isDestructive/isOpenWorld RES-G2-01 受控残留同类处置）。
     *
     * @param content 工具结果文本（MCP 的 Output 即 string）
     * @return true = 超 3 行截断语义命中（有更多内容可展开）；default false = 永不截断
     */
    default boolean isResultTruncated(String content) {
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // P1.2 新增 1 个 default 方法 · 对齐 CC Tool.ts:368-371 aliases 字段
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 工具别名列表 · 对齐 CC {@code Tool.ts:368-371 aliases?: string[]}。
     *
     * <p><b>用途 (backward-compatibility 通道)</b>：当 Tool 被重命名时，老名进入
     * {@code aliases} 列表，{@link ToolRegistry} 查表时按 name + 任一 alias 都能反查到
     * 同一个 Tool. 这是 LLM 历史 transcript 的关键不变量：若没有 alias 通道，
     * LLM rename tool = 永久破坏历史 transcript（用户调过的"read"在新版找不到对应 Tool）.
     *
     * <p><b>CC L1 行为对齐</b>：
     * <pre>
     * // CC Tool.ts:346-352
     * export function toolMatchesName(tool, name) {
     *   return tool.name === name || (tool.aliases?.includes(name) ?? false)
     * }
     * // CC Tool.ts:355-360
     * export function findToolByName(tools, name) {
     *   return tools.find(t => toolMatchesName(t, name))
     * }
     * </pre>
     *
     * <p><b>Java idiom 升级 (L3)</b>：以 {@code default List<String> aliases()} 方法
     * 形式暴露，47 个现有 Tool 实现零修改仍可工作（继承 {@code List.of()} 默认值）。
     * 需要 alias 的 Tool（如 ReadFileTool 重命名后老用户调过"Read"）选择性 override.
     *
     * <p><b>约束 (与 CC 一致)</b>：
     * <ul>
     *   <li>name() 本身不在 aliases() 内（查表自动用 name + alias 双路径）</li>
     *   <li>alias 大小写敏感（CC 同样敏感，{@code toolMatchesName} 严格 === 比较）</li>
     *   <li>alias 不得含 null（ToolRegistry.register 会过滤并 log warn）</li>
     *   <li>alias 不得与既有 tool.name() 冲突（ToolRegistry.register 会过滤并 log warn）</li>
     * </ul>
     *
     * <p>默认 {@link List#of()}（空列表，无 alias）— 保持向后兼容。
     *
     * @return 别名列表（不可变，可为空），元素为非 null 字符串
     */
    default List<String> aliases() {
        return List.of();
    }

    // ════════════════════════════════════════════════════════════════════════
    // R32-b8 新增: mapToToolResultBlockParam default + SearchReadKind enum
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [R32-b8 #1] 工具结果 → Anthropic tool_result 块参数 · 对齐 CC
     * {@code Tool.ts:557-560 mapToolResultToToolResultBlockParam(content, toolUseID)}.
     *
     * <p>CC 端契约（必填方法，toolExecution.ts:1292 消费）:
     * <pre>
     * mapToolResultToToolResultBlockParam(content, toolUseID) {
     *   return {
     *     tool_use_id: toolUseID,
     *     type: 'tool_result',
     *     content,  // string | ContentBlockParam[]
     *     is_error? // CC 由 interrupted 等推导
     *   }
     * }
     * </pre>
     *
     * <p><b>G2 接线（DEL-G2-01）</b>: production 路径 {@code LlmAgentLoop.toolResultMessage}
     * 已由「renderToolResultPayloadText 旁路直拼」改为经本方法构造 tool_result 块
     * （AgentLoopContext 按 toolName 解析 Tool 实例，对齐 CC toolExecution.ts:1292
     * {@code tool.mapToolResultToToolResultBlockParam(result.data, toolUseID)}）。
     *
     * <p><b>[IMP-C2] toolUseId/isError 参数透传</b>（组 2-1 拍板）: CC mapper 签名
     * {@code (content, toolUseID)} 显式接收 toolUseID；is_error 由错误路径推导。Java 端
     * ToolResult 已删除 toolUseId/isError 字段，本方法显式接收二者（对齐 CC 参数透传）。
     * 调用方（LlmAgentLoop.toolResultMessage / StreamingToolExecutor）从调用块
     * （{@code ToolUseBlock.id()}）与执行路径推导后传入。
     *
     * <p><b>默认实现（Java 兜底，非 CC 必填的对抗）</b>: CC 契约 per-tool 必填、无默认
     * （Tool.ts DefaultableToolKeys 不含本方法），但 Java 60+ 工具全量 override 超出
     * G2 session 安全范围（G2.md §9 登记范围扩展决策）——默认实现复用
     * {@link ToolResult#renderToolResultPayloadText} 渲染通用文本 content，保证未 override
     * 工具仍有合法 tool_result 块（不破坏契约）；Bash/Edit/Write/EnterPlanMode/
     * ExitPlanMode/RemoteTrigger/SkillTool 已按 CC 各自 per-tool 实现 override。
     *
     * @param result    工具执行结果 ({@link ToolResult ToolResult&lt;T&gt;}; newMessages/contextModifier/mcpMeta 已折入)
     * @param toolUseId 工具调用 ID（CC original: toolUseID，mapper 参数透传）
     * @param isError   是否错误（CC original: is_error，错误路径推导）
     * @return tool_result 块（tool_use_id/type/content/is_error）；result null → null
     */
    default ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (result == null) {
            return null;
        }
        // 默认兜底: 复用 renderToolResultPayloadText 渲染通用文本 content（未 override 工具合法回退）
        String content = ToolResult.renderToolResultPayloadText((ToolResult<?>) result);
        if (LoggerFactory.getLogger(Tool.class).isDebugEnabled()) {
            LoggerFactory.getLogger(Tool.class).debug(
                "Tool.mapToToolResultBlockParam 默认兜底: toolUseId={} payloadLen={}（CC 契约 per-tool 必填, 本工具未 override）",
                toolUseId, content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 嵌套类型
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [IT-5] 输入未知键运行时策略三态 · 对齐 CC zod v4 {@code unknownKeys}
     * （z.object strip / z.strictObject reject / .passthrough() accept）。
     *
     * <p>与广告层（{@code additionalProperties}）解耦：默认 {@link #UNSPECIFIED}
     * 时 validator 仍以广告 {@code additionalProperties=false} 为运行时拒绝开关
     * （兼容 IT-3 已交付行为，覆盖 CC 36 处 z.strictObject 工具）；工具显式声明
     * {@link #STRIP} / {@link #PASSTHROUGH} 才放行未知键（IT-5 五处 CC z.object /
     * strictObject().passthrough() 对应工具）。
     */
    enum UnknownKeysPolicy {
        /** 未声明 · 跟随广告层（additionalProperties=false 即拒绝）——默认值。 */
        UNSPECIFIED,
        /** 放行未知键（等价 CC {@code z.object} strip，不报 unrecognized_keys）。 */
        STRIP,
        /** 强制拒绝未知键（等价 CC {@code z.strictObject}，无视广告层）。 */
        STRICT,
        /** 放行并保留未知键（等价 CC {@code .passthrough()}）。 */
        PASSTHROUGH
    }

    /**
     * [R32-b8 #2] 工具的搜索/读取类型 4 态分类 · 对齐 CC {@code Tool.ts:429-433}
     * {@code isSearchOrReadCommand(input)} 返回的 {@code {isSearch, isRead, isList?}} 联合字段.
     *
     * <p>CC 端语义映射:
     * <ul>
     *   <li>{@link #NONE}      → CC: {@code {isSearch: false, isRead: false, isList: undefined}}
     *       —— 非搜索/读取操作，不折叠显示</li>
     *   <li>{@link #IS_READ}   → CC: {@code {isSearch: false, isRead: true}}
     *       —— 读取操作（cat / head / tail / file read），UI 折叠到一行</li>
     *   <li>{@link #IS_SEARCH} → CC: {@code {isSearch: true,  isRead: false}}
     *       —— 搜索操作（grep / find / glob patterns），UI 折叠到一行</li>
     *   <li>{@link #IS_LIST}   → CC: {@code {isList: true}}
     *       —— 目录列表操作（ls / tree / du），BashTool 生产使用（BashTool.tsx:72）</li>
     * </ul>
     *
     * <p><b>WHY 4 态而非 3 态</b>: 任务 brief 说 "{NONE, IS_READ, IS_LIST}" 3 态, 但
     * (a) CC GlobTool 实为 IS_SEARCH 而非 IS_LIST (D1 校正); (b) IS_LIST 对齐 CC
     * isList 字段，BashTool ls/tree/du 生产返回（BashTool.tsx:146/156/170）.
     */
    enum SearchReadKind {
        /** 非搜索 / 非读取操作 —— 默认值，不折叠显示. */
        NONE,
        /** 读取操作 (cat / head / tail / file read). */
        IS_READ,
        /** 搜索操作 (grep / find / glob patterns). */
        IS_SEARCH,
        /** 目录列表操作 (ls / tree / du) —— BashTool 生产返回（BashTool.tsx:72 BASH_LIST_COMMANDS）. */
        IS_LIST
    }

    /**
     * 工具执行进度 · 对齐 CC {@code ToolProgress<P>} 的 toolUseID + data 契约。
     *
     * @param toolUseId 产生进度的工具调用 ID
     * @param data      工具自定义进度载荷
     */
    record ToolProgress(String toolUseId, Object data) {}

    /**
     * 工具级验证结果 · 对齐 CC {@code Tool.ts:95-101 ValidationResult} 3 字段契约
     * （{@code { result: true } | { result: false, message, errorCode }}）。
     *
     * <p>{@code OK = 通过}；非 {@code OK} = {@code errorCode + message} 注入 LLM。
     *
     * <p><b>[IMP-C4] DC-A1-02</b>: {@code meta} 第 4 分量 + {@code passWithMeta} 工厂已删除
     * （EV-A1-007）——CC Tool.ts:95-101 类型未声明 meta、toolExecution.ts:683-733 只消费
     * result/message/errorCode，meta 属「形状对齐」死权重（无消费者）。Java record 回归 3 字段契约；
     * 写侧 EditFileTool 成功收尾改道 pass()（actualOldString 属 CC FileEditTool.ts:361 的
     * TS 结构化多余字段，Java 无需承接）。
     *
     * @param ok        是否通过验证
     * @param errorCode 错误码（{@code !ok} 时必填，用于分类，如 {@code "PATH_ESCAPE"}）
     * @param message   错误消息（{@code !ok} 时必填，给 LLM 看）
     */
    record ValidationResult(boolean ok, String errorCode, String message) {

        /**
         * compact constructor：不变量保护——{@code !ok} 时 errorCode + message 都必填。
         *
         * <p>WHY: errorCode 为 null 会让上游无法按类型分支处理
         * （{@code PATH_ESCAPE} 区别于 {@code SCHEMA_INVALID}）；message 为 blank
         * 会让 LLM 看到空解释无法自纠。
         */
        public ValidationResult {
            if (!ok) {
                if (errorCode == null || errorCode.isBlank()) {
                    throw new IllegalArgumentException(
                        "ValidationResult.errorCode is required when !ok");
                }
                if (message == null || message.isBlank()) {
                    throw new IllegalArgumentException(
                        "ValidationResult.message is required when !ok");
                }
            }
        }

        /** 验证通过（3 字段契约成功态）。 */
        public static ValidationResult pass() {
            return new ValidationResult(true, null, null);
        }

        /**
         * 验证失败。
         *
         * @param errorCode 错误码（必填，用于分类，如 {@code "PATH_ESCAPE"}）
         * @param message   错误消息（必填，给 LLM 看）
         */
        public static ValidationResult fail(String errorCode, String message) {
            return new ValidationResult(false, errorCode, message);
        }
    }
}
