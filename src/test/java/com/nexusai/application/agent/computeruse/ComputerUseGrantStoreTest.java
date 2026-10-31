package com.nexusai.application.agent.computeruse;

import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话授权位读写（RMW）单测。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>读失败/坏列 ⇒ 降级为空授权</b>：授权列读不出来时，工具必须表现为"没授权"
 *       （⇒ 点击被拒），而不是"崩掉"或"当成已授权"。</li>
 *   <li><b>写走整行 update 且列非空</b>：写不进 ⇒ 授权丢失 ⇒ 模型下次调用会被拒 ⇒ 卡死。</li>
 * </ul>
 */
class ComputerUseGrantStoreTest {

    private static final String SESSION = "sess-1";

    private SessionMapper sessionMapper;
    private SessionRecord record;
    private ComputerUseGrantStore store;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        record = new SessionRecord();
        record.setId(SESSION);
        when(sessionMapper.selectOneById(SESSION)).thenReturn(record);
        when(sessionMapper.update(any(SessionRecord.class))).thenReturn(1);
        store = new ComputerUseGrantStore(sessionMapper);
    }

    @Test
    @DisplayName("空列 ⇒ 空授权（不抛）")
    void emptyColumnYieldsEmptyGrants() {
        record.setComputerUseGrants(null);
        assertThat(store.read(SESSION).apps()).isEmpty();
        assertThat(store.read(SESSION).flags().allFalse()).isTrue();
    }

    @Test
    @DisplayName("坏列 ⇒ 降级为空授权 + 不抛（fail-soft）")
    void brokenColumnDegradesToEmpty() {
        record.setComputerUseGrants("{broken");
        assertThat(store.read(SESSION).apps()).isEmpty();
    }

    @Test
    @DisplayName("读失败（mapper 抛异常）⇒ 降级为空授权 + 不抛")
    void readFailureDegradesToEmpty() {
        when(sessionMapper.selectOneById(SESSION)).thenThrow(new RuntimeException("db down"));
        assertThat(store.read(SESSION).apps()).isEmpty();
    }

    @Test
    @DisplayName("写入：授权 + 授权位落到会话列（整行 update）")
    void grantWritesColumn() {
        ComputerUseGrants after = store.grant(SESSION,
                List.of(new ComputerUseGrant("notepad", "记事本", 42L, "full")),
                new ComputerUseGrantFlags(true, false, false));

        assertThat(after.apps()).hasSize(1);
        assertThat(after.flags().clipboardRead()).isTrue();

        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        verify(sessionMapper).update(captor.capture());
        assertThat(captor.getValue().getComputerUseGrants()).contains("notepad").contains("clipboardRead");
    }

    @Test
    @DisplayName("授权位 truthy-only：后一次传 false 不撤销先前的 true")
    void grantKeepsExistingFlags() {
        store.grant(SESSION, List.of(), new ComputerUseGrantFlags(true, false, false));

        ComputerUseGrants after = store.grant(SESSION, List.of(), new ComputerUseGrantFlags(false, false, false));

        assertThat(after.flags().clipboardRead()).isTrue();
    }

    @Test
    @DisplayName("会话不存在 ⇒ 不写库 + 返回空授权")
    void missingSessionDoesNotWrite() {
        when(sessionMapper.selectOneById("nope")).thenReturn(null);
        ComputerUseGrants after = store.grant("nope", List.of(), ComputerUseGrantFlags.DEFAULT);
        assertThat(after.apps()).isEmpty();
    }
}
