package com.nexusai.application.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.BootstrapSkillsSeeder;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 出厂预设（Factory Preset）种子 · 全新安装（空库）时预填以用户配置为蓝本的默认 provider/models/settings，
 * 老库（已有任何 provider）绝不写入、不覆盖。
 *
 * <p><b>WHY（用户拍板 Task-A）</b>：新用户首次启动空库时，开箱即有一条 deepseek 无 key provider
 * 与其 3 个 deepseek 模型，以及一组以当前用户 settings 为蓝本的环境开关——填 key 即用；
 * 而非让用户面对一个空 providers 列表无从下手。
 *
 * <p><b>触发</b>：仅当 {@code providers} 表 count==0（全新库）。任何已存在的 provider 行都判定为
 * 「老用户 / 已使用」，跳过（不覆盖任何已有数据）。见 {@link #seedIfFresh()}。
 *
 * <p><b>数据源</b>：classpath {@code factory/factory-preset.json}（随 jar 分发，非运行时外部生成）。
 * <ul>
 *   <li>providers：deepseek（openai_compatible / baseUrl https://api.deepseek.com / enabled，<b>无 key</b>——
 *       api_key_hash / api_key_masked 用空串占位（providers 表 NOT NULL 约束），api_key_encrypted 不写；
 *       hasKey 判定链为 {@code masked != null && !masked.isBlank()}（BillingController:126），空串 → false，
 *       语义正确「无 key」）。</li>
 *   <li>models：3 条挂 deepseek（deepseek-v4-flash / deepseek-v4-pro 为 chat，deepseek-v4-flash-vision-exp
 *       为 multimodal——均照用户库实际 type）。</li>
 *   <li>settings：仅 merge JSON 里出现的键到 id=1 单例行（MyBatis-Flex {@code update(entity)} 忽略 null 列，
 *       等价「只 set JSON 出现的键」）。白名单 {@link #SETTING_APPLIERS}：<b>严禁</b>收录 10 档
 *       {@code *_model_name}、{@code classifier_model}、含 key 列（不预设模型档位、不预设任何密钥）；
 *       {@code permission_mode} 强制 {@code default}（不照搬用户的 bypassPermissions）。</li>
 * </ul>
 *
 * <p><b>失败策略</b>：{@link #run(ApplicationArguments)} 在事务（可选注入 PlatformTransactionManager，
 * 无则直跑）中执行，任一写失败 → 回滚全部 + 中文 warn，<b>不阻断启动</b>——对齐
 * {@link BootstrapSkillsSeeder} 先例（引导类失败非致命）。DB 写入异常向 run 传播以触发回滚，
 * {@code seedIfFresh()} 本身只抛异常不吞（测试可直接调用、断言异常/成功）。
 *
 * <p><b>非 {@code @Transactional} 自调用陷阱说明</b>：{@code run()} → {@code this.seedIfFresh()} 属
 * 自调用，注解事务不会生效，故用 {@link TransactionTemplate} 显式包裹（同 MessageService
 * {@code @Transactional} 职责，避免 AOP 自调用失效）。
 */
@Component
public class FactoryPresetSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FactoryPresetSeeder.class);

    /** classpath 出厂预设资源路径 · 随 jar 分发（非外部目录）。 */
    static final String RESOURCE_PATH = "factory/factory-preset.json";

    /** settings 恒单例行（V1__init_schema.sql INSERT INTO settings (id) VALUES (1)）。 */
    private static final int SETTINGS_SINGLETON_ID = 1;

    private final ProviderMapper providerMapper;
    private final ModelMapper modelMapper;
    private final SettingsMapper settingsMapper;
    private final ObjectMapper objectMapper;

    /** 可选事务管理器：Spring 环境注入 → run() 内用 TransactionTemplate 包裹，失败回滚；测试/无 bean → null 直跑。 */
    @Autowired(required = false)
    private PlatformTransactionManager txManager;

    public FactoryPresetSeeder(ProviderMapper providerMapper, ModelMapper modelMapper,
            SettingsMapper settingsMapper, ObjectMapper objectMapper) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.settingsMapper = settingsMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (txManager != null) {
                TransactionTemplate tt = new TransactionTemplate(txManager);
                Boolean seeded = tt.execute(status -> seedIfFresh());
                if (log.isDebugEnabled()) {
                    log.debug("[FactoryPresetSeeder] run(事务) 完成：seeded={}", seeded);
                }
            } else {
                // 无事务管理器（测试 / 无 spring-tx）：直接执行，异常也由本方法兜底
                seedIfFresh();
            }
        } catch (Exception e) {
            // 失败不阻断启动：回滚（若在事务内）后记录中文错误
            log.warn("[FactoryPresetSeeder] 出厂预设写入失败（已回滚，不阻断启动）: {}", e.toString(), e);
        }
    }

    /**
     * 空库则写出厂预设（幂等一次 · 非空跳过）。
     *
     * <p>注意：本方法<b>不吞异常</b>——写失败向上抛，由 {@link #run(ApplicationArguments)} 的事务边界
     * 回滚并 warn。测试直接调用验证成功路径与跳过路径。
     *
     * @return true = 本次执行了预设写入；false = providers 非空跳过（老库）
     */
    public boolean seedIfFresh() {
        List<ProviderRecord> existing = providerMapper.selectAll();
        if (!existing.isEmpty()) {
            log.info("[FactoryPresetSeeder] providers 表非空(count={})，判定非全新库，跳过出厂预设写入（不覆盖老用户数据）", existing.size());
            return false;
        }

        JsonNode root = readPreset();
        Map<String, String> providerIdByName = insertProviders(root.path("providers"));
        int insertedModels = insertModels(root.path("models"), providerIdByName);
        boolean settingsApplied = applySettings(root.path("settings"));

        log.info("[FactoryPresetSeeder] 全新库出厂预设写入完成：provider {} 个, model {} 个, settings {}",
            providerIdByName.size(), insertedModels, settingsApplied ? "已 merge" : "无预设键");
        return true;
    }

    // ============== providers ==============

    /** 插入 JSON providers 数组（缺省空串占位 api_key_hash/api_key_masked 满足 NOT NULL，无真实 key）。返回 name→id。 */
    private Map<String, String> insertProviders(JsonNode node) {
        Map<String, String> idByName = new LinkedHashMap<>();
        if (node == null || !node.isArray()) {
            return idByName;
        }
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        for (JsonNode p : node) {
            String name = p.path("name").asText(null);
            if (name == null || name.isBlank()) {
                log.warn("[FactoryPresetSeeder] providers[] 存在空 name 条目，跳过");
                continue;
            }
            if (idByName.containsKey(name)) {
                log.warn("[FactoryPresetSeeder] providers[] name='{}' 重复，跳过", name);
                continue;
            }
            ProviderRecord rec = new ProviderRecord();
            rec.setId(generateId("prov"));
            rec.setName(name);
            rec.setType(p.path("type").asText("openai_compatible"));
            rec.setBaseUrl(p.path("baseUrl").asText(""));
            // 空 key 占位：hash/masked 空串满足 NOT NULL；encrypted 不写。hasKey 判定 masked 非空 → false。
            rec.setApiKeyHash("");
            rec.setApiKeyMasked("");
            rec.setApiKeyEncrypted(null);
            rec.setEnabled(p.path("enabled").asBoolean(true));
            rec.setCreatedAt(now);
            rec.setUpdatedAt(now);
            providerMapper.insertSelective(rec);
            idByName.put(name, rec.getId());
            if (log.isDebugEnabled()) {
                log.debug("[FactoryPresetSeeder] 插入出厂 provider: name={} type={} baseUrl={} enabled={} id={}（无 key，空串占位）",
                    name, rec.getType(), rec.getBaseUrl(), rec.getEnabled(), rec.getId());
            }
        }
        return idByName;
    }

    // ============== models ==============

    /** 插入 JSON models 数组（model.provider 引用刚插入的 provider name → id）。返回插入模型数。 */
    private int insertModels(JsonNode node, Map<String, String> providerIdByName) {
        if (node == null || !node.isArray()) {
            return 0;
        }
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        int count = 0;
        for (JsonNode m : node) {
            String providerName = m.path("provider").asText(null);
            String name = m.path("name").asText(null);
            if (providerName == null || name == null || name.isBlank()) {
                log.warn("[FactoryPresetSeeder] models[] 缺 provider/name，跳过: {}", m);
                continue;
            }
            String providerId = providerIdByName.get(providerName);
            if (providerId == null) {
                log.warn("[FactoryPresetSeeder] models[] provider='{}' 不在本次预设 providers 内，跳过模型 '{}'", providerName, name);
                continue;
            }
            ModelRecord rec = new ModelRecord();
            rec.setId(generateId("model"));
            rec.setProviderId(providerId);
            rec.setName(name);
            rec.setTag(m.path("tag").asText("DS"));
            rec.setType(m.path("type").asText("chat"));
            rec.setEnabled(m.path("enabled").asBoolean(true));
            rec.setCreatedAt(now);
            modelMapper.insertSelective(rec);
            count++;
            if (log.isDebugEnabled()) {
                log.debug("[FactoryPresetSeeder] 插入出厂 model: providerId={} name={} tag={} type={} enabled={}",
                    providerId, name, rec.getTag(), rec.getType(), rec.getEnabled());
            }
        }
        return count;
    }

    // ============== settings ==============

    /**
     * settings 白名单 → 应用器。仅收录「非模型档位 / 非密钥 / 非机器相关」的环境标量列。
     * <p><b>安全红线</b>：严禁加入 {@code *_model_name}（10 档）、{@code classifier_model}、
     * 任何含 key 列（如 {@code api_key}）。{@code permission_mode} 强制 {@code default}（忽略 JSON 值）。
     * JSON 里出现不在白名单的键 → warn 跳过（fail-loud，防未来漂移被静默吞掉）。
     */
    private static final Map<String, BiConsumer<SettingsRecord, JsonNode>> SETTING_APPLIERS = buildSettingAppliers();

    private static Map<String, BiConsumer<SettingsRecord, JsonNode>> buildSettingAppliers() {
        Map<String, BiConsumer<SettingsRecord, JsonNode>> m = new LinkedHashMap<>();
        // —— UI 主题（V1 列：theme/font_size/accent/animations_enabled）——
        m.put("theme", (s, v) -> s.setTheme(txt(v)));
        m.put("font_size", (s, v) -> s.setFontSize(txt(v)));
        m.put("accent", (s, v) -> s.setAccent(txt(v)));
        m.put("animations_enabled", (s, v) -> s.setAnimationsEnabled(bool(v)));
        // —— 环境数值档位（W3-1 auto_compact_window / F1 max_output_tokens）——
        m.put("auto_compact_window", (s, v) -> s.setAutoCompactWindow(num(v)));
        m.put("max_output_tokens", (s, v) -> s.setMaxOutputTokens(num(v)));
        // —— WebSearch 非密钥列（V37/V38/V39；api_key/proxy 含密钥可能不入库）——
        m.put("websearch_engine", (s, v) -> s.setWebsearchEngine(txt(v)));
        m.put("websearch_use_small_model", (s, v) -> s.setWebsearchUseSmallModel(bool(v)));
        m.put("websearch_base_url", (s, v) -> s.setWebsearchBaseUrl(txt(v)));
        m.put("websearch_domain_check_url", (s, v) -> s.setWebsearchDomainCheckUrl(txt(v)));
        // —— Agent Swarms（V42）——
        m.put("agent_swarms_enabled", (s, v) -> s.setAgentSwarmsEnabled(bool(v)));
        // —— 全局默认权限模式（V44）：强制 default，不照搬用户 bypassPermissions（拍板规格）——
        m.put("permission_mode", (s, v) -> s.setPermissionMode("default"));
        // —— 自动记忆开关（V34，bool only；auto_memory_directory 绝对路径不预设）——
        m.put("auto_memory_enabled", (s, v) -> s.setAutoMemoryEnabled(bool(v)));
        // —— auto-dream 开关（V56，默认开）——
        m.put("auto_dream_enabled", (s, v) -> s.setAutoDreamEnabled(bool(v)));
        // —— 压缩配置开关 12 列（V52）——
        m.put("auto_compact_enabled", (s, v) -> s.setAutoCompactEnabled(bool(v)));
        m.put("reactive_compact_enabled", (s, v) -> s.setReactiveCompactEnabled(bool(v)));
        m.put("context_collapse_enabled", (s, v) -> s.setContextCollapseEnabled(bool(v)));
        m.put("history_snip_enabled", (s, v) -> s.setHistorySnipEnabled(bool(v)));
        m.put("sm_session_memory_enabled", (s, v) -> s.setSmSessionMemoryEnabled(bool(v)));
        m.put("sm_compact_enabled", (s, v) -> s.setSmCompactEnabled(bool(v)));
        m.put("cached_microcompact_enabled", (s, v) -> s.setCachedMicrocompactEnabled(bool(v)));
        m.put("time_based_mc_enabled", (s, v) -> s.setTimeBasedMcEnabled(bool(v)));
        m.put("time_based_mc_gap_minutes", (s, v) -> s.setTimeBasedMcGapMinutes(num(v)));
        m.put("time_based_mc_keep_recent", (s, v) -> s.setTimeBasedMcKeepRecent(num(v)));
        m.put("disable_compact", (s, v) -> s.setDisableCompact(bool(v)));
        m.put("disable_auto_compact", (s, v) -> s.setDisableAutoCompact(bool(v)));
        // —— 压缩数值 11 列（V54）+ snip nudge 阈值（V55）——
        m.put("cached_microcompact_trigger_threshold", (s, v) -> s.setCachedMicrocompactTriggerThreshold(num(v)));
        m.put("cached_microcompact_keep_recent", (s, v) -> s.setCachedMicrocompactKeepRecent(num(v)));
        m.put("sm_min_tokens", (s, v) -> s.setSmMinTokens(num(v)));
        m.put("sm_min_text_block_messages", (s, v) -> s.setSmMinTextBlockMessages(num(v)));
        m.put("sm_max_tokens", (s, v) -> s.setSmMaxTokens(num(v)));
        m.put("sm_minimum_message_tokens_to_init", (s, v) -> s.setSmMinimumMessageTokensToInit(num(v)));
        m.put("sm_minimum_tokens_between_update", (s, v) -> s.setSmMinimumTokensBetweenUpdate(num(v)));
        m.put("sm_tool_calls_between_updates", (s, v) -> s.setSmToolCallsBetweenUpdates(num(v)));
        m.put("max_consecutive_autocompact_failures", (s, v) -> s.setMaxConsecutiveAutocompactFailures(num(v)));
        m.put("max_ptl_retries", (s, v) -> s.setMaxPtlRetries(num(v)));
        m.put("max_compact_streaming_retries", (s, v) -> s.setMaxCompactStreamingRetries(num(v)));
        m.put("snip_nudge_threshold", (s, v) -> s.setSnipNudgeThreshold(num(v)));
        // —— 提示词对齐门控 12 列（V56；language/output_style 为字符串）——
        m.put("task_reminder_enabled", (s, v) -> s.setTaskReminderEnabled(bool(v)));
        m.put("deferred_tools_delta_enabled", (s, v) -> s.setDeferredToolsDeltaEnabled(bool(v)));
        m.put("system_prompt_boundary_enabled", (s, v) -> s.setSystemPromptBoundaryEnabled(bool(v)));
        m.put("proactive_enabled", (s, v) -> s.setProactiveEnabled(bool(v)));
        m.put("coordinator_mode_enabled", (s, v) -> s.setCoordinatorModeEnabled(bool(v)));
        m.put("skill_search_intent_enabled", (s, v) -> s.setSkillSearchIntentEnabled(bool(v)));
        m.put("scratchpad_enabled", (s, v) -> s.setScratchpadEnabled(bool(v)));
        m.put("frc_enabled", (s, v) -> s.setFrcEnabled(bool(v)));
        m.put("agent_main_thread_enabled", (s, v) -> s.setAgentMainThreadEnabled(bool(v)));
        m.put("verify_plan_reminder_enabled", (s, v) -> s.setVerifyPlanReminderEnabled(bool(v)));
        m.put("language", (s, v) -> s.setLanguage(txt(v)));
        m.put("output_style", (s, v) -> s.setOutputStyle(txt(v)));
        // —— 插件双读回退开关（V61，bool；enabled_plugins 启停 JSON 属机器状态不入库）——
        m.put("plugin_claude_fallback", (s, v) -> s.setPluginClaudeFallback(bool(v)));
        return m;
    }

    /** 供单测断言出厂资源 settings 键 ⊆ 白名单。 */
    static Set<String> supportedSettingsKeys() {
        return SETTING_APPLIERS.keySet();
    }

    /**
     * 把出厂 settings 键 merge 到单例行 id=1。只 set JSON 里出现的键（MyBatis-Flex update 忽略 null 列，
     * 故仅写本实体非 null 字段 = 仅写已 set 的键，其余列保持原值/null）。settings 行缺失（理论 V1 已插入）
     * → update 影响 0 行 → 防御性 insertSelective。
     *
     * @return true = 应用了至少一个键；false = settings 段空 / 无白名单键
     */
    private boolean applySettings(JsonNode settingsNode) {
        if (settingsNode == null || !settingsNode.isObject() || settingsNode.isEmpty()) {
            log.info("[FactoryPresetSeeder] factory-preset.json settings 段为空，跳过 settings merge");
            return false;
        }
        SettingsRecord s = new SettingsRecord();
        s.setId(SETTINGS_SINGLETON_ID);
        boolean anyApplied = false;
        Iterator<Map.Entry<String, JsonNode>> fields = settingsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String key = e.getKey();
            BiConsumer<SettingsRecord, JsonNode> applier = SETTING_APPLIERS.get(key);
            if (applier == null) {
                log.warn("[FactoryPresetSeeder] settings 键 '{}' 不在出厂预设白名单，跳过（模型档位/密钥列严禁预设；如需支持请扩展 SETTING_APPLIERS 并补单测）", key);
                continue;
            }
            applier.accept(s, e.getValue());
            anyApplied = true;
        }
        if (!anyApplied) {
            return false;
        }
        int updated = settingsMapper.update(s);
        if (updated == 0) {
            // settings 单例行缺失（迁移理论已插入，防御性兜底）
            log.warn("[FactoryPresetSeeder] settings 单例行(id=1)不存在，update 影响 0 行，改为防御性 insertSelective");
            settingsMapper.insertSelective(s);
        }
        return true;
    }

    // ============== helpers ==============

    /** 读 classpath 出厂预设 JSON；IO/解析失败包装为 IllegalStateException（Runtime，事务边界可回滚）。 */
    private JsonNode readPreset() {
        try (InputStream in = FactoryPresetSeeder.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少出厂预设资源: " + RESOURCE_PATH);
            }
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取/解析出厂预设资源失败: " + RESOURCE_PATH, e);
        }
    }

    private static String txt(JsonNode v) {
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Boolean bool(JsonNode v) {
        return v == null || v.isNull() ? null : v.asBoolean();
    }

    private static Integer num(JsonNode v) {
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
