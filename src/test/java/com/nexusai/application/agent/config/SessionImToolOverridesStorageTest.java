package com.nexusai.application.agent.config;

import com.alibaba.fastjson2.JSON;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 新列必须经真实SQLite原子合并，不能被旧实体全行更新覆盖。 */
class SessionImToolOverridesStorageTest {
    @TempDir static Path directory;
    private static SessionMapper mapper;
    private static SQLiteDataSource dataSource;

    @BeforeAll
    static void database() throws Exception {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("im-tools.db"));
        dataSource.setBusyTimeout(10000);
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath())
                .load().migrate();
        MybatisFlexDbTestSupport.resetAndStart(dataSource, SessionMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
    }

    private void create(String id) {
        SessionRecord row = new SessionRecord();
        row.setId(id);
        row.setModelTag("DS");
        row.setTitle("IM工具持久化测试");
        row.setTime("刚刚");
        row.setSessionGroup("default");
        mapper.insertSelective(row);
    }

    private int patch(String id, Map<String, Boolean> updates) throws Exception {
        assertThat(Arrays.stream(SessionMapper.class.getMethods()).map(m -> m.getName()))
                .as("持久化合同要求提供IM原子增量写口")
                .contains("patchImToolOverrides");
        try {
            return (int) SessionMapper.class.getMethod("patchImToolOverrides", String.class, Map.class)
                    .invoke(mapper, id, updates);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private String read(String id) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT im_tool_overrides FROM sessions WHERE id=?")) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    @Test
    void patchPersistsAcrossConnectionsAndDoesNotTouchDefaultSentinel() throws Exception {
        create("sess-im-reopen");
        assertThat(patch("sess-im-reopen", Map.of("ListImChats", true))).isEqualTo(1);
        assertThat(JSON.parseObject(read("sess-im-reopen"))).containsEntry("ListImChats", true);
        assertThat(mapper.selectOneById("sess-im-reopen").getDisabledTools()).isNull();
    }

    @Test
    void staleFullEntityUpdateCannotEraseConcurrentImPatch() throws Exception {
        create("sess-im-stale");
        SessionRecord stale = mapper.selectOneById("sess-im-stale");
        assertThat(patch("sess-im-stale", Map.of("SendImAsUser", false))).isEqualTo(1);
        stale.setBriefMode(1);
        stale.setUnattendedMode(0);
        stale.setDisabledTools("[\"Bash\"]");
        mapper.update(stale);
        assertThat(JSON.parseObject(read("sess-im-stale"))).containsEntry("SendImAsUser", false);
        assertThat(mapper.selectOneById("sess-im-stale").getDisabledTools()).isEqualTo("[\"Bash\"]");
    }

    @Test
    void simultaneousDifferentKeysMergeWithoutLostUpdateOrCrossSessionLeak() throws Exception {
        create("sess-im-concurrent");
        create("sess-im-other");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return patch("sess-im-concurrent", Map.of("ListImChats", true));
            });
            var second = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return patch("sess-im-concurrent", Map.of("SendImAsUser", false));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(JSON.parseObject(read("sess-im-concurrent")))
                .containsEntry("ListImChats", true).containsEntry("SendImAsUser", false);
        assertThat(read("sess-im-other")).isNull();
    }

    @Test
    void forkCopyPropagatesOverridesToTargetOnly() throws Exception {
        create("sess-fork-src");
        create("sess-fork-dst");
        assertThat(patch("sess-fork-src", Map.of("ListImChats", true))).isEqualTo(1);
        assertThat(mapper.copyImToolOverrides("sess-fork-src", "sess-fork-dst")).isEqualTo(1);
        assertThat(JSON.parseObject(read("sess-fork-dst"))).containsEntry("ListImChats", true);
        assertThat(read("sess-fork-src")).isEqualTo("{\"ListImChats\":true}");
    }

    @Test
    void reservedAndUnknownKeysAreRejectedWithoutWrite() throws Exception {
        create("sess-im-invalid-key");
        // 先验证写口存在，避免把“接口不存在”的异常误当作正确拒绝。
        assertThat(patch("sess-im-invalid-key", Map.of("ListImChats", true))).isEqualTo(1);
        for (String name : new String[]{"SendUserMessage", "SendMessage", "Bash", "unknown"}) {
            assertThatThrownBy(() -> patch("sess-im-invalid-key", Map.of(name, true)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(JSON.parseObject(read("sess-im-invalid-key"))).hasSize(1).containsEntry("ListImChats", true);
    }

    @Test
    void malformedStoredJsonIsNotReplacedByAnEnablingPatch() throws Exception {
        create("sess-im-malformed");
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("UPDATE sessions SET im_tool_overrides=? WHERE id=?")) {
            statement.setString(1, "not-json");
            statement.setString(2, "sess-im-malformed");
            statement.executeUpdate();
        }
        assertThat(patch("sess-im-malformed", Map.of("ListImChats", true))).isZero();
        assertThat(read("sess-im-malformed")).isEqualTo("not-json");
    }
}
