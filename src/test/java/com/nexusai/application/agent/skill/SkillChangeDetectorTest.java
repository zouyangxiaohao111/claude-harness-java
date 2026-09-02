package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PromptHook;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-16 + P3-33] SkillChangeDetector 真实 FS watcher · 对齐 CC utils/skills/skillChangeDetector.ts:85-279.
 *
 * <p>WHY (规则九 · 测试验证意图): CC 用 chokidar 被动监听 skill/command 目录, 文件变更 →
 * tengu_skill_file_changed 遥测 + awaitWriteFinish 写稳定 + 300ms 去抖 → ConfigChange hook 阻断闸 →
 * clearSkillCaches + clearCommandsCache + resetSentSkillNames + skillsChanged.emit。Java 旧实现是内存
 * hash 对比存根（主动 check() 调用, 无 FS watcher, X29/D-8）→ 磁盘 skill 变更永不触发重载。
 * P3-33 起监听层用 io.methvin:directory-watcher（递归注册 + 内容 hash 去重），写稳定等待由
 * {@code onIdle} debounce 空闲窗口承载（awaitWriteFinish 等价）。本测试用临时目录 + 真实 FS 监听
 * 锁定 CC 全链路:
 * <ul>
 *   <li><b>真实 FS 监听</b>: SKILL.md 修改 → SkillRegistry.refresh + telemetry + hook 闸 + emit (CC :255-279)</li>
 *   <li><b>阻断闸</b>: 配置 blocked ConfigChange hook → reload 被阻断, refresh 不调用 (CC :267-273)</li>
 *   <li><b>5 类路径跳过缺失</b>: getWatchablePaths 跳过不存在路径, 空路径 initialize 早返 (CC :171-235/:104)</li>
 *   <li><b>递归子目录补注册</b>: initialize 后新建 skillName 目录 → 内部 SKILL.md 变更仍触发 reload (directory-watcher 递归注册)</li>
 *   <li><b>debounce 写稳定</b>: onIdle 空闲窗口内不触发, 稳定后恰一次 reload (CC awaitWriteFinish 语义, P3-33)</li>
 *   <li><b>dispose 后不触发</b>: 事件不再产生 reload (CC :146-164)</li>
 *   <li><b>signal 多订阅者</b>: subscribe 返回退订句柄, emit 通知全部订阅者 (CC :169 signal.ts)</li>
 * </ul>
 */
@DisplayName("[P1-16] SkillChangeDetector 真实 FS watcher")
class SkillChangeDetectorTest {

    @TempDir
    Path tempDir;

