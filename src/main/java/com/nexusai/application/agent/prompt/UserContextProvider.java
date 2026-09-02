package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.common.RequestContext;

/**
 * User 通道上下文提供者 · 对齐 CC {@code getUserContext} 的 claudeMd 生产侧
 * （CC original: {@code getClaudeMds} (Open-ClaudeCode/src/utils/claudemd.ts:1153-1195)）。
 *
 * <p><b>claudeMd 本 session 范围（concern #4 决议）</b>：CC getUserContext.claudeMd 来自
 * {@code getClaudeMds(getMemoryFiles())}（多文件 + 类型描述 + MEMORY_INSTRUCTION_PROMPT 头），
 * 与 memory 模块强耦合。注入 ClaudemdEngine 时走完整链
 * （{@code getClaudeMds(filterInjectedMemoryFiles(getMemoryFiles()))}，CC context.ts:170-172）；
 * 未注入 → 回退单项目根 {@code CLAUDE.md}（trim，等价主文件）。
 *
 * <p><b>currentDate 会话冻结（I-10）</b>：日期不实时取，而用 {@code AgentState.sessionStartDate}
 * 冻结值 —— 跨午夜不陈旧，prompt cache-key 稳定（CC common.ts:17-24 注释语义）。
 *
 * <p><b>prependUserContext（FIX-CL 删除本类第三套并行实现）</b>：CC api.ts:449-474 的前置渲染
 * 生产唯一实现为 {@code AgentLoopContext.prependUserContext}（LlmAgentLoop:2757 调用，
 * 与 CC 逐字等价），本类只保留 claudeMd/currentDate 生成侧。
 */
public class UserContextProvider {

    private static final Logger log = LoggerFactory.getLogger(UserContextProvider.class);

    /** CC original: CLAUDE_CODE_DISABLE_CLAUDE_MDS（context.ts:166，硬开关恒关） */
    private static final String DISABLE_CLAUDE_MDS_ENV = "CLAUDE_CODE_DISABLE_CLAUDE_MDS";

    /**
     * 可注入环境变量查询 · 测试注入假实现避免改真实进程环境（concern #8 假 runner 同款先例）。
     */
    @FunctionalInterface
    public interface Environment {
        String get(String key);
    }

    /** 项目根目录（默认会话 originalCwd 层 · 测试可注入临时目录） */
    private final Path projectRoot;

    private final Environment environment;

    /**
     * bare 模式判定缝（可注入）· 默认 {@link MemoryBareModeConfig#isBareMode()}
     * （ODF-A3 统一判定：nexusai.memory.bare-mode 配置 → env CLAUDE_CODE_SIMPLE → false）。
     *
     * <p>SP-07 △-2：claudeMd 门控接入 bare 模式（CC context.ts:165-167
     * {@code isBareMode() && getAdditionalDirectoriesForClaudeMd().length === 0}；
     * Java 无 --add-dir 通道 → addDir 恒空 → isBareMode() 即抑制）。
     * SkillsLoader.setBareModeSupplier 同款先例（测试注入，Java 无法进程内改 env）。
     */
    private java.util.function.Supplier<Boolean> bareModeSupplier = MemoryBareModeConfig::isBareMode;

    /**
     * 测试缝：覆盖 bare 判定。null 忽略（保持默认）。
     *
     * @param supplier bare 判定（null → 忽略）
     */
    public void setBareModeSupplier(java.util.function.Supplier<Boolean> supplier) {
        if (supplier != null) {
            this.bareModeSupplier = supplier;
        }
    }

    /**
     * 可选 claudemd 引擎（memory 模块 IMP-M-P2-4，完整 getClaudeMds 链）。
     *
     * <p>非 null → {@link #claudeMd()} 走完整链（CC context.ts:170-172
     * {@code getClaudeMds(getMemoryFiles())}）；null → 回退单项目根 CLAUDE.md 子集。
     * 避免 LlmAgentLoop 层重复 prepend（CC api.ts:449-474 单注入）。
     */
    private final com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine;

    /**
     * @param projectRoot 项目根目录（默认 {@code Path.of(CwdResolution.getOriginalCwdLayer(
     *      RequestContext.sessionId()))} · 对齐 CC {@code getOriginalCwd}（claudemd.ts:851），
     *      无会话回落 user.dir；测试注入临时目录）
     */
    public UserContextProvider(Path projectRoot) {
        this(projectRoot, System::getenv, null);
    }

    /**
     * 测试注入构造：可替换环境查询。
     *
     * @param projectRoot 项目根目录
     * @param environment 环境变量查询（默认 {@code System::getenv}；测试注入假实现）
     */
    public UserContextProvider(Path projectRoot, Environment environment) {
        this(projectRoot, environment, null);
    }

    /** 便捷构造：默认会话 originalCwd 层（无会话回落 user.dir）。 */
    public UserContextProvider() {
        this(Path.of(CwdResolution.getOriginalCwdLayer(RequestContext.sessionId())), System::getenv, null);
    }

    /**
     * [merge worktree-memory-align] 注入 claudemd 引擎构造 · 完整 getClaudeMds 链。
     *
     * @param claudemdEngine claudemd 引擎（可 null → 回退单文件子集）
     */
    public UserContextProvider(com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine) {
        this(Path.of(CwdResolution.getOriginalCwdLayer(RequestContext.sessionId())), System::getenv, claudemdEngine);
    }

