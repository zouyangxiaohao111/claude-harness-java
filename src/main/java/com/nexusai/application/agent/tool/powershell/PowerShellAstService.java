package com.nexusai.application.agent.tool.powershell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.cli.PackageManagers;
import com.nexusai.application.agent.readonly.ReadOnlyCommandTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PowerShell AST Service · 对齐 CC {@code utils/powershell/parser.ts} 的
 * {@code ParsedPowerShellCommand}（session A5 扩展为 parser.ts 对齐种子）。
 *
 * <p>真启动 pwsh 进程解析 AST（CC parser.ts:669 toUtf16LeBase64 UTF-16LE Base64），
 * 输出完整 AST JSON：statements（elements→commands 含 name/nameType/args/elementTypes/
 * text/redirections/children）+ nestedCommands + variables + 安全标志 + typeLiterals +
 * hasUsingStatements + hasScriptRequirements。
 *
 * <p>RAW→transformed 变换（RC-1/RC-2 修正，移植 perm-wf1 88f212ef）：raw PS1 输出
 * statements[].elements（无 commands 键），本类 transformCommandAst 从 commandElements[]
 * 构建 CommandElement；children 按真实 array-of-object 形状解析并经 mapElementType 归一
 * （CC parser.ts:903-914），与 args 对齐（children[i] ↔ args[i] ↔ elementTypes[i+1]）。
 */
@Component
public class PowerShellAstService {

    private static final Logger log = LoggerFactory.getLogger(PowerShellAstService.class);

    /** pwsh 进程解析超时（ms），对齐 CC parser.ts:207 DEFAULT_PARSE_TIMEOUT_MS。 */
    private static final long PARSE_TIMEOUT_MS = 5_000L;

    /** 用户命令最大字节数（UTF-8）· Unix 固定 4500，对齐 CC parser.ts:636 UNIX_MAX_COMMAND_LENGTH。 */
    private static final int UNIX_MAX_COMMAND_BYTES = 4_500;

    /** Windows CreateProcess argv 上限 · 对齐 CC parser.ts:611 WINDOWS_ARGV_CAP。 */
    private static final int WINDOWS_ARGV_CAP = 32_767;

    /** pwsh 路径 + flags + argv 引号固定开销 · 对齐 CC parser.ts:615 FIXED_ARGV_OVERHEAD。 */
    private static final int FIXED_ARGV_OVERHEAD = 200;

    /** {@code "$EncodedCommand = ''\n"} 包装长度 · 对齐 CC parser.ts:617 ENCODED_CMD_WRAPPER。 */
    private static final int ENCODED_CMD_WRAPPER = "$EncodedCommand = ''\n".length();

    /** base64 padding 舍入 + 估算漂移安全边际 · 对齐 CC parser.ts:621 SAFETY_MARGIN。 */
    private static final int SAFETY_MARGIN = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public PowerShellAstService() {}

    // ════════════════════════════════════════════════════════════════════════
    // A5 扩展：ParsedPowerShellCommand 等价结构
    // ════════════════════════════════════════════════════════════════════════
    /** 顶层文件重定向 · 对齐 CC getFileRedirections 返回的 {@code {target, isMerging}}。 */
    public record Redirection(String target, boolean isMerging) {}

    /**
     * 命令元素子节点 · 对齐 CC {@code CommandElementChild}（parser.ts:43-46）。
     * 仅 colon-bound 参数（CommandParameterAst.Argument）产出，children[i] ↔ args[i] ↔ elementTypes[i+1]。
     * @param type 绑定值 AST 类型（StringConstant/Variable/...，经 mapElementType 归一）
     * @param text 绑定值原文（CC text）
     */
    public record CommandElementChild(String type, String text) {}

    /**
     * 命令元素 · 对齐 CC {@code ParsedCommandElement}（parser.ts transformCommandAst）。
     * @param name        规范命令名（stripModulePrefix + 去引号）
     * @param nameType    cmdlet | application | unknown（CC classifyCommandName）
     * @param elementType CommandAst（CC elementType）
     * @param args        参数原始文本（CC args）
     * @param elementTypes 参数元素类型（CC elementTypes，StringConstant/Parameter/...）
     * @param text        命令整体原始文本（CC text）
     * @param redirections 重定向目标（CC redirections）
     * @param children    每参数的 colon-bound 子节点（CC children，与 args 对齐，无子节点为空列表）
     */
    public record CommandElement(String name, String nameType, String elementType, List<String> args,
                                 List<String> elementTypes, String text, List<String> redirections,
                                 List<List<CommandElementChild>> children) {}

