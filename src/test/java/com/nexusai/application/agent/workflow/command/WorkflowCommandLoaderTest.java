package com.nexusai.application.agent.workflow.command;

import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkflowCommandLoader 测试 · 对齐 CC {@code getWorkflowCommands}
 * (Open-ClaudeCode/src/workflow/namedWorkflowCommands.ts:10-34)。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>扫描行为</b>（namedWorkflowCommands.ts:13-14）— 面板命令必须把用户
 *       {@code .claude/workflows} 下的每个脚本曝光为一个斜杠命令；目录缺失/为空 → 空列表
 *       而非异常（空项目不能崩掉命令池，namedWorkflows.ts:39-41）。</li>
 *   <li><b>字段映射</b>（namedWorkflowCommands.ts:15-32）— {@code source=BUILTIN} 是消费侧过滤闸
 *       （SkillRegistry {@code source != BUILTIN} 排除 workflow 命令进模型技能清单，commands.ts:570），
 *       {@code contentLength=0} 表达「命令无静态正文、内容全由 promptFn 派生」；任一字段漂移都会
 *       改变命令在命令池中的分类面。</li>
 *   <li><b>promptFn 提示语义</b>（namedWorkflowCommands.ts:23-32）— 面板命令是「提示型」，不真正执行
 *       workflow：promptFn 提示模型调用 Workflow 工具（name="&lt;name&gt;"）本体执行；args 透传是
 *       CC {@code \n\nArguments: ${args}} 契约（空串等价 CC falsy → 不追加），丢 args 则用户参数
 *       无法到达工作流。</li>
 *   <li><b>扩展名过滤</b>（namedWorkflows.ts:32-46）— 只收 {@code .ts|.js|.mjs}；README.md 等非脚本
 *       不得被误当 workflow 命令（否则命令池混入垃圾项）。</li>
 * </ol>
 */
class WorkflowCommandLoaderTest {

    @TempDir
    Path tmpDir;

    /** 建 workflow 脚本 · 内容与命令无关（list 只看扩展名 + isRegularFile）。 */
    private void writeWorkflow(String fileName) throws Exception {
        Path dir = Files.createDirectories(tmpDir.resolve(".claude/workflows"));
        Files.writeString(dir.resolve(fileName), "export default { name: 'x' }", StandardCharsets.UTF_8);
    }

    private static Command findCommand(String name, List<Command> commands) {
        return commands.stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到命令: " + name));
    }

    // ─────────────────────────── 1. 扫描行为 ───────────────────────────

    @Test
    @DisplayName("load 扫描 .claude/workflows 下 .ts/.js 各生成一个命令（字典序）")
    void load_scansWorkflowScripts_oneCommandPerScript() throws Exception {
        writeWorkflow("foo.ts");
        writeWorkflow("bar.js");

        List<Command> commands = new WorkflowCommandLoader(tmpDir.toString()).load();

        // WHY: 每个命名 workflow 脚本曝光为一个命令；名字去扩展名 + 字典序（namedWorkflows.ts:42-45）
        assertEquals(2, commands.size(), "两个脚本各生成一个命令");
        assertEquals(List.of("bar", "foo"),
                commands.stream().map(Command::getName).toList(),
                "命令名去扩展名、按字典序排列（bar < foo）");
    }

    @Test
    @DisplayName("load 空 .claude/workflows 目录 → 空列表")
    void load_emptyWorkflowDir_returnsEmptyList() throws Exception {
        Files.createDirectories(tmpDir.resolve(".claude/workflows"));

        List<Command> commands = new WorkflowCommandLoader(tmpDir.toString()).load();

        // WHY: 无脚本 = 无 workflow 命令，空列表是合法的可空命令池状态
        assertEquals(List.of(), commands, "空目录返回空列表");
    }

    @Test
    @DisplayName("load .claude/workflows 目录缺失 → 空列表不抛（对齐 namedWorkflows.ts:39-41）")
    void load_missingWorkflowDir_returnsEmptyListNoThrow() {
        // 项目根存在但从未建过 workflows 目录 → 不得抛 IO/NoSuchFile 异常
        List<Command> commands = new WorkflowCommandLoader(tmpDir.toString()).load();
        assertEquals(List.of(), commands, "目录缺失返回空列表而非异常");
    }

    // ─────────────────────────── 2. 字段映射 ───────────────────────────

