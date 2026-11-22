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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [V334] 权限中继两道门的读写（DB 唯一权威，默认<b>开</b>）。
 *
 * <p>用户 2026-11-22 裁定：「两个开关默认打开，但都开不意味着开启推送，只有会话接入了渠道才行」。
 * 本测试钉住读侧的四种取值（有值 0/1、未配置、读失败）与写侧落库，避免默认值漂移成「默认关」
 * ——那会让刚开的渠道静默收不到任何审批镜像。
 */
@DisplayName("[V334] settings 两道渠道门：默认开 / 显式关 / 读失败")
class SettingsServiceChannelsGatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SettingsService newService(SettingsMapper mapper) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        return service;
    }

    @Test
    @DisplayName("未配置（行缺失 / 列 NULL）⇒ 两门都按默认开")
    void unsetMeansOpen() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(null);
        SettingsService service = newService(mapper);

        assertThat(service.readDbChannelsEnabled()).isTrue();
        assertThat(service.readDbChannelsPermissionRelayEnabled()).isTrue();

        SettingsRecord row = new SettingsRecord();
        when(mapper.selectOneById(1)).thenReturn(row);
        assertThat(service.readDbChannelsEnabled()).isTrue();
        assertThat(service.readDbChannelsPermissionRelayEnabled()).isTrue();
    }

    @Test
    @DisplayName("显式关闭（列 = 0）⇒ 各自为 false")
    void explicitOffWins() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        row.setChannelsEnabled(false);
        row.setChannelsPermissionRelayEnabled(false);
        when(mapper.selectOneById(1)).thenReturn(row);
        SettingsService service = newService(mapper);

        assertThat(service.readDbChannelsEnabled()).isFalse();
        assertThat(service.readDbChannelsPermissionRelayEnabled()).isFalse();
    }

    @Test
    @DisplayName("读失败 ⇒ 能力门按默认开；中继门 fail-closed（读不到就别推审批）")
    void readFailureSemantics() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenThrow(new RuntimeException("db down"));
        SettingsService service = newService(mapper);

        assertThat(service.readDbChannelsEnabled()).isTrue();
        assertThat(service.readDbChannelsPermissionRelayEnabled()).isFalse();
    }

    @Test
    @DisplayName("PUT 两个字段 ⇒ 落库（null 不覆盖）")
    void updatePersistsBoth() throws Exception {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        when(mapper.selectOneById(1)).thenReturn(row);
        SettingsService service = newService(mapper);

        service.update(JSON.readValue(
            "{\"channelsEnabled\":false,\"channelsPermissionRelayEnabled\":false}", SettingsDto.class));

        assertThat(row.getChannelsEnabled()).isFalse();
        assertThat(row.getChannelsPermissionRelayEnabled()).isFalse();
        verify(mapper).update(row);
    }

    @Test
    @DisplayName("GET ⇒ 两列原样透出（null = 未配置，前端按默认开渲染）")
    void getExposesRawNullable() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        row.setChannelsPermissionRelayEnabled(true);
        when(mapper.selectOneById(1)).thenReturn(row);
        SettingsService service = newService(mapper);

        SettingsDto dto = service.get();
        assertThat(dto.channelsPermissionRelayEnabled()).isTrue();
        assertThat(dto.channelsEnabled()).isNull();
    }
}
