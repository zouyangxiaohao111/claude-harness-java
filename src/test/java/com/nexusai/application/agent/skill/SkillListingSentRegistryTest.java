package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [skill-listing-cc-align 2026-09-10] {@link SkillListingSentRegistry} 决策矩阵实测。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图）</b>：CC {@code sentSkillNames} 是进程级 module-scope Map
 * （attachments.ts:2676），nexusai 旧实现把它存在 per-run {@code LoopSessionState} → 每 run isInitial=true、
 * newSkills=全量（delta 机器被架空）。本类钉死新注册表的<b>六种情形分支与判据</b>（a–g），任何分支被改错
 * 都会令某用例 RED：
 * <ul>
 *   <li>a. 全新会话首 run（!resume）→ 整份注入；</li>
 *   <li>b. 同 JVM 第二 run（resume 已初始化）→ 不重复注入；</li>
 *   <li>c. 中途新技能 → 只发增量（不重发整份）；</li>
 *   <li>d. JVM 重启（reset）→ resume 且未初始化 → 抑制（不重注）；</li>
 *   <li>e. /clear（removeSession）→ 即便 resume=true 也重发整份；</li>
 *   <li>f. 同 JVM 两会话 → sent 互不串扰（sessionId 进键）；</li>
 *   <li>g. compact 不触碰注册表（经真实压缩清理入口 {@code PostCompactCleanup.runPostCompactCleanup()} 驱动）
 *       → 状态保持、不重注；skill 文件变更（resetSentAllSessions）→ 重发；</li>
 *   <li>h. 首 run 空候选（零技能）仍消费 suppress 置 initialized → 后续技能出现必须注入整份，
 *       <b>不得</b>被误判为冷 resume 而永久 suppress（2026-09-10 对抗核验 high 回归）；</li>
 *   <li>边界. removeSession（/clear）保留 initialized（= CC suppressNext=false，消 CLEARED 单消费者竞态）；
 *       removeSessionEntries（会话删除纯清理）清双键不置 CLEARED；null sessionId 不抛 NPE（2026-09-10 收尾轮）。</li>
 *   <li>i. removeAgentKey（P2-12）· 单键回收只命中目标 agentKey、复现时落抑制分支、不触碰会话级 CLEARED、
 *       幂等且 null 安全 —— 子代理 / hook agent 结束点的槽位回收语义。</li>
 * </ul>
 */
class SkillListingSentRegistryTest {

    private String sessionA;
    private String sessionB;

    @BeforeEach
    void setUp() {
        SkillListingSentRegistry.reset(); // 模拟 JVM 起点的干净态
        sessionA = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        sessionB = "sess-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        SkillListingSentRegistry.reset();
    }

    // ── a. 全新会话首 run → 整份注入 ──

