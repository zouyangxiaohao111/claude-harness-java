package com.nexusai.application.agent.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2] 短 ID 随机化 + 会话内唯一守卫 + 哈希回退路径。
 *
 * <p><b>WHY</b>：渠道回执是「yes &lt;5 位短 ID&gt;」。确定性哈希（同 toolUseId 恒同号）可被
 * 旁路猜测/复用；改成随机后仍必须满足：
 * <ol>
 *   <li>字符集 = 25 个小写字母（去掉 {@code l}，避免 1/l 混淆）、长度 5；</li>
 *   <li>脏词子串命中 ⇒ 重试（不能把不雅词发到用户手机上）；</li>
 *   <li><b>会话内</b>不重复（重复会覆盖 pending 表、让回执命中错的请求）；</li>
 *   <li>随机失败保留<b>哈希回退</b>路径（可复算）。</li>
 * </ol>
 */
@DisplayName("[P2] 短 ID 随机化")
class ShortIdRandomizationTest {

    private final ChannelPermission cp = new ChannelPermission(() -> true);

    /** 独立实现的 FNV-1a → base-25（用于「可复算样例」对照，不复用被测实现）。 */
    private static String expectedHashToId(String input) {
        long h = 0x811c9dc5L;
        for (int i = 0; i < input.length(); i++) {
            h ^= input.charAt(i);
            h = (h * 0x01000193L) & 0xFFFFFFFFL;
        }
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(ChannelPermission.ID_ALPHABET.charAt((int) (h % 25)));
            h = h / 25;
        }
        return sb.toString();
    }

    private static boolean dirty(String id) {
        for (String bad : ChannelPermission.ID_AVOID_SUBSTRINGS) {
            if (id.contains(bad)) return true;
        }
        return false;
    }

    private static String expectedShortRequestId(String toolUseId) {
        if (toolUseId == null) return "aaaaa";
        for (int salt = 0; salt < 10; salt++) {
            String c = expectedHashToId(salt == 0 ? toolUseId : toolUseId + ":" + salt);
            if (!dirty(c)) return c;
        }
        return expectedHashToId(toolUseId);
    }

    @Test
    @DisplayName("随机号：长度 5 / 25 字母（无 l）/ 不命中脏词 / 会话内不重复")
    void randomIdsShapeAndUniqueness() {
        assertThat(ChannelPermission.ID_ALPHABET).hasSize(25).doesNotContain("l");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = cp.shortRequestIdForSession("sess-p2-shape", "tool-" + i);
            assertThat(id).hasSize(5);
            for (char c : id.toCharArray()) {
                assertThat(ChannelPermission.ID_ALPHABET.indexOf(c))
                    .as("字符 %s 必须在 25 字母表内（无 l）", c).isGreaterThanOrEqualTo(0);
            }
            assertThat(dirty(id)).as("短 ID 不得含脏词子串: %s", id).isFalse();
            assertThat(seen.add(id)).as("同一会话内不得重复发号: %s", id).isTrue();
        }
    }

    @Test
    @DisplayName("哈希回退路径可复算：独立 FNV-1a 实现逐样例对照")
    void hashFallbackIsReproducible() {
        String[] samples = {
            "call_relay_60380_1795328383407_35_0",
            "call_relay_tooluseid_probe",
            "tool-A",
            "Bash",
            "x",
            ""
        };
        for (String s : samples) {
            assertThat(cp.shortRequestId(s))
                .as("样例 %s 的哈希短 ID 必须可复算", s)
                .isEqualTo(expectedShortRequestId(s));
        }
        assertThat(cp.shortRequestId(null)).isEqualTo("aaaaa");
    }

    @Test
    @DisplayName("脏词命中 ⇒ 换盐重 hash（不返回脏号，且结果可复现）")
    void dirtyWordTriggersRehash() {
        String dirtySeed = null;
        for (int i = 0; i < 200_000 && dirtySeed == null; i++) {
            String probe = "probe-" + i;
            if (dirty(expectedHashToId(probe))) dirtySeed = probe;
        }
        assertThat(dirtySeed).as("前 20 万个探针应能找到哈希落脏的输入").isNotNull();

        String id = cp.shortRequestId(dirtySeed);
        assertThat(dirty(id)).as("脏词命中后必须换盐，不得返回脏号").isFalse();
        assertThat(id).isEqualTo(expectedShortRequestId(dirtySeed));
        assertThat(cp.shortRequestId(dirtySeed)).as("可复现").isEqualTo(id);
    }

    @Test
    @DisplayName("会话内撞号 ⇒ 重试/哈希回退；不同会话互不影响；释放后可复用")
    void sessionScopedUniquenessAndRelease() {
        // 强制随机源恒为 "aaaaa"：同会话第二次必然撞号 ⇒ 走哈希回退
        cp.setRandomCandidateSupplier(() -> "aaaaa");
        try {
            String first = cp.shortRequestIdForSession("sess-A", "t1");
            assertThat(first).isEqualTo("aaaaa");

            String second = cp.shortRequestIdForSession("sess-A", "t1");
            assertThat(second).as("同会话撞号必须重试/回退，不得重复").isNotEqualTo("aaaaa");
            assertThat(dirty(second)).isFalse();

            String otherSession = cp.shortRequestIdForSession("sess-B", "t1");
            assertThat(otherSession).as("唯一性作用域是会话内，B 会话可用同号").isEqualTo("aaaaa");

            cp.releaseShortId("sess-A", "aaaaa");
            assertThat(cp.shortRequestIdForSession("sess-A", "t2"))
                .as("释放后该号可再次发出").isEqualTo("aaaaa");
        } finally {
            cp.setRandomCandidateSupplier(null);
        }
    }
}
