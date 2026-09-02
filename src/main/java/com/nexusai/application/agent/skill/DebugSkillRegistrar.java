package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug skill 注册器 · 对齐 CC skills/bundled/debug.ts.
 *
 * <p>L1 语义: 注册 /debug skill — 调试当前 Claude Code session.
 *            - 非 ant 用户默认未启用 debug log,先 enableDebugLogging 再读 log
 *            - tail 读 log 最后 64KB,取最后 20 行 (避免 RSS spike)
 *            - 拼装 prompt 含 debug log path + tail + 是否刚启用 + 用户 args + 设置路径 + 指引
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: register() → BundledSkillDefinition;
 *       getPromptForCommand(args) → List&lt;PromptBlock&gt;; 6 段 prompt 结构;
 *       DEFAULT_DEBUG_LINES_READ=20, TAIL_READ_BYTES=64*1024;
 *       disableModelInvocation=true (用户需显式调用,免占 context).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 读 stats → 取 min(size, TAIL_READ_BYTES) → tail read
 *       → 切最后 20 行 → 拼装 prompt (log 路径 + size + tail + just-enabled section + args + 设置路径 + instructions).</li>
 *   <li><b>A3</b>: 状态机: NOT_LOGGING → JUST_ENABLED (一次) → LOGGING;
 *       fs 异常 (NoSuchFile) → "No debug log exists yet" + justEnabledSection;
 *       其它异常 → "Failed to read last 20 lines..." + justEnabledSection.</li>
 *   <li><b>A4</b>: log 不存在 (NoSuchFile) → 用 fallback 文案;其它 IOException → 用 fallback 文案;
 *       args 为 null/empty → "The user did not describe a specific issue...";
 *       用户类型 ant vs 非 ant → 不同 description (process.env.USER_TYPE).</li>
 *   <li><b>A5</b>: 真实场景 — 用户 /debug "tool X 失败" → 启用 logging → 读 tail → 给出排查指引.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async fs/promises → 注入式 Supplier&lt;String&gt; (logPath) + FileTailReader (path,offset,size) → tail string;
 *                    TS `process.env.USER_TYPE` → 注入式 BooleanSupplier (isAnt);
 *                    TS `registerBundledSkill({...})` → 直接返回 BundledSkillDefinition record (上层 register).
 */
