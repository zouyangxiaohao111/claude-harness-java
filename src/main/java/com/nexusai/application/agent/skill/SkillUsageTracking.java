package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * [P1-15] 技能使用追踪 · 对齐 CC utils/suggestions/skillUsageTracking.ts (55 行).
 *
 * <p><b>CC 真源行为（Read 实证，skillUsageTracking.ts:1-55）</b>：
 * <ul>
 *   <li>recordSkillUsage（:13-35）：now=Date.now()；进程内去抖缓存 {@link #lastWriteBySkill}，
 *       60s 内重复调用直接 bail 跳过 saveGlobalConfig（避免 lock+IO，:18）；去抖通过后更新缓存
 *       （:21）并 read-modify-write 持久化 {@code config.skillUsage[skillName] = {usageCount+1, lastUsedAt:now}}（:22-34）。</li>
 *   <li>getSkillUsageScore（:44-55）：无记录返回 0（:47）；{@code daysSinceUse=(Date.now()-lastUsedAt)/(1000*60*60*24)}（:50）；
 *       {@code recencyFactor=Math.pow(0.5, daysSinceUse/7)}（:51，7 天半衰期）；{@code usageCount * Math.max(recencyFactor, 0.1)}（:54，
 *       0.1 下限防老而高频技能完全归零）。</li>
 * </ul>
 *
 * <p><b>持久化通道</b>：CC 写 {@code ~/.nexusai.json} 顶层 {@code skillUsage} 嵌套对象（config.ts:481 schema），
 * Java 等价为 {@link ConfigStorage#writeGlobal(String, Object)} key=skillUsage（FileConfigStorage @Component
 * 生产注入，落地 {@code <user.home>/.nexusai.json}）。未注入 ConfigStorage 时<b>写侧 no-op、读侧返回 0</b>
 * （null-configStorage 语义：CC 无内存兜底分支，getGlobalConfig 恒有值；Java 删除 memoryStore 兜底后，
 * 仅 POJO/测试未接线场景出现 null，不落盘不造记录）。
 *
 * <p><b>时钟控制</b>：{@link #setClock(Supplier)} 包可见（仿 CC testOverrides 模式），测试可快进时间验证
 * 60s 去抖与 7 天半衰期；生产默认 {@code System::currentTimeMillis}（= CC Date.now()）。
 *
 * <p><b>并发</b>：{@link #lastWriteBySkill} 用 ConcurrentHashMap（CC Map 单线程假定，Java 多线程执行器
 * 需线程安全）。
 */
@Component
public class SkillUsageTracking {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageTracking.class);

    /** CC original: SKILL_USAGE_DEBOUNCE_MS = 60_000（skillUsageTracking.ts:3）· 子分钟粒度对 7 天半衰期无关紧要 */
    public static final long SKILL_USAGE_DEBOUNCE_MS = 60_000L;

    /** 进程内去抖缓存：skillName → 上次写入时间戳 · CC original: lastWriteBySkill = new Map<string, number>（skillUsageTracking.ts:7） */
    private final Map<String, Long> lastWriteBySkill = new ConcurrentHashMap<>();

    /**
     * 读改写互斥锁 · 对齐 CC saveGlobalConfig→saveConfigWithLock（config.ts:1153-1156 文件锁）
     * 的 read-modify-write 原子性（P3-30，△-3）。Java 端 {@link ConfigStorage#writeGlobal}
     * 内部已对写持 ReentrantLock，但「读旧值→合并→写回」三步之间仍可能被其他线程插队丢更新；
     * 本锁将整个读改写串行化（进程内；跨进程对齐由 FileConfigStorage 写锁 + 文件原子写承载）。
     */
    private final Object persistLock = new Object();

    /** 时钟源 · CC original: Date.now()（skillUsageTracking.ts:14/:50）；测试经 {@link #setClock} 控制 */
    private Supplier<Long> clock = System::currentTimeMillis;

    /** 可选注入持久化通道 · 生产为 FileConfigStorage @Component；测试可注入 fake 或留空（写侧 no-op、读侧返回 0） */
    private ConfigStorage configStorage;

    /** 全局配置顶层 key · CC original: config.skillUsage（config.ts:481） */
    private static final String GLOBAL_KEY = "skillUsage";

    /** 测试支撑：覆盖时钟源（仿 CC testOverrides 模式；包可见，测试同包访问） */
    void setClock(Supplier<Long> clock) {
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    /** 注入持久化通道（@Autowired(required=false) Spring 可选注入；POJO 测试可手动注入或留空走 no-op + 读侧 0） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setConfigStorage(ConfigStorage configStorage) {
        this.configStorage = configStorage;
    }

    /**
     * 记录技能使用（去抖 + 持久化）· 对齐 CC skillUsageTracking.ts:13-35 recordSkillUsage().
     *
     * <p>60s 内同技能重复调用直接 return（bail 跳过 saveGlobalConfig 避免 lock+IO）；去抖通过后
     * read-modify-write 累加 usageCount 并刷新 lastUsedAt。调用方为 SkillToolImpl.doExecute
     * （CC SkillTool.ts:619，inline+fork 共用一个 invoke 计一次）。
     *
     * @param skillName 技能名（null/blank 忽略 —— ConcurrentHashMap 禁 null key）
     */
    public void recordSkillUsage(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillUsageTracking] recordSkillUsage 忽略空技能名 (CC skillUsageTracking.ts:13)");
            }
            return;
        }
        long now = clock.get(); // CC :14 now = Date.now()
        Long lastWrite = lastWriteBySkill.get(skillName);
        // CC :18-20 去抖 bail：60s 内跳过持久化（子分钟粒度对 7 天半衰期无关紧要）
        if (lastWrite != null && now - lastWrite < SKILL_USAGE_DEBOUNCE_MS) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillUsageTracking] 技能 '{}' 去抖命中 (距上次写 {}ms < {}ms)，跳过持久化 (CC skillUsageTracking.ts:18)",
                        skillName, now - lastWrite, SKILL_USAGE_DEBOUNCE_MS);
            }
            return;
        }
        // CC :21 lastWriteBySkill.set(skillName, now)
        lastWriteBySkill.put(skillName, now);
        persistIncrement(skillName, now);
    }

    /** 持久化一次计数 +1（ConfigStorage 有则写 config.json，无则写侧 no-op）· 对齐 CC :22-34 saveGlobalConfig read-modify-write */
    private void persistIncrement(String skillName, long now) {
        if (configStorage == null) {
            // CC 恒 saveGlobalConfig（getGlobalConfig 恒有值，无内存分支）；Java 生产 FileConfigStorage @Component 恒注入。
            // 未注入（仅 POJO/测试直构）时写侧 no-op，读侧 getUsage 返回 null → 评分 0（删除 memoryStore 兜底后的 null-configStorage 语义）。
            if (log.isDebugEnabled()) {
                log.debug("[SkillUsageTracking] 技能 '{}' 无 ConfigStorage 注入，写侧 no-op（读侧返回 0，CC skillUsageTracking.ts:22-34 无内存兜底分支）",
                        skillName);
            }
            return;
        }
        persistToConfig(skillName, now);
    }

    /** 写 global config：read-modify-write 合并 skillUsage（对齐 CC saveGlobalConfig(current => ({...current, skillUsage: {...}}))） */
    private void persistToConfig(String skillName, long now) {
        // P3-30: 整个读改写持 persistLock 串行化，避免并发插队丢更新（对齐 CC saveConfigWithLock
        // read-modify-write 原子性，config.ts:1153-1156；FileConfigStorage.writeGlobal 内部写锁仅覆盖单次写）。
        synchronized (persistLock) {
            persistToConfigLocked(skillName, now);
        }
    }

    /** 锁内执行的实际读改写（见 {@link #persistToConfig} 说明） */
    private void persistToConfigLocked(String skillName, long now) {
        try {
            Map<String, SkillUsageEntry> current = readUsageFromConfig();
            SkillUsageEntry existing = current.get(skillName);
            long newCount = (existing == null ? 0 : existing.usageCount) + 1;
            current.put(skillName, new SkillUsageEntry(newCount, now));
            Map<String, Map<String, Object>> writeMap = new LinkedHashMap<>();
            for (Map.Entry<String, SkillUsageEntry> e : current.entrySet()) {
                writeMap.put(e.getKey(), Map.of(
                        "usageCount", e.getValue().usageCount,
                        "lastUsedAt", e.getValue().lastUsedAt));
            }
            // CC :28-31 形状: [skillName]: {usageCount: ..., lastUsedAt: ...}
            configStorage.writeGlobal(GLOBAL_KEY, writeMap);
            if (log.isDebugEnabled()) {
                log.debug("[SkillUsageTracking] 技能 '{}' 持久化: usageCount={} lastUsedAt={} (config.json skillUsage, CC skillUsageTracking.ts:22-34)",
                        skillName, newCount, now);
            }
        } catch (Exception e) {
            // CC recordSkillUsage 无错误处理（bail 只避免 lock+IO）; Java 侧捕获避免破坏技能执行链
            log.warn("[SkillUsageTracking] 持久化技能使用失败 skill={}: {} (降级跳过，不影响技能执行)", skillName, e.getMessage());
        }
    }

    /**
     * 从 ConfigStorage 读取当前 skillUsage 映射（无记录 → 空 Map）。
     *
     * <p>P3-32 对齐 CC 单返回形态：生产 FileConfigStorage.jsonNodeToJavaValue 对对象返回原始
     * JsonNode（FileConfigStorage.java:447-448 对象/数组分支），故只保留 JsonNode 单分支；
     * Map 分支已删除（旧为"将来兼容"，实为双轨死代码，FakeConfigStorage 测试已同步为
     * JsonNode 形态与生产一致）。
     */
    private Map<String, SkillUsageEntry> readUsageFromConfig() {
        Map<String, SkillUsageEntry> result = new LinkedHashMap<>();
        Object raw = configStorage.readGlobal(GLOBAL_KEY);
        if (raw instanceof JsonNode node && node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode v = entry.getValue();
                if (v.isObject()) {
                    long count = v.has("usageCount") ? v.get("usageCount").asLong(0) : 0;
                    long lastUsedAt = v.has("lastUsedAt") ? v.get("lastUsedAt").asLong(0) : 0;
                    result.put(entry.getKey(), new SkillUsageEntry(count, lastUsedAt));
                }
            }
        }
        return result;
    }

    /**
     * 技能使用评分（频次 × 近度衰减）· 对齐 CC skillUsageTracking.ts:44-55 getSkillUsageScore().
     *
     * <p>7 天半衰期 {@code 0.5^(daysSinceUse/7)}，0.1 下限防老而高频技能完全归零；无记录返回 0。
     * 供消费方（对齐 CC commandSuggestions.ts:318 最近使用 top-5 / :419 搜索排序）按近度/频次排序。
     *
     * @param skillName 技能名
     * @return 使用评分（0.0 = 无记录）
     */
    public double getSkillUsageScore(String skillName) {
        SkillUsageEntry usage = getUsage(skillName);
        if (usage == null) return 0.0; // CC :47 if (!usage) return 0
        // CC :50-54
        long now = clock.get();
        double daysSinceUse = (now - usage.lastUsedAt) / (1000.0 * 60 * 60 * 24); // CC :50 (1000*60*60*24)
        double recencyFactor = Math.pow(0.5, daysSinceUse / 7); // CC :51 7 天半衰期
        return usage.usageCount * Math.max(recencyFactor, 0.1); // CC :54 0.1 下限
    }

    /** 取某技能的使用记录（ConfigStorage 有则读配置，无则返回 null → 读侧 0） */
    private SkillUsageEntry getUsage(String skillName) {
        if (configStorage == null) {
            return null;
        }
        return readUsageFromConfig().get(skillName);
    }

    /** 单技能使用记录 · CC original: {usageCount, lastUsedAt}（config.ts:481 + skillUsageTracking.ts:29-30） */
    record SkillUsageEntry(long usageCount, long lastUsedAt) {
    }
}
