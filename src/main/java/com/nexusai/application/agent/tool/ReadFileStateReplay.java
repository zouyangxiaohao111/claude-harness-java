package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.infra.util.FileEncodingReader;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>[批 rfs-replay-3b] 跨进程 readFileState 恢复（replay）</b> —— 把持久化的会话消息历史
 * 反推回 {@code readFileState}，使「后端 JVM 重启 / 换进程 resume」后 Read-before-Write 门禁
 * 不再把上一进程 Read 过的文件判成「从未读过」。
 *
 * <h2>对齐目标（必须钉版本）</h2>
 * CC <b>2.1.278</b>（{@code claude.exe} 内嵌 {@code // Version: 2.1.278}，off 195936371）：
 * <pre>
 * // 入口（mergeReadFileStateFrom 定义 exe off 222009557；由 restoreReadFileState 定义 off 222145682 调用）
 * mergeReadFileStateFrom(h,v){ this.readFileState = s6r(this.readFileState, aHt(h,v,LC)) }
 * //          ↑h=messages  ↑v=cwd                                                  ↑LC=5000
 * // restoreReadFileState 内调用点 off 222145759，resume 路径
 *
 * // 第一遍 + 第二遍（exe off 209688341）
 * function aHt(e,r,n=ka){ ... }
 *
 * // 合并（exe off 199642286）
 * function s6r(e,n){let r=mle(e);for(let[i,o]of n.entries()){let s=r.get(i);if(!s||o.timestamp>s.timestamp)r.set(i,o)}return r}
 * </pre>
 * 即：<b>clone 现有表 + 逐条 newer-timestamp-wins</b>（⛔ 不是「只补缺失路径」）。
 *
 * <h2>三支语义（照 {@code aHt}）</h2>
 * <ul>
 *   <li><b>Read</b>（第一遍 assistant {@code tool_use}）：仅收 {@code offset===undefined &&
 *       limit===undefined} 的整文件读（对齐 CC 2.1.88 {@code extractReadFilesFromMessages}
 *       「Ranged reads are not added to the cache」，本仓源码参照
 *       {@code Open-ClaudeCode/src/utils/queryHelpers.ts:379-388}）；
 *       第二遍用对应 {@code tool_result} 的<b>反渲染</b>结果当 content，
 *       timestamp = <b>消息时间戳</b>，{@code contentNotInModelContext=false}
 *       （模型确实看过这份内容）。
 *       <br>⚠️ <b>与 2.1.278 的偏离（如实登记）</b>：2.1.278 的 {@code aHt} 第一遍
 *       <b>不再要求 offset/limit 为 undefined</b>（只校验其类型合法性，Read 支
 *       {@code g.set(D.id,{filePath:…,offset:Y,limit:V})} off 209688935），即它<b>也 replay 窗口读</b>。
 *       本批按派单书钉死「仅整文件读」（= 2.1.88 口径，且更保守：窗口读 entry 的
 *       {@code offset>1} 永远过不了本仓门禁 2 的内容兜底）。</li>
 *   <li><b>Write</b>：第二遍用<b>入参 content</b>（{@code aHt}：{@code {content:Lb(X.content),...}}），
 *       timestamp = 消息时间戳，{@code contentNotInModelContext=false}。</li>
 *   <li><b>Edit</b>：Edit 的入参只有 old/new_string、结果只有片段 ⇒ <b>现读磁盘</b>，
 *       timestamp = <b>盘上 mtime</b>（{@code aHt}：{@code timestamp:ZZe(de)} = getFileModificationTime，
 *       ⚠️ <b>不是</b>消息时间戳），{@code contentNotInModelContext=TRUE}
 *       （内容来自磁盘、模型没看过）。</li>
 * </ul>
 *
 * <h2>反渲染（Read 支）</h2>
 * 落库的 {@code messages.content}（role=tool）= 我们 mapper 的渲染输出
 * （{@code ReadFileTool#mapToToolResultBlockParam}）：
 * {@code freshnessNote + addLineNumbers(raw, startLine) + (injectReminder ? CYBER_RISK : "")}，
 * 而 {@code readFileState} 存的是 <b>raw</b> ⇒ 必须反渲染。
 *
 * <p>⚠️ <b>本实现【不照抄】CC 的<a>通用剥块 + trim</a>，理由见 {@link #reverseRender(String)} 的 javadoc</b>：
 * CC 2.1.278 {@code aHt} 是
 * {@code content.replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g,'').split('\n').map(E9t).join('\n').trim()}
 * （剥块 off 209690129、{@code map(E9t)} off 209690201、{@code .trim()} off 209690219）。
 * 本仓实测（{@code ReadFileStateReplayTest} 前置 A + 真实 DB 语料 129/129 逐字节）证明：
 * <b>只有「减掉渲染层实际附加的那几段常量」才能逐字节还原 raw</b>；通用剥块会误吃正文、
 * {@code trim()} 会吃掉尾随换行。逐字步骤见 {@link #reverseRender(String)}。
 *
 * <h2>软降级（同本仓先例 {@code ResumeService.restoreSessionCwd}）</h2>
 * 历史缺失（null/空）/ 目标表 null / 无 sessionId / 单条解析失败 / 单文件读盘失败
 * ⇒ debug 日志 + 跳过该条（或整段跳过），<b>绝不抛异常、绝不阻断 run</b>。
 *
 * <h2>⛔ local-only 红线（同 {@code SessionReadFileStateRegistry} / {@code FileStateCache}）</h2>
 * 纯内存进程内状态；<b>绝不</b>序列化 / 绝不经 STOMP / WebSocket / EventPublisher / outbound DTO 外发。
 *
 * <h2>⛔ 不新增 DB I/O</h2>
 * 本类<b>只吃调用方已经读到的</b> {@code List<ChatMessageDto>}（生产 = {@code LlmAgentLoop.doRun}
 * 在 {@code resumeRawTranscript} 那一次 {@code listRawForTranscript} 的产物），
 * 内部零 mapper / 零 SQL。
 */
public final class ReadFileStateReplay {

    private static final Logger log = LoggerFactory.getLogger(ReadFileStateReplay.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC {@code FILE_READ_TOOL_NAME}（本仓 DB {@code tool_calls.tool_name} 同值）。 */
    private static final String TOOL_READ = "Read";
    /** CC {@code FILE_WRITE_TOOL_NAME}。 */
    private static final String TOOL_WRITE = "Write";
    /** CC {@code FILE_EDIT_TOOL_NAME}。 */
    private static final String TOOL_EDIT = "Edit";

    /**
     * 行号前缀剥离正则 · <b>逐字照抄</b> CC 2.1.278 {@code E9t}（exe off 197071357）：
     * {@code function E9t(e){return e.match(/^\s*\d+[→\t:](.*)$/)?.[1]??e}}
     *
     * <p>三种分隔符：{@code →}（U+2192，padded-arrow 模式）、TAB（compact 模式，本仓当前默认
     * {@code compactLinePrefixEnabled=true}）、{@code :}（CC 另一渲染形态）。
     * 未命中 → 原样返回（{@code ?? e}）。
     */
    private static final Pattern LINE_NUMBER_PREFIX =
        Pattern.compile("^\\s*\\d+[→\t:](.*)$", Pattern.DOTALL);

    private ReadFileStateReplay() {
    }

    /** replay 统计（供日志 / 测试断言；⛔ 纯观测，不参与任何判定）。 */
    public record Stats(
        int scannedMessages,
        int readEntries,
        int writeEntries,
        int editEntries,
        int readSkippedRanged,
        int readSkippedNoResult,
        int readSkippedStubOrError,
        int editSkippedDiskUnreadable,
        int mergedNew,
        int mergedOverwrite,
        int skippedOlder
    ) {
        /** replay 产出（= 第一遍+第二遍能构造出来的 entry 数）。 */
        public int extracted() {
            return readEntries + writeEntries + editEntries;
        }

        /** 真正落到目标表上的条数（新插 + 覆盖）。 */
        public int applied() {
            return mergedNew + mergedOverwrite;
        }
    }

    /** 无操作统计（历史缺失 / 无 sessionId / 软降级路径）。 */
    public static final Stats EMPTY_STATS =
        new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * 从 DB 历史 replay 并 merge 进目标 readFileState（newer-timestamp-wins）。
     *
     * @param sessionId   会话 id（null/空白 ⇒ 软降级空跑；仅供日志与 null 守卫，
     *                    ⛔ 本方法<b>不</b>自己去查表 —— 目标表由调用方传入，保证
     *                    {@code SessionReadFileStateRegistry.forSession} 仍是唯一查找点）
     * @param transcript  调用方<b>已读到的</b> DB 原始转录（{@code listRawForTranscript} 产物）；
     *                    null/空 ⇒ 软降级空跑（debug 日志）
     * @param cwdFallback 消息自身 cwd 缺失时的解析基座（等价 CC {@code aHt(e,r)} 的 {@code r}）；
     *                    null/空白 ⇒ 该条跳过（不猜基准）
     * @param target      目标 readFileState（null ⇒ 软降级空跑）
     * @return 统计（永不 null）
     */
    public static Stats merge(String sessionId, List<ChatMessageDto> transcript,
                              String cwdFallback, FileStateCache target) {
        if (target == null) {
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·replay] 跳过：目标 readFileState 为 null（无会话标识 ⇒ 无表可灌）"
                    + " sessionId={}", sessionId);
            }
            return EMPTY_STATS;
        }
        if (transcript == null || transcript.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·replay] 跳过：DB 历史缺失或为空（软降级，不阻断 run）"
                    + " sessionId={} 目标表现有 {} 条", sessionId, target.size());
            }
            return EMPTY_STATS;
        }
        // ── 第一遍：assistant 的 tool_use（Read/Write/Edit 三条支线；对齐 aHt 第一遍）──
        Map<String, String> readPaths = new HashMap<>();            // toolUseId -> 绝对路径
        Map<String, WriteHit> writeHits = new HashMap<>();          // toolUseId -> {path, content}
        Map<String, String> editPaths = new HashMap<>();            // toolUseId -> 绝对路径
        Map<String, Boolean> toolCallIsError = new HashMap<>();     // toolUseId -> is_error（真源 = tool_calls 表）
        int readSkippedRanged = 0;
        for (ChatMessageDto m : transcript) {
            if (m == null || m.role() != Role.assistant || m.toolCalls() == null) {
                continue;
            }
            String base = cwdBase(m.cwd(), cwdFallback);
            for (ToolCallDto tc : m.toolCalls()) {
                if (tc == null || tc.name() == null || tc.id() == null) {
                    continue;
                }
                // [is_error 真源] messages 表不持久化消息级 is_error（MessageService.toDto 恒 false），
                //   真值是 tool_calls.is_error ⇒ 必须从 ToolCallDto 取（第二遍的 is_error 守卫要用）。
                toolCallIsError.put(tc.id(), Boolean.TRUE.equals(tc.isError()));
                JsonNode input = parseArguments(tc.arguments());
                if (input == null) {
                    continue;
                }
                String filePath = textOrNull(input.get("file_path"));
                if (filePath == null) {
                    continue;
                }
                switch (tc.name()) {
                    case TOOL_READ -> {
                        // 对齐 CC 2.1.88 queryHelpers.ts:379-382「Ranged reads are not added to the cache」
                        // （派单书钉死：仅 offset===undefined && limit===undefined）。
                        boolean hasOffset = input.hasNonNull("offset");
                        boolean hasLimit = input.hasNonNull("limit");
                        if (hasOffset || hasLimit) {
                            readSkippedRanged++;
                            continue;
                        }
                        String abs = toCacheKey(filePath, base);
                        if (abs != null) {
                            readPaths.put(tc.id(), abs);
                        }
                    }
                    case TOOL_WRITE -> {
                        String content = textOrNull(input.get("content"));
                        if (content != null) {
                            String abs = toCacheKey(filePath, base);
                            if (abs != null) {
                                writeHits.put(tc.id(), new WriteHit(abs, content));
                            }
                        }
                    }
                    case TOOL_EDIT -> {
                        String abs = toCacheKey(filePath, base);
                        if (abs != null) {
                            editPaths.put(tc.id(), abs);
                        }
                    }
                    default -> { /* 其余工具不参与 replay（对齐 aHt 只认这三支） */ }
                }
            }
        }
        // ── 第二遍：tool_result（本仓 role=tool 行）—— 对齐 aHt 第二遍 ──
        int readEntries = 0;
        int writeEntries = 0;
        int editEntries = 0;
        int readSkippedNoResult = 0;
        int readSkippedStubOrError = 0;
        int editSkippedDiskUnreadable = 0;
        int mergedNew = 0;
        int mergedOverwrite = 0;
        int skippedOlder = 0;
        for (ChatMessageDto m : transcript) {
            if (m == null || m.role() != Role.tool || m.toolCallId() == null) {
                continue;
            }
            String toolUseId = m.toolCallId();
            String content = m.content();
            boolean isError = Boolean.TRUE.equals(toolCallIsError.get(toolUseId));
            Long ts = epochMillis(m.createdAt());
            String key;

            // ── Read 支 ──
            key = readPaths.get(toolUseId);
            if (key != null) {
                if (isError || content == null
                        || content.startsWith(ReadFileTool.FILE_UNCHANGED_STUB)) {
                    // dedup stub 里没有文件内容（前一次真 Read 已入表）；错误结果同理。
                    // 对齐 aHt：`!SVt(Y.content)` + `Y.is_error!==!0`。
                    readSkippedStubOrError++;
                } else if (ts == null) {
                    // CC 侧同样要求 `D.timestamp` 存在（无时间戳 ⇒ 无法定 timestamp ⇒ 跳过）。
                    readSkippedNoResult++;
                } else {
                    String raw = reverseRender(content);
                    if (raw == null) {
                        // 非 text 分支渲染体（pdf / image / notebook / file_unchanged stub 变体）⇒ 不灌表
                        readSkippedNoResult++;
                    } else {
                        readEntries++;
                        int r = mergeOne(target, key,
                            new ToolUseContext.ReadState(ts, null, null, false, raw, false),
                            "Read");
                        if (r == 1) { mergedNew++; } else if (r == 2) { mergedOverwrite++; } else { skippedOlder++; }
                    }
                }
            }

            // ── Write 支 ──
            WriteHit wh = writeHits.get(toolUseId);
            if (wh != null && !isError && ts != null) {
                writeEntries++;
                int r = mergeOne(target, wh.path(),
                    new ToolUseContext.ReadState(ts, null, null, false, wh.content(), false),
                    "Write");
                if (r == 1) { mergedNew++; } else if (r == 2) { mergedOverwrite++; } else { skippedOlder++; }
            }

            // ── Edit 支 ──
            String editKey = editPaths.get(toolUseId);
            if (editKey != null && !isError) {
                DiskHit dh = readDisk(editKey);
                if (dh == null) {
                    editSkippedDiskUnreadable++;
                } else {
                    editEntries++;
                    // ⭐ contentNotInModelContext=TRUE：content 取自磁盘、模型没看过
                    //   （aHt：`{content:Uw(F), timestamp:ZZe(de), ..., contentNotInModelContext:!0}`）。
                    int r = mergeOne(target, editKey,
                        new ToolUseContext.ReadState(dh.mtimeMillis(), null, null, false, dh.content(), true),
                        "Edit");
                    if (r == 1) { mergedNew++; } else if (r == 2) { mergedOverwrite++; } else { skippedOlder++; }
                }
            }
        }
        Stats stats = new Stats(transcript.size(), readEntries, writeEntries, editEntries,
            readSkippedRanged, readSkippedNoResult, readSkippedStubOrError, editSkippedDiskUnreadable,
            mergedNew, mergedOverwrite, skippedOlder);
        if (log.isDebugEnabled()) {
            log.debug("[readFileState·replay] 本会话 replay 完成: sessionId={} 扫描消息 {} 条 ⇒ "
                    + "Read 派生 {} 条 / Write 派生 {} 条 / Edit 派生（打标 contentNotInModelContext）{} 条；"
                    + "跳过: 窗口读 {} 条 / 无结果或无时间戳 {} 条 / file_unchanged-stub 或错误 {} 条 / "
                    + "Edit 现读盘失败 {} 条；落表: 新插 {} 条 / 覆盖(更旧→被历史盖) {} 条 / "
                    + "历史更旧而保活表 {} 条；目标表现有 {} 条",
                sessionId, transcript.size(), readEntries, writeEntries, editEntries,
                readSkippedRanged, readSkippedNoResult, readSkippedStubOrError, editSkippedDiskUnreadable,
                mergedNew, mergedOverwrite, skippedOlder, target.size());
        }
        return stats;
    }

    // ────────────────────────── 内部实现 ──────────────────────────

    /** Write 支命中记录（toolUseId → 路径 + 入参 content）。 */
    private record WriteHit(String path, String content) {}

    /** Edit 支现读磁盘结果（content + 盘上 mtime）。 */
    private record DiskHit(String content, long mtimeMillis) {}

    /**
     * merge 单条 · 对齐 CC {@code s6r} 的逐条判定：{@code if(!s || o.timestamp > s.timestamp) r.set(i,o)}
     * —— {@code s} = 活表里的同名 entry。
     *
     * @return 1 = 新插（活表原本没有）；2 = 覆盖（历史条目更新）；0 = 活表那条更新 ⇒ <b>跳过</b>（保活表）
     */
    private static int mergeOne(FileStateCache target, String key,
                                ToolUseContext.ReadState value, String source) {
        ToolUseContext.ReadState existing = target.get(key);
        if (existing == null) {
            target.set(key, value);
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·replay] 新插 entry（{} 派生）key={} timestamp={} contentLen={}",
                    source, key, value.mtimeMillis(),
                    value.content() == null ? 0 : value.content().length());
            }
            return 1;
        }
        if (value.mtimeMillis() > existing.mtimeMillis()) {
            target.set(key, value);
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·replay] 覆盖 entry（{} 派生，历史更新）key={} 活表 timestamp={} → 历史 timestamp={}",
                    source, key, existing.mtimeMillis(), value.mtimeMillis());
            }
            return 2;
        }
        // ⭐ 活表那条更新（或同刻）⇒ 不压回（s6r 的 `o.timestamp > s.timestamp` 反向）。
        //   这条是「in-process 活表优先」的判据；删掉它（改成无条件 set）会让活表被历史盖回去。
        if (log.isDebugEnabled()) {
            log.debug("[readFileState·replay] 跳过（{} 派生，活表更新或同刻）key={} 活表 timestamp={} 历史 timestamp={}",
                source, key, existing.mtimeMillis(), value.mtimeMillis());
        }
        return 0;
    }

    /**
     * <b>反渲染</b>：把落库的 tool_result 文本还原成 {@code readFileState} 存的 raw 内容。
     *
     * <p>渲染式（{@code ReadFileTool#mapToToolResultBlockParam}）：
     * {@code freshnessNote + addLineNumbers(raw, startLine) + (injectReminder ? CYBER_RISK : "")}。
     * 反渲染必须按<b>渲染层实际附加的那几段</b>精确减去，⛔ 不能用 CC 的「通用剥 reminder + trim」——
     * 本仓实测（见 {@code ReadFileStateReplayTest} 前置 A）：
     * CC 的 {@code .replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g,'')} + {@code .trim()}
     * 在「文件正文自带 reminder 块」时会误剥正文，而 {@code .trim()} 又会把「以换行结尾」的文件的
     * 尾随 {@code \n} 吃掉 ⇒ 门禁 2 的内容兜底比对失配成假 stale。故本实现改成三段精确减法：
     * <ol>
     *   <li>① 逐字剥 {@link ReadFileTool#CYBER_RISK_MITIGATION_REMINDER} 后缀
     *       （常量自带前导 {@code "\n\n"} 与尾随 {@code "\n"} ⇒ 分隔符一并剥净，不留残行）；</li>
     *   <li>② 剥开头「整行就是一个 reminder 块」的 freshness 前缀；
     *       ⛔ 真实正文首行恒带行号前缀（{@code "1\t"} / {@code "     1→"}），故不会误剥正文；</li>
     *   <li>③ 整串只有一个 reminder 块（无换行）= 空文件 / offset-past-end 的 warning 分支
     *       ⇒ 文件内容为空串（与 CC 剥完 trim 后同结果）；</li>
     *   <li>④ 逐行剥行号前缀（CC {@code E9t} 逐字）。</li>
     * </ol>
     *
     * <p>⛔ <b>非文本渲染不猜</b>：pdf / image / notebook / file_unchanged 的 tool_result 不是行号体，
     * 第 ④ 步剥不动 ⇒ 本方法返回 {@code null}，调用方跳过该条（否则会把 JSON 摘要当文件内容灌进表）。
     *
     * @param rendered mapper 渲染后的文本（非 null、非空——空已在调用方挡掉）
     * @return raw 内容；{@code null} = 这不是本工具 text 分支的行号体（不可 replay）
     */
    static String reverseRender(String rendered) {
        String body = rendered;
        // ① CYBER_RISK 后缀（逐字常量，含其自带的 "\n\n" 前缀与尾部 "\n"）。
        if (body.endsWith(ReadFileTool.CYBER_RISK_MITIGATION_REMINDER)
                && body.length() > ReadFileTool.CYBER_RISK_MITIGATION_REMINDER.length()) {
            body = body.substring(0, body.length() - ReadFileTool.CYBER_RISK_MITIGATION_REMINDER.length());
        }
        // ② freshness 前缀：开头整行恰为一个 reminder 块且带换行。
        int firstNl = body.indexOf('\n');
        if (body.startsWith("<system-reminder>") && firstNl > 0
                && body.substring(0, firstNl).endsWith("</system-reminder>")) {
            body = body.substring(firstNl + 1);
        }
        // ③ 纯 warning 分支（整串一个 reminder 块、无换行）⇒ 文件内容为空串。
        if (body.startsWith("<system-reminder>") && body.endsWith("</system-reminder>")
                && body.indexOf('\n') < 0) {
            return "";
        }
        // ④ 逐行剥行号；先确认「首行确实带行号」（否则不是 text 分支的渲染体）。
        String[] lines = body.split("\n", -1);
        if (lines.length == 0 || !LINE_NUMBER_PREFIX.matcher(lines[0]).matches()) {
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·replay] 反渲染拒绝：首行不是行号体（非 text 分支渲染，如 pdf/image/"
                        + "notebook/file_unchanged）⇒ 不灌表。首行前 80 字符={}",
                    lines.length == 0 ? "<empty>" : preview(lines[0]));
            }
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            Matcher mt = LINE_NUMBER_PREFIX.matcher(lines[i]);
            out.append(mt.matches() ? mt.group(1) : lines[i]);
        }
        return out.toString();
    }

    /** 日志预览（避免把整段文件内容打进日志）。 */
    private static String preview(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    /** 解析 tool_use 的 arguments（JSON 字符串）；失败/空白 ⇒ null（软降级跳过该条）。 */
    private static JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(arguments);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·replay] tool_use arguments 解析失败（跳过该条）: err={}", e.toString());
            }
            return null;
        }
    }

    /** JSON 文本字段（非空白才返回，否则 null）。 */
    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String s = node.asText();
        return s.isBlank() ? null : s;
    }

    /** 消息自身 cwd 优先，缺失回落调用方基准（对齐本仓「每条读消息自带 cwd」事实）。 */
    private static String cwdBase(String messageCwd, String cwdFallback) {
        if (messageCwd != null && !messageCwd.isBlank()) {
            return messageCwd;
        }
        return cwdFallback;
    }

    /**
     * 派生 readFileState 的 key · <b>与门禁同源</b>：
     * {@code ToolUseContext.keyForReadFileState(guard, p)} = {@code guard.resolve(p).toAbsolutePath().normalize()}
     * 而 {@code guard.resolve(p)} = {@code Paths.get(PathGuard.expandPath(p, workdir))}。
     * 本方法复用同一个 {@link PathGuard#expandPath(String, String)} 静态展开器 + 同样的
     * {@code toAbsolutePath().normalize()}，保证 key 与 Edit/Write 门禁查表时<b>逐字相同</b>
     * （不同源必然出现「replay 灌了但门禁查不到」的死循环）。
     *
     * @param rawPath 原始 file_path（tool_use 入参）
     * @param base    解析基座（该消息自己的 cwd，缺失时为调用方兜底）
     * @return 归一化绝对路径字符串 key；base 缺失 / 展开异常 ⇒ null（软降级跳过该条）
     */
    private static String toCacheKey(String rawPath, String base) {
        if (base == null || base.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·replay] 跳过路径 {}：消息 cwd 与兜底基准均缺失（不猜基准）", rawPath);
            }
            return null;
        }
        try {
            return Paths.get(PathGuard.expandPath(rawPath, base)).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·replay] 跳过路径 {}：展开失败 err={}", rawPath, e.toString());
            }
            return null;
        }
    }

    /**
     * Edit 支现读磁盘（对齐 {@code aHt} 的 {@code readFileSyncWithMetadata(editFilePath)} +
     * {@code Uw} 去 BOM）。
     *
     * <p>内容形态与 {@code ReadFileTool} 写回 readFileState 的形状保持一致
     * （{@code FileEncodingReader.readFileMetadata} 已做 CRLF→LF 归一），再剥前导 U+FEFF
     * （{@code ReadFileTool} 逐行剥 BOM；{@code Uw(e)} = BOM 剥离）。
     *
     * @return null = 读盘失败（文件已删 / 不可读）⇒ 跳过该条（软降级，同 aHt 的
     *         {@code isFsInaccessible} 分支语义）
     */
    private static DiskHit readDisk(String absolutePath) {
        try {
            Path p = Paths.get(absolutePath);
            FileEncodingReader.FileMetadata meta = FileEncodingReader.readFileMetadata(p);
            String content = meta.content();
            if (content != null && !content.isEmpty() && content.charAt(0) == '﻿') {
                content = content.substring(1);
            }
            long mtime = java.nio.file.Files.getLastModifiedTime(p).toMillis();
            return new DiskHit(content, mtime);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[readFileState·replay] Edit 支现读盘失败（跳过该条，软降级）: path={} err={}",
                    absolutePath, e.toString());
            }
            return null;
        }
    }

    /** ISO-8601 → epoch millis（对齐 aHt 的 {@code new Date(D.timestamp).getTime()}）；null ⇒ null。 */
    private static Long epochMillis(OffsetDateTime ts) {
        return ts == null ? null : ts.toInstant().toEpochMilli();
    }
}
