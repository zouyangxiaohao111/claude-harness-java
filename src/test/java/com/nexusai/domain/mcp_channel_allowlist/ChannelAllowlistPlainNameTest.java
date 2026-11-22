package com.nexusai.domain.mcp_channel_allowlist;

import com.nexusai.repository.mcp_channel_allowlist.entity.ChannelAllowlistRecord;
import com.nexusai.repository.mcp_channel_allowlist.mapper.ChannelAllowlistMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelAllowlistPlainNameTest {

    private static ChannelAllowlistService service() {
        ChannelAllowlistService svc = new ChannelAllowlistService();
        ChannelAllowlistMapper mapper = mock(ChannelAllowlistMapper.class);
        ChannelAllowlistRecord row = new ChannelAllowlistRecord();
        ReflectionTestUtils.setField(row, "id", "cal-1");
        ReflectionTestUtils.setField(row, "marketplace", "official");
        ReflectionTestUtils.setField(row, "plugin", "im-internal");
        when(mapper.selectAll()).thenReturn(List.of(row));
        ReflectionTestUtils.setField(svc, "mapper", mapper);
        return svc;
    }

    @Test
    void plainNameHitsByPlugin() {
        assertThat(service().isAllowlisted("im-internal")).isTrue();
        assertThat(service().isAllowlisted("other-name")).isFalse();
    }

    @Test
    void marketplaceFormStillWorks() {
        assertThat(service().isAllowlisted("im-internal@official")).isTrue();
        assertThat(service().isAllowlisted("im-internal@other")).isFalse();
    }
}
