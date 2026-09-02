package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-19 getCharBudget 动态预算测试 · 对齐 CC tools/SkillTool/prompt.ts:31-41
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>预算必须随上下文窗口动态缩放</b>——CC 给 skill listing 上下文窗口的 1%（× 4 chars/token），
 *       200k 窗口 → 8000 字符、1M 窗口 → 40000 字符。旧 Java {@code DEFAULT_CHAR_BUDGET=8000} 静态
 *       字面量在 1M 窗口下人为压制技能清单 = 大模型下技能发现能力减损（RED 于旧架构）。</li>
 *   <li><b>env SLASH_COMMAND_TOOL_CHAR_BUDGET 优先</b>——CC {@code Number(env)} truthy 语义：
 *       非零整数直返、0/空白/NaN 落穿下一分支。测试用 {@link SkillCatalog#parseEnvBudget}（抽离纯函数）
 *       覆盖 env 解析，避免依赖不可控的 System.getenv。</li>
 *   <li><b>活跃委托路径 formatListing(list, contextWindowTokens) 动态化</b>——CC
 *       {@code formatCommandsWithinBudget(newSkills, contextWindowTokens)} (prompt.ts:70-76)，即
 *       attachments.ts:2741 传入模型上下文窗口；小窗口走截断/names-only、大窗口走全量。</li>
 * </ol>
 */
class SkillCatalogBudgetTest {

    /** 空目录 SkillRegistry + SkillCatalog（formatListing/getCharBudget 不依赖 registry 内容） */
    private static SkillCatalog catalog(Path tempDir) {
        return new SkillCatalog(new SkillRegistry(tempDir.toString()));
    }

    /** 构造一条命令（USER 源，非 bundled） */
    private static Command userCmd(String name, String desc) {
        Command c = new Command();
        c.setName(name);
        c.setDescription(desc);
        c.setSource(CommandSource.USER);
        return c;
    }

    @Test
    @DisplayName("getCharBudget(null) 回落 DEFAULT_CHAR_BUDGET=8000（CC prompt.ts:40 缺省分支）")
    void getCharBudget_null_fallsBack8000(@TempDir Path tempDir) {
        // RED 于旧架构（无 getCharBudget 方法 → 编译失败）；GREEN：缺省上下文窗口 → 8000
        assertThat(catalog(tempDir).getCharBudget(null)).isEqualTo(8_000);
    }

    @Test
    @DisplayName("getCharBudget(200_000) = 200000×4×0.01 = 8000（CC prompt.ts:35-39 动态计算）")
    void getCharBudget_200k_equals8000(@TempDir Path tempDir) {
        // 200k 上下文窗口 = 默认静态值同量 → 语义连续（1% of 200k × 4）
        assertThat(catalog(tempDir).getCharBudget(200_000)).isEqualTo(8_000);
    }

    @Test
    @DisplayName("getCharBudget(1_000_000) = 40000（大模型窗口技能清单扩容 · RED 于旧静态 8000）")
    void getCharBudget_1M_equals40000(@TempDir Path tempDir) {
        // 1M 窗口（如 opus-4-6 [1m]）→ 40000 字符；旧静态 8000 无法表达该扩容 → 本用例 GREEN 即对齐 CC
        assertThat(catalog(tempDir).getCharBudget(1_000_000)).isEqualTo(40_000);
    }

    @Test
    @DisplayName("getCharBudget(50_000) = 2000（小窗口预算缩水）")
    void getCharBudget_50k_equals2000(@TempDir Path tempDir) {
        assertThat(catalog(tempDir).getCharBudget(50_000)).isEqualTo(2_000);
    }

    @Test
    @DisplayName("getCharBudget(≤0) 落穿 → 回落 8000（CC contextWindowTokens falsy 语义）")
    void getCharBudget_zeroOrNegative_fallsBack8000(@TempDir Path tempDir) {
        assertThat(catalog(tempDir).getCharBudget(0)).isEqualTo(8_000);
        assertThat(catalog(tempDir).getCharBudget(-1)).isEqualTo(8_000);
    }

    @Test
    @DisplayName("parseEnvBudget：非零整数/浮点/十六进制直返 / 0·空白·无效·null → null（CC Number() truthy 语义，P2-5 浮点对齐）")
    void parseEnvBudget_resolution() {
        // 整数（旧行为保持）
        assertThat(SkillCatalog.parseEnvBudget("5000")).isEqualTo(5_000);
        // P2-5 浮点对齐：CC Number("500.5")=500.5 truthy → Java 截断 int 500（EV-WF2-SA-020 △-2 修复）
        assertThat(SkillCatalog.parseEnvBudget("500.5")).isEqualTo(500);
        // 科学计数法：CC Number("1e3")=1000
        assertThat(SkillCatalog.parseEnvBudget("1e3")).isEqualTo(1_000);
        // 十六进制：CC Number("0x1F4")=500
        assertThat(SkillCatalog.parseEnvBudget("0x1F4")).isEqualTo(500);
        // 二进制 / 八进制：CC Number("0b101")=5 / Number("0o17")=15
        assertThat(SkillCatalog.parseEnvBudget("0b101")).isEqualTo(5);
        assertThat(SkillCatalog.parseEnvBudget("0o17")).isEqualTo(15);
        // 带空白 trim（CC Number() 自动 trim）
        assertThat(SkillCatalog.parseEnvBudget("  500  ")).isEqualTo(500);
        // 0 / 0x0 → falsy 落穿（CC Number()=0）
        assertThat(SkillCatalog.parseEnvBudget("0")).isNull();
        assertThat(SkillCatalog.parseEnvBudget("0x0")).isNull();
        // 无效 → null（CC Number()=NaN falsy）
        assertThat(SkillCatalog.parseEnvBudget("abc")).isNull();
        assertThat(SkillCatalog.parseEnvBudget("500abc")).isNull();
        assertThat(SkillCatalog.parseEnvBudget("0xZZ")).isNull();
        assertThat(SkillCatalog.parseEnvBudget("NaN")).isNull();
        assertThat(SkillCatalog.parseEnvBudget(null)).isNull();
        assertThat(SkillCatalog.parseEnvBudget("  ")).isNull();
        assertThat(SkillCatalog.parseEnvBudget("")).isNull();
    }

    @Test
    @DisplayName("formatListing(list, 200_000) 大窗口 → 全量完整描述（对齐 CC formatCommandsWithinBudget 全量分支）")
    void formatListing_200k_fullListing(@TempDir Path tempDir) {
        List<Command> cmds = List.of(
            userCmd("alpha", "短描述 A"),
            userCmd("beta", "短描述 B"));
        // Integer 装箱 → 命中 formatListing(List,Integer) 委托 getCharBudget(200_000)=8000；
        // 两条短命令在 8000 字符预算下必然全量放行
        assertThat(catalog(tempDir).formatListing(cmds, Integer.valueOf(200_000)))
            .isEqualTo("- alpha: 短描述 A\n- beta: 短描述 B");
    }

    @Test
    @DisplayName("formatListing(list, 50_000) 小窗口超预算 → 截断/names-only（对齐 CC prompt.ts:88-171 超预算分支）")
    void formatListing_50k_overBudget_truncated(@TempDir Path tempDir) {
        // 5 条 400 字符长描述命令：全量 ≈ 2050 > 2000（50k 窗口预算）→ 必走超预算截断
        List<Command> cmds = List.of(
            userCmd("alpha", "A".repeat(400)),
            userCmd("beta", "B".repeat(400)),
            userCmd("gamma", "C".repeat(400)),
            userCmd("delta", "D".repeat(400)),
            userCmd("epsilon", "E".repeat(400)));
        String result = catalog(tempDir).formatListing(cmds, Integer.valueOf(50_000));
        // 超预算 → 任一完整 400 字符描述都不再出现（截断/names-only 均截短）
        assertThat(result).doesNotContain("A".repeat(400));
        assertThat(result).doesNotContain("E".repeat(400));
        assertThat(result).contains("alpha").contains("epsilon");
    }

    @Test
    @DisplayName("formatListing(list, null) 缺省上下文窗口 → 回落 8000 全量（缺省分支语义）")
    void formatListing_nullContext_fullListing(@TempDir Path tempDir) {
        List<Command> cmds = List.of(
            userCmd("alpha", "短描述 A"),
            userCmd("beta", "短描述 B"));
        assertThat(catalog(tempDir).formatListing(cmds, (Integer) null))
            .isEqualTo("- alpha: 短描述 A\n- beta: 短描述 B");
    }
}
