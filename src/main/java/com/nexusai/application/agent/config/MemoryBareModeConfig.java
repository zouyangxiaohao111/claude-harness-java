package com.nexusai.application.agent.config;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.common.SessionKeys;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * bare 模式统一判定 · Java 独有增强（CC {@code envUtils.ts} 无对应配置）。
 *
 * <p><b>CC 真源</b>：{@code Open-ClaudeCode/src/utils/envUtils.ts:60-65} {@code isBareMode()}
 * <pre>{@code
 * export function isBareMode(): boolean {
 *   return (
 *     isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE) ||
 *     process.argv.includes('--bare')
 *   )
 * }
 * }</pre>
 * truthy 集 = {@code {1,true,yes,on}}（envUtils.ts:32-38，Java 侧 {@link TaskSystemConfig#isEnvTruthy(String)}
 * 接受集合一致，TaskSystemConfig:345-352）。Java Web 后端无 {@code --bare} argv（main.tsx:284/1015
 * 将 {@code --bare} 置 env 的机制在 Java 不存在），仅建模 env 通道。
 *
 * <p><b>Java 独有增强（owner 拍板方案 C，ODF-A3）</b>：新增配置通道
 * {@code nexusai.memory.bare-mode}（application.yml），CC 无对应配置项。优先级：
 * <ol>
 *   <li>{@code nexusai.memory.bare-mode} 显式设置（Spring {@code @Value} 注入，tri-state
 *       Boolean：null = 未配置）</li>
 *   <li>{@code CLAUDE_CODE_SIMPLE} env truthy（对齐 CC isBareMode 唯一权威通道）</li>
 *   <li>默认 {@code false}（对齐 CC 默认非 bare）</li>
 * </ol>
 *
 * <p><b>静态桥接</b>：{@link #isBareMode()} 为静态（BundledSkillEnabledGates:109 在静态方法内、
 * SkillsLoader:145 为默认 lambda、LlmAgentLoop:3610 为局部变量），Spring 注入值经构造器桥接到
 * 静态字段。POJO {@code new} 场景（单测）不触发 Spring 构造 → 桥接为 null → 走 env 回退；
 * 测试用 {@link #reset()} 防跨测试污染。
 *
 * <p><b>会话级重载（V33，用户 2026-08-23 拍板：bareMode 随会话走）</b>：
 * {@link #isBareMode(String)} 读当前会话 sessions.bare_mode（DB）→ 有值则用之；null /
 * 无会话上下文 / DB 读取失败 → 回落 {@link #isBareMode()}（nexusai.memory.bare-mode →
 * env CLAUDE_CODE_SIMPLE truthy → 默认 false）。SessionMapper 经静态桥接（{@link #bridgeSessionMapper}，
 * setter 注入 → 静态字段，对齐 {@code springConfiguredBareMode} 同款惯例）。
 */
@Component
public class MemoryBareModeConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryBareModeConfig.class);

    /** Spring 注入的配置值桥接（{@code nexusai.memory.bare-mode}）；null = 未配置 = 走 env */
    private static volatile Boolean springConfiguredBareMode;

    /** 会话级 bare 判定 DB 读取桥接（sessions.bare_mode，V33 列）；null = 未注入（POJO 单测/无 Spring）→ 走全局判定 */
    private static volatile SessionMapper sessionMapper;

    /** 测试辅助：env 读取覆盖开关（Java 无法进程内改 env，对齐 SkillsLoader.setBareModeSupplier 同款缝隙） */
    private static boolean envOverrideSet;
    private static String envOverrideValue;

    public MemoryBareModeConfig(@Value("${nexusai.memory.bare-mode:#{null}}") Boolean bareMode) {
        springConfiguredBareMode = bareMode;
        if (bareMode != null) {
            log.info("MemoryBareModeConfig 启动注入 nexusai.memory.bare-mode={}（Java 独有增强，CC envUtils.ts 无对应配置）", bareMode);
        }
    }

    /**
     * Spring 注入 SessionMapper 桥接（setter 注入 → 静态字段，对齐 {@code springConfiguredBareMode}
     * 同款静态桥接惯例；{@code @Autowired} 不支持静态字段/方法）。
     *
     * <p>会话级 bare 判定（V33 列 bare_mode）依赖该 mapper；POJO {@code new} 场景（单测）不触发
     * Spring → 桥接为 null → 会话级判定回落全局 {@link #isBareMode()}。
     *
     * @param mapper 会话 mapper（Spring 注入，可为 null → 跳过桥接）
     */
    @Autowired(required = false)
    public void bridgeSessionMapper(SessionMapper mapper) {
        setSessionMapper(mapper);
    }

    /**
     * 会话 mapper 静态桥接 setter · 测试（同包）+ Spring 桥接共用（对齐 {@code setEnvOverride} 惯例）。
     */
    static void setSessionMapper(SessionMapper mapper) {
        sessionMapper = mapper;
        if (mapper != null) {
            log.info("MemoryBareModeConfig 注入 SessionMapper（会话级 bare_mode 判定可用，V33 列）");
        }
    }

    /**
     * bare 模式统一判定 · 对齐 CC {@code isBareMode()}（envUtils.ts:60-65）+ Java 独有配置覆盖。
     *
     * <p>优先级：配置（{@code nexusai.memory.bare-mode}）→ env {@code CLAUDE_CODE_SIMPLE} truthy
     * → 默认 {@code false}。true = 跳过 auto-memory / auto-dream / skills 自动发现 /
     * prompt-suggestion（对齐 CC isBareMode 门控语义）。
     *
     * @return true = bare 模式
     */
    public static boolean isBareMode() {
        Boolean configured = springConfiguredBareMode;
        if (configured != null) {
            if (log.isDebugEnabled()) {
                log.debug("bare 模式判定：nexusai.memory.bare-mode 配置生效 = {}", configured);
            }
            return configured;
        }
        boolean envBare = TaskSystemConfig.isEnvTruthy(currentEnvBareValue());
        if (log.isDebugEnabled()) {
            log.debug("bare 模式判定：配置未设，env CLAUDE_CODE_SIMPLE 判定 = {}", envBare);
        }
        return envBare;
    }

    /**
     * 会话级 bare 模式判定 · 用户 2026-08-23 拍板：bareMode 随会话走（非全局 settings）。
     *
     * <p>优先级（硬约束）：当前会话 sessions.bare_mode（V33 列，DB）→ 有值则用之（0/1）；
     * null / 无会话上下文 / SessionMapper 未注入 / DB 读取失败 → 回落 {@link #isBareMode()}
     * （nexusai.memory.bare-mode → env CLAUDE_CODE_SIMPLE truthy → 默认 false）。
     *
     * <p>会话键归一化：{@link SessionKeys#originalKey(String)}（"sess-xxx" 原样 / 派生 UUID 反解），
     * 不可反解 → 原样当 DB 键（对齐 EffortCommand.resolveSessionKey）。
     *
     * @param sessionId 会话标识（TUC.sessionId() 派生 UUID / MDC "sess-xxx" / null）
     * @return true = bare 模式（会话显式开启，或回落全局判定命中）
     */
    public static boolean isBareMode(String sessionId) {
        String key = normalizeSessionKey(sessionId);
        SessionMapper mapper = sessionMapper;
        if (key != null && mapper != null) {
            try {
                SessionRecord s = mapper.selectOneById(key);
                if (s != null && s.getBareMode() != null) {
                    boolean sessionBare = s.getBareMode() != 0;
                    if (log.isDebugEnabled()) {
                        log.debug("bare 模式判定：会话 '{}' bare_mode={} 生效（会话级覆盖，V33 列）",
                                key, sessionBare);
                    }
                    return sessionBare;
                }
                if (log.isDebugEnabled()) {
                    log.debug("bare 模式判定：会话 '{}' bare_mode 未设置（null）→ 回落全局判定", key);
                }
            } catch (Exception e) {
                log.warn("bare 模式判定：会话 '{}' bare_mode 读取失败（{}），回落全局判定", key, e.getMessage());
            }
        } else if (log.isDebugEnabled()) {
            log.debug("bare 模式判定：无会话上下文（sessionId={}）或 SessionMapper 未注入 → 走全局判定",
                    sessionId == null ? "null" : sessionId);
        }
        return isBareMode();
    }

    /**
     * 会话键归一化 · [session-id-short] 入参已 short 直返（不再 originalKey 反解——调用方传
     * ctx.sessionId() 已统一形态；存量旧行读取可保留 originalKey 兜底，阶段2 删）。
     */
    private static String normalizeSessionKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String original = SessionKeys.originalKey(sessionId);
        return original != null ? original : sessionId;
    }

    /**
     * 当前 env 通道取值 · 测试覆盖时读 {@link #envOverrideValue}，否则读进程 env。
     */
    private static String currentEnvBareValue() {
        return envOverrideSet ? envOverrideValue : System.getenv("CLAUDE_CODE_SIMPLE");
    }

    /**
     * 测试辅助：覆盖 env 通道取值（Java 无法进程内改 env）。null = 模拟 env 未设置。
     */
    static void setEnvOverride(String value) {
        envOverrideSet = true;
        envOverrideValue = value;
    }

    /**
     * 测试辅助：重置静态桥接与 env 覆盖（防跨测试 / 跨 Spring 上下文污染）。
     */
    public static void reset() {
        springConfiguredBareMode = null;
        sessionMapper = null;
        envOverrideSet = false;
        envOverrideValue = null;
    }
}
