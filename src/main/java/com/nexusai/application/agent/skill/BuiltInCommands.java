package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * COMMANDS 内置命令源 · 对齐 CC commands.ts:258 {@code COMMANDS = memoize((): Command[] => [...])}
 * （内置斜杠命令数组，loadAllCommands 合并序最后追加 commands.ts:467 {@code ...COMMANDS()}）。
 *
 * <h2>CC 对齐</h2>
 * <p>取 CC COMMANDS 数组中 web 端有意义的 11 命令子集（DEC-9）：
 * clear / compact / config / help / init / memory / model / output-style / resume / session / effort。
 * 每个命令 {@code source='builtin'}（CC Command.source，command.ts:32），type 取 CC 真实类型
 * （clear/compact='local'，config/help/memory/model/output-style/resume/session='local-jsx'，
 * init='prompt'）。
 *
 * <p><b>isEnabled 门控</b>（对齐 CC types/command.ts:214-215 {@code isEnabled?.() ?? true}）：
 * <ul>
 *   <li>{@code compact}：CC commands/compact/index.ts:9 {@code isEnabled: () => !isEnvTruthy(process.env.DISABLE_COMPACT)}
 *       → Java 用 BooleanSupplier 读 {@code System.getenv("DISABLE_COMPACT")}（isEnvTruthy 对齐
 *       CC utils/envUtils.ts:32-36：'1'/'true'/'yes'/'on' 大小写不敏感为真）。</li>
 *   <li>{@code session}：CC commands/session/index.ts:9 {@code isEnabled: () => getIsRemoteMode()}。
 *       <b>web 差异</b>（DEC-9 concern）：web 端无 remote 模式（getIsRemoteMode 恒 false），若按 CC
 *       字面 gate 会导致 session 被 isCommandEnabled 过滤不出现在 getAllCommands，与「取 web 端
 *       有意义子集」目标冲突 → default true + 本注释标注差异；isHidden 同样 default false（CC
 *       session/index.ts:10-12 {@code isHidden: () => !getIsRemoteMode()}，web 恒 false）。</li>
 *   <li>其余命令 CC 未声明 isEnabled → null → {@link Command#isCommandEnabled()} 回退 enabled=true
 *       恒放行（对齐 CC {@code isEnabled?.() ?? true}）。</li>
 * </ul>
 *
 * <p><b>不进模型可调用清单 / 不进斜杠技能集</b>（对齐 CC commands.ts:570/:593
 * {@code source !== 'builtin'}）：SkillRegistry 既有过滤链（getModelInvocableCommands:628 /
 * getSlashCommandToolSkills:780）已含 {@code c.getSource() != CommandSource.BUILTIN} 排除，
 * 本源 source=BUILTIN 自动对齐，无需额外过滤。
 *
 * <p>静态不可变注册表（对齐 {@link BundledSkills} 模式，但无动态注册/清除 —— 内置命令编译期固定，
 * 无跨测试可变状态）。{@link #getAll()} 返回防御性拷贝（不可变 List 包裹新 List），{@link #findByName}
 * 同样返回防御性拷贝，避免调用方修改共享 Command 实例污染后续调用。
 */
public final class BuiltInCommands {

    private static final List<Command> COMMANDS = List.of(
        clear(), compact(), config(), help(), init(), memory(), model(), outputStyle(), resume(), session(), effort()
    );

    /**
     * 环境变量读取器 · 测试可覆写接缝（默认 {@link System#getenv}）。
     *
     * <p>对齐 AutoCompactor.setEnvProvider 模式（AutoCompactorCcContractTest 用 Function 注入覆写
     * DISABLE_COMPACT/DISABLE_AUTO_COMPACT）：JDK 9+ 强封装下 System.getenv 返回不可变 map，测试无法
     * 就地设置 env 变量（AgentModelResolverTest:16 注释「Java System.getenv 只读无法在测试内设置」）——
     * 提供包级可覆写函数使 compact 的 isEnabled 门控语义可测（verifyStrategy DEC-9 要求锁定 env 语义）。
     * package-private（非 public API），测试经同包覆写后须在 finally/@AfterEach 还原。
     */
    static volatile java.util.function.Function<String, String> envProvider = System::getenv;

    private BuiltInCommands() {}

    /**
     * 获取全部内置命令 · 对齐 CC {@code COMMANDS()}（commands.ts:258-346）＋
     * loadAllCommands 合并序最后追加（commands.ts:467）。
     *
     * <p>返回防御性拷贝（每调用新建 Command 实例），调用方修改不影响注册表。
     *
     * @return 11 命令不可变 List（source=BUILTIN / builtin=true）
     */
    public static List<Command> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(COMMANDS));
    }

    /**
     * 按 name / alias 查找内置命令 · 对齐 CC {@code findCommand}（commands.ts:688-698 三维匹配：
     * name 精确 / userFacingName / aliases，首个命中者胜）。
     *
     * <p>前导 '/' 自动剥除（对齐 SkillRegistry.findCommand 内部归一化，commands.ts:694 之前 CC 在
     * 调用方剥 —— SkillTool.ts:437-438）。别名维度命中（如 'continue' → resume、'remote' → session）。
     *
     * @param name 命令名（可为 '/clear' 形式，前导 '/' 自动剥除）
     * @return 命中的内置命令（防御性拷贝）；未命中返回 null（CC findCommand 返回 undefined）
     */
    public static Command findByName(String name) {
        if (name == null || name.isBlank()) return null;
        String normalized = name.startsWith("/") ? name.substring(1) : name;
        for (Command c : COMMANDS) {
            if (normalized.equals(c.getName())) return copy(c);
            if (c.getAliases() != null && c.getAliases().contains(normalized)) return copy(c);
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 命令构造（逐字段对齐 CC 实际 TS 源）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * /clear · CC original: commands/clear/index.ts:10-16
     * {@code {type:'local', name:'clear', description:'Clear conversation history and free up context',
     * aliases:['reset','new'], supportsNonInteractive:false, load:()=>import('./clear.js')}}。
     * <p>type='local'（TUI 本地无副作用命令）；supportsNonInteractive 无 Java 对应字段（web 无
     * 非交互 TUI 语义），不映射。
     */
    private static Command clear() {
        return builtin("clear", "local",
            "Clear conversation history and free up context",
            List.of("reset", "new"), null, false, null);
    }

    /**
     * /compact · CC original: commands/compact/index.ts:4-13
     * {@code {type:'local', name:'compact', description:'Clear conversation history but keep a summary
     * in context. Optional: /compact [instructions for summarization]',
     * isEnabled: () => !isEnvTruthy(process.env.DISABLE_COMPACT), supportsNonInteractive:true,
     * argumentHint:'<optional custom summarization instructions>', load:()=>import('./compact.js')}}。
     * <p>isEnabled 门控（CC compact/index.ts:9）→ BooleanSupplier 经 {@link #envProvider} 读
     * DISABLE_COMPACT（惰性求值，每次 isCommandEnabled 新鲜读 —— 对齐 CC commands.ts:478 注释
     * 「isEnabled checks run fresh every call」），isEnvTruthy 语义对齐 CC utils/envUtils.ts:32-36。
     */
    private static Command compact() {
        return builtin("compact", "local",
            "Clear conversation history but keep a summary in context. Optional: /compact [instructions for summarization]",
            null, "<optional custom summarization instructions>", false,
            () -> !isEnvTruthy(envProvider.apply("DISABLE_COMPACT")));
    }

    /**
     * /config · CC original: commands/config/index.ts:3-9
     * {@code {aliases:['settings'], type:'local-jsx', name:'config', description:'Open config panel',
     * load:()=>import('./config.js')}}。
     */
    private static Command config() {
        return builtin("config", "local-jsx", "Open config panel",
            List.of("settings"), null, false, null);
    }

    /**
     * /help · CC original: commands/help/index.ts:3-8
     * {@code {type:'local-jsx', name:'help', description:'Show help and available commands',
     * load:()=>import('./help.js')}}。
     */
    private static Command help() {
        return builtin("help", "local-jsx", "Show help and available commands",
            null, null, false, null);
    }

    /**
     * /init · CC original: commands/init.ts:226-254
     * {@code {type:'prompt', name:'init', get description(){...}, contentLength:0,
     * progressMessage:'analyzing your codebase', source:'builtin',
     * async getPromptForCommand(){...}}}。
     * <p>description 在 CC 为动态 getter（feature NEW_INIT && (USER_TYPE==='ant' || CLAUDE_CODE_NEW_INIT)
     * 时 'Initialize new CLAUDE.md file(s) and optional skills/hooks...'，否则 'Initialize a new CLAUDE.md
     * file with codebase documentation'）；web 默认非 ant → 取后者（init.ts:230-234）。
     * progressMessage='analyzing your codebase'（init.ts:237）。
     * <p><b>prompt 文本不迁移到后端</b>（DEC-9 concern）：CC getPromptForCommand 返回 OLD/NEW_INIT_PROMPT
     * 长文本（init.ts:239-253），web 端 React 触发 init 自行生成，Java 仅暴露元数据。
     */
    private static Command init() {
        Command c = builtin("init", "prompt",
            "Initialize a new CLAUDE.md file with codebase documentation",
            null, null, false, null);
        c.setProgressMessage("analyzing your codebase");
        return c;
    }

    /**
     * /memory · CC original: commands/memory/index.ts:3-8
     * {@code {type:'local-jsx', name:'memory', description:'Edit Claude memory files',
     * load:()=>import('./memory.js')}}。
     */
    private static Command memory() {
        return builtin("memory", "local-jsx", "Edit Claude memory files",
            null, null, false, null);
    }

    /**
     * /model · CC original: commands/model/index.ts:5-16
     * {@code {type:'local-jsx', name:'model', get description(){return 'Set the AI model for Claude Code
     * (currently ...)'}, argumentHint:'[model]', get immediate(){return
     * shouldInferenceConfigCommandBeImmediate()}, load:()=>import('./model.js')}}。
     * <p>description 在 CC 为动态 getter（内嵌当前模型名 renderModelName(getMainLoopModel())，model/index.ts:8-10）；
     * Java 用静态描述（web 模型名由前端持有）。immediate 在 CC 为动态 getter
     * shouldInferenceConfigCommandBeImmediate()（immediateCommand.ts:10-17：
     * USER_TYPE==='ant' || growthbook experiment tengu_immediate_model_command）—— web 非 ant、
     * 无 growthbook → 恒 false（setImmediate false 为构造默认，此处显式标注差异）。
     */
    private static Command model() {
        Command c = builtin("model", "local-jsx",
            "Set the AI model for Claude Code",
            null, "[model]", false, null);
        c.setImmediate(Boolean.FALSE);
        return c;
    }

    /**
     * /output-style · CC original: commands/output-style/index.ts:3-9
     * {@code {type:'local-jsx', name:'output-style',
     * description:'Deprecated: use /config to change output style', isHidden:true,
     * load:()=>import('./output-style.js')}}。
     * <p>isHidden=true（CC output-style/index.ts:7）—— 命令仍可经 findCommand/execute 触发，
     * 但不出现在 GET /api/command/builtins 列表（React 默认不渲染隐藏命令）。
     */
    private static Command outputStyle() {
        return builtin("output-style", "local-jsx",
            "Deprecated: use /config to change output style",
            null, null, true, null);
    }

    /**
     * /resume · CC original: commands/resume/index.ts:3-10
     * {@code {type:'local-jsx', name:'resume', description:'Resume a previous conversation',
     * aliases:['continue'], argumentHint:'[conversation id or search term]',
     * load:()=>import('./resume.js')}}。
     */
    private static Command resume() {
        return builtin("resume", "local-jsx", "Resume a previous conversation",
            List.of("continue"), "[conversation id or search term]", false, null);
    }

    /**
     * /session · CC original: commands/session/index.ts:4-14
     * {@code {type:'local-jsx', name:'session', aliases:['remote'],
     * description:'Show remote session URL and QR code', isEnabled:()=>getIsRemoteMode(),
     * get isHidden(){return !getIsRemoteMode()}, load:()=>import('./session.js')}}。
     * <p><b>web 差异</b>（DEC-9 concern）：web 无 remote 模式（bootstrap/state.ts getIsRemoteMode 恒
     * false）→ 按 CC 字面 gate 将恒被过滤；为满足「web 端有意义子集」isEnabled default true +
     * isHidden false，本注释标注与 CC 的差异（CC 语义为 remote 模式下才启用/可见）。
     */
    private static Command session() {
        return builtin("session", "local-jsx", "Show remote session URL and QR code",
            List.of("remote"), null, false, null);
    }

    /**
     * /effort · CC original: commands/effort/index.ts:4-13
     * {@code {type:'local-jsx', name:'effort', description:'Set effort level for model usage',
     * argumentHint:'[low|medium|high|max|auto]', get immediate(){return
     * shouldInferenceConfigCommandBeImmediate()}, load:()=>import('./effort.js')}}。
     * <p>immediate 在 CC 为动态 getter shouldInferenceConfigCommandBeImmediate()
     * （immediateCommand.ts:10-17：USER_TYPE==='ant' || growthbook experiment）—— web 非 ant、
     * 无 growthbook → 恒 false（显式 false，同 {@link #model()} 差异标注模式）。
     * <p>后端真实执行：web 端点 POST /api/command/builtins/effort/execute（CommandController
     * 专属字面端点）→ {@code EffortCommand.handle(args)}（R2 会话级：写当前会话 sessions.effort_level
     * + 会话 AgentState.effortValue + env 覆盖检测，对齐 CC effort.tsx 全链）。
     */
    private static Command effort() {
        Command c = builtin("effort", "local-jsx", "Set effort level for model usage",
            null, "[low|medium|high|max|auto]", false, null);
        c.setImmediate(Boolean.FALSE);
        return c;
    }

    /**
     * 内置命令统一构造 · source=BUILTIN + builtin=true（对齐 CC COMMANDS 数组全部命令
     * {@code source:'builtin'}，command.ts:32 SettingSource|'builtin' 联合）。
     *
     * @param name 命令名（CC CommandBase.name）
     * @param type CC 真实类型（'local' / 'local-jsx' / 'prompt'）
     * @param description 描述（CC CommandBase.description）
     * @param aliases 别名（CC CommandBase.aliases，null → 不设）
     * @param argumentHint 参数提示（CC CommandBase.argumentHint，null → 不设）
     * @param isHidden 是否隐藏（CC CommandBase.isHidden）
     * @param isEnabled 惰性启用判定（CC types/command.ts:214-215 isEnabled，null → 恒启用）
     */
    private static Command builtin(String name, String type, String description,
                                   List<String> aliases, String argumentHint,
                                   boolean isHidden, BooleanSupplier isEnabled) {
        Command c = new Command();
        c.setName(name);
        c.setType(type);
        c.setDescription(description);
        if (aliases != null) c.setAliases(List.copyOf(aliases));
        c.setArgumentHint(argumentHint);
        c.setIsHidden(isHidden);
        c.setIsEnabled(isEnabled);
        c.setSource(CommandSource.BUILTIN);   // CC Command.source = 'builtin'
        c.setBuiltin(Boolean.TRUE);           // 内置命令不可删除（CommandService.delete 守卫）
        return c;
    }

    /** 防御性拷贝 · 避免调用方修改共享 Command 实例（isEnabled supplier 随 Command 实例共享）。 */
    private static Command copy(Command c) {
        Command clone = new Command();
        clone.setName(c.getName());
        clone.setType(c.getType());
        clone.setDescription(c.getDescription());
        if (c.getAliases() != null) clone.setAliases(List.copyOf(c.getAliases()));
        clone.setArgumentHint(c.getArgumentHint());
        clone.setIsHidden(c.getIsHidden());
        clone.setImmediate(c.getImmediate());
        clone.setProgressMessage(c.getProgressMessage());
        clone.setIsEnabled(c.getIsEnabled());
        clone.setSource(c.getSource());
        clone.setBuiltin(c.getBuiltin());
        return clone;
    }

    /**
     * 判断环境变量值是否为真 · 对齐 CC isEnvTruthy（utils/envUtils.ts:32-36）。
     *
     * <p>接受 true/1/yes/on（trim + 小写，大小写不敏感）；空/null/其它 → false（CC
     * {@code ['1','true','yes','on'].includes(normalizedValue)}）。
     */
    private static boolean isEnvTruthy(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.trim().toLowerCase();
        return "true".equals(lower) || "1".equals(lower)
            || "yes".equals(lower) || "on".equals(lower);
    }
}
