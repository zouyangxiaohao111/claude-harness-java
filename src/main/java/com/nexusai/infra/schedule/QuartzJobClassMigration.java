package com.nexusai.infra.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 一次性补偿：把 QRTZ 表里已持久化的旧 Job 类名改成新名字。
 *
 * <h2>为什么必须有这一步</h2>
 * Quartz 用 JDBC JobStore（{@code application.yml: job-store-type: jdbc}），
 * 每个 job 的 {@code JOB_CLASS_NAME} 是<b>类全名的字符串</b>，不是类型引用 ——
 * 改类名后编译器不会报错，但启动时 Quartz 按旧名字反射加载会失败，
 * <b>已注册的定时任务会全部静默不触发</b>。这是改名唯一真正的坑。
 *
 * <h2>为什么不写成 Flyway 迁移</h2>
 * QRTZ 表由 Quartz 自己建（{@code spring.quartz.jdbc.initialize-schema}），
 * 首次启动时 Flyway 可能先于它执行 ⇒ 迁移里 UPDATE 一张还不存在的表会直接失败。
 * 放在 ApplicationRunner 里则保证"应用起来了 ⇒ 表一定存在（或确实没有）"。
 */
@Component
public class QuartzJobClassMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuartzJobClassMigration.class);

    private static final String OLD_CLASS = "com.nexusai.infra.schedule.TestJob";
    private static final String NEW_CLASS = "com.nexusai.infra.schedule.AgentJob";

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc == null) return;
        try {
            Integer present = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='QRTZ_JOB_DETAILS'",
                Integer.class);
            if (present == null || present == 0) return;

            int updated = jdbc.update(
                "UPDATE QRTZ_JOB_DETAILS SET JOB_CLASS_NAME = ? WHERE JOB_CLASS_NAME = ?",
                NEW_CLASS, OLD_CLASS);
            if (updated > 0) {
                log.info("[Quartz] Job 类名补偿完成：{} 条 {} → {}", updated, OLD_CLASS, NEW_CLASS);
            }
        } catch (Exception e) {
            // 补偿失败不能让应用起不来；但要留明确痕迹，便于排查"定时任务为什么不动"
            log.warn("[Quartz] Job 类名补偿失败（旧类名 {}）：{}", OLD_CLASS, e.toString());
        }
    }
}
