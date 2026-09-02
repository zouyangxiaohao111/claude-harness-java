package com.nexusai.application.agent.workflow.command;

import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.workflow.NamedWorkflows;
import com.nexusai.application.agent.workflow.WorkflowConstants;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * workflow 面板命令加载器 · CC original: {@code getWorkflowCommands}
 * (Open-ClaudeCode/src/workflow/namedWorkflowCommands.ts:10-34)。
 *
 * <p>扫描 {@code <projectRoot>/.claude/workflows/*.ts|js|mjs}（复用 {@link NamedWorkflows#list}，
 * 与 CC {@code listNamedWorkflows(dir)} 同源），为每个命名 workflow 生成一个
 * {@code /<name>} 斜杠命令（{@link Command}）。生成的命令是「提示型」：{@code promptFn}
 * 提示模型调用 Workflow 工具（name="&lt;workflowName&gt;"）真正执行 —— 面板命令仅负责
 * 把 workflow 曝光到命令池（footer pill / 斜杠命令），执行本体在 {@code WorkflowTool}。
 *
 * <p><b>W-4a 接线</b>：作为 {@code SkillRegistry.setWorkflowCommandProvider} 的生产加载器
 * 注入（ToolRegistrationConfig 按 {@code FeatureFlags.workflowScripts()} 门控），对齐 CC
 * {@code getWorkflowCommands = feature('WORKFLOW_SCRIPTS') ? ... : null}（commands.ts:401-406）。
 *
 * <p><b>字段对齐（namedWorkflowCommands.ts:15-32）</b>：
 * <table>
 *   <tr><th>CC 字段</th><th>CC 行号</th><th>Java 落点</th></tr>
 *   <tr><td>{@code type: 'prompt'}</td><td>:16</td><td>{@code Command.setType("prompt")}</td></tr>
 *   <tr><td>{@code name}</td><td>:17</td><td>{@code Command.setName(name)}（workflow 文件名去扩展名）</td></tr>
 *   <tr><td>{@code description: `Run workflow: ${name}`}</td><td>:18</td><td>{@code Command.setDescription(...)}</td></tr>
 *   <tr><td>{@code kind: 'workflow'}</td><td>:19</td><td>{@code Command.setKind("workflow")}</td></tr>
 *   <tr><td>{@code source: 'builtin'}</td><td>:20</td><td>{@code Command.setSource(CommandSource.BUILTIN)}</td></tr>
 *   <tr><td>{@code progressMessage: `Running workflow ${name}...`}</td><td>:21</td><td>{@code Command.setProgressMessage(...)}</td></tr>
 *   <tr><td>{@code contentLength: 0}</td><td>:22</td><td>{@code Command.content} 不设 → {@code getContentLength()} 自然返回 0</td></tr>
 *   <tr><td>{@code getPromptForCommand}</td><td>:23-32</td><td>{@code Command.setPromptFn(...)}（闭包捕获 name）</td></tr>
 * </table>
 *
 * <p><b>source=BUILTIN 的消费侧后果</b>（对齐 CC，非缺陷）：生成命令经
 * {@code SkillRegistry.getModelInvocableCommands} / {@code getSlashCommandToolSkills}
 * 的既有 {@code source != BUILTIN} 过滤被排除（commands.ts:570/:593），仅进入
 * {@code getAllCommands} / {@code findCommand} 消费面 —— 与 CC workflow 命令一致
 * （斜杠命令可查，不参与模型技能清单）。
 */
public final class WorkflowCommandLoader {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCommandLoader.class);

    /** projectRoot（会话绑定项目根 · 生产 = {@code AutoMemPaths.currentSessionProjectRoot}）· CC original: {@code cwd} (namedWorkflowCommands.ts:11) */
    private final String projectRoot;

    /**
     * 构造 · CC original: {@code getWorkflowCommands(cwd = getProjectRoot())} (namedWorkflowCommands.ts:10-12)。
     *
     * @param projectRoot 项目根目录（其下 {@code .claude/workflows} 即扫描目录）
     */
    public WorkflowCommandLoader(String projectRoot) {
        this.projectRoot = projectRoot != null ? projectRoot : "";
    }

    /**
     * 扫描并生成 workflow 命令列表 · CC original: {@code getWorkflowCommands} (namedWorkflowCommands.ts:13-33)。
     *
     * <p>{@code dir = join(cwd, WORKFLOW_DIR_NAME)}（:13）→ {@code listNamedWorkflows(dir)}（:14，
     * Java 复用 {@link NamedWorkflows#list}，目录缺失 → 空列表不抛）→ 逐 name 映射命令（:15-32）。
     *
     * @return 不可变命令列表（无 workflow 脚本 → 空列表）
     */
    public List<Command> load() {
        // 决策 D6/D7：nexusai 目录优先 + .claude/workflows 回落（既有命名 workflow 兼容）
        List<String> names = NamedWorkflows.listWithFallback(projectRoot);
        List<Command> commands = new ArrayList<>(names.size());
        for (String name : names) {
            commands.add(toCommand(name));
        }
        if (log.isDebugEnabled()) {
            log.debug("WorkflowCommandLoader.load: projectRoot={} 命中 {} 个命名 workflow → 生成 {} 个命令（namedWorkflowCommands.ts:13-33）",
                    projectRoot, names.size(), commands.size());
        }
        return List.copyOf(commands);
    }

    /**
     * 单 workflow 名 → Command · CC original: {@code names.map(name => ({...}))} (namedWorkflowCommands.ts:15-32)。
     *
     * @param name workflow 文件名（去扩展名，NamedWorkflows.list 已保证）
     * @return 生成的提示型 workflow 命令
     */
    private static Command toCommand(String name) {
        Command c = new Command();
        // namedWorkflowCommands.ts:16 type: 'prompt'
        c.setType("prompt");
        // namedWorkflowCommands.ts:17 name（workflow 文件名）
        c.setName(name);
        // namedWorkflowCommands.ts:18 description
        c.setDescription("Run workflow: " + name);
        // namedWorkflowCommands.ts:19 kind: 'workflow'
        c.setKind("workflow");
        // namedWorkflowCommands.ts:20 source: 'builtin'
        c.setSource(CommandSource.BUILTIN);
        // namedWorkflowCommands.ts:21 progressMessage
        c.setProgressMessage("Running workflow " + name + "...");
        // namedWorkflowCommands.ts:22 contentLength: 0 — Command.content 留 null，
        //   getContentLength() 派生返回 0（对齐 CC 显式字面量 0）。
        // namedWorkflowCommands.ts:23-32 getPromptForCommand(args, _context) 闭包：
        //   const argText = typeof args === 'string' && args ? `\n\nArguments: ${args}` : ''
        //   return [{ type: 'text', text: `Run the "${name}" workflow now by calling the
        //     Workflow tool with name="${name}".${argText}` }]
        c.setPromptFn((args, context) -> {
            String argText = (args != null && !args.isEmpty()) ? "\n\nArguments: " + args : "";
            String text = "Run the \"" + name + "\" workflow now by calling the Workflow tool "
                    + "with name=\"" + name + "\"." + argText;
            return List.of(new ContentBlockParam.TextBlockParam(text));
        });
        return c;
    }
}
