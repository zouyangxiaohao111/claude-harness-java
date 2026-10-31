package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话级 computer-use 授权快照（规范形编解码 + 授权位 truthy-only 合并）单测。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>解析失败必须降级为空而不是抛</b>：一列脏数据不得让整轮 run 崩掉
 *       （照抄 {@code SessionService.getDisabledTools} 的 fail-soft 读法）。</li>
 *   <li><b>授权位只增不减</b>：后一次 {@code request_access} 若把 clipboardRead 传成 false，
 *       就把先前授予的剪贴板读权限<b>悄悄撤销</b>了 —— 这是安全回归（用户以为授了，其实没有）。
 *       断言必须锁死"后一次不能撤销"。</li>
 * </ul>
 */
class ComputerUseGrantsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("规范形往返：apps + flags 序列化后再解析完全一致")
    void canonicalFormRoundTrip() {
        ComputerUseGrants grants = new ComputerUseGrants(
                List.of(new ComputerUseGrant("chrome", "Chrome", 1750000000000L, "read")),
                new ComputerUseGrantFlags(true, false, true));

        String json = grants.toJson();
        ObjectNode root = JSON.createObjectNode();
        root.set("apps", JSON.valueToTree(grants.apps()));
        assertThat(json).contains("\"apps\"").contains("\"flags\"");

        ComputerUseGrants parsed = ComputerUseGrants.parse(json);
        assertThat(parsed.apps()).hasSize(1);
        assertThat(parsed.apps().get(0).appId()).isEqualTo("chrome");
        assertThat(parsed.apps().get(0).displayName()).isEqualTo("Chrome");
        assertThat(parsed.apps().get(0).grantedAt()).isEqualTo(1750000000000L);
        assertThat(parsed.apps().get(0).tier()).isEqualTo("read");
        assertThat(parsed.flags().clipboardRead()).isTrue();
        assertThat(parsed.flags().clipboardWrite()).isFalse();
        assertThat(parsed.flags().systemKeyCombos()).isTrue();
    }

    @Test
    @DisplayName("解析失败降级为空授权（不抛）· 对齐 SessionService.getDisabledTools 的 fail-soft 读法")
    void parseFailureDegradesToEmpty() {
        assertThat(ComputerUseGrants.parse("{not json").apps()).isEmpty();
        assertThat(ComputerUseGrants.parse("[1,2,3]").apps()).isEmpty();
        assertThat(ComputerUseGrants.parse(null).apps()).isEmpty();
        assertThat(ComputerUseGrants.parse("   ").apps()).isEmpty();
        // 坏条目被跳过，但好条目保留（一条脏数据不得让整列消失）
        ComputerUseGrants partial = ComputerUseGrants.parse(
                "{\"apps\":[{\"displayName\":\"无 appId\"},{\"appId\":\"notepad\",\"displayName\":\"记事本\"}]}");
        assertThat(partial.apps()).hasSize(1);
        assertThat(partial.apps().get(0).appId()).isEqualTo("notepad");
    }

    @Test
    @DisplayName("授权位 truthy-only 合并：后一次不能撤销先前的授权（CCB mcpServer.ts:58-77）")
    void flagsMergeIsTruthyOnly() {
        ComputerUseGrantFlags existing = new ComputerUseGrantFlags(true, true, false);
        ComputerUseGrantFlags incoming = new ComputerUseGrantFlags(false, false, true);

        ComputerUseGrantFlags merged = ComputerUseGrantFlags.mergeTruthyOnly(existing, incoming);

        assertThat(merged.clipboardRead()).isTrue();   // 先前 true ⇒ 保住
        assertThat(merged.clipboardWrite()).isTrue();  // 先前 true ⇒ 保住
        assertThat(merged.systemKeyCombos()).isTrue(); // 本次 true ⇒ 加上
    }

    @Test
    @DisplayName("默认授权位全 false（CCB types.ts:80-84 DEFAULT_GRANT_FLAGS）")
    void defaultFlagsAreAllFalse() {
        assertThat(ComputerUseGrantFlags.DEFAULT.clipboardRead()).isFalse();
        assertThat(ComputerUseGrantFlags.DEFAULT.clipboardWrite()).isFalse();
        assertThat(ComputerUseGrantFlags.DEFAULT.systemKeyCombos()).isFalse();
        assertThat(ComputerUseGrantFlags.DEFAULT.allFalse()).isTrue();
    }

    @Test
    @DisplayName("合并授权：应用按授权键去重（后者覆盖），授权位只增不减")
    void mergeKeepsExistingGrantsAndFlags() {
        ComputerUseGrants base = new ComputerUseGrants(
                List.of(new ComputerUseGrant("chrome", "Chrome", 1L, "read")),
                new ComputerUseGrantFlags(true, false, false));

        ComputerUseGrants merged = base.merge(
                List.of(new ComputerUseGrant("notepad", "记事本", 2L, "full")),
                new ComputerUseGrantFlags(false, false, false));

        assertThat(merged.apps()).hasSize(2);
        assertThat(merged.isGranted("chrome")).isTrue();
        assertThat(merged.isGranted("notepad")).isTrue();
        // 先前的剪贴板读授权未被撤销
        assertThat(merged.flags().clipboardRead()).isTrue();
    }

    @Test
    @DisplayName("查授权按归一后的 exe 名（授权键与前台判定键同一命名空间）")
    void findByNormalizedAppId() {
        ComputerUseGrants grants = new ComputerUseGrants(
                List.of(new ComputerUseGrant("chrome", "Chrome", 1L, "read")),
                ComputerUseGrantFlags.DEFAULT);
        assertThat(grants.findByAppId("C:\\Program Files\\chrome.exe")).isNotNull();
        assertThat(grants.findByAppId("CHROME")).isNotNull();
        assertThat(grants.findByAppId("firefox")).isNull();
    }
}
