package com.nexusai.application.agent.permission.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * 启动接线 · CLAUDE_CODE_REMOTE → {@link HookEventBus#setAllHookEventsEnabled(boolean)} (D-07).
 *
 * <p>对齐 CC {@code main.tsx:1229-1233} (自验 read):
 * <pre>
 *   // Enable all hook event types when explicitly requested via SDK option
 *   // or when running in CLAUDE_CODE_REMOTE mode (CCR needs them).
 *   // Without this, only SessionStart and Setup events are emitted.
 *   if (includeHookEvents || isEnvTruthy(process.env.CLAUDE_CODE_REMOTE)) {
 *     setAllHookEventsEnabled(true);
 *   }
 * </pre>
 *
 * <p><b>分量说明</b>: CC 条件是 {@code includeHookEvents || CLAUDE_CODE_REMOTE} 两分量的或 —
 * Java 端无 SDK includeHookEvents 等价物 (无 SDK 输出层, 决策 09#1 D-04 判 N/A), 因此本接线
 * 只表达 CLAUDE_CODE_REMOTE 分量. 启动完成前开关恒 false, 对齐 CC 缺省 (仅
 * SessionStart/Setup 过 {@link HookEventBus#ALWAYS_EMITTED_HOOK_EVENTS} 白名单,
 * hookEvents.ts:83-91).
 *
 * <p><b>用 {@link ApplicationRunner} 而非 @PostConstruct</b>: {@link HookEventBus} 是
 * 单例 bean, 但 run() 在全部 bean 创建完成后执行, 不依赖 bean 就绪时序
 * (先例: SkillChangeDetector.java:237-240 — @PostConstruct 不保证依赖 bean 就绪,
 * ApplicationRunner 规避 NPE).
 *
 * <p><b>isEnvTruthy</b>: 按 codebase 惯例私有静态复制 (PluginDirectories.isEnvTruthy 为
 * plugin 包 package-private, 跨包不可用; MarketplaceManager.remoteModeCheck 同款
 * 私有判定), truthy 集合 1/true/yes/on — 对齐 CC envUtils.ts:32-37 (自验 read):
 * {@code ['1','true','yes','on'].includes(value.toLowerCase().trim())}.
 *
 * @see HookEventBus#setAllHookEventsEnabled(boolean)
 * @see HookEventBus#shouldEmit(String)
 * @since Session IMP-HOOKS-S3 (D-07)
 */
@Component
public class HookEventStartupWiring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HookEventStartupWiring.class);

    /** 事件总线 · 启动开关落在其上. */
    private final HookEventBus hookEventBus;

    /**
     * CLAUDE_CODE_REMOTE 判定 · 默认读 env, 测试可覆写
     * (先例: MarketplaceManager.remoteModeCheck, 测试注入 mock supplier).
     */
    private BooleanSupplier envRemoteSupplier = () ->
        isEnvTruthy(System.getenv("CLAUDE_CODE_REMOTE"));

    public HookEventStartupWiring(HookEventBus hookEventBus) {
        this.hookEventBus = hookEventBus;
    }

    /** 测试覆写 · 替换 env 判定 (避免测试依赖真实环境变量). */
    void setEnvRemoteSupplier(BooleanSupplier supplier) {
        this.envRemoteSupplier = supplier;
    }

    /**
     * 启动接线 · 对齐 CC main.tsx:1232-1233.
     *
     * <p>CCR 模式 (CLAUDE_CODE_REMOTE truthy) → 放开全量 hook 事件; 否则保持缺省
     * (仅 SessionStart/Setup). 事件进 buffer 的完整语义 (无 handler 缓冲、满 100 丢最旧、
     * 注册回放) 由 {@link HookEventBus} 承担, 本类只做开关.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (envRemoteSupplier.getAsBoolean()) {
            hookEventBus.setAllHookEventsEnabled(true);
            if (log.isInfoEnabled()) {
                log.info("HOOK CLAUDE_CODE_REMOTE 生效 → 全量 hook 事件已放开 (CC main.tsx:1232-1233)");
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("HOOK CLAUDE_CODE_REMOTE 未设置 → 保持缺省 (仅 SessionStart/Setup 事件)");
            }
        }
    }

    /**
     * CC original: {@code isEnvTruthy} (envUtils.ts:32-37)
     * {@code ['1','true','yes','on'].includes(value.toLowerCase().trim())}.
     */
    private static boolean isEnvTruthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }
}
