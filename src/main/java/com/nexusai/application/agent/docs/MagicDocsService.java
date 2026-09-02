package com.nexusai.application.agent.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.tool.FileReadListener;
import com.nexusai.application.agent.tool.FileReadListenerRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Magic Docs 自动 hook 服务 · 对齐 CC {@code services/MagicDocs/magicDocs.ts initMagicDocs}.
 *
 * <h2>完整链路（Session L 后 = CC magicDocs.ts 全量对齐）</h2>
 * <pre>
 * [1] ReadFileTool.execute → text 分支成功 → FileReadListenerRegistry.notifyRead(event)
 *       → 本类 onFileRead：MagicDocDetector.detect → 命中则幂等登记 trackedMagicDocs
 *       （CC registerFileReadListener 回调 + registerMagicDoc :87-94 / :245-250）
 * [2] LlmAgentLoop 每轮 sampling 后 → PostSamplingHookRegistry.executeAll
 *       → 本类 onPostSampling（CC registerPostSamplingHook(updateMagicDocs) :252）
 *         门控：querySource==主线程 && 末轮 assistant 无 tool calls && tracked 非空
 *       → updateTrackedDocs：串行遍历 → updateSingle 经 ReadFileTool 重读
 *         → 重新 detect（未命中移除追踪）→ MagicDocUpdater.updateWithContent 写回
 * </pre>
 *
 * <p><b>CC 对齐修正（Session L）</b>：早期版本「挂载点由外部调用方在适当时机触发、
 * 不硬造 hook 框架」的假设错误——CC 真源 magicDocs.ts:252 明确
 * {@code registerPostSamplingHook(updateMagicDocs)}，Java 端 PostSamplingHookRegistry
 * + LlmAgentLoop:3085-3103 已有执行点，本类直接挂载（见 {@link #registerAsListener}）。
 *
 * <h2>CC USER_TYPE==='ant' 门控</h2>
 * <p>CC 真源用 env USER_TYPE 控制 init；Java 端用 {@code nexusai.magic-docs.enabled}
 * （默认 false）开关。等价语义。
 *
 * @see MagicDocDetector
 * @see MagicDocUpdater
 * @see FileReadListenerRegistry
 * @see PostSamplingHookRegistry
 */
@Component
public class MagicDocsService implements FileReadListener {

    private static final Logger log = LoggerFactory.getLogger(MagicDocsService.class);

    /** 渲染对话上下文时的最大消息条数（CC forkContextMessages 是全量，Java 直调取有界摘要防 prompt 爆炸）. */
    private static final int MAX_CONTEXT_MESSAGES = 10;
    /** 单条消息内容最大字符数（超出截断）. */
    private static final int MAX_CONTEXT_CHARS_PER_MESSAGE = 4000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 开关 · 对齐 CC magicDocs.ts:243 USER_TYPE==='ant' 门控.
     * Java 用配置开关替代 env var，默认 false（Session L 决策：先关，避免后台写回意外触发）.
     */
    @Value("${nexusai.magic-docs.enabled:false}")
    private boolean enabled;

    private final MagicDocDetector detector;
    private final MagicDocUpdater updater;
    private final FileReadListenerRegistry listenerRegistry;
    private final EditFileTool editFileTool;
    /**
     * [Session L] 重读文件的 ReadFileTool · 对齐 CC magicDocs.ts:134-137
     * {@code FileReadTool.call} 重读语义（复用同一套 validateInput / PathGuard /
     * 大小截断契约，而非裸 Files.readString 绕过校验链）。
     */
    private final ReadFileTool readFileTool;

    /** 幂等登记表 · 对齐 CC magicDocs.ts:87-94 registerMagicDoc. */
    private final ConcurrentMap<String, TrackedDoc> trackedMagicDocs = new ConcurrentHashMap<>();

    /** 追踪记录 · 对齐 CC magicDocs.ts:38-41 MagicDocInfo. */
    public record TrackedDoc(String path) {}

    /** hook 已注册标记 · 防止 @PostConstruct 重复触发时重复注册（PostSamplingHookRegistry 是静态表）. */
    private volatile boolean hookRegistered = false;

    /**
     * Spring 注入用构造器.
     */
    @Autowired
    public MagicDocsService(MagicDocDetector detector, MagicDocUpdater updater,
                            FileReadListenerRegistry listenerRegistry,
                            EditFileTool editFileTool,
                            ReadFileTool readFileTool) {
        this.detector = detector;
        this.updater = updater;
        this.listenerRegistry = listenerRegistry;
        this.editFileTool = editFileTool;
        this.readFileTool = readFileTool;
    }

    /** 测试便捷构造器（无 ReadFileTool → updateSingle 显式失败，绝无 Files.readString 降级路径）. */
    public MagicDocsService(MagicDocDetector detector, MagicDocUpdater updater,
                            FileReadListenerRegistry listenerRegistry,
                            EditFileTool editFileTool) {
        this(detector, updater, listenerRegistry, editFileTool, null);
    }

    /**
     * 启动 hook · 对齐 CC magicDocs.ts:242-254 initMagicDocs：
     * <ol>
     *   <li>{@code registerFileReadListener(...)} → Java {@code listenerRegistry.register(this)}</li>
     *   <li>{@code registerPostSamplingHook(updateMagicDocs)} → Java
     *       {@link PostSamplingHookRegistry#register}（门控+更新见 {@link #onPostSampling}）</li>
     * </ol>
     *
     * <p>WHY PostConstruct：Spring bean 装配完成后立即注册，模拟 CC initMagicDocs() 的启动注册行为。
     */
    @PostConstruct
    public void registerAsListener() {
        if (!enabled) {
            if (log.isInfoEnabled()) {
                log.info("[MagicDocsService] disabled by nexusai.magic-docs.enabled=false · 跳过 listener/hook 注册");
            }
            return;
        }
        if (listenerRegistry == null) {
            if (log.isWarnEnabled()) {
                log.warn("[MagicDocsService] listenerRegistry=null · 无法注册");
            }
            return;
        }
        listenerRegistry.register(this);
        // [Session L] 对齐 CC magicDocs.ts:252 registerPostSamplingHook(updateMagicDocs)——
        // 之前 updateTrackedDocs 是 0 生产调用点的死链路，现在挂到 post-sampling 执行链。
        if (!hookRegistered) {
            PostSamplingHookRegistry.register(this::onPostSampling);
            hookRegistered = true;
        }
        if (log.isInfoEnabled()) {
            log.info("[MagicDocsService] 已注册为 FileReadListener + PostSamplingHook · 等价 CC initMagicDocs USER_TYPE==='ant' 门控开启");
        }
    }

    /**
     * FileReadListener 入口 · 对齐 CC magicDocs.ts:245-250 registerFileReadListener 回调.
     *
     * <p><b>[L+ R2] 自 catch 业务异常</b>：L+ R2 删除 {@link FileReadListenerRegistry} 内的
     * try/catch RuntimeException 隔离（对齐 CC FileReadTool.ts:1042 裸调用 fail-fast 哲学）。
     * 本类作为 FileReadListener 实现方，必须在 onFileRead 内自 catch 业务异常, 避免
     * MagicDocs bug 炸 ReadFileTool 主流程（CLAUDE.md 规则十二·显式失败 + 业务隔离并存）.
     * 注：与 R2 哲学调整不冲突 — Registry 行为对齐 CC, listener 实现方保留 fail-soft.
     */
    @Override
    public void onFileRead(String filePath, String content) {
        // [IMP-C5] FileReadEvent 退役（TR-D1-⊕-2）→ CC 二元签名 (filePath, content)；
        //   content 为 ReadFileTool 本次读取的 range 内容（对齐 CC magicDocs.ts:245-250
        //   detectMagicDocHeader(content) 语义，W6）。
        if (!enabled || filePath == null || content == null) return;
        try {
            String path = filePath;
            if (log.isDebugEnabled()) {
                log.debug("[MagicDocsService] 收到文件读事件 path={} bytes={}", path, content.length());
            }
            Optional<MagicDocDetector.Detection> detection = detector.detect(content);
            if (detection.isPresent()) {
                // 幂等登记（CC :87-94）
                TrackedDoc prev = trackedMagicDocs.putIfAbsent(path, new TrackedDoc(path));
                if (prev == null && log.isInfoEnabled()) {
                    log.info("[MagicDocsService] 登记 magic doc: path={} title='{}'",
                        path, detection.get().title());
                }
            }
        } catch (Exception e) {
            // [L+ R2] 自 catch 业务异常 — listener 异常不能再炸主流程.
            // 保留 L session fail-soft 行为 (MagicDocsService 业务层隔离), 与 registry
            // fail-fast 哲学不冲突 (registry 暴露 listener bug, listener 自管业务异常).
            if (log.isWarnEnabled()) {
                log.warn("[MagicDocsService] onFileRead 业务异常已隔离: filePath={} err={}",
                    filePath, e.toString());
            }
        }
    }

    /**
     * Post-sampling hook 入口 · 对齐 CC magicDocs.ts:217-240 {@code updateMagicDocs}.
     *
     * <p>门控顺序与 CC 完全一致：
     * <ol>
     *   <li>{@code querySource !== 'repl_main_thread'} → return（CC :222-224）</li>
     *   <li>末轮 assistant 消息含 tool calls → return（CC :226-230，对话不空闲）</li>
     *   <li>trackedMagicDocs 空 → return（CC :232-235）</li>
     * </ol>
     *
     * <p>hook 异常自隔离（对齐 CC postSamplingHooks.ts:62-69 logError continue）——
     * PostSamplingHookRegistry.executeAll 异步 fire-and-forget，MagicDocs 更新 LLM 调用
     * 可能慢，绝不能阻塞 LlmAgentLoop 主链。
     */
    void onPostSampling(PostSamplingContext psContext) {
        try {
            // 门控 1: 仅主线程（CC :222-224 querySource !== 'repl_main_thread' → return）
            if (psContext == null || psContext.querySource() != QuerySource.REPL_MAIN_THREAD) {
                if (log.isDebugEnabled()) {
                    log.debug("[MagicDocsService] hook 门控跳过: querySource={}（非主线程）",
                        psContext == null ? "null" : psContext.querySource());
                }
                return;
            }
            // 门控 2: 末轮 assistant 无 tool calls（CC :226-230 hasToolCallsInLastAssistantTurn）
            if (hasToolCallsInLastAssistantTurn(psContext.messages())) {
                if (log.isDebugEnabled()) {
                    log.debug("[MagicDocsService] hook 门控跳过: 末轮 assistant 含 tool calls（对话不空闲）");
                }
                return;
            }
            // 门控 3: 有被追踪的 doc（CC :232-235）
            if (trackedMagicDocs.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[MagicDocsService] hook 门控跳过: trackedMagicDocs 为空");
                }
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("[MagicDocsService] post-sampling 触发更新: tracked={}", trackedMagicDocs.size());
            }
            updateTrackedDocs(psContext);
        } catch (Exception e) {
            // 对齐 CC postSamplingHooks.ts:62-69 logError continue — hook 异常隔离，绝不炸主链
            if (log.isWarnEnabled()) {
                log.warn("[MagicDocsService] post-sampling hook 异常已隔离: {}", e.toString());
            }
        }
    }

    /**
     * 对齐 CC {@code utils/messages.ts:341-353 hasToolCallsInLastAssistantTurn}：
     * 从消息末尾向前找第一条 assistant 消息，其 toolCalls 非空 → true（对话仍在执行工具，不空闲）。
     * <p><b>[IMP-HOOKS-S7 D12] 已知差异已消除</b>：CC 的 hook 收到的是<b>包含本次刚采样消息</b>
     * 的列表（REPLHookContext.messages，query.ts:1001-1008 [...messagesForQuery, ...assistantMessages]）；
     * Java 端 LlmAgentLoop :4143 postSamplingMessages 同样在 query 输入消息末尾追加当前
     * assistant message（含本次采样输出）后构造 PostSamplingContext —— "末轮"判定含刚采样消息，
     * 行为与 CC 一致（旧注释"params.messages() 本轮采样前快照"为过时断言，已不成立）。
     */
    static boolean hasToolCallsInLastAssistantTurn(List<ChatMessageDto> messages) {
        if (messages == null) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                return m.toolCalls() != null && !m.toolCalls().isEmpty();
            }
        }
        return false;
    }

    /**
     * 更新所有追踪的 docs · 对齐 CC updateMagicDocs :217-240.
     *
     * <p>挂载点：{@link #onPostSampling}（post-sampling hook）。方法本身保持公开，
     * 供定时任务等外部调用方复用同一入口。
     *
     * @param psContext post-sampling 上下文（messages → 对话摘要透传给 updater，
     *                  对齐 CC :118-119 REPLHookContext 全量透传的 Java 折算）
     * @return 串行处理结果汇总
     */
    public synchronized UpdateSummary updateTrackedDocs(PostSamplingContext psContext) {
        // synchronized: 对齐 CC sequential(...)（magicDocs.ts:217）串行化语义——
        // post-sampling hook 异步并发触发时，文档更新不能并行写同一批文件。
        if (!enabled) {
            if (log.isDebugEnabled()) {
                log.debug("[MagicDocsService] updateTrackedDocs 被调用但已禁用");
            }
            return new UpdateSummary(0, 0, 0, List.of());
        }
        if (trackedMagicDocs.isEmpty()) {
            return new UpdateSummary(0, 0, 0, List.of());
        }
        String context = renderConversationContext(psContext);
        int updated = 0, skipped = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        // 串行（sequential）· 对齐 CC magicDocs.ts:217 sequential(...)
        for (TrackedDoc doc : List.copyOf(trackedMagicDocs.values())) {
            MagicDocUpdater.UpdateResult result = updateSingle(doc, context);
            switch (result.state()) {
                case SUCCESS -> {
                    if (result.updated()) updated++;
                    else skipped++;
                }
                case FAILED -> {
                    failed++;
                    failures.add(doc.path() + ": " + result.error().orElse("unknown"));
                }
                default -> { /* NOT_STARTED/RUNNING 不应到达这里 */ }
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[MagicDocsService] updateTrackedDocs 完成: updated={} skipped={} failed={}",
                updated, skipped, failed);
        }
        return new UpdateSummary(updated, skipped, failed, List.copyOf(failures));
    }

    /**
     * 从 post-sampling 上下文渲染对话摘要 · 对齐 CC magicDocs.ts:118-119
     * （CC 把 REPLHookContext 的 messages 全量透传给 forked agent；Java 是
     * LlmProviderFactory.chat 直调，只能折算为有界字符串——取最近
     * {@value #MAX_CONTEXT_MESSAGES} 条消息，每条截断
     * {@value #MAX_CONTEXT_CHARS_PER_MESSAGE} 字符，防 prompt 爆炸）。
     *
     * <p><b>[prompt-align TOOLS-03] 已登记降级</b>：CC runAgent 以
     * {@code forkContextMessages: messages}（magicDocs.ts:195-208）把全量对话作为
     * <b>独立 fork 上下文消息</b>注入子代理（置于指令消息之前）；Java 直调
     * {@code provider.chat} 单 user 字符串通道无独立 fork 消息 → 有界 {@value #MAX_CONTEXT_MESSAGES}
     * 条摘要经 {@link MagicDocUpdater#updateWithContent} 作为<b>独立前置块</b>置于指令之前
     * （对齐 fork 顺序）。降级点：有界截断 + 与指令合并为同一 user 消息（非 CC 独立 fork 消息）。
     */
    private static String renderConversationContext(PostSamplingContext psContext) {
        if (psContext == null || psContext.messages().isEmpty()) {
            return "";
        }
        List<ChatMessageDto> messages = psContext.messages();
        int from = Math.max(0, messages.size() - MAX_CONTEXT_MESSAGES);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < messages.size(); i++) {
            ChatMessageDto m = messages.get(i);
            String role = m.role() == null ? "unknown" : m.role().name();
            String content = m.content() == null ? "" : m.content();
            if (content.length() > MAX_CONTEXT_CHARS_PER_MESSAGE) {
                content = content.substring(0, MAX_CONTEXT_CHARS_PER_MESSAGE) + "...(truncated)";
            }
            sb.append('[').append(role).append("] ").append(content).append('\n');
        }
        return sb.toString();
    }

    /**
     * 更新单个 doc · 对齐 CC updateMagicDoc :114-212.
     *
     * <p>3 个关键步骤：
     * <ol>
     *   <li>经 {@link ReadFileTool} 重读（若不可读 → 移除追踪，对齐 CC :142-153）</li>
     *   <li>重新 detect（未命中 → 移除追踪，对齐 CC :155-161）</li>
     *   <li>委派 updater.updateWithContent（已读内容直传，避免双读；路径白名单守卫由 updater 负责）</li>
     * </ol>
     */
    private MagicDocUpdater.UpdateResult updateSingle(TrackedDoc doc, String context) {
        // 步骤 1: 经 ReadFileTool 重读（对齐 CC magicDocs.ts:134-137 FileReadTool.call）。
        //   WHY 不用 Files.readString: CC 走 FileReadTool.call 复用同一套 validateInput /
        //   PathGuard / 截断语义；无 ctx 路径天然 full read（ReadFileTool.java:441-446
        //   跳过 dedup），等价 CC clone readFileState 删除该 doc 防 dedup 命中
        //   file_unchanged stub 的语义（magicDocs.ts:121-129）。
        //   readFileTool==null（测试便捷构造器）→ 显式失败，绝无 Files.readString 降级。
        if (readFileTool == null) {
            return MagicDocUpdater.UpdateResult.failed(
                "ReadFileTool not injected (test constructor) — 无降级直读路径");
        }
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", doc.path());
        input.put("offset", 1);
        // limit 传极大值 = 全量读：对齐 CC offset=1 limit=undefined（读到 EOF）。
        // Java 字符级 RESULT_SIZE_LIMIT=100k 截断可能截掉超长 magic doc，故显式放大。
        input.put("limit", Integer.MAX_VALUE);
        ToolUseBlock call = new ToolUseBlock(
            "magic-doc-read-" + Integer.toHexString(doc.path().hashCode()),
            readFileTool.name(), input);
        com.nexusai.application.agent.tool.AgentToolResult<?> readResult;
        try {
            readResult = readFileTool.execute(call, null);
        } catch (Exception e) {
            // execute 内部已兜 error；这里兜 unexpected 异常 —— CC :142-153 读失败 → 移除追踪
            if (log.isInfoEnabled()) {
                log.info("[MagicDocsService] ReadFileTool 读异常（移除追踪）: path={} err={}",
                    doc.path(), e.toString());
            }
            trackedMagicDocs.remove(doc.path());
            return MagicDocUpdater.UpdateResult.skipped();
        }
        if (com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(readResult.data())) {
            // CC :142-153 isFsInaccessible / "File does not exist" → 移除追踪
            // （含 workspace 外路径被 PathGuard 拒的 SecurityException → ToolResult.error 情况）
            // [IMP-C2] isError 字段删除（组 2-1 拍板），改按 data 错误文案判定（error 路径
            //   构造的 data 均为错误消息，fail-loud 语义）。
            if (log.isInfoEnabled()) {
                log.info("[MagicDocsService] 文件不可读（移除追踪）: path={} err={}",
                    doc.path(), readResult.data());
            }
            trackedMagicDocs.remove(doc.path());
            return MagicDocUpdater.UpdateResult.skipped();
        }
        Object data = readResult.data();
        // [IMP-C2] structuredOutput 折入 data（Map {summary, ...}）——从 Map 提取 raw content
        //   （"summary" 键 = 原 rawContent），非 text 分支（image/pdf/notebook）不可能是 magic doc。
        String currentDoc = null;
        if (data instanceof String s) {
            currentDoc = s;
        } else if (data instanceof java.util.Map<?, ?> m) {
            Object summary = m.get("summary");
            if (summary instanceof String ss) {
                currentDoc = ss;
            }
        }
        if (currentDoc == null) {
            // 非 text 分支（image/pdf/notebook）不可能是 magic doc —— CC :138-141 只认 type==='text'
            if (log.isInfoEnabled()) {
                log.info("[MagicDocsService] 非文本读取结果（移除追踪）: path={} type={}",
                    doc.path(), data == null ? "null" : data.getClass().getSimpleName());
            }
            trackedMagicDocs.remove(doc.path());
            return MagicDocUpdater.UpdateResult.skipped();
        }
        // [OPD-TOOL-06-3a] 剥除行号前缀（逆 addLineNumbers，对齐 CC file.ts:326-328 stripLineNumberPrefix）。
        //   MagicDocs 重检测 header 需要 raw content（CC magicDocs.ts:134-137 FileReadTool.call 返回
        //   data.file.content，无行号）；ReadFileTool.execute().data() 现为模型侧渲染文本（行号 + reminder），
        //   首行 "# MAGIC DOC:" 被 "N\t" 前缀污染 → detect 失配。剥行号还原 raw，恢复重检测。
        //   reminder 后缀为既有偏差（首行匹配不受影响，故不在此处剥）。
        currentDoc = stripLineNumberPrefixes(currentDoc);
        // 步骤 2: 重新 detect
        Optional<MagicDocDetector.Detection> detected = detector.detect(currentDoc);
        if (detected.isEmpty()) {
            // CC :155-161 header 消失 → 移除追踪
            if (log.isInfoEnabled()) {
                log.info("[MagicDocsService] magic doc header 已消失（移除追踪）: path={}", doc.path());
            }
            trackedMagicDocs.remove(doc.path());
            return MagicDocUpdater.UpdateResult.skipped();
        }
        // 步骤 3: 委派 updater（已读内容直传，路径白名单守卫由 updater 负责）
        Path filePath = Paths.get(doc.path());
        return updater.updateWithContent(filePath, context, filePath, editFileTool, currentDoc);
    }

    /** 更新汇总 · 给调用方做 telemetry / 日志用. */
    public record UpdateSummary(int updated, int skipped, int failed, List<String> failures) {}

    /**
     * [OPD-TOOL-06-3a] 剥除每行的行号前缀（逆 {@link ReadFileTool} addLineNumbers）·
     * CC original: stripLineNumberPrefix (file.ts:326-328) {@code line.match(/^\s*\d+[→\t](.*)$/)}。
     *
     * <p>兼容 compact（{@code N\t}）与 padded-arrow（{@code "     N→"}）两种格式；
     * 无前缀行（如 reminder 行）原样保留。
     */
    private static String stripLineNumberPrefixes(String content) {
        java.util.regex.Pattern prefix = java.util.regex.Pattern.compile("^\\s*\\d+[→\t](.*)$");
        StringBuilder sb = new StringBuilder();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            java.util.regex.Matcher m = prefix.matcher(lines[i]);
            sb.append(m.matches() ? m.group(1) : lines[i]);
        }
        return sb.toString();
    }

    // ═════════════ 测试 / 监控辅助方法 ═════════════

    /** 当前追踪 doc 数（测试 / 监控用）. */
    public int trackedCount() {
        return trackedMagicDocs.size();
    }

    /** 是否启用（test 可通过反射 / @Value 注入覆盖）. */
    public boolean isEnabled() {
        return enabled;
    }
}