    @Test
    @DisplayName("a. 全新会话首 run（!resume）→ 注入整份 + isInitial=true + 置 initialized")
    void freshRun_injectsFull() {
        List<String> names = List.of("commit", "review", "test");

        SkillListingSentRegistry.Decision d =
            SkillListingSentRegistry.decide(sessionA, "", names, false);

        assertThat(d.names()).as("首 run 必须注入整份").containsExactly("commit", "review", "test");
        assertThat(d.isInitial()).as("首份 isInitial=true（CC sent.size===0）").isTrue();
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, ""))
            .as("注入后必须置 initialized（否则第二 run 会被误判为 JVM 重启而抑制）").isTrue();
    }

    // ── b. 同 JVM 第二 run → 不重复注入 ──

    @Test
    @DisplayName("b. 同 JVM 第二 run（resume 且已初始化，技能未变）→ 不重复注入")
    void secondRunSameJvm_noReinject() {
        List<String> names = List.of("commit", "review");
        SkillListingSentRegistry.decide(sessionA, "", names, false); // 首 run

        SkillListingSentRegistry.Decision d =
            SkillListingSentRegistry.decide(sessionA, "", names, true); // 第二 run

        assertThat(d.names()).as("技能未变 → 第二 run 不得重复注入").isEmpty();
    }

    // ── c. 中途出现新技能 → 只发增量 ──

    @Test
    @DisplayName("c. 中途出现新技能 → 只发增量（不重发整份）")
    void newSkillOnlyDelta() {
        SkillListingSentRegistry.decide(sessionA, "", List.of("commit", "review"), false);

        SkillListingSentRegistry.Decision d = SkillListingSentRegistry.decide(
            sessionA, "", List.of("commit", "review", "new-skill"), true);

        assertThat(d.names()).as("仅注入新增技能").containsExactly("new-skill");
        assertThat(d.isInitial()).as("非首份").isFalse();
    }

    // ── d. JVM 重启 → 不重注 ──

    @Test
    @DisplayName("d. JVM 重启（reset 注册表）→ resume 且未初始化 → 抑制（不重注）")
    void jvmRestart_noReinject() {
        List<String> names = List.of("commit", "review");
        SkillListingSentRegistry.decide(sessionA, "", names, false); // 旧进程已注入

        SkillListingSentRegistry.reset(); // JVM 重启：进程内存清空

        SkillListingSentRegistry.Decision d =
            SkillListingSentRegistry.decide(sessionA, "", names, true); // 老会话 resume

        assertThat(d.names()).as("JVM 重启后 resume → 转录已含清单，不得重注（CC suppress 分支）").isEmpty();
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, ""))
            .as("抑制分支必须置 initialized（下一 run 才走增量）").isTrue();
    }

    // ── e. /clear → 重发整份 ──

    @Test
    @DisplayName("e. /clear（removeSession）→ 即便 resume=true 也重发整份（CC resetSentSkillNames）")
    void clear_reinjectsFull() {
        List<String> names = List.of("commit", "review");
        SkillListingSentRegistry.decide(sessionA, "", names, false);
        assertThat(SkillListingSentRegistry.decide(sessionA, "", names, true).names()).isEmpty(); // 稳定态

        SkillListingSentRegistry.removeSession(sessionA); // /clear 时点

        SkillListingSentRegistry.Decision d =
            SkillListingSentRegistry.decide(sessionA, "", names, true);
        assertThat(d.names()).as("/clear 后必须重发整份（即使会话仍有历史）").containsExactly("commit", "review");
        assertThat(d.isInitial()).as("重发整份 = isInitial").isTrue();
    }

    /**
     * [2026-09-10 /clear 收敛] {@code supersedesPriorRows} 只对 /clear 分支置真 —— 落库侧据此
     * 「先插后删」把 DB 收敛为唯一一份。
     *
     * <p><b>WHY（CLAUDE.md 规则 9）</b>：只有 /clear 时 CC 清空 messages → 旧清单附件消失（唯一一份）。
     * 增量清单 / skill 变更重发都必须<b>保留</b>旧行（CC 累积：增量清单依赖旧整份提供未变技能的信息）。
     * 若无条件置真 → 中途新增技能时 ChatService 删掉旧整份 → 模型丢失未变技能（严重回归）。
     * RED：把任一非 /clear 分支的第三参改成 true → 对应断言红。
     */
    @Test
    @DisplayName("e2. supersedesPriorRows 只对 /clear 置真（增量/首注/技能变更均为 false）")
    void supersedesPriorRows_onlyOnClear() {
        List<String> names = List.of("commit", "review");

        // 首注（全新会话 !resume）：无旧行可删 → false
        SkillListingSentRegistry.Decision fresh =
            SkillListingSentRegistry.decide(sessionB, "", names, false);
        assertThat(fresh.supersedesPriorRows()).as("全新会话首注：DB 无旧行，无需删").isFalse();

        // 稳定态（同集合二次）→ 不注入，supersede=false
        assertThat(SkillListingSentRegistry.decide(sessionB, "", names, true).supersedesPriorRows())
            .as("无增量 → 不注入 → 不删").isFalse();

        // 增量（新增 new-skill）→ 必须保留旧整份 → false
        SkillListingSentRegistry.Decision delta = SkillListingSentRegistry.decide(sessionB, "",
            List.of("commit", "review", "new-skill"), true);
        assertThat(delta.names()).containsExactly("new-skill");
        assertThat(delta.supersedesPriorRows())
            .as("增量只含新技能名，删旧行会丢未变技能 → 必须 false").isFalse();

        // skill 文件变更重发整份 → CC 亦保留旧附件累积 → false
        SkillListingSentRegistry.resetSentAllSessions();
        assertThat(SkillListingSentRegistry.decide(sessionB, "", names, true).supersedesPriorRows())
            .as("skill 变更重发整份：CC 保留旧附件累积 → false").isFalse();

        // /clear → true
        SkillListingSentRegistry.removeSession(sessionA);
        assertThat(SkillListingSentRegistry.decide(sessionA, "", names, true).supersedesPriorRows())
            .as("/clear 重发整份 → 收敛为唯一一份").isTrue();
    }

    // ── f. 同 JVM 两会话互不串扰 ──

    @Test
    @DisplayName("f. 同 JVM 两会话 → sent 互不串扰（键含 sessionId · 非 CC 偏离）")
    void twoSessionsIsolated() {
        List<String> names = List.of("commit");
        SkillListingSentRegistry.decide(sessionA, "", names, false); // A 首注

        // B 首注不得被 A 的 sent 吞掉
        SkillListingSentRegistry.Decision b =
            SkillListingSentRegistry.decide(sessionB, "", names, false);
        assertThat(b.names()).as("会话 B 必须独立首注（sessionId 进键）").containsExactly("commit");

        // A 第二 run → 空（未被 B 影响）
        assertThat(SkillListingSentRegistry.decide(sessionA, "", names, true).names()).isEmpty();
    }

    // ── g. compact 不重注（真实压缩清理入口驱动 · 2026-09-10 收尾轮：修同义反复）──

    @Test
    @DisplayName("g. 真实 compact 清理入口 runPostCompactCleanup() 不得 reset 注册表 → 状态保持、不重注")
    void compactRealCleanup_doesNotResetRegistry() {
        List<String> names = List.of("commit", "review");
        SkillListingSentRegistry.decide(sessionA, "", names, false); // 首注 → initialized + sent=全量
        SkillListingSentRegistry.decide(sessionA, "", names, true);  // 稳定态

        // 驱动真实「压缩后清理」入口（与 /clear、auto/reactive compact 同一 CC postCompactCleanup.ts 序列）。
        // WHY（CLAUDE.md 规则 9）：上一版把「compact」建模成「什么都不调用」再断言不重注 —— 等于自证：
        //   若有人日后在 PostCompactCleanup（或 compact 管线）里加 SkillListingSentRegistry.reset() /
        //   resetSentAllSessions()（正是 CC compact.ts:548-553 明令禁止的 reset），该测试照样全绿。
        //   本版真实调用入口并断言「注册表状态未被触碰」：加 reset() → isInitialized 断言 RED；
        //   加 resetSentAllSessions() → sent 清空 → 下一 decide 重发整份 → 第二条断言 RED。
        com.nexusai.application.agent.compact.PostCompactCleanup.runPostCompactCleanup();

        assertThat(SkillListingSentRegistry.isInitialized(sessionA, ""))
            .as("compact 清理不得清 INITIALIZED（加 reset() 即 RED）").isTrue();
        assertThat(SkillListingSentRegistry.decide(sessionA, "", names, true).names())
            .as("compact 后不重注（加 resetSentAllSessions() → sent 清空重发整份 → 即 RED）").isEmpty();
    }

    @Test
    @DisplayName("g2. skill 文件变更（resetSentAllSessions）→ sent 清空 → 重发整份")
    void skillFileChange_reinjectsFull() {
        List<String> names = List.of("commit", "review");
        SkillListingSentRegistry.decide(sessionA, "", names, false);
        assertThat(SkillListingSentRegistry.decide(sessionA, "", names, true).names()).isEmpty(); // 稳定态

        SkillListingSentRegistry.resetSentAllSessions(); // skillChangeDetector.ts:276 reset

        assertThat(SkillListingSentRegistry.decide(sessionA, "", names, true).names())
            .as("skill 文件变更后必须重发（sent 已清 → newSkills=全量）")
            .containsExactly("commit", "review");
    }

    // ── 边界：空候选 / /clear 保留 initialized / 会话删除纯清理 / null sessionId ──

    @Test
    @DisplayName("边界: 空候选 → 不注入；removeSession（/clear）清 sent 保留 initialized")
    void edgeCases() {
        assertThat(SkillListingSentRegistry.decide(sessionA, "", List.of(), false).names())
            .as("空候选 → 不注入（CC allCommands 空）").isEmpty();

        SkillListingSentRegistry.decide(sessionA, "", List.of("commit"), false);

        SkillListingSentRegistry.removeSession(sessionA);
        // [2026-09-10 收尾轮] removeSession（/clear）清 SENT 但保留 INITIALIZED = CC resetSentSkillNames 的
        //   sentSkillNames.clear() + suppressNext=false。已初始化槽因此仍重发整份（sent 空 → isInitial），
        //   使「先 decide 的 agentKey 抢走 CLEARED」不再致命（finding：CLEARED 单消费者）。
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, ""))
            .as("/clear 保留 initialized（= CC suppressNext=false）").isTrue();
        assertThat(SkillListingSentRegistry.decide(sessionA, "", List.of("commit"), true).names())
            .as("/clear 后 sent 已清 → 下一 resume pass 仍重发整份").containsExactly("commit");
    }

    @Test
    @DisplayName("边界2: removeSessionEntries（会话删除纯清理）→ 清双键、不置 CLEARED、无残留")
    void removeSessionEntries_pureCleanup() {
        SkillListingSentRegistry.decide(sessionA, "", List.of("commit"), false);
        SkillListingSentRegistry.decide(sessionA, "sub-1", List.of("commit"), false);
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, "")).isTrue();

        SkillListingSentRegistry.removeSessionEntries(sessionA);

        assertThat(SkillListingSentRegistry.isInitialized(sessionA, ""))
            .as("删除路径必须清 INITIALIZED（回收槽位，防无界增长）").isFalse();
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, "sub-1")).isFalse();
        // 未置 CLEARED：删除后 decide 落 branch 3（resume 且未初始化 → 抑制）而非 branch 1 整份重发 ——
        //   证明删除路径没有残留「重发整份」意图（finding：SessionService.delete 写入的 CLEARED 永不被消费）。
        assertThat(SkillListingSentRegistry.decide(sessionA, "", List.of("commit"), true).names())
            .as("删除路径不得残留 CLEARED 类重发意图（否则被删 key 复现会整份重发）").isEmpty();
    }

    @Test
    @DisplayName("边界4: /clear 后子代理先 decide 消费 CLEARED，主线程仍必须重发整份（消 CLEARED 单消费者竞态）")
    void clear_subagentConsumesCleared_mainStillReinjectsFull() {
        List<String> names = List.of("commit", "review");
        SkillListingSentRegistry.decide(sessionA, "", names, false); // 主线程首注
        SkillListingSentRegistry.removeSession(sessionA);            // /clear（清 sent，保留 initialized）

        // 子代理（resume=false）先到：命中 CLEARED 分支、消费掉标记、拿整份
        assertThat(SkillListingSentRegistry.decide(sessionA, "sub-1", names, false).names())
            .as("子代理先到拿整份").containsExactly("commit", "review");

        // 主线程随后：CLEARED 已被消费，但 initialized 保留 + sent 已清 → branch 4 仍重发整份。
        //   （若 removeSession 连带清了 INITIALIZED，主线程会落 branch 3 抑制 → 本断言 RED）
        SkillListingSentRegistry.Decision main = SkillListingSentRegistry.decide(sessionA, "", names, true);
        assertThat(main.names())
            .as("finding：CLEARED 单消费者曾致主线程 /clear 后被抑制 —— 保留 initialized 后仍整份重发")
            .containsExactly("commit", "review");
        assertThat(main.isInitial()).as("整份重发 → isInitial=true").isTrue();
    }

    @Test
    @DisplayName("边界3: sessionId=null 不抛 NPE（CLEARED.remove(null) 曾致 doRun 静默不注入）")
    void nullSessionId_noNpe() {
        // 旧实现：CLEARED.remove(null) 抛 NPE（ConcurrentHashMap 不允许 null 键），被 doRun 的
        //   try/catch 吞成「静默不注入」（违反规则十二 fail loud）。本用例断言 null sessionId 走正常状态机。
        assertThat(SkillListingSentRegistry.decide(null, "", List.of("commit"), false).names())
            .as("null sessionId 首 run → 正常整份注入而非抛 NPE").containsExactly("commit");
        assertThat(SkillListingSentRegistry.decide(null, "", List.of(), true).names())
            .as("null sessionId 空候选分支（曾 NPE 的分支）→ 不注入且不抛").isEmpty();
        SkillListingSentRegistry.removeSessionEntries(null); // 亦不得抛
    }

    // ── h. 空候选首 run 不得永久抑制（2026-09-10 对抗核验 high 回归）──

    @Test
    @DisplayName("h. 首 run 零技能（空候选）→ 后续技能出现必须注入（不得永久 suppress）")
    void emptyFirstRun_doesNotPermanentlySuppress() {
        List<String> names = List.of("commit", "review");

        // run 1：全新会话首 run，catalog 为空（零技能 / 元数据未就绪 / filterToBundledAndMcp 后为空）
        SkillListingSentRegistry.Decision d1 =
            SkillListingSentRegistry.decide(sessionA, "", List.of(), false);
        assertThat(d1.names()).as("空候选 → 不注入").isEmpty();
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, ""))
            .as("空候选 pass 仍须消费 suppress 并置 initialized（CC attachments.ts:2791-2797 无条件消费）")
            .isTrue();

        // run 2：resume=true（转录已有历史）+ 技能出现 → 必须注入整份，而非落分支 3 抑制
        SkillListingSentRegistry.Decision d2 =
            SkillListingSentRegistry.decide(sessionA, "", names, true);
        assertThat(d2.names())
            .as("CC 此场景必然注入整份（sent 仍空 → isInitial=true）；修复前该会话被永久 suppress")
            .containsExactly("commit", "review");
        assertThat(d2.isInitial())
            .as("sent 为空 → isInitial 对齐 CC `sent.size()===0`（attachments.ts:2805）").isTrue();

        // run 3：稳定态 → 不重发
        assertThat(SkillListingSentRegistry.decide(sessionA, "", names, true).names()).isEmpty();
    }

    @Test
    @DisplayName("h2. 冷 resume 首 pass 空候选 → 消费 suppress（sent 空）→ 技能出现仍注入整份")
    void coldResumeEmptyFirstPass_thenSkillsAppear_injects() {
        SkillListingSentRegistry.reset(); // JVM 重启
        List<String> names = List.of("commit");

        // 冷 resume 首个 pass 候选为空（CC suppressNext 被消费、allCommands 空 → forEach 空转、sent 保持空）
        assertThat(SkillListingSentRegistry.decide(sessionA, "", List.of(), true).names()).isEmpty();
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, "")).isTrue();

        // 技能出现 → 注入整份（CC 下一次 pass sent.size()===0 → isInitial=true → 整份）
        SkillListingSentRegistry.Decision d =
            SkillListingSentRegistry.decide(sessionA, "", names, true);
        assertThat(d.names()).containsExactly("commit");
        assertThat(d.isInitial()).isTrue();
    }

    @Test
    @DisplayName("h3. 无 Skill 工具首 run：fresh(!resume) 补记 initialized → 后续注入；冷 resume 不补记 → 仍抑制")
    void markInitialized_freshOnly() {
        List<String> names = List.of("commit");

        // fresh（!resume）无 Skill 工具：调用方补记 = CC suppress=false 态 → 后续 pass 注入整份
        SkillListingSentRegistry.markInitialized(sessionA, "");
        SkillListingSentRegistry.Decision fresh =
            SkillListingSentRegistry.decide(sessionA, "", names, true);
        assertThat(fresh.names()).as("fresh 无工具 run 后技能出现 → 注入（CC fresh 进程 suppress=false）")
            .containsExactly("commit");
        assertThat(fresh.isInitial()).isTrue();

        // 冷 resume（resume=true）无 Skill 工具：调用方不补记 → 保持 suppress 待消费 → 首个 decide 抑制
        SkillListingSentRegistry.Decision cold =
            SkillListingSentRegistry.decide(sessionB, "", names, true);
        assertThat(cold.names()).as("冷 resume 无工具 run 不补记 → 首个 decide 仍走 suppress（转录已含清单）")
            .isEmpty();
        assertThat(SkillListingSentRegistry.isInitialized(sessionB, "")).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // i. P2-12 · 单键回收（removeAgentKey）—— 子代理 / hook agent 结束点
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("i1. removeAgentKey → 只回收指定 agentKey 槽（双键同清），不误伤主线程槽与其他 agentKey")
    void removeAgentKey_onlyTargetedSlot() {
        List<String> names = List.of("commit");
        String hookAgent = "hook-agent-" + UUID.randomUUID();
        SkillListingSentRegistry.decide(sessionA, "", names, false);           // 主线程
        SkillListingSentRegistry.decide(sessionA, "sub-1", names, false);      // 子代理
        SkillListingSentRegistry.decide(sessionA, hookAgent, names, false);    // hook agent
        assertThat(SkillListingSentRegistry.sessionSlotCount(sessionA))
            .as("前置：3 个 agentKey 各占一槽").isEqualTo(3);

        SkillListingSentRegistry.removeAgentKey(sessionA, hookAgent);

        // WHY 该断言重要：hook agent 每次调用都是新 UUID（ExecAgentHook.generateHookAgentId），
        //   若不按结束点回收，槽位随「hook 调用次数」在常驻 JVM 无界增长（审计 P2-12）。
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, hookAgent))
            .as("目标 agentKey 的 INITIALIZED 必须一并回收（否则后续 decide 会因 sent 空整份重发）").isFalse();
        assertThat(SkillListingSentRegistry.sessionSlotCount(sessionA))
            .as("槽位数必须减一（SENT 条目确实被移除）").isEqualTo(2);
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, "")).as("主线程槽不受影响").isTrue();
        assertThat(SkillListingSentRegistry.isInitialized(sessionA, "sub-1")).as("其他 agentKey 槽不受影响").isTrue();
    }

    @Test
    @DisplayName("i2. removeAgentKey → 复现同一 agentKey 时落抑制分支（不整份重发），且不触碰会话级 CLEARED")
    void removeAgentKey_reusedKeySuppressed_clearedUntouched() {
        List<String> names = List.of("commit");
        String sub = "sub-reused";
        SkillListingSentRegistry.decide(sessionA, sub, names, false); // 子代理首注 → sent=全量 + initialized
        assertThat(SkillListingSentRegistry.decide(sessionA, sub, names, true).names())
            .as("稳定态：无增量 → 不注入").isEmpty();

        SkillListingSentRegistry.removeAgentKey(sessionA, sub);

        // 复现同 agentKey（resume 复用 agentId 路径）→ 分支 3 抑制：转录已含清单，不重复注入。
        //   若只清 SENT 而保留 INITIALIZED，则会走「sent 空 → isInitial=true → 整份重发」（~4K token 冗余）。
        assertThat(SkillListingSentRegistry.decide(sessionA, sub, names, true).names())
            .as("回收后复现同一 agentKey 必须抑制（与回收前稳定态的可观测结果一致：不注入）").isEmpty();

        // CLEARED 是会话级意图：单 agent 结束不得消费/清除它（/clear 后所有槽仍须整份重发）。
        SkillListingSentRegistry.removeSession(sessionA); // /clear
        SkillListingSentRegistry.removeAgentKey(sessionA, "another-agent");
        assertThat(SkillListingSentRegistry.decide(sessionA, "", names, true).names())
            .as("removeAgentKey 不得清掉会话级 CLEARED（/clear 整份重发语义必须保持）")
            .containsExactly("commit");
    }

    @Test
    @DisplayName("i3. removeAgentKey 幂等 + null 入参不抛（与 decide/keyOf 同口径）")
    void removeAgentKey_idempotentAndNullSafe() {
        SkillListingSentRegistry.decide(sessionA, "", List.of("commit"), false);

        SkillListingSentRegistry.removeAgentKey(sessionA, "never-existed"); // 未知键 no-op
        SkillListingSentRegistry.removeAgentKey(null, null);                 // 不得抛 NPE
        assertThat(SkillListingSentRegistry.sessionSlotCount(sessionA)).isEqualTo(1);

        SkillListingSentRegistry.removeAgentKey(sessionA, "");
        SkillListingSentRegistry.removeAgentKey(sessionA, "");               // 二次调用幂等
        assertThat(SkillListingSentRegistry.sessionSlotCount(sessionA)).isZero();
    }
}
