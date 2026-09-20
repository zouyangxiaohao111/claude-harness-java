package com.nexusai.application.agent.tool.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeamStatusPublisher;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * [P0-7 · D6] 「删任务/team 目录」不得抛一个外层接不住的错。
 *
 * <p><b>WHY（规则九 · 意图）</b>：原实现把 {@code Files.walk} 的删除写在流内 lambda 里，失败时抛
 * {@link java.io.UncheckedIOException}，而外层只 {@code catch (IOException)} ⇒ <b>穿网</b>：
 * REST 侧变 500、模型工具直接报错，且 {@code TeamDeleteTool.deleteTeamByName} 的收尾
 * （unregisterTeamForSessionCleanup / clearLeaderTeamName / publish "deleted"）全部被跳过
 * ⇒ 首次点「解散」必失败。CC {@code teamHelpers.ts:641-682} 两处 try/catch 全捕获、从不外抛。
 *
 * <p><b>本类锁三件事</b>：
 * <ol>
 *   <li><b>不外抛</b>：条目删不掉时 {@code cleanupTeamDirectories} / {@code deleteTeam} 正常返回；</li>
 *   <li><b>不假宣称成功</b>：有跳过时**不得**出现「已清理…」成功日志，必须出现带计数的 warn
 *       （规则十二：跳过了却说「已清理」比 CC 还差）；</li>
 *   <li><b>收尾条件执行</b>：cleanup 抛异常时 deleteTeamByName 仍执行 unregister/clear/publish
 *       （⛔ try 只包 cleanup 一行）。</li>
 * </ol>
 *
 * <p><b>反向验证（两处同一 helper）</b>：{@code deleteTeam} 与 tasks 目录两处走同一私有 helper
 * ⇒「删一处、两处行为同时变」——两个用例分别覆盖这两个入口。
 *
 * <p>⚠ <b>残留是已知且可接受的</b>（方案不解决残留，只解决「不再外抛」，CC 同样不重试）；
 * 本类**不**断言「首次即删干净」。
 */
class TeamCleanupDeleteRobustnessTest {

