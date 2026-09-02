package com.nexusai.application.agent.skill;

import com.nexusai.infra.util.StringWidth;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-21 SkillCatalog stringWidth 宽度感知测试 · 对齐 CC prompt.ts:85/:107/:118 stringWidth
 * + utils/truncate.ts 宽度感知截断（CJK 全宽计 2 终端列）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>CJK 字符预算必须按终端列宽计</b>——1 个 CJK 字符占 2 终端列（eastAsianWidth W），
 *       CC formatCommandsWithinBudget 用 stringWidth 计 fullTotal/bundledChars/restNameOverhead；
 *       旧 Java {@code String::length} 把 CJK 计 1，预算被低估 → 中文技能清单在预算内被过度放行
 *       或截断位置偏后。用例 (a)(b) 是 RED 于旧架构的关键失败证据。</li>
 *   <li><b>截断必须宽度感知且不劈代理对</b>——CC truncateToWidth（truncate.ts:63-75）按终端列宽
 *       截断 + '…' 后缀；Java 旧 {@code String::substring} 会劈代理对（emoji）且按字符数而非宽度。
 *       用例 (c)。</li>
 * </ol>
 */
class SkillCatalogStringWidthTest {

    /** 构造一条命令（source/loadedFrom 不影响 formatListing 预算计算，仅 bundled 分区用 source） */
    private static Command cmd(String name, String desc) {
        Command c = new Command();
        c.setName(name);
        c.setDescription(desc);
        c.setSource(CommandSource.USER);
        c.setLoadedFrom(CommandLoadedFrom.SKILLS);
        return c;
    }

    @Test
    @DisplayName("stringWidth('中文')=4 而 length=2（CJK 全宽计 2 · RED 于旧 length 计宽）")
    void stringWidth_cjk_counts2PerChar() {
        assertThat("中文".length()).isEqualTo(2);
        assertThat(StringWidth.stringWidth("中文")).isEqualTo(4);
        assertThat(StringWidth.stringWidth("abc")).isEqualTo(3);
        assertThat(StringWidth.stringWidth(null)).isZero();
        assertThat(StringWidth.stringWidth("")).isZero();
    }

    @Test
    @DisplayName("fullTotal 按终端列宽计：预算 15 时 CJK 条目超预算被截断（RED 于旧 length=12 预算算小放行全量）")
    void fullTotal_usesWidth_redUnderLengthBudget(@TempDir Path tempDir) {
        Command cjk = cmd("中文技能", "中文描述");
        // formatEntry = "- 中文技能: 中文描述"
        //   宽度 = 2("- ") + 8("中文技能" 4字×2) + 2(": ") + 8("中文描述") = 20
        //   长度 = 2 + 4 + 2 + 4 = 12
        // 预算 15：旧实现 fullTotal=12 ≤ 15 → 全量放行（"中文描述" 在输出）；新实现 20 > 15 → 截断
        SkillCatalog catalog = new SkillCatalog(new SkillRegistry(tempDir.toString()));
        String listing = catalog.formatListing(List.of(cjk), 15);
        assertThat(listing).doesNotContain("中文描述");
        assertThat(listing).contains("中文技能");
    }

    @Test
    @DisplayName("restNameOverhead 按名称终端列宽计：CJK 技能名截断后总宽度不超预算（RED 于旧 length 计宽）")
    void restNameOverhead_usesWidth(@TempDir Path tempDir) {
        // 超长 CJK 描述 + CJK 名称：预算 30，restNameOverhead = width(name)+4 = 8+4 = 12
        //   旧实现 length(name)+4 = 4+4 = 8 → availableForDescs 偏大 → 截断结果宽度可能超预算
        Command cjk = cmd("中文技能", "中".repeat(60));   // 名称宽 8、描述宽 120
        SkillCatalog catalog = new SkillCatalog(new SkillRegistry(tempDir.toString()));
        String listing = catalog.formatListing(List.of(cjk), 30);
        // 截断后整条 listing 终端宽度 ≤ 预算（不超 30）——旧 length 计宽下 availableForDescs 大、
        // maxDescLen 偏大 → 结果宽度可超 30
        assertThat(StringWidth.stringWidth(listing)).isLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("truncate 宽度感知：CJK 结果宽度 ≤ maxLen-1 + '…'（RED 于旧 substring 按字符截）")
    void truncate_widthAware_cjk() {
        String t = SkillCatalog.truncate("长".repeat(10), 12);   // 宽 20 → 需截断
        assertThat(t).endsWith("…");
        assertThat(StringWidth.stringWidth(t)).isLessThanOrEqualTo(12);
        // 不劈 CJK 字：宽 ≤ 11 截断 + '…'，不可能出现半个「长」
        assertThat(t.length()).isLessThan(10);
    }

    @Test
    @DisplayName("truncate 不劈代理对：emoji（代理对）截断后完整保留（RED 于旧 substring 劈代理对）")
    void truncate_doesNotSplitSurrogatePair() {
        // "A😀B"：A=1 + 😀=2 + B=1 = 宽 4 > 3 → 截断；maxWidth-1=2 → A(1) 后 😀(2) 超 → "A…"
        String t = SkillCatalog.truncate("A😀B", 3);
        assertThat(t).isEqualTo("A…");
        // 代理对完整：结果不含高/低代理项单独出现（substring 截断会劈开 \uD83D / \uDE00）
        assertThat(t).doesNotContain("\uD83D");
        assertThat(t).doesNotContain("\uDE00");
    }

    @Test
    @DisplayName("truncate 未超宽返回原串 / null → 空串 / maxLen≤1 → '…'")
    void truncate_edgeCases() {
        assertThat(SkillCatalog.truncate("abc", 10)).isEqualTo("abc");
        assertThat(SkillCatalog.truncate(null, 10)).isEmpty();
        assertThat(SkillCatalog.truncate("abc", 1)).isEqualTo("…");
        assertThat(SkillCatalog.truncate("中文", 2)).isEqualTo("…");
    }
}
