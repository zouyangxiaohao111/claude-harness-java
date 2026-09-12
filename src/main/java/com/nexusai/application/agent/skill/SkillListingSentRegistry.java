package com.nexusai.application.agent.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [skill-listing-cc-align 2026-09-10] skill_listing 已发送技能名 · <b>进程级会话键控注册表</b>。
 *
 * <p><b>WHY（CC 真源对齐）</b>：CC {@code sentSkillNames} 是进程级 module-scope
 * {@code Map<string, Set<string>>}（{@code src/utils/attachments.ts:2676}），键 = {@code agentId ?? ''}。
 * CC 一进程一会话，故只按 agentId 即可；nexusai web 端多会话共享同一 JVM，若只按 agentKey 会
 * <b>跨会话串扰</b>（会话 A 已发清单会让会话 B 误判「已发」）—— 故本表键 = {@code sessionId + '\0' + agentKey}，
 * 既保留 CC「主线程 agentKey=''、各 agent 独立」语义，又隔离多会话。
 *
 * <p><b>三态判据（对齐 CC attachments.ts:2781-2832 的 get-or-create/suppress/newSkills/isInitial）</b>：
 * <ol>
 *   <li><b>key ∈ CLEARED</b>（/clear 时点写入）→ 消费标记，清 sent + 全量标 sent + 置 initialized
 *       → {@link Decision#isInitial()} {@code true} 的整份注入。等价 CC {@code resetSentSkillNames()}
 *       （{@code sentSkillNames.clear() + suppressNext=false}，clear/caches.ts:79）→ 下一次 attachment
 *       pass 因 sent 空而 newSkills=全量、isInitial=true。</li>
 *   <li><b>!resume</b>（全新会话首 run）→ 清 sent + 全量标 sent + 置 initialized → 整份注入。
 *       对齐 CC 全新进程 + 全新会话：sent 空、suppress=false。</li>
 *   <li><b>resume 且 key ∉ INITIALIZED</b>（JVM 刚重启 / 该会话首次在本进程出现）→ 全量标 sent +
 *       置 initialized → <b>不注入</b>。对齐 CC {@code suppressNext} 分支（attachments.ts:2791-2797：
 *       全量标 sent + {@code return []}）—— 转录已含上一进程注入的清单，不重复注入。</li>
 *   <li><b>resume 且 key ∈ INITIALIZED</b> → {@code newSkills = 全量 − sent}；空 → 不注入；
 *       非空 → 标 sent → 只注入增量子集。对齐 CC attachments.ts:2799-2809。</li>
 * </ol>
 *
 * <p><b>「从未初始化」vs「存在但集合为空」</b>：CC 用 {@code sent.size === 0} 判 isInitial，但 CC 的
 * 空集合 entry 只在 get-or-create 后存在；本表需显式区分「JVM 刚重启（无 entry）」与「resume 已处理过
 * （entry 在，sent 可能空）」。故 {@link #SENT} 集合的存在性与 {@link #INITIALIZED} 标记分离：
 * 只有真正走过初始化分支（1/2/3）才置 {@link #INITIALIZED}；resume 判断只看该标记。
 *
 * <p><b>INITIALIZED 的精确语义 = CC 的 {@code suppressNext === false}</b>（2026-09-10 对抗核验修复轮）：
 * CC 只有两个进程级变量 —— {@code sent} 集合 + 一次性布尔 {@code suppressNext}
 * （attachments.ts:2706）。{@code suppressNext} 仅由 conversationRecovery（冷 resume）置 true
 * （attachments.ts:2703-2705），并在<b>下一次 pass 被无条件消费</b>（attachments.ts:2791-2797：置 false
 * + {@code for (cmd of allCommands) sent.add(...)}，allCommands 为空时是空循环，但标志照样被消费）。
 * 本表把 {@code suppressNext === false} 折叠为 {@link #INITIALIZED} 的「在集合内」：
 * <ul>
 *   <li>首次 decide 前 key ∉ INITIALIZED ⟺ {@code suppressNext === true}（抑制待消费）；</li>
 *   <li>key ∈ INITIALIZED ⟺ {@code suppressNext === false}（不再抑制，走 newSkills 增量）。
 *       <b>注意 sent 可能同时为空</b>（首 run 无候选 / 抑制分支无候选 / resetSentAllSessions 后）——
 *       此时下一个非空 pass 的 {@code sent.size()===0} 仍为真 → CC 判 isInitial=true → 注入整份。</li>
 * </ul>
 *
 * <p><b>空候选也必须走完状态机</b>（2026-09-10 对抗核验 high 修复）：上一版在 {@code allNames} 为空时
 * 于置 {@link #INITIALIZED} 之前 return {@link Decision#NONE}，等价于「suppressNext 从未被消费」。
 * 后果：某会话首 run 零技能（或元数据未就绪）→ 从未 initialize；此后每个 run 都是 resume=true →
 * 落分支 3 抑制 + 全量标 sent → <b>整份清单与后续新增技能永不注入</b>，直到用户 /clear。
 * CC 此场景必然注入（allCommands 空 → newSkills=[] → return []，suppressNext 已被消费、sent 仍空 →
 * 下一次非空 pass 因 sent.size()===0 → isInitial=true → 注入整份，attachments.ts:2799-2809）。
 * 故 {@link #decide} 对空候选一律置 {@link #INITIALIZED}（消费 suppress + 保持 sent 空），
 * 并<b>同时消费</b> {@link #CLEARED}（该分支已把会话置回「已初始化」态 → 下一次非空 pass 走 branch 4、
 * 因 sent 仍空而 isInitial=true 注入整份，与 branch 1 等价；消费标记顺带防无界增长）。
 * <p><b>（2026-09-10 收尾轮纠偏）</b>本段此前写「不消费 CLEARED」，与 {@link #decide} 实际实现相反
 * （代码在空候选分支即 {@code CLEARED.remove(sessionId)}）—— 已按代码实际行为改写。
 *
 * <p><b>非 CC 偏离标注</b>：
 * <ul>
 *   <li>{@code sessionId} 进键 —— <b>非 CC</b>（CC 进程级单会话）。理由：nexusai 多会话共享 JVM，
 *       不带 sessionId 会跨会话串扰（见上）。</li>
 *   <li>{@code CLEARED} 标记 —— <b>非 CC</b>（CC 直接 clear 全局 Map）。理由：nexusai 的 /clear
 *       不删 DB 消息行 → 下一 run resume 仍为 true；若只清 entry，会落进「resume 且未 initialized」
 *       分支被 suppress（不重发），偏离 CC「clear 后重发整份」。故显式记录 clear 意图，下次 decide
 *       强制整份。</li>
 * </ul>
 *
 * <p><b>local-only 红线</b>：纯进程内存，绝不持久化 / 绝不外发（与 CC module-scope Map 同性质）。
 */
public final class SkillListingSentRegistry {

    /** 会话键与 agentKey 分隔符（sessionId 与 agentKey 均不含 NUL，天然无歧义）。 */
    private static final char KEY_SEP = '\0';

    /** code → 已发送技能名集合（CC original: sentSkillNames Map value）。 */
    private static final ConcurrentHashMap<String, Set<String>> SENT = new ConcurrentHashMap<>();

    /**
     * 已初始化 key 集（CC original：无直接对应字段，本表为「区分从未初始化 vs 已初始化空集合」而设）。
     * 只有走过 CU/FULL 或 SUPPRESS 分支的 key 才在此集合内。
     */
    private static final Set<String> INITIALIZED = ConcurrentHashMap.newKeySet();

    /** /clear 会话集：下次 decide 强制整份重发（CC resetSentSkillNames 语义）；按 sessionId（清全部 agentKey）。 */
    private static final Set<String> CLEARED = ConcurrentHashMap.newKeySet();

    private SkillListingSentRegistry() {
    }

    /** 组装键 · 非 CC：nexusai 加 sessionId 前缀（多会话共享 JVM 隔离）。 */
    static String keyOf(String sessionId, String agentKey) {
        String sid = sessionId != null ? sessionId : "";
        String ak = agentKey != null ? agentKey : ""; // CC: agentId ?? ''
        return sid + KEY_SEP + ak;
    }

    /**
     * 本次 run 应注入的 skill_listing 决策 · 对齐 CC {@code getSkillListingAttachments}
     * （attachments.ts:2781-2832，含 get-or-create / suppress / newSkills / isInitial 四段）。
     *
     * @param sessionId 会话 short id（键前缀；null/blank 亦可，仅作键的一部分）
     * @param agentKey  agent 标识（主线程 null/""；对齐 CC {@code agentId ?? ''}）
     * @param allNames  当前全部候选技能名（CC allCommands，按 name；顺序保留 = 输出顺序）
     * @param resume    会话是否续跑（= 排除当前 in-flight 用户消息后转录非空）· 由调用方按
     *                  LlmAgentLoop 现成 resume 判据计算（勿用 JVM 冷热：冷热分不开「全新会话首 run」
     *                  与「JVM 重启后老会话」）
     * @return 决策（{@link Decision#names()} = 本次注入子集；空 = 不注入；{@link Decision#isInitial()}
     *         = 是否首次整份，供 tengu_skill_loaded 遥测门控）· allNames 全空 → {@link Decision#NONE}
     *         <b>但仍会置 initialized</b>（消费 suppress，见类 javadoc「空候选也必须走完状态机」）
     */
    public static Decision decide(String sessionId, String agentKey, List<String> allNames, boolean resume) {
        // [2026-09-10 收尾轮] sessionId 归一：CLEARED 是 ConcurrentHashMap.newKeySet()，remove(null) 抛 NPE
        //   （CHM 不允许 null 键）。调用方（LlmAgentLoop.injectSkillListingForRun）直传 state.sessionId()，
        //   它可能为 null（forTest / REPL / 无会话 run）—— 原实现会让 NPE 被 doRun 的 catch 吞成「静默不注入」
        //   （违反规则十二 fail loud）。keyOf 已对 null 兜底，这里把 CLEARED 的键也统一为空串，保持等价语义。
        String sid = sessionId != null ? sessionId : "";
        String key = keyOf(sessionId, agentKey);
        Set<String> sent = SENT.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());

        // 空候选：仍须消费 suppress + 置 initialized（否则本会话被后续 run 误判为冷 resume → 永久抑制）。
        //   对齐 CC attachments.ts:2791-2797（suppressNext 无条件置 false，allCommands 空则 forEach 空转）
        //   → :2799-2804（newSkills 空 → return []，sent 保持空）→ :2805（下一 pass sent.size()===0 →
        //   isInitial=true → 注入整份）。CLEARED 同样消费（removeSession 已清空该会话 sent 槽 → 下一次
        //   非空 pass 走分支 4 亦因 sent 空而 isInitial=true 注入整份，与分支 1 等价；顺带防无界增长）。
        if (allNames == null || allNames.isEmpty()) {
            CLEARED.remove(sid);
            INITIALIZED.add(key);
            return Decision.NONE;
        }

        // 分支 1：/clear 标记（消费一次）→ 整份重发（CC resetSentSkillNames 语义）。
        if (CLEARED.remove(sid)) {
            sent.clear();
            sent.addAll(allNames);
            INITIALIZED.add(key);
            // supersedesPriorRows=true：/clear 时 CC 清空 messages 数组 → 旧 skill_listing 附件消失，
            //   本整份即该会话唯一一份；nexusai /clear 不删 DB 行 → 落库侧据此「先插后删」收敛（见
            //   Decision.supersedesPriorRows 与 AgentState.skillListingSupersedesPriorRows）。
            return new Decision(allNames, true, true);
        }

        // 分支 2：全新会话首 run（!resume）→ 整份注入（CC 全新进程 + 新会话：sent 空 + suppress=false）。
        //   supersedesPriorRows=false：全新会话 DB 本无旧清单行，无需删（也无从删）。
        if (!resume) {
            sent.clear();
            sent.addAll(allNames);
            INITIALIZED.add(key);
            return new Decision(allNames, true, false);
        }

        // 分支 3：resume 且从未初始化（JVM 重启 / 会话首现本进程）→ suppress：全量标 sent，不注入
        //   （对齐 CC attachments.ts:2791-2797 suppressNext 分支）。
        if (!INITIALIZED.contains(key)) {
            sent.addAll(allNames);
            INITIALIZED.add(key);
            return Decision.NONE;
        }

        // 分支 4：resume 且已初始化 → 只发增量（CC attachments.ts:2799-2809）。
        List<String> newSkills = new ArrayList<>();
        for (String name : allNames) {
            if (name != null && !sent.contains(name)) {
                newSkills.add(name);
            }
        }
        if (newSkills.isEmpty()) {
            return Decision.NONE;
        }
        // isInitial 对齐 CC 定义 `sent.size() === 0`（attachments.ts:2805）——sent 可能因「首 pass 无候选」、
        //   抑制分支无候选、或 resetSentAllSessions() 而为空，此时本 pass 实为该 agent 的首份整份。
        boolean isInitial = sent.isEmpty();
        sent.addAll(newSkills);
        // supersedesPriorRows=false：增量只含新技能名，旧整份仍是未变技能的唯一来源（CC 同场景旧附件
        //   留在 messages 数组里累积）→ 绝不删旧行，否则丢技能。resetSentAllSessions 触发的整份重发同理
        //   （CC 保留旧附件累积）。
        return new Decision(newSkills, isInitial, false);
    }

    /**
     * 仅置 initialized（= CC {@code suppressNext === false}）· 不触碰 sent、不消费 CLEARED。
     *
     * <p><b>用途</b>：无 Skill 工具的 run（{@link SkillListingSentRegistry} 的调用方守卫路径）——
     * 对齐 CC attachments.ts:2750-2755「无 Skill 工具直接 return」，CC 在此<b>不</b>消耗 suppressNext、
     * 不建 sent 槽。但「全新会话首 run」在 CC 属 {@code suppressNext === false} 态；若连这个状态都不记，
     * 同一 JVM 内后续 run（resume=true）会把该 key 误判为「冷 resume（suppress 待消费）」→ 永久抑制。
     * 故调用方仅在 {@code !resume}（非冷 resume，无 pending suppress）时补记；冷 resume（resume=true）
     * 必须保持未初始化，让首个 decide 正确地走 suppress 分支。
     *
     * <p><b>非 CC 偏离</b>：CC 无此方法（其 {@code suppressNext=false} 由「从未被 conversationRecovery
     * 置位」隐式表达）。本表把该布尔折叠为 {@link #INITIALIZED} 的存在性 → 需显式落位。
     *
     * @param sessionId 会话 short id
     * @param agentKey  agent 标识（主线程 ""，对齐 CC {@code agentId ?? ''}）
     */
    public static void markInitialized(String sessionId, String agentKey) {
        String key = keyOf(sessionId, agentKey);
        SENT.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        INITIALIZED.add(key);
    }

    /**
     * /clear 时点重置该会话的 skill_listing 去重态（清 SENT + 置 CLEARED）。
     *
     * <p><b>对齐 CC {@code resetSentSkillNames()}（clear/caches.ts:79）</b>：CC 该函数做两件事 ——
     * {@code sentSkillNames.clear()}（清各 agentId 槽的 sent）+ {@code suppressNext = false}
     * （不再抑制；下一次 pass 因 sent 空 → isInitial=true 重发整份）。本表把 {@code suppressNext===false}
     * 折叠为 {@link #INITIALIZED} 的存在性，故 faithful 翻译 = <b>清 SENT、保留 INITIALIZED</b>：
     * 保留后下一次 decide 走 branch 4、{@code sent.isEmpty()} → isInitial=true → 整份重发（与 CC 等价），
     * 且该语义对该会话<b>每个已存在的 agentKey 槽都成立</b>。
     *
     * <p><b>（2026-09-10 收尾轮修复）</b>上一版在此处 {@code INITIALIZED.removeIf(...)} 把槽一并删掉，
     * 只靠 CLEARED 的一次性消费补回「重发整份」，于是同一 sessionId 的多个 agentKey（主线程 {@code ""}
     * 与后台化 agentUuid）中<b>先到者消费掉 CLEARED</b>、其余 key 落进「resume 且未初始化 → 抑制」——
     * 主线程 /clear 后可能看不到整份重发（finding：CLEARED 单消费者）。保留 INITIALIZED 后该竞态消除
     * （每个已初始化槽都因 sent 空而独立 isInitial 重发）。
     *
     * <p><b>CLEARED 仍保留的必要性</b>：仅覆盖「该 key 从未初始化就遇到 /clear」的场景（典型：JVM 重启后
     * 用户先 /clear 再发消息 → 冷 resume 本会走 branch 3 抑制，而 CC 因 resetSentSkillNames 置
     * suppressNext=false 会重发整份）。已初始化的 key 不依赖它。
     *
     * <p><b>非 CC</b>：CC 无 CLEARED（其 {@code suppressNext=false} 由 reset 直接置位，且进程级单会话
     * 无需会话键）；本表多会话共享 JVM，需 CLEARED 按 sessionId 记录 clear 意图。
     *
     * @param sessionId 会话 short id（null/blank → no-op）
     */
    public static void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String prefix = sessionId + KEY_SEP;
        SENT.keySet().removeIf(k -> k.startsWith(prefix));
        // 保留 INITIALIZED（= CC suppressNext=false）：清 sent + 保留 initialized 即等价 CC reset 后
        //   下一次 pass 的 isInitial 重发。见方法 javadoc 的收尾轮修复说明。
        CLEARED.add(sessionId);
    }

    /**
     * 纯清理：会话<b>被删除</b>时点移除该会话全部 agentKey 条目，<b>不置</b> CLEARED。
     *
     * <p><b>为什么与 {@link #removeSession} 分开</b>：被删会话不会再跑 {@link #decide}，若走 removeSession
     * 会写入一个永不被消费的 CLEARED String（长跑 JVM 下随「删除会话次数」无界增长，与本表「防无界增长」
     * 的立项目标相反 —— finding：delete 路径 CLEARED 残留）。此方法同时清掉可能残留的 CLEARED，
     * 是真正的「回收槽位」入口。
     *
     * @param sessionId 会话 short id（null/blank → no-op）
     */
    public static void removeSessionEntries(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String prefix = sessionId + KEY_SEP;
        SENT.keySet().removeIf(k -> k.startsWith(prefix));
        INITIALIZED.removeIf(k -> k.startsWith(prefix));
        CLEARED.remove(sessionId);
    }

    /**
     * [P2-12 · 2026-09-11] 单键回收：子代理 / hook agent <b>结束点</b>移除该 (会话, agentKey) 槽位。
     *
     * <p><b>WHY（泄漏面）</b>：本表键含 agentKey，而 hook agent 每次调用都生成<b>新 UUID</b>
     * （{@code ExecAgentHook.generateHookAgentId()} → {@code hook-agent-<uuid>}），子代理每次执行亦为新
     * agentId → 每个 (会话, agent 调用) 都在 {@link #SENT} 留一份 Set&lt;技能名&gt; 且<b>永不回收</b>
     * （此前只有按 sessionId 前缀的 {@link #removeSession} / {@link #removeSessionEntries}）。
     * CC 的同名结构 {@code sentSkillNames}（attachments.ts:2676，键 {@code agentId ?? ''}）同样从不删单键，
     * 但 CC 一进程一会话、agent 随进程消亡 → 无泄漏面；nexusai 常驻 JVM 必须显式回收。
     *
     * <p><b>为什么连 INITIALIZED 一并移除（而非只清 SENT）</b>：只清 SENT 会让同一 key 的下一次 decide
     * 落分支 4 且 {@code sent.isEmpty()} → {@code isInitial=true} → <b>整份重发</b>（~4K token）；
     * 而该 key 对应的 agent 已经结束，若其因 resume 复用同 agentId 续跑（{@code SubagentExecutor}
     * 的 {@code forkParams.agentIdOverride()} 路径），重发既冗余又偏离「续跑不重注」语义。
     * 两者同删后，复用同 agentId 的续跑落分支 3（resume 且未初始化 → 抑制 + 全量标 sent），
     * 与「槽位未被回收时分支 4 无增量 → 不注入」的<b>可观测结果一致</b>（皆不注入）。
     *
     * <p><b>不触碰 CLEARED</b>：CLEARED 是<b>会话级</b>的 /clear 意图（供该会话所有 agentKey 槽整份重发），
     * 单个 agent 结束不得消费或清除它。
     *
     * <p><b>幂等</b>：键不存在 → no-op；{@code sessionId}/{@code agentKey} 为 null → 与 {@link #keyOf}
     * 同口径折算（不抛 NPE）。
     *
     * @param sessionId 会话 short id（null → 折算空串，与 {@link #decide} 一致）
     * @param agentKey  agent 标识（主线程 {@code ""}；子代理 / hook agent = 其 agentUuid 字符串）
     */
    public static void removeAgentKey(String sessionId, String agentKey) {
        String key = keyOf(sessionId, agentKey);
        SENT.remove(key);
        INITIALIZED.remove(key);
    }

    /**
     * skill 文件变更时清空全部已发送集合（保留 initialized 标记）· 对齐 CC {@code resetSentSkillNames()}
     * 由 skillChangeDetector.ts:276 调用 → 下一轮 listing 重发（本表走分支 4：sent 空 → newSkills=全量）。
     *
     * <p>非 CC：CC 全局唯一进程，clear 全局 Map 即当前会话；nexusai 多会话共享 JVM，此处等同清全部会话
     * （与 {@link SkillChangeDetector#resetSentSkillNames()} 既有「清全部已注册 per-run Map」口径一致）。
     *
     * <p><b>跨会话副作用影响评估（2026-09-10 收尾轮登记）</b>：任一 skill 文件变更都会让<b>所有活跃会话</b>
     * 在下一次 run 重发整份清单（~4K token/会话）并断掉这些会话的前缀缓存。CC 单进程单会话时该 clear 无
     * 副作用；nexusai 多会话下代价被放大。评估结论：<b>接受</b>——触发条件是「skill 文件在磁盘上真实变更」
     * （低频、通常由用户主动编辑），且该行为与既有 {@code resetSentSkillNames()}（清全部 per-run Map）口径
     * 一致，保持两表语义统一优于按会话收敛（按会话收敛需把「哪个会话该感知 skill 变更」的定义引入本表，
     * 而 skill 目录是进程级共享的，无法按会话切分）。保留 INITIALIZED → 各会话走 branch 4 全量重发。
     */
    public static void resetSentAllSessions() {
        for (Set<String> sent : SENT.values()) {
            sent.clear();
        }
        CLEARED.clear();
    }

    /**
     * 清空全表（@VisibleForTesting：模拟 JVM 重启）。生产进程重启天然清空（JVM 内存），不供生产调用。
     */
    public static void reset() {
        SENT.clear();
        INITIALIZED.clear();
        CLEARED.clear();
    }

    /** 该 key 是否已初始化（存在 entry 且走过初始化分支）· 仅供测试/诊断。 */
    public static boolean isInitialized(String sessionId, String agentKey) {
        return INITIALIZED.contains(keyOf(sessionId, agentKey));
    }

    /**
     * 该会话当前持有的槽位数（键前缀匹配 {@code sessionId + KEY_SEP}）· <b>仅供测试/诊断</b>。
     *
     * <p>WHY 需要它：{@link #removeAgentKey} 的调用点在 agent 结束路径（ExecAgentHook / SubagentExecutor），
     * 其 agentKey 是随机 UUID、调用后无法从外部反解 —— 只有「按会话数槽位」才能断言「结束点确实回收了」。
     * 与 {@link #isInitialized} 同性质（只读诊断，不暴露可变内部结构）。
     *
     * @param sessionId 会话 short id（null → 折算空串，与 {@link #decide} 一致）
     * @return 该会话名下 SENT 槽位数
     */
    public static int sessionSlotCount(String sessionId) {
        String prefix = (sessionId != null ? sessionId : "") + KEY_SEP;
        int n = 0;
        for (String k : SENT.keySet()) {
            if (k.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    /**
     * decide 结果载体 · 对齐 CC attachments.ts:2799-2832
     * （{@code newSkills} 过滤 + {@code isInitial = sent.size === 0}）。
     *
     * @param names     本次应注入的技能名（空 = 不注入）
     * @param isInitial 是否首次整份注入（CC original: isInitial，供 tengu_skill_loaded 遥测门控）
     * @param supersedesPriorRows 本次注入是否<b>取代该会话此前所有 skill_listing 行</b>（仅 /clear 触发
     *        = true）· 落库侧据此「先插后删」把 DB 收敛为唯一一份。WHY 仅 /clear：CC 的 /clear 清空
     *        messages 数组使旧附件消失（唯一一份）；增量清单与 skill 变更重发都必须保留旧行（CC 累积）。<b>非 CC 字段</b>
     *        （CC 无 DB 位置行概念，靠内存数组 + 清空事件表达同一语义）。
     */
    public record Decision(List<String> names, boolean isInitial, boolean supersedesPriorRows) {
        /** 不注入（suppress / 无增量 / 空候选）· 对齐 CC {@code return []}。 */
        public static final Decision NONE = new Decision(List.of(), false, false);
    }
}
