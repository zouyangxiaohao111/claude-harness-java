package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentDefinitionRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * 记忆预取器 · 每用户 turn 启动一次相关记忆检索 · 对齐 CC {@code startRelevantMemoryPrefetch}
 * （utils/attachments.ts:2361-2424）+ {@code getRelevantMemoryAttachments}（:2196-2242）+
 * {@code readMemoriesForSurfacing}（:2279-2321）+ {@code collectSurfacedMemories}（:2251-2266）+
 * {@code collectRecentSuccessfulTools}（:2465-2503）+ {@code filterDuplicateMemoryAttachments}（:2520-2541）。
 *
 * <p>对齐语义（2026-08-05 grep -n 自验）：
 * <ol>
 *   <li><b>门控</b>（:2365-2370）：{@code isAutoMemoryEnabled() && getFeatureValue_CACHED_MAY_BE_STALE('tengu_moth_copse', false)}
 *       → Java 注入 {@code autoMemoryEnabled}（{@code BundledSkillEnabledGates::isAutoMemoryEnabled}）+
 *       {@code mothCopseFlag}（GB flag 未接入前默认 false，对齐 MemoryPromptBuilder 同 flag 先例）</li>
 *   <li><b>单字守卫</b>（:2378-2381）：{@code !input || !whitespaceRegex.test(input.trim())} → 不预取
 *       （单字 prompt 缺乏足够上下文做有意义的 term 提取）</li>
 *   <li><b>60KB 会话预算</b>（:2383-2386）：{@code collectSurfacedMemories(messages).totalBytes >= MAX_SESSION_BYTES}
 *       → 不预取；扫 messages 而非 toolUseContext 追踪 → compact 自然重置（:2246-2249 注释）</li>
 *   <li><b>旧跨轮消费追踪删除</b>（DEL-M-34）：去重依赖 readFileState 会话级共享
 *       （filterDuplicateMemoryAttachments :2520-2541 标记，跨 turn/compact 自然重置）</li>
 * </ol>
 */
public class MemoryPrefetcher {

    private static final Logger log = LoggerFactory.getLogger(MemoryPrefetcher.class);

    /** CC attachments.ts:269 MAX_MEMORY_LINES = 200（单文件注入最大行数） */
    public static final int MAX_MEMORY_LINES = 200;

    /** CC attachments.ts:277 MAX_MEMORY_BYTES = 4096（单文件注入最大字节；5 × 4KB = 20KB/turn 有界） */
    public static final int MAX_MEMORY_BYTES = 4096;

    /** CC attachments.ts:288 MAX_SESSION_BYTES = 60KB（会话累计注入预算，超限停止预取） */
    public static final long MAX_SESSION_BYTES = 60 * 1024L;

    /**
     * CC attachments.ts:2379 单字守卫 {@code /\s/} 的 ECMAScript WhiteSpace + LineTerminator 集合
     * 「trim/正则 {@code \s}」定义）：含 U+3000 全角空格等 Unicode 空白、含 U+FEFF、
     * 不含 U+0085 NEL。Java 默认 {@code \s} 仅 ASCII（{@code [ \t\n\x0B\f\r]}），中文
     * 「记得　查一下」场景会漏判——故显式写出该集合以对齐 JS 语义。
     *
     * <p>残余声明：Java {@code String.trim()} 仅移除 ≤ U+0020（CC {@code input.trim()} 移除全部
     * ECMAScript 空白）；当且仅当空白只出现在输入边缘且为 Unicode 空白（如「　查」，U+3000 开头）
     * 时，本实现按「含空白」放行预取，CC 按裁剪后单字拒绝——与 CC 判定相反。
     */
    private static final String ECMASCRIPT_WS_CHAR_CLASS =
        "[\\u0009-\\u000D\\u0020\\u00A0\\uFEFF\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]";

    /**
     * 单条注入记忆 · CC original: {@code readMemoriesForSurfacing} 结果元素
     * （attachments.ts:2282-2289 {@code {path, content, mtimeMs, header, limit?}}）。
     *
     * @param path    记忆文件绝对路径（CC original: path）
     * @param content 截断后的文件内容（含截断 note，CC :2304-2307）
     * @param mtimeMs 文件修改时间戳毫秒（CC original: mtimeMs）
     * @param header  注入头（CC original: header = memoryHeader(filePath, mtimeMs)，:2312）
     * @param limit   截断后实际注入行数（CC original: limit，未截断 = null，:2313）
     */
    public record RelevantMemoryAttachment(String path, String content, long mtimeMs, String header, Integer limit) {}

