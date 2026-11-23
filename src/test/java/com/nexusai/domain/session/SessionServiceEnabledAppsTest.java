package com.nexusai.domain.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PATCH /sessions/{id} 的 enabledApps 落列（V337 enabled_apps = schema 门）：
 * 数组 → JSON；{@code []} → {@code "[]"}；显式 null → 清列（update(...,false)）；
 * 缺省 → 不改动；非法形态 → 400；DTO 回读三态。
 */
@DisplayName("SessionService · enabledApps 落列（V337）")
class SessionServiceEnabledAppsTest {

    private static final String SID = "sess-enabled";
    private final ObjectMapper om = new ObjectMapper();

    private SessionService service;
    private SessionMapper mapper;
    private SessionRecord row;

    @BeforeEach
    void setUp() {
        service = new SessionService();
        mapper = mock(SessionMapper.class);
        row = new SessionRecord();
        row.setId(SID);
        when(mapper.selectOneById(any())).thenReturn(row);
        ReflectionTestUtils.setField(service, "sessionMapper", mapper);
    }

    /** 11 参：只有 enabledApps 有值。 */
    private static SessionUpdateRequest req(JsonNode enabledApps) {
        return new SessionUpdateRequest(null, null, null, null, null, null, null,
            null, null, null, enabledApps);
    }

    private SessionRecord capturedUpdate() {
        ArgumentCaptor<SessionRecord> cap = ArgumentCaptor.forClass(SessionRecord.class);
        verify(mapper).update(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("数组 ⇒ 写 JSON 数组，DTO 回读为列表")
    void array_persistsAndReadsBack() {
        JsonNode arr = om.createArrayNode().add("A").add("B");

        SessionDto dto = service.update(SID, req(arr));

        assertThat(capturedUpdate().getEnabledApps()).isEqualTo("[\"A\",\"B\"]");
        assertThat(dto.enabledApps()).containsExactly("A", "B");
    }

    @Test
    @DisplayName("空数组 ⇒ 写 \"[]\"，DTO 回读为空列表（与 null 区分）")
    void emptyArray_persistsEmptyJson() {
        SessionDto dto = service.update(SID, req(om.createArrayNode()));

        assertThat(capturedUpdate().getEnabledApps()).isEqualTo("[]");
        assertThat(dto.enabledApps()).isEmpty();
    }

    @Test
    @DisplayName("显式 null ⇒ 清列：必须 update(entity,false) 才能写 NULL；DTO 回读 null")
    void explicitNull_clearsWithIgnoreNullsFalse() {
        row.setEnabledApps("[\"X\"]");

        SessionDto dto = service.update(SID, req(om.nullNode()));

        ArgumentCaptor<SessionRecord> cap = ArgumentCaptor.forClass(SessionRecord.class);
        verify(mapper).update(cap.capture(), eq(false));
        assertThat(cap.getValue().getEnabledApps()).isNull();
        assertThat(dto.enabledApps()).isNull();
    }

    @Test
    @DisplayName("字段缺省 ⇒ 不改动该列（1 参 update）")
    void absent_doesNotTouch() {
        row.setEnabledApps("[\"X\"]");

        service.update(SID, req(null));

        verify(mapper).update(any(SessionRecord.class));
        verify(mapper, never()).update(any(SessionRecord.class), anyBoolean());
        assertThat(row.getEnabledApps()).isEqualTo("[\"X\"]");
    }

    @Test
    @DisplayName("非法形态（对象）⇒ 400 ValidationException")
    void invalidType_throws() {
        assertThatThrownBy(() -> service.update(SID, req(om.createObjectNode())))
            .isInstanceOf(ValidationException.class);
    }
}
