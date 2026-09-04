package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * [IMP-M-P0-2] MemoryPromptBuilder 行为指令 prompt 对齐 CC memdir.ts + memoryTypes.ts.
 *
 * <p>WHY (规则九): CC 把「行为指令」(四类 taxonomy / 两步保存 / TRUSTING_RECALL / drift caveat /
 * searching-past-context / KAIROS daily-log) 作为 systemPromptSection('memory') 注入
 * (prompts.ts:495)，而非旧 Java 的泛化 "# Memory + MEMORY.md 索引块"。
 * 且 loadMemoryPrompt 三路分发 (KAIROS -> team -> auto -> null)，disabled 时返回 null (INV-3)。
 */
@DisplayName("[IMP-M-P0-2] MemoryPromptBuilder 行为指令 prompt 对齐 CC memdir.ts")
class MemoryPromptBuilderTest {

    /** 注入 overrideSupplier 构造隔离 AutoMemPaths (getAutoMemPath 直接返回 override 目录)。 */
    private static AutoMemPaths paths(Path overrideDir) {
        return new AutoMemPaths(
            () -> System.getProperty("user.dir"),
            () -> null,
            () -> overrideDir.toString(),
            () -> null);
    }

    /** 全参数 builder (生产 gate 全注入式，测试可逐开关 on/off)。 */
    private static MemoryPromptBuilder builder(
        Path overrideDir,
        boolean autoEnabled,
        boolean kairosActive,
        boolean teamEnabled,
        boolean coralFern,
        boolean mothCopse,
        boolean herringClock,
        Supplier<String> coworkEnv,
        Function<String, String> entrypointReader) {
        return new MemoryPromptBuilder(
            paths(overrideDir),
            () -> autoEnabled,
            () -> kairosActive,
            () -> teamEnabled,
            () -> coralFern,
            () -> mothCopse,
            () -> herringClock,
            coworkEnv,
            entrypointReader);
    }

    // ════════════════════════════════════════════════════════════════
    // 1. disabled (isAutoMemoryEnabled=false) -> null · INV-3
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("disabled (isAutoMemoryEnabled=false) -> loadMemoryPrompt 返回 null (memdir.ts:492-506)")
    void disabled_returnsNull(@TempDir Path dir) {
        // WHY: CC loadMemoryPrompt 最后一条分支 —— 任何 memory 系统未启用时返回 null
        //      (memdir.ts:506 return null + tengu_memdir_disabled telemetry)。Java 端
        //      buildPromptContext 必须能感知 null 以跳过 memory section。
        MemoryPromptBuilder b = builder(dir, false, false, false, false, false, false, () -> null, p -> "");

        assertThat(b.loadMemoryPrompt()).isNull();
    }

    // ════════════════════════════════════════════════════════════════
    // 2. auto-only 分支: buildMemoryLines 行为指令逐段文本 (memdir.ts:475-489)
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("auto-only -> buildMemoryLines 行为指令: # auto memory + DIR_EXISTS_GUIDANCE")
    void autoOnly_includesDirectoryGuidance(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        assertThat(prompt).contains("# auto memory");
        // AutoMemPaths.getAutoMemPath() 对 override 也追加尾分隔符（尾分隔符契约）
        assertThat(prompt).contains("You have a persistent, file-based memory system at `" + dir + File.separator + "`");
        // CC memdir.ts:116-117 DIR_EXISTS_GUIDANCE 精确文本
        assertThat(prompt).contains(
            "This directory already exists — write to it directly with the Write tool "
                + "(do not run mkdir or check for its existence).");
        // 反 CC 文本必须消失 (DEL-M-01)
        assertThat(prompt).doesNotContain("ls both directories");
    }

    @Test
    @DisplayName("auto-only -> 四类 taxonomy 段 (memoryTypes.ts:113-178 TYPES_SECTION_INDIVIDUAL)")
    void autoOnly_includesTaxonomy(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        assertThat(prompt)
            .contains("## Types of memory")
            .contains("There are several discrete types of memory that you can store in your memory system:")
            .contains("<name>user</name>")
            .contains("<name>feedback</name>")
            .contains("<name>project</name>")
            .contains("<name>reference</name>")
            .contains("[saves user memory: user is a data scientist, currently focused on observability/logging]")
            .doesNotContain("<scope>");
    }

    @Test
    @DisplayName("auto-only -> 两步保存 (memdir.ts:218-234) + MEMORY_FRONTMATTER_EXAMPLE")
    void autoOnly_includesTwoStepSave(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        assertThat(prompt)
            .contains("## How to save memories")
            .contains("Saving a memory is a two-step process:")
            .contains("**Step 1** — write the memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:")
            .contains("**Step 2** — add a pointer to that file in `MEMORY.md`. `MEMORY.md` is an index, not a memory")
            .contains("type: {{user, feedback, project, reference}}")
            .contains("- Keep the name, description, and type fields in memory files up-to-date with the content");
    }

    @Test
    @DisplayName("auto-only -> WHEN_TO_ACCESS + drift caveat + TRUSTING_RECALL 段")
    void autoOnly_includesAccessAndTrustingRecall(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        assertThat(prompt)
            .contains("## When to access memories")
            .contains("You MUST access memory when the user explicitly asks you to check, recall, or remember.")
            .contains("Memory records can become stale over time. Use memory as context for what was true at a given point in time.")
            .contains("## Before recommending from memory")
            .contains("A memory that names a specific function, file, or flag is a claim that it existed *when the memory was written*.")
            .contains("## Memory and other forms of persistence");
    }

