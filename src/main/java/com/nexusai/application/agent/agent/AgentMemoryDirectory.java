package com.nexusai.application.agent.agent;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryPromptBuilder;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.NexusaiPaths;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent memory directory resolution · 对齐 CC tools/AgentTool/agentMemory.ts.
 *
 * <p>L1 语义: 解析 agent memory 目录路径 (3 scopes: user/project/local) +
 *            安全检查 isAgentMemoryPath + 加载 memory prompt.
 *            - 'user': ~/.{appName}/agent-memory/<agentType>/（决策 D1：记忆基址经
 *              {@link AutoMemPaths#getMemoryBaseDir()} = CLAUDE_CODE_REMOTE_MEMORY_DIR ??
 *              NexusaiPaths home，不再落 ~/.claude）
 *            - 'project': <cwd>/.nexusai/agent-memory/<agentType>/（仓库内，决策 D6 项目写迁移）
 *            - 'local': <cwd>/.nexusai/agent-memory-local/<agentType>/ 或 remote mount dir（仓库内，决策 D6 项目写迁移）.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 scopes (user/project/local);sanitizeAgentTypeForPath;
 *       isAgentMemoryPath (安全检查);loadAgentMemoryPrompt (含 scopeNote).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — sanitizeAgentType (替换 :) →
 *       根据 scope 拼接路径 → memory prompt 含 scopeNote + displayName + memoryDir.</li>
 *   <li><b>A3</b>: 状态: USER / PROJECT / LOCAL scope;CWD 或 CLAUDE_CODE_REMOTE_MEMORY_DIR 决定 local base.</li>
 *   <li><b>A4</b>: ':' 替换为 '-' (Windows invalid char);
 *       CLAUDE_CODE_REMOTE_MEMORY_DIR 设置 → local 用 mount;
 *       isAgentMemoryPath 用 normalize 防 path traversal.</li>
 *   <li><b>A5</b>: 真实场景 — agent type "plugin:my-agent" → "plugin-my-agent";
 *       load memory for user scope → 路径 ~/.{appName}/agent-memory/plugin-my-agent/MEMORY.md.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `path.join` → Java `Path.resolve`;
 *                    TS `process.env.CLAUDE_CODE_REMOTE_MEMORY_DIR` → 注入式 Supplier;
 *                    TS `path.normalize` → Java `Path.normalize`;
 *                    TS `sanitizePath` → 注入式 Function;
 *                    TS `void ensureMemoryDirExists` → fire-and-forget Consumer.
 *
 * <p>[IMP-M-P2-2] INV-12 修复 + 真实 prompt:
 * <ul>
 *   <li><b>isAgentMemoryPath 尾分隔符</b>（agentMemory.ts:74/80/97）——各 scope 基址
 *       {@code join(...) + sep} 后再 {@code startsWith}，缺失尾分隔符会让
 *       {@code agent-memory-evil} 前缀攻击路径误判为 memory 目录（P0 安全缺陷）。</li>
 *   <li><b>loadAgentMemoryPrompt 真实 prompt</b>（agentMemory.ts:138-177）——JSON 桩（DEL-M-31）
 *       改 {@link MemoryPromptBuilder#buildMemoryPrompt} 真实 CC prompt
 *       （displayName='Persistent Agent Memory' + scopeNote + cowork extra guidelines）。
 *       [OPD-CM5-F-25] 门控移调用方（严格对齐 CC，纯构建无内部门控）。</li>
 * </ul>
 */
public final class AgentMemoryDirectory {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryDirectory.class);

    /** AgentMemoryScope enum (CC type alias, agentMemory.ts:13). */
    public enum AgentMemoryScope { USER, PROJECT, LOCAL }

    private final Supplier<String> cwdSupplier;
    private final Supplier<java.nio.file.Path> memoryBaseSupplier;
    private final Supplier<String> remoteMemoryDirSupplier;
    private final Supplier<java.nio.file.Path> projectRootSupplier;
    private final java.util.function.Function<String, String> sanitizePathFn;
    private final java.util.function.Consumer<String> ensureDirConsumer;
    private final Supplier<String> coworkExtraGuidelinesSupplier;
    /**
     * CC original: isAutoMemoryEnabled（memdir/paths.ts:30-56）· 保留为构造器契约注入
     * （fromAutoMemPaths / MemoryFileDetection 装配面）；[OPD-CM5-F-25] 门控已移调用方，
     * 本字段不再被 loadAgentMemoryPrompt 查询。
     */
    private final BooleanSupplier autoMemoryEnabled;
    /** CC original: buildMemoryPrompt（memdir/memdir.ts:272-316）· agent memory prompt 构建器。 */
    private final MemoryPromptBuilder promptBuilder;

    /**
     * 全参构造器 · 对齐 CC agentMemory.ts 依赖 isAutoMemoryEnabled + memdir buildMemoryPrompt。
     *
     * @param cwdSupplier                CC getCwd()
     * @param memoryBaseSupplier         CC getMemoryBaseDir()（CLAUDE_CODE_REMOTE_MEMORY_DIR ?? config home；
     *                                   生产默认 config home = NexusaiPaths ~/.{appName}，决策 D1）
     * @param remoteMemoryDirSupplier    CC process.env.CLAUDE_CODE_REMOTE_MEMORY_DIR
     * @param projectRootSupplier        CC getProjectRoot()
     * @param sanitizePathFn             CC sanitizePath（sessionStoragePortable.ts:311-319）
     * @param ensureDirConsumer          CC void ensureMemoryDirExists（memdir.ts:129-147，fire-and-forget）
     * @param coworkExtraGuidelinesSupplier CC process.env.CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES（agentMemory.ts:167）
     * @param autoMemoryEnabled          CC isAutoMemoryEnabled（memdir/paths.ts:30-56）
     * @param promptBuilder              CC memdir buildMemoryPrompt 构建器
     */
    public AgentMemoryDirectory(Supplier<String> cwdSupplier,
                                   Supplier<java.nio.file.Path> memoryBaseSupplier,
                                   Supplier<String> remoteMemoryDirSupplier,
                                   Supplier<java.nio.file.Path> projectRootSupplier,
                                   java.util.function.Function<String, String> sanitizePathFn,
                                   java.util.function.Consumer<String> ensureDirConsumer,
                                   Supplier<String> coworkExtraGuidelinesSupplier,
                                   BooleanSupplier autoMemoryEnabled,
                                   MemoryPromptBuilder promptBuilder) {
        this.cwdSupplier = Objects.requireNonNull(cwdSupplier);
        this.memoryBaseSupplier = Objects.requireNonNull(memoryBaseSupplier);
        this.remoteMemoryDirSupplier = remoteMemoryDirSupplier;
        this.projectRootSupplier = projectRootSupplier;
        this.sanitizePathFn = sanitizePathFn;
        this.ensureDirConsumer = ensureDirConsumer;
        this.coworkExtraGuidelinesSupplier = coworkExtraGuidelinesSupplier;
        this.autoMemoryEnabled = Objects.requireNonNull(autoMemoryEnabled);
        this.promptBuilder = Objects.requireNonNull(promptBuilder);
    }

    /**
     * 生产默认共享单例（[IMP-F2-4 · OPD-CM5-F-21] 统一 Bean · 同实例）。
     *
     * <p>本方法返回 {@link DefaultHolder#INSTANCE} 单例 —— ToolRegistrationConfig {@code agentMemoryDirectory()}
     * @Bean、SubagentExecutor/SubagentTool 装配、loadAgentsDir 快照等全部生产注入点统一引用同一实例，
     * 关闭 ⊕-4（CM-F4）「@Bean 与生产注入实例分离」的接线不一致（DC-V5-10，选"统一"非"删除"）。
     * CC 端 agentMemory.ts 为模块级纯函数（无实例状态），Java 侧以"共享单例 + 惰性 supplier"等价建模。
     *
     * <p>ODF-A1：cwd/projectRoot 从 {@link AutoMemPaths#currentSessionProjectRoot()} 惰性读取
     * （per-session 注入 holder，绝不读 JVM 进程工作目录）——同一 JVM 不同 cwd 会话
     * 解析出各自独立的 agent-memory 目录。单例共享安全：实例不可变（final supplier 组合），
     * 会话相关值全部经 ThreadLocal/env 惰性读取（构造期不读），{@link #withEffectiveCwd} 返回
     * 派生副本不污染共享实例。
     *
     * @return 生产默认共享单例（@Bean 同实例）
     */
    public static AgentMemoryDirectory productionDefault() {
        return DefaultHolder.INSTANCE;
    }

    /**
     * F-21 单例构建 · 生产默认（env + 用户配置 home + 门控 BundledSkillEnabledGates）。
     * coralFern（searching-past 段）经 {@code MemoryPromptBuilder#setProductionCoralFern}
     * 静态兜底接线 —— 本单例构造期无 Spring 依赖，productionDefault() 链读静态 holder
     * （[IMP-C-6 · OPD-CM5-C-10] C-6 单例缺口：agent-memory 变体 searching-past 段接
     * FeatureFlags.coralFern()，装配点 ToolRegistrationConfig agentMemoryDirectory() @Bean）。
     */
    private static AgentMemoryDirectory buildProductionDefault() {
        AutoMemPaths autoMemPaths = AutoMemPaths.defaultInstance();
        MemoryPromptBuilder promptBuilder = MemoryPromptBuilder.productionDefault();
        return new AgentMemoryDirectory(
            AutoMemPaths::currentSessionProjectRoot,
            () -> java.nio.file.Paths.get(autoMemPaths.getMemoryBaseDir()),
            () -> System.getenv(AutoMemPaths.REMOTE_MEMORY_DIR_ENV),
            () -> java.nio.file.Paths.get(AutoMemPaths.currentSessionProjectRoot()),
            AutoMemPaths::sanitizePath,
            promptBuilder::ensureMemoryDirExists,
            () -> System.getenv(MemoryPromptBuilder.COWORK_EXTRA_GUIDELINES_ENV),
            BundledSkillEnabledGates::isAutoMemoryEnabled,
            promptBuilder);
    }

    /** 生产默认单例持有者（initialization-on-demand holder，线程安全惰性初始化）。 */
    private static final class DefaultHolder {
        static final AgentMemoryDirectory INSTANCE = buildProductionDefault();
    }

    /**
     * [IMP-D F4/M-08] 返回 cwdSupplier 被有效工作目录覆盖的新实例 · CC
     * {@code runWithCwdOverride}（cwd.ts:12-14）等价。
     *
     * <p><b>WHY</b>（T5-D5）：CC agent-memory 的 project/local scope 根 = {@code getCwd()}
     * （agentMemory.ts:43/59），worktree 隔离子代理运行时 getCwd() = worktree 路径（CC
     * AgentTool.tsx:640-641 {@code runWithCwdOverride(cwdOverridePath, fn)} 包住整个 runAgent）。
     * Java 端 project scope 根原绑 {@link AutoMemPaths#currentSessionProjectRoot()} —— worktree
     * 隔离子代理场景错位为 projectRoot。本方法以 worktree 路径覆盖 cwdSupplier（对齐 CC getCwd
     * 语义）；非 worktree 场景保持 projectRoot 绑定（T5 C3：非 worktree 的 effectiveCwd=user.dir
     * 不是 projectRoot 替身，不可作覆盖值）。
     *
     * <p>不可变：返回新实例（仅替换 cwdSupplier），原实例与共享 @Bean 不受影响（无跨子代理
     * 污染）。consumer 复制引用，多线程安全。
     *
     * @param effectiveCwd 有效工作目录（worktree 路径 · 绝对）；null → 回落原 cwdSupplier
     * @return cwdSupplier 固定的新实例
     */
    public AgentMemoryDirectory withEffectiveCwd(String effectiveCwd) {
        if (effectiveCwd == null || effectiveCwd.isBlank()) {
            return this;
        }
        return new AgentMemoryDirectory(
            () -> effectiveCwd,
            memoryBaseSupplier,
            remoteMemoryDirSupplier,
            projectRootSupplier,
            sanitizePathFn,
            ensureDirConsumer,
            coworkExtraGuidelinesSupplier,
            autoMemoryEnabled,
            promptBuilder);
    }

    /**
     * 从既有 {@link AutoMemPaths} 构建（权限层/检测层复用入口）· 仅作纯路径判定
     * {@link #isAgentMemoryPath}，不触发 mkdir 副作用（ensureDirConsumer 为 no-op）。
     *
     * <p>供 {@link com.nexusai.application.agent.memory.MemoryFileDetection}（isAgentMemFile 委托）
     * 与 Write/Read 权限 carve-out 使用 —— 这些消费者已有 AutoMemPaths 实例，无需重复解析 env。
     *
     * @param autoMemPaths    CC getMemoryBaseDir/getCwd/sanitizePath 供应（AutoMemPaths）
     * @param autoMemoryEnabled 注入式门控（isAgentMemoryPath 本身无门控；仅对齐构造器契约）
     */
    public static AgentMemoryDirectory fromAutoMemPaths(AutoMemPaths autoMemPaths,
                                                        BooleanSupplier autoMemoryEnabled) {
        return new AgentMemoryDirectory(
            AutoMemPaths::currentSessionProjectRoot,
            () -> java.nio.file.Paths.get(autoMemPaths.getMemoryBaseDir()),
            () -> System.getenv(AutoMemPaths.REMOTE_MEMORY_DIR_ENV),
            () -> java.nio.file.Paths.get(AutoMemPaths.currentSessionProjectRoot()),
            AutoMemPaths::sanitizePath,
            path -> { /* 检测纯谓词：不 mkdir */ },
            () -> System.getenv(MemoryPromptBuilder.COWORK_EXTRA_GUIDELINES_ENV),
            autoMemoryEnabled,
            MemoryPromptBuilder.productionDefault());
    }

    /**
     * String scope → {@link AgentMemoryScope} · CC original: VALID_MEMORY_SCOPES
     * （loadAgentsDir.ts:131，'user' | 'project' | 'local'）。非法值 → null（对齐 CC undefined）。
     */
    public static AgentMemoryScope fromName(String s) {
        if (s == null) {
            return null;
        }
        switch (s) {
            case "user": return AgentMemoryScope.USER;
            case "project": return AgentMemoryScope.PROJECT;
            case "local": return AgentMemoryScope.LOCAL;
            default: return null;
        }
    }

    /** CC sanitizeAgentTypeForPath（agentMemory.ts:20-22）. */
    public static String sanitizeAgentTypeForPath(String agentType) {
        return agentType.replace(":", "-");
    }

    /** CC getLocalAgentMemoryDir — local scope (含 remote mount 处理, agentMemory.ts:29-44). */
    public java.nio.file.Path getLocalAgentMemoryDir(String dirName) {
        String remoteDir = remoteMemoryDirSupplier != null ? remoteMemoryDirSupplier.get() : null;
        if (remoteDir != null && !remoteDir.isEmpty()) {
            // [FIX-AM REQ-M-19] remote mount 项目名对齐 CC agentMemory.ts:35-37
            //   findCanonicalGitRoot(getProjectRoot()) ?? getProjectRoot()：
            //   用 canonical git root（worktree → 主仓库根）让同一仓库所有 worktree 共享
            //   agent-memory-local 目录（对齐 AutoMemPaths.getAutoMemBase 既有语义）。
            String projectRoot = projectRootSupplier.get() != null
                ? projectRootSupplier.get().toString() : cwdSupplier.get();
            String canonical = AutoMemPaths.findCanonicalGitRoot(projectRoot);
            String projectPath = sanitizePathFn.apply(canonical != null ? canonical : projectRoot);
            return java.nio.file.Paths.get(remoteDir, "projects", projectPath,
                "agent-memory-local", dirName);
        }
        return java.nio.file.Paths.get(cwdSupplier.get(), NexusaiPaths.getProjectDirName(), "agent-memory-local", dirName);
    }

    /** CC getAgentMemoryDir（agentMemory.ts:52-65）. */
    public java.nio.file.Path getAgentMemoryDir(String agentType, AgentMemoryScope scope) {
        String dirName = sanitizeAgentTypeForPath(agentType);
        switch (scope) {
            case PROJECT:
                return java.nio.file.Paths.get(cwdSupplier.get(), NexusaiPaths.getProjectDirName(), "agent-memory", dirName);
            case LOCAL:
                return getLocalAgentMemoryDir(dirName);
            case USER:
                return memoryBaseSupplier.get().resolve("agent-memory").resolve(dirName);
            default:
                throw new IllegalArgumentException("Unknown scope: " + scope);
        }
    }

    /**
     * CC isAgentMemoryPath — 安全检查 (防 path traversal + 前缀攻击)。
     *
     * <p>[IMP-M-P2-2] INV-12 修复：各 scope 基址 {@code toString() + File.separator} 后再
     * {@code startsWith}（agentMemory.ts:74/80/97 的 {@code join(...) + sep}）。缺尾分隔符会让
     * {@code agent-memory-evil} 这类前缀攻击路径误判为 memory 目录（P0 安全缺陷，可放大权限放错）。
     *
     * @param absolutePath 待检查的绝对路径
     * @return true = 位于任一 agent memory 目录内
     */
    public boolean isAgentMemoryPath(String absolutePath) {
        String normalized = java.nio.file.Paths.get(absolutePath).normalize().toString();
        String sep = java.io.File.separator;
        java.nio.file.Path memoryBase = memoryBaseSupplier.get();

        // User scope: join(memoryBase, 'agent-memory') + sep（agentMemory.ts:74）
        if (normalized.startsWith(memoryBase.resolve("agent-memory").toString() + sep)) {
            return true;
        }
        // Project scope: join(cwd, '.nexusai', 'agent-memory') + sep（agentMemory.ts:80 · 决策 D6 项目写迁移）
        if (normalized.startsWith(
            java.nio.file.Paths.get(cwdSupplier.get(), NexusaiPaths.getProjectDirName(), "agent-memory").toString() + sep)) {
            return true;
        }
        // Local scope（agentMemory.ts:86-101）
        String remoteDir = remoteMemoryDirSupplier != null ? remoteMemoryDirSupplier.get() : null;
        if (remoteDir != null && !remoteDir.isEmpty()) {
            // 持久化到 mount：join(remote, 'projects') + sep（agentMemory.ts:89-92）
            if (normalized.contains(sep + "agent-memory-local" + sep)
                && normalized.startsWith(
                    java.nio.file.Paths.get(remoteDir, "projects").toString() + sep)) {
                return true;
            }
        } else if (normalized.startsWith(
            java.nio.file.Paths.get(cwdSupplier.get(), NexusaiPaths.getProjectDirName(), "agent-memory-local").toString() + sep)) {
            return true;
        }
        return false;
    }

    /**
     * CC getAgentMemoryEntrypoint（agentMemory.ts:106-114）· 返回 agent memory 入口文件路径
     * {@code join(getAgentMemoryDir(agentType, scope), 'MEMORY.md')}。
     *
     * <p>[IMP-F2-5 · OPD-CM5-F-24] 补函数对齐 CC 导出面（REQ-CM-F4-06：Java 原缺失 N/A）。
     * CC 导出但 in-repo 无消费方；前端有导出会话记忆需求，需此入口（登记 待前端对接.md §26）。
     * 纯路径解析，无 mkdir 副作用（区别于 loadAgentMemoryPrompt 的 ensureMemoryDirExists）。
     *
     * @param agentType CC original: agentType（目录名）
     * @param scope     CC original: scope（'user' | 'project' | 'local'）
     * @return agent memory 入口文件路径（{@code <memoryDir>/MEMORY.md}）
     */
    public java.nio.file.Path getAgentMemoryEntrypoint(String agentType, AgentMemoryScope scope) {
        return getAgentMemoryDir(agentType, scope).resolve("MEMORY.md");
    }

    /**
     * CC loadAgentMemoryPrompt（agentMemory.ts:138-177）· 真实 CC prompt（删 JSON 桩，DEL-M-31）。
     *
     * <p>[OPD-CM5-F-25] 门控移调用方（严格对齐 CC）：CC agentMemory.ts:138-177 <b>无内部门控</b>——
     * 门控在调用方（loadAgentsDir.ts:481-488/726-732、loadPluginAgents.ts:207-212
     * {@code if (isAutoMemoryEnabled() && memory)}）。本方法为纯构建：不查询 {@code isAutoMemoryEnabled}，
     * fire-and-forget mkdir（agentMemory.ts:165）→ {@code buildMemoryPrompt('Persistent Agent Memory',
     * memoryDir, [scopeNote(, coworkExtraGuidelines)])}（agentMemory.ts:169-176）。禁用时由调用方
     * （SubagentExecutor#agentMemoryPrompt / SubagentTool#getEffectiveSystemPrompt）跳过本调用，
     * 故禁用场景无 mkdir 副作用。
     *
     * @param agentType CC original: agentType（目录名）
     * @param scope     CC original: scope（'user' | 'project' | 'local'）
     * @return memory prompt 文本（纯构建，无门控）
     */
    public String loadAgentMemoryPrompt(String agentType, AgentMemoryScope scope) {
        String scopeNote;
        switch (scope) {
            case USER:
                scopeNote = "- Since this memory is user-scope, keep learnings general since they apply across all projects";
                break;
            case PROJECT:
                scopeNote = "- Since this memory is project-scope and shared with your team via version control, tailor your memories to this project";
                break;
            case LOCAL:
                scopeNote = "- Since this memory is local-scope (not checked into version control), tailor your memories to this project and machine";
                break;
            default:
                scopeNote = "";
        }

        java.nio.file.Path memoryDir = getAgentMemoryDir(agentType, scope);

        // Fire-and-forget mkdir（agentMemory.ts:165 void ensureMemoryDirExists）
        if (ensureDirConsumer != null) {
            try {
                ensureDirConsumer.accept(memoryDir.toString());
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[AgentMemoryDirectory] loadAgentMemoryPrompt mkdir 失败: {} {}", memoryDir, e.getMessage());
                }
            }
        }

        // cowork extra guidelines（agentMemory.ts:167-168 process.env.CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES）
        String cowork = coworkExtraGuidelinesSupplier != null
            ? coworkExtraGuidelinesSupplier.get() : null;
        java.util.List<String> extraGuidelines =
            (cowork != null && !cowork.trim().isEmpty())
                ? java.util.List.of(scopeNote, cowork)
                : java.util.List.of(scopeNote);

        String prompt = promptBuilder.buildMemoryPrompt(
            "Persistent Agent Memory", memoryDir.toString(), extraGuidelines);
        if (log.isDebugEnabled()) {
            log.debug("[AgentMemoryDirectory] loadAgentMemoryPrompt 构建 agent memory prompt: agentType={} scope={} dir={} 长度={}",
                agentType, scope, memoryDir, prompt.length());
        }
        return prompt;
    }
}
