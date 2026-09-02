package com.nexusai.application.agent.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseContext.ReadState;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Magic Doc 自动更新器 · 对齐 CC services/MagicDocs/magicDocs.ts:114-212 updateMagicDoc.
 *
 * <p>L1 语义（Session L 升级）：
 * <ol>
 *   <li>读文件 → 检测 magic doc header（委托 {@link MagicDocDetector}）</li>
 *   <li>用 {@link MagicDocsPrompts#buildMagicDocsUpdatePrompt} 构造 prompt
 *       （对齐 CC buildMagicDocsUpdatePrompt；消除此前自造 buildPrompt 的双 prompt，规则七）</li>
 *   <li>调 LLM（生产 LLM 或测试 callback）</li>
 *   <li>写回：<b>无条件走 {@link EditFileTool}</b>（对齐 CC magicDocs.ts:172-192 canUseTool
 *       仅允许 Edit + Edit file_path 精确匹配被追踪 doc 路径）。
 *       <b>Session L 升级：</b> 删除原 {@code Files.writeString} 降级直写路径，
 *       EditFileTool 强制注入，零降级直写（CC 源生语义）。</li>
 *   <li><b>路径白名单守卫</b>：目标路径必须精确等于 {@code trackedDocPath}，否则拒绝
 *       （对齐 CC :172-192 canUseTool 路径精确匹配语义）</li>
 * </ol>
 *
 * <p>L2 契约 (6 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 文件首行 {@code # MAGIC DOC: <title>} 才更新</li>
 *   <li><b>A2 Golden Trace</b>: read → detect → buildPrompt(CC 对齐) → llm.chat → EditFileTool 写回</li>
 *   <li><b>A3</b>: 写回前路径白名单检查</li>
 *   <li><b>A4</b>: 检测器 → prompt → LLM → writer 4 步严格顺序</li>
 *   <li><b>A5</b>: 写回失败回滚原内容（EditFileTool 失败 → 原文不变）</li>
 *   <li><b>A6</b>: NOT_STARTED → RUNNING → SUCCESS/FAILED, 单文件互斥</li>
 * </ul>
 */
@Component
public class MagicDocUpdater {

    private static final Logger log = LoggerFactory.getLogger(MagicDocUpdater.class);

    /**
     * [L+ R12] 静态 ObjectMapper · 替代 writeViaEditTool 内部每次 {@code new ObjectMapper()}.
     *
     * <p>WHY 静态化:
     * <ul>
     *   <li>ObjectMapper 创建成本不低 (类型工厂初始化 ~50ms), writeViaEditTool 是热路径
     *       (每次 magic doc 更新都调一次), 静态复用避免重复初始化.</li>
     *   <li>本类所有 JSON 序列化都走无状态序列化 (无 ObjectMapper 配置定制), 静态
     *       共享语义安全 — Jackson ObjectMapper 自身线程安全 (官方文档保证).</li>
     *   <li>对齐 CC 端 {@code JSON.stringify} 同步无对象复用语义 (JS 单线程无需复用,
     *       Java 多线程需要).</li>
     * </ul>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次更新状态 · 对齐 CC magicDocs.ts MagicDocRun state. */
    public enum RunState { NOT_STARTED, RUNNING, SUCCESS, FAILED }

    /** 更新结果. */
    public record UpdateResult(boolean updated, Optional<String> error, RunState state) {
        public static UpdateResult skipped() {
            return new UpdateResult(false, Optional.empty(), RunState.SUCCESS);
        }
        public static UpdateResult ok() {
            return new UpdateResult(true, Optional.empty(), RunState.SUCCESS);
        }
        public static UpdateResult failed(String msg) {
            return new UpdateResult(false, Optional.of(msg), RunState.FAILED);
        }
    }

    private final MagicDocDetector detector;
    /** 可选 LLM 回调 (测试 stub 注入, 替代真实 LLM 端点). */
    private final AtomicReference<Function<String, String>> llmCallbackOverride = new AtomicReference<>();
    /** 真实 LLM endpoint (Spring 注入). */
    private final LlmProviderFactory providerFactory;
    /** [RV14B-WIRE-03] 共享配置解析器 · model 名 → 真实 (ProviderConfig, providerType)。 */
    private final com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver;
    /** 兜底模型名（字面量 "haiku" → resolver 映射 settings fast → DB 名）。 */
    private final String model;
    /**
     * [Session L] 强制注入的 EditFileTool · 对齐 CC magicDocs.ts:172-192 canUseTool
     * 仅允许 Edit 工具，无降级直写路径。构造器 {@link #MagicDocUpdater(MagicDocDetector, EditFileTool)}
     * 与 {@link #MagicDocUpdater(MagicDocDetector, LlmProviderFactory, EditFileTool)} 均会
     * {@code Objects.requireNonNull} 校验，null 立即抛 {@link IllegalArgumentException}（规则十二·显式失败）。
     */
    private final EditFileTool editFileTool;

    /** Spring @Autowired 构造器 · 强制 EditFileTool · [RV14B-WIRE-03] 注入 ModelConfigResolver。 */
    @Autowired
    public MagicDocUpdater(MagicDocDetector detector, LlmProviderFactory providerFactory,
                           EditFileTool editFileTool,
                           com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver) {
        this(detector, providerFactory, editFileTool, "haiku", modelConfigResolver);
    }

    /** 测试构造函数 (允许注入 model, 强制 EditFileTool). */
    public MagicDocUpdater(MagicDocDetector detector, LlmProviderFactory providerFactory,
                           EditFileTool editFileTool, String model) {
        this(detector, providerFactory, editFileTool, model, null);
    }

    /** 完整构造器 · 强制 EditFileTool · [RV14B-WIRE-03] resolver 可 null（测试兜底）。 */
    public MagicDocUpdater(MagicDocDetector detector, LlmProviderFactory providerFactory,
                           EditFileTool editFileTool, String model,
                           com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver) {
        this.detector = detector;
        this.providerFactory = providerFactory;
        // [Session L] 构造器强制 requireNonNull —— CC magicDocs.ts:172-192 canUseTool 严格仅 Edit,
        // 零降级直写。原 writeDirect 方法已删除，editFileTool 缺失会导致 update() 调用链无声 NPE，
        // 提前到构造期显式失败（规则十二）.
        this.editFileTool = Objects.requireNonNull(editFileTool,
            "[Session L] EditFileTool 强制注入 (CC magicDocs.ts:172-192 canUseTool 仅 Edit, 无降级直写路径)");
        this.model = model;
        this.modelConfigResolver = modelConfigResolver;
    }

    /**
     * [Session L] 测试便捷构造器 (无 LLM 端点, 强制 EditFileTool).
     *
     * <p>WHY 强制 EditFileTool：写回路径不走 Files.writeString；任何调用 {@code update(...)} 写回
     * 必经 {@link EditFileTool#execute(ToolUseBlock)}。测试路径允许短路（detect 失败 → skipped）
     * 但写回断言必须提供真实的 EditFileTool。
     */
    public MagicDocUpdater(MagicDocDetector detector, EditFileTool editFileTool) {
        this(detector, null, editFileTool, "test-model", null);
    }

    /** 注入 LLM 回调 (供测试 stub). */
    public void setLlmCallback(Function<String, String> callback) {
        this.llmCallbackOverride.set(callback);
        if (log.isInfoEnabled()) {
            log.info("[MagicDocUpdater] llmCallback override set (testing mode)");
        }
    }

    /**
     * 更新单个 magic doc 文件（<b>内容由调用方读取</b>）· 对齐 CC updateMagicDoc
     * magicDocs.ts:134-137 语义（FileReadTool.call 读一次 → detect → buildPrompt
     * 用同一份最新 content）。
     *
     * <p><b>WHY 不在此重读（Session L）</b>：MagicDocsService.updateSingle 已经经
     * ReadFileTool 读到最新内容，此处若再 {@code Files.readString} 就是双读——且
     * detect / prompt / EditFileTool old_string 必须用同一份 content（CC 语义），
     * 双读有 TOCTOU 风险（文件在两次读之间被外部改动）。
     *
     * @param filePath       magic doc 文件路径
     * @param context        对话上下文 (供 prompt 拼接)
     * @param trackedDocPath 被追踪的 doc 路径（白名单守卫基准）
     * @param editFileTool   EditFileTool 实例（<b>必填</b>，CC canUseTool 仅 Edit；
     *                       构造期字段已 requireNonNull，此处 null 也会显式失败）
     * @param currentContent 调用方已读取的最新文件内容（不可为 null）
     */
    public UpdateResult updateWithContent(Path filePath, String context,
                                          Path trackedDocPath, EditFileTool editFileTool,
                                          String currentContent) {
        if (currentContent == null) {
            return UpdateResult.failed("currentContent is null: " + filePath);
        }
        var detectionOpt = detector.detect(currentContent);
        if (detectionOpt.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[MagicDocUpdater] 非 magic doc: {}", filePath);
            }
            return UpdateResult.skipped();
        }
        var detection = detectionOpt.get();
        if (log.isInfoEnabled()) {
            log.info("[MagicDocUpdater] 更新 title='{}' instructions='{}' file={} trackedAs={}",
                detection.title(), detection.instructions(), filePath, trackedDocPath);
        }

        // A4 Tool Sequence: buildPrompt(CC 对齐) → llm → write
        String instruction = buildPrompt(filePath.toString(), detection.title(),
            detection.instructions(), currentContent);
        // [prompt-align TOOLS-03] forkContextMessages 注入对齐：对话上下文作为独立前置块
        //  置于指令消息之前（对齐 CC runAgent forkContextMessages: messages + promptMessages:
        //  [createUserMessage]，magicDocs.ts:199-211）——不再拼进 currentDoc（消除
        //  「<!-- Recent conversation context -->」注释拼接，避免上下文混入
        //  <current_doc_content> 标签内）。Java chatWithOptions 单字符串 user 通道 → 前置块
        //  与指令合并为同一 user 消息（已登记降级，见 MagicDocsService.MAX_CONTEXT_MESSAGES）。
        String prompt = (context != null && !context.isBlank())
            ? context + "\n\n" + instruction
            : instruction;
        String updated;
        try {
            Function<String, String> override = llmCallbackOverride.get();
            if (override != null) {
                updated = override.apply(prompt);
            } else {
                updated = callRealLlm(prompt);
            }
        } catch (Exception e) {
            return UpdateResult.failed("LLM call failed: " + e.getMessage());
        }
        if (updated == null || updated.isBlank()) {
            return UpdateResult.failed("LLM returned empty content");
        }

        // 路径白名单守卫（CC canUseTool :172-192 精确匹配语义）
        String tracked = trackedDocPath == null ? null : trackedDocPath.toString();
        if (tracked != null && !tracked.equals(filePath.toString())) {
            if (log.isWarnEnabled()) {
                log.warn("[MagicDocUpdater] 路径白名单拒绝: trackedDoc={} 但 filePath={} (CC canUseTool 对齐)",
                    tracked, filePath);
            }
            return UpdateResult.failed(
                "MagicDoc path whitelist rejected: tracked=" + tracked + " actual=" + filePath);
        }

        // [Session L] 无条件走 EditFileTool——CC canUseTool 仅允许 Edit 工具（magicDocs.ts:172-192）.
        // 原 writeDirect Files.writeString 降级路径已删除。优先用入参（MagicDocsService 注入），
        // 回退到构造期强制 requireNonNull 注入的字段，null → 显式失败（规则十二）.
        EditFileTool tool = editFileTool != null ? editFileTool : this.editFileTool;
        return writeViaEditTool(requireEditFile(tool), filePath, tracked, currentContent, updated);
    }

    /**
     * 兼容入口（自身读文件）· 供独立调用（MagicDocUpdaterTest / 定时任务等无
     * ReadFileTool 上下文的场景）；生产链路 MagicDocsService.updateSingle 走
     * {@link #updateWithContent} 避免双读。
     *
     * <p>WHY 保留自身读取：updater 是 Spring bean，文件读取的单一职责在
     * ReadFileTool；但独立调用方（非 hook 链路）没有已读内容可传，保留此入口
     * 自读自更（Files.readString 仅此入口使用）。
     */
    public UpdateResult update(Path filePath, String context,
                               Path trackedDocPath, EditFileTool editFileTool) {
        if (!Files.isRegularFile(filePath)) {
            return UpdateResult.failed("not a regular file: " + filePath);
        }
        String original;
        try {
            original = Files.readString(filePath);
        } catch (IOException e) {
            return UpdateResult.failed("read failed: " + e.getMessage());
        }
        return updateWithContent(filePath, context, trackedDocPath, editFileTool, original);
    }

    /**
     * [Session L] EditFileTool 显式守卫 · CC canUseTool 仅允许 Edit 工具，零降级直写.
     *
     * <p>WHY 既校验入参又校验字段：MagicDocsService.java:220 仍按 4 参 update() 契约传参，
     * 测试也可以只通过构造期强制注入（不传参）。两路汇聚到 {@link #writeViaEditTool} 前
     * 必须确保非 null——否则将走进 writeViaEditTool 内部 NPE，违反规则十二。
     *
     * @param tool 待校验 EditFileTool
     * @return 非 null 的 tool（链式表达）
     * @throws IllegalArgumentException 工具缺失时显式失败
     */
    private EditFileTool requireEditFile(EditFileTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException(
                "[Session L] EditFileTool 强制注入 (CC magicDocs.ts:172-192 canUseTool 仅 Edit, 无降级直写路径)");
        }
        return tool;
    }

    /**
     * 调真实 LLM endpoint · 对齐 CC MagicDocs runAgent prompt → chat.
     *
     * <p>[RV14B-WIRE-03] 替换原 {@code getProvider(ProviderConfig.empty())} 恒 mock：模型名
     * "haiku" 字面量非 DB models.name → 经 resolver.resolveFastModelName 映射 settings fast/main
     * → DB 名，再 resolve 真实 (config, providerType)。解析失败 → <b>显式抛异常</b>（同步路径
     * 规则十二 Fail loud，调用方 updateWithContent catch → UpdateResult.failed），不落 mock。
     */
    String callRealLlm(String prompt) {
        if (providerFactory == null) {
            throw new IllegalStateException("LlmProviderFactory not available (test mode)");
        }
        com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolved = resolveModelConfig();
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            log.warn("[MagicDocUpdater] 模型配置解析失败（model={}），显式失败（warn+throw 不落 mock，RV14B-GATE-01）",
                model);
            throw new IllegalStateException("MagicDoc model config unavailable: " + model);
        }
        String modelName = resolveModelName();
        LlmProvider provider = providerFactory.getProvider(resolved.config(), resolved.providerType());
        String systemPrompt = "You are a code documentation updater. "
            + "Update the given magic document with new information from the conversation context. "
            + "Return ONLY the updated document content with no preamble.";
        return provider.chat(resolved.config(), modelName, systemPrompt, prompt);
    }

    /**
     * [RV14B-WIRE-03] 解析 magic doc 模型真实配置。
     *
     * <p>CC magicDocs.ts:104 {@code model: 'sonnet'}（字面量）语义 → Java 端 "haiku" 字面量经
     * settings fast/main 映射为 DB 名再 resolve。resolver 未注入（测试）→ 返回 null → callRealLlm
     * 显式失败（测试路径用 llmCallbackOverride 短路，不走真实 LLM）。
     *
     * @return 真实 (config, providerType)；解析失败 → null
     */
    private com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolveModelConfig() {
        if (modelConfigResolver == null) {
            log.warn("[MagicDocUpdater] ModelConfigResolver 未注入，跳过配置解析（warn）");
            return null;
        }
        String modelName = resolveModelName();
        if (modelName == null || modelName.isBlank()) return null;
        return modelConfigResolver.resolve(modelName);
    }

    /**
     * [RV14B-WIRE-03] 解析模型 DB 名 · "haiku" 字面量经 settings fast/main 映射。
     *
     * @return DB 可用模型名；resolver 未注入 → 原字面量（测试兜底）
     */
    private String resolveModelName() {
        if (modelConfigResolver == null) return model;
        String fastName = modelConfigResolver.resolveFastModelName(model);
        return fastName != null && !fastName.isBlank() ? fastName : model;
    }

    /**
     * 构造 LLM 指令 prompt（不含对话上下文）· 委托
     * {@link MagicDocsPrompts#buildMagicDocsUpdatePrompt}。
     *
     * <p>[prompt-align TOOLS-03] 变更：
     * <ol>
     *   <li><b>stub loader → 真实读</b>：claudeConfigHomeDir 传
     *       {@code ClaudePaths.getClaudeConfigHomeDir()}（~/.claude，对齐 CC
     *       getClaudeConfigHomeDir envUtils.ts:7-14）+ {@link MagicDocsPrompts#defaultFileLoader}
     *       （Files.readString）→ {@code <configHome>/magic-docs/prompt.md} 真实加载、
     *       缺失/异常回落 {@link MagicDocsPrompts#DEFAULT_UPDATE_PROMPT_TEMPLATE}
     *       （CC prompts.ts:66-76 loadMagicDocsPrompt 语义）。旧 stub 恒抛 → 恒回落 default。</li>
     *   <li><b>context 参数移除</b>：对话上下文不再拼进 currentDoc（消除
     *       「<!-- Recent conversation context -->」注释拼接）——改由
     *       {@link #updateWithContent} 作为独立前置块置于指令之前（对齐 CC
     *       forkContextMessages，magicDocs.ts:199-211）。</li>
     *   <li><b>变量数注释修正</b>：CC prompts.ts buildMagicDocsUpdatePrompt 实为
     *       <b>4 个变量</b>（docContents/docPath/docTitle/customInstructions）——旧注释变量数笔误已修正。</li>
     * </ol>
     *
     * @param docPath       magic doc 路径（{{docPath}} 变量，prompts.ts:121 variables 对象；默认模板 {{docPath}} 位于 :13/:56）
     * @param title         文档标题（{{docTitle}} 变量）
     * @param instructions  文档作者自定义指令（{{customInstructions}} 变量，可空）
     * @param currentContent 已读文件内容（{{docContents}} 变量）
     * @return 渲染后指令 prompt（不含对话上下文）
     */
    String buildPrompt(String docPath, String title, String instructions,
                       String currentContent) {
        String mergedDoc = currentContent == null ? "" : currentContent;
        return MagicDocsPrompts.buildMagicDocsUpdatePrompt(
            com.nexusai.application.agent.skill.ClaudePaths.getClaudeConfigHomeDir(),
            MagicDocsPrompts::defaultFileLoader,
            mergedDoc,
            docPath == null ? "" : docPath,   // 对齐 CC prompts.ts {{docPath}} 变量 (L C-3 恢复)
            title == null ? "" : title,
            instructions
        );
    }

    /**
     * 通过 EditFileTool 写回 · 对齐 CC canUseTool 仅允许 Edit + 路径白名单.
     *
     * <p>策略：old_string = 原内容，new_string = LLM 输出，replace_all=false.
     * 若 old_string 不唯一 → EditFileTool 自带 fail；与 CC canUseTool 路径守卫语义一致.
     */
    /**
     * [L+ round 4] 写回走 ctx 路径 · 不再调 editTool.execute(call) 无 ctx (那条路被
     * 门禁拒绝). 构造一个最小 ToolUseContext, 把"已经读过文件"的事实播种进
     * readFileState, 让 EditFileTool 的 read-before-write + stale-write 门禁
     * 真实生效且不误拒. WHY 关键: CC 端 readFileState 是会话级 (QueryEngine.ts:191)
     * 且 parent→child 透传; Java 端 MagicDocUpdater 拿不到父 ctx (hook 链路只透传
     * PostSamplingContext 的折算摘要给 buildPrompt, 不含 ToolUseContext), 所以必须现场构造.
     *
     * <p>语义不变: 调用方 (MagicDocsService.updateSingle 经 ReadFileTool / 兼容入口
     * 自身 Files.readString) 已经在写回前拿到 original, 等价于 CC 端
     * "先 Read 再 Edit". 播种 readFileState 就是把这个事实告诉门禁.
     */
    private UpdateResult writeViaEditTool(EditFileTool editTool, Path filePath,
                                          String tracked, String original, String updated) {
        try {
            String relPath = tracked != null ? tracked : filePath.toString();
            // 构造 EditFileTool input：file_path/old_string/new_string/replace_all
            // [L+ R12] 复用静态 MAPPER, 避免每次 writeViaEditTool 调用 new ObjectMapper()
            // [IMP-D2] 键名对齐 CC：旧 path/old_text/new_text 已删除，消费方同步。
            ObjectNode input = MAPPER.createObjectNode();
            input.put("file_path", relPath);
            input.put("old_string", original);
            input.put("new_string", updated);
            input.put("replace_all", false);

            ToolUseBlock call = new ToolUseBlock(
                "magic-doc-edit-" + Integer.toHexString(System.identityHashCode(filePath)),
                editTool.name(),
                input);

            // [L+ round 4] 构造 ctx + 播种 readFileState.
            // relPath 用于 keyForReadFileState: 必须与 EditFileTool.execute
            // 内部所用的 relPath 一致 (L+ round 3 已共用 keyForReadFileState,
            // 这里再次走同一函数保证 key 派生零漂移).
            ToolUseContext ctx = buildSeededContext(editTool, relPath, original);
            ToolResult result = editTool.execute(call, ctx);
            if (com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(result.data())) {
                // [A1·退役 metadata] content() 已退役改 data(): ToolResult<String> cast 取 String
                String reason = result.data() instanceof String s ? s : String.valueOf(result.data());
                if (log.isWarnEnabled()) {
                    log.warn("[MagicDocUpdater] EditFileTool 写回失败: path={} reason={}",
                        relPath, reason);
                }
                return UpdateResult.failed("EditFileTool write failed: " + reason);
            }
            if (log.isInfoEnabled()) {
                log.info("[MagicDocUpdater] EditFileTool 写回成功: path={} newChars={}",
                    relPath, updated.length());
            }
            return UpdateResult.ok();
        } catch (Exception e) {
            if (log.isErrorEnabled()) {
                log.error("[MagicDocUpdater] EditFileTool 调用异常: path={}", filePath, e);
            }
            return UpdateResult.failed("EditFileTool invocation error: " + e.getMessage());
        }
    }

    /**
     * [L+ round 4] 构造最小 ctx 并播种 readFileState.
     *
     * <p>WHY 必须用 editTool.pathGuard() 派生 key: 不同 workspace (测试 TempDir vs 生产)
     * 的 guard.resolve() 解析出的绝对路径不同, 错位会让 read-before-write 门禁永远不命中.
     *
     * <p>content 必须 CRLF 归一化为 LF-only · 对齐 ReadFileTool / EditFileTool
     * 写入 cache 的归一化形式 (L+ round 3), 否则 CRLF 文件的 stale-write 内容兜底比对
     * 会误判.
     *
     * <p>mtime: 取文件当前 mtime (ms), 用于 stale-write 比对; 与 Read 工具写入
     * ReadState.mtimeMillis() 同语义.
     *
     * <p>offset/limit=null · 对齐 CC FileEditTool.ts:520 写回后 ReadState 形态
     * (即所谓"full read"语义). isPartialView=false, 让 read-before-write 门禁通过.
     */
    private ToolUseContext buildSeededContext(EditFileTool editTool, String relPath, String original) {
        // 派生 session/agent id: MagicDocUpdater 自身没持有 ctx, 构造随机 id.
        // (跨调用不复用, 每个 magic doc 写回独立 ctx — 走单文件原子写语义.)
        // [session-id-short] sessionId 统一 short 形态（sess-xxx）。
        UUID agentId = UUID.randomUUID();
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);

        // 派生 key: 必须用 editTool.pathGuard() 让 key 与 EditFileTool.execute 内部派生一致
        String key = ToolUseContext.keyForReadFileState(editTool.pathGuard(), relPath);

        // 取 mtime; 失败兜底 0 (stale-write 门禁会拒, MagicDocUpdater 写回失败回滚原文)
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(editTool.pathGuard().workdir().resolve(relPath)).toMillis();
        } catch (Exception e) {
            mtime = 0L;
        }
        // CRLF 归一化 (与 Read 侧 + Edit 写回侧一致)
        String normalizedContent = original == null ? "" : original.replace("\r\n", "\n");

        // [P-CC-02] 用 ToolUseContext.createFileStateCache() 替换原 new ConcurrentHashMap<>() —
        //   走 FileStateCache 双限真 LRU (100 条 + 25MB, 用户 2026-08-05 拍板严格对齐 CC),
        //   与父 cache 同容量配置 (单条 entry 不会触发驱逐).
        FileStateCache readFileState =
            ToolUseContext.createFileStateCache();
        readFileState.set(key, ReadState.full(mtime, normalizedContent));

        // [L+ round 4] 用 18 参兼容构造器 (ToolUseContext.java:398 含 readFileState 透传)
        // Stage 3.2 C2 + Stage 3.3 UI + Stage 3.4 session 字段传 null → compact ctor 兜底 noop.
        // [Session E] fork path 2 字段也由 18 参 compat ctor 末尾 null 兜底自动注入,
        //   无需 MagicDocUpdater 显式追加 (保持 18 参 compat 调用形态).
        ToolUseContext ctx = new ToolUseContext(
            agentId, sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            readFileState);
        if (log.isDebugEnabled()) {
            log.debug("[MagicDocUpdater] 播种 readFileState: key={} mtime={} contentChars={}",
                key, mtime, normalizedContent.length());
        }
        return ctx;
    }

}