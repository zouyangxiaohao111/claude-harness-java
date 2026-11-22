package com.nexusai.domain.im_channel.send;

import com.nexusai.domain.im_channel.ImSendService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工厂分发与 {@code notifyChats} 解析的边界覆盖。
 *
 * <h2>WHY</h2>
 * 这两处是「投递链的入口」：工厂要给未知平台一个**有原因**的出口（而不是 null / 异常），
 * 解析要能扛住库里各种历史脏值（NULL、空串、非法 JSON、混类型数组）。
 * 入口一崩，整批投递就没了 —— 所以这里专门测「坏输入不让程序崩」。
 */
class ImSendStrategyFactoryAndParseTest {

    /** 最小可用策略（只需要 platform + 能力，不参与本测试的 doSend）。 */
    private static ImSendStrategy stub(String platform) {
        return new AbstractImSendStrategy() {
            @Override public String platform() { return platform; }
            @Override public SendCapability capability() { return SendCapability.proactive(100, true); }
            @Override public String doSend(ImSendContext ctx, String text) { return "id"; }
        };
    }

    // ───────────────────────── 工厂 ─────────────────────────

    @Test
    @DisplayName("已注册平台 → 返回对应策略")
    void registeredPlatformReturnsItsStrategy() {
        ImSendStrategyFactory f = new ImSendStrategyFactory(
                List.of(stub("feishu"), stub("wecom")));

        assertThat(f.of("feishu").platform()).isEqualTo("feishu");
        assertThat(f.of("wecom").platform()).isEqualTo("wecom");
        assertThat(f.supportedPlatforms()).containsExactlyInAnyOrder("feishu", "wecom");
    }

    @Test
    @DisplayName("未知平台 → Unsupported（不返回 null、不抛异常）")
    void unknownPlatformReturnsUnsupported() {
        ImSendStrategyFactory f = new ImSendStrategyFactory(List.of(stub("feishu")));

        ImSendStrategy s = f.of("qq");

        assertThat(s).isNotNull();
        assertThat(s.platform()).isEqualTo("qq");
        assertThat(s.capability().canSendProactively()).isFalse();
        assertThat(s.capability().unsupportedReason()).contains("qq");
    }

    @Test
    @DisplayName("platform 为 null / 空白 → Unsupported，不 NPE")
    void nullPlatformReturnsUnsupported() {
        ImSendStrategyFactory f = new ImSendStrategyFactory(List.of(stub("feishu")));

        assertThat(f.of(null).capability().canSendProactively()).isFalse();
        assertThat(f.of("   ").capability().canSendProactively()).isFalse();
    }

    @Test
    @DisplayName("构造时传 null / 含 null 元素 / 缺 platform 的策略 → 不崩")
    void constructorToleratesBadInput() {
        ImSendStrategyFactory f = new ImSendStrategyFactory(new ArrayList<>(
                Arrays.asList(null, stub("feishu"))));
        assertThat(f.of("feishu").platform()).isEqualTo("feishu");

        ImSendStrategyFactory nullList = new ImSendStrategyFactory(null);
        assertThat(nullList.supportedPlatforms()).isEmpty();
        assertThat(nullList.of("feishu")).isNotNull();
    }

    @Test
    @DisplayName("重复平台：后者覆盖前者（Spring 里不该发生，但不能崩）")
    void duplicatePlatformLastWins() {
        ImSendStrategy a = stub("feishu");
        ImSendStrategy b = stub("feishu");
        ImSendStrategyFactory f = new ImSendStrategyFactory(List.of(a, b));

        assertThat(f.of("feishu")).isSameAs(b);
    }

    // ───────────────────────── notifyChats 解析 ─────────────────────────

    @Test
    @DisplayName("null / 空串 → 空列表（不抛）")
    void parseNullOrBlank() {
        assertThat(ImSendService.parseChatIds(null)).isEmpty();
        assertThat(ImSendService.parseChatIds("")).isEmpty();
        assertThat(ImSendService.parseChatIds("   ")).isEmpty();
    }

    @Test
    @DisplayName("合法 JSON 数组 → 按序解析，跳过空/非字符串元素")
    void parseJsonArray() {
        assertThat(ImSendService.parseChatIds("[\"a\",\"b\"]")).containsExactly("a", "b");
        // 含 null / 数字 / 空串 的脏数组：只取非空字符串
        assertThat(ImSendService.parseChatIds("[\"a\",null,1,\"  \",\"b\"]")).containsExactly("a", "b");
        assertThat(ImSendService.parseChatIds("[]")).isEmpty();
        // JSON 对象不是数组 → 空
        assertThat(ImSendService.parseChatIds("{\"a\":\"b\"}")).isEmpty();
    }

    @Test
    @DisplayName("非法 JSON → 空列表（⛔ 不能让一条脏配置炸掉投递）")
    void parseInvalidJsonReturnsEmpty() {
        assertThat(ImSendService.parseChatIds("[not json")).isEmpty();
        assertThat(ImSendService.parseChatIds("\\\\")).isEmpty();
    }

    @Test
    @DisplayName("兼容逗号分隔历史形态")
    void parseCommaSeparated() {
        assertThat(ImSendService.parseChatIds("a,b")).containsExactly("a", "b");
        assertThat(ImSendService.parseChatIds("a, ,b")).containsExactly("a", "b");
        assertThat(ImSendService.parseChatIds("a")).containsExactly("a");
    }

    @Test
    @DisplayName("JSON 数组带空白与首尾空格 → 结果 trim")
    void parseTrimsValues() {
        assertThat(ImSendService.parseChatIds(" [ \" a \" , \"b\" ] ")).containsExactly("a", "b");
    }

    @Test
    @DisplayName("ImSendResult 三态语义：SKIPPED 可接受、FAILED 不可接受")
    void resultTriState() {
        assertThat(ImSendResult.sent("id").ok()).isTrue();
        assertThat(ImSendResult.sent("id").acceptable()).isTrue();

        ImSendResult skipped = ImSendResult.skipped("IM_SEND_UNSUPPORTED", null);
        assertThat(skipped.ok()).isFalse();
        assertThat(skipped.acceptable()).as("跳过是能力边界，不是失败").isTrue();
        assertThat(skipped.reason()).as("SKIPPED 必须带原因").isNotBlank();

        ImSendResult failed = ImSendResult.failed("IM_SEND_PLATFORM_ERROR", null);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.acceptable()).isFalse();
        assertThat(failed.reason()).isNotBlank();
    }
}
