package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.team.TeamMemorySyncTypes;
import com.nexusai.application.agent.memory.TeamMemoryHttpClient.FetchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4-3（IMP-MV2-25）· B1 ?-12 fetchOnce {@code catch(Exception)} 可触发性运行验证。
 *
 * <p>探查证据（raw/team-memory-sync/探查-mm-b1-team-sync-main.md §9 ?-12）：
 * Java {@code catch (Exception e) → FetchResult.error(e.getMessage(), true, "parse", null)}
 * （TeamMemoryHttpClient:434-435）vs CC 非 axios 错误 → 'unknown' + 可重试（index.ts:297-303）。
 * 触发场景假设 = {@code URI.create} 对畸形 baseUrl 抛 IllegalArgumentException（baseUrl 来自
 * TEAM_MEMORY_SYNC_URL env 或默认常量）——本测试运行时注入畸形 baseUrl 实证可触发性。
 *
 * <p>结论口径：可触发性 = <b>可达</b>（畸形 baseUrl 注入即触发 'parse'+skipRetry:true），
 * 触发面 = TEAM_MEMORY_SYNC_URL 环境变量误配置（生产默认常量合法）；与 CC 差异（'unknown'
 * + 可重试 vs 'parse'+skipRetry）在畸形配置下可观测，登记维持（配置面错误，非正常运行路径）。
 */
@DisplayName("[E4-3] fetchOnce catch(Exception) 可触发性：畸形 baseUrl 运行时注入")
class E4FetchOnceTriggerabilityTest {

    private static TeamMemoryHttpClient client() {
        return new TeamMemoryHttpClient(HttpClient.newHttpClient(),
            () -> Map.of("Authorization", "Bearer test"));
    }

    @Test
    @DisplayName("畸形 baseUrl → URI.create 抛 IllegalArgumentException → catch(Exception) → 'parse'+skipRetry:true")
    void malformedBaseUrl_landsInCatchException_asParseSkipRetry() {
        // 畸形 baseUrl：非法 IPv6 括号 —— 字符串拼接后 URI.create 必然抛 IllegalArgumentException
        // （java.net.URI 构造校验，非 IO 非 InterruptedException → 落入 :434 catch(Exception)）。
        String malformedBaseUrl = "http://[::1";  // 未闭合 '[' —— URI 语法非法
        TeamMemoryHttpClient client = client();
        TeamMemorySyncTypes.SyncState state = TeamMemorySyncTypes.SyncState.create();

        FetchResult result = client.fetchOnce(state, malformedBaseUrl, "acme/demo", null);

        assertThat(result.success()).as("畸形 baseUrl 不得成功").isFalse();
        assertThat(result.skipRetry()).as("catch(Exception) 分支 skipRetry:true（CC 非 axios 错误为可重试 'unknown'——语义分叉点）")
            .isTrue();
        assertThat(result.errorType()).as("catch(Exception) 分支 errorType='parse'").isEqualTo("parse");
        assertThat(result.error()).isNotNull();
    }

    @Test
    @DisplayName("对照：合法 baseUrl + 未监听端口 → IOException 分支 'network'（:426-430），不受 ?-12 影响")
    void reachableServer_usesNetworkBranchNotParse() {
        // 合法 baseUrl 指向本机未监听端口 → connect 失败 → IOException → 'network' + 可重试。
        // 证明 'parse' 分支仅畸形配置可达，正常运行错误走 CC 对齐的 network/unknown 语义。
        TeamMemoryHttpClient client = client();
        TeamMemorySyncTypes.SyncState state = TeamMemorySyncTypes.SyncState.create();

        FetchResult result = client.fetchOnce(state, "http://127.0.0.1:1", "acme/demo", null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorType()).as("连接失败 → 'network'（CC 'network' 等价）").isEqualTo("network");
        assertThat(result.skipRetry()).as("network 分支可重试（CC 非 skipRetry 一致）").isFalse();
    }
}
