package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-4 / P4-5 · autoCompact tracking（压缩判据）生命周期 CC 契约测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>：本类锁的是「该压一次」与「不该再压」两侧判据的
 * <b>生命周期</b>。历史事故「同一会话反复 autoCompact」的直接表现就是这套判据错乱：要么把别的会话的
 * 状态当成自己的（该压的不压 / 不该压的反复压），要么把上一段生命周期的判据带进新一段。三条被锁的
 * CC 语义（逐条给 CC 真源）：
 * <ol>
 *   <li><b>跟踪状态归属</b>：CC {@code autoCompactTracking} 是 {@code query()} 调用的循环局部 State
 *       （query.ts:264 声明 → :425 每 query() 初始化 undefined → :555 {@code let tracking =
 *       autoCompactTracking}），随 query() 创建/丢弃。Java 端 AutoCompactor 是<b>单例 bean</b>，
 *       状态挂在实例字段上则多会话共享 JVM 时 A 的 {@code consecutiveFailures}/{@code compacted}
 *       会污染 B（B 被无故熔断短路 = 上下文不再被压；或继承 {@code compacted=true}）。</li>
 *   <li><b>justCompacted 是 per-iteration</b>：CC {@code const { compactionResult, consecutiveFailures }
 *       = await deps.autocompact(...)}（query.ts:652）在 {@code while(true)} <b>迭代体内</b>声明；
 *       消费点 query.ts:827（blocking-limit 预检 {@code !compactionResult}）与 query.ts:852
 *       （预测性 autocompact {@code !compactionResult && isAutoCompactEnabled()}）均为本迭代语义。</li>
 *   <li><b>reactive compact 成功后复位</b>：CC {@code autoCompactTracking: undefined}
 *       （query.ts:1442，reactive_compact_retry 的 next State）。</li>
 * </ol>
 *
 * <p><b>生产调用契约</b>：失败计数经返回值承载，由调用方写回跟踪状态（CC autoCompact.ts:341-349
 * 返回 {@code consecutiveFailures} + query.ts:536-542 调用方写回）。本测试的
 * {@link #callLikeLlmAgentLoop} 复刻 {@code LlmAgentLoop} 的调用 + 写回契约，故断言对象与生产一致。
 *
 * <p><b>RED 条件（变异自证）</b>：
 * <ul>
 *   <li>5 参 {@code autoCompactIfNeeded} 改回读实例字段 {@code defaultTracking} ——
 *       {@link #multiSessionIsolation_circuitBreakerNotShared()} 与
 *       {@link #multiSessionIsolation_productionPathDoesNotTouchInstanceField()} 红。</li>
 *   <li>{@code justCompacted} 还原为 loop() 方法级（do-while 外声明）——
 *       {@link #justCompacted_declaredPerIteration()} 红。</li>
 *   <li>删掉 reactive compact 成功分支的 tracking 复位 ——
 *       {@link #reactiveCompactSuccess_resetsTracking_wiring()} 红。</li>
 *   <li>压缩结果不回流（未压缩却返回原消息链 / 压缩后仍超阈）——
 *       {@link #noImmediateReCompaction_nextIterationUsesCompactedMessages()} 红。</li>
 * </ul>
 */
@DisplayName("[P4-4/P4-5] autoCompact tracking 生命周期（多会话隔离 / per-iteration / reactive 复位）")
class AutoCompactTrackingStateTest {

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    /** 恒定超阈 token 桩（越阈判定确定性）· 与 AutoCompactorCcContractTest 同约定。 */
    private static final TokenCounter HIGH_TOKEN = msgs -> 200_000;

    @AfterEach
    void tearDown() {
        PostCompactionState.reset();
        SessionMemoryService.setLastSummarizedMessageId(null, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 多会话隔离（P4-4 · CC query.ts:264/:425 每 query() 一个对象）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("多会话隔离: A 会话熔断后 B 会话首判仍为干净初值 —— 同一 AutoCompactor 单例不串台（P4-4）")
    void multiSessionIsolation_circuitBreakerNotShared() {
        // WHY（规则 9）: 生产环境 AutoCompactor 是 Spring 单例，多会话共享同一 JVM 实例。
        //   CC 的 tracking 归属 query()（局部 State），故 A 会话连失 3 次熔断后，B 会话必须仍以
        //   「未压缩过、无失败」的初值判定——否则 B 的自动压缩被 A 的熔断永久短路：上下文不再被
        //   压缩 → 撞 blocking-limit / provider 413 → 触发 reactive compact → 正是历史
        //   「反复 autoCompact / 上下文失控」事故的判据根因面。
        AutoCompactor auto = new AutoCompactor(HIGH_TOKEN,
            (p, m) -> { throw new RuntimeException("session-A boom"); });
        AutoCompactTrackingState sessionA = new AutoCompactTrackingState();
        AutoCompactTrackingState sessionB = new AutoCompactTrackingState();

        // A 会话连续失败 3 次 → 熔断打开（CC autoCompact.ts:260-265）
        for (int i = 0; i < 3; i++) {
            callLikeLlmAgentLoop(auto, largeMessages(50), sessionA);
        }
        assertThat(sessionA.getConsecutiveFailures())
            .as("A 的 3 次失败必须落进 A 自己的 tracking（若落实例字段，此处为 0 → 红）")
            .isEqualTo(3);
        assertThat(sessionA.isCircuitBreakerOpen()).isTrue();
        assertThat(callLikeLlmAgentLoop(auto, largeMessages(50), sessionA).wasCompacted())
            .as("A 熔断后必须短路（不再尝试压缩 · INV-5）")
            .isFalse();

        // 关键断言：B 首判 = 干净初值（CC `autoCompactTracking: undefined` 等价物）
        assertThat(sessionB.getConsecutiveFailures())
            .as("B 会话首判必须无失败计数（不被 A 的 3 次失败污染 · 隔离核心）")
            .isZero();
        assertThat(sessionB.isCircuitBreakerOpen())
            .as("B 会话熔断器必须关闭（A 的熔断不得跨会话传染）")
            .isFalse();
        assertThat(sessionB.isCompacted())
            .as("B 会话首判 compacted 必须 false（不被 A 污染）")
            .isFalse();

        // 反向：B 的判定结果也只落 B 自己
        callLikeLlmAgentLoop(auto, largeMessages(50), sessionB);
        assertThat(sessionB.getConsecutiveFailures()).isEqualTo(1);
        assertThat(sessionA.getConsecutiveFailures())
            .as("B 的失败不得反向写进 A（双向隔离）")
            .isEqualTo(3);
    }

    @Test
    @DisplayName("多会话隔离: 生产路径（5 参）绝不读/写 AutoCompactor 实例字段 tracking（P4-4）")
    void multiSessionIsolation_productionPathDoesNotTouchInstanceField() {
        // WHY（规则 9）: 实例字段是"单例共享"的载体；只要生产路径还在读写它，多会话隔离就是假的
        //   （旧实现 doRun 入口 reset() 只能对齐单会话串行时序，无法隔离并发会话）。CC 侧无任何
        //   模块级/单例压缩状态（tracking 只在 query() 的循环局部变量里流转）——本断言锁住 Java
        //   生产路径 ①不写、②不读 实例字段。
        // ① 不写：失败计数落调用方传入的对象，不落实例字段。
        AutoCompactor auto = new AutoCompactor(HIGH_TOKEN,
            (p, m) -> { throw new RuntimeException("boom"); });
        AutoCompactTrackingState perQuery = new AutoCompactTrackingState();

        callLikeLlmAgentLoop(auto, largeMessages(50), perQuery);

        assertThat(perQuery.getConsecutiveFailures()).isEqualTo(1);
        assertThat(auto.getTracking().getConsecutiveFailures())
            .as("生产路径（5 参重载）不得写实例字段 tracking（写则多会话串台 → 红）")
            .isZero();
        assertThat(auto.getTracking().isCompacted())
            .as("生产路径不得把 compacted 写进实例字段")
            .isFalse();

        // ② 不读（更强）：把实例字段预置成"另一会话留下的熔断态"（≥3 次失败）——若生产路径读它，
        //    本会话首判就会被短路（wasCompacted=false）；正确实现下本会话 tracking 干净 → 正常压缩。
        //    这正是历史事故里"B 会话被 A 的熔断拖住、上下文不再被压缩"的直接复现。
        AutoCompactor other = new AutoCompactor(HIGH_TOKEN,
            (p, m) -> new CompactConversation.SummaryResult("<summary>clean-session</summary>", null));
        other.getTracking().setConsecutiveFailures(3);   // 模拟另一会话遗留的熔断态（旧实现的串台载体）
        assertThat(other.getTracking().isCircuitBreakerOpen()).isTrue();

        AutoCompactTrackingState cleanSession = new AutoCompactTrackingState();
        assertThat(callLikeLlmAgentLoop(other, largeMessages(50), cleanSession).wasCompacted())
            .as("本会话 tracking 干净时压缩必须正常执行 —— 实例字段里的残留熔断态不得短路本会话"
                + "（生产路径读实例字段 → 此处 false → 红）")
            .isTrue();
        assertThat(cleanSession.isCompacted()).isTrue();
        assertThat(other.getTracking().getConsecutiveFailures())
            .as("本会话压缩成功不得改写实例字段（残留态保持原样）")
            .isEqualTo(3);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 不重复压缩（历史事故回归锁）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("不重复压缩: 一次压缩后同一会话的后续迭代（喂压缩结果）不应立刻再压（历史事故回归锁）")
    void noImmediateReCompaction_nextIterationUsesCompactedMessages() {
        // WHY（规则 9）: 历史事故「同一会话反复 autoCompact」= 压缩后判据没有落到"已变小"的新消息链上
        //   → 下一迭代又判超阈 → 再压。CC 侧的保证方式：压缩成功后
        //   `messagesForQuery = buildPostCompactMessages(...)`（query.ts:660-666）成为本 query
        //   后续迭代的输入，且 tracking 由同一对象继续携带（query.ts:2046 next State）——
        //   于是下一迭代的阈值判定天然基于压缩后（更小）的消息链。
        //   本测试用「消息数 × 10k」的真实计数桩复现该链路：压缩一次后把结果作为新消息链再判，
        //   必须"不再压"；若压缩结果没有回流（wasCompacted=true 却返回原消息链），第二次必然又压 → 红。
        AutoCompactor auto = new AutoCompactor(msgs -> msgs.size() * 10_000,
            (p, m) -> new CompactConversation.SummaryResult("<summary>one-shot</summary>", null));
        AutoCompactTrackingState tracking = new AutoCompactTrackingState();
        List<ChatMessageDto> beforeIteration = largeMessages(50);

        AutoCompactor.AutoCompactResult first =
            callLikeLlmAgentLoop(auto, beforeIteration, tracking);
        assertThat(first.wasCompacted())
            .as("首次超阈必须压缩（基线，保证第二次断言有区分度）")
            .isTrue();
        assertThat(tracking.isCompacted())
            .as("压缩成功后 tracking.compacted 必须 true（CC query.ts:720 · 实测真源行号）")
            .isTrue();
        assertThat(tracking.getConsecutiveFailures())
            .as("压缩成功必须复位连续失败（CC query.ts:723 · 实测真源行号；仓内旧注释「521-526」为陈旧引用）")
            .isZero();

        // 调用方把压缩结果作为本 query 后续迭代的消息链（CC query.ts:666 messagesForQuery = postCompactMessages）
        List<ChatMessageDto> postCompact = first.messages();
        assertThat(postCompact.size())
            .as("压缩结果消息数必须显著小于压缩前（本测试计数桩的前提：postCompact 远低于越阈线）")
            .isLessThan(17);

        AutoCompactor.AutoCompactResult second =
            callLikeLlmAgentLoop(auto, postCompact, tracking);
        assertThat(second.wasCompacted())
            .as("压缩后的消息链已低于阈值 → 同一 query 的后续迭代不得再压（反复 autoCompact 回归锁）")
            .isFalse();
        assertThat(second.messages())
            .as("未压缩时必须原样返回入参消息链（调用方局部变量语义 · CC messagesForQuery）")
            .isSameAs(postCompact);
        assertThat(tracking.getConsecutiveFailures())
            .as("未压缩不得产生失败计数（第二次判定走阈值分支，非熔断短路）")
            .isZero();
    }

    @Test
    @DisplayName("不重复压缩（接线）: 压缩成功后调用方必须把压缩结果作为本 query 后续迭代的消息链（CC query.ts:666）")
    void noImmediateReCompaction_wiringThreadsCompactedMessages() throws IOException {
        // WHY（规则 9）: 上面那条行为断言的"缩链"前提由调用方保证 —— CC 压缩成功后
        //   `messagesForQuery = buildPostCompactMessages(compactionResult)`（query.ts:660-666）
        //   取代本迭代剩余流程的消息链。若调用方漏掉这步替换，下一迭代仍以压缩前的超长链判定 →
        //   又判超阈 → 再压（反复 autoCompact 事故的直接机制）。故该行必须存在且后继
        //   `messagesForQuery` 消费点（blocking 预检 / 请求组装）读到的是压缩后的链。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        int persistIdx = source.indexOf("persistCompactedMessages(state, l4Result.messages());");
        assertThat(persistIdx)
            .as("锚点缺失：压缩结果落库调用（proactive 成功分支）")
            .isGreaterThan(0);
        assertThat(source)
            .as("压缩成功后必须把压缩结果回填为 messagesForQuery（CC query.ts:666）——漏掉即反复压缩")
            .contains("messagesForQuery = new ArrayList<>(state.messages());");
        assertThat(source.indexOf("messagesForQuery = new ArrayList<>(state.messages());"))
            .as("回填必须发生在压缩成功分支内（persistCompactedMessages 之后）")
            .isGreaterThan(persistIdx);
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 跨 run / 跨 query 复位（P4-4 · CC query.ts:425 每 query() 新对象）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("跨 query 复位: 新 query() 用全新 tracking → 不继承上一 query 的熔断/压缩判据（P4-4）")
    void crossQuery_freshTrackingPerQuery() {
        // WHY（规则 9）: CC 每次 query() 调用都以 `autoCompactTracking: undefined`（query.ts:425）起步，
        //   用户回合之间判据完全断开（熔断范围 = 单次 query() 内，非进程/会话级）。旧 Java 用单例
        //   实例字段 + run 入口 reset() 模拟该语义：单会话串行"看起来"对，但那是共享状态上的时序
        //   补丁（并发会话互相清零）。本测试锁"每个 query() 一个对象"。
        AutoCompactor auto = new AutoCompactor(HIGH_TOKEN,
            (p, m) -> { throw new RuntimeException("query-1 boom"); });

        // query 1：3 次失败 → 熔断（同一单例实例上的第 1 段 query）
        AutoCompactTrackingState query1 = new AutoCompactTrackingState();
        for (int i = 0; i < 3; i++) {
            callLikeLlmAgentLoop(auto, largeMessages(50), query1);
        }
        assertThat(query1.isCircuitBreakerOpen()).isTrue();

        // query 2：新对象 = 干净初值（CC 每 query() undefined）
        AutoCompactTrackingState query2 = new AutoCompactTrackingState();
        assertThat(query2.getConsecutiveFailures()).isZero();
        assertThat(query2.isCompacted()).isFalse();
        assertThat(query2.getTurnCounter()).isZero();
        assertThat(query2.getTurnId())
            .as("新 query 的 turnId 必须是新实例自带值（非上一 query 轮换后的 id）")
            .isNotEqualTo(query1.getTurnId());

        // query 2 的失败计数从 0 起算（CC 熔断范围 = 单 query()）
        callLikeLlmAgentLoop(auto, largeMessages(50), query2);
        assertThat(query2.getConsecutiveFailures())
            .as("query 2 的失败计数必须从 0 起算（不继承 query 1 的 3 次）")
            .isEqualTo(1);
        assertThat(query1.getConsecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("跨 query 复位（接线）: LlmAgentLoop 每 query() 新建 tracking 并经参数透传（P4-4）")
    void crossQuery_wiringCreatesFreshTrackingAtQueryEntry() throws IOException {
        // WHY（规则 9）: 结构锁 —— per-query() 生命周期只有在"创建点在 query() 等价入口
        //   （LlmAgentLoop.queryLoop）且经参数流转"时才成立；若创建点落回 loop() 局部（每个 stop-hook
        //   递归重入帧都重置）或落回单例字段，判据范围就不再等于 CC 的 query()。本仓先例：
        //   ReactiveCompactorCcContractTest 对 LlmAgentLoop 的 reactive 分支同样做源码锚定。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));

        assertThat(source)
            .as("queryLoop（= CC query() 等价入口）必须每调用新建一个 tracking（CC query.ts:425）")
            .contains("AutoCompactTrackingState autoCompactTracking = new AutoCompactTrackingState();");
        assertThat(source)
            .as("tracking 必须作为 loop(...) 形参透传（含 stop-hook 递归重入帧共用同一实例 · CC transition 站点）")
            .contains("AutoCompactTrackingState autoCompactTracking,");
        assertThat(source)
            .as("生产调用点必须把 per-query tracking 作为第 5 参传入（CC autoCompact.ts:275 tracking 形参位）")
            .contains("autoCompactTracking);");
        assertThat(source)
            .as("生产路径不得再读 AutoCompactor 单例实例字段 tracking（P4-4 隔离前提）")
            .doesNotContain("autoCompactor.getTracking()");
        assertThat(source)
            .as("旧的 run 入口 autoCompactor.reset()（共享状态时序补丁）必须已删")
            .doesNotContain("autoCompactor.reset();");
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. reactive compact 成功后复位（P4-4 item3 · CC query.ts:1442）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reactive 复位（语义）: 复位后的 tracking 不再被上一段的熔断短路（CC :1442 undefined）")
    void reactiveCompactSuccess_resetsTracking_semantics() {
        // WHY（规则 9）: CC 在 reactive compact 成功后把 State 的 autoCompactTracking 置 undefined
        //   （query.ts:1442）——下一迭代的 proactive autocompact 拿到全新状态（compacted=false /
        //   熔断计数 0 / turnCounter 0），不会把上一段生命周期的判据带进新段。Java 等价物 = 新建
        //   AutoCompactTrackingState（LlmAgentLoop 在 reactive 成功分支执行）。本测试锁该对象的语义。
        AutoCompactor auto = new AutoCompactor(HIGH_TOKEN,
            (p, m) -> { throw new RuntimeException("boom"); });
        AutoCompactTrackingState session = new AutoCompactTrackingState();
        for (int i = 0; i < 3; i++) {
            callLikeLlmAgentLoop(auto, largeMessages(50), session);
        }
        assertThat(session.isCircuitBreakerOpen()).isTrue();
        assertThat(callLikeLlmAgentLoop(auto, largeMessages(50), session).wasCompacted())
            .as("熔断态下必须短路（复位前的基线）")
            .isFalse();

        // LlmAgentLoop reactive compact 成功分支的等价动作（CC query.ts:1442）
        session = new AutoCompactTrackingState();
        assertThat(session.isCircuitBreakerOpen()).isFalse();
        assertThat(session.isCompacted()).isFalse();
        assertThat(session.getTurnCounter()).isZero();

        // 复位后：真正的压缩能执行（不被上一段熔断短路）
        AutoCompactor ok = new AutoCompactor(HIGH_TOKEN,
            (p, m) -> new CompactConversation.SummaryResult("<summary>after-reactive</summary>", null));
        assertThat(callLikeLlmAgentLoop(ok, largeMessages(50), session).wasCompacted())
            .as("复位后压缩尝试必须真正执行（熔断计数已归零 · CC :1442 语义）")
            .isTrue();
    }

    @Test
    @DisplayName("reactive 复位（接线）: LlmAgentLoop reactive compact 成功分支必须复位 per-query tracking（CC :1442）")
    void reactiveCompactSuccess_resetsTracking_wiring() throws IOException {
        // WHY（规则 9）: 结构锁。loop() 为 private static 巨型方法，无法单测其内部分支；而
        //   "reactive 成功后是否复位 tracking"直接决定下一迭代 proactive 判据是否干净，必须可回归。
        //   锚点 = markReactiveCompact()（reactive 成功分支内）之后的同分支代码窗口。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        int markIdx = source.indexOf("recoveryState.markReactiveCompact();");
        assertThat(markIdx)
            .as("锚点缺失：reactive compact 成功标记（R25-3）")
            .isGreaterThan(0);
        String reactiveWindow = source.substring(markIdx, Math.min(source.length(), markIdx + 2000));
        assertThat(reactiveWindow)
            .as("reactive compact 成功后必须复位 autoCompactTracking（CC query.ts:1442 "
                + "`autoCompactTracking: undefined`）——复位缺失即判据串段（旧实现缺陷）")
            .contains("autoCompactTracking = new AutoCompactTrackingState();");
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. justCompacted per-iteration（P4-5 · CC query.ts:652/:827/:852）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("justCompacted: 必须在 do-while 迭代体内声明（per-iteration），不得回到 loop() 方法级粘滞（P4-5）")
    void justCompacted_declaredPerIteration() throws IOException {
        // WHY（规则 9）: CC 的 compactionResult 在 while(true) 迭代体内声明（query.ts:652），只影响
        //   本迭代的 blocking-limit 预检跳过（query.ts:827）与预测性 autocompact（query.ts:852）。
        //   旧 Java 在 loop() 方法级声明 `justCompacted` 且从不复位 = 粘滞：一次压缩后本 run 余下
        //   所有迭代都跳过 blocking-limit 预检 → 上下文越过 blocking 上限直撞 provider 413 →
        //   reactive compact → 反复压缩（历史事故放大面）。
        //   锁法：声明必须出现在 do-while 迭代体开界之后，且全文件恰一处（方法级残留会立刻被抓）。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));

        assertThat(source)
            .as("justCompacted 声明必须全文件恰一处（方法级 + 迭代体双声明 = 粘滞回归）")
            .containsOnlyOnce("boolean justCompacted = false;");

        int anchor = source.indexOf("boolean[] lastIterationRanTools = { false };");
        assertThat(anchor)
            .as("锚点缺失：do-while 迭代体之前的 lastIterationRanTools 声明")
            .isGreaterThan(0);
        int doIdx = source.indexOf("do {", anchor);
        assertThat(doIdx)
            .as("锚点缺失：do-while 迭代体开界")
            .isGreaterThan(anchor);
        int declIdx = source.indexOf("boolean justCompacted = false;");
        assertThat(declIdx)
            .as("justCompacted 必须在 do-while 迭代体内声明（per-iteration · CC query.ts:652）；"
                + "声明在 do 之前 = 粘滞语义回归 → 红")
            .isGreaterThan(doIdx);
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * 复刻 {@code LlmAgentLoop} 的生产调用契约：5 参
     * {@link AutoCompactor#autoCompactIfNeeded(List, int, String, CompactConversationContext, AutoCompactTrackingState)}
     * + 失败计数写回（CC autoCompact.ts:341-349 返回值 + query.ts:536-542 调用方写回）。
     *
     * <p>WHY: 不写回复刻，失败计数就不会累计 → 熔断语义在测试里恒不触发，隔离断言也就失去意义。
     */
    private static AutoCompactor.AutoCompactResult callLikeLlmAgentLoop(
            AutoCompactor auto, List<ChatMessageDto> messages, AutoCompactTrackingState tracking) {
        AutoCompactor.AutoCompactResult result =
            auto.autoCompactIfNeeded(messages, 0, "user", null, tracking);
        if (result.consecutiveFailures() != null) {
            tracking.setConsecutiveFailures(result.consecutiveFailures());
        }
        return result;
    }

    /** 构造 N 条纯文本 user 消息（无 usage → 阈值走 tokenCounter.count 桩，判定确定性）。 */
    private static List<ChatMessageDto> largeMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ChatMessageDto("u" + i, null, Role.user, "user", "hi", null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null, false, false));
        }
        return list;
    }
}
