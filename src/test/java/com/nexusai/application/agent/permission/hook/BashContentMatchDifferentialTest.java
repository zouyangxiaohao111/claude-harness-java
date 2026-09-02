package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.BashTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-11 OD-09] Bash if 内容匹配差分测试矩阵 · CC tree-sitter vs Java
 * {@link com.nexusai.application.agent.bash.BashParser#splitCommands}.
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: hook if 过滤是「不匹配 → 跳过 hook」的 deny 语义
 * （BashTool.tsx:448-450 注释原文），<b>误过滤有安全含义</b>（CC 会触发、Java 跳过 →
 * 安全 hook 不执行）。OD-09 ADJUDICATED 为测试任务：5 组复合命令用例对照 CC tree-sitter
 * 实际语义（不信注释，行号自验），含正例 + 反例。
 *
 * <p><b>CC 真源 oracle</b>（本测试断言基准）：
 * <ol>
 *   <li>{@code BashTool.preparePermissionMatcher}（BashTool.tsx:445-468）：parseForSecurity
 *       → kind != 'simple' → {@code () => true}（fail-safe 运行 hook）；否则
 *       {@code subcommands = parsed.commands.map(c => c.argv.join(' '))}，任一子命令
 *       {@code cmd === prefix || cmd.startsWith(prefix + ' ')}（{:*} 前缀）或
 *       {@code matchWildcardPattern(pattern, cmd)}（锚定全匹配 + 尾随 {@code " *"} 可选）。
 *       注意 argv 已剥离 envVars（tree-sitter env_vars 分离）且不含 heredoc body。</li>
 *   <li>{@code parseForSecurityFromAst}（ast.ts:400-460）：控制字符 / Unicode 空白 /
 *       反斜杠空白 / zsh 扩展 / 引号内 brace 混淆 → too-complex；{@code walkHeredocRedirect}
 *       （ast.ts:1143-1193）：<b>非引号定界 heredoc（{@code <<EOF}）→ too-complex</b>
 *       （body 会做 shell 展开）；定界符同行后跟 {@code &&}/pipeline（ast.ts:1158-1168）→
 *       too-complex（fail closed）；{@code $(cmd)} 命令替换<b>仅双引号内（walkString
 *       ast.ts:1561-1578）/ {@code VAR=} 赋值（walkVariableAssignment ast.ts:1796-1804）</b>
 *       提取为独立 subcommand 且外层 argv 以 {@code __CMDSUB_OUTPUT__} 占位（ast.ts:70-74）；
 *       <b>裸参数位 {@code $(cmd)} → walkCommand/walkArgument default tooComplex</b>
 *       （ast.ts:1282-1290/1481-1490 注释原文）→ fail-safe（FIX-EX-A 实证：原 DIV-4
 *       断言「CC 跳过」与源码矛盾，按源码翻转）。</li>
 * </ol>
 * <b>Java 被测路径</b>（生产代码，非复制实现）：
 * {@code HookMatcherEngine.getMatchingHooks} → {@code filterByIfCondition} →
 * {@code prepareContentMatcher}（经 ToolRegistry 解析 BashTool）→
 * {@code BashTool.preparePermissionMatcher}（[G3] 接口扩展点）——if 条件 {@code Bash(...)}
 * 解析 + {@code BashParser.splitCommands} 子命令拆分 + 前缀/通配匹配。
 *
 * <p><b>分歧处置</b>: 用例断言 CC <b>实际源码</b>语义（不信注释/任务书假设，AGENTS 经验九）。
 * FIX-EX-A 已按 CC 语义修复 DIV-1..4（BashParser.splitForSecurity heredoc 段 + 命令替换
 * 内层提取），原 {@code knownDivergences} 组已翻转回 CC 语义断言；09-open-decisions.md
 * 的 DIV 登记待关闭。
 *
 * @since IMPL-11 (P2 测试补强)
 */
@DisplayName("[IMPL-11 OD-09] Bash if 内容匹配差分矩阵（CC tree-sitter vs BashParser.splitCommands）")
class BashContentMatchDifferentialTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 用例: if 条件 / 命令 / CC tree-sitter 期望（true=运行 hook）. */
    private record Case(String name, String ifCondition, String command, boolean ccFires) {}

    // ════════════════════════════════════════════════════════════════════════
    // 1. && 分隔（CC and_series 两命令均入 argv 列表）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G1 && 分隔: 任一子命令命中即触发（ls && git push 防绕过 Bash(git *)）")
    void group1_andSeparator() throws Exception {
        // 正例: CC 子命令 ['ls','git push'] → git 命中 → hook 运行
        assertThat(javaFires("Bash(git *)", "ls && git push")).as("ls && git push 须命中 git *").isTrue();
        // 正例: 前段命中
        assertThat(javaFires("Bash(ls *)", "ls && git push")).as("ls && git push 须命中 ls *").isTrue();
        // 反例: 无 git 子命令 → 跳过（防误过滤）
        assertThat(javaFires("Bash(git *)", "ls && rm -rf /")).as("ls && rm -rf / 不得命中 git *").isFalse();
        // 反例: 精确匹配不中
        assertThat(javaFires("Bash(git status)", "ls && git push")).as("git status 精确模式不得命中 git push").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. VAR=x 前缀（CC tree-sitter envVars 分离，argv 不含 VAR=val）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G2 VAR=x 前缀: 前置赋值剥离后匹配（FOO=bar git push 命中 git *）")
    void group2_envVarPrefix() throws Exception {
        // 正例: 单前置赋值
        assertThat(javaFires("Bash(git *)", "FOO=bar git push")).as("FOO=bar git push 须命中 git *").isTrue();
        // 正例: 多前置赋值
        assertThat(javaFires("Bash(git *)", "A=1 B=2 git push")).as("A=1 B=2 git push 须命中 git *").isTrue();
        // 反例: 赋值后命令非 git
        assertThat(javaFires("Bash(git *)", "FOO=bar echo hi")).as("FOO=bar echo hi 不得命中 git *").isFalse();
        // 反例: 赋值是参数不是 env 前缀（echo FOO=bar 整串是 argv）
        assertThat(javaFires("Bash(FOO=*)", "echo FOO=bar")).as("echo FOO=bar 的 FOO=bar 是参数, 不得命中 FOO=*").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. heredoc（CC: body 不在 argv；非引号定界 / 定界后同行结构 → too-complex fail-safe）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G3 heredoc: 引号定界 body 不进 argv；命令本体照常匹配")
    void group3_heredoc_quotedDelimiter() throws Exception {
        // 正例: cat 命令本体匹配（heredoc 标记附于命令后）
        assertThat(javaFires("Bash(cat *)", "cat <<'EOF'\nhello\nEOF"))
            .as("cat <<'EOF' 须命中 cat *").isTrue();
        // 反例: body 内容不是 argv（git checkout main 在 body 里 → 不得命中 git *）
        assertThat(javaFires("Bash(git *)", "cat <<'EOF'\nhello\nEOF"))
            .as("body 内容不得命中 git *").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. 命令替换 $( )（CC: 内层命令提取为独立 subcommand；外层 argv 占位）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G4 命令替换: 裸 $(...) 参数位 fail-safe 运行；单引号字面不替换")
    void group4_commandSubstitution() throws Exception {
        // 正例: 裸 $(...) 参数位 → CC walkCommand default tooComplex → fail-safe 运行
        //   （ast.ts:1282-1290；非「内层提取」——提取仅限双引号/赋值内, 见 div1to4_ccAligned）
        assertThat(javaFires("Bash(git *)", "echo $(git status)")).as("$(git status) 裸参数位 fail-safe 须运行 hook").isTrue();
        // 正例: 外层 echo 命中（fail-safe 无条件运行）
        assertThat(javaFires("Bash(echo *)", "echo $(git status)")).as("外层 echo 须命中 echo *").isTrue();
        // 反例: 单引号内 $(...) 是字面文本, 无命令替换
        assertThat(javaFires("Bash(git *)", "echo '$(git status)'")).as("单引号字面 $(git status) 不得命中 git *").isFalse();
        // 正例: 内层 + 外层均命中（echo $(git status) && git push）
        assertThat(javaFires("Bash(git *)", "echo $(git status) && git push"))
            .as("$(git status) 与 git push 均含 git → 命中").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. glob 转义（CC matchWildcardPattern: \* 字面 / * 通配 / 锚定全匹配 / 尾随可选）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G5 glob 转义: \\* 字面星 vs * 通配（锚定全匹配 + 尾随 * 可选）")
    void group5_globEscaping() throws Exception {
        // 正例: 通配 * 命中
        assertThat(javaFires("Bash(git *)", "git status")).as("git status 须命中 git *").isTrue();
        // 正例: 尾随 * 可选 → 裸 git 命中
        assertThat(javaFires("Bash(git *)", "git")).as("裸 git 须命中 git *（尾随参数可选）").isTrue();
        // 正例: 命令含字面 *（argv join 后仍是 git *）→ 通配命中
        assertThat(javaFires("Bash(git *)", "git *")).as("git * 字面星命令须命中 git *").isTrue();
        // 反例: \* 字面星 ≠ status
        assertThat(javaFires("Bash(git \\*)", "git status")).as("\\* 字面星不得命中 git status").isFalse();
        // 正例: \* 字面星命中命令里的字面星
        assertThat(javaFires("Bash(git \\*)", "git *")).as("\\* 字面星须命中 git *").isTrue();
        // 反例: 转义文件模式不命中（file\\*.txt 字面星 ≠ file1.txt）
        assertThat(javaFires("Bash(file\\*.txt)", "file1.txt")).as("file\\*.txt 字面星不得命中 file1.txt").isFalse();
        // 反例: 锚定全匹配（*.txt 不命中 report.txt.bak）
        assertThat(javaFires("Bash(*.txt)", "report.txt.bak")).as("*.txt 锚定不得命中 report.txt.bak").isFalse();
        // 正例: 锚定全匹配命中
        assertThat(javaFires("Bash(*.txt)", "report.txt")).as("*.txt 须命中 report.txt").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // DIV-1..4 对齐 CC 组（FIX-EX-A: 按 CC 实际源码语义翻转, 09 登记待关闭）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DIV-1..4 对齐 CC: heredoc body 不匹配 / 非引号定界 fail-safe / 定界同行结构 fail-safe / 命令替换分类")
    void div1to4_ccAligned() throws Exception {
        // [DIV-1] 引号定界 heredoc body 不进 argv:
        //   CC（oracle=false）：argv=['cat']，body 是 stdin 字面量（BashTool.tsx:458 argv.join）
        //   Java 修复: splitForSecurity heredoc 段吞至终结符行, body 行不作独立子命令
        assertThat(javaFires("Bash(git *)", "cat <<'EOF'\ngit checkout main\nEOF"))
            .as("[DIV-1] 引号定界 heredoc body 不进 argv → 不得命中 git *").isFalse();
        // DIV-1 正例: 命令本体照常匹配
        assertThat(javaFires("Bash(cat *)", "cat <<'EOF'\ngit checkout main\nEOF"))
            .as("[DIV-1+] heredoc 命令本体 cat 仍命中 cat *").isTrue();

        // [DIV-2] 非引号定界 <<EOF:
        //   CC（oracle=true）：walkHeredocRedirect 非引号定界 → too-complex → fail-safe 运行
        //     （ast.ts:1176-1182；body 会做 shell 展开）
        //   Java 修复: 非引号定界 → failSafe → 运行 hook
        assertThat(javaFires("Bash(git *)", "cat <<EOF\nhello\nEOF"))
            .as("[DIV-2] 非引号定界 heredoc → fail-safe 运行 hook").isTrue();

        // [DIV-3] 定界符同行后跟结构:
        //   CC（oracle=true）：heredoc_redirect 子节点含 && → too-complex fail-closed → fail-safe
        //     （ast.ts:1158-1168 SECURITY：`ls <<'EOF' | rm x` 同类）
        //   Java 修复: 定界符同行余下非空白内容 → failSafe → 运行 hook
        assertThat(javaFires("Bash(git *)", "cat <<'EOF' && git push\nbody\nEOF"))
            .as("[DIV-3] 定界符同行 && → fail-safe 运行 hook").isTrue();
        assertThat(javaFires("Bash(rm *)", "cat <<'EOF' | rm x\nbody\nEOF"))
            .as("[DIV-3+] 定界符同行 | → fail-safe 运行 hook").isTrue();

        // [DIV-4] 命令替换按 CC 实际源码（不信注释/任务书假设）:
        //   裸 $(...) 参数位 → CC walkCommand/walkArgument 无 command_substitution case →
        //     default tooComplex（ast.ts:1282-1290/1481-1490 注释原文）→ fail-safe 运行
        //   （原 DIV-4 断言「CC 跳过」与源码矛盾 → 按源码翻转; 任务书/09 登记需同步修正）
        assertThat(javaFires("Bash(ls *)", "echo $(git status)"))
            .as("[DIV-4] 裸 $(git status) 参数位 → CC too-complex → fail-safe 运行").isTrue();
        //   双引号内 $() → 内层提取为独立 subcommand（CC walkString ast.ts:1561-1578）→
        //     ls * 不中 → 跳过（真 over-fire 分歧点: Java 旧逻辑无条件 fail-safe）
        assertThat(javaFires("Bash(ls *)", "echo \"sha: $(git status)\""))
            .as("[DIV-4+] 双引号内 $() 内层 git status 不中 ls * → 跳过").isFalse();
        assertThat(javaFires("Bash(git *)", "echo \"sha: $(git status)\""))
            .as("[DIV-4+] 双引号内 $() 内层 git status 命中 git * → 运行").isTrue();
        //   VAR=$(...) 赋值 → 内层提取（CC walkVariableAssignment ast.ts:1796-1804）
        assertThat(javaFires("Bash(ls *)", "VAR=$(git status)"))
            .as("[DIV-4+] VAR=$(git status) 内层不中 ls * → 跳过").isFalse();
        assertThat(javaFires("Bash(git *)", "VAR=$(git status) && git push"))
            .as("[DIV-4+] VAR=$() 内层 git status + 外层 git push → 运行").isTrue();
        //   引号定界 heredoc body 内 $(...) 是字面量（CC: body 不解析）→ 不触发 fail-safe
        assertThat(javaFires("Bash(ls *)", "cat <<'EOF'\n$(git status)\nEOF"))
            .as("[DIV-4+] heredoc body 内 $() 是字面量 → 不中 ls *").isFalse();
        //   非引号定界 heredoc body 内 $(...) 会展开 → fail-safe（DIV-2 同源）
        assertThat(javaFires("Bash(git *)", "cat <<EOF\n$(git status)\nEOF"))
            .as("[DIV-4+] 非引号定界 heredoc body 会展开 → fail-safe 运行").isTrue();
        // [REF-A] 双引号内 solo 占位符（段内只有 $() 无字面内容）→ too-complex → fail-safe
        //   （CC walkString ast.ts:1633-1637 sawDynamicPlaceholder && !sawLiteralContent →
        //   tooComplex；占位符单独成 argv 会绕过下游路径校验）
        assertThat(javaFires("Bash(ls *)", "echo \"$(git status)\""))
            .as("[REF-A] 双引号 solo $() → too-complex → fail-safe 运行").isTrue();
        //   有字面内容 → 内层提取逐条匹配（ls * 不中 → 跳过）
        assertThat(javaFires("Bash(ls *)", "echo \"sha: $(git status)\""))
            .as("[REF-A+] 双引号带字面前缀 → 内层提取不中 ls * → 跳过").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════════════════════════════════

    /** Java 生产路径: if 条件过滤后 hook 是否保留（true=运行）. */
    private boolean javaFires(String ifCondition, String command) throws Exception {
        HookMatcherEngine engine = engineWith(ifCondition);
        ObjectNode input = mapper.createObjectNode();
        input.put("command", command);
        return !engine.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null)).isEmpty();
    }

    /** user settings 源 + Bash matcher + 带 if 条件的 command hook 引擎（不依赖 Spring）. */
    private HookMatcherEngine engineWith(String ifCondition) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo probe", ifCondition, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        // [G3] if 内容匹配迁移到接口：Bash 内容匹配由 BashTool.preparePermissionMatcher 承担
        //       （CC hooks.ts:1407-1419 工具驱动，无集中回退）→ 注入 ToolRegistry + BashTool。
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        engine.setToolRegistry(registry);
        // [H-WF2-01] safeParse 门禁（CC hooks.ts:1405-1409）：复用 ToolInputValidator 校验
        //   tool_input（缺键/类型错/可选字段非法 → matcher undefined → 过滤）。
        engine.setInputValidator(new ToolInputValidator());
        return engine;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. [H-WF2-01] safeParse 门禁差分（CC inputSchema.safeParse 失败 → matcher undefined → 跳过）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G6 safeParse 门禁差分: 畸形 tool_input (类型错/timeout 非法) → CC 过滤 → Java 对齐过滤")
    void group6_safeParseGate_invalidInput() throws Exception {
        // 意图 (规则九 · WHY): 差分测试缺口 (X-WF2-03 E6) — 本矩阵原只覆盖合法命令字符串.
        //   CC prepareIfConditionMatcher (hooks.ts:1405-1409): tool_input 未通过 inputSchema.
        //   safeParse → patternMatcher undefined → ruleContent 非空 → false (过滤). Java 旧实现
        //   无门禁, Bash 对畸形输入 asText coerce 后仍可能制备 matcher → 命中 (I-2b/d 偏移).
        //   本组用例<b>判别</b>: 每个畸形输入都挑一个"coerce 后恰能命中"的 if 模式 — 无门禁时
        //   (RED) hook 保留, 有门禁 (GREEN) safeParse 失败 → 过滤. 证明门禁真实参与过滤.

        // 正例(控制): 合法 command 字符串 → 命中 (与 G1-G5 同路径)
        assertThat(javaFires("Bash(git *)", "git status")).as("合法命令须命中 git *").isTrue();

        // I-2b 必填键类型错: command:123 → invalid_type (z.string 不 coerce).
        //   旧实现 asText coerce → "123" 命中 "Bash(123 *)" → hook 保留 (RED);
        //   新门禁拒绝数字 command → 过滤 (GREEN).
        assertThat(javaFiresRaw("Bash(123 *)", "{\"command\":123}"))
            .as("command:123 类型错 → safeParse 失败 → 过滤 (旧实现 coerce 成 '123' 会命中)").isFalse();

        // I-2d 可选字段非法: {command:"git status", timeout:"abc"} → timeout 声明 integer,
        //   "abc" 类型错 → 整个 safeParse 失败 → 过滤. 无门禁时 command "git status" 命中
        //   "Bash(git *)" → hook 保留 (RED).
        assertThat(javaFiresRaw("Bash(git *)", "{\"command\":\"git status\",\"timeout\":\"abc\"}"))
            .as("timeout:\"abc\" 可选字段非法 → safeParse 失败 → 过滤 (无门禁时 git status 会命中)").isFalse();
    }

    /** 直接以原始 JSON 文本构造 tool_input（区别于 javaFires 只放合法 command 字符串）. */
    private boolean javaFiresRaw(String ifCondition, String jsonInput) throws Exception {
        JsonNode input = mapper.readTree(jsonInput);
        HookMatcherEngine engine = engineWith(ifCondition);
        return !engine.getMatchingHooks(HookEvent.toolPre("Bash", input, "s1", null)).isEmpty();
    }
}