    @Test
    @DisplayName("toCommand 字段映射对齐 namedWorkflowCommands.ts:15-32")
    void toCommand_fieldMapping_alignedWithCcNamedWorkflowCommands() throws Exception {
        writeWorkflow("foo.ts");

        Command c = findCommand("foo", new WorkflowCommandLoader(tmpDir.toString()).load());

        assertEquals("foo", c.getName(), ":17 name = workflow 文件名去扩展名");
        assertEquals("prompt", c.getType(), ":16 type='prompt'（PromptCommand 判别键）");
        assertEquals("Run workflow: foo", c.getDescription(), ":18 description");
        assertEquals("workflow", c.getKind(), ":19 kind='workflow'");
        assertEquals(CommandSource.BUILTIN, c.getSource(),
                ":20 source='builtin' — 消费侧 source != BUILTIN 过滤把本命令挡在模型技能清单外");
        assertEquals("Running workflow foo...", c.getProgressMessage(), ":21 progressMessage");
        assertEquals(0, c.getContentLength(),
                ":22 contentLength=0 — 无静态正文，内容全由 promptFn 派生");
    }

    // ─────────────────────────── 3. promptFn 提示文本 ───────────────────────────

    @Test
    @DisplayName("promptFn 无 args → 提示调用 Workflow 工具（name=foo）· 单个 text 块")
    void promptFn_noArgs_returnsSingleWorkflowInvocationBlock() throws Exception {
        writeWorkflow("foo.ts");

        Command c = findCommand("foo", new WorkflowCommandLoader(tmpDir.toString()).load());
        // 空串等价 CC falsy（typeof args === 'string' && args → '' 为假 → 不追加 Arguments）
        List<ContentBlockParam> blocks = c.getPromptFn().apply("",
                PromptFnContext.of(tmpDir.toString(), List.of(), "s1"));

        assertEquals(1, blocks.size(), ":31-32 返回单 text 内容块");
        assertTrue(blocks.get(0) instanceof ContentBlockParam.TextBlockParam, "块类型为 text");
        ContentBlockParam.TextBlockParam block = (ContentBlockParam.TextBlockParam) blocks.get(0);
        assertEquals("text", block.type(), "CC 内容块 { type: 'text', text: ... }");
        assertEquals("Run the \"foo\" workflow now by calling the Workflow tool with name=\"foo\".",
                block.text(), ":29 无 args 基础提示文案");
    }

    @Test
    @DisplayName("promptFn 有 args → 末尾追加 \"\\n\\nArguments: x\"（CC :30 契约）")
    void promptFn_withArgs_appendsArgumentsBlock() throws Exception {
        writeWorkflow("foo.ts");

        Command c = findCommand("foo", new WorkflowCommandLoader(tmpDir.toString()).load());
        List<ContentBlockParam> blocks = c.getPromptFn().apply("x",
                PromptFnContext.of(tmpDir.toString(), List.of(), "s1"));

        assertEquals(1, blocks.size());
        String text = ((ContentBlockParam.TextBlockParam) blocks.get(0)).text();
        // WHY: args 透传是用户参数到达工作流的唯一通道（CC `\n\nArguments: ${args}`）
        assertEquals("Run the \"foo\" workflow now by calling the Workflow tool with name=\"foo\".\n\nArguments: x",
                text, "args 原样透传、双换行分隔、无截断无折叠");
    }

    // ─────────────────────────── 4. 负向/边界 ───────────────────────────

    @Test
    @DisplayName("load 忽略非 .ts|.js|.mjs 文件（README.md 不当 workflow 命令），三种扩展名全收")
    void load_excludesNonScriptFiles_acceptsAllThreeExtensions() throws Exception {
        writeWorkflow("foo.ts");
        writeWorkflow("bar.mjs");
        // README.md 非脚本 → NamedWorkflows.list 按扩展名过滤排除（namedWorkflows.ts:42-45）
        Path dir = Files.createDirectories(tmpDir.resolve(".claude/workflows"));
        Files.writeString(dir.resolve("README.md"), "# readme", StandardCharsets.UTF_8);

        List<Command> commands = new WorkflowCommandLoader(tmpDir.toString()).load();

        assertEquals(2, commands.size(), "foo.ts + bar.mjs 命中，README.md 被过滤");
        assertEquals(List.of("bar", "foo"), commands.stream().map(Command::getName).toList());
    }
}
