package com.nexusai.application.agent.mcp;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T9 · normalizeNameForMCP 小写变体统一（⊕-19）。
 *
 * <p><b>WHY (意图验证)</b>: McpServerUtils 旧实现 {@code toLowerCase()} 与 McpStringUtils
 * （CC normalization.ts:17-23 无小写）双实现漂移。server 名含大写/./空格时，前缀
 * 匹配会错位——旧实现把 "My.Server" 变成 "my_server"，无法匹配工具名 "mcp__My_Server__x"
 * （CC 语义保留大小写）。本测试锁定 CC 语义：无小写 + claude.ai 前缀 collapse。
 * （原过滤消费点用例已随 D-B10-02 删除，normalize 语义由本 3 用例锁定。）
 */
class McpServerUtilsNormalizeTest {

    @Test
    @DisplayName("normalizeNameForMCP: My.Server → My_Server（非 my_server，无小写）")
    void preservesCase() {
        assertThat(McpServerUtils.normalizeNameForMCP("My.Server")).isEqualTo("My_Server");
        assertThat(McpServerUtils.normalizeNameForMCP("My.Server")).isNotEqualTo("my_server");
    }

    @Test
    @DisplayName("normalizeNameForMCP: claude.ai Slack → claude_ai_Slack（collapse _ + 去首尾 _）")
    void claudeAiPrefixCollapse() {
        assertThat(McpServerUtils.normalizeNameForMCP("claude.ai Slack")).isEqualTo("claude_ai_Slack");
    }

    @Test
    @DisplayName("normalizeNameForMCP: null → 空串（McpServerUtils null 安全保留）")
    void nullReturnsEmpty() {
        assertThat(McpServerUtils.normalizeNameForMCP(null)).isEqualTo("");
    }
}