    private static final String TEAM = "d6-team";
    private static final String LEAD_SESSION = "sess-d6-lead";
    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        System.setProperty("nexusai.experimental.agent-teams", "true");
    }

    @AfterEach
    void tearDown() {
        TaskService.clearLeaderTeamName(LEAD_SESSION);
        TaskSystemConfig.clearForTest();
    }

    private static ListAppender<ILoggingEvent> captureTeamHelpersLog() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(TeamHelpers.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        return appender;
    }

    private static void stopCapture(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(TeamHelpers.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static boolean hasMessage(ListAppender<ILoggingEvent> logs, Level level, String marker) {
        return logs.list.stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(level))
            .anyMatch(e -> e.getFormattedMessage().contains(marker));
    }

    /**
     * Windows 上「被 {@link RandomAccessFile} 持有的文件」删不掉（JDK 的 NIO 开了 FILE_SHARE_DELETE，
     * 但 {@code RandomAccessFile}/{@code FileOutputStream} 没开 ⇒ 删除抛 AccessDeniedException）。
     * 这正是构造「条目删不掉」现场的确定性手段（已实测 failed=2：文件与父目录都失败）。
     */
    private RandomAccessFile lockFile(Path path) throws Exception {
        return new RandomAccessFile(path.toFile(), "rw");
    }

    private static ToolUseBlock block(String name) {
        return new ToolUseBlock(UUID.randomUUID().toString(), name, JsonNodeFactory.instance.objectNode());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. tasks 目录入口（cleanupTeamDirectories）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cleanupTeamDirectories: 条目删不掉 → **不外抛**；且不得假宣称「已清理」（残留必须带计数 warn）")
    void cleanupTeamDirectories_undeletableEntry_doesNotThrowAndNeverFalselyClaimsSuccess() throws Exception {
        Path tasksDir = tempDir.resolve("tasks").resolve(TeamHelpers.sanitizeName(TEAM));
        Files.createDirectories(tasksDir);
        Files.writeString(tasksDir.resolve("a.json"), "{}");
        Path locked = tasksDir.resolve("locked.json");
        Files.writeString(locked, "{}");

        TeamHelpers helpers = new TeamHelpers();
        ListAppender<ILoggingEvent> captured = captureTeamHelpersLog();
        try (RandomAccessFile raf = lockFile(locked)) {
            // 核心断言 1：绝不外抛（原实现抛 UncheckedIOException，外层 catch(IOException) 接不住）
            helpers.cleanupTeamDirectories(TEAM);

            boolean residue = Files.exists(locked);
            if (IS_WINDOWS) {
                assertThat(residue)
                    .as("Windows 上被 RandomAccessFile 持有的文件删不掉 ⇒ 本用例确实走到了「跳过」分支"
                        + "（否则断言退化为空转）")
                    .isTrue();
            }
            // 核心断言 2：日志必须与实际结果一致（不假宣称成功）
            assertThat(hasMessage(captured, Level.INFO, "已清理任务目录"))
                .as("残留时**不得**出现「已清理任务目录」成功日志（规则十二）")
                .isEqualTo(!residue);
            if (residue) {
                assertThat(hasMessage(captured, Level.WARN, "项未删除"))
                    .as("跳过必须带计数 WARN，用户/日记可判「首次未删干净」")
                    .isTrue();
                assertThat(hasMessage(captured, Level.WARN, "任务目录"))
                    .as("WARN 必须点名是哪个目录类别").isTrue();
            }
            // 可删的兄弟条目仍应被清掉（helper 不是「一失败就全放弃」）
            assertThat(Files.exists(tasksDir.resolve("a.json")))
                .as("只有删不掉的条目留下，其余条目照删").isFalse();
        } finally {
            stopCapture(captured);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. team 目录入口（deleteTeam）—— 反向验证：与上面走同一 helper
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("deleteTeam: 条目删不掉 → **不外抛**（与 tasks 入口同一 helper ⇒ 删一处两处同时变）")
    void deleteTeam_undeletableEntry_doesNotThrow() throws Exception {
        Path teamDir = tempDir.resolve("teams").resolve(TeamHelpers.sanitizeName(TEAM));
        Files.createDirectories(teamDir);
        Files.writeString(teamDir.resolve("config.json"), "{}");
        Path locked = teamDir.resolve("locked.json");
        Files.writeString(locked, "{}");

        TeamHelpers helpers = new TeamHelpers();
        ListAppender<ILoggingEvent> captured = captureTeamHelpersLog();
        try (RandomAccessFile raf = lockFile(locked)) {
            helpers.deleteTeam(TEAM);
            if (IS_WINDOWS) {
                assertThat(Files.exists(locked))
                    .as("Windows 上持有句柄的条目删不掉（本用例确实走到跳过分支）").isTrue();
                assertThat(hasMessage(captured, Level.WARN, "项未删除")).isTrue();
                assertThat(hasMessage(captured, Level.INFO, "已清理team 目录")).isFalse();
            }
            assertThat(Files.exists(teamDir.resolve("config.json")))
                .as("config.json 可删 ⇒ 必须已被清掉").isFalse();
        } finally {
            stopCapture(captured);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. 收尾条件执行（try 只包 cleanup 一行）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cleanup 抛异常时 deleteTeamByName 收尾仍全执行：unregister + clearLeaderTeamName + publish deleted")
    void deleteTeamByName_cleanupThrows_stillRunsTailSteps() {
        // WHY（CC TeamDeleteTool.ts:200-204）：unregister/clear/publish 是**无条件执行**的收尾。
        //   若把 try 包住三行，一旦 cleanup 抛异常收尾全被跳过 ⇒ 前端面板不消失、leader 仍绑已删团队。
        TeamHelpers spyHelpers = spy(new TeamHelpers());
        doThrow(new RuntimeException("模拟 cleanupTeamDirectories 抛错"))
            .when(spyHelpers).cleanupTeamDirectories(anyString());
        TeamStatusPublisher publisher = mock(TeamStatusPublisher.class);

        TeamDeleteTool tool = new TeamDeleteTool(spyHelpers);
        tool.setTeamStatusPublisher(publisher);

        // 前置：leader 已绑本 team（clearLeaderTeamName 是否执行才可观测）
        TaskService.setLeaderTeamName(TEAM, LEAD_SESSION);
        assertThat(TaskService.getTaskListId(LEAD_SESSION, null)).isEqualTo(TEAM);

        AgentToolResult<?> result = tool.deleteTeamByName(TEAM, LEAD_SESSION, 0, "test-d6");

        assertThat(String.valueOf(result.data()))
            .as("REST/工具都不得因 cleanup 异常报错（恒 success，CC TeamDeleteTool 同语义）")
            .contains("Cleaned up directories and worktrees for team");
        verify(spyHelpers).cleanupTeamDirectories(TEAM);
        verify(spyHelpers).unregisterTeamForSessionCleanup(TEAM);
        assertThat(TaskService.getTaskListId(LEAD_SESSION, null))
            .as("clearLeaderTeamName 必须执行（原 0 参调用清不掉任何键 ⇒ 这里会红）")
            .isEqualTo(LEAD_SESSION);
        verify(publisher).publish(eq(TEAM), eq(LEAD_SESSION), eq("deleted"));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. 成功路径：零跳过时才通知任务已更新（对齐 CC teamHelpers.ts:677 只在删除成功后通知）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("tasks 目录确实清掉 → 清理后 notifyTasksUpdated 被触发（前端面板刷新）")
    void cleanupTeamDirectories_fullyRemoved_notifiesTasksUpdated() throws Exception {
        Path tasksDir = tempDir.resolve("tasks").resolve(TeamHelpers.sanitizeName(TEAM));
        Files.createDirectories(tasksDir);
        Files.writeString(tasksDir.resolve("a.json"), "{}");

        int[] notified = {0};
        Runnable listener = () -> notified[0]++;
        Runnable unsubscribe = TaskService.addListener(listener);
        try {
            new TeamHelpers().cleanupTeamDirectories(TEAM);
            assertThat(Files.exists(tasksDir)).as("零跳过 ⇒ 目录应被删净").isFalse();
            assertThat(notified[0])
                .as("目录清掉后必须 notifyTasksUpdated（CC teamHelpers.ts:677）").isGreaterThan(0);
        } finally {
            unsubscribe.run();
        }
    }
}
