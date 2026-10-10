package com.nexusai.domain.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code settings.stream_idle_timeout_ms}（V78）· SettingsDto 往返契约 + null-skip 语义。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：该字段是流空闲看门狗阈值的「前端可配」入口
 * （用户 10-10 追加要求）。三处同步（{@code SettingsDto} 分量 / {@code SettingsService.toDto} 透出 /
 * {@code update} 的 null-skip merge）任缺一处，症状都从**用户视角**可见：
 * <ul>
 *   <li>缺 DTO 分量 → Jackson 直接抛（字段根本不在线上契约里）；</li>
 *   <li>缺 toDto 透出 → 读回 null（设置页恒显示「未填写」，用户以为没保存）；</li>
 *   <li>缺 update merge → 落库仍是旧值（用户点了没反应，且全程零报错）——「点了等于没点」。</li>
 * </ul>
 * 造请求体走 Jackson 反序列化（真实链路形状 = {@code SettingsController.update} 收
 * {@code @RequestBody SettingsDto}）；裸 {@code ObjectMapper} 的 FAIL_ON_UNKNOWN_PROPERTIES 默认 true，
 * 字段一旦被删就抛，比 Spring 的静默丢弃响亮。
 */
@DisplayName("[V78] settings.stream_idle_timeout_ms 往返契约（DTO/透出/merge 三处同步）")
class SettingsStreamIdleTimeoutRoundTripTest {

    private static final int SINGLETON_ID = 1;

    private SettingsService newService(SettingsMapper mapper) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        return service;
    }

    /** 造 DB 行。autoMemoryEnabled 设值：避免 {@code toDto} 回落 settings.json 文件读
     *  （沿用 {@code SettingsServiceTest.webSearchRow()} 的既有做法，保持 POJO 单测不碰文件系统）。 */
    private static SettingsRecord rowWith(Integer streamIdleTimeoutMs) {
        SettingsRecord row = new SettingsRecord();
        row.setAutoMemoryEnabled(true);
        row.setStreamIdleTimeoutMs(streamIdleTimeoutMs);
        return row;
    }

    private static SettingsDto reqFromJson(String json) throws Exception {
        return new ObjectMapper().readValue(json, SettingsDto.class);
    }

    @Test
    @DisplayName("端到端契约：PUT streamIdleTimeoutMs=600000 → 落库 + update 返回 + GET 读回 600000")
    void updateThenGetRoundTripsStreamIdleTimeoutMs() throws Exception {
        SettingsRecord row = rowWith(null);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);
        SettingsService service = newService(mapper);

        SettingsDto updated = service.update(reqFromJson("{\"streamIdleTimeoutMs\":600000}"));

        assertThat(updated.streamIdleTimeoutMs())
            .as("update 的返回值必须透出新值（前端 PUT 的响应体取的就是它）").isEqualTo(600000);
        assertThat(row.getStreamIdleTimeoutMs())
            .as("必须真的写进 DB 列（streamIdleTimeoutMs → stream_idle_timeout_ms 精确映射）").isEqualTo(600000);
        assertThat(service.get().streamIdleTimeoutMs())
            .as("GET 必须读回 600000；读不回 = 设置页恒显示「未填写」").isEqualTo(600000);
    }

    @Test
    @DisplayName("0 可写入且原样读回（消费点把 <=0 语义化为「未配置 → 回落 env/默认」，非拒绝）")
    void zeroIsStoredAndReadBack() throws Exception {
        // WHY：与 snipNudgeThreshold 的「0 必须拒绝」不同——本字段 0 有**明确定义**的语义
        //   （未配置），消费点 StreamIdleWatchdogSettings 按 <=0 → 走 env/默认 300000。
        SettingsRecord row = rowWith(600000);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);
        SettingsService service = newService(mapper);

        service.update(reqFromJson("{\"streamIdleTimeoutMs\":0}"));

        assertThat(row.getStreamIdleTimeoutMs()).as("0 是合法输入（= 清回未配置）").isZero();
        assertThat(service.get().streamIdleTimeoutMs()).isZero();
    }

    @Test
    @DisplayName("null-skip：PUT 未带该字段（= null）→ 不得改动既有值（PATCH 语义）")
    void nullDoesNotOverwriteExistingValue() throws Exception {
        // WHY：settings 的 PUT 是「仅覆盖非空字段」。漏了 null 判断而写成无条件写，
        //   任何一次「只改别的设置」的 PUT 都会把阈值静默重置（清成 null → 回落默认 300s）。
        SettingsRecord row = rowWith(600000);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);
        SettingsService service = newService(mapper);

        SettingsDto req = reqFromJson("{\"language\":\"zh-CN\"}");   // 未带该字段 → null
        assertThat(req.streamIdleTimeoutMs()).as("前置条件：字段缺失必须解析成 null").isNull();

        service.update(req);

        assertThat(row.getStreamIdleTimeoutMs())
            .as("null = 不覆盖：既有 600000 必须原样保留").isEqualTo(600000);
        assertThat(service.get().streamIdleTimeoutMs()).isEqualTo(600000);
    }
}