public final class DebugSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DebugSkillRegistrar.class);

    public static final int DEFAULT_DEBUG_LINES_READ = 20;
    public static final int TAIL_READ_BYTES = 64 * 1024;

    private final BooleanSupplier isAnt;                   // process.env.USER_TYPE === 'ant'
    private final Supplier<String> debugLogPathSupplier;
    private final BooleanSupplier debugLoggingEnabler;     // returns wasAlreadyLogging
    private final FileTailReader tailReader;               // (path, size) → tail string
    private final FileStatReader statReader;               // path → FileStat (size)
    private final FileSizeFormatter fileSizeFormatter;     // long → "1.2 MB"
    private final SettingsPathProvider settingsPathProvider; // source → path

    public DebugSkillRegistrar(BooleanSupplier isAnt,
                                Supplier<String> debugLogPathSupplier,
                                BooleanSupplier debugLoggingEnabler,
                                FileTailReader tailReader,
                                FileStatReader statReader,
                                FileSizeFormatter fileSizeFormatter,
                                SettingsPathProvider settingsPathProvider) {
        this.isAnt = Objects.requireNonNull(isAnt);
        this.debugLogPathSupplier = Objects.requireNonNull(debugLogPathSupplier);
        this.debugLoggingEnabler = Objects.requireNonNull(debugLoggingEnabler);
        this.tailReader = Objects.requireNonNull(tailReader);
        this.statReader = Objects.requireNonNull(statReader);
        this.fileSizeFormatter = Objects.requireNonNull(fileSizeFormatter);
        this.settingsPathProvider = Objects.requireNonNull(settingsPathProvider);
    }

    /** 注入 — 读文件尾部 (path,size) → tail string. 抛 IOException 表示读失败. */
    @FunctionalInterface
    public interface FileTailReader {
        String readTail(String path, long offset, long size) throws java.io.IOException;
    }

    /** 注入 — 读文件 stat. */
    @FunctionalInterface
    public interface FileStatReader {
        FileStat stat(String path) throws java.io.IOException;
    }

    public record FileStat(long size) {}

    /** 注入 — 格式化文件大小. */
    @FunctionalInterface
    public interface FileSizeFormatter {
        String format(long bytes);
    }

    /** 注入 — settings file path 解析. */
    @FunctionalInterface
    public interface SettingsPathProvider {
        String pathFor(String source);  // 'userSettings' / 'projectSettings' / 'localSettings'
    }

    /** CC registerDebugSkill — 统一产出 BundledSkillDefinition（P1-4）. */
    public BundledSkillDefinition register() {
        return new BundledSkillDefinition(
            "debug",
            isAnt.getAsBoolean()
                ? "Debug your current Claude Code session by reading the session debug log. Includes all event logging"
                : "Enable debug logging for this session and help diagnose issues",
            null,   // aliases
            null,   // whenToUse
            "[issue description]",
            java.util.List.of("Read", "Grep", "Glob"),   // allowedTools (CC debug.ts:19)
            null,   // model
            true,   // disableModelInvocation (CC debug.ts:23)
            true,   // userInvocable (CC debug.ts:24)
            null,   // isEnabled
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, cwd) -> getPromptForCommand(args)
        );
    }

    /** CC getPromptForCommand — 主链. */
    public java.util.List<PromptBlock> getPromptForCommand(String args) {
        boolean wasAlreadyLogging = debugLoggingEnabler.getAsBoolean();
        String debugLogPath = debugLogPathSupplier.get();

        String logInfo = readLogTail(debugLogPath);

        String justEnabledSection = wasAlreadyLogging ? "" :
            "\n## Debug Logging Just Enabled\n\n"
            + "Debug logging was OFF for this session until now. Nothing prior to this /debug invocation was captured.\n\n"
            + "Tell the user that debug logging is now active at `" + debugLogPath
            + "`, ask them to reproduce the issue, then re-read the log. "
            + "If they can't reproduce, they can also restart with `claude --debug` to capture logs from startup.\n";

        String issueSection = (args == null || args.isEmpty())
            ? "The user did not describe a specific issue. Read the debug log and summarize any errors, warnings, or notable issues."
            : args;

        String prompt = "# Debug Skill\n\n"
            + "Help the user debug an issue they're encountering in this current Claude Code session.\n"
            + justEnabledSection
            + "\n## Session Debug Log\n\n"
            + "The debug log for the current session is at: `" + debugLogPath + "`\n\n"
            + logInfo + "\n\n"
            + "For additional context, grep for [ERROR] and [WARN] lines across the full file.\n\n"
            + "## Issue Description\n\n"
            + issueSection + "\n\n"
            + "## Settings\n\n"
            + "Remember that settings are in:\n"
            + "* user - " + settingsPathProvider.pathFor("userSettings") + "\n"
            + "* project - " + settingsPathProvider.pathFor("projectSettings") + "\n"
            + "* local - " + settingsPathProvider.pathFor("localSettings") + "\n\n"
            + "## Instructions\n\n"
            + "1. Review the user's issue description\n"
            + "2. The last " + DEFAULT_DEBUG_LINES_READ + " lines show the debug file format. Look for [ERROR] and [WARN] entries, stack traces, and failure patterns across the file\n"
            + "3. Consider launching the claude-code-guide subagent to understand the relevant Claude Code features\n"
            + "4. Explain what you found in plain language\n"
            + "5. Suggest concrete fixes or next steps\n";

        return java.util.List.of(PromptBlock.text(prompt));
    }

    private String readLogTail(String debugLogPath) {
        try {
            FileStat stats = statReader.stat(debugLogPath);
            long readSize = Math.min(stats.size(), TAIL_READ_BYTES);
            long startOffset = Math.max(0, stats.size() - readSize);
            String tail = tailReader.readTail(debugLogPath, startOffset, readSize);
            // CC debug.ts:46-47 split('\n').slice(-20) — JS split 保留尾部空元素；Java split 默认丢尾部
            // 空串，-1 限长复现 CC 语义（日志以 \n 结尾时尾块多一行空行，与 CC 输出一致）.
            String[] lines = tail.split("\n", -1);
            int take = Math.min(lines.length, DEFAULT_DEBUG_LINES_READ);
            StringBuilder sb = new StringBuilder();
            sb.append("Log size: ").append(fileSizeFormatter.format(stats.size())).append("\n\n");
            sb.append("### Last ").append(DEFAULT_DEBUG_LINES_READ).append(" lines\n\n");
            sb.append("```\n");
            for (int i = lines.length - take; i < lines.length; i++) {
                sb.append(lines[i]);
                if (i < lines.length - 1) sb.append("\n");
            }
            sb.append("\n```");
            return sb.toString();
        } catch (java.io.IOException e) {
            if (isEnoent(e)) {
                return "No debug log exists yet — logging was just enabled.";
            }
            return "Failed to read last " + DEFAULT_DEBUG_LINES_READ + " lines of debug log: " + e.getMessage();
        }
    }

    private static boolean isEnoent(java.io.IOException e) {
        // JDK 8 / 11 / 17+: java.nio.file.NoSuchFileException is the common ENOENT
        return e instanceof java.nio.file.NoSuchFileException
            || (e.getClass().getSimpleName().contains("NotFound"))
            || "No such file or directory".equals(e.getMessage());
    }

    /**
     * 文件型 debug log 基建 · 对齐 CC {@code utils/debug.ts}（拍板#9 part1 生产 wiring 真实化）。
     *
     * <p>替代旧生产全桩（Bootstrapper 注入 isAnt=()->false / path="/tmp/debug.log" / enabler=()->true /
     * tail=空 / stat=抛 NoSuchFile / formatter=Long::toString / settingsPath=桩 —— NG-CDB-1）。本嵌套类提供
     * CC debug.ts 生产可观测行为所需的全部真实数据源：
     * <ul>
     *   <li>{@link #getDebugLogPath()} — CC {@code getDebugLogPath}（utils/debug.ts:230-236）
     *       {@code getDebugFilePath() ?? process.env.CLAUDE_CODE_DEBUG_LOGS_DIR ?? join(configHome,'debug',`${getSessionId()}.txt`)}
     *       （[决策 D1/D2] configHome 用 nexusai 自有根 {user.home}/.{appName}，不再写 ~/.claude）</li>
     *   <li>{@link #enableDebugLogging()} — CC {@code enableDebugLogging}（utils/debug.ts:64-69）
     *       {@code wasActive = isDebugMode() || process.env.USER_TYPE === 'ant'; runtimeDebugEnabled = true; return wasActive}</li>
     *   <li>{@link #isDebugMode()} — CC {@code isDebugMode}（utils/debug.ts:44-57）</li>
     *   <li>{@link #stat(String)} / {@link #readTail(String,long,long)} — CC debug.ts:35/:38-48 真实文件读取</li>
     *   <li>{@link #formatFileSize(long)} — CC {@code formatFileSize}（utils/format.ts:9-24）</li>
     *   <li>{@link #settingsPathFor(String)} — CC {@code getSettingsFilePathForSource}（settings.ts:274-296）</li>
     * </ul>
     *
     * <p>L3 (Java idiom)：CC {@code process.argv} → Java {@code System.getProperty("claude.debugFile")}
     * （--debug-file= 等价，Java 后端经 -D 注入）；CC {@code getSessionId()}（bootstrap/state.js 进程级）→
     * Java {@link RequestContext#sessionId()}（请求级，web 后端每会话 MDC 携带）。真源读 CC 实际 TS 源码。
     */
    public static final class DebugLogging {

        /** 运行时 debug 已开启 · CC original: {@code runtimeDebugEnabled}（utils/debug.ts:42 模块级变量）。 */
        private static volatile boolean runtimeDebugEnabled = false;

        private DebugLogging() {
            // 静态工具类
        }

        /**
         * 启用 debug log · CC original: {@code enableDebugLogging}（utils/debug.ts:64-69）
         * <pre>wasActive = isDebugMode() || process.env.USER_TYPE === 'ant';
         * runtimeDebugEnabled = true; return wasActive</pre>
         * 返回是否本就已开启（决定 /debug 的 justEnabled 段是否渲染）。
         */
        public static boolean enableDebugLogging() {
            boolean wasActive = isDebugMode() || "ant".equalsIgnoreCase(System.getenv("USER_TYPE"));
            runtimeDebugEnabled = true;
            return wasActive;
        }

        /**
         * 是否 debug mode · CC original: {@code isDebugMode}（utils/debug.ts:44-57）。
         * Java 无 process.argv 探测，等价取 env DEBUG / DEBUG_SDK + claude.debugFile 属性。
         */
        public static boolean isDebugMode() {
            return runtimeDebugEnabled
                || isEnvTruthy(System.getenv("DEBUG"))
                || isEnvTruthy(System.getenv("DEBUG_SDK"))
                || getDebugFilePath() != null;
        }

        /** CC original: getDebugFilePath()（utils/debug.ts:91-102）— --debug-file= 等价：system property claude.debugFile。 */
        public static String getDebugFilePath() {
            String p = System.getProperty("claude.debugFile");
            return (p == null || p.isBlank()) ? null : p;
        }

        /**
         * 真实 debug log 路径 · CC original: {@code getDebugLogPath}（utils/debug.ts:230-236）
         * <pre>getDebugFilePath() ?? process.env.CLAUDE_CODE_DEBUG_LOGS_DIR ?? join(getClaudeConfigHomeDir(),'debug',`${getSessionId()}.txt`)</pre>
         * 三优先级：claude.debugFile 属性 → CLAUDE_CODE_DEBUG_LOGS_DIR env → {configHome}/debug/{sessionId}.txt。
         * <b>[T · 决策 D1/D2]</b> 默认写盘根改 nexusai 自有根（{@link NexusaiPaths#getAppConfigHomeDir()}
         * ={user.home}/.{appName}）→ {nexusaiHome}/debug/{sessionId}.txt，不再写 ~/.claude/debug。
         */
        public static String getDebugLogPath() {
            String debugFile = getDebugFilePath();
            if (debugFile != null) {
                return debugFile;
            }
            String logsDir = System.getenv("CLAUDE_CODE_DEBUG_LOGS_DIR");
            if (logsDir != null && !logsDir.isBlank()) {
                return logsDir;
            }
            String sessionId = RequestContext.sessionId();
            String file = (sessionId == null || sessionId.isBlank()) ? "unknown" : sessionId;
            return Paths.get(NexusaiPaths.getAppConfigHomeDir(), "debug", file + ".txt").toString();
        }

        /** 真实 stat · CC original: {@code stat(debugLogPath)}（debug.ts:35）。 */
        public static FileStat stat(String path) throws java.io.IOException {
            return new FileStat(Files.size(Paths.get(path)));
        }

        /** 真实 tail 读 · CC original: {@code fd.read({buffer,position})}（debug.ts:38-48），UTF-8 解码。 */
        public static String readTail(String path, long offset, long size) throws java.io.IOException {
            if (size <= 0) {
                return "";
            }
            try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
                raf.seek(offset);
                byte[] buf = new byte[(int) size];
                int n = raf.read(buf);
                if (n <= 0) {
                    return "";
                }
                return new String(buf, 0, n, StandardCharsets.UTF_8);
            }
        }

        /** CC original: {@code formatFileSize}（utils/format.ts:9-24）— 1.5KB/1.5MB/1.5GB，<1KB 原样 bytes。 */
        public static String formatFileSize(long bytes) {
            double kb = bytes / 1024.0;
            if (kb < 1) {
                return bytes + " bytes";
            }
            if (kb < 1024) {
                return stripTrailingZero(String.format(Locale.ROOT, "%.1f", kb)) + "KB";
            }
            double mb = kb / 1024;
            if (mb < 1024) {
                return stripTrailingZero(String.format(Locale.ROOT, "%.1f", mb)) + "MB";
            }
            double gb = mb / 1024;
            return stripTrailingZero(String.format(Locale.ROOT, "%.1f", gb)) + "GB";
        }

        /** CC {@code .toFixed(1).replace(/\.0$/, '')}（format.ts:13/16/19/22）等价。 */
        private static String stripTrailingZero(String s) {
            return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
        }

        /**
         * 真实 settings 文件路径 · CC original: {@code getSettingsFilePathForSource}（settings.ts:274-296）。
         * <ul>
         *   <li>userSettings → {~/.{appName}}/settings.json（NexusaiPaths.getAppConfigHomeDir() ·
         *       settings.ts:279-282 默认非 cowork；<b>[T2 · 决策 D2]</b> 改读 nexusai 自有 settings，
         *       不再读 claude {configHome}/settings.json）</li>
         *   <li>projectSettings → {cwd}/.nexusai/settings.json（settings.ts:284-287 + :301-303 ·
         *       <b>[T2 · 决策 D2/D6]</b> 项目级改读 nexusai 目录 .nexusai，.claude settings.json 一律不读）</li>
         *   <li>localSettings → {cwd}/.nexusai/settings.local.json（settings.ts:284-287 + :305-306 ·
         *       <b>[T2 · 决策 D2/D6]</b> 项目级改读 nexusai 目录 .nexusai，.claude settings.local.json 一律不读）</li>
         * </ul>
         */
        public static String settingsPathFor(String source) {
            return switch (source) {
                case "userSettings" -> Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json").toString();
                // cwd-align-ext：project/local settings 基 = 会话 originalCwd（CC settings.ts:246
                //   case projectSettings/localSettings → resolve(getOriginalCwd())）；无 sessionId 回落
                //   user.dir（方案 1，零行为变化）。R3-5（决策 D2/D6）目录 .claude → .nexusai。
                case "projectSettings" -> Paths.get(settingsBaseCwd(), NexusaiPaths.getProjectDirName(), "settings.json").toString();
                case "localSettings" -> Paths.get(settingsBaseCwd(), NexusaiPaths.getProjectDirName(), "settings.local.json").toString();
                default -> null;
            };
        }

        /**
         * project/local settings 文件路径基 · 对齐 CC getOriginalCwd()（settings.ts:246）。
         *
         * <p>静态方法经 RequestContext 取会话 originalCwd；无 sessionId 回落 user.dir（零行为变化）。
         */
        private static String settingsBaseCwd() {
            String cwd = CwdResolution.getOriginalCwdLayer(RequestContext.sessionId());
            return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
        }

        /** CC original: isEnvTruthy（utils/envUtils.ts:32）— 值 ∈ {1,true,yes,on} 为 true。 */
        private static boolean isEnvTruthy(String envVar) {
            if (envVar == null) {
                return false;
            }
            String normalized = envVar.trim().toLowerCase(Locale.ROOT);
            return normalized.equals("1") || normalized.equals("true")
                || normalized.equals("yes") || normalized.equals("on");
        }
    }
}
