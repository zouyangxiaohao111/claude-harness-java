package com.nexusai.domain.session;

import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [gap29] SessionService 会话级禁用工具集合读写（V34 列 disabled_tools）意图测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>: 前端「点 × 临时禁用 → 该工具从模型 schema 移除，会话内生效」
 * （待前端对接 §29 #2）——禁用集合必须随会话持久化（跨 turn / 重开会话生效），读写往返一致。
 * 变异点：
 * <ul>
 *   <li>setDisabledTools 未落库 → 重开会话禁用丢失 → 红</li>
 *   <li>getDisabledTools 解析失败抛异常 → 列表接口 500 → 红</li>
 *   <li>session 不存在静默返回 → 前端误以为可用 → 红</li>
 * </ul>
 */
class SessionToolsServiceTest {

    private SessionService service;
    private SessionMapper sessionMapper;
    private SessionRecord record;

    @BeforeEach
    void setUp() {
        service = new SessionService();
        sessionMapper = mock(SessionMapper.class);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        record = new SessionRecord();
        record.setId("sess-1");
        when(sessionMapper.selectOneById(any())).thenReturn(record);
    }

    @Test
    @DisplayName("setDisabledTools → disabled_tools 落库（JSON 数组），getDisabledTools 读回一致")
    void setAndGet_roundTripPersists() {
        // WHY: 禁用集合随会话持久化（V34 列 disabled_tools）——跨 turn / 重开会话生效。
        //   变异点：写/读任一丢数据 → 禁用失效 → 红。
        // 注：Set.of 迭代序未定义，用 LinkedHashSet 固定插入序，断言 JSON 序列化稳定。
        service.setDisabledTools("sess-1", new LinkedHashSet<>(java.util.Arrays.asList("Bash", "WebSearch")));

        // 落库 JSON 数组串（fastjson2 序列化，按 LinkedHashSet 插入序稳定）
        assertThat(record.getDisabledTools()).isEqualTo("[\"Bash\",\"WebSearch\"]");
        // 同记录读回 → 集合一致
        Set<String> back = service.getDisabledTools("sess-1");
        assertThat(back).containsExactlyInAnyOrder("Bash", "WebSearch");
        verify(sessionMapper).update(record);
    }

    @Test
    @DisplayName("getDisabledTools null（未禁用）→ 空集合，不 NPE")
    void get_unset_returnsEmptySet() {
        record.setDisabledTools(null);

        assertThat(service.getDisabledTools("sess-1")).isEmpty();
    }

    @Test
    @DisplayName("setDisabledTools 空集 → 存 null（读回空集）")
    void set_emptyStoresNull() {
        service.setDisabledTools("sess-1", Set.of());

        assertThat(record.getDisabledTools()).isNull();
        assertThat(service.getDisabledTools("sess-1")).isEmpty();
    }

    @Test
    @DisplayName("getDisabledTools session 不存在 → NotFoundException（404）")
    void get_sessionMissing_throwsNotFound() {
        when(sessionMapper.selectOneById(any())).thenReturn(null);

        assertThatThrownBy(() -> service.getDisabledTools("ghost"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("setDisabledTools session 不存在 → NotFoundException（404）")
    void set_sessionMissing_throwsNotFound() {
        when(sessionMapper.selectOneById(any())).thenReturn(null);

        assertThatThrownBy(() -> service.setDisabledTools("ghost", Set.of("Bash")))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("恢复（移除工具名）→ 集合收缩，读回不含该工具")
    void set_removeTool_shrinksSet() {
        service.setDisabledTools("sess-1", new LinkedHashSet<>(Set.of("Bash", "WebSearch")));
        service.setDisabledTools("sess-1", Set.of("Bash"));   // 恢复 WebSearch

        assertThat(record.getDisabledTools()).isEqualTo("[\"Bash\"]");
        assertThat(service.getDisabledTools("sess-1")).containsExactly("Bash");
    }
}
