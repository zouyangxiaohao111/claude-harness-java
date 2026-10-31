package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code mcp__computer-use__wait} 的单测（Phase 1c）。
 *
 * <p><b>WHY（规则 9）</b>：等待工具本身无害，但它承载<b>模型的心智模型</b>——
 * 模型靠它"等界面加载"。因此必须保证：①时长真的被等待（不是空转）；
 * ②越界时长被<b>明确拒绝</b>（否则模型可以要求等 10000 秒把会话挂死）；
 * ③描述里的区间（0–100）与实现一致。
 */
class ComputerUseWaitToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 记录休眠时长的假时钟（单测零耗时）。 */
    private static final class RecordingSleeper implements ComputerUseSleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    private static ObjectNode input(double duration) {
        ObjectNode node = JSON.createObjectNode();
        node.put("duration", duration);
        return node;
    }

    @Test
    @DisplayName("工具面逐字对齐 CCB：name / description / schema（0–100 的 en dash 不能变成连字符）")
    void toolSurfaceMatchesCcbVerbatim() {
        ComputerUseWaitTool tool = new ComputerUseWaitTool(new RecordingSleeper());

        assertThat(tool.name()).isEqualTo("mcp__computer-use__wait");
        assertThat(tool.description()).isEqualTo("Wait for a specified duration.");
        assertThat(tool.inputSchema().path("required").get(0).asText()).isEqualTo("duration");
        String description = tool.inputSchema().path("properties").path("duration").path("description").asText();
        assertThat(description).isEqualTo("Duration in seconds (0–100).");
        int dashIndex = description.indexOf("–");
        assertThat(dashIndex).as("描述里必须有一个 dash").isGreaterThan(0);
        assertThat((int) description.charAt(dashIndex))
                .as("CCB 用的是 en dash（U+2013），不是 ASCII 连字符")
                .isEqualTo(0x2013);
        assertThat(description).doesNotContain("0-100");
    }

    @Test
    @DisplayName("真的等待了 duration 秒（毫秒换算正确）")
    void waitsRequestedDuration() {
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseWaitTool tool = new ComputerUseWaitTool(sleeper);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__wait", input(2)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Waited 2s.");
        assertThat(sleeper.sleeps).containsExactly(2000L);
    }

    @Test
    @DisplayName("小数时长保留小数（与 JS 的 Number→String 一致：2.5 ⇒ \"2.5\"）")
    void fractionalDurationKeepsFraction() {
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseWaitTool tool = new ComputerUseWaitTool(sleeper);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__wait", input(2.5)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Waited 2.5s.");
        assertThat(sleeper.sleeps).containsExactly(2500L);
    }

    @Test
    @DisplayName("越界与非法时长 ⇒ 逐字报错（CCB toolCalls.ts:3641-3654）")
    void invalidDurationsAreRejected() {
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseWaitTool tool = new ComputerUseWaitTool(sleeper);

        var negative = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__wait", input(-1)));
        assertThat(((ToolResult<?>) negative).data().toString()).contains("duration must be non-negative");

        var tooLong = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__wait", input(101)));
        assertThat(((ToolResult<?>) tooLong).data().toString())
                .contains("duration is too long. Duration is in seconds.");

        var notNumber = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__wait",
                JSON.createObjectNode().put("duration", "abc")));
        assertThat(((ToolResult<?>) notNumber).data().toString()).contains("duration must be a number");

        assertThat(sleeper.sleeps).as("非法时长不得真的等待").isEmpty();
    }

    @Test
    @DisplayName("边界值：0 秒与 100 秒都合法")
    void boundaryDurationsAreAccepted() {
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseWaitTool tool = new ComputerUseWaitTool(sleeper);

        var zero = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__wait", input(0)));
        assertThat(((ToolResult<?>) zero).data()).isEqualTo("Waited 0s.");

        var max = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__wait", input(100)));
        assertThat(((ToolResult<?>) max).data()).isEqualTo("Waited 100s.");

        assertThat(sleeper.sleeps).containsExactly(0L, 100000L);
    }

    @Test
    @DisplayName("等待是只读且并发安全（不触碰系统状态）")
    void waitIsReadOnlyAndConcurrencySafe() {
        ComputerUseWaitTool tool = new ComputerUseWaitTool(new RecordingSleeper());

        assertThat(tool.isReadOnly(JSON.createObjectNode())).isTrue();
        assertThat(tool.isConcurrencySafe(JSON.createObjectNode())).isTrue();
    }
}
