package com.nexusai.infra.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRON-C1 Quartz cluster lock (SQLite adapter) verification.
 *
 * WHY: CC cross-process single-scheduler lock intent (cronTasksLock.ts:64-91 O_EXCL +
 * cronScheduler.ts:350 if(isOwner) gate) is expressed by Quartz cluster lock
 * (OPD-Cron-04-01). SQLite lacks SELECT FOR UPDATE so SQLiteClusterSemaphore grabs the
 * RESERVED write lock via idempotent INSERT OR IGNORE. Covers: config assertion,
 * cross-connection mutex, dual-cluster-scheduler single fire.
 */
class SQLiteClusterSemaphoreTest {

    @TempDir
    Path tmpDir;

    @Test
    @DisplayName("application.yml quartz cluster config: isClustered + instanceId=AUTO + SQLiteClusterSemaphore")
    void applicationYmlConfiguresQuartzCluster() throws Exception {
        // WHY: Quartz does NOT strongly validate lockHandler type in cluster mode (javap check on
        // quartz-2.5.0 JobStoreSupport); a silent fallback to SimpleSemaphore would start
        // non-cluster-safe. The YAML assertion is the guard.
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
                loader.load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();
        PropertySource<?> ps = sources.get(0);

        assertThat(ps.getProperty("spring.quartz.properties.org.quartz.jobStore.isClustered"))
                .isEqualTo(true); // YAML 解析为布尔
        // WHY: 锁接管探测对齐 CC cronScheduler.ts:44 LOCK_PROBE_INTERVAL_MS=5000（:434 setInterval）：
        // 非 owner 会话每 5s 探测锁、owner 崩溃即接管。Quartz clusterCheckinInterval 默认 7500ms，
        // 不收紧则接管延迟被抬到 7.5s，偏离 CC 5s 行为（决策 #16 / open-decisions.md:147）。
        assertThat(ps.getProperty("spring.quartz.properties.org.quartz.jobStore.clusterCheckinInterval"))
                .isEqualTo(5000); // YAML 无引号数字解析为 Integer，Spring Boot 透传 Quartz properties
        assertThat(ps.getProperty("spring.quartz.properties.org.quartz.jobStore.lockHandler.class"))
                .isEqualTo("com.nexusai.infra.schedule.SQLiteClusterSemaphore");
        assertThat(ps.getProperty("spring.quartz.properties.org.quartz.scheduler.instanceId"))
                .isEqualTo("AUTO");
    }

    @Test
    @DisplayName("mutex: A holds write lock, B obtainLock blocks, succeeds after A commit")
    void semaphoreMutexAcrossConnections() throws Exception {
        // WHY: the core invariant is cross-connection/cross-JVM exclusion backed by the SQLite
        // write lock. If INSERT OR IGNORE failed to grab RESERVED, B would pass concurrently and
        // double-fire protection would be void.
        Path db = tmpDir.resolve("mutex.db");
        String url = "jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=500";
        Class.forName("org.sqlite.JDBC");
        try (Connection init = DriverManager.getConnection(url)) {
            try (Statement st = init.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS QRTZ_LOCKS(SCHED_NAME VARCHAR(120) NOT NULL, "
                        + "LOCK_NAME VARCHAR(40) NOT NULL, PRIMARY KEY (SCHED_NAME, LOCK_NAME))");
                st.execute("INSERT OR IGNORE INTO QRTZ_LOCKS(SCHED_NAME, LOCK_NAME) "
                        + "VALUES ('s1', 'TRIGGER_ACCESS')");
            }
        }

        SQLiteClusterSemaphore sem = new SQLiteClusterSemaphore();
        // DBSemaphore.setExpandedSQL 仅在 schedName 非空时才展开 SQL（生产由 JobStoreSupport
        // 通过 SchedulerNameAware 注入）；直接 new 时需手动设置以匹配 QRTZ_LOCKS 的 SCHED_NAME。
        sem.setSchedName("s1");
        try (Connection a = DriverManager.getConnection(url);
             Connection b = DriverManager.getConnection(url)) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);

            assertThat(sem.obtainLock(a, "TRIGGER_ACCESS"))
                    .as("A should acquire write lock").isTrue();

            AtomicInteger bResult = new AtomicInteger(-1);
            Thread bThread = new Thread(() -> {
                try {
                    bResult.set(sem.obtainLock(b, "TRIGGER_ACCESS") ? 1 : 0);
                } catch (Exception e) {
                    bResult.set(-2);
                }
            });
            bThread.start();
            Thread.sleep(300);
            assertThat(bResult.get())
                    .as("B must still be blocked while A holds the write lock")
                    .isEqualTo(-1);

