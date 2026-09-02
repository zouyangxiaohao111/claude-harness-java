package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SkillToolImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-15] SkillUsageTracking 对齐 CC 测试 · 对齐 CC utils/suggestions/skillUsageTracking.ts (55 行).
 *
 * <p><b>规则九（验证意图）</b>：CC 排行算法依赖三个不变量，测试逐一钉死其"为何重要"：
 * <ul>
 *   <li><b>60s 去抖</b>（skillUsageTracking.ts:18）：排行用 7 天半衰期，子分钟粒度无关紧要 ——
 *       60s 内重复调用必须 bail 跳过持久化，否则每次 invoke 都触发 lock+文件 IO 拖慢技能执行链。</li>
 *   <li><b>7 天半衰期</b>（:51）：{@code 0.5^(daysSinceUse/7)} —— 7 天前的一次使用只值今天的一半，
 *       保证排行反映"最近在用"而非历史累计。</li>
 *   <li><b>0.1 下限</b>（:54）：{@code Math.max(recencyFactor, 0.1)} —— 老而高频的技能不能因时间衰减
 *       完全归零，否则常用技能会从最近使用 top-5 中被零记录技能挤掉。</li>
 * </ul>
 *
 * <p><b>RED 依据</b>：实施前 src/main/java 下 recordSkillUsage/getSkillUsageScore/skillUsage/SkillUsage
 * 均 0 命中（grep 实证，探查 E-08/E-14 复验）—— 新符号编译失败即 RED；本测试断言全部基于新行为。
 *
 * <p><b>持久化双通道验证</b>：
 * <ul>
 *   <li>Fake ConfigStorage（内存 Map）→ 验证 read-modify-write 合并语义（同 key 累加、异 key 保留）。</li>
 *   <li>真实 FileConfigStorage（tempDir）→ 验证 {@code applyValue} Map→JsonNode 转换 + config.json 磁盘形状
 *       （CC config.ts:481 嵌套对象而非字符串）。</li>
 * </ul>
 *
 * <p><b>时钟控制</b>：{@code SkillUsageTracking.setClock} 包可见（CC testOverrides 模式），
 * 用 {@link AtomicLong} 快进时间模拟 60s 去抖窗口与 7 天半衰期。
 */
