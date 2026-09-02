package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * [ALIGN-CATALOG-1] 截断遥测测试 · 对齐 CC tools/SkillTool/prompt.ts:126/:150
 * {@code logEvent('tengu_skill_descriptions_truncated', {...})}
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>两处截断发射点必须与 CC 语义一致</b>——names_only（prompt.ts:125-139，maxDescLen &lt; 20 极端
 *       情况）与 description_trimmed（prompt.ts:149-171，描述宽度截断）各发一条，载荷字段逐项对齐
 *       （skill_count/budget/full_total/truncation_mode/max_desc_length/bundled_count/bundled_chars，
 *       truncated_count 仅 description_trimmed 携带）。</li>
 *   <li><b>USER_TYPE==='ant' 门控必须真实生效</b>——CC prompt.ts:125/:149 {@code process.env.USER_TYPE === 'ant'}
 *       限定发射；非 ant 恒不发射（Java 默认读 {@code System.getenv("USER_TYPE")}，测试经
 *       {@link SkillCatalog#setUserTypeIsAnt} 注入覆盖，System.getenv 测试环境不可设值）。</li>
 *   <li><b>发射不改变清单文本</b>——遥测是旁路副作用，格式输出（全量/截断/names-only）与
 *       telemetry 未接线时完全一致。</li>
 * </ol>
 */
class SkillCatalogTelemetryTest {

    /** 空目录 SkillRegistry + SkillCatalog（formatListing 不依赖 registry 内容） */
    private static SkillCatalog catalog(Path tempDir) {
        return new SkillCatalog(new SkillRegistry(tempDir.toString()));
    }

    /** 构造一条命令（USER 源，非 bundled） */
    private static Command userCmd(String name, String desc) {
        Command c = new Command();
        c.setName(name);
        c.setDescription(desc);
        c.setSource(CommandSource.USER);
        return c;
    }

    /** 构造一条 bundled 源命令（分区特权 · CC prompt.ts:97 source==='bundled'） */
    private static Command bundledCmd(String name, String desc) {
        Command c = new Command();
        c.setName(name);
        c.setDescription(desc);
        c.setSource(CommandSource.BUNDLED);
        return c;
    }

    /**
     * 单条 user 命令 "alpha"（宽 5）+ 描述 80 字符（ASCII 宽 80）：
     * full = "- alpha: "（宽 9）+ 80 = 89，fullTotal = 89（N=1 无换行）。
     * <ul>
     *   <li>budget=60 → 89 &gt; 60 走截断；restNameOverhead = 5+4 = 9；availableForDescs = 51；
     *       maxDescLen = 51 ≥ 20 → description_trimmed；getDescription 宽 80 &gt; 51 → truncated_count=1</li>
     *   <li>budget=10 → availableForDescs = 1；maxDescLen = 1 &lt; 20 → names_only</li>
     *   <li>budget=60 + desc 3 字符 → fullTotal = 12 ≤ 60 → 全量，无截断无遥测</li>
     * </ul>
     */
    private static Command longUserCmd() {
        return userCmd("alpha", "d".repeat(80));
    }

    @Test
    @DisplayName("description_trimmed 模式发射遥测：字段与 CC prompt.ts:150-161 载荷逐项对齐")
    void descriptionTrimmed_emitsEvent_withAlignedFields(@TempDir Path tempDir) {
        Telemetry telemetry = spy(new Telemetry());
        SkillCatalog catalog = catalog(tempDir);
        catalog.setTelemetry(telemetry);
        catalog.setUserTypeIsAnt(() -> true);

        catalog.formatListing(List.of(longUserCmd()), 60);

        verify(telemetry).recordEvent(eq(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED),
            org.mockito.ArgumentMatchers.any());
        assertThat(telemetry.getCounter(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED)).isEqualTo(1);
    }

    @Test
    @DisplayName("description_trimmed 载荷值正确：maxDescLen=51、truncated_count=1、bundled 归零")
    void descriptionTrimmed_attrValues(@TempDir Path tempDir) {
        Telemetry telemetry = spy(new Telemetry());
        SkillCatalog catalog = catalog(tempDir);
        catalog.setTelemetry(telemetry);
        catalog.setUserTypeIsAnt(() -> true);

        catalog.formatListing(List.of(longUserCmd()), 60);

        Map<String, Object> attrs = captureAttrs(telemetry);
        assertThat(attrs).containsEntry("skill_count", 1);
        assertThat(attrs).containsEntry("budget", 60);
        assertThat(attrs).containsEntry("full_total", 89);
        assertThat(attrs).containsEntry("truncation_mode", "description_trimmed");
        assertThat(attrs).containsEntry("max_desc_length", 51);
        assertThat(attrs).containsEntry("truncated_count", 1);
        assertThat(attrs).containsEntry("bundled_count", 0);
        assertThat(attrs).containsEntry("bundled_chars", 0);
        assertThat(attrs).hasSize(8);  // truncated_count 仅在 description_trimmed 携带
    }

    @Test
    @DisplayName("names_only 模式发射遥测：载荷不含 truncated_count（CC prompt.ts:126-138）")
    void namesOnly_emitsEvent_withoutTruncatedCount(@TempDir Path tempDir) {
        Telemetry telemetry = spy(new Telemetry());
        SkillCatalog catalog = catalog(tempDir);
        catalog.setTelemetry(telemetry);
        catalog.setUserTypeIsAnt(() -> true);

        catalog.formatListing(List.of(longUserCmd()), 10);

        Map<String, Object> attrs = captureAttrs(telemetry);
        assertThat(attrs).containsEntry("truncation_mode", "names_only");
        assertThat(attrs).containsEntry("max_desc_length", 1);
        assertThat(attrs).containsEntry("full_total", 89);
        assertThat(attrs).hasSize(7);  // names_only 无 truncated_count（CC :126-138 载荷）
        assertThat(telemetry.getCounter(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED)).isEqualTo(1);
    }

    @Test
    @DisplayName("bundled 技能参与 bundled_count/bundled_chars，且不进入 truncated_count（CC :145-147 仅 rest）")
    void bundledCountAndChars_mixedWithTruncatedCount(@TempDir Path tempDir) {
        Telemetry telemetry = spy(new Telemetry());
        SkillCatalog catalog = catalog(tempDir);
        catalog.setTelemetry(telemetry);
        catalog.setUserTypeIsAnt(() -> true);

        // cmd1 BUNDLED "bundle" full 宽 10+80=90；cmd2 USER "alpha" full 宽 89；
        // fullTotal = 90+89+1(换行) = 180 > 120 → 截断；bundledChars = 90+1 = 91；
        // remainingBudget = 29；restNameOverhead = 9；maxDescLen = 20 ≥ 20 → description_trimmed；
        // truncated_count 只统计 rest（cmd2，宽 80 > 20 → 1），bundled cmd1 不计
        catalog.formatListing(List.of(
            bundledCmd("bundle", "d".repeat(80)),
            userCmd("alpha", "d".repeat(80))), 120);

        Map<String, Object> attrs = captureAttrs(telemetry);
        assertThat(attrs).containsEntry("truncation_mode", "description_trimmed");
        assertThat(attrs).containsEntry("skill_count", 2);
        assertThat(attrs).containsEntry("full_total", 180);
        assertThat(attrs).containsEntry("max_desc_length", 20);
        assertThat(attrs).containsEntry("truncated_count", 1);
        assertThat(attrs).containsEntry("bundled_count", 1);
        assertThat(attrs).containsEntry("bundled_chars", 91);
    }

    @Test
    @DisplayName("USER_TYPE 非 ant → 恒不发射（CC prompt.ts:125/:149 门控）")
    void nonAntGate_suppressesEvent(@TempDir Path tempDir) {
        Telemetry telemetry = spy(new Telemetry());
        SkillCatalog catalog = catalog(tempDir);
        catalog.setTelemetry(telemetry);
        catalog.setUserTypeIsAnt(() -> false);

        catalog.formatListing(List.of(longUserCmd()), 10);   // 走 names_only 分支
        catalog.formatListing(List.of(longUserCmd()), 60);   // 走 description_trimmed 分支

        verify(telemetry, never()).recordEvent(eq(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED),
            org.mockito.ArgumentMatchers.any());
        assertThat(telemetry.getCounter(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED)).isZero();
    }

    @Test
    @DisplayName("全量未截断 → 不发射（CC prompt.ts:87 全量分支先 return）")
    void fullListing_noEvent(@TempDir Path tempDir) {
        Telemetry telemetry = spy(new Telemetry());
        SkillCatalog catalog = catalog(tempDir);
        catalog.setTelemetry(telemetry);
        catalog.setUserTypeIsAnt(() -> true);

        catalog.formatListing(List.of(userCmd("alpha", "d".repeat(3))), 60);

        verify(telemetry, never()).recordEvent(eq(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED),
            org.mockito.ArgumentMatchers.any());
        assertThat(telemetry.getCounter(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED)).isZero();
    }

    @Test
    @DisplayName("仅 bundled 技能（rest 为空）→ 不发射（CC prompt.ts:112-114 提前 return）")
    void bundledOnly_noEvent(@TempDir Path tempDir) {
        Telemetry telemetry = spy(new Telemetry());
        SkillCatalog catalog = catalog(tempDir);
        catalog.setTelemetry(telemetry);
        catalog.setUserTypeIsAnt(() -> true);

        // fullTotal = 90 > 10 进入截断路径，但 restIndices 空 → 全量返回（CC :112-114）
        catalog.formatListing(List.of(bundledCmd("bundle", "d".repeat(80))), 10);

        verify(telemetry, never()).recordEvent(eq(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED),
            org.mockito.ArgumentMatchers.any());
        assertThat(telemetry.getCounter(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED)).isZero();
    }

    @Test
    @DisplayName("telemetry 未接线（null）→ 静默跳过，输出文本不变（旁路副作用）")
    void nullTelemetry_outputUnchanged(@TempDir Path tempDir) {
        SkillCatalog catalog = catalog(tempDir);          // 不 setTelemetry
        catalog.setUserTypeIsAnt(() -> true);

        assertThat(catalog.formatListing(List.of(longUserCmd()), 10))
            .isEqualTo("- alpha");                        // names-only 文本不受遥测影响
        assertThat(catalog.formatListing(List.of(longUserCmd()), 60))
            .isEqualTo("- alpha: " + SkillCatalog.truncate("d".repeat(80), 51));
    }

    @Test
    @DisplayName("isAntUser 纯谓词：恰为 ant 才 true（CC === 严格相等）")
    void isAntUser_strictEquality() {
        assertThat(SkillCatalog.isAntUser("ant")).isTrue();
        assertThat(SkillCatalog.isAntUser("ANT")).isFalse();       // CC === 大小写敏感
        assertThat(SkillCatalog.isAntUser("external")).isFalse();
        assertThat(SkillCatalog.isAntUser(null)).isFalse();
        assertThat(SkillCatalog.isAntUser("")).isFalse();
        assertThat(SkillCatalog.isAntUser(" ant")).isFalse();
    }

    // ─── 辅助：捕获 recordEvent 属性 ───

    private static Map<String, Object> captureAttrs(Telemetry telemetry) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetry).recordEvent(eq(SkillCatalog.TENGU_SKILL_DESCRIPTIONS_TRUNCATED), captor.capture());
        return captor.getValue();
    }
}
