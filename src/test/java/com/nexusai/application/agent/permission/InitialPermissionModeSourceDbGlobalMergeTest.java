package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.source.InitialPermissionModeSource;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [V44] InitialPermissionModeSource 的 DB 全局默认（settings.permission_mode）合并测试。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：V44 三态链 settings 槽内部 = {@code DB 全局 ??
 * 磁盘 settings.json defaultMode}——前端经 PUT /api/v1/settings 写的全局默认落
 * {@code settings.permission_mode} 列，须优先于磁盘 settings.json；DB 未配置 / mapper 未注入 /
 * 读异常 → fail-soft 回落磁盘（对齐 SettingsService.readDbAgentSwarmsEnabled :198-208 容错先例），
 * 绝不阻断 loop 启动。本测试钉死三种形态：
 * <ol>
 *   <li><b>DB 优先</b>——DB 列非 null 恒胜磁盘 defaultMode（resolveInput settingsDefaultMode 取 DB）；</li>
 *   <li><b>DB null 回落磁盘</b>——DB 行存在但 permission_mode 为 null → 用磁盘 defaultMode（零行为变化）；</li>
 *   <li><b>mapper 未注入回落磁盘</b>——POJO 单测 / 无 Spring → null → 磁盘（向后兼容，RevFix2 场景）。</li>
 * </ol>
 */
@DisplayName("[V44] InitialPermissionModeSource DB 全局 ?? 磁盘 settings.json 合并")
class InitialPermissionModeSourceDbGlobalMergeTest {

    /** 3 参构造器（直接注入 mock SettingsMapper）。 */
    private static InitialPermissionModeSource newSource(Path nexusaiHome, SettingsMapper mapper) {
        return new InitialPermissionModeSource(
            new SettingsJsonParser(new ObjectMapper(), new PermissionRuleValueParser()),
            () -> nexusaiHome.toString(),
            mapper);
    }

    /** 2 参构造器（mapper 未注入，模拟 POJO 单测 / 无 Spring）。 */
    private static InitialPermissionModeSource newSourceWithoutMapper(Path nexusaiHome) {
        return new InitialPermissionModeSource(
            new SettingsJsonParser(new ObjectMapper(), new PermissionRuleValueParser()),
            () -> nexusaiHome.toString());
    }

    /** 项目层磁盘 settings.json：defaultMode=acceptEdits（模拟磁盘三源之一）。 */
    private static void writeDiskDefaultMode(Path nexusaiHome) throws IOException {
        Files.createDirectories(nexusaiHome.resolve(NexusaiPaths.getProjectDirName()));
        Files.writeString(nexusaiHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json"),
            "{\"permissions\":{\"defaultMode\":\"acceptEdits\"}}");
    }

    @Test
    @DisplayName("① DB 列 permission_mode='plan' 恒胜磁盘 defaultMode='acceptEdits'（settingsDefaultMode='plan' → PLAN）")
    void dbGlobalWinsOverDisk(@TempDir Path nexusaiHome) throws IOException {
        // WHY: 前端 PUT 全局默认 → DB settings.permission_mode（V44 列）——DB 是权威（前端配置优先），
        //   磁盘 settings.json 只是兜底。变异点：resolveInput 未合并 DB → settingsDefaultMode 落磁盘
        //   acceptEdits → DB 配置"不生效"。
        writeDiskDefaultMode(nexusaiHome);

        SettingsRecord dbRow = new SettingsRecord();
        dbRow.setPermissionMode("plan");
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(dbRow);

        InitialPermissionModeResolver.Input input = newSource(nexusaiHome, mapper).resolveInput(null, false);

        assertThat(input.settingsDefaultMode())
            .as("DB 列 permission_mode=plan 必须优先于磁盘 defaultMode=acceptEdits（DB ?? 磁盘）")
            .isEqualTo("plan");
        assertThat(InitialPermissionModeResolver.resolve(input,
            InitialPermissionModeResolver.Config.defaults()).mode())
            .as("settings 槽 plan → 初始 mode PLAN")
            .isEqualTo(PermissionMode.PLAN);
    }

    @Test
    @DisplayName("② DB 行存在但 permission_mode=null → 回落磁盘 defaultMode='acceptEdits'")
    void dbNullFallsBackToDisk(@TempDir Path nexusaiHome) throws IOException {
        // WHY: DB 未配置（null）必须回落磁盘——向后兼容：老库 / 未设全局默认时行为与 V44 前完全一致。
        //   变异点：DB null 被强塞默认 / 或吞掉磁盘值 → 行为漂移。
        writeDiskDefaultMode(nexusaiHome);

        SettingsRecord dbRow = new SettingsRecord();   // permission_mode=null（未配置）
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(dbRow);

        InitialPermissionModeResolver.Input input = newSource(nexusaiHome, mapper).resolveInput(null, false);

        assertThat(input.settingsDefaultMode())
            .as("DB permission_mode=null → 回落磁盘 defaultMode=acceptEdits（零行为变化）")
            .isEqualTo("acceptEdits");
    }

    @Test
    @DisplayName("③ mapper 未注入（POJO / 无 Spring）→ 磁盘 defaultMode 直读（零行为变化，RevFix2 场景）")
    void mapperNotInjectedUsesDisk(@TempDir Path nexusaiHome) throws IOException {
        // WHY: POJO 单测 / 无 Spring 装配时 settingsMapper=null → readDbGlobalPermissionMode 直接
        //   return null（fail-soft）→ 磁盘 defaultMode 兜底。对齐 RevFix2ProductionInputWiringTest
        //   2 参构造器场景（newSource(nexusaiHome) 无 mapper）。
        writeDiskDefaultMode(nexusaiHome);

        InitialPermissionModeResolver.Input input = newSourceWithoutMapper(nexusaiHome).resolveInput(null, false);

        assertThat(input.settingsDefaultMode())
            .as("mapper 未注入 → 磁盘 defaultMode=acceptEdits（与 V44 前行为一致）")
            .isEqualTo("acceptEdits");
        assertThat(InitialPermissionModeResolver.resolve(input,
            InitialPermissionModeResolver.Config.defaults()).mode())
            .as("磁盘 acceptEdits → 初始 mode ACCEPT_EDITS")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
    }
}