@DisplayName("[P1-15] SkillUsageTracking（去抖 / 持久化 / 7天半衰期 / 0.1 下限 / doExecute 接线）")
class SkillUsageTrackingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DAY_MS = 1000L * 60 * 60 * 24;

    /** 可快进时钟 · 包内测试用（仿 CC testOverrides）。 */
    private static final class MutableClock {
        long now = 1_000_000_000L;

        long get() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    /**
     * 内存 ConfigStorage fake · 验证 read-modify-write 合并语义（不落盘）。
     *
     * <p>P3-32 与生产 {@link FileConfigStorage} 一致：writeGlobal 把 Map/List → JsonNode
     * （applyValue.toJsonNode 语义，FileConfigStorage.java:378-407），readGlobal 对对象返回原始
     * JsonNode（jsonNodeToJavaValue :447-448 对象分支）—— readUsageFromConfig 只走 JsonNode 单分支。
     */
    private static final class FakeConfigStorage implements ConfigStorage {
        final Map<String, Object> global = new HashMap<>();

        @Override
        public Object readGlobal(String key) {
            return global.get(key);
        }

        @Override
        public void writeGlobal(String key, Object value) {
            global.put(key, MAPPER.valueToTree(value));
        }

        @Override
        public void unsetGlobal(String key) {
            global.remove(key);
        }

        @Override
        public Object readSettings(List<String> path) {
            return null;
        }

        @Override
        public void writeSettings(List<String> path, Object value) {
            // unused
        }

        @Override
        public void unsetSettings(List<String> path) {
            // unused
        }

        @Override
        public void addChangeListener(ConfigChangeListener listener) {
            // unused
        }

        @Override
        public void removeChangeListener(ConfigChangeListener listener) {
            // unused
        }
    }

    /** 从 FakeConfigStorage 的 skillUsage 全局键读某技能 usageCount（等价重建被删的 getUsageCount 观察点）。 */
    private static long usageCountOf(FakeConfigStorage storage, String skill) {
        return usageFieldOf(storage, skill, "usageCount");
    }

    /** 从 FakeConfigStorage 的 skillUsage 全局键读某技能 lastUsedAt（等价重建被删的 getLastUsedAt 观察点）。 */
    private static long lastUsedAtOf(FakeConfigStorage storage, String skill) {
        return usageFieldOf(storage, skill, "lastUsedAt");
    }

    /**
     * FakeConfigStorage.global["skillUsage"] 形状 = JsonNode 对象
     * （P3-32 与生产 FileConfigStorage 一致，writeGlobal 已 Map→JsonNode）。
     */
    private static long usageFieldOf(FakeConfigStorage storage, String skill, String field) {
        Object raw = storage.readGlobal("skillUsage");
        if (raw instanceof JsonNode node) {
            return node.path(skill).path(field).asLong(0);
        }
        return 0L;
    }

    // ── 无记录 / 首次记录 / 只读访问器 ─────────────────────────────────────

    @Test
    @DisplayName("无记录 → getSkillUsageScore 返回 0（CC skillUsageTracking.ts:47）")
    void noRecord_returnsZero() {
        // WHY: CC :47 if (!usage) return 0 —— 从未用过的技能分数必须为 0，否则零记录技能会
        //   以非法分数混进最近使用 top-5（commandSuggestions.ts:318 filter score>0 会放行错误条目）。
        SkillUsageTracking tracking = new SkillUsageTracking();
        assertThat(tracking.getSkillUsageScore("never-used")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("首次 recordSkillUsage → usageCount=1 + lastUsedAt=now 持久化（CC :21-34）")
    void firstRecord_persistsCountAndTimestamp() {
        // WHY: 排行同时依赖频次(usageCount)与近度(lastUsedAt)（:54 两因子相乘），两者缺一
        //   评分失真：只有 count 无 timestamp 无法算 7 天半衰期，只有 timestamp 无 count 无法区分
        //   用过 1 次与 100 次。
        MutableClock clock = new MutableClock();
        FakeConfigStorage storage = new FakeConfigStorage();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setClock(clock::get);
        tracking.setConfigStorage(storage);

        tracking.recordSkillUsage("skill-a");

        assertThat(usageCountOf(storage, "skill-a"))
                .as("首次记录 usageCount=1 (CC :29 existing?.usageCount ?? 0 + 1)")
                .isEqualTo(1);
        assertThat(lastUsedAtOf(storage, "skill-a"))
                .as("lastUsedAt=now (CC :30)")
                .isEqualTo(clock.now);
    }

    @Test
    @DisplayName("空技能名 → 忽略不抛错（ConcurrentHashMap 禁 null key）")
    void blankSkillName_isIgnored() {
        // WHY: CC recordSkillUsage 入参恒非 null（commandName 来自调用方）；Java 守卫在触达
        //   ConcurrentHashMap.get/put（禁 null key）之前拦截 null/blank —— 防御性但必要，
        //   否则 doExecute 传 null 技能名会 NPE 中断技能执行链。
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.recordSkillUsage(null);
        tracking.recordSkillUsage("   ");
        assertThat(tracking.getSkillUsageScore("   "))
                .as("blank 技能名不产生记录")
                .isZero();
        assertThat(tracking.getSkillUsageScore("real"))
                .as("空名忽略不影响真实技能记录查询")
                .isEqualTo(0.0);
    }

    // ── 60s 去抖 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("60s 内二次调用去抖 bail：持久化不变（CC :18-20）；61s 后 → usageCount=2")
    void debounce_withinWindow_thenAfterWindow() {
        // WHY: :18 去抖是性能闸 —— 60s 内跳过 saveGlobalConfig 避免 lock+IO 拖慢技能执行链。
        //   若去抖失效（每次 invoke 都写盘），高频技能调用直接放大 IO 开销；
        //   若 61s 后仍不写（去抖过度），频次数据失真（用 10 次只记 1 次）。
        MutableClock clock = new MutableClock();
        FakeConfigStorage storage = new FakeConfigStorage();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setClock(clock::get);
        tracking.setConfigStorage(storage);

        long t0 = clock.now;
        tracking.recordSkillUsage("skill-a"); // t0: 首次写
        assertThat(usageCountOf(storage, "skill-a")).isEqualTo(1);

        clock.advance(1); // t0+1ms，仍在 60s 窗口内
        tracking.recordSkillUsage("skill-a"); // 去抖 bail
        assertThat(usageCountOf(storage, "skill-a"))
                .as("窗口内二次调用被去抖，usageCount 仍为 1 (CC :18-20 bail)")
                .isEqualTo(1);
        assertThat(lastUsedAtOf(storage, "skill-a"))
                .as("去抖 bail 不改 lastUsedAt")
                .isEqualTo(t0);

        clock.advance(SkillUsageTracking.SKILL_USAGE_DEBOUNCE_MS); // t0+61s，出窗口
        tracking.recordSkillUsage("skill-a");
        assertThat(usageCountOf(storage, "skill-a"))
                .as("出窗口后第三次调用 → usageCount=2 (CC :29)")
                .isEqualTo(2);
        assertThat(lastUsedAtOf(storage, "skill-a"))
                .as("lastUsedAt 刷新为最新时间")
                .isEqualTo(t0 + 1 + SkillUsageTracking.SKILL_USAGE_DEBOUNCE_MS);
    }

    @Test
    @DisplayName("去抖按技能隔离：技能 A 写入不影响技能 B 的去抖窗口")
    void debounce_isPerSkill() {
        // WHY: :15 lastWriteBySkill.get(skillName) 以技能名为键 —— 窗口是 per-skill 的。
        //   若共用全局窗口，一个高频技能会压掉所有其他技能的去抖，导致其它技能频次失真。
        MutableClock clock = new MutableClock();
        FakeConfigStorage storage = new FakeConfigStorage();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setClock(clock::get);
        tracking.setConfigStorage(storage);

        tracking.recordSkillUsage("a");
        clock.advance(10);
        tracking.recordSkillUsage("b"); // b 无历史，不受 a 的窗口影响
        assertThat(usageCountOf(storage, "a")).isEqualTo(1);
        assertThat(usageCountOf(storage, "b"))
                .as("技能 B 首次写入不被技能 A 的窗口拦截")
                .isEqualTo(1);
    }

    // ── read-modify-write 合并（Fake ConfigStorage）───────────────────────

    @Test
    @DisplayName("read-modify-write 合并：同 key 累加 + 异 key 保留（CC :22-34 saveGlobalConfig(current=>...)）")
    void readModifyWrite_preservesOtherSkills_andAccumulatesSame() {
        // WHY: CC :22-34 是 { ...current, skillUsage: {...current.skillUsage, [skillName]: {...}} } ——
        //   写一条技能必须保留其它技能已有记录，否则每次写都覆盖整个 skillUsage → 前一条记录丢失，
        //   排行只反映最后一次调用（多技能场景评分全错）。
        MutableClock clock = new MutableClock();
        FakeConfigStorage storage = new FakeConfigStorage();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setClock(clock::get);
        tracking.setConfigStorage(storage);

        long t0 = clock.now;
        tracking.recordSkillUsage("a"); // {a:{1,t0}}
        clock.advance(1000);
        tracking.recordSkillUsage("b"); // {a:{1,t0}, b:{1,t0+1000}}
        clock.advance(SkillUsageTracking.SKILL_USAGE_DEBOUNCE_MS);
        tracking.recordSkillUsage("a"); // {a:{2,t0+61000}, b:{1,t0+1000}}

        assertThat(usageCountOf(storage, "a")).isEqualTo(2);
        assertThat(usageCountOf(storage, "b"))
                .as("异 key 记录在写 a 时被保留 (read-modify-write 合并)")
                .isEqualTo(1);
        assertThat(lastUsedAtOf(storage, "b"))
                .as("b 的 lastUsedAt 未被 a 的写入覆盖")
                .isEqualTo(t0 + 1000);
    }

    // ── P3-30 并发读改写（对齐 CC saveConfigWithLock 原子性）─────────────

    /**
     * 线程安全的内存 ConfigStorage fake · 验证并发读改写不丢更新（真实 FileConfigStorage 跨线程不安全，故独立 fake）。
     * P3-32 与生产一致：writeGlobal Map→JsonNode（同 {@link FakeConfigStorage}）。
     */
    private static final class ThreadSafeConfigStorage implements ConfigStorage {
        final java.util.concurrent.ConcurrentHashMap<String, Object> global =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Object readGlobal(String key) {
            return global.get(key);
        }

        @Override
        public void writeGlobal(String key, Object value) {
            global.put(key, MAPPER.valueToTree(value));
        }

        @Override
        public void unsetGlobal(String key) {
            global.remove(key);
        }

        @Override
        public Object readSettings(List<String> path) {
            return null;
        }

        @Override
        public void writeSettings(List<String> path, Object value) {
            // unused
        }

        @Override
        public void unsetSettings(List<String> path) {
            // unused
        }

        @Override
        public void addChangeListener(ConfigChangeListener listener) {
            // unused
        }

        @Override
        public void removeChangeListener(ConfigChangeListener listener) {
            // unused
        }
    }

    @Test
    @DisplayName("P3-30: 并发写不同技能 → 读改写持锁串行化，无丢更新（CC saveConfigWithLock 原子性）")
    void concurrentWrites_differentSkills_noLostUpdate() throws Exception {
        // WHY（P3-30 · 规则九）：CC saveGlobalConfig→saveConfigWithLock（config.ts:1153-1156）对
        //   read-modify-write 全程持文件锁 —— 若 Java 端「读旧值→合并→写回」三步可被并发插队，
        //   两个线程分别写 a/b 时可能互读旧快照后各写一份，后写者覆盖前者 → skillUsage 丢一条记录
        //   （EV-WF7-TU-008/027，△-3）。本测试以两个不同技能并发写验证无丢失。
        ThreadSafeConfigStorage storage = new ThreadSafeConfigStorage();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setConfigStorage(storage);

        int threads = 8;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            String skill = "skill-" + (i % 2); // 两个不同技能交错并发
            workers[i] = new Thread(() -> tracking.recordSkillUsage(skill));
        }
        for (Thread t : workers) {
            t.start();
        }
        for (Thread t : workers) {
            t.join(5_000);
        }

        Object raw = storage.global.get("skillUsage");
        assertThat(raw)
                .as("skillUsage 全局键必须被写入（CC config.ts:481）")
                .isNotNull();
        assertThat(raw)
                .as("P3-32 与生产一致：持久化形状为 JsonNode 对象（FileConfigStorage toJsonNode）")
                .isInstanceOf(JsonNode.class);
        JsonNode usageNode = (JsonNode) raw;
        assertThat(usageNode.has("skill-0"))
                .as("并发写后 skill-0 必须存在（无丢更新）")
                .isTrue();
        assertThat(usageNode.has("skill-1"))
                .as("并发写后 skill-1 必须存在（无丢更新）")
                .isTrue();
    }

    // ── 真实 FileConfigStorage（applyValue Map→JsonNode + 磁盘形状）────────

    @Test
    @DisplayName("真实 FileConfigStorage：skillUsage 以嵌套 JSON 对象落盘（CC config.ts:481），非 toString 字符串")
    void realFileConfigStorage_persistsNestedObjectShape(@TempDir Path tempDir) throws Exception {
        // WHY: CC config.ts:481 skillUsage 是全局配置嵌套对象。若 applyValue 对 Map 走 String.valueOf
        //   兜底 → config.json 里是 "skillUsage":"{...}" 字符串，读侧 jsonNodeToJavaValue 返回 asText，
        //   无法恢复 {usageCount, lastUsedAt} 数值结构 —— 跨会话排行数据损坏。
        // 决策 D1：FileConfigStorage 单参（ConfigStorageProperties），缺省 global = {user.home}/.nexusai.json
        //   —— 覆写 user.home 隔离写盘到临时目录（防污染真实 ~/.nexusai.json），用后恢复。
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            FileConfigStorage storage = new FileConfigStorage(null);
            MutableClock clock = new MutableClock();
            SkillUsageTracking tracking = new SkillUsageTracking();
            tracking.setClock(clock::get);
            tracking.setConfigStorage(storage);

            tracking.recordSkillUsage("real-skill");

            // 读侧：FileConfigStorage.readGlobal 对对象返回原始 JsonNode（jsonNodeToJavaValue :407-408）
            Object raw = storage.readGlobal("skillUsage");
            assertThat(raw)
                    .as("readGlobal('skillUsage') 必须返回 JsonNode 对象而非字符串")
                    .isInstanceOf(JsonNode.class);
            JsonNode usageNode = (JsonNode) raw;
            assertThat(usageNode.path("real-skill").path("usageCount").asLong())
                    .as("落盘形状 = 嵌套对象 {skillName:{usageCount,lastUsedAt}}")
                    .isEqualTo(1);
            assertThat(usageNode.path("real-skill").path("lastUsedAt").asLong())
                    .isEqualTo(clock.now);

            // 磁盘形状：JSON 对象字段存在（非被 String.valueOf 序列化成 "[...]=1" 之类）
            String fileContent = Files.readString(tempDir.resolve(".nexusai.json"));
            assertThat(fileContent)
                    .as(".nexusai.json 必须含 usageCount 字段（嵌套对象形状，CC config.ts:481）")
                    .contains("usageCount");
        } finally {
            if (originalUserHome != null) {
                System.setProperty("user.home", originalUserHome);
            }
        }
    }

    // ── 7 天半衰期 + 0.1 下限 ─────────────────────────────────────────────

    @Test
    @DisplayName("d=7 天 → 评分 = usageCount * 0.5（7 天半衰期，CC :51）")
    void scoreAfterSevenDays_isHalf() {
        // WHY: :51 recencyFactor=0.5^(daysSinceUse/7) —— 7 天前的使用价值今天的一半。
        //   若半衰期常数错（如 1 天），重技能 3 天后就被轻技能挤出 top-5。
        MutableClock clock = new MutableClock();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setClock(clock::get);
        tracking.setConfigStorage(new FakeConfigStorage());

        tracking.recordSkillUsage("skill-a"); // usageCount=1
        clock.advance(7 * DAY_MS);

        assertThat(tracking.getSkillUsageScore("skill-a"))
                .as("7 天半衰期: 1 * 0.5^(7/7) = 0.5")
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("d 极大 → Math.max(recencyFactor, 0.1) 下限兜底（CC :54），老而高频技能不归零")
    void scoreWithHugeElapsed_floorAt01() {
        // WHY: :54 Math.max(recencyFactor, 0.1) —— 100 天前的使用若完全衰减到 0，高频老技能
        //   会被"用过一次但刚刚"的轻技能挤掉；0.1 下限保留其频次贡献。
        MutableClock clock = new MutableClock();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setClock(clock::get);
        tracking.setConfigStorage(new FakeConfigStorage());

        tracking.recordSkillUsage("old-but-heavy");
        clock.advance(100 * DAY_MS); // daysSinceUse=100

        double score = tracking.getSkillUsageScore("old-but-heavy");
        assertThat(score)
                .as("100 天后 recencyFactor≈5e-5 被 0.1 下限兜住: 1 * max(...,0.1) = 0.1")
                .isEqualTo(0.1);
        assertThat(score)
                .as("评分必须 >0（未归零），否则老而高频技能从排行消失 (CC :54 注释)")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("频次 × 近度：同日两次使用比一次评分翻倍")
    void score_scalesWithFrequency() {
        // WHY: :54 usageCount * recencyFactor —— 频次是乘法因子。若实现误用加法/忽略 count，
        //   用 10 次的技能与用 1 次的技能评分相同，排行失去频次区分度。
        MutableClock clock = new MutableClock();
        FakeConfigStorage storage = new FakeConfigStorage();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setClock(clock::get);
        tracking.setConfigStorage(storage);

        tracking.recordSkillUsage("frequent");
        clock.advance(1);
        tracking.recordSkillUsage("frequent"); // 去抖窗口内 → bail，不计数
        clock.advance(SkillUsageTracking.SKILL_USAGE_DEBOUNCE_MS);
        tracking.recordSkillUsage("frequent"); // 出窗口 → count=2

        assertThat(usageCountOf(storage, "frequent")).isEqualTo(2);
        assertThat(tracking.getSkillUsageScore("frequent"))
                .as("count=2 * recencyFactor(0ms→1.0) = 2.0，两倍于单次")
                .isEqualTo(2.0);
    }

    // ── doExecute 接线（对齐 CC SkillTool.ts:619，inline+fork 共用一个 invoke 计一次）──

    @Test
    @DisplayName("doExecute inline 调用 → skillUsageTracking 记录命中（CC SkillTool.ts:619 recordSkillUsage）")
    void doExecuteInline_recordsSkillUsage(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:619 recordSkillUsage(commandName) 位于 getAllCommands+findCommand 之后、
        //   fork 路由 if 之前 —— inline 技能真实调用必须写 usageCount，否则 getSkillUsageScore 恒 0，
        //   消费方（commandSuggestions.ts:318/419 的 Java 等价物）拿不到排行数据。
        Path skillDir = tempDir.resolve("wired-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: wired-skill\ndescription: 接线测试技能\n---\n# Wired Skill\n\nBODY\n");

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        FakeConfigStorage storage = new FakeConfigStorage();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setConfigStorage(storage);
        SkillToolImpl tool = new SkillToolImpl(registry);
        tool.setSkillUsageTracking(tracking);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "wired-skill");
        tool.execute(new ToolUseBlock("tool-use-1", "Skill", input),
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        assertThat(usageCountOf(storage, "wired-skill"))
                .as("doExecute 经 SkillToolImpl 接线后必须记录技能使用 (CC SkillTool.ts:619)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("doExecute 未命中技能 → 仍记录 usage（P3-7 对齐 CC SkillTool.ts:619 recordSkillUsage 未命中也 record）")
    void doExecuteUnknownSkill_stillsRecordUsage(@TempDir Path tempDir) throws Exception {
        // WHY (P3-7 · 规则九): CC SkillTool.ts:615-619 —— getAllCommands+findCommand 之后无条件
        //   recordSkillUsage(commandName)，对 findCommand 未命中（command undefined）同样 record
        //   （command?.type 可选链继续走 inline，:652/:670 command? 空安全）。Java 旧实现 cmd==null
        //   提前 return → 未命中技能不计，排行数据漏掉"用户尝试了不存在的技能"这一信号；对齐后
        //   未命中技能名进入排行数据（CC 语义），供消费方观察用户输入分布。
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        FakeConfigStorage storage = new FakeConfigStorage();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setConfigStorage(storage);
        SkillToolImpl tool = new SkillToolImpl(registry);
        tool.setSkillUsageTracking(tracking);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "no-such-skill");
        tool.execute(new ToolUseBlock("tool-use-unknown", "Skill", input),
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        assertThat(usageCountOf(storage, "no-such-skill"))
                .as("未命中技能名也必须记录 usage（CC SkillTool.ts:619 recordSkillUsage 未命中也 record）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SkillRegistry.getSkillUsageScore 未注入 → 返回 0；注入后透传")
    void registryGetSkillUsageScore_delegatesWhenInjected() {
        // WHY: 读侧 API 由 SkillRegistry 承载（CC commandSuggestions 以 getAllCommands 为数据源，
        //   消费方从命令注册中心取分）。未注入（POJO/未接线）必须返回 0 不 NPE —— 现有调用方不破。
        SkillRegistry registry = new SkillRegistry(tempDirNotUsed());
        assertThat(registry.getSkillUsageScore("anything"))
                .as("未注入 SkillUsageTracking → 返回 0（POJO 兼容）")
                .isEqualTo(0.0);

        MutableClock clock = new MutableClock();
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.setClock(clock::get);
        tracking.setConfigStorage(new FakeConfigStorage());
        tracking.recordSkillUsage("scored");
        registry.setSkillUsageTracking(tracking);
        assertThat(registry.getSkillUsageScore("scored"))
                .as("注入后 getSkillUsageScore 透传到 SkillUsageTracking (CC :44-55)")
                .isEqualTo(1.0);
    }

    /** 非 Spring 场景 registry 只需一个合法 skillsRoot（SKILL.md 目录可不存在 —— findCommand 返回 null）. */
    private static String tempDirNotUsed() {
        return UUID.randomUUID().toString();
    }

    // ── 未接线 null-configStorage 语义 ────────────────────────────────────

    @Test
    @DisplayName("ConfigStorage 未注入 → 写侧 no-op + 读侧返回 0（无内存兜底，POJO 兼容不 NPE）")
    void withoutConfigStorage_readsZero_noMemoryFallback(@TempDir Path tempDir) throws Exception {
        // WHY: CC recordSkillUsage 恒 saveGlobalConfig（getGlobalConfig 恒有值，无内存分支）；Java 生产
        //   FileConfigStorage @Component 恒注入，未注入只出现在 POJO/测试直构场景。删除 memoryStore 兜底后，
        //   未注入 ConfigStorage 必须写侧 no-op（不 NPE）+ 读侧返回 0（不凭空造记录），否则内存兜底死代码复现。
        SkillUsageTracking tracking = new SkillUsageTracking();
        tracking.recordSkillUsage("mem-only");
        assertThat(tracking.getSkillUsageScore("mem-only"))
                .as("无 ConfigStorage 评分返回 0（CC :47 无记录 → 0，写侧 no-op）")
                .isEqualTo(0.0);

        Path skillDir = tempDir.resolve("mem-wired");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: mem-wired\ndescription: 未接线\n---\n# Mem\n");
        SkillToolImpl tool = new SkillToolImpl(new SkillRegistry(tempDir.toString()));
        tool.setSkillUsageTracking(tracking);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "mem-wired");
        tool.execute(new ToolUseBlock("tool-use-2", "Skill", input),
                ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));
        assertThat(tracking.getSkillUsageScore("mem-wired"))
                .as("doExecute 写侧同样 no-op（无 ConfigStorage 不落盘），读侧返回 0")
                .isZero();
    }
}
