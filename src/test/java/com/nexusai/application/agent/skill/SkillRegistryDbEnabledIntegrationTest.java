package com.nexusai.application.agent.skill;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.model.command.Command;
import com.nexusai.repository.command.entity.CommandRecord;
import com.nexusai.repository.command.mapper.CommandMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [DB 集成] SkillRegistry DB enabled 主控 · 真实数据库验证（收尾遗留闭环 · 2026-08-31）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：单元测试 {@link SkillRegistryDbEnabledOverrideTest} 用
 * Mockito mock {@link CommandMapper}，未验证 MyBatis-Flex {@code selectAll()} 在真实 SQLite 的映射
 * （command.enabled INTEGER 0/1 → {@link CommandRecord#getEnabled()} Integer → 覆盖）
 * 与磁盘 SKILL.md 合并的真实行为。本测试用 Flyway 全量迁移的真实 SQLite 验证两件事：
 * <ol>
 *   <li>DB enabled=0（前端禁用）→ {@code getAllCommands()} 排除该 skill（DB 覆盖文件默认 enabled=true，
 *       isCommandEnabled()=false 过滤）——前端禁用真实生效</li>
 *   <li>DB 行同名命中且 enabled=1 → 保留 + SkillRegistry 补 DB 行 id（integration-gap 修复：
 *       SkillsLoader 不 setId → 列表 DTO id=null → 前端 PATCH /{id}/toggle 传 null 写不进 DB；
 *       补 id 后走通全链路）</li>
 * </ol>
 *
 * <p>DB 模式与既有 MCP DB 测试一致：共享 {@link MybatisFlexDbTestSupport#sharedDbPath()} + Flyway
 * migrate + {@code resetAndStart}（单例/mapper 代理缓存重置）。
 */
@DisplayName("[DB 集成] SkillRegistry 真实 DB enabled 覆盖 + id 同步")
class SkillRegistryDbEnabledIntegrationTest {

    private static CommandMapper mapper;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        String dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(dbUrl);
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds, CommandMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(CommandMapper.class);
    }

    /** 写最小 SKILL.md（无 enabled frontmatter → 文件默认 enabled=true）· 对齐 SkillRegistryDbEnabledOverrideTest */
    private static void writeSkill(Path root, String dir, String name) throws Exception {
        Path skillDir = root.resolve(dir);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: " + name + "\n---\n# " + name + "\n");
    }

    /** 幂等落 DB 行：先删同名（共享 flex.db 防残留），再插指定 enabled 行。 */
    private static void upsertEnabled(String name, Integer enabled) {
        mapper.deleteByQuery(QueryWrapper.create().where("name = ?", name));
        CommandRecord rec = new CommandRecord();
        rec.setId("cmd-" + name);
        rec.setName(name);
        rec.setSource("user");
        rec.setEnabled(enabled);
        // insertSelective：只插非 null 字段，其余列走 DB DEFAULT（全字段 insert 会把 is_hidden 等
        // NOT NULL DEFAULT 列显式 NULL → SQLITE_CONSTRAINT_NOTNULL）
        mapper.insertSelective(rec);
    }

    @Test
    @DisplayName("真实 DB：enabled=0 → getAllCommands 排除（DB 覆盖文件默认 enabled，前端禁用真实生效）")
    void realDb_enabledZero_excludesSkill(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "db-disabled-integration", "db-disabled-integration");
        BundledSkills.clear();
        upsertEnabled("db-disabled-integration", 0);

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setCommandMapper(mapper);

        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .as("真实 DB enabled=0 覆盖 SKILL.md 默认 enabled=true → isCommandEnabled=false → 排除（禁用真实生效）")
            .doesNotContain("db-disabled-integration");
    }

    @Test
    @DisplayName("真实 DB：enabled=1 且同名命中 → 保留 + 补 DB 行 id（integration-gap：列表 DTO id 非 null 供前端 toggle）")
    void realDb_enabledOne_keepsAndSyncsId(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "db-id-integration", "db-id-integration");
        BundledSkills.clear();
        upsertEnabled("db-id-integration", 1);

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setCommandMapper(mapper);

        Command hit = registry.getAllCommands().stream()
            .filter(c -> "db-id-integration".equals(c.getName()))
            .findFirst().orElseThrow();
        assertThat(hit.getEnabled()).as("真实 DB enabled=1 覆盖保留").isTrue();
        assertThat(hit.getId()).as("同名 DB 行命中 → SkillRegistry 补 DB 行 id（SkillsLoader 不 setId → 供 PATCH /{id}/toggle 走通）")
            .isEqualTo("cmd-db-id-integration");
    }
}
