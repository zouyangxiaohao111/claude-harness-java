package com.nexusai.domain.settings;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [external-agent 2026-11-09] Settings 层「子代理默认外部运行时」（V259 列 subagent_default_runtime）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：这个字段上串着<b>两个独立缺陷</b>，
 * 两者叠加的表现都是同一个 ——「界面显示改好了，实际派发还是回落到 claude」：
 *
 * <ol>
 *   <li><b>清空不落库</b>：{@code update} 的 merge 契约是「{@code null} = 不覆盖」，
 *       所以「改回内置」只能用<b>空串</b>表达。而清空分支只是把实体字段置 null，
 *       再走默认的 {@code update(entity)} —— mybatis-flex 默认忽略 null 字段（NOT_NULL 策略）
 *       ⇒ {@code subagent_default_runtime} 列被 SET 跳过 ⇒ <b>DB 永远留着旧值</b>。
 *       用户点「改回内置」没反应，下一轮照样回落到 claude。</li>
 *   <li><b>值域是 runtime id，不是 agentType</b>：派发侧（{@code SubagentTool.setSettingsService}）
 *       按 <b>runtime id</b> 去 registry 里反查 agentType；写侧也只认 runtime id。
 *       传 agentType（codex-external 那种）一律 400。</li>
 * </ol>
 *
 * <p>本用例锁死三件事：
 * <ul>
 *   <li><b>清空必须显式写 NULL</b>（{@code update(entity, false)}）—— 否则「改回内置」是空操作；</li>
 *   <li><b>非清空仍走默认 update</b>（{@code update(entity)}）—— 别为了清空把整表都写成 NULL，
 *       那样 NOT NULL 列（theme/font_size/…）会炸；</li>
 *   <li><b>值域只认 runtime id</b> —— 传 agentType 必须 fail loud（400），
 *       不能静默存进去让派发侧永远查不到。</li>
 * </ul>
 *
 * <p>变异自证：把 {@code update(s, false)} 改回 {@code update(s)} → 第 1 条红；
 * 把清空分支的 {@code update(s, false)} 也换成 {@code update(s)} → 第 1 条红；
 * 把校验分支去掉 → 第 3 条红。
 */
class SettingsServiceSubagentDefaultRuntimeTest {

    private static final int SINGLETON_ID = 1;

    private SettingsService newService(SettingsMapper mapper, SettingsRecord dbRow) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(dbRow);
        return service;
    }

    /** DB 行：默认运行时已配成 claude，其余字段留空。 */
    private static SettingsRecord rowWithDefault(String runtime) {
        SettingsRecord s = new SettingsRecord();
        s.setAutoMemoryEnabled(true);   // 避免 toDto 回落 settings.json 文件读（与既有 settings 测试同款）
        s.setSubagentDefaultRuntime(runtime);
        return s;
    }

    /**
     * 只带 subagentDefaultRuntime 的请求体（其余 72 个组件全 null）。
     *
     * <p>⚠️ 用 canonical 73 参构造器（不是 70 参兼容构造器）—— 兼容构造器把末尾 3 个
     * （goalCheckinMinutes / subagentDefaultRuntime / subagentExternalEnabled）写死成 null，
     * 用它根本表达不了「设置默认运行时」这个被测行为（会假绿）。
     */
    private static SettingsDto req(String defaultRuntime) {
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
            null);
    }

    @Test
    @DisplayName("update(空串)：清空默认运行时 ⇒ 必须 update(entity,false) 显式写 NULL（否则「改回内置」是空操作）")
    void update_emptyString_writesExplicitNull() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = rowWithDefault("claude");
        SettingsService service = newService(mapper, dbRow);

        service.update(req(""));

        assertThat(dbRow.getSubagentDefaultRuntime()).as("实体字段必须被置 NULL").isNull();
        // ⚠️ 必须是 update(entity, **false**) —— ignoreNulls=false 才显式写 NULL。
        //    默认 update(entity) 忽略 null 字段（NOT_NULL 策略）⇒ 清空列被 SET 跳过
        //    ⇒ DB 残留 claude，「改回内置」点了没反应（2026-11-09 真机实测：DB 一直是 claude）。
        verify(mapper).update(dbRow, false);
    }

    @Test
    @DisplayName("update(非空 runtime)：走默认 update(entity)（别为清空把整表写成 NULL）")
    void update_nonEmptyRuntime_usesDefaultUpdate() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = rowWithDefault("claude");
        SettingsService service = newService(mapper, dbRow);

        service.update(req("codex"));

        assertThat(dbRow.getSubagentDefaultRuntime()).isEqualTo("codex");
        // ⛔ 不能对非清空分支用 update(entity,false)：那会把所有 null 列写成 NULL，
        //   settings 的 NOT NULL 列（theme / font_size / accent / …）会违反约束。
        verify(mapper).update(dbRow);
        verify(mapper, never()).update(dbRow, false);
    }

    @Test
    @DisplayName("update(agentType)：值域只认 runtime id ⇒ 传 codex-external 必须 fail loud（400），不能静默存进去")
    void update_agentTypeRejected_failsLoud() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = rowWithDefault("claude");
        SettingsService service = newService(mapper, dbRow);

        // agentType（md 的 name）与 runtime id（md 的 sub-runtime）是两个命名空间。
        // 派发侧按 runtime id 反查 agentType ⇒ 存 agentType 会永远查不到 ⇒ 回落内置。
        // 写侧必须当场拒（400），不能「先存着等以后再说」。
        assertThatThrownBy(() -> service.update(req("codex-external")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("codex-external");
        // 拒绝时不得写库
        verify(mapper, never()).update(org.mockito.ArgumentMatchers.any(SettingsRecord.class));
        // DB 旧值保持不变
        assertThat(dbRow.getSubagentDefaultRuntime()).isEqualTo("claude");
    }

    @Test
    @DisplayName("update(null)：merge 语义 = 不改这一项（保留 DB 原值）")
    void update_nullKeepsOldValue() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = rowWithDefault("claude");
        SettingsService service = newService(mapper, dbRow);

        service.update(req(null));

        assertThat(dbRow.getSubagentDefaultRuntime()).as("null = 不覆盖").isEqualTo("claude");
        verify(mapper).update(dbRow);
    }

    @Test
    @DisplayName("get()：默认运行时原样透出到 DTO（前端据此显示「当前默认」）")
    void get_transmitsDefaultRuntime() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsService service = newService(mapper, rowWithDefault("codex"));

        SettingsDto dto = service.get();

        assertThat(dto.subagentDefaultRuntime()).isEqualTo("codex");
    }
}