    @Test
    @DisplayName("auto-only -> WHAT_NOT_TO_SAVE 段 + 显式保存门 (memoryTypes.ts:183-195)")
    void autoOnly_includesWhatNotToSave(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        assertThat(prompt)
            .contains("## What NOT to save in memory")
            .contains("These exclusions apply even when the user explicitly asks you to save.");
    }

    // ════════════════════════════════════════════════════════════════
    // 3. KAIROS daily-log 分支 (memdir.ts:432-438 + buildAssistantDailyLogPrompt:327-370)
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("KAIROS on -> daily-log prompt 含路径形状 <autoMemPath>/logs/YYYY/MM/YYYY-MM-DD.md")
    void kairosActive_dailyLogPathShape(@TempDir Path dir) {
        // WHY: CC KAIROS 分支用「路径模式」(非今日字面路径) 描述 daily-log —— prompt 被
        //      systemPromptSection 缓存且不会在日期变更时失效 (memdir.ts:330-335)。模型从
        //      currentDate attachment 推导当日日期。路径形状必须为 <autoMemPath>/logs/YYYY/MM/YYYY-MM-DD.md。
        MemoryPromptBuilder b = builder(dir, true, true, false, false, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        String sep = File.separator;
        assertThat(prompt)
            .contains("# auto memory")
            .contains("You have a persistent, file-based memory system found at: `" + dir + sep + "`")
            .contains("logs" + sep + "YYYY" + sep + "MM" + sep + "YYYY-MM-DD.md")
            .contains("## What to log")
            .contains("append-only")
            .contains("Substitute today's date (from `currentDate` in your context) for `YYYY-MM-DD`");
    }

    @Test
    @DisplayName("KAIROS off -> 落到 auto 分支，不出现 daily-log 文本")
    void kairosOff_fallsThroughToAuto(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        assertThat(prompt).contains("Saving a memory is a two-step process:");
        assertThat(prompt).doesNotContain("## What to log");
        assertThat(prompt).doesNotContain("daily log file");
    }

    @Test
    @DisplayName("KAIROS on 但 auto disabled -> 不进入 daily-log，返回 null")
    void kairosOnButAutoDisabled_returnsNull(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, false, true, false, false, false, false, () -> null, p -> "");

        assertThat(b.loadMemoryPrompt()).isNull();
    }

    // ════════════════════════════════════════════════════════════════
    // 4. buildSearchingPastContextSection (memdir.ts:375-407) tengu_coral_fern 门控
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("tengu_coral_fern on -> 注入 Searching past context 段 (grep 工具形态)")
    void coralFernOn_includesSearchingSection(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, true, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        assertThat(prompt)
            .contains("## Searching past context")
            .contains("When looking for past context:")
            .contains("1. Search topic files in your memory directory:")
            .contains("2. Session transcript logs (last resort — large files, slow):")
            .contains("Use narrow search terms (error messages, file paths, function names) rather than broad keywords.");
    }

    @Test
    @DisplayName("tengu_coral_fern off -> Searching past context 段不出现")
    void coralFernOff_omitsSearchingSection(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        assertThat(prompt).doesNotContain("## Searching past context");
    }

    @Test
    @DisplayName("[A1 读取侧回归] 真实 AutoMemPaths：会话线程 projectRoot → loadMemoryPrompt 解析到该项目的 per-project 记忆目录（不回落 config-home）")
    void autoOnly_realAutoMemPaths_resolvesSessionProjectMemoryDir(@TempDir Path configHome) throws Exception {
        // WHY（规则九 · 用户补测要求）：既有测试全用 overrideSupplier 注入固定目录，未覆盖
        // "会话线程 projectRoot ThreadLocal → AutoMemPaths.getAutoMemPath() 真实解析"这条读取链路。
        // 若 setCurrentProjectRoot 未生效（如误删注入点），loadMemoryPrompt 会回落 config-home 自身
        // （C--configHome slug）而非绑定项目目录 → 模型拿到错误目录的记忆。本测试钉死解析正确性。
        // config home 隔离到 @TempDir（NexusaiPaths override），绝不写真实 ~/.nexusai。
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        try {
            // 绑定项目（中文路径 → sanitize slug，贴近用户真实「桌面报告」场景）
            Path boundProject = configHome.resolve("报告项目");
            java.nio.file.Path projectMemoryDir = configHome.resolve("projects")
                .resolve(AutoMemPaths.sanitizePath(boundProject.toString()))
                .resolve("memory");
            Files.createDirectories(projectMemoryDir);

            // 模拟会话线程：注入会话 projectRoot（LlmAgentLoop.run() resolveSessionProjectRoot 等价）
            AutoMemPaths.setCurrentProjectRoot(boundProject.toString());

            // 真实 AutoMemPaths（defaultInstance supplier = currentSessionProjectRoot ThreadLocal）
            MemoryPromptBuilder b = new MemoryPromptBuilder(
                AutoMemPaths.defaultInstance(),
                () -> true,   // autoEnabled
                () -> false,  // kairosActive
                () -> false,  // teamEnabled
                () -> false,  // coralFernFlag
                () -> false,  // mothCopseFlag
                () -> false,  // herringClockFlag
                () -> null,   // coworkEnv
                p -> "");     // entrypointReader

            String prompt = b.loadMemoryPrompt();

            // auto-only prompt 首行目录 = 该绑定项目的 per-project 记忆目录（buildMemoryLines :901）
            assertThat(prompt).contains("file-based memory system at `" + projectMemoryDir + java.io.File.separator + "`");
            // 绝不回落 config-home 自身（缺陷 A 表现：把 config-home 自身当项目 → projects/sanitize(configHome)/memory）。
            // 注意：boundProject slug 以 configHome slug 为前缀，故断言"configHome slug 后紧跟 /memory"而非前缀匹配。
            assertThat(prompt).doesNotContain(
                "memory system at `" + configHome.resolve("projects")
                    .resolve(AutoMemPaths.sanitizePath(configHome.toString()))
                    .resolve("memory").toString());
        } finally {
            AutoMemPaths.setCurrentProjectRoot(null);
            NexusaiPaths.setConfigHomeDirOverride(null);
        }
    }

