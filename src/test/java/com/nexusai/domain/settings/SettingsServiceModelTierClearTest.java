package com.nexusai.domain.settings;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.settings.dto.ModelTierRole;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [model-settings-clear 2026-10-31] Settings 层「模型档位清空」（DELETE 语义）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：
 * {@code SettingsService.update} 的 merge 契约是「{@code null} = 不覆盖」——
 * 前端传 {@code null} 的意思是「这次别动这个字段」。⇒ <b>用 PUT 根本无法表达「把档位清空」</b>
 * （清空必须传 {@code null}，而 {@code null} 恰恰被当成「别动它」）。
 * 而前端 UI 上「解绑某个档位」和「一键清除全部」是<b>必须能用</b>的动作 ⇒ 必须有独立通道把列置 NULL。
 *
 * <p>本用例锁死三件事：
 * <ol>
 *   <li><b>清空单档位</b>：只置空目标列，<b>其余列分毫不动</b>（清一个不能误伤别的档位）；</li>
 *   <li><b>清空全部</b>：11 个档位列全为 NULL；</li>
 *   <li><b>幂等</b>：本就未配置的档位再清一次 → 不写库、不报错（前端可以放心重复调用）。</li>
 * </ol>
 *
 * <p>变异自证：把 {@code writeTierValue} 里某个 case 的 setter 去掉 → 第 1 条红；
 * 把 {@code clearAllModelTiers} 的循环去掉 → 第 2 条红；
 * 把「old 为空就不写库」改成「总是写库」→ 第 3 条红。
 */
class SettingsServiceModelTierClearTest {

    private static final int SINGLETON_ID = 1;

