package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BooleanSupplier;

/**
 * Bundled skill 惰性启用 gate 单一 source · 对齐 CC 两个 isEnabled 惰性函数。
 *
 * <p><b>无 Spring 依赖</b>：纯静态工具类，供 {@link BundledSkillsBootstrapper} 构造器默认
 * （无参/2/3 参）与 {@link BundledSkillFeatureFlagsConfig} 生产 @Bean 接线注入同一 gate source，
 * 避免测试/生产两套实现漂移。
 *
 * <h2>CC 真源</h2>
 * <ul>
 *   <li>{@link #isKairosCronEnabled(boolean, BooleanSupplier)} ·
 *       CC original: isKairosCronEnabled (ScheduleCronTool/prompt.ts:36-45)</li>
 *   <li>{@link #isAutoMemoryEnabled()} ·
 *       CC original: isAutoMemoryEnabled (memdir/paths.ts:30-56)</li>
 * </ul>
 */
public final class BundledSkillEnabledGates {

    private static final Logger log = LoggerFactory.getLogger(BundledSkillEnabledGates.class);
    private static final ObjectMapper AUTO_MEMORY_SETTINGS_MAPPER = new ObjectMapper();

    /**
     * [C-05 · OPD-CM5-C-05] DB settings 静态桥接（auto_memory_enabled 前端可配列）。
     * 对齐 {@link com.nexusai.application.agent.config.MemoryBareModeConfig} 静态桥接惯例
     * （setter 注入 → 静态字段；POJO {@code new} 场景不触发 Spring → null → 走 settings 文件回落）。
     */
    private static volatile SettingsMapper staticSettingsMapper;

    /**
     * [C-05] 静态桥接 setter · Spring 装配点（SettingsService @PostConstruct）注入 SettingsMapper，
     * 使 {@link #readAutoMemoryEnabledSetting()} 能读 DB settings 列。测试（无 Spring）→ null → 回落文件。
     *
     * @param mapper DB settings mapper（可 null → 清除桥接，回落文件链）
     */
    public static void bridgeSettingsMapper(SettingsMapper mapper) {
        staticSettingsMapper = mapper;
        if (log.isDebugEnabled()) {
            log.debug("[BundledSkillEnabledGates] bridgeSettingsMapper: {}",
                mapper != null ? "已注入(DB autoMemoryEnabled 可用)" : "已清除(回落文件链)");
        }
    }

    private BundledSkillEnabledGates() {
    }

    /**
     * loop isEnabled gate · CC original: isKairosCronEnabled (ScheduleCronTool/prompt.ts:36-45)：
     * <pre>{@code
     * return feature('AGENT_TRIGGERS')
     *   ? !isEnvTruthy(process.env.CLAUDE_CODE_DISABLE_CRON)
     *       && getFeatureValue_CACHED_WITH_REFRESH('tengu_kairos_cron', true, KAIROS_CRON_REFRESH_MS)
     *   : false
     * }</pre>
     *
     * <p>Java 映射：
     * <ul>
     *   <li>{@code feature('AGENT_TRIGGERS')} → {@code agentTriggers} 参数（P2-4
     *       {@link BundledSkillFeatureFlags#agentTriggers()}，生产默认 true；注意 CC 该 flag 还有
     *       注册门控 bundled/index.ts:47，Java 侧 Bootstrapper 已按同一 flag 门控注册，双保险）</li>
     *   <li>{@code CLAUDE_CODE_DISABLE_CRON} → {@link TaskSystemConfig#isEnvTruthy(String)}
     *       （isEnvTruthy 接受集合一致，TaskSystemConfig:310-317）</li>
     *   <li>{@code getFeatureValue_CACHED_WITH_REFRESH('tengu_kairos_cron', true, 5min)} →
     *       {@code kairosCronRuntime} BooleanSupplier（Java 无 GrowthBook；默认 {@code () -> true}
     *       对齐 CC GB 无配置默认 true；可配置源归主 agent C3 决策）</li>
     * </ul>
     *
     * @param agentTriggers     CC feature('AGENT_TRIGGERS') 编译期 flag（Java Spring 运行时等价）
     * @param kairosCronRuntime CC GB 'tengu_kairos_cron' default true 的 Java 供应（默认 () -&gt; true）
     * @return true = loop 可用（对齐 CC 默认：feature 开 + CLAUDE_CODE_DISABLE_CRON 未置真 + GB 默认 true）
     */
    public static boolean isKairosCronEnabled(boolean agentTriggers, BooleanSupplier kairosCronRuntime) {
        if (!agentTriggers) {
            return false;
        }
        if (TaskSystemConfig.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_CRON"))) {
            return false;
        }
        return kairosCronRuntime != null && kairosCronRuntime.getAsBoolean();
    }