            a.commit();
            bThread.join(5000);
            assertThat(bResult.get()).as("B should acquire after A commits").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("two clustered schedulers on same SQLite DB fire a job exactly once")
    void twoClusterSchedulersFireOnce() throws Exception {
        // WHY: end-to-end single-scheduler-per-cluster semantics. Both nodes share the DB; the
        // TRIGGER_ACCESS write lock guarantees only one node moves the trigger WAITING->ACQUIRED.
        Path db = tmpDir.resolve("cluster.db");
        String url = "jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000";

        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection(url)) {
            try (InputStream in = getClass().getResourceAsStream(
                    "/org/quartz/impl/jdbcjobstore/tables_sqlite.sql")) {
                assertThat(in).as("tables_sqlite.sql must be on classpath").isNotNull();
                String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // 剔除整行 -- 注释：xerial 3.46.0.0 对纯注释语句抛 "prepared statement has been finalized"
                // （生产由 Spring ResourceDatabasePopulator 剥注释，测试需等价处理）
                StringBuilder sqlOnly = new StringBuilder();
                for (String line : ddl.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("--") || t.isEmpty()) continue;
                    sqlOnly.append(line).append('\n');
                }
                for (String stmt : sqlOnly.toString().split(";")) {
                    String s = stmt.trim();
                    if (!s.isEmpty()) {
                        try (Statement st = c.createStatement()) {
                            st.execute(s);
                        }
                    }
                }
            }
        }

        Scheduler schedA = newScheduler("NODE_A", url);
        Scheduler schedB = newScheduler("NODE_B", url);
        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger fireCount = new AtomicInteger(0);

        try {
            schedA.start();
            schedB.start();

            JobDetail job = JobBuilder.newJob(CountingJob.class)
                    .withIdentity("cluster-job", "cluster-group")
                    .storeDurably(false)
                    .build();
            SimpleTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("cluster-trigger", "cluster-group")
                    .forJob(job)
                    .startAt(new Date(System.currentTimeMillis() + 1500))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0))
                    .build();
            CountingJob.fireCount = fireCount;
            CountingJob.fired = fired;
            schedA.scheduleJob(job, trigger);

            boolean firedOnce = fired.await(15, TimeUnit.SECONDS);
            assertThat(firedOnce).as("job should fire once within timeout").isTrue();
            Thread.sleep(2000);
            assertThat(fireCount.get())
                    .as("clustered job must fire exactly once (double-fire guard)")
                    .isEqualTo(1);
        } finally {
            try {
                schedA.shutdown(true);
            } catch (SchedulerException ignored) {
            }
            try {
                schedB.shutdown(true);
            } catch (SchedulerException ignored) {
            }
            // scheduler.shutdown 不关闭 c3p0 连接池（Quartz 外部管理），否则 Windows 上 SQLite
            // 文件被连接占用，@TempDir 清理失败。两个节点必须用不同 datasource 名（DBConnectionManager
            // 是 JVM 单例，同名会互相覆盖注册，导致只关掉最后一个连接池）。
            // 未实际连过库的节点池可能未注册，shutdown 抛 SQLException —— 忽略即可。
            try {
                org.quartz.utils.DBConnectionManager.getInstance().shutdown(dsName("NODE_A"));
            } catch (java.sql.SQLException ignored) {
            }
            try {
                org.quartz.utils.DBConnectionManager.getInstance().shutdown(dsName("NODE_B"));
            } catch (java.sql.SQLException ignored) {
            }
            // Windows 上 SQLite 文件句柄释放存在延迟（c3p0 异步关闭连接），@TempDir 清理前重试删除。
            // 生产环境不涉及此问题（进程生命周期与文件解耦），此处仅为测试稳定性。
            String[] suffixes = {"", "-wal", "-shm"};
            for (String suffix : suffixes) {
                Path f = tmpDir.resolve("cluster.db" + suffix);
                for (int i = 0; i < 20 && Files.exists(f); i++) {
                    try {
                        Files.deleteIfExists(f);
                    } catch (Exception ignored) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }
    }

    private static String dsName(String instanceId) {
        return "NexusAiQuartzDS_" + instanceId;
    }

    private static Scheduler newScheduler(String instanceId, String url) throws SchedulerException {
        Properties p = new Properties();
        p.setProperty("org.quartz.scheduler.instanceName", "ClusteredTestScheduler");
        p.setProperty("org.quartz.scheduler.instanceId", instanceId);
        p.setProperty("org.quartz.threadPool.threadCount", "2");
        p.setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX");
        p.setProperty("org.quartz.jobStore.driverDelegateClass", "com.nexusai.infra.schedule.SQLiteDelegate");
        p.setProperty("org.quartz.jobStore.dataSource", dsName(instanceId));
        p.setProperty("org.quartz.jobStore.isClustered", "true");
        p.setProperty("org.quartz.jobStore.clusterCheckinInterval", "2000");
        p.setProperty("org.quartz.jobStore.lockHandler.class",
                "com.nexusai.infra.schedule.SQLiteClusterSemaphore");
        p.setProperty("org.quartz.dataSource." + dsName(instanceId) + ".driver", "org.sqlite.JDBC");
        p.setProperty("org.quartz.dataSource." + dsName(instanceId) + ".URL", url);
        return new StdSchedulerFactory(p).getScheduler();
    }

    /** Counting job: increments counter and countDowns latch on each execution. */
    public static class CountingJob implements Job {
        static AtomicInteger fireCount;
        static CountDownLatch fired;

        @Override
        public void execute(JobExecutionContext context) {
            fireCount.incrementAndGet();
            fired.countDown();
        }
    }
}
