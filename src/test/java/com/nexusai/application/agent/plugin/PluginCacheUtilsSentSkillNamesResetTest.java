package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.skill.SkillListingSentRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-22 · 2026-09-11] {@code PluginCacheUtils.clearAllCaches} 末位 {@code resetSentSkillNames}
 * 的级联实测（对齐 CC {@code cacheUtils.ts:44-50}，末项 {@code resetSentSkillNames()}）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图）</b>：CC 插件上下文变更（安装/启停/升级、{@code /reload-plugins}）
 * 时整份重发技能清单 —— 机制是 {@code clearAllCaches()} 末位清空 {@code sentSkillNames}
 * （{@code sentSkillNames.clear() + suppressNext=false}，attachments.ts:2681-2685）→ 下一次 attachment pass
 * 因 sent 空而 {@code isInitial=true} → <b>整份重发</b>。我们此前缺这一步：技能候选集变了，去重记忆没清
 * → 只走增量分支（新技能名能发，但「整份重发」语义缺失，实测偏差见审计 P2-22）。
 *
 * <p>本类钉死两个不可分割的语义面：
 * <ol>
 *   <li>{@code clearAllCaches()} 后各槽 sent <b>确实被清空</b>（下个 pass 整份重发）——
 *       删掉 {@code clearAllCaches} 末尾的 {@code SkillListingSentRegistry.resetSentAllSessions()} 即 RED；</li>
 *   <li>{@code INITIALIZED} <b>保留</b>（= CC {@code suppressNext === false}）—— 这是
 *       {@code resetSentAllSessions} 的语义本体；若一并清掉，各会话会落「resume 且未初始化 → 抑制」，
 *       反而永不重发（与 CC 行为相反）。</li>
 * </ol>
 */
@DisplayName("[P2-22] clearAllCaches 末位 resetSentSkillNames 级联")
class PluginCacheUtilsSentSkillNamesResetTest {

    @AfterEach
    void tearDown() {
        SkillListingSentRegistry.reset();
    }

    @Test
    @DisplayName("clearAllCaches → sent 清空（下个 pass 整份重发）+ INITIALIZED 保留（= CC suppressNext=false）")
    void clearAllCaches_resetsSentNamesButKeepsInitialized() {
        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> names = List.of("commit", "review");
        SkillListingSentRegistry.reset();

        // 前置：首注落 sent=全量 + initialized；稳定态（resume 且有 sent）→ 无增量、不注入
        assertThat(SkillListingSentRegistry.decide(sid, "", names, false).names())
            .as("前置：全新会话首 run → 整份注入").hasSize(2);
        assertThat(SkillListingSentRegistry.decide(sid, "", names, true).names())
            .as("前置：稳定态无增量 → 不注入").isEmpty();

        // PluginLoader / SkillRegistry 未注入 → 其余级联项 no-op；本用例只验 resetSentSkillNames 一项
        new PluginCacheUtils().clearAllCaches();

        SkillListingSentRegistry.Decision after =
            SkillListingSentRegistry.decide(sid, "", names, true);
        assertThat(after.names())
            .as("clearAllCaches 必须清空 sent → 下一 pass 因 sent 空而整份重发（CC cacheUtils.ts:49）；"
                + "删掉 PluginCacheUtils.clearAllCaches 末尾的 resetSentAllSessions() 即 RED")
            .containsExactlyElementsOf(names);
        assertThat(after.isInitial())
            .as("sent 空 → isInitial=true（CC：sent.size()===0 → isInitial，attachments.ts:2805）").isTrue();
        assertThat(SkillListingSentRegistry.isInitialized(sid, ""))
            .as("INITIALIZED 必须保留（= CC suppressNext=false）：若一并清掉，该会话落「resume 且未初始化→抑制」，永不重发")
            .isTrue();
    }
}
