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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code settings.ask_user_afk_timeout} 的读写（[V333]）。
 *
 * <p>锁定三点：
 * <ol>
 *   <li><b>写侧 fail-loud</b>：非法档位 400，绝不静默折叠 —— 用户以为设了 1 小时，实际关闭，
 *       这是最容易踩的坑；</li>
 *   <li><b>规范值落库</b>：大小写/空白归一成 {@code never/60s/5m/10m}；</li>
 *   <li><b>读侧原样透出</b>：{@code get()} 把列值带进 {@link SettingsDto}（前端据此显示当前档位）。</li>
 * </ol>
 *
 * <p>⛔ 不在此测「生效时长解析」——那是 {@code AskUserAfkTimeout} 纯函数的职责（见该测试）。
 */
@DisplayName("SettingsService · ask_user_afk_timeout 读写")
class SettingsServiceAfkTimeoutTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SettingsService newService(SettingsMapper mapper, SettingsRecord row) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        when(mapper.selectOneById(1)).thenReturn(row);
        return service;
    }

    /** 只带 AFK 一个字段的请求（避免手写 70 参构造）。 */
    private static SettingsDto req(String afk) throws Exception {
        return JSON.readValue("{\"askUserAfkTimeout\":\"" + afk + "\"}", SettingsDto.class);
    }

    @Test
    @DisplayName("合法档位 ⇒ 归一化后落库（大小写/空白）")
    void validValueIsCanonicalizedAndPersisted() throws Exception {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        SettingsService service = newService(mapper, row);

        service.update(req(" 5M "));

        assertThat(row.getAskUserAfkTimeout()).isEqualTo("5m");
        verify(mapper).update(row);
    }

    @Test
    @DisplayName("非法档位 ⇒ 400（ValidationException），且不落库")
    void invalidValueFailsLoud() throws Exception {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        SettingsService service = newService(mapper, row);

        assertThatThrownBy(() -> service.update(req("1h")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("askUserAfkTimeout");

        assertThat(row.getAskUserAfkTimeout()).isNull();
        verify(mapper, never()).update(row);
    }

    @Test
    @DisplayName("省略字段 ⇒ 不覆盖旧值（merge 语义）")
    void omittedKeepsOldValue() throws Exception {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        row.setAskUserAfkTimeout("10m");
        SettingsService service = newService(mapper, row);

        // 全空请求（Jackson 构造 ⇒ 每个组件都是 null，等价 PUT 只带别的字段）
        service.update(JSON.readValue("{}", SettingsDto.class));

        assertThat(row.getAskUserAfkTimeout()).isEqualTo("10m");
    }

    @Test
    @DisplayName("get() ⇒ 列值原样透出（含 never）")
    void getExposesColumnValue() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord row = new SettingsRecord();
        row.setAskUserAfkTimeout("60s");
        SettingsService service = newService(mapper, row);

        assertThat(service.get().askUserAfkTimeout()).isEqualTo("60s");
    }
}