    /**
     * remember isEnabled gate · CC original: isAutoMemoryEnabled (memdir/paths.ts:30-56)：
     * <pre>{@code
     * const envVal = process.env.CLAUDE_CODE_DISABLE_AUTO_MEMORY
     * if (isEnvTruthy(envVal)) return false
     * if (isEnvDefinedFalsy(envVal)) return true
     * if (isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE)) return false
     * if (isEnvTruthy(process.env.CLAUDE_CODE_REMOTE)
     *     && !process.env.CLAUDE_CODE_REMOTE_MEMORY_DIR) return false
     * const settings = getInitialSettings()
     * if (settings.autoMemoryEnabled !== undefined) return settings.autoMemoryEnabled
     * return true
     * }</pre>
     *
     * <p>Java 映射（FIX-MC 补全 5 级链）：
     * <ol>
     *   <li>{@code CLAUDE_CODE_DISABLE_AUTO_MEMORY} truthy → false（最高优先）</li>
     *   <li>{@code isEnvDefinedFalsy(CLAUDE_CODE_DISABLE_AUTO_MEMORY)} → true（显式置 0/false/no/off
     *       = 显式开启 · CC paths.ts:35-37，FIX-MC 补 CC 真源缺项）</li>
     *   <li>{@link MemoryBareModeConfig#isBareMode()} → false（--bare 模式关闭 auto-memory；
     *       ODF-A3 统一判定：nexusai.memory.bare-mode 配置 → env CLAUDE_CODE_SIMPLE → false）</li>
     *   <li>{@code CLAUDE_CODE_REMOTE} truthy 且 {@code CLAUDE_CODE_REMOTE_MEMORY_DIR} 未置 → false
     *       （CC paths.ts:44-49）</li>
     *   <li>{@code settings.autoMemoryEnabled}（DB settings 列优先，文件回落 nexusai user settings
     *       {@code ~/.{appName}/settings.json} · CC paths.ts:50-53；<b>[T2 · 决策 D2]</b> 不再读任何
     *       claude settings.json 档）→ 返回；未设 → 默认 true（paths.ts:54）</li>
     * </ol>
     *
     * @return true = remember 可用（默认 true）
     */
    public static boolean isAutoMemoryEnabled() {
        String envVal = System.getenv("CLAUDE_CODE_DISABLE_AUTO_MEMORY");
        if (TaskSystemConfig.isEnvTruthy(envVal)) {
            return false;
        }
        // CC paths.ts:35-37：isEnvDefinedFalsy(envVal) → true（0/false/no/off 显式开启）
        if (TaskSystemConfig.isEnvDefinedFalsy(envVal)) {
            return true;
        }
        if (MemoryBareModeConfig.isBareMode()) {
            return false;
        }
        // CC paths.ts:44-49：CLAUDE_CODE_REMOTE truthy 且 CLAUDE_CODE_REMOTE_MEMORY_DIR 未定义 → OFF
        if (TaskSystemConfig.isEnvTruthy(System.getenv("CLAUDE_CODE_REMOTE"))
            && isEnvBlank(System.getenv("CLAUDE_CODE_REMOTE_MEMORY_DIR"))) {
            return false;
        }
        // CC paths.ts:50-53：settings.autoMemoryEnabled（支持项目级 opt-out）
        Boolean setting = readAutoMemoryEnabledSetting();
        if (setting != null) {
            return setting;
        }
        // CC paths.ts:54：默认 enabled
        return true;
    }