    @Test
    @DisplayName("[IMP-C-6 · C-6] productionDefault() 单例装配接生产静态 coralFern —— flag 开 agent-memory prompt 含 Searching past context")
    void productionDefault_reads_static_coralFern(@TempDir Path dir) {
        // WHY（规则九）：C-6 单例缺口 —— AgentMemoryDirectory 共享单例（DefaultHolder.INSTANCE）
        // 经 MemoryPromptBuilder.productionDefault()（≤5 参链）装配，coralFernFlag 恒 false →
        // agent-memory 子代理变体 searching-past 段永不输出（对齐 CC agentMemory.ts:138-177 →
        // memdir.ts:263 buildMemoryLines → :376 buildSearchingPastContextSection 门控
        // getFeatureValue_CACHED_MAY_BE_STALE('tengu_coral_fern', false)）。合并门裁决：装配点
        // setProductionCoralFern(FeatureFlags.coralFern()) 注入。若静态 holder 未流入
        // productionDefault() 链，flag 开时 prompt 仍缺「Searching past context」段（回归到 C-6 缺口）。
        MemoryPromptBuilder prod = MemoryPromptBuilder.productionDefault();
        try {
            // flag 开（GB 开）→ agent-memory 变体 prompt 注入 searching-past 段
            MemoryPromptBuilder.setProductionCoralFern(() -> true);
            assertThat(prod.buildMemoryPrompt("Persistent Agent Memory", dir.toString(), null))
                .contains("## Searching past context")
                .contains("When looking for past context:")
                .contains("1. Search topic files in your memory directory:");
        } finally {
            // flag 关（GB 缺省）→ 段不出现；清理静态 holder 防跨用例泄漏（对齐 setProductionTelemetry 惯例）
            MemoryPromptBuilder.setProductionCoralFern(null);
            assertThat(prod.buildMemoryPrompt("Persistent Agent Memory", dir.toString(), null))
                .doesNotContain("## Searching past context");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 5. skipIndex (tengu_moth_copse) -> 一步保存 vs 两步保存
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("skipIndex=true -> 一步保存 (memdir.ts:205-217) ; false -> 两步")
    void skipIndex_togglesSaveSection(@TempDir Path dir) {
        MemoryPromptBuilder b1 = builder(dir, true, false, false, false, true, false, () -> null, p -> "");
        MemoryPromptBuilder b2 = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        String skip = b1.loadMemoryPrompt();
        String normal = b2.loadMemoryPrompt();

        assertThat(skip)
            .contains("## How to save memories")
            .contains("Write each memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:")
            .doesNotContain("two-step process")
            .doesNotContain("**Step 2**");
        assertThat(normal).contains("Saving a memory is a two-step process:");
    }
    @Test
    @DisplayName("KAIROS on + skipIndex(tengu_moth_copse)=true -> daily-log 省略 MEMORY.md 蒸馏索引段 (memdir.ts:359-365)")
    void kairosActive_skipIndexTrue_omitsMemoryMdIndex(@TempDir Path dir) {
        // WHY: CC buildAssistantDailyLogPrompt(skipIndex)（memdir.ts:327-370）—— skipIndex=true 时
        //      不注入 `## MEMORY.md` 蒸馏索引段（:359-365）；false 时注入。IMP-MV2-12 单轨收敛后
        //      skipIndex 与 auto 变体同源（tengu_moth_copse GB flag）。
        MemoryPromptBuilder b1 = builder(dir, true, true, false, false, true, false, () -> null, p -> "");
        MemoryPromptBuilder b2 = builder(dir, true, true, false, false, false, false, () -> null, p -> "");

        String skip = b1.loadMemoryPrompt();
        String normal = b2.loadMemoryPrompt();

        assertThat(skip)
            .contains("## What to log")
            .contains("append-only")
            .doesNotContain("## " + MemoryPromptBuilder.ENTRYPOINT_NAME)
            .doesNotContain("is the distilled index");
        assertThat(normal)
            .contains("## " + MemoryPromptBuilder.ENTRYPOINT_NAME)
            .contains("is the distilled index (maintained nightly from your logs)");
    }

    // ════════════════════════════════════════════════════════════════
    // 6. extra guidelines 注入 (CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES)
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES 非空 -> 注入段；空 -> 跳过 (memdir.ts:441-446)")
    void extraGuidelines_injectedWhenPresent(@TempDir Path dir) {
        MemoryPromptBuilder withExtra = builder(dir, true, false, false, false, false, false, () -> "EXTRA POLICY LINE", p -> "");
        MemoryPromptBuilder withoutExtra = builder(dir, true, false, false, false, false, false, () -> "", p -> "");

        assertThat(withExtra.loadMemoryPrompt()).contains("EXTRA POLICY LINE");
        assertThat(withoutExtra.loadMemoryPrompt()).doesNotContain("EXTRA POLICY LINE");
    }

    // ════════════════════════════════════════════════════════════════
    // 7. buildMemoryPrompt (agent 变体, memdir.ts:272-316) 含 MEMORY.md 内容
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("buildMemoryPrompt -> 追加 ## MEMORY.md + 内容 (截断保护 truncateEntrypointContent)")
    void buildMemoryPrompt_includesEntrypoint(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "- [Alpha](alpha.md) — user role notes\n- [Beta](beta.md) — feedback on terse replies");

        String prompt = b.buildMemoryPrompt("agent memory", dir.toString(), null);

        assertThat(prompt).contains("## MEMORY.md");
        assertThat(prompt).contains("- [Alpha](alpha.md) — user role notes");
        assertThat(prompt).doesNotContain("WARNING: MEMORY.md is");
    }

    @Test
    @DisplayName("buildMemoryPrompt -> entrypoint 为空时提示 currently empty (memdir.ts:307-313)")
    void buildMemoryPrompt_emptyEntrypoint(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        String prompt = b.buildMemoryPrompt("agent memory", dir.toString(), null);

        assertThat(prompt).contains("## MEMORY.md");
        assertThat(prompt).contains("Your MEMORY.md is currently empty. When you save new memories, they will appear here.");
    }

    @Test
    @DisplayName("truncateEntrypointContent -> 超 200 行触发行截断 + WARNING (memdir.ts:57-103)")
    void truncateEntrypoint_lineCap(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 210; i++) {
            sb.append("- entry ").append(i).append("\n");
        }
        MemoryPromptBuilder.EntrypointTruncation t = b.truncateEntrypointContent(sb.toString());

        assertThat(t.wasLineTruncated()).isTrue();
        assertThat(t.content()).contains("WARNING: MEMORY.md is");
        assertThat(t.lineCount()).isEqualTo(210);
    }