    /**
     * 已展示记忆扫描结果 · CC original: {@code collectSurfacedMemories} 返回
     * （attachments.ts:2251-2266 {@code {paths, totalBytes}}）。
     *
     * @param paths      历史已展示路径集合（selector 去重用）
     * @param totalBytes 历史注入内容累计字节（会话总预算节流用）
     */
    public record SurfacedMemories(Set<String> paths, long totalBytes) {}

    /**
     * readFileInRange 等价返回 · CC original: {@code utils/readFileInRange.ts} 返回
     * （{@code content / lineCount / totalLines / truncatedByBytes}，:48-54）。
     *
     * @param content         选择到的行内容（join '\n'，CRLF 归一化）
     * @param lineCount       实际注入行数（CC original: lineCount）
     * @param totalLines      文件总行数（含尾随片段，CC original: totalLines）
     * @param truncatedByBytes 是否因字节上限截断（CC original: truncatedByBytes）
     */
    public record MemoryFileData(String content, int lineCount, int totalLines, boolean truncatedByBytes) {}

    /**
     * 预取句柄 · CC original: {@code MemoryPrefetch}（attachments.ts:2346-2353
     * {@code {promise, settledAt, consumed}}）。
     */
    public static final class MemoryPrefetch {
        public final CompletableFuture<List<RelevantMemoryAttachment>> promise;
        public volatile long settledAt = 0L;
        public volatile int consumed = -1;
        /** 预取启动时刻（CC firedAt，dispose 遥测 latency 基准，attachments.ts:2391）。 */
        private final long firedAt;
        /** 链到 turn 级 abort 的子控制器（CC createChildAbortController :2390）。 */
        private final AbortController childAbortController;

        public MemoryPrefetch(CompletableFuture<List<RelevantMemoryAttachment>> promise,
                              AbortController childAbortController) {
            this.promise = promise;
            this.childAbortController = childAbortController != null ? childAbortController : AbortController.NOOP;
            this.firedAt = System.currentTimeMillis();
        }

        /**
         * CC [Symbol.dispose]（attachments.ts:2410-2418）等价：abort 子控制器 + 返回
         * {@code tengu_memdir_prefetch_collected} 遥测属性。LLM 循环在 turn 结束（正常/异常
         * 全部退出路径）调用一次；幂等（abort 幂等）。
         *
         * @return 遥测属性：hidden_by_first_iteration（:2413-2414）/ consumed_on_iteration /
         *         latency_ms（:2416）
         */
        public Map<String, Object> dispose() {
            childAbortController.abort();
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("hidden_by_first_iteration", settledAt != 0L && consumed == 0);
            attrs.put("consumed_on_iteration", consumed);
            attrs.put("latency_ms", (settledAt != 0L ? settledAt : System.currentTimeMillis()) - firedAt);
            return attrs;
        }
    }

