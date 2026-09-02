package com.nexusai.application.agent.skill;

import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-9 getMcpSkillCommands feature gate + 分离测试 · 对齐 CC commands.ts:547-559 三语义。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>MCP 技能 live outside getCommands</b>（commands.ts:541-546）——getAllCommands / getModelInvocableCommands
 *       不得含 MCP；消费方经 thread-in（findCommandIncludingMcp / getModelInvocableCommandsForListing）合并。
 *       若 MCP 又被并入 getAllCommands（旧架构回归），用例 ① fail。</li>
 *   <li><b>listing 合并按 name 去重 local-first</b>（attachments.ts:2680-2682 {@code uniqBy([...local, ...mcp], 'name')}
 *       / SkillTool.ts:86/:93）——同名冲突本地胜，MCP 不得覆盖本地技能。用例 ②。</li>
 *   <li><b>SkillTool 搜索基座含 MCP</b>（SkillTool.ts:81-93 getAllCommands(context)）——findCommandIncludingMcp
 *       命中 MCP、findCommand（纯本地）不命中。用例 ③。</li>
 *   <li><b>feature('MCP_SKILLS') gate 关 → 返回空</b>（commands.ts:550/:558）——setMcpSkillsGate(false) 后
 *       getMcpSkillCommands 为空、thread-in 退化为纯本地。用例 ④。</li>
 * </ol>
 */
class McpSkillCommandsTest {

    /** 写一个最小 SKILL.md（frontmatter name）· 对齐 SkillsLoader.loadFromSkillMd */
    private static void writeSkill(Path root, String dir, String name) throws Exception {
        Path skillDir = root.resolve(dir);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: " + name + "\n---\n# " + name + "\n");
    }

    /** 构造真实 McpServerService + 反射注入 mcpSkillCommands（gate 走真实 getMcpSkillCommands） */
    private static McpServerService newMcpService(Command... cmds) throws Exception {
        McpServerService svc = new McpServerService();
        // P1-9: mcpSkillsGate 默认 false（对齐 CC 生产折叠）→ 本测试验证「MCP 技能在池中」的
        // 使能路径，显式开 gate（关 gate 行为由用例 ④ 单独验证）。
        svc.setMcpSkillsGate(() -> true);
        Field field = McpServerService.class.getDeclaredField("mcpSkillCommands");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Command> list = (List<Command>) field.get(svc);
        list.addAll(List.of(cmds));
        return svc;
    }

    /** 构造真实 McpServerService + 反射注入 mcpSkillCommands + mcpPromptCommands（拍板#2 双池搜索） */
    private static McpServerService newMcpServiceWithPrompts(List<Command> skills, List<Command> prompts) throws Exception {
        McpServerService svc = newMcpService(skills.toArray(Command[]::new));
        Field field = McpServerService.class.getDeclaredField("mcpPromptCommands");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Command> list = (List<Command>) field.get(svc);
        list.addAll(prompts);
        return svc;
    }

    private static Command mcpSkill(String name) {
        Command c = new Command();
        c.setName(name);
        c.setType("prompt");
        c.setSource(CommandSource.MCP);                 // CC client.ts:2072 source: 'mcp'
        c.setLoadedFrom(CommandLoadedFrom.MCP);         // CC skill:// 资源技能 loadedFrom: 'mcp'（P2-21 独立字段）
        c.setDisableModelInvocation(Boolean.FALSE);
        return c;
    }

    private static Command mcpSkillDisabled(String name) {
        Command c = mcpSkill(name);
        c.setDisableModelInvocation(Boolean.TRUE);
        return c;
    }

    /** 非 prompt 类型 MCP 命令（CC filter #1 type==='prompt' 排除） */
    private static Command mcpBashSkill(String name) {
        Command c = new Command();
        c.setName(name);
        c.setType("bash");
        c.setSource(CommandSource.MCP);
        c.setDisableModelInvocation(Boolean.FALSE);
        return c;
    }

    /** 拍板#2: MCP prompt 命令（fetchCommands 产物，source='mcp' 无 loadedFrom，client.ts:2057-2072） */
    private static Command mcpPrompt(String name) {
        Command c = new Command();
        c.setName(name);
        c.setType("prompt");
        c.setSource(CommandSource.MCP);          // CC client.ts:2072 source: 'mcp'
        c.setIsMcp(Boolean.TRUE);                // CC client.ts:2064 isMcp: true（wirePromptFunctions 落位）
        c.setDisableModelInvocation(Boolean.FALSE);
        // 不设 loadedFrom —— 普通 MCP prompt 非 skill（CC commands.ts:551-556 会排除，
        // 但拍板#2 要求 findCommandIncludingMcp 搜索基座含 MCP prompt）
        return c;
    }