    @Test
    @DisplayName("truncateEntrypointContent -> 超字节上限时 WARNING reason 用 formatFileSize 人类可读形态 (format.ts:9-24)")
    void truncateEntrypoint_byteCapUsesFormatFileSize(@TempDir Path dir) {
        // WHY: CC 的 WARNING reason 用 formatFileSize(byteCount) 命名触发上限（format.ts:87-92）——
        //      如 "29.3KB (limit: 24.4KB) — index entries are too long"，而非原始字节数。
        //      模型需理解截断规模；原始字节数数字会淹没在长行内容中。
        MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> "");

        // 单行 30000 字符 → 1 行（不触发行上限）但超字节上限
        String raw = "a".repeat(30000);
        MemoryPromptBuilder.EntrypointTruncation t = b.truncateEntrypointContent(raw);

        assertThat(t.wasByteTruncated()).isTrue();
        assertThat(t.wasLineTruncated()).isFalse();
        // CC formatFileSize(30000) = "29.3KB"，formatFileSize(25000) = "24.4KB"
        assertThat(t.content()).contains("WARNING: MEMORY.md is 29.3KB (limit: 24.4KB) — index entries are too long.");
        assertThat(t.content()).doesNotContain("30000 bytes");
    }

    // ════════════════════════════════════════════════════════════════
    // 8. team 分支 -> CombinedMemoryPrompt DIRS_EXIST_GUIDANCE 文本 (DEL-M-01)
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("team enabled -> 合并 prompt 含 DIRS_EXIST_GUIDANCE 'do not run mkdir' (teamMemPrompts.ts:63)")
    void teamEnabled_combinedPromptText(@TempDir Path dir) {
        MemoryPromptBuilder b = builder(dir, true, false, true, false, false, false, () -> null, p -> "");

        String prompt = b.loadMemoryPrompt();

        assertThat(prompt)
            .contains("# Memory")
            .contains("two directories")
            .contains("Both directories already exist — write to them directly with the Write tool "
                + "(do not run mkdir or check for their existence).")
            .contains("## Memory scope")
            .contains("You MUST avoid saving sensitive data within shared team memories.")
            .doesNotContain("ls both directories");
    }

    // ════════════════════════════════════════════════════════════════
    // 9. NEW-6 生产装配（kairosActive supplier 接线 · nexusai.feature.kairos → productionDefault）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("NEW-6 生产装配: productionDefault(tel, kairosActive=true) → daily-log 分支可达 (memdir.ts:432)")
    void productionDefault_kairosActiveTrue_reachesDailyLogBranch(@TempDir Path dir) {
        // WHY: NEW-6 接线断言 —— nexusai.feature.kairos 部署标志置真后（kairosActive supplier 注入），
        //      生产装配的 builder 必须让 KAIROS daily-log 分支可达（CC memdir.ts:432 三重组门控
        //      feature('KAIROS') && autoEnabled && getKairosActive() 的 Java 等价）。
        MemoryPromptBuilder prod = MemoryPromptBuilder.productionDefault(null, () -> true);
        if (!BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            // 环境显式禁用 auto memory → CC 三重组第二项失败 → KAIROS 分支不进 → null（memdir.ts:432/506）
            assertThat(prod.loadMemoryPrompt()).isNull();
            return;
        }
        assertThat(prod.loadMemoryPrompt())
            .contains("# auto memory")
            .contains("## What to log")
            .contains("append-only");
    }

    @Test
    @DisplayName("IMP-MV2-12 生产装配: productionDefaultWithMothCopse 接线 FeatureFlags.tenguMothCopse -> flag=true 单步 / false 两步")
    void productionDefaultWithMothCopse_wiresFeatureFlagsSingleSource(@TempDir Path dir) {
        // WHY: 单轨收敛断言 —— CC memdir.ts:422-425 skipIndex 单一 flag 源 = tengu_moth_copse GB flag；
        //      LlmAgentLoop 生产装配（:2593-2595/:3617-3618）经 productionDefaultWithMothCopse
        //      注入 FeatureFlags.tenguMothCopse()，与预取门控（attachments.ts:2367）/
        //      claudemd 过滤（claudemd.ts:1146）/提取 prompt skipIndex（extractMemories.ts:367）
        //      共用同一值 —— flag=true 时 loadMemoryPrompt 输出单步 howToSave（CC 语义联动）。
        com.nexusai.application.agent.loop.FeatureFlags on =
            new com.nexusai.application.agent.loop.FeatureFlags(false, false, false, false, true,
                false, false, false, false, false, false, false, false, false, false, false,
                false, false, false, false, false);
        com.nexusai.application.agent.loop.FeatureFlags off =
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;

        MemoryPromptBuilder bOn = MemoryPromptBuilder.productionDefaultWithMothCopse(null, on::tenguMothCopse);
        MemoryPromptBuilder bOff = MemoryPromptBuilder.productionDefaultWithMothCopse(null, off::tenguMothCopse);
        if (!BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            assertThat(bOn.loadMemoryPrompt()).isNull();
            return;
        }
        assertThat(bOn.loadMemoryPrompt())
            .contains("## How to save memories")
            .contains("Write each memory to its own file")
            .doesNotContain("two-step process");
        assertThat(bOff.loadMemoryPrompt()).contains("Saving a memory is a two-step process:");
    }

    @Test
    @DisplayName("NEW-6 生产装配: kairosActive=false → 落到 auto 分支（不出现 daily-log 文本）")
    void productionDefault_kairosActiveFalse_fallsThroughToAuto(@TempDir Path dir) {
        MemoryPromptBuilder prod = MemoryPromptBuilder.productionDefault(null, () -> false);
        if (!BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            assertThat(prod.loadMemoryPrompt()).isNull();
            return;
        }
        assertThat(prod.loadMemoryPrompt())
            .contains("Saving a memory is a two-step process:")
            .doesNotContain("## What to log");
    }

    // ════════════════════════════════════════════════════════════════
    // 10. NEW-2 disabled 分支 telemetry（两属性 + herring_clock 子事件 · memdir.ts:492-505）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("disabled -> tengu_memdir_disabled 两属性 (disabled_by_env_var/disabled_by_setting, memdir.ts:492-499)")
    void disabled_emitsMemdirDisabledWithTwoAttributes(@TempDir Path dir) {
        Telemetry tel = spy(new Telemetry());
        MemoryPromptBuilder b = new MemoryPromptBuilder(
            paths(dir), () -> false, () -> false, () -> false, () -> false, () -> false,
            () -> false, () -> null, p -> "", tel);

        assertThat(b.loadMemoryPrompt()).isNull();

        // 双发射（recordEvent + logOTelEvent，跟随落地载体 NEW-1）
        verify(tel).recordEvent(eq("tengu_memdir_disabled"), any());
        verify(tel).logOTelEvent(eq("tengu_memdir_disabled"), any());
        // 两属性（CC 字面 memdir.ts:492-499）：CLAUDE_CODE_DISABLE_AUTO_MEMORY 未置（测试环境）→ env=false；
        // settings 未配置（无 settings 文件）→ getInitialSettings().autoMemoryEnabled !== false → setting=false
        // （F1 返工：精确读 settings 字面值，不再按 bare/remote 排除法推导）
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> captor =
            (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(tel).recordEvent(eq("tengu_memdir_disabled"), captor.capture());
        assertThat(captor.getValue())
            .containsEntry("disabled_by_env_var", false)
            .containsEntry("disabled_by_setting", false);
        // herring_clock 关闭 → 无 team 子事件
        verify(tel, never()).recordEvent(eq("tengu_team_memdir_disabled"), any());
        verify(tel, never()).logOTelEvent(eq("tengu_team_memdir_disabled"), any());
    }

    @Test
    @DisplayName("disabled + nexusai user settings 显式 autoMemoryEnabled=false -> disabled_by_setting=true（CC 字面 memdir.ts:496-498）")
    void disabled_settingFalseInProjectSettings_emitsSettingTrue(@TempDir Path dir) throws Exception {
        // D2 适配：claude settings.json 一律不读（BundledSkillEnabledGates.java:163-167）——fixture 从
        // dir/.claude/settings.json 迁移到 nexusai user settings 落点（NexusaiPaths.getAppConfigHomeDir()/
        // settings.json = {user.home}/.{appName}/settings.json），显式关闭 auto memory —— CC
        // getInitialSettings().autoMemoryEnabled === false → disabled_by_setting=true（bare/remote 等其它
        // 禁用原因不参与本属性，仅字面读 settings；反射角例「bare/remote 与 settings=false 并存」随精确
        // 实现消除）。唯一 appName 隔离（防写真实 ~/.nexusai）。
        BundledSkillEnabledGates.bridgeSettingsMapper(null);   // 清 DB 桥接泄漏（防他用例污染）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + dir.getFileName());
        Files.createDirectories(Paths.get(NexusaiPaths.getAppConfigHomeDir()));
        Files.writeString(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json"),
            "{\"autoMemoryEnabled\": false}");
        try {
            Telemetry tel = spy(new Telemetry());
            MemoryPromptBuilder b = new MemoryPromptBuilder(
                paths(dir), () -> false, () -> false, () -> false, () -> false, () -> false,
                () -> false, () -> null, p -> "", tel);

            assertThat(b.loadMemoryPrompt()).isNull();

            @SuppressWarnings({"unchecked", "rawtypes"})
            ArgumentCaptor<Map<String, Object>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
            verify(tel).recordEvent(eq("tengu_memdir_disabled"), captor.capture());
            assertThat(captor.getValue())
                .containsEntry("disabled_by_env_var", false)
                .containsEntry("disabled_by_setting", true);
        } finally {
            NexusaiPaths.setAppNameOverride(null);
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
        }
    }

    @Test
    @DisplayName("disabled + tengu_herring_clock on -> 追加 tengu_team_memdir_disabled 子事件 (memdir.ts:503-505)")
    void disabled_herringClockOn_emitsTeamMemdirDisabledSubEvent(@TempDir Path dir) {
        Telemetry tel = spy(new Telemetry());
        MemoryPromptBuilder b = new MemoryPromptBuilder(
            paths(dir), () -> false, () -> false, () -> false, () -> false, () -> false,
            () -> true, () -> null, p -> "", tel);

        assertThat(b.loadMemoryPrompt()).isNull();

        verify(tel).recordEvent(eq("tengu_memdir_disabled"), any());
        verify(tel).recordEvent(eq("tengu_team_memdir_disabled"), any());
        verify(tel).logOTelEvent(eq("tengu_team_memdir_disabled"), any());
    }

    @Test
    @DisplayName("[IMP-C-5 · OPD-CM5-C-09] 五参生产装配: herringClockFlag 接 FeatureFlags.tenguHerringClock() → GB flag 开时 disabled 分支发射 tengu_team_memdir_disabled (memdir.ts:503-505)")
    void productionDefault5_wiresHerringClockFlagToFeatureFlags(@TempDir Path dir) throws Exception {
        // WHY: C-09 接线断言 —— 生产装配 herringClockFlag 必须接 FeatureFlags.tenguHerringClock()
        //      （访问器 FeatureFlags.java:341-344 已存在，但旧生产装配全系硬编码 () -> false =
        //      探查 △-3'：GB flag 开启时 CC 发射 tengu_team_memdir_disabled、Java 不发射，生产行为分叉）。
        //      D2 适配：claude settings.json 一律不读（BundledSkillEnabledGates.java:163-167）——
        //      fixture 从 dir/.claude/settings.json 迁移到 nexusai user settings 落点
        //      （NexusaiPaths.getAppConfigHomeDir()/settings.json）autoMemoryEnabled=false →
        //      isAutoMemoryEnabled()=false → auto-memory 禁用 → disabled 分支（memdir.ts:492-506）；
        //      tenguHerringClock=true → 追加 team 子事件（:503-505），与 teamMemoryEnabled 双门控无关。
        //      唯一 appName 隔离（防写真实 ~/.nexusai）。
        BundledSkillEnabledGates.bridgeSettingsMapper(null);   // 清 DB 桥接泄漏（防他用例污染）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + dir.getFileName());
        Files.createDirectories(Paths.get(NexusaiPaths.getAppConfigHomeDir()));
        Files.writeString(Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json"),
            "{\"autoMemoryEnabled\": false}");
        try {
            // 对照：4 参生产装配（旧路径）→ herringClockFlag 恒 false → 无 team 子事件
            Telemetry telControl = spy(new Telemetry());
            MemoryPromptBuilder b4 = MemoryPromptBuilder.productionDefault(
                telControl, () -> false, () -> false, () -> false);
            assertThat(b4.loadMemoryPrompt()).isNull();
            verify(telControl).recordEvent(eq("tengu_memdir_disabled"), any());
            verify(telControl, never()).recordEvent(eq("tengu_team_memdir_disabled"), any());
            verify(telControl, never()).logOTelEvent(eq("tengu_team_memdir_disabled"), any());

            // 测试：5 参生产装配 + FeatureFlags.tenguHerringClock=true（GB flag 开）→ team 子事件双发射
            Telemetry tel = spy(new Telemetry());
            com.nexusai.application.agent.loop.FeatureFlags on =
                new com.nexusai.application.agent.loop.FeatureFlags(false, false, false, false, false,
                    false, false, false, false, false, false, false, false, false, false, false,
                    false, false, false, false, true);
            MemoryPromptBuilder b5 = MemoryPromptBuilder.productionDefault(
                tel, () -> false, () -> false, () -> false, on::tenguHerringClock);
            assertThat(b5.loadMemoryPrompt()).isNull();
            verify(tel).recordEvent(eq("tengu_memdir_disabled"), any());
            verify(tel).recordEvent(eq("tengu_team_memdir_disabled"), any());
            verify(tel).logOTelEvent(eq("tengu_team_memdir_disabled"), any());
        } finally {
            NexusaiPaths.setAppNameOverride(null);
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 11. D08-3 logMemoryDirCounts symlink 不计数（NOFOLLOW_LINKS · memdir.ts:168-171）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("D08-3: symlink 文件不计入 total_file_count（Dirent 不 follow · memdir.ts:168-171）")
    void logMemoryDirCounts_symlinkNotCounted(@TempDir Path dir) throws Exception {
        // WHY: CC memdir.ts:168-171 用 `dirent.isFile()/isDirectory()` —— Node Dirent 对 symlink
        //      返回 false（isSymbolicLink() 而非 isFile/isDirectory）。旧 Java `Files.isRegularFile`
        //      默认跟链 → symlink→file 被误计为 file（total_file_count +1）。NOFOLLOW_LINKS 对齐
        //      Dirent 语义：symlink 不计数（D08-3，探查 C/CM-C3 △-1）。
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("real.md"), "---\ndescription: real\n---\nbody\n");
        Path target = dir.resolve("target.md");
        Files.writeString(target, "---\ndescription: target\n---\nbody\n");
        Path link = dir.resolve("link.md");
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "symlink 创建失败（无权限/无 Developer Mode），跳过 symlink 用例: " + e.getMessage());
            return;
        }

        Telemetry tel = spy(new Telemetry());
        MemoryPromptBuilder b = new MemoryPromptBuilder(
            paths(dir), () -> true, () -> false, () -> false, () -> false, () -> false,
            () -> false, () -> null, p -> "", tel);

        b.logMemoryDirCounts(dir.toString(), Map.of("memory_type", "auto"));

        // logMemoryDirCounts 是 fire-and-forget（CompletableFuture.runAsync 共享守护线程池）：
        // 轮询等待双发射落地（无 awaitility 依赖，手写兜底，同 LocalBashTaskRunnerStreamingTest）。
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> captor =
            (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        Map<String, Object> emitted = awaitMemdirLoaded(tel, captor);

        // 1 个真实文件 + 1 个 symlink→file：NOFOLLOW_LINKS 后仅真实文件计入
        assertThat(emitted)
            .as("symlink→file 不计数（Dirent 不 follow · memdir.ts:168-171）")
            .containsEntry("total_file_count", 1)
            .containsEntry("total_subdir_count", 0);
    }

    @Test
    @DisplayName("D08-3 补盲（C3 Q-5）: symlink→dir 不计入 total_subdir_count（Dirent 不 follow · memdir.ts:168-171）")
    void logMemoryDirCounts_symlinkDirNotCounted(@TempDir Path dir) throws Exception {
        // WHY（C3 Q-5）：D08-3 只固化 symlink→file；symlink→dir 同样不可计为子目录——Node Dirent
        // isDirectory() 对 symlink 返回 false（memdir.ts:170-171）。Java Files.isDirectory(NOFOLLOW_LINKS)
        // 对 symlink→dir 返回 false（实现在 2026-08-16 探查 v3 △-1 已对齐 CC），本测试固化该分支。
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("real.md"), "---\ndescription: real\n---\nbody\n");
        Path realSub = Files.createDirectories(dir.resolve("real-sub"));
        Path linkDir = dir.resolve("link-dir");
        try {
            Files.createSymbolicLink(linkDir, realSub);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "symlink 创建失败（无权限/无 Developer Mode），跳过 symlink 用例: " + e.getMessage());
            return;
        }

        Telemetry tel = spy(new Telemetry());
        MemoryPromptBuilder b = new MemoryPromptBuilder(
            paths(dir), () -> true, () -> false, () -> false, () -> false, () -> false,
            () -> false, () -> null, p -> "", tel);

        b.logMemoryDirCounts(dir.toString(), Map.of("memory_type", "auto"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> captor =
            (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        Map<String, Object> emitted = awaitMemdirLoaded(tel, captor);

        // 1 个真实文件 + 1 个真实子目录 + 1 个 symlink→dir：NOFOLLOW_LINKS 后仅真实子目录计入
        assertThat(emitted)
            .as("symlink→dir 不计数（Dirent isDirectory()=false · memdir.ts:170-171）")
            .containsEntry("total_file_count", 1)
            .containsEntry("total_subdir_count", 1);
    }

    /** 轮询等待 tengu_memdir_loaded recordEvent 落地（fire-and-forget 异步）。 */
    private static Map<String, Object> awaitMemdirLoaded(Telemetry tel, ArgumentCaptor<Map<String, Object>> captor) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(tel).recordEvent(eq("tengu_memdir_loaded"), captor.capture());
                return captor.getValue();
            } catch (AssertionError e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("等待 tengu_memdir_loaded 被中断", ie);
                }
            }
        }
        throw new AssertionError("5s 内未收到 tengu_memdir_loaded");
    }
    // ════════════════════════════════════════════════════════════════
    // 12. IMP-MV2-19 teamMemoryEnabled 接线（TEAMMEM 分支可达 + 双计数一致；双关零行为变化）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IMP-MV2-19 双开: TEAMMEM 分支可达 + 双计数一致（auto+team 两路 tengu_memdir_loaded）")
    void teamEnabled_dualCountsAutoAndTeam(@TempDir Path dir) {
        // WHY: CC memdir.ts:460-467 —— TEAMMEM 分支对 autoDir 与 teamDir 各计数一次
        //       （memory_type=auto + memory_type=team，双计数一致）。P1-4 接线（IMP-MV2-19）前
        //       双门控恒 false → 分支与双计数生产不可达；本用例锁定接线后契约。
        Telemetry tel = spy(new Telemetry());
        MemoryPromptBuilder b = new MemoryPromptBuilder(
            paths(dir), () -> true, () -> false, () -> true, () -> false, () -> false,
            () -> false, () -> null, p -> "", tel);

        String prompt = b.loadMemoryPrompt();

        // CombinedMemoryPrompt 文本（teamMemPrompts.ts:22-100）—— TEAMMEM 分支可达
        assertThat(prompt).contains("## Memory scope");
        // 双计数一致：autoDir → memory_type=auto、teamDir → memory_type=team（memdir.ts:460-467）
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> captor =
            (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(tel, timeout(5000).times(2)).recordEvent(eq("tengu_memdir_loaded"), captor.capture());
        List<String> types = captor.getAllValues().stream()
            .map(m -> (String) m.get("memory_type"))
            .collect(java.util.stream.Collectors.toList());
        assertThat(types)
            .as("双计数 memory_type = auto + team（CC memdir.ts:460-467）")
            .containsExactlyInAnyOrder("auto", "team");
        captor.getAllValues().forEach(m ->
            assertThat(m)
                .as("计数载荷含 total_file_count/total_subdir_count（memdir.ts:168-173）")
                .containsKeys("total_file_count", "total_subdir_count"));
    }

    @Test
    @DisplayName("IMP-MV2-19 生产装配: productionDefault(tel, kairos, team=true) → TEAMMEM 分支可达（三闸全开）")
    void productionDefault_teamEnabledTrue_reachesTeamBranch(@TempDir Path dir) {
        // WHY: IMP-MV2-19 接线断言 —— teamMemoryEnabled supplier（feature('TEAMMEM') &&
        //       tengu_herring_clock）注入生产装配入口后 TEAMMEM 分支可达（CC memdir.ts:448-449）；
        //       auto-memory 门由 productionDefault 内部合成（teamMemPaths.ts:73-78 isTeamMemoryEnabled
        //       语义）→ 环境显式禁用 auto 时回落 disabled（null），与 CC 一致。
        MemoryPromptBuilder prod = MemoryPromptBuilder.productionDefault(null, () -> false, () -> true);

        String prompt = prod.loadMemoryPrompt();

        if (!BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            // 环境显式禁用 auto memory → 三闸第二项失败 → TEAMMEM 分支不进 → null（memdir.ts:448-449/506）
            assertThat(prompt).isNull();
            return;
        }
        assertThat(prompt).contains("## Memory scope");
    }

    @Test
    @DisplayName("IMP-MV2-19 双关: productionDefault 默认 team 关 → 零行为变化（回落 auto-only 或 disabled→null）")
    void productionDefault_teamDisabled_defaultBehaviorUnchanged(@TempDir Path dir) {
        // WHY: IMP-MV2-19 双关零行为变化 —— 生产默认双门控关（FeatureFlags teamMem/tenguHerringClock
        //       默认 false）时 TEAMMEM 分支不可达，行为与接线前完全一致（auto-only 或 disabled→null）。
        MemoryPromptBuilder prod = MemoryPromptBuilder.productionDefault(null);

        String prompt = prod.loadMemoryPrompt();

        if (!BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            assertThat(prompt).isNull();
            return;
        }
        assertThat(prompt).contains("# auto memory");
        assertThat(prompt).doesNotContain("## Memory scope");
    }

    // ════════════════════════════════════════════════════════════════
    // 13. IMP-C-4 · OPD-CM5-C-08 子代理 agent-memory 路径计数遥测接线（生产静态兜底回落）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IMP-C-4: buildMemoryPrompt（agent-memory 变体）telemetry=null 实例回落生产静态兜底 → tengu_memdir_loaded 发射")
    void buildMemoryPrompt_agentPath_fallsBackToProductionTelemetry(@TempDir Path dir) throws Exception {
        // WHY（规则九）：CC 端 agentMemory.ts:169-176 → buildMemoryPrompt:298 门控通过时无条件
        //      logEvent('tengu_memdir_loaded')。Java 端 AgentMemoryDirectory 经 IMP-F2-4（F-21）
        //      统一共享单例，其 MemoryPromptBuilder 构造期无实例 telemetry → emitMemdirLoaded 跳过
        //      （探查 C/CM-C3 R-3 / △-1）。本用例锁定 C-08 修复：生产装配点经
        //      setProductionTelemetry 注入静态兜底后，agent-memory 路径（buildMemoryPrompt）
        //      在门控通过时真实发射，memory_type='agent'。
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MEMORY.md"),
            "# Index\n- [x](x.md) — hook\n");

        Telemetry tel = spy(new Telemetry());
        MemoryPromptBuilder.setProductionTelemetry(tel);
        try {
            // 9 参构造（telemetry=null）→ 模拟 AgentMemoryDirectory 单例装配（无实例 telemetry）
            MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> {
                try {
                    return Files.readString(Path.of(p));
                } catch (Exception e) {
                    return "";
                }
            });
            b.buildMemoryPrompt("Persistent Agent Memory", dir.toString(), List.of());

            @SuppressWarnings({"unchecked", "rawtypes"})
            ArgumentCaptor<Map<String, Object>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
            Map<String, Object> emitted = awaitMemdirLoaded(tel, captor);
            assertThat(emitted)
                .as("agent-memory 路径计数事件载荷（buildMemoryPrompt baseMetadata，memdir.ts:298-305）")
                .containsEntry("memory_type", "agent")
                .containsEntry("total_file_count", 1)
                .containsEntry("total_subdir_count", 0)
                .containsKeys("content_length", "line_count");
        } finally {
            MemoryPromptBuilder.setProductionTelemetry(null);
        }
    }

    @Test
    @DisplayName("IMP-C-4: 静态兜底未设置（测试默认）→ telemetry=null 实例 buildMemoryPrompt 不发射（零行为变化）")
    void buildMemoryPrompt_agentPath_noProductionTelemetry_noEmit(@TempDir Path dir) throws Exception {
        // WHY（规则九）：C-08 修复必须保持零行为变化面 —— 未设置静态兜底时，telemetry=null 实例
        //      不得发射（不污染测试/未接线装配）。对照：实例 telemetry 注入仍优先（主 loop 路径）。
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MEMORY.md"), "# Index\n");
        MemoryPromptBuilder.setProductionTelemetry(null); // 显式清空（防前例泄漏）
        try {
            Telemetry tel = spy(new Telemetry());
            MemoryPromptBuilder b = builder(dir, true, false, false, false, false, false, () -> null, p -> {
                try {
                    return Files.readString(Path.of(p));
                } catch (Exception e) {
                    return "";
                }
            });
            b.buildMemoryPrompt("Persistent Agent Memory", dir.toString(), List.of());
            // 静态兜底未设置 → telemetry=null 实例不发射（emitMemdirLoaded 双 null 短路）
            verify(tel, never()).recordEvent(eq("tengu_memdir_loaded"), any());
            verify(tel, never()).logOTelEvent(eq("tengu_memdir_loaded"), any());
        } finally {
            MemoryPromptBuilder.setProductionTelemetry(null);
        }
    }

}