    /**
     * 语句 · 对齐 CC {@code ParsedPowerShellCommand.statements[number]}。
     * @param type         AST 节点类型名（PipelineAst/AssignmentStatementAst/...）
     * @param text         语句原始文本
     * @param commands     管道内顶层 CommandAst 命令
     * @param nestedCommands 脚本块/括号内嵌套命令（CC nestedCommands）
     */
    public record Statement(String type, String text, List<CommandElement> commands,
                            List<CommandElement> nestedCommands) {}

    /**
     * 完整解析结果 · 对齐 CC {@code ParsedPowerShellCommand}（parser.ts:1300-1420）。
     */
    public record ParsedResult(
        boolean valid,
        List<String> errors,
        boolean hasStopParsing,
        boolean hasUsingStatements,
        boolean hasScriptRequirements,
        boolean hasScriptBlocks,
        boolean hasSubExpressions,
        boolean hasExpandableStrings,
        boolean hasSplatting,
        boolean hasMemberInvocations,
        boolean hasAssignments,
        List<String> variables,
        List<String> typeLiterals,
        List<Statement> statements,
        List<Redirection> redirections,
        String originalCommand
    ) {
        /** 命令名列表（CC getAllCommandNames，用于汇总/日志）。 */
        public List<String> commandNames() {
            List<String> names = new ArrayList<>();
            for (Statement st : statements) {
                for (CommandElement c : st.commands()) {
                    names.add(c.name().toLowerCase());
                }
                for (CommandElement c : st.nestedCommands()) {
                    names.add(c.name().toLowerCase());
                }
            }
            return names;
        }
    }

    /**
     * safe-output cmdlet 判定（name-only）· 对齐 CC readOnlyValidation.ts:1038-1041 +
     * SAFE_OUTPUT_CMDLETS:888-917。
     *
     * <p>仅 {@code out-null} 保留在 SAFE_OUTPUT_CMDLETS（其余 Format-* 与 Select-Object 等迁至
     * PIPELINE_TAIL_CMDLETS 并走 argLeaksValue 保护的 allowlist）。
     */
    public static boolean isSafeOutputCommand(String name) {
        return "out-null".equals(ReadOnlyCommandTable.resolveToCanonical(name));
    }

    /**
     * 提取子命令信息列表 · 对齐 CC {@code powershellPermissions.ts:539-624
     * getSubCommandsForPermissionCheck}。
     *
     * <p>对每个语句的 commands + nestedCommands（仅 CommandAst）构建 {@link PowerShellSubCommandInfo}，
     * 保留 statement 关联（step5 fail-closed 去重）与 isSafeOutput 标志。未解析或空列表返回
     * fallback 单元素（text=originalCommand，statement=null，isSafeOutput=false）。
     *
     * @param parsed           解析结果
     * @param originalCommand  原始命令（fallback 用）
     * @return 子命令信息列表（永非空）
     */
    public List<PowerShellSubCommandInfo> subCommandsForPermissionCheck(
            ParsedResult parsed, String originalCommand) {
        if (!parsed.valid()) {
            return List.of(new PowerShellSubCommandInfo(originalCommand,
                fallbackElement(originalCommand), null, false));
        }
        List<PowerShellSubCommandInfo> subCommands = new ArrayList<>();
        for (Statement st : parsed.statements()) {
            for (CommandElement c : st.commands()) {
                // Only check actual commands (CommandAst), not expressions
                if (!"CommandAst".equals(c.elementType())) continue;
                subCommands.add(new PowerShellSubCommandInfo(c.text(), c, st,
                    isSafeOutputFlag(c)));
            }
            if (st.nestedCommands() != null) {
                for (CommandElement c : st.nestedCommands()) {
                    subCommands.add(new PowerShellSubCommandInfo(c.text(), c, st,
                        isSafeOutputFlag(c)));
                }
            }
        }
        if (subCommands.isEmpty()) {
            return List.of(new PowerShellSubCommandInfo(originalCommand,
                fallbackElement(originalCommand), null, false));
        }
        return subCommands;
    }

    /** isSafeOutput 标志 · 对齐 CC :582-585（nameType!=='application' && safe-output && args 空）。 */
    private static boolean isSafeOutputFlag(CommandElement c) {
        return !"application".equals(c.nameType())
            && isSafeOutputCommand(c.name())
            && c.args().isEmpty();
    }

