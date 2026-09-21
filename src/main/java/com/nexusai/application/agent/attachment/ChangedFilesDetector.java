package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.compact.PostCompactAttachmentRestorer;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.StructuredPatchGenerator;
import com.nexusai.application.agent.tool.StructuredPatchHunk;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * <b>[步骤 7 · 投递层] 变更文件检测器</b> · 对齐 CC {@code getChangedFiles}
 * （Open-ClaudeCode/src/utils/attachments.ts:2063-2161）。
 *
 * <h2>为什么必须有这一层（本类的全部意义）</h2>
 * 头部冻结（{@code userContext}/{@code systemPromptSectionCache} 跨 run 复用）会让模型看到<b>陈旧</b>
 * 的 CLAUDE.md / rules。CC 并不是「冻结了事」，它另外有一条<b>通用、无 feature 门控、每轮运行</b>的
 * 变更投递通道：{@code maybe('changed_files', () => getChangedFiles(context))}
 * （attachments.ts:871，位于 {@code allThreadAttachments} 段 —— 主线程与子代理<b>都</b>评估，
 * 且该行<b>不在</b>任何 {@code feature(...)} 分支里）。产出的 {@code edited_text_file} 附件经
 * {@code yield attachment; toolResults.push(attachment)}（query.ts:1580-1588）成为<b>会话里的一条真实
 * 消息</b>，位置在<b>尾部</b> ⇒ 头部字节不变、模型仍得知文件变了。
 * ⛔ 只做头部冻结而砍掉本通道 = 把 CC 的 freshness 能力砍掉，<b>不算对齐</b>（计划 §1.5）。
 *
 * <h2>判据链（逐条对齐 CC，全部来自真源而非注释）</h2>
 * <ol>
 *   <li>遍历 {@code readFileState} 的 key（:2066 {@code cacheKeys(toolUseContext.readFileState)}）</li>
 *   <li><b>跳过 offset/limit 已设的 entry</b>（:2076-2078，CC 注释自述 {@code TODO: Implement offset/limit
 *       support}）：那类 entry 只缓存了窗口/局部视图，拿它做 diff 会得出误导性变更行</li>
 *   <li><b>read deny 规则命中的路径跳过</b>（:2083-2085 {@code isFileReadDenied}）</li>
 *   <li>取磁盘 mtime，<b>判据 = {@code mtime > fileState.timestamp}</b>（:2088-2090）</li>
 *   <li>读新内容（CC :2104 {@code FileReadTool.call}），<b>并把新内容 + 新 mtime 写回 readFileState</b>
 *       （CC 经 {@code callInner} 的 {@code readFileState.set}，FileReadTool.ts:1032-1037）——
 *       这一步发生在「片段是否为空」判定<b>之前</b>，故「mtime 变了但内容没变」（touch / 存盘重写）
 *       也会落位，不会每轮重复读盘</li>
 *   <li>算 diff 片段；<b>片段为空 ⇒ 不产出附件</b>（:2112-2115 {@code // File was touched but not
 *       modified}）</li>
 *   <li>成功 ⇒ {@code {type:'edited_text_file', filename, snippet}}（:2117-2121）</li>
 *   <li>读盘异常：<b>仅 ENOENT（文件确实被删）才驱逐</b>（:2153-2155）；瞬时 stat 失败不得驱逐
 *       （CC 注释自述：编辑器 tmp→rename 的原子保存竞态会让 stat 撞上空档，误驱逐会让下一次 Edit
 *       假报 code-6）</li>
 * </ol>
 *
 * <h2>⭐ 本仓映射与<b>诚实登记的残缺</b>（⛔ 不假装抄了就完美）</h2>
 * <ol>
 *   <li><b>[有意偏离 1] 「写回 readFileState 时的 offset 取值」</b>：CC 在 getChangedFiles 里调
 *       {@code FileReadTool.call({file_path})}，该调用把 {@code offset} 解构默认成 <b>1</b>
 *       （FileReadTool.ts:497 {@code { file_path, offset = 1, ... }}）并原样写回
 *       （:1032-1037）⇒ CC 的 entry 变成 {@code offset=1}，而 getChangedFiles 自己第 2 条又
 *       {@code offset !== undefined ⇒ return null} —— 即：<b>同一个文件在 CC 里只会被投递一次</b>，
 *       之后该 entry 永远被自己的跳过规则挡掉（除非模型再 Read 一次重置）。
 *       本仓<b>写回 {@code offset=null}/{@code limit=null}</b>（= 全量视图），使变更投递<b>可重复</b>：
 *       这正是本步骤要保住的 freshness 能力（一次性通道等于该能力悄悄死掉）。
 *       偏离理由与证据如上，已登记；若后续要对齐该形态，必须同时改 getChangedFiles 的跳过规则。</li>
 *   <li><b>[有意偏离 2] diff 超时的表达方式</b>：CC 的 {@code structuredPatch({timeout: DIFF_TIMEOUT_MS})}
 *       超时返回 undefined ⇒ {@code !patch} ⇒ 片段 {@code ''}（静默无附件）。本仓的 LCS 端口
 *       （{@link StructuredPatchGenerator}）无超时概念，但 DP 矩阵是 O(行数²) 内存 ⇒ 用
 *       {@link #DIFF_MAX_MATRIX_CELLS} 表达同一件事：超限 = 等价于 CC 的超时退化（记 WARN 后返回空片段）。</li>
 *   <li><b>[CC 已知残缺，如实抄下] readFileState 是 100 条 + 25MB 双限 LRU</b>（fileStateCache.ts:18/:22，
 *       本仓 {@link FileStateCache} 同构）⇒ 忙会话会把根 CLAUDE.md 挤掉，此后它的变更<b>不再被投递</b>
 *       （CC 只有 nested memory 有 {@code loadedNestedMemoryPaths} 非驱逐兜底，根 CLAUDE.md 没有）。</li>
 *   <li><b>[CC 已知残缺，如实抄下] 只投 8KB 截断的 diff 片段</b>（{@link #DIFF_SNIPPET_MAX_BYTES}）⇒
 *       模型会同时看到「头部旧全文」与「尾部说它变了」的矛盾态；且片段只含<b>新增/上下文</b>行
 *       （删除行被 {@code filter(!startsWith('-'))} 丢弃），没有「删掉了什么」的完整信息。</li>
 * </ol>
 *
 * <h2>local-only 红线</h2>
 * 本类只读磁盘 + 读写会话内的 {@link FileStateCache}，不序列化、不外发。
 */
public final class ChangedFilesDetector {

    private static final Logger log = LoggerFactory.getLogger(ChangedFilesDetector.class);

    /**
     * diff 片段字节上限 · CC {@code DIFF_SNIPPET_MAX_BYTES = 8192}
     * （FileEditTool/utils.ts:355，注释自述：format-on-save 大文件曾整篇每轮注入，观测最大 16.1KB
     * ≈ 14K tokens/会话；8KB 保留有意义上下文又封顶最坏情况）。
     *
     * <p>⚠ 比较口径 = <b>UTF-16 code unit 数</b>（CC JS {@code String.length} ≡ Java
     * {@code String.length()}），非字节数 —— 名称沿用 CC 的 "BYTES" 以免二次漂移。
     */
    public static final int DIFF_SNIPPET_MAX_BYTES = 8192;

    /**
     * diff 上下文行数 · CC {@code getSnippetForTwoFileDiff} 的 {@code structuredPatch(..., {context: 8})}
     * （FileEditTool/utils.ts:374）。⚠ 与 Edit 工具的 {@code CONTEXT_LINES = 3} 不同，勿混。
     */
    public static final int DIFF_CONTEXT_LINES = 8;

    /**
     * LCS DP 矩阵格数上限 · <b>本仓对 CC {@code timeout: DIFF_TIMEOUT_MS} 的等价表达</b>
     * （见类 javadoc 偏离 2）：{@code 4_000_000} 格 ≈ 16MB int[][]，对应约 2000×2000 行。
     * 超限即返回空片段（= CC 超时后 {@code !patch ⇒ ''} 的无附件退化）。
     */
    public static final long DIFF_MAX_MATRIX_CELLS = 4_000_000L;

    /**
     * compact 行号前缀开关（{@code "N\t"} / {@code "     N→"}）· 对齐 CC
     * {@code isCompactLinePrefixEnabled()}（utils/file.ts:278-285，3P 默认 = killswitch off = compact）。
     *
     * <p><b>为什么是静态字段而不是本类构造参数</b>：CC 侧它是<b>进程级</b>全局读（GB flag 缓存），
     * 片段渲染（本类）与 Read 工具输出（{@code ReadFileTool}）必须<b>同格式</b> —— 模型拿尾部片段
     * 与其上下文里的 Read 输出对行号时，两种格式混用会平添噪声。故本字段是那<b>一个</b>属性的
     * 第二持有者，由 {@code ReadFileTool} 在 Spring 装配时镜像过来（见其 {@code @PostConstruct}）。
     * 默认 true 与 CC 3P default 一致（非 Spring 场景 / 纯单测 = 该默认）。
     */
    private static volatile boolean compactLinePrefixEnabled = true;

    /** 装配点 · {@code ReadFileTool} 把同一属性（{@code nexusai.compact-line-prefix.enabled}）镜像进来。 */
    public static void setCompactLinePrefixEnabled(boolean enabled) {
        compactLinePrefixEnabled = enabled;
    }

    /** 当前行号前缀形态（诊断/测试用）。 */
    public static boolean isCompactLinePrefixEnabled() {
        return compactLinePrefixEnabled;
    }

    private ChangedFilesDetector() {
    }

    /**
     * 检测「上次读过、现在磁盘上变了的文件」并产出 {@code edited_text_file} 附件 ·
     * 对齐 CC {@code getChangedFiles}（utils/attachments.ts:2063-2161）。
     *
     * <p>副作用（对齐 CC）：对每个 mtime 已变的候选，把<b>新内容 + 新 mtime</b> 写回
     * {@code readFileState}；仅 ENOENT 时驱逐该 entry。
     *
     * @param readFileState    会话级已读文件状态表（CC {@code toolUseContext.readFileState}）；
     *                         null/空 → 空列表（CC :2067 {@code if (filePaths.length === 0) return []}）
     * @param permCtx          权限上下文（read deny 判定；null → 不 deny，同 CC appState 缺失语义）
     * @param cwd              路径规则的 root-relative 匹配基准（会话工作目录；null → 无会话回落）
     * @param maxFileSizeBytes 单文件读取上限（CC {@code readFileInRange} 的 maxSizeBytes；
     *                         本仓 {@code FileReadingLimits.DEFAULT_MAX_SIZE_BYTES = 256KB}）；
     *                         超限的文件 = CC 的读取抛错路径 ⇒ 跳过（记 WARN）
     * @return 附件列表（每项 type='edited_text_file'）；无变更 → 空列表
     */
    public static List<AttachmentMessageDto> detectChangedFiles(
            FileStateCache readFileState, ToolPermissionContext permCtx, String cwd,
            long maxFileSizeBytes) {
        if (readFileState == null || readFileState.size() == 0) {
            return List.of();
        }
        List<AttachmentMessageDto> result = new ArrayList<>();
        // entries() 已返回快照（FileStateCache 内 List.copyOf(cache.entrySet())）⇒ 迭代期写缓存安全
        Iterator<Map.Entry<String, ToolUseContext.ReadState>> it = readFileState.entries();
        while (it.hasNext()) {
            String path = it.next().getKey();
            AttachmentMessageDto att = detectOne(path, readFileState, permCtx, cwd, maxFileSizeBytes);
            if (att != null) {
                result.add(att);
            }
        }
        return result;
    }

    /**
     * 单文件检测 · 对齐 CC {@code getChangedFiles} 的 {@code filePaths.map(async filePath => {...})} 体
     * （attachments.ts:2071-2158）。
     */
    private static AttachmentMessageDto detectOne(String path, FileStateCache readFileState,
                                                 ToolPermissionContext permCtx, String cwd,
                                                 long maxFileSizeBytes) {
        ToolUseContext.ReadState fileState = readFileState.get(path);
        if (fileState == null) {
            return null;
        }
        // CC :2076-2078 —— offset/limit 已设 = 只缓存了窗口视图，diff 会误导（CC 自述 TODO 未实现）
        if (fileState.offset() != null || fileState.limit() != null) {
            if (log.isDebugEnabled()) {
                log.debug("[changed_files] 跳过（entry 是窗口视图，offset={} limit={}）: path={} · CC attachments.ts:2076-2078",
                    fileState.offset(), fileState.limit(), path);
            }
            return null;
        }
        // CC :2083-2085 —— read deny 命中的路径不再投递（复用附件恢复链同一实现，单点，防双轨）
        if (PostCompactAttachmentRestorer.isFileReadDenied(path, permCtx, cwd)) {
            if (log.isDebugEnabled()) {
                log.debug("[changed_files] 跳过（read deny 规则命中）: path={} · CC attachments.ts:2083-2085", path);
            }
            return null;
        }
        long mtime;
        try {
            FileTime t = Files.getLastModifiedTime(Path.of(path));
            mtime = t.toMillis();
        } catch (NoSuchFileException e) {
            // CC :2153-2155 —— 仅 ENOENT 驱逐（文件确实被删）；瞬时 stat 失败不得驱逐
            readFileState.delete(path);
            log.info("[changed_files] 文件已不存在 ⇒ 从 readFileState 驱逐: path={} · CC attachments.ts:2153-2155", path);
            return null;
        } catch (IOException | RuntimeException e) {
            // CC :2145-2157 catch —— 非 ENOENT 的异常只跳过、不驱逐（防原子保存竞态误驱逐）
            log.warn("[changed_files] stat 失败（非 ENOENT，不驱逐）: path={} 原因={} · CC attachments.ts:2145-2157",
                path, e.getMessage());
            return null;
        }
        long recorded = fileState.mtimeMillis();
        if (mtime <= recorded) {
            if (log.isDebugEnabled()) {
                log.debug("[changed_files] 跳过（未变更）: path={} mtime={} 记录时间戳={} · 判据 mtime>记录时间戳（CC :2088-2090）",
                    path, mtime, recorded);
            }
            return null;
        }
        long size;
        try {
            size = Files.size(Path.of(path));
        } catch (IOException e) {
            log.warn("[changed_files] 取文件大小失败 ⇒ 跳过: path={} 原因={}", path, e.getMessage());
            return null;
        }
        if (size > maxFileSizeBytes) {
            // CC 等价路径 = FileReadTool.call 内部 readFileInRange 抛「exceeds maximum allowed size」→ catch → null
            log.warn("[changed_files] 跳过（文件 {} 字节 > 读取上限 {} 字节）: path={} · CC attachments.ts:2104 读取抛错路径",
                size, maxFileSizeBytes, path);
            return null;
        }
        String newContent;
        try {
            // CRLF 归一化：与 ReadFileTool / EditFileTool 写回 readFileState 的口径一致，
            // 否则换行符差异会淹没真实变更行（本仓既有约定，见 ToolUseContext.ReadState javadoc）
            newContent = Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException | RuntimeException e) {
            log.warn("[changed_files] 读文件失败 ⇒ 跳过: path={} 原因={}", path, e.getMessage());
            return null;
        }
        String oldContent = fileState.content();
        // CC :2104 —— 先写回 readFileState（新内容 + 新 mtime），再判片段是否为空：
        //   「mtime 变了但内容没变」（touch / 存盘重写）也落位 ⇒ 下一轮不再重复读盘
        //   ⚠ 本仓写的 offset/limit 是 null（= 全量视图），非 CC 的 offset=1 —— 见类 javadoc「有意偏离 1」
        readFileState.set(path, new ToolUseContext.ReadState(mtime, null, null, false, newContent));
        if (oldContent == null) {
            // 无基线内容（ReadState.full(mtime) 无 content 变体）⇒ 无从 diff，只落位新内容
            if (log.isDebugEnabled()) {
                log.debug("[changed_files] 跳过（entry 无基线内容，无从 diff）: path={} 已落位新内容 mtime={}", path, mtime);
            }
            return null;
        }
        String snippet = getSnippetForTwoFileDiff(oldContent, newContent);
        if (snippet.isEmpty()) {
            // CC :2112-2115 —— 「File was touched but not modified」
            if (log.isDebugEnabled()) {
                log.debug("[changed_files] 跳过（mtime 变了但内容一致 = touched but not modified）: path={} mtime={} 记录时间戳={}"
                    + " · CC attachments.ts:2112-2115", path, mtime, recorded);
            }
            return null;
        }
        log.info("[changed_files] 产出 edited_text_file 附件（尾部投递用）: path={} mtime={} 记录时间戳={} 片段字符数={}"
            + " · CC attachments.ts:2117-2121",
            path, mtime, recorded, snippet.length());
        return AttachmentMessageDto.editedTextFile(path, snippet);
    }

    // ════════════════════════════════════════════════════════════════════
    // 片段生成 · 对齐 CC getSnippetForTwoFileDiff（FileEditTool/utils.ts:362-406）
    // ════════════════════════════════════════════════════════════════════

    /**
     * old → new 的变更片段 · 对齐 CC {@code getSnippetForTwoFileDiff}
     * （Open-ClaudeCode/src/tools/FileEditTool/utils.ts:362-406）。
     *
     * <p><b>CC original 逐行语义</b>：
     * <pre>
     * const patch = structuredPatch('file.txt','file.txt', A, B, undefined, undefined, {context: 8, timeout});
     * if (!patch) return ''
     * const full = patch.hunks
     *   .map(_ => ({ startLine: _.oldStart,
     *                content: _.lines.filter(_ => !_.startsWith('-') &amp;&amp; !_.startsWith('\\'))
     *                                 .map(_ => _.slice(1)).join('\n') }))
     *   .map(addLineNumbers)
     *   .join('\n...\n')
     * if (full.length &lt;= 8192) return full
     * const cutoff = full.lastIndexOf('\n', 8192)
     * const kept = cutoff &gt; 0 ? full.slice(0, cutoff) : full.slice(0, 8192)
     * return `${kept}\n\n... [${countCharInString(full,'\n',kept.length)+1} lines truncated] ...`
     * </pre>
     *
     * <p>要点：<b>删除行与 diff 元数据行（{@code '\'} 开头）被过滤掉</b>，行号取
     * {@code hunk.oldStart}（旧文件起始行），hunk 之间以 {@code "\n...\n"} 连接；截断在<b>最后一个
     * 能放下的换行处</b>，并追加与 BashTool 同形的截断标记。
     *
     * @param fileAContents 旧内容（readFileState 里记的上次内容）
     * @param fileBContents 新内容（本次从磁盘读到的内容）
     * @return 变更片段；无变更 / 超限退化 → {@code ""}（调用方据此不产出附件）
     */
    static String getSnippetForTwoFileDiff(String fileAContents, String fileBContents) {
        String oldContent = fileAContents == null ? "" : fileAContents;
        String newContent = fileBContents == null ? "" : fileBContents;
        long oldLines = countLines(oldContent);
        long newLines = countLines(newContent);
        if (oldLines * newLines > DIFF_MAX_MATRIX_CELLS) {
            // 等价于 CC 的 structuredPatch 超时退化（!patch ⇒ ''）—— 见类 javadoc「有意偏离 2」
            log.warn("[changed_files] diff 规模超限（旧 {} 行 × 新 {} 行 > {} 格）⇒ 片段退化为空"
                + "（= CC structuredPatch 超时后退化为无附件）", oldLines, newLines, DIFF_MAX_MATRIX_CELLS);
            return "";
        }
        List<StructuredPatchHunk> hunks =
            StructuredPatchGenerator.getPatch(oldContent, newContent, DIFF_CONTEXT_LINES);
        if (hunks.isEmpty()) {
            // CC `if (!patch) return ''` 的等价分支（本仓 getPatch 无变更即空数组）
            return "";
        }
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < hunks.size(); i++) {
            if (i > 0) {
                full.append("\n...\n");
            }
            StructuredPatchHunk hunk = hunks.get(i);
            StringBuilder content = new StringBuilder();
            boolean first = true;
            for (String line : hunk.lines()) {
                if (line.startsWith("-") || line.startsWith("\\")) {
                    continue;   // CC filter：删除行 + diff 元数据行
                }
                if (!first) {
                    content.append('\n');
                }
                first = false;
                content.append(line.isEmpty() ? "" : line.substring(1));   // CC _.slice(1) 去掉前缀字符
            }
            full.append(addLineNumbers(content.toString(), hunk.oldStart()));
        }
        String s = full.toString();
        if (s.length() <= DIFF_SNIPPET_MAX_BYTES) {
            return s;
        }
        int cutoff = s.lastIndexOf('\n', DIFF_SNIPPET_MAX_BYTES);
        String kept = cutoff > 0 ? s.substring(0, cutoff) : s.substring(0, DIFF_SNIPPET_MAX_BYTES);
        int remaining = countCharInString(s, '\n', kept.length()) + 1;
        return kept + "\n\n... [" + remaining + " lines truncated] ...";
    }

    /**
     * 带行号的片段渲染 · 对齐 CC {@code addLineNumbers({content, startLine})}
     * （Open-ClaudeCode/src/utils/file.ts:290-319）。
     *
     * <p>⚠ <b>与 {@code ReadFileTool#addLineNumbers} 是同一条 CC 函数的翻译</b>（两处渲染必须同格式：
     * 模型把尾部片段与上下文里的 Read 输出对行号）。两者共用同一个进程级开关
     * （{@link #isCompactLinePrefixEnabled()} ← 由 ReadFileTool 装配时镜像），故格式恒一致。
     *
     * <p>逐字语义：{@code content} 空 → {@code ""}；compact 开 → {@code `${i+startLine}\t${line}`}；
     * 关 → 行号字符串长度 ≥6 用 {@code `${num}→${line}`}，否则 {@code padStart(6,' ')+'→'+line}。
     *
     * @param content   无行号内容（可为空）
     * @param startLine 1-indexed 起始行（CC hunk.oldStart）
     * @return 行号化文本
     */
    static String addLineNumbers(String content, int startLine) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);   // -1 保留尾随空片段（对齐 JS split 语义）
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (compactLinePrefixEnabled) {
                out.append(i + startLine).append('\t').append(lines[i]);
            } else {
                String numStr = String.valueOf(i + startLine);
                if (numStr.length() >= 6) {
                    out.append(numStr).append('→').append(lines[i]);
                } else {
                    out.append(" ".repeat(6 - numStr.length())).append(numStr).append('→').append(lines[i]);
                }
            }
        }
        return out.toString();
    }

    /** 行数（CC split(/\r?\n/).length 的等价物；空串记 0 行）。仅用于超限预判。 */
    private static long countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        long n = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /**
     * 统计 {@code start} 之后出现的字符数 · 对齐 CC {@code countCharInString(str, char, start)}
     * （utils/stringUtils.ts:54-66：{@code indexOf(char, start)} 起循环计数）。
     */
    private static int countCharInString(String s, char ch, int start) {
        int count = 0;
        for (int i = Math.max(start, 0); i < s.length(); i++) {
            if (s.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }
}
