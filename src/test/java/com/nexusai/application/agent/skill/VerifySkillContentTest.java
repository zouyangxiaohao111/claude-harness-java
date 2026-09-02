package com.nexusai.application.agent.skill;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ALIGN-BD-4（BD-29）verifyContent 内容对齐测试（RED→GREEN）· 对齐 CC verifyContent.ts。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>键结构必须对齐 CC 字面量</b>——CC verifyContent.ts:10-12 {@code SKILL_FILES =
 *       {'examples/cli.md', 'examples/server.md'}}（2 键, 顺序固定）。若有人改键名/增删键, 此断言必红
 *       （verify.ts:21 {@code files: SKILL_FILES} 经 P1-3 解压端到端, 键漂移破坏解压路径）。</li>
 *   <li><b>正文不得伪造</b>——CC 3 个 .md 源文件（SKILL.md/cli.md/server.md）在本 checkout 缺失
 *       （DCE 剔除, git history 亦无）。DEC-15 铁律「源缺失不伪造」: 正文必须为显式 N/A marker,
 *       旧实现伪造 "# CLI verify example\nRun `npm test`..." 简化占位, 非 CC 真实内容, 属伪造。
 *       若有人回退成伪造占位（含 "npm test" 等非 N/A 内容）, 此断言必红。</li>
 *   <li><b>SKILL_MD 不得伪造 frontmatter</b>——CC verify.ts:5 {@code parseFrontmatter(SKILL_MD)}
 *       取 description + SKILL_BODY; 真实 SKILL.md frontmatter 未知, 故 SKILL_MD 为 N/A marker
 *       （无伪造 frontmatter）, 剥离后不得残留 "description:" 伪字段。</li>
 * </ol>
 */
class VerifySkillContentTest {

    @Test
    @DisplayName("SKILL_FILES 键结构对齐 verifyContent.ts:10-12：2 键顺序固定（CC Record 字面量）")
    void skillFilesKeyStructureMatchesCcLiteral() {
        assertThat(VerifySkillContent.SKILL_FILES.keySet())
            .as("CC verifyContent.ts:11-12 字面量顺序 examples/cli.md → examples/server.md")
            .containsExactly("examples/cli.md", "examples/server.md");
    }

    @Test
    @DisplayName("SKILL_FILES 正文为显式 N/A marker（不伪造，DEC-15）")
    void skillFilesContentIsExplicitNaNotFabricated() {
        for (Map.Entry<String, String> e : VerifySkillContent.SKILL_FILES.entrySet()) {
            assertThat(e.getValue())
                .as("正文必须为显式 N/A marker（CC .md 源缺失，DEC-15 不伪造），键=%s", e.getKey())
                .contains("N/A")
                .contains("源文件缺失")
                .doesNotContain("npm test")
                .doesNotContain("curl /health");
        }
    }

    @Test
    @DisplayName("SKILL_MD 为显式 N/A marker（无伪造 frontmatter，不伪造正文）")
    void skillMdIsExplicitNaNotFabricated() {
        assertThat(VerifySkillContent.SKILL_MD)
            .as("SKILL_MD 必须为显式 N/A marker（CC verify/SKILL.md 源缺失，DEC-15 不伪造）")
            .isNotBlank()
            .contains("N/A")
            .contains("源文件缺失");
        // CC verify.ts:5 parseFrontmatter(SKILL_MD) → SKILL_BODY 不得残留伪造 frontmatter 字段
        String body = new ParseSkillFrontmatter().extractBody(VerifySkillContent.SKILL_MD);
        assertThat(body)
            .as("SKILL_MD 无伪造 frontmatter，剥离后不得残留 'description:'")
            .doesNotContain("description:");
    }
}
