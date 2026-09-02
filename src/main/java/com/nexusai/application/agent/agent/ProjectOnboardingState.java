package com.nexusai.application.agent.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 项目 onboarding 状态机 · 对齐 CC projectOnboardingState.ts.
 *
 * <p>L1 语义: REPL 首次启动时显示 onboarding 提示.两个 step: workspace (clone 仓库或创建新 app)
 *            和 claudemd (运行 /init 生成 CLAUDE.md).workspace 在 cwd 为空时启用;claudemd 在 cwd 非空时启用.
 *            两者都完成 → onboarding 整体完成.
 *            shouldShowProjectOnboarding 在 seenCount≥4 或 hasCompletedProjectOnboarding=true 或 IS_DEMO=true 时不展示.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: Step 5 字段 (key/text/isComplete/isCompletable/isEnabled);
 *       4 个 mutator/getter (getSteps/isProjectOnboardingComplete/maybeMarkProjectOnboardingComplete/shouldShowProjectOnboarding/incrementProjectOnboardingSeenCount);
 *       2 个固定常量 step.key: "workspace" / "claudemd".</li>
 *   <li><b>A2 Golden Trace</b>: 空目录 → workspace enabled + claudemd disabled;
 *       非空目录 + 有 CLAUDE.md → claudemd enabled + isComplete=true;两者都 complete → maybeMarkProjectOnboardingComplete 写标志.
 *       seenCount=4 → shouldShow=false (不再骚扰).</li>
 *   <li><b>A3</b>: 状态机: 3 个 seenCount 状态 (0-3 显示; 4 隐藏); 2 个 onboarding 完成标志 (hasCompletedProjectOnboarding bool).
 *       纯函数 + 注入式 supplier (cwd/claudeMdExists/isDirEmpty/currentProjectConfig saver).</li>
 *   <li><b>A4</b>: 空目录判断 (isDirEmpty=true) 时 workspace 启用;空 workspace 时 claudemd 禁用 (不可用);
 *       null/blank 处理: 缺失 CLAUDE.md → claudemd.isComplete=false (仍 enabled).</li>
 *   <li><b>A5</b>: 真实场景 — 新 clone 的空仓库 → workspace step 启用;新员工非空目录但无 CLAUDE.md → claudemd 启用 (提示 /init).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS lodash memoize → 不可变 fields (getSteps 内每次构建新 List);
 *                    TS `process.env.IS_DEMO` → 注入式 BooleanSupplier (testable, no static env).
 *                    TS `getCurrentProjectConfig().hasCompletedProjectOnboarding` → 注入式 Supplier&lt;ProjectConfig&gt;.
 */
public final class ProjectOnboardingState {

    private static final Logger log = LoggerFactory.getLogger(ProjectOnboardingState.class);
    private static final int SEEN_COUNT_MAX = 4;

    private final Supplier<Path> cwdSupplier;
    private final BooleanSupplier claudeMdExistsCheck;   // true if CLAUDE.md 在 cwd
    private final BooleanSupplier isDirEmptyCheck;       // true if cwd 为空目录
    private final BooleanSupplier isDemoCheck;           // IS_DEMO env 等价
    private final Supplier<ProjectConfig> configReader;
    private final java.util.function.Consumer<java.util.function.UnaryOperator<ProjectConfig>> configSaver;

    public ProjectOnboardingState(Supplier<Path> cwdSupplier,
                                  BooleanSupplier claudeMdExistsCheck,
                                  BooleanSupplier isDirEmptyCheck,
                                  BooleanSupplier isDemoCheck,
                                  Supplier<ProjectConfig> configReader,
                                  java.util.function.Consumer<java.util.function.UnaryOperator<ProjectConfig>> configSaver) {
        this.cwdSupplier = Objects.requireNonNull(cwdSupplier);
        this.claudeMdExistsCheck = Objects.requireNonNull(claudeMdExistsCheck);
        this.isDirEmptyCheck = Objects.requireNonNull(isDirEmptyCheck);
        this.isDemoCheck = Objects.requireNonNull(isDemoCheck);
        this.configReader = Objects.requireNonNull(configReader);
        this.configSaver = Objects.requireNonNull(configSaver);
    }

    /** CC Step — onboarding 单步状态. */
    public record Step(
        String key,
        String text,
        boolean isComplete,
        boolean isCompletable,
        boolean isEnabled
    ) {}

    /** CC ProjectConfig — 只暴露 onboarding 相关 2 字段 (最小契约). */
    public record ProjectConfig(
        boolean hasCompletedProjectOnboarding,
        int projectOnboardingSeenCount
    ) {
        public static final ProjectConfig DEFAULT = new ProjectConfig(false, 0);
    }

    /** CC getSteps — 返回 2 步 onboarding 列表. */
    public List<Step> getSteps() {
        boolean hasClaudeMd = claudeMdExistsCheck.getAsBoolean();
        boolean empty = isDirEmptyCheck.getAsBoolean();
        List<Step> steps = new ArrayList<>(2);
        steps.add(new Step(
            "workspace",
            "Ask Claude to create a new app or clone a repository",
            false,
            true,
            empty  // 空目录才显示 workspace 提示
        ));
        steps.add(new Step(
            "claudemd",
            "Run /init to create a CLAUDE.md file with instructions for Claude",
            hasClaudeMd,  // 有 CLAUDE.md 即完成
            true,
            !empty  // 非空目录才显示 claudemd 提示
        ));
        return steps;
    }

    /** CC isProjectOnboardingComplete — 所有 enabled+completable step 都 complete 才算完成. */
    public boolean isProjectOnboardingComplete() {
        return getSteps().stream()
            .filter(s -> s.isCompletable() && s.isEnabled())
            .allMatch(Step::isComplete);
    }

    /**
     * CC maybeMarkProjectOnboardingComplete — 短路缓存避免每次 prompt 都查 fs.
     * 短路: 配置里已经标记为完成 → 不再检查.
     */
    public void maybeMarkProjectOnboardingComplete() {
        ProjectConfig current = configReader.get();
        if (current.hasCompletedProjectOnboarding()) {
            return;
        }
        if (isProjectOnboardingComplete()) {
            configSaver.accept(prev -> new ProjectConfig(true, prev.projectOnboardingSeenCount()));
        }
    }

    /**
     * CC shouldShowProjectOnboarding — memoized boolean.
     * 短路: 已完成 OR seenCount≥4 OR IS_DEMO → false.
     * 否则: 若 onboarding 未完成 → true.
     */
    public boolean shouldShowProjectOnboarding() {
        ProjectConfig config = configReader.get();
        // 短路缓存检查在 fs 检查之前 (与 CC 注释一致 — "first render")
        if (config.hasCompletedProjectOnboarding()
            || config.projectOnboardingSeenCount() >= SEEN_COUNT_MAX
            || isDemoCheck.getAsBoolean()) {
            return false;
        }
        return !isProjectOnboardingComplete();
    }

    /** CC incrementProjectOnboardingSeenCount — 增加 seenCount. */
    public void incrementProjectOnboardingSeenCount() {
        configSaver.accept(prev -> new ProjectConfig(
            prev.hasCompletedProjectOnboarding(),
            prev.projectOnboardingSeenCount() + 1
        ));
    }
}