    /** fallback 命令元素 · 对齐 CC :546-558（name=extractCommandName，nameType=unknown，无参数）。 */
    private static CommandElement fallbackElement(String originalCommand) {
        String name = originalCommand == null ? "" : originalCommand.trim();
        int sp = name.indexOf(' ');
        if (sp > 0) name = name.substring(0, sp);
        name = name.replaceAll("^['\"]|['\"]$", "");
        return new CommandElement(name, "unknown", "CommandAst",
            List.of(), List.of("StringConstant"), originalCommand, List.of(), List.of());
    }

    /**
     * 解析 PowerShell 脚本，真启动 pwsh 进程（UTF-16LE Base64 -EncodedCommand）。
     *
     * @param script 用户脚本
     * @return ParsedResult（valid=false 时 errors 包含错误信息；A5 保留降级语义）
     */
    public ParsedResult parseAst(String script) {
        if (script == null || script.isBlank()) {
            return emptyResult(false, List.of("NoInput"), script == null ? "" : script);
        }
        byte[] scriptBytes = script.getBytes(StandardCharsets.UTF_8);
        if (scriptBytes.length > MAX_COMMAND_BYTES) {
            if (log.isDebugEnabled()) {
                log.debug("PowerShellAstService: 命令过长 ({} bytes, max {})", scriptBytes.length, MAX_COMMAND_BYTES);
            }
            return emptyResult(false, List.of("CommandTooLong: " + scriptBytes.length), script);
        }
        String pwshPath = resolvePwshPath();
        if (pwshPath == null) {
            return emptyResult(false, List.of("NoPowerShell"), script);
        }
        String psScript = buildParseScript(script);
        String encoded = toUtf16LeBase64(psScript);
        try {
            ProcessBuilder pb = new ProcessBuilder(pwshPath, "-NoProfile", "-NonInteractive", "-NoLogo",
                "-EncodedCommand", encoded);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    output.append(buf, 0, n);
                }
            }
            boolean finished = process.waitFor(PARSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("PowerShellAstService: pwsh 超时 {}ms", PARSE_TIMEOUT_MS);
                return emptyResult(false, List.of("PwshTimeout"), script);
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("PowerShellAstService: pwsh exitCode={} stderr={}", exitCode, output);
                return emptyResult(false, List.of("PwshError: exitCode=" + exitCode), script);
            }
            String trimmed = output.toString().trim();
            if (trimmed.isEmpty()) {
                return emptyResult(false, List.of("EmptyOutput"), script);
            }
            // TR-C1-D-4（EV-C1-082，对齐 CC parser.ts）：PS 5.1（powershell.exe）stdout 重定向时可能
            // 输出 CLIXML 头 '#< CLIXML'（pwsh/PS7 无此行为）。CC 不剥离该头——CLIXML 场景在 CC 侧
            // fail-closed→ask（解析失败即权限链降级 ask）；Java 旧实现剥离后解析成功更宽容（secret 面
            // 变宽）。移除头剥离：CLIXML 头使下方 readTree 失败 → valid=false → 权限链 ask（fail-closed）。
            return parseJsonOutput(trimmed, script);
        } catch (Exception e) {
            log.warn("PowerShellAstService: 进程启动失败: {}", e.getMessage());
            return emptyResult(false, List.of("PwshSpawnError: " + e.getMessage()), script);
        }
    }

    private ParsedResult emptyResult(boolean valid, List<String> errors, String original) {
        return new ParsedResult(valid, errors, false, false, false, false, false, false, false, false, false,
            List.of(), List.of(), List.of(), List.of(), original);
    }

    /**
     * 解析 pwsh 输出 JSON → ParsedResult（对齐 CC parser.ts:1106 transformRawOutput）。
     *
     * <p>原始 JSON 为 CC RawParsedOutput 形状（statements[].elements/nestedCommands/
     * redirections/securityPatterns + variables[].path/isSplatted + typeLiterals），本方法变换为
     * ParsedResult：elements→commands、nestedCommands.commandElements→transform、
     * securityPatterns 聚合为顶层布尔、hasAssignments 由 statement.type==AssignmentStatementAst
     * 派生、variables{path,isSplatted}→List&lt;String&gt; path + hasSplatting、语句级 redirections 去重。
     *
     * <p>package-private 供 parse 路径集成测试访问（不改安全语义）。
     */
    ParsedResult parseJsonOutput(String json, String originalScript) {
        try {
            JsonNode root = MAPPER.readTree(json);
            boolean valid = root.path("valid").asBoolean(false);

            // errors {message,errorId} → List<String> message（CC RawParsedOutput.errors）
            List<String> errors = new ArrayList<>();
            JsonNode errs = root.get("errors");
            if (errs != null && errs.isArray()) {
                for (JsonNode e : errs) {
                    errors.add(e.path("message").asText(""));
                }
            }

            // variables {path,isSplatted} → List<String> path + hasSplatting（CC RawParsedOutput.variables）
            List<String> variables = new ArrayList<>();
            boolean hasSplatting = false;
            JsonNode vars = root.get("variables");
            if (vars != null && vars.isArray()) {
                for (JsonNode v : vars) {
                    variables.add(v.path("path").asText(""));
                    if (v.path("isSplatted").asBoolean(false)) {
                        hasSplatting = true;
                    }
                }
            }

            // typeLiterals（顶层字符串数组，对齐 CC RawParsedOutput.typeLiterals → checkTypeLiterals）
            List<String> typeLiterals = new ArrayList<>();
            JsonNode tl = root.get("typeLiterals");
            if (tl != null && tl.isArray()) {
                for (JsonNode t : tl) {
                    typeLiterals.add(t.asText(""));
                }
            }

            // 语句 + 聚合 securityPatterns / hasAssignments / 语句级 redirections
            boolean hasScriptBlocks = false;
            boolean hasSubExpressions = false;
            boolean hasExpandableStrings = false;
            boolean hasMemberInvocations = false;
            boolean hasAssignments = false;
            List<Statement> statements = new ArrayList<>();
            List<Redirection> redirections = new ArrayList<>();
            java.util.Set<String> seenRedir = new java.util.HashSet<>();
            JsonNode stmts = root.get("statements");
            if (stmts != null && stmts.isArray()) {
                for (JsonNode st : stmts) {
                    statements.add(parseStatement(st));
                    JsonNode sp = st.get("securityPatterns");
                    if (sp != null && sp.isObject()) {
                        hasScriptBlocks |= sp.path("hasScriptBlocks").asBoolean(false);
                        hasSubExpressions |= sp.path("hasSubExpressions").asBoolean(false);
                        hasExpandableStrings |= sp.path("hasExpandableStrings").asBoolean(false);
                        hasMemberInvocations |= sp.path("hasMemberInvocations").asBoolean(false);
                    }
                    if ("AssignmentStatementAst".equals(st.path("type").asText(""))) {
                        hasAssignments = true;
                    }
                    for (JsonNode redir : ensureArray(st.get("redirections"))) {
                        Redirection r = transformRedirection(redir);
                        String key = r.target() + "|" + r.isMerging();
                        if (seenRedir.add(key)) {
                            redirections.add(r);
                        }
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("PowerShellAstService: JSON 变换完成 valid={} statements={} variables={} " +
                        "typeLiterals={} hasMemberInvocations={} hasScriptBlocks={} hasSubExpressions={}",
                    valid, statements.size(), variables.size(), typeLiterals.size(),
                    hasMemberInvocations, hasScriptBlocks, hasSubExpressions);
            }

            return new ParsedResult(valid, errors,
                root.path("hasStopParsing").asBoolean(false),
                root.path("hasUsingStatements").asBoolean(false),
                root.path("hasScriptRequirements").asBoolean(false),
                hasScriptBlocks, hasSubExpressions, hasExpandableStrings, hasSplatting,
                hasMemberInvocations, hasAssignments, variables, typeLiterals, statements, redirections,
                originalScript);
        } catch (Exception e) {
            log.warn("PowerShellAstService: 无效 JSON 输出: {}", e.getMessage());
            return emptyResult(false, List.of("InvalidJson: " + e.getMessage()), originalScript);
        }
    }

    /**
     * 变换 raw statement → Statement（对齐 CC transformStatement L1002-1103）。
     * PipelineAst 走 elements（CommandAst→transformCommandAst，ParenExpressionAst→
     * ParenExpressionAst，其它→CommandExpressionAst）；非 Pipeline 合成 CommandExpressionAst。
     */
    private Statement parseStatement(JsonNode st) {
        String type = st.path("type").asText("PipelineAst");
        String text = st.path("text").asText("");
        List<CommandElement> commands = new ArrayList<>();
        if (st.has("elements")) {
            for (JsonNode elem : ensureArray(st.get("elements"))) {
                String elemType = elem.path("type").asText("");
                if ("CommandAst".equals(elemType)) {
                    commands.add(transformCommandAst(elem));
                } else {
                    commands.add(transformExpressionElement(elem));
                }
            }
        } else {
            // 非 Pipeline 语句：合成 CommandExpressionAst 条目（CC L1048-1055）
            commands.add(new CommandElement(text, "unknown", "CommandExpressionAst",
                List.of(), List.of("Other"), text, List.of(), List.of()));
        }
        List<CommandElement> nested = new ArrayList<>();
        // ensureArray：PS5.1 ConvertTo-Json 单元素数组可能解包为对象
        for (JsonNode nc : ensureArray(st.get("nestedCommands"))) {
            nested.add(transformCommandAst(nc));
        }
        return new Statement(type, text, commands, nested);
    }

    /**
     * 变换 raw CommandAst → CommandElement（对齐 CC transformCommandAst L830-935）。
     * name 取首 commandElement 的 value/text（去引号 + stripModulePrefix），nameType 由
     * classifyCommandName 派生（非 ASCII 命令名强制 application），args 取后续元素
     * value/text，elementTypes 走 mapElementType，children 按真实 array-of-object 逐元素
     * mapElementType 归一并与 args 对齐（无子节点空列表，CC L903-914），redirections 拍平
     * 为 target 列表。
     */
    private CommandElement transformCommandAst(JsonNode raw) {
        List<JsonNode> cmdElements = ensureArray(raw.get("commandElements"));
        String name = "";
        List<String> args = new ArrayList<>();
        List<String> elementTypes = new ArrayList<>();
        List<List<CommandElementChild>> children = new ArrayList<>();
        String nameType = "unknown";

        if (!cmdElements.isEmpty()) {
            JsonNode first = cmdElements.get(0);
            boolean isFirstStringLiteral =
                "StringConstantExpressionAst".equals(first.path("type").asText(""))
                || "ExpandableStringExpressionAst".equals(first.path("type").asText(""));
            String rawNameUnstripped =
                isFirstStringLiteral && first.has("value") && first.get("value").isTextual()
                    ? first.get("value").asText()
                    : first.path("text").asText("");
            String rawName = rawNameUnstripped.replaceAll("^['\"]|['\"]$", "");
            // SECURITY: 非 ASCII 命令名强制 application（CC L882-886 finding #31）
            if (rawName.codePoints().anyMatch(cp -> cp >= 0x80)) {
                nameType = "application";
            } else {
                nameType = classifyCommandName(rawName);
            }
            name = stripModulePrefix(rawName);
            elementTypes.add(mapElementType(first.path("type").asText(""),
                first.has("expressionType") ? first.get("expressionType").asText() : null));

            for (int i = 1; i < cmdElements.size(); i++) {
                JsonNode ce = cmdElements.get(i);
                String ceType = ce.path("type").asText("");
                boolean isStringLiteral =
                    "StringConstantExpressionAst".equals(ceType)
                    || "ExpandableStringExpressionAst".equals(ceType);
                args.add(isStringLiteral && ce.has("value") && ce.get("value").isTextual()
                    ? ce.get("value").asText()
                    : ce.path("text").asText(""));
                elementTypes.add(mapElementType(ceType,
                    ce.has("expressionType") ? ce.get("expressionType").asText() : null));
                // children：CommandParameterAst.Argument（array-of-object）经 mapElementType 归一
                // 与 args[i] 对齐（CC L903-914；PS5.1 单元素数组可能解包为对象，ensureArray 处理）
                List<CommandElementChild> kids = new ArrayList<>();
                for (JsonNode child : ensureArray(ce.get("children"))) {
                    if (child == null || child.isNull()) continue;
                    String childType = mapElementType(child.path("type").asText(""), null);
                    String childText = child.path("text").asText("");
                    if (log.isDebugEnabled()) {
                        log.debug("PowerShellAstService: children 命中 arg={} childType={} childText={}",
                            args.get(args.size() - 1), childType, childText);
                    }
                    kids.add(new CommandElementChild(childType, childText));
                }
                children.add(kids);
            }
        }

        List<String> redirs = new ArrayList<>();
        for (JsonNode redir : ensureArray(raw.get("redirections"))) {
            Redirection r = transformRedirection(redir);
            if (!r.target().isEmpty() && !redirs.contains(r.target())) {
                redirs.add(r.target());
            }
        }

        return new CommandElement(name, nameType, "CommandAst", args, elementTypes,
            raw.path("text").asText(""), redirs, children);
    }

    /**
     * 变换非 CommandAst 管线元素（对齐 CC transformExpressionElement L939-958）。
     * ParenExpressionAst→ParenExpressionAst，其它→CommandExpressionAst。
     */
    private CommandElement transformExpressionElement(JsonNode raw) {
        String elementType = "ParenExpressionAst".equals(raw.path("type").asText(""))
            ? "ParenExpressionAst" : "CommandExpressionAst";
        List<String> elementTypes = List.of(mapElementType(raw.path("type").asText(""),
            raw.has("expressionType") ? raw.get("expressionType").asText() : null));
        return new CommandElement(raw.path("text").asText(""), "unknown", elementType,
            List.of(), elementTypes, raw.path("text").asText(""), List.of(), List.of());
    }

    /** 变换 raw redirection → Redirection(target, isMerging)（对齐 CC transformRedirection L962-998）。 */
    private static Redirection transformRedirection(JsonNode raw) {
        if ("MergingRedirectionAst".equals(raw.path("type").asText(""))) {
            return new Redirection("", true);
        }
        return new Redirection(raw.path("locationText").asText(""), false);
    }

    /** mapElementType（对齐 CC parser.ts:749-796）：raw .NET AST 类型名 → CC CommandElementType 名。 */
    private static String mapElementType(String rawType, String expressionType) {
        if (rawType == null) return "Other";
        return switch (rawType) {
            case "ScriptBlockExpressionAst" -> "ScriptBlock";
            case "SubExpressionAst", "ArrayExpressionAst", "ParenExpressionAst" -> "SubExpression";
            case "ExpandableStringExpressionAst" -> "ExpandableString";
            case "InvokeMemberExpressionAst", "MemberExpressionAst" -> "MemberInvocation";
            case "VariableExpressionAst" -> "Variable";
            case "StringConstantExpressionAst", "ConstantExpressionAst" -> "StringConstant";
            case "CommandParameterAst" -> "Parameter";
            case "CommandExpressionAst" -> {
                if (expressionType != null) {
                    yield mapElementType(expressionType, null);
                }
                yield "Other";
            }
            default -> "Other";
        };
    }

    /** classifyCommandName（对齐 CC parser.ts:800-810）：cmdlet | application | unknown。 */
    private static String classifyCommandName(String name) {
        if (name == null) return "unknown";
        if (name.matches("^[A-Za-z]+-[A-Za-z][A-Za-z0-9_]*$")) return "cmdlet";
        if (name.contains(".") || name.contains("\\") || name.contains("/")) return "application";
        return "unknown";
    }

    /** stripModulePrefix（对齐 CC parser.ts:814-826，不剥离文件路径）。 */
    private static String stripModulePrefix(String name) {
        if (name == null) return "";
        int idx = name.lastIndexOf('\\');
        if (idx < 0) return name;
        if (name.matches("^[A-Za-z]:.*") || name.startsWith("\\\\")
                || name.startsWith(".\\") || name.startsWith("..\\")) {
            return name;
        }
        return name.substring(idx + 1);
    }

    /** ensureArray（对齐 CC parser.ts:703-708；PS 5.1 单元素数组解包处理）。 */
    private static List<JsonNode> ensureArray(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (node.isArray()) {
            List<JsonNode> list = new ArrayList<>();
            node.forEach(list::add);
            return list;
        }
        return List.of(node);
    }

    /** 解析 pwsh 路径（对齐 CC parser.ts:1156 getCachedPowerShellPath）。 */
    private String resolvePwshPath() {
        if (isExecutable("pwsh")) return "pwsh";
        if (isExecutable("powershell")) return "powershell";
        if (isExecutable("powershell.exe")) return "powershell.exe";
        return null;
    }

    private boolean isExecutable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "-NoProfile", "-NonInteractive", "-NoLogo", "-Command", "$true")
                .redirectErrorStream(true).start();
            boolean finished = p.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * UTF-16LE Base64 编码（对齐 CC parser.ts:669 toUtf16LeBase64）。
     * PowerShell -EncodedCommand 参数要求 UTF-16LE + Base64。
     */
    static String toUtf16LeBase64(String text) {
        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
        return Base64.getEncoder().encodeToString(utf16);
    }

    /**
     * 构建 PS1 解析脚本（对齐 CC parser.ts:687 buildParseScript）。
     * 把用户脚本 UTF-8 Base64 编码后嵌入变量，然后调用 AST 解析输出完整 JSON。
     * PS1 脚本体以资源文件 {@code powershell/parse-script.ps1} 静态加载（单一真源）。
     */
    private String buildParseScript(String userCommand) {
        String encoded = Base64.getEncoder().encodeToString(userCommand.getBytes(StandardCharsets.UTF_8));
        return "$EncodedCommand = '" + encoded + "'\n" + PARSE_SCRIPT_BODY;
    }

    /** PS1 解析脚本体 · 对齐 CC parser.ts:315-568，资源文件 {@code powershell/parse-script.ps1} 静态加载。 */
    private static final String PARSE_SCRIPT_BODY = loadParseScriptBody();

    /**
     * [IMP-5 WinLen] 用户命令最大字节数（UTF-8）· 平台化预算。
     *
     * <p>Windows 按 CC parser.ts:611-630 公式从 Java 自身 {@link #PARSE_SCRIPT_BODY} 长度动态推导
     * （drift-prone 值是 Windows 预算，随脚本本体长度漂移，故从自身 bodyLen 推导，不照抄 CC 常量）；
     * Unix 固定 4500（CC :636）。平台选择 {@link PackageManagers#isWindows()}（对齐 CC
     * {@code process.platform === 'win32'}）。
     *
     * <p>声明在 {@link #PARSE_SCRIPT_BODY} 之后，保证静态字段初始化顺序正确（body 先加载，预算后推导）。
     */
    private static final int MAX_COMMAND_BYTES = PackageManagers.isWindows()
        ? windowsMaxCommandBytes(PARSE_SCRIPT_BODY.length())
        : UNIX_MAX_COMMAND_BYTES;

    /**
     * [IMP-5 WinLen] Windows 命令长度预算推导 · 对齐 CC parser.ts:622-630。
     *
     * <p>预算保证 {@code -EncodedCommand}（UTF-16LE base64 编码的
     * {@code "$EncodedCommand='<cmd-b64>'\n"} + 脚本本体）argv 总长不超过 Windows
     * CreateProcess 32K argv cap。单位 UTF-8 字节（门控用 {@code getBytes(UTF_8).length}
     * 对齐 CC {@code Buffer.byteLength}，非 code-unit 长度）。
     *
     * @param parseScriptBodyLength PS1 脚本本体字符长度（{@code PARSE_SCRIPT_BODY.length()}）
     * @return Windows 下最大命令字节数（下取整，下限 0）
     */
    static int windowsMaxCommandBytes(int parseScriptBodyLength) {
        double scriptCharsBudget = (WINDOWS_ARGV_CAP - FIXED_ARGV_OVERHEAD) * 3.0 / 8.0;
        double cmdB64Budget = scriptCharsBudget - parseScriptBodyLength - ENCODED_CMD_WRAPPER;
        int value = (int) Math.floor((cmdB64Budget * 3.0) / 4.0) - SAFETY_MARGIN;
        return Math.max(0, value);
    }

    /**
     * 从 classpath 资源加载脚本体（fail-loud：缺失/空抛 {@link IllegalStateException}，规则十二显式失败）。
     */
    private static String loadParseScriptBody() {
        try (java.io.InputStream in = PowerShellAstService.class.getClassLoader()
                .getResourceAsStream("powershell/parse-script.ps1")) {
            if (in == null) {
                log.error("PowerShellAstService: 解析脚本资源缺失 powershell/parse-script.ps1");
                throw new IllegalStateException("缺少 PowerShell 解析脚本资源 powershell/parse-script.ps1");
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                log.error("PowerShellAstService: 解析脚本资源为空 powershell/parse-script.ps1");
                throw new IllegalStateException("PowerShell 解析脚本资源为空 powershell/parse-script.ps1");
            }
            if (log.isDebugEnabled()) {
                log.debug("PowerShellAstService: 已加载解析脚本资源 powershell/parse-script.ps1 ({} 字符)", body.length());
            }
            return body;
        } catch (java.io.IOException e) {
            log.error("PowerShellAstService: 加载解析脚本资源失败: {}", e.getMessage());
            throw new IllegalStateException("加载 PowerShell 解析脚本资源失败", e);
        }
    }
}