    // ── ① 分离：getAllCommands / getModelInvocableCommands 不含 MCP ──

    @Test
    @DisplayName("分离：getAllCommands/getModelInvocableCommands 不含 MCP（CC commands.ts:541-546）")
    void localCommandsExcludeMcp(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setMcpServerService(newMcpService(mcpSkill("mcp__s__summarize")));

        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .contains("skill-a").doesNotContain("mcp__s__summarize");
        assertThat(registry.getModelInvocableCommands()).extracting(Command::getName)
            .contains("skill-a").doesNotContain("mcp__s__summarize");
    }

    // ── ② listing 合并：含 MCP + 按 name 去重 local-first ──

    @Test
    @DisplayName("listing 视图含 MCP，按 name 去重 local-first（CC attachments.ts:2680-2682）")
    void listingIncludesMcpAndDedupLocalFirst(@TempDir Path tempDir) throws Exception {
        // 本地 USER 技能 "dup"（allowlist 免显式描述自动放行）+ MCP 同名技能 "dup" + 仅 MCP 技能
        writeSkill(tempDir, "dup", "dup");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setMcpServerService(newMcpService(mcpSkill("dup"), mcpSkill("mcp__s__summarize")));

        List<Command> listing = registry.getModelInvocableCommandsForListing();
        // 含 MCP 技能（thread-in）
        assertThat(listing).extracting(Command::getName).contains("mcp__s__summarize");
        // 同名冲突去重：local-first → "dup" 实例是本地 USER 源（MCP 不得覆盖本地）
        Command dup = listing.stream().filter(c -> c.getName().equals("dup")).findFirst().orElseThrow();
        assertThat(dup.getSource()).isEqualTo(CommandSource.USER);
        assertThat(listing.stream().filter(c -> c.getName().equals("dup")).count()).isEqualTo(1);
        // 分离后本地视图无 "dup" 之外的 MCP（mcp__s__summarize 只经 listing 进入）
        assertThat(registry.getModelInvocableCommands()).extracting(Command::getName)
            .contains("dup").doesNotContain("mcp__s__summarize");
    }

    // ── ③ SkillTool 搜索基座：findCommandIncludingMcp 命中 MCP，findCommand 不命中 ──

    @Test
    @DisplayName("findCommandIncludingMcp 命中 MCP；findCommand（纯本地）不命中（CC SkillTool.ts:81-93）")
    void findCommandIncludingMcp_hitsMcp_findCommandMisses(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setMcpServerService(newMcpService(mcpSkill("mcp__s__summarize")));

        // 本地技能两路都命中
        assertThat(registry.findCommand("skill-a")).isNotNull();
        assertThat(registry.findCommandIncludingMcp("skill-a")).isNotNull();
        // MCP 技能仅 findCommandIncludingMcp 命中（thread-in 搜索基座 = CC getAllCommands(context)）
        assertThat(registry.findCommand("mcp__s__summarize")).isNull();
        Command mcp = registry.findCommandIncludingMcp("mcp__s__summarize");
        assertThat(mcp).isNotNull();
        assertThat(mcp.getSource()).isEqualTo(CommandSource.MCP);
        // 前导 '/' 归一化同样适用
        assertThat(registry.findCommandIncludingMcp("/mcp__s__summarize")).isNotNull();
    }

    // ── ③b S3（R2B-DEC-9）：搜索基座含 disableModelInvocation MCP 技能（errorCode 4 后置）──

    @Test
    @DisplayName("S3: 搜索基座命中 disableModelInvocation MCP 技能（CC SkillTool.ts:81-94 不过滤）→ errorCode 4 可达；listing 仍过滤")
    void findCommandIncludingMcp_hitsDisabledMcpSkill(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setMcpServerService(newMcpService(mcpSkillDisabled("mcp__s__off")));

        // 搜索基座 = CC SkillTool.getAllCommands（SkillTool.ts:89，仅 type/loadedFrom 过滤）
        // → disableModelInvocation 技能保持可达，validateInput 命中后走 errorCode 4（:412-418）
        Command hit = registry.findCommandIncludingMcp("mcp__s__off");
        assertThat(hit).isNotNull();
        assertThat(hit.getDisableModelInvocation()).isTrue();
        assertThat(hit.getSource()).isEqualTo(CommandSource.MCP);

        // listing 视图仍过滤 disableModelInvocation（CC commands.ts:553-555 getMcpSkillCommands）
        assertThat(registry.getModelInvocableCommandsForListing())
            .extracting(Command::getName).doesNotContain("mcp__s__off");
    }

    // ── ③c 拍板#2：MCP prompt 命令（fetchCommands 产物，无 loadedFrom）进搜索基座 ──

