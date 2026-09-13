package com.nexusai.domain.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code SettingsService.update} 对 {@code snipNudgeThreshold} 的写入侧值域校验
 * （snip-nudge-percent 2026-09-13 · 配置面单元）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 / 规则十二 · 测试验证意图而非行为）</b>：
 * DB 列 {@code settings.snip_nudge_threshold} 的语义已由「消息条数」改为「上下文剩余百分比」，
 * 读侧 {@link com.nexusai.application.agent.compact.SnipCompactor#resolveSnipNudgeRemainingPercent}
 * 只认 <b>1..100</b>，越界一律回落默认 30 并打 WARN。若写侧不设防，症状是<b>彻底的静默失败</b>：
 * 用户在设置页填 900（旧「消息数」语义的存量值）能保存成功、GET 也读回 900，但系统<b>实际用的是
 * 默认 30</b>——用户以为配置生效了，界面上没有任何线索指出它被丢弃。故本类把写侧钉成 fail-loud（400）。
 *
 * <p>四个越界用例各自代表一种真实来路（不是同一件事的四种写法）：
 * <ol>
 *   <li><b>0</b> —— 旧语义里 {@code ≤0} 就是「未配置 → 回落窗口档位」。若让 0 合法（= 只在剩余 0%
 *       时提示，事实上等于关闭），存量库里的 0 会从「回落档位」<b>静默翻转为「关闭提示」</b>。
 *       关闭 nudge 的正确开关是 {@code settings.history_snip_enabled}。</li>
 *   <li><b>-1</b> —— 负数在任何口径下都无意义（读侧同样按未配置处理）。</li>
 *   <li><b>101</b> —— 刚过上限的边界，钉死上界是含 100 的闭区间（与 {@code 100} 那条配对看）。</li>
 *   <li><b>900</b> —— 旧「消息数」语义的存量值（历史档位 900/600/360/180 的上界），
 *       是最可能在真实库里出现、也最容易被静默吞掉的那一类。</li>
 * </ol>
 *
 * <p><b>造请求体的方式照抄既有先例</b> {@code SettingsAllowDynamicHeaderValuesTest#reqFromJson}：
 * {@link SettingsDto} 是 65+ 组件的位置 record，手写位置参数极易错位（{@code SettingsServiceTest}
 * 里已有「避免手写 66 参」的注释），故走 Jackson 反序列化——这也正是线上真实链路
 * （{@code SettingsController.update} 收 {@code @RequestBody SettingsDto}）。裸 {@code ObjectMapper}
 * 的 {@code FAIL_ON_UNKNOWN_PROPERTIES} 默认 true，字段一旦被删就抛，比 Spring Boot 的静默丢弃响亮。
 */
@DisplayName("[snip-nudge-percent] settings.snip_nudge_threshold 写入侧值域校验（1..100）")
class SettingsSnipNudgeThresholdRangeTest {

    private static final int SINGLETON_ID = 1;

    private SettingsService newService(SettingsMapper mapper) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        return service;
    }

    /** 造 DB 行。autoMemoryEnabled 设值：避免 {@code toDto} 回落 settings.json 文件读
     *  （沿用 {@code SettingsServiceTest.webSearchRow()} / {@code SettingsAllowDynamicHeaderValuesTest}
     *  的既有做法，保持 POJO 单测不碰文件系统）。 */
    private static SettingsRecord rowWith(Integer snipNudgeThreshold) {
        SettingsRecord row = new SettingsRecord();
        row.setAutoMemoryEnabled(true);
        row.setSnipNudgeThreshold(snipNudgeThreshold);
        return row;
    }

    /** 只带 snipNudgeThreshold 的 PUT 请求体（走 JSON，避免手写 65 个位置参数）。 */
    private static SettingsDto reqWith(int snipNudgeThreshold) throws Exception {
        return new ObjectMapper()
            .readValue("{\"snipNudgeThreshold\":" + snipNudgeThreshold + "}", SettingsDto.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 合法域：1..100（闭区间两端都要钉）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("下界 1 被接受 → 落库 + update 返回透出")
    void lowerBoundAccepted() throws Exception {
        SettingsRecord row = rowWith(null);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);

        SettingsDto updated = newService(mapper).update(reqWith(1));

        assertThat(row.getSnipNudgeThreshold()).as("1 是合法下界，必须真的写进 DB 列").isEqualTo(1);
        assertThat(updated.snipNudgeThreshold()).as("update 返回值必须透出新值（前端 PUT 响应体取的就是它）").isEqualTo(1);
        verify(mapper).update(row);
    }

    @Test
    @DisplayName("上界 100 被接受 → 落库 + update 返回透出")
    void upperBoundAccepted() throws Exception {
        SettingsRecord row = rowWith(null);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);

        SettingsDto updated = newService(mapper).update(reqWith(100));

        assertThat(row.getSnipNudgeThreshold()).as("100 是合法上界（含），必须接受").isEqualTo(100);
        assertThat(updated.snipNudgeThreshold()).isEqualTo(100);
        verify(mapper).update(row);
    }

    // ════════════════════════════════════════════════════════════════════
    // 非法域：fail-loud（400），且不得落库 / 不得改动既有值
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("0 被拒（ValidationException）—— 旧语义的「未配置」不得静默翻转为「关闭提示」")
    void zeroRejected() throws Exception {
        SettingsRecord row = rowWith(null);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);

        SettingsDto req = reqWith(0);
        assertThat(req.snipNudgeThreshold()).as("前置条件：JSON 的 0 必须解析成 Integer 0").isZero();

        assertThatThrownBy(() -> newService(mapper).update(req))
            .as("0 按非法处理：让 0 合法会让存量库里的 0 从「回落档位」静默变成「事实关闭 nudge」")
            .isInstanceOf(ValidationException.class);
        assertThat(row.getSnipNudgeThreshold()).as("被拒时不得写库").isNull();
        verify(mapper, never()).update(any(SettingsRecord.class));
    }

    @Test
    @DisplayName("负数 -1 被拒（ValidationException）")
    void negativeRejected() throws Exception {
        SettingsRecord row = rowWith(null);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);

        SettingsDto req = reqWith(-1);

        assertThatThrownBy(() -> newService(mapper).update(req))
            .as("负数在「剩余百分比」口径下无意义，必须 fail-loud")
            .isInstanceOf(ValidationException.class);
        assertThat(row.getSnipNudgeThreshold()).as("被拒时不得写库").isNull();
        verify(mapper, never()).update(any(SettingsRecord.class));
    }

    @Test
    @DisplayName("超出上界 101 被拒（ValidationException）—— 与「100 被接受」配对钉死闭区间")
    void aboveUpperBoundRejected() throws Exception {
        SettingsRecord row = rowWith(null);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);

        SettingsDto req = reqWith(101);

        assertThatThrownBy(() -> newService(mapper).update(req))
            .as("101 = 101% 剩余，永远不可能触发 nudge；静默存下则用户以为配好了")
            .isInstanceOf(ValidationException.class);
        assertThat(row.getSnipNudgeThreshold()).as("被拒时不得写库").isNull();
        verify(mapper, never()).update(any(SettingsRecord.class));
    }

    @Test
    @DisplayName("旧「消息数」语义存量值 900 被拒（ValidationException）—— 最容易被静默吞掉的一类")
    void legacyMessageCountValueRejected() throws Exception {
        SettingsRecord row = rowWith(null);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);

        SettingsDto req = reqWith(900);

        assertThatThrownBy(() -> newService(mapper).update(req))
            .as("旧档位 900 在新口径下越界；若静默存下，读侧回落到 30 而用户看到的是 900（静默失败）")
            .isInstanceOf(ValidationException.class);
        assertThat(row.getSnipNudgeThreshold()).as("被拒时不得写库").isNull();
        verify(mapper, never()).update(any(SettingsRecord.class));
    }

    // ════════════════════════════════════════════════════════════════════
    // null = 不覆盖（既有 PATCH 语义，不得因新校验而误伤）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("null（PUT 未带该字段）→ 不覆盖既有值，且不抛（PATCH 语义）")
    void nullDoesNotOverwriteExistingValue() throws Exception {
        // WHY：settings 的 PUT 是「仅覆盖非空字段」。加了值域校验后若把 null 也当成非法，
        //   「只改别的设置」的任何一次 PUT 都会 400 —— 把新校验变成全局拦路虎。
        SettingsRecord row = rowWith(60);   // 既有 DB 值 = 60（合法域内）
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);

        SettingsDto req = new ObjectMapper().readValue("{\"language\":\"zh-CN\"}", SettingsDto.class);
        assertThat(req.snipNudgeThreshold()).as("前置条件：字段缺失必须解析成 null").isNull();

        SettingsDto updated = newService(mapper).update(req);

        assertThat(row.getSnipNudgeThreshold()).as("null = 不覆盖：既有 60 必须原样保留").isEqualTo(60);
        assertThat(updated.snipNudgeThreshold()).as("读回同样必须是 60").isEqualTo(60);
        verify(mapper).update(row);
    }
}
