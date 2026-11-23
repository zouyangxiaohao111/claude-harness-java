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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设置层放行 ACP 作为「子代理默认运行时」· 未知 runtime 仍 400。
 *
 * <p>与 {@code SettingsServiceSubagentDefaultRuntimeTest} 同款夹具；单独成文件避免动既有测试。
 */
@DisplayName("[acp] SettingsService 默认运行时放行 acp")
class SettingsServiceAcpRuntimeTest {

    private static final int SINGLETON_ID = 1;

    private static SettingsService newService(SettingsMapper mapper, SettingsRecord dbRow) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(dbRow);
        return service;
    }

    private static SettingsRecord rowWithDefault(String runtime) {
        SettingsRecord s = new SettingsRecord();
        s.setAutoMemoryEnabled(true);   // 避免 toDto 回落 settings.json 文件读
        s.setSubagentDefaultRuntime(runtime);
        return s;
    }

    private static SettingsDto req(String defaultRuntime) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            node.put("subagentDefaultRuntime", defaultRuntime);
            return new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(node, SettingsDto.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("update(\"acp\") 成功落库")
    void updateAcpAccepted() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = rowWithDefault("claude");
        SettingsService service = newService(mapper, dbRow);

        service.update(req("acp"));

        assertThat(dbRow.getSubagentDefaultRuntime()).isEqualTo("acp");
        verify(mapper).update(dbRow);
    }

    @Test
    @DisplayName("update(未知 runtime) 仍 400 且不写库")
    void updateUnknownStillRejected() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        SettingsRecord dbRow = rowWithDefault("claude");
        SettingsService service = newService(mapper, dbRow);

        assertThatThrownBy(() -> service.update(req("totally-unknown")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("totally-unknown");
        verify(mapper, never()).update(any(SettingsRecord.class));
        assertThat(dbRow.getSubagentDefaultRuntime()).isEqualTo("claude");
    }
}