    @Test
    @DisplayName("拍板#2: findCommandIncludingMcp 搜索基座含 MCP prompt（无 loadedFrom，fetchCommands 产物）")
    void findCommandIncludingMcp_hitsMcpPrompt_NoLoadedFrom(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        // 同时注入技能池（loadedFrom=MCP）+ prompt 池（fetchCommands 产物，无 loadedFrom）
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setMcpServerService(newMcpServiceWithPrompts(
            List.of(mcpSkill("mcp__s__skill")),
            List.of(mcpPrompt("mcp__s__summarize"))));

        // 纯本地 findCommand 不命中任何 MCP（分离语义，P2-9）
        assertThat(registry.findCommand("mcp__s__summarize")).isNull();
        assertThat(registry.findCommand("mcp__s__skill")).isNull();
        // findCommandIncludingMcp 同时命中 MCP skill（loadedFrom=MCP）与 MCP prompt（无 loadedFrom）
        Command prompt = registry.findCommandIncludingMcp("mcp__s__summarize");
        assertThat(prompt).isNotNull();
        assertThat(prompt.getSource()).isEqualTo(CommandSource.MCP);
        assertThat(prompt.getLoadedFrom()).isNull();           // 无 loadedFrom = fetchCommands 产物
        assertThat(prompt.getIsMcp()).isTrue();
        assertThat(registry.findCommandIncludingMcp("mcp__s__skill")).isNotNull();
        // 前导 '/' 归一化同样适用
        assertThat(registry.findCommandIncludingMcp("/mcp__s__summarize")).isNotNull();
    }

    @Test
    @DisplayName("拍板#2: getMcpPromptCommandsForSearch 过滤 type=prompt（MCP prompt 搜索视图）")
    void getMcpPromptCommandsForSearch_filtersPromptType() throws Exception {
        McpServerService svc = newMcpServiceWithPrompts(
            List.of(), // 技能池空
            List.of(mcpPrompt("mcp__s__summarize"), mcpBashSkill("mcp__s__bash")));

        // prompt 池搜索视图只保留 type=prompt（fetchCommands 产物恒 prompt 型）
        assertThat(svc.getMcpPromptCommandsForSearch())
            .extracting(Command::getName).containsExactly("mcp__s__summarize");
    }

    @Test
    @DisplayName("拍板#2: MCP prompt 不进 listing 视图（getMcpSkillCommands 仅 loadedFrom=mcp，commands.ts:551-556）")
    void listing_excludesMcpPrompt_NoLoadedFrom(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setMcpServerService(newMcpServiceWithPrompts(
            List.of(mcpSkill("mcp__s__skill")),
            List.of(mcpPrompt("mcp__s__summarize"))));

        // listing = 本地 + getMcpSkillCommands（仅 loadedFrom=MCP）→ prompt 不在其中
        assertThat(registry.getModelInvocableCommandsForListing())
            .extracting(Command::getName)
            .contains("skill-a", "mcp__s__skill")
            .doesNotContain("mcp__s__summarize");
    }

    // ── ④ feature('MCP_SKILLS') gate：关 → 空 + thread-in 退化为本地 ──

    @Test
    @DisplayName("getMcpSkillCommands 过滤 + gate 关返回空（CC commands.ts:550/:558）")
    void getMcpSkillCommands_gateOff_returnsEmpty() throws Exception {
        // 默认 gate true：纯过滤（type=prompt && loadedFrom=mcp && !disableModelInvocation）
        McpServerService svc = newMcpService(
            mcpSkill("mcp__s__on"),
            mcpSkillDisabled("mcp__s__off"),
            mcpBashSkill("mcp__s__bash"));
        assertThat(svc.getMcpSkillCommands()).extracting(Command::getName).containsExactly("mcp__s__on");

        // gate 关 → return []（CC :558）
        svc.setMcpSkillsGate(() -> false);
        assertThat(svc.getMcpSkillCommands()).isEmpty();
    }

    @Test
    @DisplayName("gate 关 → findCommandIncludingMcp 退化为纯本地（MCP 技能不可达）")
    void gateOff_threadInDegradesToLocal(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        McpServerService mcp = newMcpService(mcpSkill("mcp__s__summarize"));
        mcp.setMcpSkillsGate(() -> false);
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setMcpServerService(mcp);

        // MCP 技能经 thread-in 不可达（gate 关 → getMcpSkillCommands 空 → 合并退化为本地）
        assertThat(registry.findCommandIncludingMcp("mcp__s__summarize")).isNull();
        assertThat(registry.getModelInvocableCommandsForListing())
            .extracting(Command::getName)
            .contains("skill-a").doesNotContain("mcp__s__summarize");
        // 本地技能不受 gate 影响
        assertThat(registry.findCommandIncludingMcp("skill-a")).isNotNull();
    }
}