    private SkillChangeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SkillChangeDetector();
        detector.setProjectDir(tempDir);
        // 测试覆盖: debounce 空闲窗口 100ms (P3-33 起 stabilityThreshold 即 directory-watcher
        // onIdle debounce 窗口; 旧 reloadDebounce/pollInterval 已移除, 见 SkillChangeDetector)
        detector.resetForTesting(Map.of(
            "stabilityThreshold", 100L));
    }

    @AfterEach
    void tearDown() {
        detector.dispose();
        // P2-20/P2-21：清理 ClaudePaths 覆写，避免跨测试污染（SkillsLoaderMultiSourceTest 同款）
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 黄金链路 — 文件修改 → refresh + telemetry + hook 闸 + emit
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SKILL.md 修改 → refresh+遥测+hook 阻断闸+emit 全链路 (CC :255-279)")
    void realFsWatch_fileChange_firesFullReloadChain() throws Exception {
        // WHY: 旧 hash 存根在磁盘上根本没有 watcher — skill 文件变更永不触发重载 (X29).
        //       验证: 监听真实目录 → 修改 SKILL.md → WatchService 事件 → 去抖后 refresh
        //       + tengu_skill_file_changed + ConfigChange hook 闸 + skillsChanged.emit 全命中.
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Path skillDir = skillsRoot.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent");

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        detector.setSkillRegistry(registry);

        Telemetry telemetry = new Telemetry();
        detector.setTelemetry(telemetry);

        HookRegistry hookRegistry = new HookRegistry();
        hookRegistry.setTelemetry(new Telemetry());
        AtomicInteger hookCalls = new AtomicInteger();
        hookRegistry.register("rec-config-change",
            event -> {
                hookCalls.incrementAndGet();
                return GenericHook.HookResult.proceed();
            },
            HookEventType.CONFIG_CHANGE);
        detector.setHookRegistry(hookRegistry);

        AtomicInteger emitCalls = new AtomicInteger();
        detector.subscribe(emitCalls::incrementAndGet);

        detector.initialize();

        // 修改已存在的 SKILL.md → ENTRY_MODIFY
        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent v2");

        assertThat(awaitTrue(5000, () -> registry.refreshCalls.get() >= 1))
            .as("文件修改后 SkillRegistry.refresh() 必须被调用 (CC :274 clearSkillCaches+clearCommandsCache)")
            .isTrue();
        assertThat(telemetry.getCounter("tengu_skill_file_changed"))
            .as("tengu_skill_file_changed 遥测必须命中 (CC :239-241, source=chokidar)")
            .isGreaterThanOrEqualTo(1);
        assertThat(hookCalls.get())
            .as("ConfigChange hook 阻断闸必须被调用 (CC :267 executeConfigChangeHooks('skills', firstPath))")
            .isGreaterThanOrEqualTo(1);
        assertThat(emitCalls.get())
            .as("skillsChanged.emit() 必须通知订阅者 (CC :277)")
            .isGreaterThanOrEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 阻断闸 — blocked ConfigChange hook 跳过 reload
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("blocked ConfigChange hook → reload 被阻断, refresh 不调用 (CC :267-273)")
    void blockedConfigChangeHook_skipsReload() throws Exception {
        // WHY: CC hasBlockingResult(results) 时 return 不 reload (skillChangeDetector.ts:268-273).
        //       Java 等价 preventContinuation||blockingError!=null → refresh 必须不调用.
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Path skillDir = skillsRoot.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent");

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        detector.setSkillRegistry(registry);

        Telemetry telemetry = new Telemetry();
        detector.setTelemetry(telemetry);

        HookRegistry hookRegistry = new HookRegistry();
        hookRegistry.setTelemetry(new Telemetry());
        hookRegistry.register("block-skill-reload",
            event -> GenericHook.HookResult.stop("blocked", "blocked by test"),
            HookEventType.CONFIG_CHANGE);
        detector.setHookRegistry(hookRegistry);

        detector.initialize();

        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent v3");

        // 等超过去抖窗口 (50ms 覆盖 → 800ms 足够)
        Thread.sleep(800);
        assertThat(registry.refreshCalls.get())
            .as("ConfigChange hook 阻断后 reload 必须跳过 (CC :268-273)")
            .isZero();
        assertThat(telemetry.getCounter("tengu_skill_file_changed"))
            .as("遥测在阻断闸之前记录 (CC handleChange :239 先于 scheduleReload)")
            .isGreaterThanOrEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2.5 [FIX-C2 拍板#10] 阻断闸聚合 some(any blocked) — 非 firstStop
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY (NG-2 / HSCS-IMP-6 · 规则九): CC hasBlockingResult(results)（hooks.ts:2983-2985）
     * = results.some(r => r.blocked) —— 任一结果阻断即阻断，聚合全部结果。旧实现经
     * executeEvent 折叠 firstStop：prompt 型阻断结果（preventContinuation=true）若先完成会
     * 被 isBlockingConfigChange 过滤（prompt 恒不阻断 ConfigChange），从而掩盖同事件 command
     * hook 的 exit-2 阻断 → 漏阻断。本测试锁定：同一 ConfigChange 事件聚合出
     * [prompt 阻断, command 阻断]，只要 command 阻断存在就必须阻断重载（some(any blocked)）。
     */
    @Test
    @DisplayName("[FIX-C2] prompt 阻断 + command 阻断同事件 → 聚合 any blocked 仍阻断（非 firstStop）")
    void configChangeGate_anyBlockedResult_blocksReload() throws Exception {
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Path skillDir = skillsRoot.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: my-skill\n---\ncontent");

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        // 覆写 seam：返回 [prompt 型阻断, command 型阻断] 聚合列表（模拟同事件两 hook 并发，
        // prompt 先完成 -> 旧 firstStop=prompt 会掩盖 command 阻断的边缘）
        SkillChangeDetector stub = new SkillChangeDetector() {
            @Override
            protected List<GenericHook.HookResult> executeConfigChangeHook(String source, String filePath) {
                GenericHook.HookResult promptBlocked = GenericHook.HookResult.stop("prompt-blocked", "p-err")
                    .withHook(new PromptHook("q", null, null, null, null, false));
                GenericHook.HookResult commandBlocked = GenericHook.HookResult.stop("command-blocked", "exit-2-err")
                    .withHook(new CommandHook("exit 2", null, null, null, null, false, false, false));
                return List.of(promptBlocked, commandBlocked);
            }
        };
        stub.setProjectDir(tempDir);
        stub.setSkillRegistry(registry);
        stub.setTelemetry(new Telemetry());
        stub.resetForTesting(Map.of("stabilityThreshold", 100L));
        stub.initialize();

        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: my-skill\n---\ncontent v2");

        // 等超过去抖窗口 (50ms 覆盖 → 800ms 足够)
        Thread.sleep(800);
        assertThat(registry.refreshCalls.get())
            .as("prompt 阻断 + command 阻断 → some(any blocked) 必须阻断重载 (CC hooks.ts:2983-2985)，command exit-2 阻断不得被 prompt 先完成掩盖 (NG-2)")
            .isZero();
    }

    /**
     * WHY (FIX-C2 拍板#10 反向回归): 聚合列表<b>仅</b>含 prompt 型阻断结果时，CC 语义
     * prompt 恒 blocked:false（hooks.ts:3152-3186）→ 不阻断重载。若实现误把 prompt 的
     * preventContinuation 当阻断，会误伤 prompt-only 配置的 skill reload。
     */
    @Test
    @DisplayName("[FIX-C2] 仅 prompt 型阻断结果 → 不阻断重载（prompt 恒 blocked:false）")
    void configChangeGate_promptOnlyBlocked_doesNotBlockReload() throws Exception {
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Path skillDir = skillsRoot.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: my-skill\n---\ncontent");

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        SkillChangeDetector stub = new SkillChangeDetector() {
            @Override
            protected List<GenericHook.HookResult> executeConfigChangeHook(String source, String filePath) {
                GenericHook.HookResult promptBlocked = GenericHook.HookResult.stop("prompt-blocked", "p-err")
                    .withHook(new PromptHook("q", null, null, null, null, false));
                return List.of(promptBlocked);
            }
        };
        stub.setProjectDir(tempDir);
        stub.setSkillRegistry(registry);
        stub.setTelemetry(new Telemetry());
        stub.resetForTesting(Map.of("stabilityThreshold", 100L));
        stub.initialize();

        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: my-skill\n---\ncontent v2");

        assertThat(awaitTrue(5000, () -> registry.refreshCalls.get() >= 1))
            .as("仅 prompt 型阻断 → 不阻断重载 (CC prompt 恒 blocked:false hooks.ts:3152-3186)")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 5 类路径跳过缺失 — 空路径 initialize 早返
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getWatchablePaths 跳过缺失路径; 空路径 initialize 早返 (CC :171-235/:104)")
    void getWatchablePaths_skipsMissingAndEmptyInitializesEarly() throws Exception {
        // WHY: CC 每路径 fs.stat try/catch 跳过缺失 (:179/:192/:205/:219/:229),
        //       paths.length===0 → initialize 早返 (:104). 缺失路径必须不抛异常、不启动 watcher.
        Path nonexistentHome = tempDir.resolve("no-such-home");
        detector.setUserHomeDir(nonexistentHome);
        detector.setProjectDir(tempDir); // 空 tempDir → 无 .claude/skills

        assertThat(detector.getWatchablePaths())
            .as("所有候选路径不存在 → 空列表 (fs.stat 跳过缺失)")
            .isEmpty();

        // 空路径 initialize 直接 return, 不抛异常
        detector.initialize();

        // 验证存在路径被收录 (project .claude/skills, CC :198-208)
        Path exists = tempDir.resolve(".claude").resolve("skills");
        Files.createDirectories(exists);
        assertThat(detector.getWatchablePaths())
            .contains(exists.toAbsolutePath().normalize());
    }

    // ════════════════════════════════════════════════════════════════════
    // 3.5 P2-20: user 路径与加载侧同源（ClaudePaths.getClaudeConfigHomeDir honor CLAUDE_CONFIG_DIR）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P2-20: user skills 路径走 ClaudePaths 配置 home（setConfigDirOverride 覆写生效，与加载侧同源）")
    void getWatchablePaths_usesClaudeConfigHome_forUserDirs() throws Exception {
        // WHY（P2-20 · 规则九）：CC skillChangeDetector.ts:176 getSkillsPath('userSettings','skills')
        //   → getClaudeConfigHomeDir() = CLAUDE_CONFIG_DIR ?? homedir()/.claude（envUtils.ts:7-14）。
        //   旧实现 userHomeDir=user.home/.claude 忽略 CLAUDE_CONFIG_DIR → 设该 env 时 watcher 盯错
        //   目录（EV-WF7-CD-014/024/025）。setConfigDirOverride 是 Java 无法进程内改 env 的测试等价，
        //   覆写后 watcher 必须监听新配置 home 的 skills 目录而非 user.home/.claude。
        Path configHome = tempDir.resolve("cfg-home");
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：watcher 亦收录 nexusai 自有根（SkillChangeDetector.java:396）→ 唯一 appName 隔离（防盯真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        Path userSkills = Files.createDirectories(configHome.resolve("skills"));
        detector.setProjectDir(tempDir.resolve("no-proj")); // 隔离 project 源

        assertThat(detector.getWatchablePaths())
            .as("P2-20 同源：user skills 目录 = ClaudePaths.getClaudeConfigHomeDir()/skills")
            .contains(userSkills.toAbsolutePath().normalize());
    }

    // ════════════════════════════════════════════════════════════════════
    // 3.6 P2-21: --add-dir 附加目录接入监听（与加载侧 additionalDirectoriesSupplier 同源）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P2-21: additional dirs 经 additionalDirectoriesSupplier 供源 → join(dir,.claude,skills) 被监听")
    void getWatchablePaths_watchesAdditionalDirsFromSupplier() throws Exception {
        // WHY（P2-21 · 规则九）：CC skillChangeDetector.ts:224-232 遍历
        //   getAdditionalDirectoriesForClaudeMd()（--add-dir）→ join(dir,'.claude','skills') 监听。
        //   旧实现 additionalDirs 仅 addAdditionalDir 注入生产零调用 → --add-dir 技能目录不被监听
        //   （EV-WF7-CD-016/023）。P2-21 默认 additionalDirectoriesSupplier=ClaudePaths 同源函数，
        //   测试注入固定目录锁定 join(dir,.claude,skills) 收录。
        Path addDir = tempDir.resolve("add-dir");
        Path addSkills = Files.createDirectories(addDir.resolve(".claude").resolve("skills"));
        detector.setProjectDir(tempDir.resolve("no-proj")); // 隔离 project 源
        detector.setAdditionalDirectoriesSupplier(() -> List.of(addDir.toString()));

        assertThat(detector.getWatchablePaths())
            .as("P2-21：--add-dir/.claude/skills 必须被监听（CC :224-232）")
            .contains(addSkills.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("R-1: 非法附加目录 env 值（含 NUL）→ getWatchablePaths 跳过不抛异常，合法值仍监听")
    void getWatchablePaths_invalidAdditionalDirValue_skipsWithoutThrowing() throws Exception {
        // WHY（R-1 健壮性 · 规则九）：additionalDirectoriesSupplier 供源自 env（--add-dir 等价），
        //   Path.of 对含 NUL / Windows 非法字符的值抛 InvalidPathException —— 旧实现无 try/catch，
        //   initialize()（:301）异常逃逸 → watcher 崩溃。CC skillChangeDetector.ts:224-232 逐路径
        //   try/catch 跳过垃圾值 → Java 同样跳过非法值，剩余合法附加目录仍被监听。
        Path addDir = tempDir.resolve("add-dir");
        Path addSkills = Files.createDirectories(addDir.resolve(".claude").resolve("skills"));
        detector.setProjectDir(tempDir.resolve("no-proj")); // 隔离 project 源
        String invalid = "add-dir" + (char) 0 + "invalid"; // NUL → InvalidPathException（Windows/Unix 均非法）
        detector.setAdditionalDirectoriesSupplier(() -> List.of(invalid, addDir.toString()));

        List<Path> paths = detector.getWatchablePaths(); // 不得抛异常
        assertThat(paths)
            .as("非法值跳过，合法 --add-dir/.claude/skills 仍被监听")
            .contains(addSkills.toAbsolutePath().normalize());
        assertThat(paths.stream().noneMatch(p -> p.toString().indexOf((char) 0) >= 0))
            .as("非法 NUL 值绝不进入监听路径集（Path.of 抛异常被跳过）")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 深度 2 子目录补注册 — initialize 后新建技能目录仍触发
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("initialize 后新建 skillName 目录 → 内部 SKILL.md 变更触发 reload (CC :113 depth:2)")
    void newSkillSubdir_afterInitialize_isRegisteredAndReloads() throws Exception {
        // WHY: chokidar depth:2 自动 watch 新建目录 (:113); WatchService 非递归, 需在
        //       ENTRY_CREATE 目录事件时补注册, 否则 new-skill/SKILL.md 的变更永远收不到.
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Files.createDirectories(skillsRoot);

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        detector.setSkillRegistry(registry);
        detector.setTelemetry(new Telemetry());
        detector.initialize();

        Path newSkillDir = skillsRoot.resolve("new-skill");
        Files.createDirectories(newSkillDir);
        Thread.sleep(200); // 等 directory-watcher 事件循环处理 ENTRY_CREATE 目录 → 递归注册 new-skill
        Files.writeString(newSkillDir.resolve("SKILL.md"), "---\nname: new-skill\n---\nhi");

        assertThat(awaitTrue(5000, () -> registry.refreshCalls.get() >= 1))
            .as("新建子目录补注册后, 内部 SKILL.md 变更必须触发 reload")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. dispose 后事件不再触发
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("dispose 后文件变更不再触发 reload (CC :146-164)")
    void dispose_stopsFurtherEvents() throws Exception {
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Path skillDir = skillsRoot.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent");

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        detector.setSkillRegistry(registry);
        detector.setTelemetry(new Telemetry());
        detector.initialize();

        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent v2");
        assertThat(awaitTrue(5000, () -> registry.refreshCalls.get() >= 1))
            .as("dispose 前 watcher 必须工作 (基线)")
            .isTrue();

        int baseline = registry.refreshCalls.get();
        detector.dispose();
        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent v3");
        Thread.sleep(800);

        assertThat(registry.refreshCalls.get())
            .as("dispose 后事件不得再触发 reload")
            .isEqualTo(baseline);
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. signal 多订阅者 — subscribe 返回退订句柄
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("subscribe 返回退订句柄; 退订后 emit 不再通知 (CC :169 signal.ts)")
    void subscribe_returnsUnsubscribeHandle() throws Exception {
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Path skillDir = skillsRoot.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent");

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        detector.setSkillRegistry(registry);
        detector.setTelemetry(new Telemetry());
        AtomicInteger emitCalls = new AtomicInteger();
        Runnable unsub = detector.subscribe(emitCalls::incrementAndGet);
        detector.initialize();

        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent v2");
        assertThat(awaitTrue(5000, () -> emitCalls.get() >= 1))
            .as("订阅者必须先收到 emit (CC :277 skillsChanged.emit)")
            .isTrue();

        unsub.run(); // CC subscribe 返回的退订函数
        int before = emitCalls.get();
        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent v3");
        assertThat(awaitTrue(5000, () -> registry.refreshCalls.get() >= 2))
            .as("第二次变更 refresh 仍触发 (watcher 未停)")
            .isTrue();
        assertThat(emitCalls.get())
            .as("退订后 emit 不再通知该订阅者")
            .isEqualTo(before);
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. debounce — 写稳定空闲窗口 (P3-33 · CC awaitWriteFinish 语义 R2I-DEC-12/C-18)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("debounce: 稳定窗口未满不触发, 窗口满后恰一次 reload (P3-33 onIdle 空闲窗口 = CC awaitWriteFinish)")
    void debounce_singleWrite_noReloadBeforeWindow() throws Exception {
        // WHY (P3-33 · 规则九): directory-watcher onIdle 空闲窗口替代自实现 awaitWriteFinish ——
        //   文件系统空闲 stabilityThreshold(默认 1000ms, CC :27) 后才触发热更新. 单次写入在窗口
        //   未满时不得 reload; 窗口满后恰一次 (长写不得放大 reload 次数, C-18: 放大 ConfigChange
        //   hook 副作用).
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Path skillDir = skillsRoot.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: my-skill\n---\nv0");

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        detector.setSkillRegistry(registry);
        detector.setTelemetry(new Telemetry());
        // 覆盖为可测窗口: debounce 空闲窗口 500ms (P3-33 testOverrides.stabilityThreshold)
        detector.resetForTesting(Map.of("stabilityThreshold", 500L));
        detector.initialize();

        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent v1");

        // debounce 窗口 (500ms) 未满 → 不得触发 reload
        Thread.sleep(250);
        assertThat(registry.refreshCalls.get())
            .as("debounce 空闲窗口未满时不得触发 reload (directory-watcher onIdle, CC awaitWriteFinish 语义)")
            .isZero();

        // 窗口满 (500ms) 过后 → 恰好一次 reload
        assertThat(awaitTrue(5000, () -> registry.refreshCalls.get() >= 1))
            .as("文件写稳定后必须触发 reload (P3-33 debounce → reload)")
            .isTrue();
        Thread.sleep(700);
        assertThat(registry.refreshCalls.get())
            .as("单次写入稳定后只触发一次 reload")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("debounce: 分批写入合并为一次 reload (C-18 长写放大, P3-33)")
    void debounce_chunkedWrites_singleReload() throws Exception {
        // WHY (P3-33 · 规则九): 编辑器/git 长写 = 多次 ENTRY_MODIFY; onIdle debounce 空闲窗口
        //   使 reload 延后至文件系统稳定 (awaitWriteFinish 等价), 多批写入合并为一次 reload —
        //   不得每批一次 (放大 ConfigChange hook 副作用).
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Path skillDir = skillsRoot.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: my-skill\n---\nchunk0");

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        detector.setSkillRegistry(registry);
        detector.setTelemetry(new Telemetry());
        // debounce 窗口 700ms: 写入总跨度 240ms < 窗口 → 稳定前任何链都不得 resolve;
        // 每批写入都 cancel+重排 debounce → 全部稳定后 (最后一批 + 700ms) 恰一次 reload (合并语义).
        detector.resetForTesting(Map.of("stabilityThreshold", 700L));
        detector.initialize();

        // 三批写入, 每批间隔 120ms (均在 debounce 窗口内)
        Files.writeString(skillMd, "---\nname: my-skill\n---\nchunk1 第一批");
        Thread.sleep(120);
        Files.writeString(skillMd, "---\nname: my-skill\n---\nchunk2 第二批更长内容");
        Thread.sleep(120);
        Files.writeString(skillMd, "---\nname: my-skill\n---\nchunk3 第三批更长更长内容");

        // 写入期尚未结束/窗口未满 (t≈500 < 最早可 resolve 的 ~940) → 不得触发 reload
        Thread.sleep(260);
        assertThat(registry.refreshCalls.get())
            .as("分批写入期间不得触发 reload (debounce 空闲窗口)")
            .isZero();

        // 全部稳定后 → 恰一次 reload (debounce 合并)
        assertThat(awaitTrue(5000, () -> registry.refreshCalls.get() >= 1))
            .as("分批写入稳定后必须触发 reload")
            .isTrue();
        Thread.sleep(900);
        assertThat(registry.refreshCalls.get())
            .as("debounce 空闲窗口 → 多批写入只触发一次 reload (C-18)")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("debounce: 无文件变更（纯空闲）→ 空路径 reload 不 refresh（P3-33 空路径守卫）")
    void debounce_idleWithoutChanges_noRefresh() throws Exception {
        // WHY (P3-33 · 规则九): directory-watcher 事件循环 poll 空 → onIdle 触发 debounce —— 监听
        //   开始后若一直无文件变更，onIdle 仍会排定一次 reload（纯空闲）。旧 scheduleReload 只由
        //   handleChange 触发，路径恒非空；新 onIdle 触发场景下 reload 若无空路径守卫会无谓地
        //   refresh + emit（对全量技能目录做一次空重载）。空路径守卫保证纯空闲不产生副作用。
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Files.createDirectories(skillsRoot);

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        detector.setSkillRegistry(registry);
        detector.setTelemetry(new Telemetry());
        AtomicInteger emitCalls = new AtomicInteger();
        detector.subscribe(emitCalls::incrementAndGet);
        detector.initialize();

        // 等超过 debounce 窗口 (100ms 覆盖) → 纯空闲触发的空路径 reload 必须被守卫拦截
        Thread.sleep(600);
        assertThat(registry.refreshCalls.get())
            .as("纯空闲（无文件变更）不得触发 refresh（P3-33 空路径守卫）")
            .isZero();
        assertThat(emitCalls.get())
            .as("纯空闲不得 emit 订阅者")
            .isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 8. bare 门控 — run() 跳过 watcher 初始化 (R2I-DEC-13)
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("bare 模式 → run() 跳过 watcher 初始化; 非 bare → 正常启动 (CC main.tsx:423-425)")
    void bareMode_run_skipsWatcherInitialization() throws Exception {
        // WHY: CC main.tsx:423-425 —— if (!isBareMode()) { void skillChangeDetector.initialize(); }.
        //       bare 模式 (CLAUDE_CODE_SIMPLE / --bare, envUtils.ts:60-65) 下脚本调用无
        //       "用户输入窗口", watcher 是纯开销 → 跳过. 判定可注入 (SkillsLoader.isBareMode 同款).
        Path skillsRoot = tempDir.resolve(".claude").resolve("skills");
        Path skillDir = skillsRoot.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent");

        RecordingSkillRegistry registry = new RecordingSkillRegistry(skillsRoot.toString());
        detector.setSkillRegistry(registry);
        detector.setTelemetry(new Telemetry());

        // bare=true → run() 跳过 initialize → watcher 未启动 → 文件变更不触发 reload
        detector.setBareModeSupplier(() -> true);
        assertThat(detector.isBareMode())
            .as("注入的 bare 判定必须生效 (SkillsLoader.isBareMode 同款可注入)")
            .isTrue();
        detector.run(null);

        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent v2");
        Thread.sleep(800);
        assertThat(registry.refreshCalls.get())
            .as("bare 模式 run() 跳过 watcher 初始化 → 文件变更不触发 reload (CC main.tsx:423-425)")
            .isZero();

        // bare=false → run() 正常 initialize → watcher 工作 → 变更触发 reload
        detector.setBareModeSupplier(() -> false);
        assertThat(detector.isBareMode())
            .as("注入判定切回非 bare")
            .isFalse();
        detector.run(null);

        Files.writeString(skillMd, "---\nname: my-skill\n---\ncontent v3");
        assertThat(awaitTrue(5000, () -> registry.refreshCalls.get() >= 1))
            .as("非 bare run() 正常启动 watcher → 变更触发 reload (CC main.tsx:424)")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════
    // 9. SU-△-1: resetSentSkillNames 实装清理已注册 sentSkillNames
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("resetSentSkillNames 清理 sentSkillNames + 复位 suppressNext（CC attachments.ts:2612-2615）")
    void resetSentSkillNames_clearsRegisteredSentSkillNames() {
        // WHY: 旧实现 resetSentSkillNames 仅 debug 日志（no-op）—— skill 文件变更后 per-run
        //      sentSkillNames 去重缓存不清，下一轮 skill_listing 不重发已改技能。CC :2612-2615
        //      sentSkillNames.clear() + suppressNext=false 是<b>两个动作</b>：前者清去重缓存、
        //      后者取消 pending 的 resume 抑制。早期实装只做 clear 漏了 suppressNext=false，
        //      却在本测试/日志/JavaDoc 三处宣称"已对齐"（规则十二 · 显式失败）。本测试锁定
        //      两个动作都实装：sentSkillNames 清空 + suppressNext 复位为 false。
        Map<String, Set<String>> sent = new ConcurrentHashMap<>();
        sent.put("", java.util.HashSet.newHashSet(1));
        sent.get("").add("skill-a");
        AtomicBoolean suppressNext = new AtomicBoolean(true); // resume 抑制 pending 态
        SkillChangeDetector.registerSentSkillNames(sent);
        SkillChangeDetector.registerSuppressNextSkillListing(suppressNext);
        try {
            detector.resetSentSkillNames();
            assertThat(sent).as("resetSentSkillNames 必须清空已注册 sentSkillNames（CC clear 语义）").isEmpty();
            assertThat(suppressNext.get())
                .as("resetSentSkillNames 必须复位 suppressNext 为 false（CC suppressNext=false，否则 resume 抑制在 clear 后仍吞掉下一轮 listing）")
                .isFalse();
        } finally {
            SkillChangeDetector.unregisterSentSkillNames(sent);
            SkillChangeDetector.unregisterSuppressNextSkillListing(suppressNext);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 9.5 [FIX-B3 unregister 生产接线] unregister 移除注册表 entry → reset 不再触碰
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九 · 验证意图): 静态注册表（IdentityHashMap 身份去重）此前生产只有 register
     * 零 unregister —— 每会话 1 Map + 1 AtomicBoolean 永久被静态 Set 持有（强引用泄漏）。
     * 本测试锁定配对注销语义：unregister 后该 entry 必须从注册表移除，后续
     * {@code resetSentSkillNames()} 不得再触碰已注销会话的 per-run 状态（泄漏消除 → skill
     * 文件变更只清活跃会话，不再跨会话持有死引用）。
     */
    @Test
    @DisplayName("unregister 移除注册表 entry → resetSentSkillNames 不再触碰已注销会话（泄漏消除）")
    void unregister_removesEntry_resetNoLongerTouches() {
        Map<String, Set<String>> sent = new ConcurrentHashMap<>();
        sent.put("", java.util.HashSet.newHashSet(1));
        sent.get("").add("skill-a");
        AtomicBoolean suppressNext = new AtomicBoolean(true); // resume 抑制 pending 态
        SkillChangeDetector.registerSentSkillNames(sent);
        SkillChangeDetector.registerSuppressNextSkillListing(suppressNext);
        // 会话结束成对注销（对齐 LlmAgentLoop.loop() finally 生产接线）
        SkillChangeDetector.unregisterSentSkillNames(sent);
        SkillChangeDetector.unregisterSuppressNextSkillListing(suppressNext);

        detector.resetSentSkillNames();

        assertThat(sent)
            .as("unregister 后注册表必须移除该 entry → resetSentSkillNames 不得再清空已注销会话的 sentSkillNames（强引用泄漏已消除）")
            .isNotEmpty();
        assertThat(sent.get(""))
            .as("已注销会话的 sentSkillNames 内容必须保留（reset 不再触碰死会话状态）")
            .contains("skill-a");
        assertThat(suppressNext.get())
            .as("已注销会话的 suppressNext 必须保留 pending 态（reset 不再复位死会话状态）")
            .isTrue();
    }

    // 10. FIX-B3 SU-△-1: AgentLoopContextFactory 生产接线 → resetSentSkillNames 生效（拍板#5）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("factory.shared() 会话注册 sentSkillNames/suppressNext → resetSentSkillNames 生产生效（拍板#5）")
    void factoryWiredSharedSession_resetSentSkillNames_isEffective() {
        // WHY: NG-1 —— registerSentSkillNames/registerSuppressNextSkillListing 此前全仓仅测试调用，
        //      AgentLoopContextFactory 未接线 → 静态注册表生产恒空 → reload() 调 resetSentSkillNames
        //      遍历空集 = 生产 no-op，skill 文件变更后 per-run sentSkillNames 不清、suppressNext 不复位。
        //      FIX-B3 在 factory.build() 接线后，生产创建会话即注册，reset 对 per-run 状态真正生效。
        AgentLoopContextFactory factory = new AgentLoopContextFactory();
        AgentLoopContext ctx = factory.shared(null); // 经 freshSession() 的 subagent/hook 路径
        AgentLoopContext.LoopSessionState session = ctx.sessionState();
        // 模拟已发送 skill + resume 抑制 pending 态
        session.sentSkillNames().computeIfAbsent("", k -> ConcurrentHashMap.newKeySet()).add("skill-a");
        session.suppressNextSkillListing().set(true);
        try {
            detector.resetSentSkillNames();
            assertThat(session.sentSkillNames())
                .as("接线后 shared() 会话 sentSkillNames 必须被 reset 清空（CC attachments.ts:2613 clear 语义，生产生效）")
                .isEmpty();
            assertThat(session.suppressNextSkillListing().get())
                .as("接线后 shared() 会话 suppressNext 必须复位 false（CC attachments.ts:2614，否则 resume 抑制吞掉 clear 后的重发）")
                .isFalse();
        } finally {
            SkillChangeDetector.unregisterSentSkillNames(session.sentSkillNames());
            SkillChangeDetector.unregisterSuppressNextSkillListing(session.suppressNextSkillListing());
        }
    }

    @Test
    @DisplayName("forSession 5 参重载（主循环 LlmAgentLoop 直传会话）同样注册 → reset 生产生效（拍板#5）")
    void factoryWiredMainLoopSession_resetSentSkillNames_isEffective() {
        // WHY: 主循环 LlmAgentLoop.run() 经 forSession 5 参重载传入 buildSessionStateFromInstance()
        //      新建的会话，**不经 freshSession()** —— 接线点必须落在 build()（funnel）而非仅
        //      freshSession()，否则主代理（最核心生产路径）sentSkillNames 仍不被 reset，决策目标
        //      「生产 resetSentSkillNames 生效」只解一半。
        AgentLoopContextFactory factory = new AgentLoopContextFactory();
        AgentLoopContext.LoopSessionState session = new AgentLoopContext.LoopSessionState(); // 等价 buildSessionStateFromInstance 新建
        AgentLoopContext ctx = factory.forSession("/topic/x", "sess-1", "msg-1", session, null);
        assertThat(ctx.sessionState()).as("5 参重载必须复用调用方传入会话").isSameAs(session);
        session.sentSkillNames().computeIfAbsent("", k -> ConcurrentHashMap.newKeySet()).add("skill-b");
        session.suppressNextSkillListing().set(true);
        try {
            detector.resetSentSkillNames();
            assertThat(session.sentSkillNames())
                .as("接线后主循环 5 参会话 sentSkillNames 必须被 reset 清空（skill 文件变更重发主代理 listing）")
                .isEmpty();
            assertThat(session.suppressNextSkillListing().get())
                .as("接线后主循环 5 参会话 suppressNext 必须复位 false")
                .isFalse();
        } finally {
            SkillChangeDetector.unregisterSentSkillNames(session.sentSkillNames());
            SkillChangeDetector.unregisterSuppressNextSkillListing(session.suppressNextSkillListing());
        }
    }

    /**
     * WHY (REG-SCD · 规则九): {@code REGISTERED_SENT_SKILL_NAMES} 原为
     * {@code ConcurrentHashMap.newKeySet()}，其 {@code add} 走 {@code Map.equals}（内容相等）。
     * 两个<b>空</b> ConcurrentHashMap 内容相等（hashCode 均 0、equals 均 true）→ 第二个 add
     * 被去重吞掉。生产上会话 A 创建后尚未发送技能（空 Map 注册入表）、会话 B 创建时其空 Map
     * 因内容相等被去重（不注册）→ 会话 B 发送技能后其 Map 已非空，但注册表里只存了会话 A
     * 的空 Map → {@code resetSentSkillNames()} 清的是 A，B 的 sentSkillNames 不清 → skill
     * 文件变更后 B 的 listing 不重发（CC attachments.ts:2612-2615 clear 语义失效）。本测试
     * 锁定注册表按<b>身份(identity)</b>去重：先注册一个"泄漏的"空 Map，再走 factory.shared()
     * 注册另一个空 Map，二者身份不同必须都被注册，reset 后都清空。
     */
    @Test
    @DisplayName("[REG-SCD] 静态注册表按身份去重——已注册空 Map 不得吞掉后注册的另一空 Map（CC clear 语义）")
    void registerSentSkillNames_identityDedup_doesNotCollideEmptyMaps() {
        Map<String, Set<String>> leakedEmpty = new ConcurrentHashMap<>();
        SkillChangeDetector.registerSentSkillNames(leakedEmpty); // 模拟生产未 pop 且未 unregister 的会话

        AgentLoopContextFactory factory = new AgentLoopContextFactory();
        AgentLoopContext ctx = factory.shared(null); // 经 build() 注册另一个空 Map（identity 上不同对象）
        AgentLoopContext.LoopSessionState session = ctx.sessionState();
        session.sentSkillNames().computeIfAbsent("", k -> ConcurrentHashMap.newKeySet()).add("skill-a");
        session.suppressNextSkillListing().set(true);
        try {
            detector.resetSentSkillNames();
            assertThat(session.sentSkillNames())
                .as("身份注册表下 factory 会话 sentSkillNames 必须被 reset 清空（不得被内容相等的空 Map 吞掉，CC attachments.ts:2613 clear 语义）")
                .isEmpty();
            assertThat(session.suppressNextSkillListing().get())
                .as("factory 会话 suppressNext 必须复位 false（CC attachments.ts:2614）")
                .isFalse();
        } finally {
            SkillChangeDetector.unregisterSentSkillNames(leakedEmpty);
            SkillChangeDetector.unregisterSentSkillNames(session.sentSkillNames());
            SkillChangeDetector.unregisterSuppressNextSkillListing(session.suppressNextSkillListing());
        }
    }

    /** 记录 refresh 调用次数的 SkillRegistry（校验 SkillChangeDetector 联动）. */
    private static final class RecordingSkillRegistry extends SkillRegistry {
        final AtomicInteger refreshCalls = new AtomicInteger();

        RecordingSkillRegistry(String skillsRoot) {
            super(skillsRoot);
        }

        @Override
        public void refresh() {
            refreshCalls.incrementAndGet();
            super.refresh();
        }
    }

    private static boolean awaitTrue(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