    /** env 值为 null/空串/空白 = 未定义（CC falsy 判定）· Java 侧 TaskSystemConfig 无 isEnvDefined 等价 */
    private static boolean isEnvBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 从可信 settings 源读取 {@code autoMemoryEnabled} · CC original:
     * {@code getInitialSettings().autoMemoryEnabled}（paths.ts:50-53）。
     *
     * <p><b>[T2 · 决策 D2] 不读 claude settings</b>：所有 claude settings.json（用户级
     * {@code ~/.claude/settings.json} + 项目级 {@code .claude/settings.json} +
     * {@code .claude/settings.local.json}）一律不读；nexusai 用独立 settings 结构
     * {@code ~/.{appName}/settings.json}。此前在项目 {@code .claude/settings.json} 里
     * autoMemoryEnabled opt-out 的项目不再生效（预期行为，D2 变更）。
     * policy/flag 源 Java 无对应基础设施 → 登记 N/A。
     *
     * <p><b>public（IMP-M-R2-P0-KAIROS F1 返工）</b>：除 {@link #isAutoMemoryEnabled()} 5 级链外，
     * 供 {@code MemoryPromptBuilder.emitMemdirDisabled} 消费——CC memdir.ts:496-498
     * {@code disabled_by_setting = !isEnvTruthy(env) && getInitialSettings().autoMemoryEnabled === false}
     * 的字面实现需要「显式配置为 false」这一独立于禁用原因的真值（bare/remote 等其它禁用原因
     * 不置位该属性，memdir.ts:492-499）。
     *
     * <p><b>[C-05 · OPD-CM5-C-05] DB 列优先</b>（V34 列 auto_memory_enabled，前端"模型配置页-
     * 环境配置"可配置）：{@link #bridgeSettingsMapper} 已注入时先读 DB settings 单例行（id=1）；
     * 未配置 → 回落 nexusai user settings 文件（{@code ~/.{appName}/settings.json}）。
     *
     * @return autoMemoryEnabled 布尔值；未配置 → null
     */
    public static Boolean readAutoMemoryEnabledSetting() {
        Boolean fromDb = readAutoMemoryEnabledFromDb();
        if (fromDb != null) {
            return fromDb;
        }
        // T2 · 决策 D2：文件回落仅 nexusai user settings（~/.{appName}/settings.json），不读任何 claude settings 档
        return readBooleanSettingFromFile(
            Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json"), "autoMemoryEnabled");
    }

    /**
     * [C-05] 从 DB settings 单例行（id=1）读取 {@code auto_memory_enabled}（V34 列）。
     * 未桥接（null）/行缺失/列未配置 → null（回落 settings 文件链）。
     */
    private static Boolean readAutoMemoryEnabledFromDb() {
        SettingsMapper mapper = staticSettingsMapper;
        if (mapper == null) {
            return null;
        }
        try {
            SettingsRecord row = mapper.selectOneById(1);
            if (row != null && row.getAutoMemoryEnabled() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[BundledSkillEnabledGates] autoMemoryEnabled 来自 DB settings 列: {}",
                        row.getAutoMemoryEnabled());
                }
                return row.getAutoMemoryEnabled();
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[BundledSkillEnabledGates] 读 DB auto_memory_enabled 失败（按无配置处理）: {} - {}",
                    e.getMessage());
            }
        }
        return null;
    }

    /**
     * 从可信 settings 源读取 {@code autoDreamEnabled} · CC original:
     * {@code getInitialSettings().autoDreamEnabled}（autoDream/config.ts:14）。
     *
     * <p><b>[V56 · 用户 2026-08-30 拍板] autoDream 统一走 DB 表列 + 默认开 + 弃文件</b>：
     * 仅读 DB settings 列（V56 列 auto_dream_enabled），<b>不再读 settings.json 文件</b>
     * （旧 local → project → user 三源文件读取已弃用，settings.ts:800-801 序不再适用）。
     * 未配置（DB 列 null / 未桥接）→ null —— 门控消费点
     * {@link com.nexusai.application.agent.memory.AutoDreamConsolidator
     * #isAutoDreamEnabledBySettingsOrEnv()} 回落默认 true（默认开）。
     *
     * <p><b>IMP-MV2-16 消费方</b>：/memory toggle REST 读侧（CC MemoryFileSelector.tsx:207
     * {@code useState(isAutoDreamEnabled)}）+ SettingsService.toDto 设置面。运行期
     * AutoDreamConsolidator 门控消费链归属 D 域 IMP-MV2-13（D2-R5）裁决，本方法只承载
     * settings 真值读取。
     *
     * @return autoDreamEnabled 布尔值；未配置 → null
     */
    public static Boolean readAutoDreamEnabledSetting() {
        return readAutoDreamEnabledFromDb();
    }

    /**
     * [V56] 从 DB settings 单例行（id=1）读取 {@code auto_dream_enabled}（V56 列）。
     * 未桥接（null）/行缺失/列未配置 → null（门控回落默认 true）。对齐
     * {@link #readAutoMemoryEnabledFromDb()}（V34 auto_memory_enabled 先例）惯例。
     */
    private static Boolean readAutoDreamEnabledFromDb() {
        SettingsMapper mapper = staticSettingsMapper;
        if (mapper == null) {
            return null;
        }
        try {
            SettingsRecord row = mapper.selectOneById(1);
            if (row != null && row.getAutoDreamEnabled() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[BundledSkillEnabledGates] autoDreamEnabled 来自 DB settings 列: {}",
                        row.getAutoDreamEnabled());
                }
                return row.getAutoDreamEnabled();
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[BundledSkillEnabledGates] 读 DB auto_dream_enabled 失败（按无配置处理）: {} - {}",
                    e.getMessage());
            }
        }
        return null;
    }

    /**
     * 写 auto-memory / auto-dream 开关 · CC original: {@code updateSettingsForSource('userSettings',
     * {autoMemoryEnabled|autoDreamEnabled})}（settings.ts:416-524）。
     *
     * <p>读-改-写合并：null 参数 = 不触碰该键（CC undefined 不覆盖）。
     *
     * <p><b>[C-05 · OPD-CM5-C-05] DB 主写 + 文件回落（autoMemoryEnabled）</b>：
     * <ul>
     *   <li><b>autoMemoryEnabled</b> → DB settings 列（V34 列 auto_memory_enabled）+ settings.json
     *       双写（读链 {@link #readAutoMemoryEnabledSetting()} DB 优先回落文件，兼容旧读链；
     *       /memory/config 切换（MemoryController）后读链取 DB 新值）。</li>
     *   <li><b>autoDreamEnabled</b> → <b>[V56 · 用户 2026-08-30 拍板] 仅落 DB settings 列</b>
     *       （V56 列 auto_dream_enabled），<b>不再写 settings.json 文件</b>（文件承载键弃用；
     *       读链 {@link #readAutoDreamEnabledSetting()} 仅读 DB）。</li>
     * </ul>
     *
     * <p>DB 未桥接（POJO 单测/无 Spring）→ autoDreamEnabled 落空（无文件回落，行为为 no-op）；
     * autoMemoryEnabled 仅 settings.json（行为不变）。写 settings.json 失败 → 抛
     * {@link RuntimeException}（CC logError + 返回 error 不抛出；Java REST 以 500 结构化失败
     * 表达，不静默 —— MemoryController.createFile mkdir 失败同款 fail-loud）。
     *
     * @param autoMemoryEnabled autoMemoryEnabled 新值（null = 不写该键）
     * @param autoDreamEnabled  autoDreamEnabled 新值（null = 不写该键）
     */
    public static void writeAutoMemoryToggles(Boolean autoMemoryEnabled, Boolean autoDreamEnabled) {
        if (autoMemoryEnabled != null) {
            writeAutoMemoryEnabledToDb(autoMemoryEnabled);
            writeAutoMemoryEnabledToSettingsFile(autoMemoryEnabled);
        }
        if (autoDreamEnabled != null) {
            writeAutoDreamEnabledToDb(autoDreamEnabled);
        }
    }

    /**
     * [C-05] autoMemoryEnabled 落 DB settings 列（V34 列 auto_memory_enabled）。
     * 未桥接（null）→ 跳过（POJO 单测/无 Spring 零行为变化）；写失败 → warn 不抛（REST 主流程
     * 不受 DB 写失败阻断，settings.json 双写已保证一致性）。
     */
    private static void writeAutoMemoryEnabledToDb(Boolean enabled) {
        SettingsMapper mapper = staticSettingsMapper;
        if (mapper == null) {
            return;
        }
        try {
            SettingsRecord row = mapper.selectOneById(1);
            if (row != null) {
                row.setAutoMemoryEnabled(enabled);
                mapper.update(row);
                if (log.isDebugEnabled()) {
                    log.debug("[BundledSkillEnabledGates] autoMemoryEnabled 落 DB settings 列: {}", enabled);
                }
            }
        } catch (Exception e) {
            log.warn("[BundledSkillEnabledGates] autoMemoryEnabled 写 DB settings 列失败（settings.json 已写，可回落）: {}",
                e.toString());
        }
    }

    /**
     * [V56] autoDreamEnabled 落 DB settings 列（V56 列 auto_dream_enabled）· 用户 2026-08-30
     * 拍板「autoDream 统一走 DB 表列 + 默认开 + 弃文件」——不再写 settings.json 文件承载键。
     * 未桥接（null）→ 跳过（POJO 单测/无 Spring 零行为变化）；写失败 → warn 不抛（REST 主流程
     * 不受 DB 写失败阻断）。
     */
    private static void writeAutoDreamEnabledToDb(Boolean enabled) {
        SettingsMapper mapper = staticSettingsMapper;
        if (mapper == null) {
            return;
        }
        try {
            SettingsRecord row = mapper.selectOneById(1);
            if (row != null) {
                row.setAutoDreamEnabled(enabled);
                mapper.update(row);
                if (log.isDebugEnabled()) {
                    log.debug("[BundledSkillEnabledGates] autoDreamEnabled 落 DB settings 列: {}", enabled);
                }
            }
        } catch (Exception e) {
            log.warn("[BundledSkillEnabledGates] autoDreamEnabled 写 DB settings 列失败: {}", e.toString());
        }
    }

    /**
     * [C-05 保留] 仅写 autoMemoryEnabled 到 nexusai user settings
     * （{@code ~/.{appName}/settings.json} · T2/D2 与读链同源，不再写 claude settings.json）。
     *
     * <p><b>[V56 收敛]</b>：原 writeAutoMemoryToggles 把 autoMemoryEnabled + autoDreamEnabled 一起
     * 写文件；autoDreamEnabled 文件承载已弃用（用户 2026-08-30 拍板）→ 本方法只写 autoMemoryEnabled
     * 键（读链 {@link #readAutoMemoryEnabledSetting()} 仍 DB 优先回落文件，兼容旧读链）。
     *
     * <p>读-改-写合并：未知键原样保留（CC mergeWith 对布尔键整体替换 —— 本方法只动
     * autoMemoryEnabled 键，其余字段不动）。写格式 {@code jsonStringify(updated, null, 2) + '\n'}
     * （settings.ts:500-503）。写失败 → 抛 {@link RuntimeException}（CC logError + 返回 error
     * 不抛出；Java REST 以 500 结构化失败表达，不静默 —— MemoryController.createFile mkdir
     * 失败同款 fail-loud）。
     */
    private static void writeAutoMemoryEnabledToSettingsFile(Boolean autoMemoryEnabled) {
        Path file = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json");
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root;
            if (Files.isRegularFile(file)) {
                JsonNode existing = AUTO_MEMORY_SETTINGS_MAPPER.readTree(file.toFile());
                root = (existing != null && existing.isObject())
                    ? (ObjectNode) existing
                    : AUTO_MEMORY_SETTINGS_MAPPER.createObjectNode();
            } else {
                root = AUTO_MEMORY_SETTINGS_MAPPER.createObjectNode();
            }
            root.put("autoMemoryEnabled", autoMemoryEnabled);
            String json = AUTO_MEMORY_SETTINGS_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root) + "\n";
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to write auto-memory settings to " + file + ": " + e.getMessage(), e);
        }
    }

    private static Boolean readBooleanSettingFromFile(Path file, String key) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = AUTO_MEMORY_SETTINGS_MAPPER.readTree(file.toFile());
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode val = root.get(key);
            if (val != null && val.isBoolean()) {
                return val.asBoolean();
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[BundledSkillEnabledGates] 读取 settings.json {} 失败（按无配置处理）: {} - {}",
                    key, file, e.getMessage());
            }
        }
        return null;
    }
}