    private final FindRelevantMemories findRelevant;
    private final AutoMemPaths autoMemPaths;
    private final MemoryAge memoryAge;
    private final BooleanSupplier autoMemoryEnabled;
    private final BooleanSupplier mothCopseFlag;
    private final java.util.function.Supplier<AgentDefinitionRegistry> agentRegistrySupplier;
    private final AgentMemoryDirectory agentMemoryDirectory;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "memory-prefetch");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param findRelevant          记忆召回器（CC findRelevantMemories 等价）
     * @param autoMemPaths          自动记忆路径解析（CC getAutoMemPath 等价）
     * @param memoryAge             memoryAge 4 函数（CC memoryAge.ts 等价，注入头新鲜度）
     * @param autoMemoryEnabled     CC isAutoMemoryEnabled 门控（生产 BundledSkillEnabledGates::isAutoMemoryEnabled）
     * @param agentRegistrySupplier agent 定义注册中心供应器（G-19/G-69 @-mention 检索隔离 · CC
     *                              toolUseContext.options.agentDefinitions.activeAgents 等价；
     *                              惰性求值（bean 装配期不得强制解析 —— Spring @Lazy 代理 + 循环
     *                              依赖防护）；可 null = 无 agent 定义 → 兜底 autoMemPath）
     * @param agentMemoryDirectory  agent memory 目录解析（CC getAgentMemoryDir 等价；可 null =
     *                              @-mention 隔离不可用 → 兜底 autoMemPath）
     */
    public MemoryPrefetcher(FindRelevantMemories findRelevant,
                            AutoMemPaths autoMemPaths,
                            MemoryAge memoryAge,
                            BooleanSupplier autoMemoryEnabled,
                            BooleanSupplier mothCopseFlag,
                            java.util.function.Supplier<AgentDefinitionRegistry> agentRegistrySupplier,
                            AgentMemoryDirectory agentMemoryDirectory) {
        this.findRelevant = Objects.requireNonNull(findRelevant);
        this.autoMemPaths = Objects.requireNonNull(autoMemPaths);
        this.memoryAge = Objects.requireNonNull(memoryAge);
        this.autoMemoryEnabled = Objects.requireNonNull(autoMemoryEnabled);
        this.mothCopseFlag = Objects.requireNonNull(mothCopseFlag);
        this.agentRegistrySupplier = agentRegistrySupplier;
        this.agentMemoryDirectory = agentMemoryDirectory;
    }

    /**
     * 启动相关记忆预取 · 对齐 CC startRelevantMemoryPrefetch（attachments.ts:2361-2424）。
     *
     * <p>门控(2) + 单字守卫 + 60KB 预算检查；返回 {@link MemoryPrefetch} 句柄（settledAt/consumed），
     * 不阻塞调用方。prefetch 每用户 turn 启动一次（消费点零等待，下轮迭代重试）。
     *
     * @param messages            会话消息（CC original: messages，:2362）
     * @param readFileState       会话级 readFileState 缓存（CC original: toolUseContext.readFileState，:2393）
     * @param turnAbortController turn 级 abort 控制器（CC original: toolUseContext.abortController，
     *                            :2390 createChildAbortController 父信号；可 null = 无取消链）
     * @return MemoryPrefetch 句柄；门控关闭 / 无 user 消息 / 单字 / 超预算 → null（CC undefined）
     */
    public MemoryPrefetch startPrefetch(List<ChatMessageDto> messages, FileStateCache readFileState,
                                        AbortController turnAbortController) {
        // 门控 1：isAutoMemoryEnabled · 门控 2：tengu_moth_copse（GB flag）
        if (!autoMemoryEnabled.getAsBoolean() || !mothCopseFlag.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[MemoryPrefetcher] 门控关闭（autoMemoryEnabled={} mothCopseFlag={}）跳过预取 · CC attachments.ts:2365-2370",
                    autoMemoryEnabled.getAsBoolean(), mothCopseFlag.getAsBoolean());
            }
            return null;
        }
        // CC :2372-2375 最后一个非 isMeta 的真实 user 消息
        ChatMessageDto lastUserMessage = null;
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessageDto m = messages.get(i);
                if (m != null && m.role() == Role.user && !m.isMeta()) {
                    lastUserMessage = m;
                    break;
                }
            }
        }
        if (lastUserMessage == null) {
            if (log.isDebugEnabled()) {
                log.debug("[MemoryPrefetcher] 无真实 user 消息，跳过预取 · CC attachments.ts:2372-2375");
            }
            return null;
        }

        String input = lastUserMessage.content();
        // 单字守卫：!input || !/\s/.test(input.trim()) → 不预取（单字 prompt 缺乏上下文）。
        // CC /\s/ 为 ECMAScript 空白（含 U+3000 全角空格，Java \s 仅 ASCII）→ 显式字符类对齐（常量注释见上）。
        if (input == null || input.isBlank() || !input.trim().matches(".*" + ECMASCRIPT_WS_CHAR_CLASS + ".*")) {
            if (log.isDebugEnabled()) {
                log.debug("[MemoryPrefetcher] 单字/空 user 消息，跳过预取 · CC attachments.ts:2377-2381");
            }
            return null;
        }

        SurfacedMemories surfaced = collectSurfacedMemories(messages);
        if (surfaced.totalBytes() >= MAX_SESSION_BYTES) {
            if (log.isDebugEnabled()) {
                log.debug("[MemoryPrefetcher] 会话注入预算已满 ({}B >= {}B)，跳过预取 · CC attachments.ts:2383-2386",
                    surfaced.totalBytes(), MAX_SESSION_BYTES);
            }
            return null;
        }

        List<String> recentTools = collectRecentSuccessfulTools(messages, lastUserMessage);
        // MEM-03：子 abort controller 链到 turn 级 abort（用户 Escape/取消立即终止 in-flight
        // side-query，attachments.ts:2388-2390）· CC createChildAbortController(toolUseContext.abortController)
        AbortController childController = turnAbortController != null
            ? turnAbortController.createChild()
            : new AbortController();
        // G-19/G-69（F3 补登）：@-mention 检索隔离 —— 输入含 agent @-mention → 仅搜索匹配
        // agent 的 memory 目录；否则 [getAutoMemPath()]（attachments.ts:2204-2213）。
        List<Path> memoryDirs = resolveMemoryDirs(input);

        // promise 恒正常完成（catch → []）· CC :2392-2404
        CompletableFuture<List<RelevantMemoryAttachment>> promise = CompletableFuture.supplyAsync(
            () -> getRelevantMemoryAttachments(input, memoryDirs, readFileState, recentTools,
                surfaced.paths(), childController),
            executor
        ).exceptionally(e -> {
            if (!(e instanceof java.util.concurrent.CancellationException)) {
                log.warn("[MemoryPrefetcher] 相关记忆预取失败，返回空（CC catch → []）: {}", e.getMessage());
            }
            return List.of();
        });

        MemoryPrefetch handle = new MemoryPrefetch(promise, childController);
        promise.whenComplete((r, ex) -> {
            handle.settledAt = System.currentTimeMillis();   // CC :2420-2422 promise.finally
        });
        if (log.isDebugEnabled()) {
            log.debug("[MemoryPrefetcher] 相关记忆预取已启动: 目录={} 已展示={} 预算={}B 最近工具={}",
                memoryDirs, surfaced.paths().size(), surfaced.totalBytes(), recentTools.size());
        }
        return handle;
    }

    /**
     * 检索目录选择 · 对齐 CC getRelevantMemoryAttachments（attachments.ts:2204-2213）：
     * 输入含 agent @-mention → 每个 mention 查 activeAgents 找到带 memory scope 的 agent →
     * getAgentMemoryDir 单目录；命中列表为空 → {@code [getAutoMemPath()]}。
     *
     * @param input 用户 query（CC original: input）
     * @return 检索目录列表（CC original: dirs）
     */
    List<Path> resolveMemoryDirs(String input) {
        List<Path> dirs = new ArrayList<>();
        AgentDefinitionRegistry registry = agentRegistrySupplier != null ? agentRegistrySupplier.get() : null;
        if (input != null && registry != null && agentMemoryDirectory != null) {
            for (String mention : extractAgentMentions(input)) {
                // CC :2207-2211 mention.replace('agent-', '') → agents.find(def.agentType === type)
                String agentType = mention.replace("agent-", "");
                AgentDefinition def = registry.findAgent(agentType);
                if (def == null || def.memory().isEmpty()) {
                    continue;   // CC :2209-2211 agentDef?.memory ? [...] : []
                }
                AgentMemoryDirectory.AgentMemoryScope scope =
                    AgentMemoryDirectory.fromName(def.memory().get());
                if (scope == null) {
                    continue;   // 非法 scope（加载层已校验，防御性跳过）
                }
                dirs.add(agentMemoryDirectory.getAgentMemoryDir(agentType, scope));
            }
        }
        // CC :2213 dirs = memoryDirs.length > 0 ? memoryDirs : [getAutoMemPath()]
        return dirs.isEmpty() ? List.of(Path.of(autoMemPaths.getAutoMemPath())) : dirs;
    }

    /**
     * 提取 agent @-mention · 对齐 CC extractAgentMentions（attachments.ts:2802-2828）：
     * <ol>
     *   <li>引号形态 {@code @"<type> (agent)"}（autocomplete 选中，:2812-2818）</li>
     *   <li>非引号形态 {@code @agent-<type>}（手动输入，:2821-2825；支持冒号/点/@ 的插件作用域）</li>
     * </ol>
     * 返回去重后的 mention 列表（CC uniq）。
     */
    static List<String> extractAgentMentions(String content) {
        List<String> results = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return results;
        }
        java.util.regex.Pattern quoted = java.util.regex.Pattern.compile("(^|\\s)@\"([\\w:.@-]+) \\(agent\\)\"");
        java.util.regex.Pattern unquoted = java.util.regex.Pattern.compile("(^|\\s)@(agent-[\\w:.@-]+)");
        java.util.regex.Matcher qm = quoted.matcher(content);
        while (qm.find()) {
            String type = qm.group(2);
            if (type != null && !results.contains(type)) {
                results.add(type);
            }
        }
        java.util.regex.Matcher um = unquoted.matcher(content);
        while (um.find()) {
            String mention = um.group(2);
            if (mention != null && !results.contains(mention)) {
                results.add(mention);
            }
        }
        return results;
    }

    /**
     * 组装相关记忆 attachment 列表 · 对齐 CC getRelevantMemoryAttachments（attachments.ts:2196-2242）。
     *
     * <p>已展示过滤在 selector 内（Sonnet 5-slot 预算花在新鲜候选上）；readFileState 兜底捕获
     * 经 FileReadTool 读过的文件；此处再过滤是 belt-and-suspenders（多目录结果可能重新引入
     * 其它目录被过滤的路径，:2226-2230 注释）。最终 slice(0,5) + readMemoriesForSurfacing。
     *
     * @param input            用户 query（CC original: input）
     * @param memoryDirs       记忆目录列表（agent @-mention 隔离结果；Java 兜底单目录 autoMemPath）
     * @param readFileState    会话级 readFileState 缓存（CC original: readFileState）
     * @param recentTools      最近成功工具名列表（CC original: recentTools）
     * @param alreadySurfaced  历史已展示路径集合（CC original: alreadySurfaced）
     * @param signal           取消信号（CC original: signal，:2201 —— findRelevantMemories 全链透传）
     * @return 注入用记忆列表（可空）
     */
    public List<RelevantMemoryAttachment> getRelevantMemoryAttachments(
        String input,
        List<Path> memoryDirs,
        FileStateCache readFileState,
        List<String> recentTools,
        Set<String> alreadySurfaced,
        AbortController signal
    ) {
        // CC :2215-2225 Promise.all(dirs.map(dir => findRelevantMemories(...).catch(() => [])))——
        // 多目录并行 + 每目录独立 catch（A24）；复用本类 executor（CC Promise.all 语义等价）。
        List<CompletableFuture<List<FindRelevantMemories.RelevantMemory>>> futures =
            new ArrayList<>();
        for (Path dir : memoryDirs) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return findRelevant.findRelevantMemories(input, dir, recentTools, alreadySurfaced, signal);
                } catch (Exception e) {
                    log.warn("[MemoryPrefetcher] findRelevantMemories 失败，跳过该目录（CC catch → []）: 目录={} err={}",
                        dir, e.getMessage());
                    return List.of();
                }
            }, executor));
        }
        List<FindRelevantMemories.RelevantMemory> allResults = new ArrayList<>();
        for (CompletableFuture<List<FindRelevantMemories.RelevantMemory>> f : futures) {
            try {
                allResults.addAll(f.join());   // 各 future 内部已 catch → join 恒正常
            } catch (Exception e) {
                log.warn("[MemoryPrefetcher] findRelevantMemories join 失败，跳过该目录（防御）: {}", e.getMessage());
            }
        }
        // CC :2231-2234 filter(!readFileState.has(path) && !alreadySurfaced.has(path)).slice(0,5)
        List<FindRelevantMemories.RelevantMemory> selected = allResults.stream()
            .filter(m -> readFileState == null || !readFileState.has(m.path()))
            .filter(m -> !alreadySurfaced.contains(m.path()))
            .limit(5)
            .toList();

        List<RelevantMemoryAttachment> memories = readMemoriesForSurfacing(selected);
        if (log.isDebugEnabled()) {
            log.debug("[MemoryPrefetcher] getRelevantMemoryAttachments: 候选 {} 条 → 去重 slice(0,5) 后 {} 条 → 注入 {} 条",
                allResults.size(), selected.size(), memories.size());
        }
        return memories;
    }

    /**
     * 读取选择到的记忆文件为注入载荷 · 对齐 CC readMemoriesForSurfacing（attachments.ts:2279-2321）。
     *
     * <p>同时执行 MAX_MEMORY_LINES + MAX_MEMORY_BYTES 双上限（truncateOnByteLimit）；截断保留
     * frontmatter + 开头上下文并附加 note 而非丢弃整文件（findRelevantMemories 已选它最相关，:2273-2275）。
     *
     * @param selected 已排序的相关记忆（path + mtimeMs）
     * @return 注入载荷列表（读失败条目丢弃，CC :2315-2317 catch → null filter）
     */
    public List<RelevantMemoryAttachment> readMemoriesForSurfacing(List<FindRelevantMemories.RelevantMemory> selected) {
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<RelevantMemoryAttachment> results = new ArrayList<>();
        for (FindRelevantMemories.RelevantMemory rm : selected) {
            try {
                Path filePath = Path.of(rm.path());
                MemoryFileData data = readFileInRange(filePath, MAX_MEMORY_LINES, MAX_MEMORY_BYTES);
                boolean truncated = data.totalLines() > MAX_MEMORY_LINES || data.truncatedByBytes();
                String content = truncated
                    ? data.content()
                        + "\n\n> This memory file was truncated ("
                        + (data.truncatedByBytes() ? MAX_MEMORY_BYTES + " byte limit" : "first " + MAX_MEMORY_LINES + " lines")
                        + "). Use the Read tool to view the complete file at: " + rm.path()
                    : data.content();
                results.add(new RelevantMemoryAttachment(
                    rm.path(),
                    content,
                    rm.mtimeMs(),
                    memoryHeader(rm.path(), rm.mtimeMs()),
                    truncated ? data.lineCount() : null));
            } catch (IOException | RuntimeException e) {
                if (log.isDebugEnabled()) {
                    log.debug("[MemoryPrefetcher] 读取记忆文件失败，丢弃（CC catch → null）: {} - {}",
                        rm.path(), e.getMessage());
                }
            }
        }
        return results;
    }

    /**
     * readFileInRange 等价实现 · 对齐 CC utils/readFileInRange.ts（offset=0 + maxLines + maxBytes
     * truncateOnByteLimit，:75-192）。读前 maxLines 行、字节超限截断，CRLF 归一化（:165-179）。
     *
     * @param filePath 文件路径
     * @param maxLines 最大行数（CC original: maxLines）
     * @param maxBytes 最大字节（truncateOnByteLimit；超出 → truncatedByBytes，不抛异常）
     * @return 读取结果
     * @throws IOException 文件读取失败
     */
    static MemoryFileData readFileInRange(Path filePath, int maxLines, int maxBytes) throws IOException {
        String text = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
        // MEM-04/G-23：剥 BOM（CC readFileInRange.ts:138 {@code raw.charCodeAt(0) === 0xfeff
        // ? raw.slice(1) : raw}）—— 否则含 BOM 记忆文件首行注入内容带 \uFEFF 污染
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        String[] allLines = text.split("\n", -1);
        int totalLines = allLines.length;
        List<String> selectedLines = new ArrayList<>();
        int selectedBytes = 0;
        boolean truncatedByBytes = false;
        for (int i = 0; i < Math.min(maxLines, allLines.length); i++) {
            String line = allLines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);   // CC :165-167 CRLF 归一化
            }
            int sep = selectedLines.isEmpty() ? 0 : 1;
            long nextBytes = (long) selectedBytes + sep + line.getBytes(StandardCharsets.UTF_8).length;
            if (nextBytes > maxBytes) {
                truncatedByBytes = true;   // CC :152-155 tryPush 拒绝
                break;
            }
            selectedBytes = (int) nextBytes;
            selectedLines.add(line);
        }
        return new MemoryFileData(String.join("\n", selectedLines), selectedLines.size(), totalLines, truncatedByBytes);
    }

    /**
     * 注入头字符串 · 对齐 CC memoryHeader（attachments.ts:2327-2332）。
     *
     * <p>陈旧记忆（>1 day）用 memoryFreshnessText 提示；新鲜记忆用 {@code Memory (saved {age}): path:}。
     *
     * @param path    记忆文件绝对路径（CC original: path）
     * @param mtimeMs 文件修改时间戳毫秒（CC original: mtimeMs）
     * @return 注入头文本
     */
    public String memoryHeader(String path, long mtimeMs) {
        String staleness = memoryAge.memoryFreshnessText(mtimeMs);
        return staleness.isEmpty()
            ? "Memory (saved " + memoryAge.memoryAge(mtimeMs) + "): " + path + ":"
            : staleness + "\n\nMemory: " + path + ":";
    }

    /**
     * 扫描 messages 中历史 relevant_memories 注入 · 对齐 CC collectSurfacedMemories（attachments.ts:2251-2266）。
     *
     * <p>返回已展示路径集合（selector 去重）+ 累计字节（会话总预算节流）。扫 messages 而非
     * toolUseContext 追踪 → compact 自然重置（旧 attachment 已从压缩 transcript 消失，重新展示有效）。
     *
     * <p>Java 表示：注入的 relevant_memories 消息以 {@code subtype="relevant_memories"} 的 isMeta
     * user 消息持久化在消息流（LlmAgentLoop 消费点 {@code state.appendMessage}），本方法据此识别。
     *
     * @param messages 会话消息列表（CC original: messages）
     * @return 已展示路径 + 累计字节
     * <p>[E-5 登记 · IMP-MV2-40] △-3：载体差异 —— CC collectSurfacedMemories 直接消费内存 message
     *   content（attachments.ts:2258-2262）；Java 解析持久化 relevant_memories wrapper 字符串
     *   （<system-reminder> 包裹）。非预期形态保守计长（不回退 0，防预算低估双发）；UTF-16 口径
     *   与 CC mem.content.length 一致（MEM-05/G-25）—— 登记不修。
     */
    public static SurfacedMemories collectSurfacedMemories(List<ChatMessageDto> messages) {
        Set<String> paths = new LinkedHashSet<>();
        long totalBytes = 0;
        if (messages != null) {
            for (ChatMessageDto m : messages) {
                if (m != null && "relevant_memories".equals(m.subtype()) && m.content() != null) {
                    // 单条消息 = 单条记忆（header + content 拼接，CC messages.ts:3715-3720 header\n\ncontent）
                    String content = m.content();
                    // FIX-FR: 字节口径对齐 CC attachments.ts:2262 —— 仅计 mem.content（纯文件内容），
                    // 减 header + <system-reminder> wrapper（原实现把 header+包装也计入，预算偏高）
                    totalBytes += contentBytesOfMeta(content);
                    String path = extractPathFromMetaContent(content);
                    if (path != null) {
                        paths.add(path);
                    }
                }
            }
        }
        return new SurfacedMemories(paths, totalBytes);
    }

    /**
     * 从 relevant_memories meta 消息 content 提取<b>纯文件内容长度</b> · 对齐 CC attachments.ts:2262
     * {@code totalBytes += mem.content.length}——JS String.length = <b>UTF-16 单元</b>（非 UTF-8 字节；
     * Java String.length() 同为 UTF-16 单元 → 直接等价，MEM-05/G-25）。仅计 attachment 的 content，
     * 不含 header + system-reminder。
     *
     * <p>持久化格式（LlmAgentLoop 注入点）：{@code <system-reminder>\n{header}\n\n{content}\n</system-reminder>}。
     * 剥离定长 wrapper 后，content 为 header 之后 {@code \n\n} 分隔符后的余部：header 新鲜形态
     * {@code Memory (saved X): path:} 无内部换行 → 首个 {@code \n\n}；陈旧形态
     * {@code staleness\n\nMemory: path:} 内部含 1 个 {@code \n\n} → 次个。非预期形态 → 整段计长
     * （保守不回退 0，避免预算低估导致双发）。
     *
     * @param persistedContent 持久化的 relevant_memories 消息 content（wrapper + header + content）
     * @return 纯文件内容 UTF-16 单元数（CC mem.content.length）
     */
    static long contentBytesOfMeta(String persistedContent) {
        if (persistedContent == null || persistedContent.isEmpty()) {
            return 0;
        }
        String s = persistedContent;
        String prefix = "<system-reminder>\n";
        String suffix = "\n</system-reminder>";
        if (s.startsWith(prefix)) {
            s = s.substring(prefix.length());
        }
        if (s.endsWith(suffix)) {
            s = s.substring(0, s.length() - suffix.length());
        }
        int sep;
        if (s.startsWith("Memory (saved")) {
            sep = s.indexOf("\n\n");          // 新鲜单行 header → 首个 \n\n 为分隔符
        } else {
            int first = s.indexOf("\n\n");    // 陈旧 header 内部含 1 个 \n\n → 分隔符为次个
            sep = first >= 0 ? s.indexOf("\n\n", first + 2) : -1;
        }
        String contentPart = sep >= 0 ? s.substring(sep + 2) : s;
        // MEM-05/G-25：UTF-16 单元（CC mem.content.length）—— 旧 UTF-8 字节口径使 CJK 记忆预算提前触顶
        return contentPart.length();
    }

    /**
     * 从 relevant_memories meta 消息 content 提取记忆文件路径 · 支持两种注入头形态：
     * {@code Memory (saved X): {path}:}（新鲜）与 {@code Memory: {path}:}（陈旧，memoryHeader
     * attachments.ts:2327-2332 契约）。
     *
     * <p>两种形态的路径行均以 {@code Memory} 开头（staleness 提示文本以 "This memory"/"Memories"
     * 开头，非 "Memory" 行首），路径为该行最后一个 {@code ": "} 之后的段并以 ':' 结尾。
     */
    private static String extractPathFromMetaContent(String content) {
        String[] lines = content.split("\n", -1);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (!line.startsWith("Memory")) {
                continue;
            }
            int lastColonSpace = line.lastIndexOf(": ");
            if (lastColonSpace < 0) {
                return null;
            }
            String path = line.substring(lastColonSpace + 2).trim();
            if (path.endsWith(":")) {
                path = path.substring(0, path.length() - 1).trim();
            }
            return path.isEmpty() ? null : path;
        }
        return null;
    }

    /**
     * 过滤与 readFileState 重复的记忆附件 · 对齐 CC filterDuplicateMemoryAttachments（attachments.ts:2520-2541）。
     *
     * <p>mark-after-filter 顺序是 load-bearing（:2513-2519 注释）：readMemoriesForSurfacing 曾在预取期
     * 写 readFileState，导致过滤看到全部预选路径已入上下文而全丢（自引用过滤）。延迟写入本方法过滤后
     * 打破循环，同时对任何迭代的工具调用去重。
     *
     * @param attachments   预取结果（CC original: attachments）
     * @param readFileState 会话级 readFileState 缓存（CC original: readFileState）
     * @return 过滤后的注入载荷（全部重复 → 空）
     */
    public List<RelevantMemoryAttachment> filterDuplicateMemoryAttachments(
        List<RelevantMemoryAttachment> attachments,
        FileStateCache readFileState
    ) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<RelevantMemoryAttachment> filtered = new ArrayList<>();
        for (RelevantMemoryAttachment m : attachments) {
            if (readFileState != null && readFileState.has(m.path())) {
                if (log.isDebugEnabled()) {
                    log.debug("[MemoryPrefetcher] filterDuplicate: readFileState 已含 {}，剔除 · CC attachments.ts:2527-2529",
                        m.path());
                }
                continue;
            }
            filtered.add(m);
        }
        // mark-after-filter：幸存者写 readFileState，后续 turn 不重复展示 · CC :2530-2537
        if (readFileState != null) {
            for (RelevantMemoryAttachment m : filtered) {
                readFileState.set(m.path(), new ToolUseContext.ReadState(m.mtimeMs(), null, m.limit(), false, m.content()));
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[MemoryPrefetcher] filterDuplicate: {} → {} 条 · CC attachments.ts:2538",
                attachments.size(), filtered.size());
        }
        return filtered;
    }

    /**
     * 收集自上个真实 turn 边界以来成功（从未报错）的工具 · 对齐 CC collectRecentSuccessfulTools
     * （attachments.ts:2465-2503）。
     *
     * <p>selector 用此抑制正在正常使用的工具的文档（模型已在成功调用时展示参考文档是噪音）；
     * 任何错误 → 工具排除（模型挣扎，文档保留可用）；无结果 → 也排除（结果未知）。
     *
     * @param messages         会话消息列表（CC original: messages）
     * @param lastUserMessage  最后一个真实 user 消息（CC original: lastUserMessage）
     * @return 最近成功工具名列表
     */
    public static List<String> collectRecentSuccessfulTools(
        List<ChatMessageDto> messages,
        ChatMessageDto lastUserMessage
    ) {
        Map<String, String> useIdToName = new HashMap<>();
        Map<String, Boolean> resultByUseId = new HashMap<>();
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessageDto m = messages.get(i);
                if (m == null) {
                    continue;
                }
                // CC :2474 isHumanTurn(m) && m !== lastUserMessage → break
                if (m.role() == Role.user && !m.isMeta() && m != lastUserMessage) {
                    break;
                }
                if (m.role() == Role.assistant && m.toolCalls() != null) {
                    for (ToolCallDto tc : m.toolCalls()) {
                        if (tc != null && tc.id() != null && tc.name() != null) {
                            useIdToName.put(tc.id(), tc.name());   // CC :2477-2478
                        }
                    }
                } else if (m.role() == Role.tool && m.toolCallId() != null) {
                    resultByUseId.put(m.toolCallId(), m.isError());   // CC :2485-2487 tool_result.is_error
                }
            }
        }
        Set<String> failed = new LinkedHashSet<>();
        Set<String> succeeded = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : useIdToName.entrySet()) {
            Boolean errored = resultByUseId.get(e.getKey());
            if (errored == null) {
                continue;   // CC :2495 errored === undefined → continue（无结果也排除）
            }
            if (errored) {
                failed.add(e.getValue());   // CC :2497-2498
            } else {
                succeeded.add(e.getValue());   // CC :2499-2500
            }
        }
        return succeeded.stream().filter(t -> !failed.contains(t)).toList();   // CC :2502
    }

    /** 供外部 shutdown 清理（线程池释放）。 */
    public void shutdown() {
        executor.shutdown();
        log.info("[MemoryPrefetcher] 已 shutdown");
    }
}
