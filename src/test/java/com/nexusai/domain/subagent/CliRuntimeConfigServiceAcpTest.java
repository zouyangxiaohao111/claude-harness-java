package com.nexusai.domain.subagent;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.repository.subagent.entity.CliRuntimeConfigRecord;
import com.nexusai.repository.subagent.mapper.CliRuntimeConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ACP 成为合法 runtime kind（配置入口）· save(acp) 成功，save(unknown) 仍 400。
 */
@DisplayName("[acp] CliRuntimeConfigService 放行 acp")
class CliRuntimeConfigServiceAcpTest {

    private static CliRuntimeConfigService service(CliRuntimeConfigMapper mapper) {
        // 用真实 CryptoUtil（构造只需合法 base64 32 字节 key），避免 mock 加密实现
        CryptoUtil crypto = new CryptoUtil(Base64.getEncoder().encodeToString(new byte[32]));
        return new CliRuntimeConfigService(mapper, crypto);
    }

    private static CliRuntimeConfigMapper mapper() {
        CliRuntimeConfigMapper mapper = mock(CliRuntimeConfigMapper.class);
        when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(null);
        when(mapper.selectAll()).thenReturn(List.of());
        return mapper;
    }

    @Test
    @DisplayName("save(acp, bin) 成功并落库")
    void saveAcpRuntime() {
        CliRuntimeConfigMapper mapper = mapper();
        CliRuntimeConfigService svc = service(mapper);

        CliRuntimeConfigService.View view = svc.save("acp",
                new CliRuntimeConfigService.SaveRequest(null, null, "C:/tools/dsh.cmd",
                        null, null, null));

        assertThat(view.runtime()).isEqualTo("acp");
        verify(mapper).insert(any(CliRuntimeConfigRecord.class));
    }

    @Test
    @DisplayName("save(acp, secrets=[DEEPSEEK_API_KEY]) 成功；错名变量仍拒绝")
    void saveAcpSecrets() {
        CliRuntimeConfigMapper mapper = mapper();
        CliRuntimeConfigService svc = service(mapper);

        svc.save("acp", new CliRuntimeConfigService.SaveRequest(null, null, null, null, null,
                Map.of("DEEPSEEK_API_KEY", "sk-test-123")));
        verify(mapper).insert(any(CliRuntimeConfigRecord.class));

        CliRuntimeConfigMapper mapper2 = mapper();
        CliRuntimeConfigService svc2 = service(mapper2);
        assertThatThrownBy(() -> svc2.save("acp", new CliRuntimeConfigService.SaveRequest(
                null, null, null, null, null, Map.of("WRONG_ENV", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不认环境变量");
    }

    @Test
    @DisplayName("save(未知运行时) 仍拒绝且不写库")
    void saveUnknownStillRejected() {
        CliRuntimeConfigMapper mapper = mapper();
        CliRuntimeConfigService svc = service(mapper);

        assertThatThrownBy(() -> svc.save("not-a-runtime",
                new CliRuntimeConfigService.SaveRequest(null, null, "x", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的外部运行时");
        verify(mapper, never()).insert(any(CliRuntimeConfigRecord.class));
        verify(mapper, never()).updateByQuery(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(QueryWrapper.class));
    }
}
