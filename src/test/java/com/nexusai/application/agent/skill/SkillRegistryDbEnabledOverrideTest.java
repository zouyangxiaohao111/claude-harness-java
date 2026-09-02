package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import com.nexusai.repository.command.entity.CommandRecord;
import com.nexusai.repository.command.mapper.CommandMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方案1（用户拍板）· DB enabled 主控覆盖测试：前端禁用/启用 skill 需后端真实生效。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：前端 PATCH toggle / update 只写 DB enabled
 * （{@code CommandService.toggleEnabled/update} → {@code commandMapper.update}），磁盘 SKILL.md 的
 * frontmatter 不含 enabled 字段（默认 enabled=true）。若 {@link SkillRegistry#loadAllCommands()} 合并
 * 五源后不读 DB 覆盖 enabled，则前端禁用/启用永不生效——列表合并以本 registry 为权威
 * （{@link #getAllCommands()} 全消费面唯一入口），DB 覆盖后前端 toggle 真实生效。本测试验证：
 * <ol>
 *   <li>DB 行 enabled=0（禁用）→ getAllCommands 排除该 skill（{@code isCommandEnabled()=false} 过滤）</li>
 *   <li>DB 行 enabled=1（启用）→ 保留</li>
 *   <li>DB 无该 skill 名（selectAll 空 / 仅含他名行）→ 不覆盖，文件默认 enabled=true 保留</li>
 *   <li>DB 行 enabled=null → 跳过该名（文件默认 enabled 保留，CC enabled 可空语义）</li>
 *   <li>bundled skill 的 isEnabled supplier（运行时 gate，如 GB/autoMemory）优先 —— 覆盖 enabled
 *       字段对 supplier skill 无效（符合 CC isEnabled 优先语义，types/command.ts:214-215
 *       {@code isEnabled?.() ?? true}）</li>
 * </ol>
 *
 * <p>注入方式：{@link SkillRegistry#setCommandMapper(CommandMapper)}（POJO setter · 生产装配点
 * ToolRegistrationConfig.skillRegistry()），null 安全——未注入时 DB 覆盖跳过，现有 POJO 直构测试不破。
 */
@DisplayName("[方案1] SkillRegistry DB enabled 主控覆盖 —— 前端 toggle 写 DB → loadAllCommands 覆盖文件默认 enabled")
class SkillRegistryDbEnabledOverrideTest {

    /** 写一个最小 SKILL.md（无 enabled frontmatter → 文件默认 enabled=true）· 对齐 SkillRegistryMemoizeTest.writeSkill */
    private static void writeSkill(Path root, String dir, String name) throws Exception {
        Path skillDir = root.resolve(dir);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: " + name + "\n---\n# " + name + "\n");
    }

    /** DB command 行（name→enabled 覆盖用 · CommandRecord.getEnabled() 为 Integer，0=false） */
    private static CommandRecord dbRow(String name, Integer enabled) {
        CommandRecord r = new CommandRecord();
        r.setId("cmd-" + name);
        r.setName(name);
        r.setSource("user");
        r.setEnabled(enabled);
        return r;
    }

    @Test
    @DisplayName("DB enabled=false → getAllCommands 排除该 skill（前端禁用真实生效）")
    void dbDisabled_excludesSkill(@TempDir Path tempDir) throws Exception {
        // 磁盘 SKILL.md 无 enabled frontmatter → 文件默认 enabled=true；DB 行 enabled=0 → 覆盖为 false。
        writeSkill(tempDir, "db-disabled-skill", "db-disabled-skill");
        BundledSkills.clear(); // 隔离跨测试泄漏的 bundled 注册集

        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        Mockito.when(mapper.selectAll()).thenReturn(List.of(dbRow("db-disabled-skill", 0)));

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setCommandMapper(mapper);

        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .as("DB enabled=0 覆盖文件默认 enabled=true → isCommandEnabled=false → getAllCommands 排除（前端禁用生效）")
            .doesNotContain("db-disabled-skill");
    }

    @Test
    @DisplayName("DB enabled=true → 保留（前端启用生效）")
    void dbEnabled_keepsSkill(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "db-enabled-skill", "db-enabled-skill");
        BundledSkills.clear();

        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        Mockito.when(mapper.selectAll()).thenReturn(List.of(dbRow("db-enabled-skill", 1)));

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setCommandMapper(mapper);

        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .as("DB enabled=1 覆盖为 true → isCommandEnabled=true → 保留（前端启用生效）")
            .contains("db-enabled-skill");
    }

    @Test
    @DisplayName("selectAll 返回空 → 无该 skill 名 → 不覆盖，文件默认 enabled=true 保留")
    void dbAbsent_selectAllEmpty_notOverridden(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "db-absent-skill", "db-absent-skill");
        BundledSkills.clear();

        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        Mockito.when(mapper.selectAll()).thenReturn(List.of()); // DB 无任何行

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setCommandMapper(mapper);

        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .as("DB 无该 skill 行 → 不覆盖（文件默认 enabled=true 保留）")
            .contains("db-absent-skill");
    }

    @Test
    @DisplayName("selectAll 含他名行 → 不覆盖本 skill（仅同名覆盖），文件默认 enabled=true 保留")
    void dbAbsent_noMatchingName_notOverridden(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "db-absent-skill", "db-absent-skill");
        BundledSkills.clear();

        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        // DB 只含另一个 skill 的行（本 skill 未落 DB）→ name→enabled 映射不含 db-absent-skill → 跳过
        Mockito.when(mapper.selectAll()).thenReturn(List.of(dbRow("db-unrelated", 0)));

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setCommandMapper(mapper);

        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .as("DB 仅含他名行（enabled=0 也不得覆盖本 skill）→ 文件默认 enabled=true 保留")
            .contains("db-absent-skill");
    }

    @Test
    @DisplayName("DB 行 enabled=null → 跳过该名（文件默认 enabled 保留 · CC enabled 可空语义）")
    void dbRowEnabledNull_notOverridden(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "db-null-skill", "db-null-skill");
        BundledSkills.clear();

        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        Mockito.when(mapper.selectAll()).thenReturn(List.of(dbRow("db-null-skill", null)));

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setCommandMapper(mapper);

        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .as("DB 行 enabled=null → rec.getEnabled()!=null 检查跳过 → 不覆盖，文件默认 enabled=true 保留")
            .contains("db-null-skill");
    }

    @Test
    @DisplayName("bundled isEnabled supplier 优先 → DB enabled=false 覆盖无效（CC isEnabled?.() ?? true）")
    void bundledSupplier_keepsPriority(@TempDir Path tempDir) throws Exception {
        // WHY: CC isCommandEnabled = cmd.isEnabled?.() ?? true（types/command.ts:214-215）——isEnabled
        //   supplier 非 null 时新鲜求值，覆盖 enabled 字段对 supplier skill 无效。bundled 运行时 gate
        //   命令（如 loop: isKairosCronEnabled / remember: isAutoMemoryEnabled）不受 DB toggle 影响
        //   （SkillRegistry.setCommandMapper Javadoc 明示该语义）。若 DB 覆盖把 supplier 优先级破坏，
        //   本测试 fail。
        BundledSkills.clear();
        Command bundled = new Command();
        bundled.setId("bundled-gate-1");
        bundled.setName("bundled-gate");
        bundled.setSource(CommandSource.BUNDLED);
        bundled.setIsEnabled(() -> true); // 运行时 gate 恒 true（模拟 GB/autoMemory 激活态）
        BundledSkills.register(bundled);

        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        Mockito.when(mapper.selectAll()).thenReturn(List.of(dbRow("bundled-gate", 0)));

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setCommandMapper(mapper);

        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .as("isEnabled supplier 非 null 优先于 enabled 字段 → DB enabled=0 覆盖无效 → supplier=true 保留")
            .contains("bundled-gate");
    }

    @Test
    @DisplayName("DB 行同名命中 → 同步 id（integration-gap：SkillsLoader 不 setId → 列表 DTO id 非 null 供前端 toggle）")
    void dbRow_syncsId(@TempDir Path tempDir) throws Exception {
        // WHY（integration-gap 修复）：SkillsLoader.loadFromSkillMd 从不 setId → SkillRegistry 文件 skill
        //   Command.id=null → 列表合并 SkillRegistry 权威 → 前端拿到的 DTO id=null → PATCH /{id}/toggle
        //   传 null → selectOneById(null) NotFound → DB 写不进、DB enabled 主控覆盖永不参与（方案1+2 空转）。
        //   本修复在 DB 覆盖块同名命中时补 id（DB 行 id），前端 toggle 才有真实 id 走通 DB 写。
        writeSkill(tempDir, "db-id-skill", "db-id-skill");
        BundledSkills.clear();

        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        Mockito.when(mapper.selectAll()).thenReturn(List.of(dbRow("db-id-skill", 1)));

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setCommandMapper(mapper);

        Command hit = registry.getAllCommands().stream()
            .filter(c -> "db-id-skill".equals(c.getName())).findFirst().orElseThrow();
        assertThat(hit.getId())
            .as("DB 行同名命中 → SkillRegistry 从 DB 拷贝 id（补 id 供前端 PATCH /{id}/toggle 走通 DB 写）")
            .isEqualTo("cmd-db-id-skill");
    }
}
