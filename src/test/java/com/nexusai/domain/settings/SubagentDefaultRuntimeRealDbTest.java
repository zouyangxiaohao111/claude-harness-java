package com.nexusai.domain.settings;

import com.nexusai.NexusAiApplication;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [external-agent 2026-11-09] 「默认外部运行时」清空契约 · <b>真实 SQLite</b>。
 *
 * <p><b>WHY 必须上真实库（规则九 · 测试验证意图而非行为）</b>：
 * 本仓的内存 Map 单测（{@code SettingsServiceSubagentDefaultRuntimeTest}）只能证明
 * 「服务层调了 {@code update(entity, false)}」——它<b>证明不了列到底有没有被写成 NULL</b>。
 * 而这个 bug 的全部要害就在那一列上：
 *
 * <ol>
 *   <li>MyBatis-Flex {@code update(entity)} 默认 {@code ignoreNulls=true} ⇒ null 字段被跳过；
 *       {@code update(entity, false)} ⇒ null 字段显式写 NULL。</li>
 *   <li>「改回内置」必须把 {@code subagent_default_runtime} 写成 NULL，
 *       否则 DB 永远留着旧 runtime ⇒ 派发永远回落到它。</li>
 * </ol>
 *
 * <p>这两条都是<b>持久层语义</b>，不是业务层逻辑 ⇒ 只能在真实列上取证。
 * 内存 Map 层的 mock 测试对本 bug 是<b>假绿</b>：把 {@code update(s, false)} 改回
 * {@code update(s)} 时，真实库会留下一条旧值，而 mock 层只有「调用了哪个重载」这一个信号。
 *
 * <p><b>变异自证</b>：把 {@code SettingsService.update} 的清空分支改回 {@code update(s)}
 * ⇒ {@link #clearingDefaultRuntime_writesNullColumn()} 变红（列里仍是 'claude'）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = NexusAiApplication.class)
@DisplayName("[external-agent] 默认外部运行时清空 ⇒ 真实列被写 NULL")
class SubagentDefaultRuntimeRealDbTest {

    // CleanupMode.NEVER：SQLite 文件（.db/-shm/-wal）在测试结束时仍被 Hikari 锁定，
    // JUnit 默认清理会因「文件被占用」抛 IOException（Windows 实测，本仓既有集成测试同款）。
    @TempDir(cleanup = CleanupMode.NEVER)
    static Path tempDir;

    @DynamicPropertySource
    static void freshDb(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", () -> "jdbc:sqlite:"
            + tempDir.resolve("subagent-default-runtime.db").toAbsolutePath()
            + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=5000");
    }

    @Autowired private SettingsService settingsService;
    @Autowired private SettingsMapper settingsMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 直接读列原文（不经任何应用层代码）——「有没有被写」的唯一可信证据。 */
    private String rawDefaultRuntime() {
        return jdbcTemplate.queryForObject(
            "SELECT subagent_default_runtime FROM settings WHERE id = 1", String.class);
    }

    /** 把默认运行时设成某个 runtime id（走服务层正常写路径）。 */
    private void setDefaultRuntime(String runtime) {
        settingsService.update(dtoWithDefaultRuntime(runtime));
    }

    /** 只带 subagentDefaultRuntime 的请求体（其余 72 个组件全 null）。 */
    private static SettingsDto dtoWithDefaultRuntime(String defaultRuntime) {
        return new SettingsDto(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, defaultRuntime,
            null, null);
    }

    @Test
    @DisplayName("「改回内置」（空串）⇒ 真实列 subagent_default_runtime 被写成 NULL（不是留在旧值）")
    void clearingDefaultRuntime_writesNullColumn() {
        // 前置：先配成 claude（模拟用户之前把默认设成了 claude）
        setDefaultRuntime("claude");
        assertThat(rawDefaultRuntime()).as("前置：默认运行时已落库").isEqualTo("claude");

        // 动作：点「改回内置」⇒ 前端发空串
        setDefaultRuntime("");

        // 断言：读**真实列**（不是 DTO、不是实体内存值）
        assertThat(rawDefaultRuntime())
                .as("清空必须把列写成 NULL；留旧值 ⇒ 派发永远回落到它（2026-11-09 真机实测的 bug）")
                .isNull();
    }

    @Test
    @DisplayName("设成 codex ⇒ 真实列落 'codex'（非清空路径不受清空修复影响）")
    void settingDefaultRuntime_writesValue() {
        setDefaultRuntime("codex");
        assertThat(rawDefaultRuntime()).isEqualTo("codex");
    }

    @Test
    @DisplayName("清空后其它 NOT NULL 列分毫不动（清空不能误伤 theme / font_size / accent）")
    void clearingDefaultRuntime_doesNotClobberNotNullColumns() {
        setDefaultRuntime("claude");
        String themeBefore = jdbcTemplate.queryForObject(
            "SELECT theme FROM settings WHERE id = 1", String.class);
        String accentBefore = jdbcTemplate.queryForObject(
            "SELECT accent FROM settings WHERE id = 1", String.class);

        setDefaultRuntime("");

        assertThat(jdbcTemplate.queryForObject("SELECT theme FROM settings WHERE id = 1", String.class))
                .as("theme 是 NOT NULL 列，清空默认运行时绝不能把它写成 NULL（会违反约束）")
                .isEqualTo(themeBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT accent FROM settings WHERE id = 1", String.class))
                .isEqualTo(accentBefore);
    }

    @Test
    @DisplayName("清空后再设 ⇒ 能正常写回（清空不是单向操作）")
    void clearThenSetAgain_restoresNormalWrite() {
        setDefaultRuntime("claude");
        setDefaultRuntime("");
        assertThat(rawDefaultRuntime()).isNull();

        setDefaultRuntime("opencode");
        assertThat(rawDefaultRuntime()).isEqualTo("opencode");
    }

    @Test
    @DisplayName("实体内存值与真实列一致（服务层返回的 DTO 不撒谎）")
    void dto_reflectsPersistedValue() {
        setDefaultRuntime("pi");
        SettingsRecord row = settingsMapper.selectOneById(1);
        assertThat(row.getSubagentDefaultRuntime()).isEqualTo("pi");
        assertThat(settingsService.get().subagentDefaultRuntime()).isEqualTo("pi");
    }
}
