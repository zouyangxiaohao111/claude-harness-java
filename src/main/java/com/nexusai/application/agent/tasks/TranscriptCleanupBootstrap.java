package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.SessionStorage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * transcript 留存清理触发编排 · 对齐 CC {@code startBackgroundHousekeeping}（backgroundHousekeeping.ts:80-83
 * 的 {@code runVerySlowOps} 一次性 session 文件清理）+ {@code cleanupOldMessageFilesInBackground} 的 guard
 * （cleanup.ts:575-585）。
 *
 * <p>启动后延迟 {@value #DELAY_MINUTES} 分钟一次性清理旧 transcript（.jsonl/.cast）与 tool-results 文件，
 * <b>非周期</b>（CC 外部构建 session 文件清理 non-recurring；24h setInterval 仅 ant-only npm/版本清理，
 * 不含 session 文件）。guard：settings.json 显式设置了 {@code cleanupPeriodDays} 但值非法
 * （非整数/负数）→ 跳过本次清理（防用户意图漂移时误删）。
 */
@Component
public class TranscriptCleanupBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TranscriptCleanupBootstrap.class);
    private static final ObjectMapper SETTINGS_MAPPER = new ObjectMapper();

    /** 启动后延迟分钟 · 对齐 CC backgroundHousekeeping.ts:26 DELAY_VERY_SLOW_OPERATIONS_THAT_HAPPEN_EVERY_SESSION = 10min */
    static final long DELAY_MINUTES = 10;
    /** 缺省留存周期天 · 对齐 CC cleanup.ts:23 DEFAULT_CLEANUP_PERIOD_DAYS = 30 */
    public static final int DEFAULT_CLEANUP_PERIOD_DAYS = 30;
    /** 非法显式值哨兵：键存在但值非法（非整数/负数）→ 返回该哨兵，guard 据此跳过本次清理。 */
    private static final Integer INVALID_EXPLICIT_SETTING = -1;

    /** single daemon thread 调度器（不阻止应用退出） */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "transcript-cleanup");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void run(ApplicationArguments args) {
        Integer days = readCleanupPeriodDays();
        if (skipDueToInvalidExplicitSetting(days)) {
            if (log.isWarnEnabled()) {
                log.warn("TranscriptCleanupBootstrap: settings.json 显式设置了 cleanupPeriodDays 但值非法"
                    + "（非整数/负数），跳过本次清理（对齐 CC cleanup.ts:575-585 防误删）");
            }
            return;
        }
        int effective = days == null ? DEFAULT_CLEANUP_PERIOD_DAYS : days;
        Instant cutoff = SessionStorage.getCutoffDate(effective);
        if (log.isInfoEnabled()) {
            log.info("TranscriptCleanupBootstrap: 启动后延迟 {} 分钟清理旧 transcript 已排定"
                + "（cleanupPeriodDays={} cutoff={}）", DELAY_MINUTES, effective, cutoff);
        }
        scheduler.schedule(() -> {
            SessionStorage.CleanupResult result = SessionStorage.cleanupOldSessionFiles(cutoff);
            if (log.isInfoEnabled()) {
                log.info("TranscriptCleanupBootstrap: 启动后延迟清理完成 cutoff={} 删除 {} 文件 错误 {}",
                    cutoff, result.messages(), result.errors());
            }
        }, DELAY_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 读 {~/.{appName}}/settings.json 的 {@code cleanupPeriodDays} 整数键（nexusai user settings ·
     * T2/决策 D2 不再读 claude settings.json，对齐 CC {@code getSettings_DEPRECATED()} 无项目上下文
     * 时落 user 源的语义但源改 nexusai 自有根）。
     *
     * <p>键缺失 → null；键存在且非负整数 → 其值；键存在但非法（非整数/负数）→
     * {@link #INVALID_EXPLICIT_SETTING} 哨兵（由 {@link #skipDueToInvalidExplicitSetting} 判定跳过，
     * 用于区分"未配置"与"显式配置非法"）。
     *
     * @return 留存天数；null = 未配置；{@link #INVALID_EXPLICIT_SETTING} = 显式配置但非法
     */
    static Integer readCleanupPeriodDays() {
        Path file = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = SETTINGS_MAPPER.readTree(file.toFile());
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode val = root.get("cleanupPeriodDays");
            if (val == null || val.isNull()) {
                return null;
            }
            if (!val.isIntegralNumber()) {
                // 显式存在但非整数（如 "abc" / 30.5）→ 哨兵触发 guard 跳过
                return INVALID_EXPLICIT_SETTING;
            }
            int days = val.asInt();
            return days < 0 ? INVALID_EXPLICIT_SETTING : days;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("TranscriptCleanupBootstrap: 读取 settings.json 失败（按无配置处理）: {} - {}",
                    file, e.getMessage());
            }
            return null;
        }
    }

    /**
     * guard · 显式设置了 cleanupPeriodDays 但值非法 → true（跳过本次清理）。
     * 对齐 CC cleanup.ts:575-585：settings 有校验错误且显式存在 cleanupPeriodDays → skip。
     * Java 以"键存在但值非法（非整数/负数）"近似"校验错误"语义（SettingsValidator 为单键校验器，
     * 无全量错误聚合）。
     *
     * @param days {@link #readCleanupPeriodDays()} 返回值
     * @return true = 显式配置非法，跳过清理；null / 合法值 → false
     */
    static boolean skipDueToInvalidExplicitSetting(Integer days) {
        return INVALID_EXPLICIT_SETTING.equals(days);
    }
}