    private SettingsService newService(SettingsMapper mapper, SettingsRecord dbRow) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(dbRow);
        return service;
    }

    /** 全档位都配满的 DB 行（11 列全非 null）。 */
    private static SettingsRecord allTiersRow() {
        SettingsRecord s = new SettingsRecord();
        s.setAutoMemoryEnabled(true);   // 避免 toDto 回落 settings.json 文件读（与 SettingsServiceTest 同款）
        s.setMainModelName("ds/ds-flash");
        s.setFastModelName("ds/ds-flash");
        s.setStrongModelName("ds/ds-r1");
        s.setMediumModelName("ds/ds-flash");
        s.setWeakModelName("ds/ds-lite");
        s.setSubagentModelName("ds/ds-lite");
        s.setFallbackModelName("ds/ds-lite");
        s.setMultimodalModelName("ds/ds-flash");
        s.setTtsModelName("ds/ds-tts");
        s.setAsrModelName("ds/ds-asr");
        s.setClassifierModel("ds/ds-lite");
        return s;
    }

    @Test
    @DisplayName("clearModelTier()：只清目标档位，其余档位列分毫不动")
    void clearModelTier_onlyTouchesTargetColumn() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = allTiersRow();
        SettingsService service = newService(mapper, dbRow);

        SettingsDto dto = service.clearModelTier(ModelTierRole.MAIN);

        assertThat(dbRow.getMainModelName()).as("主模型列必须被置 NULL").isNull();
        // 其余 10 列必须原样保留 —— 清一个档位绝不能误伤其它档位
        assertThat(dbRow.getFastModelName()).isEqualTo("ds/ds-flash");
        assertThat(dbRow.getStrongModelName()).isEqualTo("ds/ds-r1");
        assertThat(dbRow.getMediumModelName()).isEqualTo("ds/ds-flash");
        assertThat(dbRow.getWeakModelName()).isEqualTo("ds/ds-lite");
        assertThat(dbRow.getSubagentModelName()).isEqualTo("ds/ds-lite");
        assertThat(dbRow.getFallbackModelName()).isEqualTo("ds/ds-lite");
        assertThat(dbRow.getMultimodalModelName()).isEqualTo("ds/ds-flash");
        assertThat(dbRow.getTtsModelName()).isEqualTo("ds/ds-tts");
        assertThat(dbRow.getAsrModelName()).isEqualTo("ds/ds-asr");
        assertThat(dbRow.getClassifierModel()).isEqualTo("ds/ds-lite");
        // 返回 DTO 也要反映清空结果（前端据此刷新界面）
        assertThat(dto.mainModelName()).isNull();
        assertThat(dto.strongModelName()).isEqualTo("ds/ds-r1");
        // ⚠️ 必须是 update(entity, **false**) —— ignoreNulls=false 才显式写 NULL。
        //    默认 update(entity) 忽略 null 字段（NOT_NULL 策略）⇒ 清空列被 SET 跳过
        //    ⇒ DB 残留旧值，「清除」根本没生效（2026-10-31 实测踩过）。
        verify(mapper).update(dbRow, false);
    }

    @Test
    @DisplayName("clearAllModelTiers()：11 个档位列全部置 NULL")
    void clearAllModelTiers_nullsEveryTierColumn() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = allTiersRow();
        SettingsService service = newService(mapper, dbRow);

        SettingsDto dto = service.clearAllModelTiers();

        assertThat(dbRow.getMainModelName()).isNull();
        assertThat(dbRow.getFastModelName()).isNull();
        assertThat(dbRow.getStrongModelName()).isNull();
        assertThat(dbRow.getMediumModelName()).isNull();
        assertThat(dbRow.getWeakModelName()).isNull();
        assertThat(dbRow.getSubagentModelName()).isNull();
        assertThat(dbRow.getFallbackModelName()).isNull();
        assertThat(dbRow.getMultimodalModelName()).isNull();
        assertThat(dbRow.getTtsModelName()).isNull();
        assertThat(dbRow.getAsrModelName()).isNull();
        assertThat(dbRow.getClassifierModel()).isNull();
        assertThat(dto.mainModelName()).isNull();
        assertThat(dto.classifierModel()).isNull();
        // 同上：必须 update(entity, false) 才显式写 NULL
        verify(mapper).update(dbRow, false);
    }

    @Test
    @DisplayName("clearModelTier()：档位本就未配置 → 幂等，不写库不报错")
    void clearModelTier_isIdempotentWhenAlreadyUnset() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = allTiersRow();
        dbRow.setMainModelName(null);
        SettingsService service = newService(mapper, dbRow);

        SettingsDto dto = service.clearModelTier(ModelTierRole.MAIN);

        assertThat(dto.mainModelName()).isNull();
        // 幂等的关键：无谓写库会造成 updated_at 抖动，也让「已清除」的语义变得可疑
        verify(mapper, never()).update(any(SettingsRecord.class), anyBoolean());
    }

    @Test
    @DisplayName("clearAllModelTiers()：全部本就未配置 → 幂等，不写库")
    void clearAllModelTiers_isIdempotentWhenAllUnset() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = new SettingsRecord();
        dbRow.setAutoMemoryEnabled(true);
        SettingsService service = newService(mapper, dbRow);

        service.clearAllModelTiers();

        verify(mapper, never()).update(any(SettingsRecord.class), anyBoolean());
    }

    @Test
    @DisplayName("clearModelTier(null)：非法档位 → 抛 ValidationException（fail loud，不静默忽略）")
    void clearModelTier_nullRoleFailsLoud() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsService service = newService(mapper, allTiersRow());

        assertThatThrownBy(() -> service.clearModelTier(null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("ModelTierRole.fromWire()：未知档位名 → null（由 Controller 决定 400）")
    void fromWire_unknownRoleReturnsNull() {
        assertThat(ModelTierRole.fromWire("main")).isEqualTo(ModelTierRole.MAIN);
        assertThat(ModelTierRole.fromWire("MAIN")).isEqualTo(ModelTierRole.MAIN);
        assertThat(ModelTierRole.fromWire(" classifier ")).isEqualTo(ModelTierRole.CLASSIFIER);
        assertThat(ModelTierRole.fromWire("no-such-tier")).isNull();
        assertThat(ModelTierRole.fromWire(null)).isNull();
        assertThat(ModelTierRole.fromWire("")).isNull();
    }
}