    /**
     * 全参数构造。
     *
     * @param projectRoot    项目根目录
     * @param environment    环境变量查询
     * @param claudemdEngine claudemd 引擎（可 null）
     */
    public UserContextProvider(Path projectRoot, Environment environment,
                               com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine) {
        this.projectRoot = projectRoot != null
            ? projectRoot
            : Path.of(CwdResolution.getOriginalCwdLayer(RequestContext.sessionId()));
        this.environment = environment != null ? environment : System::getenv;
        this.claudemdEngine = claudemdEngine;
    }

    /**
     * 读取项目根 CLAUDE.md（trim）· 对齐 CC {@code getClaudeMds} 单主文件子集。
     *
     * <p>门控（CC context.ts:165-172 语义）：
     * <ol>
     *   <li>{@code CLAUDE_CODE_DISABLE_CLAUDE_MDS} truthy → null（硬关，恒不注入）；</li>
     *   <li><b>bare 模式（SP-07 △-2）</b>：{@code isBareMode() && getAdditionalDirectoriesForClaudeMd()
     *       .length === 0} → null；Java 无 --add-dir 通道 → addDir 恒空 → isBareMode() 即抑制
     *       （CC context.ts:165-167；判定经 {@link #bareModeSupplier}，默认
     *       {@code MemoryBareModeConfig.isBareMode()}）——注释漂移已关闭（旧注释称
     *       「Java 无 bare 等价物…不实现」已过时）；</li>
     *   <li>文件缺失/读取异常 → null。</li>
     * </ol>
     *
     * @return CLAUDE.md trim 后内容；被禁用/缺失/异常时 {@code null}
     */
    public String claudeMd() {
        if (Boolean.TRUE.equals(bareModeSupplier.get())) {
            if (log.isDebugEnabled()) {
                log.debug("[UserContextProvider] bare 模式（isBareMode），claudeMd 抑制（对齐 CC context.ts:165-167，SP-07 △-2）");
            }
            return null;
        }
        if (isEnvTruthy(environment.get(DISABLE_CLAUDE_MDS_ENV))) {
            if (log.isDebugEnabled()) {
                log.debug("[UserContextProvider] CLAUDE_CODE_DISABLE_CLAUDE_MDS truthy，claudeMd 禁用（对齐 CC context.ts:166）");
            }
            return null;
        }
        // [merge worktree-memory-align] 注入 claudemd 引擎 → 完整 getClaudeMds 链
        // （CC context.ts:170-172 getClaudeMds(getMemoryFiles()) + claudemd.ts:1142-1151
        //   filterInjectedMemoryFiles —— tengu_moth_copse 开启时 AutoMem/TeamMem 走预取不注入）；
        // 引擎异常 → null 不阻断组装。
        if (claudemdEngine != null) {
            try {
                java.util.List<com.nexusai.application.agent.context.MemoryFileInfo> files =
                    claudemdEngine.filterInjectedMemoryFiles(claudemdEngine.getMemoryFiles(false));
                String full = claudemdEngine.getClaudeMds(files, null);
                if (full == null || full.isEmpty()) {
                    return null;
                }
                if (log.isDebugEnabled()) {
                    log.debug("[UserContextProvider] claudeMd 经 ClaudemdEngine 完整链生成: {} chars", full.length());
                }
                return full;
            } catch (Exception e) {
                log.warn("[UserContextProvider] ClaudemdEngine 生成 claudeMd 失败，按 null 处理（对齐 CC 不阻断组装）: {}",
                    e.getMessage());
                return null;
            }
        }
        Path claudeMd = projectRoot.resolve("CLAUDE.md");
        if (!Files.isRegularFile(claudeMd)) {
            if (log.isDebugEnabled()) {
                log.debug("[UserContextProvider] 未找到 {}，claudeMd 为 null", claudeMd);
            }
            return null;
        }
        try {
            String content = Files.readString(claudeMd).trim();
            if (content.isEmpty()) {
                return null;
            }
            if (log.isDebugEnabled()) {
                log.debug("[UserContextProvider] 已加载 CLAUDE.md 自 {} ({} 字符)", claudeMd, content.length());
            }
            return content;
        } catch (Exception e) {
            log.warn("[UserContextProvider] 读取 CLAUDE.md 失败，claudeMd 为 null（对齐 CC context.ts 不阻断组装）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 渲染 currentDate · 对齐 CC {@code `Today's date is ${getLocalISODate()}.`}
     * （CC original: context.ts:186）。
     *
     * <p>入参为会话冻结日期（{@code AgentState.sessionStartDate}，构造时取本地日），
     * 跨午夜不陈旧（I-10）。
     *
     * @param sessionStartDate 会话冻结日期（{@code "YYYY-MM-DD"}，CC common.ts:4-15 getLocalISODate）
     * @return {@code "Today's date is <sessionStartDate>."}
     */
    public String currentDate(String sessionStartDate) {
        return "Today's date is " + sessionStartDate + ".";
    }

    /**
     * CC original: {@code isEnvTruthy}（envUtils.ts:32-37）——truthy 集合为
     * 1/true/yes/on（大小写不敏感、trim 后）。
     */
    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }
}
