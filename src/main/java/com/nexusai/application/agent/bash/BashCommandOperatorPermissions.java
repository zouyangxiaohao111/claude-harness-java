package com.nexusai.application.agent.bash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.PermissionUpdates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BashTool command operator permissions · 对齐 CC tools/BashTool/bashCommandHelpers.ts.
 *
 * <p>CC source: tools/BashTool/bashCommandHelpers.ts (265 LOC).
 * 3 main exports:
 * - checkCommandOperatorPermissions (input, hasPermissionFn, checkers, astRoot)
 *   Handles segmented commands: unsafe-compound (subshell/command group) → ask,
 *   multiple cd commands, cross-segment cd+git, per-segment permission checks.
 * - buildSegmentWithoutRedirections (strip output redirections while preserving quotes)
 * - CommandIdentityCheckers (isNormalizedCdCommand, isNormalizedGitCommand)
 *
 * <p>返回值/入参统一使用
 * {@link com.nexusai.application.agent.permission.PermissionResult}（CC 原名:
 * PermissionResult @ Open-ClaudeCode/src/types/permissions.ts:251）。
 * 决策归因 {@code subcommandResults} 用
 * {@link PermissionDecisionReason.SubcommandResults}
 * （CC 原名: decisionReason.type='subcommandResults' @ bashCommandHelpers.ts:107/117/137）。
 * allow 的 updatedInput 用 {@code {command: 首段}} 近似
 * （CC 原名: updatedInput @ bashCommandHelpers.ts:125；Java 签名无完整 input，
 * A3/A4 接线如需完整 input 再改签名）。
 *
 * <p>A4 对齐（bashCommandHelpers.ts:181-265）：
 * <ul>
 *   <li>补 unsafe-compound（CC :216-240）：subshell {@code (cmd)} / command group
 *       {@code { cmd; }} → ask。Java 无 tree-sitter，用结构扫描近似
 *       {@code compoundStructure.hasSubshell || hasCommandGroup}（排除 $( / &lt;( / (( 算术）。</li>
 *   <li>{@link #buildSegmentWithoutRedirections} 引号感知剥离（CC :163-174 用
 *       ParsedCommand.withoutOutputRedirections 保引号），不再用字符串 indexOf 简化。</li>
 *   <li>重定向剥离在 multi-cd 检查之前（CC :243-256 先 strip 再进
 *       segmentedCommandPermissionResult :31-47 的 multi-cd）。</li>
 *   <li>跨段 cd+git 用真实 {@link BashParser#splitCommands}（CC :59 splitCommand_DEPRECATED）。</li>
 *   <li>deny 逐段早退闭合（CC :98-116 全段求值后才查 deny，不逐段 return）。</li>
 * </ul>
 */
public final class BashCommandOperatorPermissions {

    private static final Logger log = LoggerFactory.getLogger(BashCommandOperatorPermissions.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 复合命令聚合建议规则上限 · 对齐 CC {@code MAX_SUGGESTED_RULES_FOR_COMPOUND = 5}
     * （bashPermissions.ts:110）。GH#11380 聚合去重后保序取最左 N 条。
     */
    private static final int MAX_SUGGESTED_RULES_FOR_COMPOUND = 5;

    /** CC CommandIdentityCheckers. */
    public record CommandIdentityCheckers(
        Predicate<String> isNormalizedCdCommand,
        Predicate<String> isNormalizedGitCommand
    ) {}

    /** CC hasPermissionFn signature. */
    @FunctionalInterface
    public interface HasPermissionFn {
        PermissionResult check(Map<String, Object> input);
    }

    /**
     * CC checkCommandOperatorPermissions（bashCommandHelpers.ts:181-265 简化：无
     * ParsedCommand/tree-sitter，用 Java 结构扫描近似）。
     *
     * @param command       原始 bash 命令（unsafe-compound 需要完整命令做结构扫描）
     * @param hasPermissionFn 逐段权限判定（CC 递归 bashToolHasPermission 等价）
     * @param checkers      cd/git 命令识别器
     */
    public PermissionResult checkCommandOperatorPermissions(
        String command,
        HasPermissionFn hasPermissionFn,
        CommandIdentityCheckers checkers
    ) {
        if (command == null || command.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("bash 复合命令为空, passthrough");
            }
            return new PermissionResult.Passthrough("No command to check", null, List.of(), null, null);
        }

        // Check 0: unsafe-compound — subshell / command group → ask（CC bashCommandHelpers.ts:216-240）
        if (isUnsafeCompound(command)) {
            String reason = "This command uses shell operators that require approval for safety";
            if (log.isDebugEnabled()) {
                log.debug("bash 复合命令含子shell/命令组, 返回 ask: [{}]", abbreviate(command));
            }
            return new PermissionResult.Ask(reason,
                new PermissionDecisionReason.Other(reason),
                List.of(), null, null, null, false, null, List.of());
        }

        // Check 1: pipe segments（CC :242-251 getPipeSegments，保留引号）
        List<String> pipeSegments = getPipeSegments(command);
        if (pipeSegments.size() <= 1) {
            if (log.isDebugEnabled()) {
                log.debug("bash 复合命令无管道, passthrough: [{}]", abbreviate(command));
            }
            return new PermissionResult.Passthrough("No pipes found in command", null, List.of(), null, null);
        }

        // Check 2: 先剥离重定向（保引号），再进 multi-cd（CC :253-256 → :31-47 时序）
        List<String> segments = new ArrayList<>();
        for (String seg : pipeSegments) {
            segments.add(buildSegmentWithoutRedirections(seg));
        }

        // Check 3: multiple cd commands across segments → ask（CC bashCommandHelpers.ts:31-47）
        long cdCount = segments.stream()
            .map(String::trim)
            .filter(s -> checkers.isNormalizedCdCommand().test(s))
            .count();
        if (cdCount > 1) {
            String reason = "Multiple directory changes in one command require approval for clarity";
            if (log.isDebugEnabled()) {
                log.debug("bash 复合命令含多个 cd, 返回 ask: [{}]", abbreviate(command));
            }
            return new PermissionResult.Ask(reason,
                new PermissionDecisionReason.Other(reason),
                List.of(), null, null, null, false, null, List.of());
        }

        // Check 4: cross-segment cd+git（CC bashCommandHelpers.ts:49-80，每段再按真实
        // splitCommand 拆子命令，防 "cd sub && echo | git status" 绕检）
        boolean hasCd = false, hasGit = false;
        for (String seg : segments) {
            for (String sub : BashParser.splitCommands(seg)) {
                String trimmed = sub.trim();
                if (checkers.isNormalizedCdCommand().test(trimmed)) hasCd = true;
                if (checkers.isNormalizedGitCommand().test(trimmed)) hasGit = true;
            }
        }
        if (hasCd && hasGit) {
            String reason = "Compound commands with cd and git require approval to prevent bare repository attacks";
            if (log.isDebugEnabled()) {
                log.debug("bash 复合命令跨段 cd+git, 返回 ask: [{}]", abbreviate(command));
            }
            return new PermissionResult.Ask(reason,
                new PermissionDecisionReason.Other(reason),
                List.of(), null, null, null, false, null, List.of());
        }

        // Check 5: per-segment permission check（CC bashCommandHelpers.ts:84-96）。
        // [A4 deny 早退闭合] CC :98-116 全段求值后统一查 deny，不逐段早退（全段求值保留
        // decisionReason.subcommandResults 完整归因）。
        Map<String, PermissionResult> results = new LinkedHashMap<>();
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) continue;
            Map<String, Object> input = Map.of("command", trimmed);
            results.put(trimmed, hasPermissionFn.check(input));
        }

        // 全段求值后查 deny（CC :98-116）
        for (Map.Entry<String, PermissionResult> e : results.entrySet()) {
            if (e.getValue() instanceof PermissionResult.Deny deny) {
                if (log.isDebugEnabled()) {
                    log.debug("bash 管道段被拒绝, 返回 deny: seg=[{}]", abbreviate(e.getKey()));
                }
                return new PermissionResult.Deny(deny.message(),
                    new PermissionDecisionReason.SubcommandResults(results), null);
            }
        }

        // Check if all allowed（CC :118-127）
        boolean allAllowed = results.values().stream()
            .allMatch(r -> r instanceof PermissionResult.Allow);
        if (allAllowed) {
            // CC 返回 updatedInput=input；Java 无 input 结构，用 {command: 首段} 近似
            String firstSegment = segments.get(0).trim();
            return new PermissionResult.Allow(
                MAPPER.valueToTree(Map.of("command", firstSegment)),
                new PermissionDecisionReason.SubcommandResults(results),
                null, false, null, List.of());
        }

        // Default: ask（CC :145-155）
        // [GAP-2] 聚合所有子命令的 addRules 建议规则 · 对齐 CC bashPermissions.ts:2472-2556
        // （extractRules 去重 + GH#28784 ask 兜底 suggestionForExactCommand + GH#11380 capped，
        //  打包为单个 addRules(localSettings, allow) 建议）。
        List<PermissionUpdate> suggestedUpdates = collectSubcommandRuleSuggestions(results);
        return new PermissionResult.Ask(
            "Permission check requires user approval",
            new PermissionDecisionReason.SubcommandResults(results),
            suggestedUpdates, null, null, null, false, null, List.of());
    }

    /**
     * 聚合所有子命令的建议规则 · 对齐 CC {@code bashPermissions.ts:2472-2556}
     * （compound ask 分支的 {@code collectedRules} 循环）。
     *
     * <p><b>CC 语义（读源码，非注释）</b>：
     * <ol>
     *   <li>对每个 {@code ask} / {@code passthrough} 子命令结果，{@code extractRules(suggestions)}
     *       抽出 {@code addRules} 型规则（其余 update 类型忽略），按
     *       {@link com.nexusai.application.agent.permission.PermissionRuleValue#toRuleString()}
     *       字符串化去重（对齐 CC {@code permissionRuleValueToString}，Map.set 覆盖语义）。</li>
     *   <li>GH#28784 兜底：{@code ask} 且无规则且 {@code decisionReason.type !== 'rule'}
     *       （Java {@code !(reason instanceof PermissionDecisionReason.Rule)}）→
     *       {@link BashRuleMatcher#suggestionForExactCommand} 补一条 Bash(exact/prefix) 规则，
     *       让 UI 显示被阻断的链式命令（跳过用户刻意每次复查的显式 ask 规则）。</li>
     *   <li>GH#11380 capped：{@link #MAX_SUGGESTED_RULES_FOR_COMPOUND}=5，保序取最左 N。</li>
     *   <li>打包为单个 {@code addRules(localSettings, allow)} 建议（CC :2527-2537，
     *       destination=localSettings）。</li>
     * </ol>
     *
     * <p><b>WHY 增强建议而非替换决策</b>：本方法只填充 Ask 的 {@code suggestions} 字段，
     * 不改 {@code behavior=ask} / {@code decisionReason=SubcommandResults} 归因——聚合是
     * 建议层增强，权限决策仍由上层管线按各子命令 ask/passthrough 结果裁决。
     *
     * @param results 子命令 → PermissionResult（subcommandResults 归因，键为 trim 后的段）
     * @return 聚合后的建议更新（无规则 → 空列表）
     */
    private List<PermissionUpdate> collectSubcommandRuleSuggestions(Map<String, PermissionResult> results) {
        // 去重键 = ruleValue.toRuleString()（对齐 CC permissionRuleValueToString）。
        // LinkedHashMap 保序（子命令顺序，对齐 CC Map 插入序，cap 时保留最左 N）。
        LinkedHashMap<String, PermissionRule> collectedRules = new LinkedHashMap<>();
        for (Map.Entry<String, PermissionResult> entry : results.entrySet()) {
            String subcommand = entry.getKey();
            PermissionResult result = entry.getValue();
            if (result instanceof PermissionResult.Ask ask) {
                List<PermissionRule> rules = PermissionUpdates.extractRules(ask.suggestions());
                for (PermissionRule rule : rules) {
                    collectedRules.put(rule.ruleValue().toRuleString(), rule);
                }
                // GH#28784 兜底：ask 且无规则且非 rule 归因 → suggestionForExactCommand（CC :2499-2510）
                if (rules.isEmpty() && !(ask.reason() instanceof PermissionDecisionReason.Rule)) {
                    for (PermissionRule rule : PermissionUpdates.extractRules(
                            BashRuleMatcher.suggestionForExactCommand(subcommand))) {
                        collectedRules.put(rule.ruleValue().toRuleString(), rule);
                    }
                }
            } else if (result instanceof PermissionResult.Passthrough passthrough) {
                for (PermissionRule rule : PermissionUpdates.extractRules(passthrough.suggestions())) {
                    collectedRules.put(rule.ruleValue().toRuleString(), rule);
                }
            }
        }

        // GH#11380 capped：保序取最左 N（CC :2523-2526）
        List<PermissionRule> cappedRules = collectedRules.values().stream()
            .limit(MAX_SUGGESTED_RULES_FOR_COMPOUND)
            .toList();
        if (cappedRules.isEmpty()) {
            return List.of();
        }
        if (log.isDebugEnabled()) {
            log.debug("Bash 复合命令子命令规则聚合: 去重后 {} 条, capped 后 {} 条（CC bashPermissions.ts:2472-2556）",
                collectedRules.size(), cappedRules.size());
        }
        return List.of(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.LOCAL_SETTINGS, cappedRules, PermissionBehavior.ALLOW));
    }

    /**
     * CC buildSegmentWithoutRedirections（bashCommandHelpers.ts:163-174）。引号感知扫描，
     * 剥离 {@code > file} / {@code >> file} / {@code 2> file} / {@code 2>&1} 及目标，
     * 引号内 {@code >} 是字面量（如 {@code echo "a>b"}）不剥离。
     */
    public String buildSegmentWithoutRedirections(String segmentCommand) {
        if (segmentCommand == null) return null;
        // Fast path（CC :167-169）
        if (!segmentCommand.contains(">")) return segmentCommand;

        StringBuilder sb = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        int n = segmentCommand.length();
        for (int i = 0; i < n; i++) {
            char c = segmentCommand.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                sb.append(c);
                continue;
            }
            if (inDouble) {
                if (c == '"') inDouble = false;
                sb.append(c);
                continue;
            }
            if (c == '\'' && !inDouble) { inSingle = true; sb.append(c); continue; }
            if (c == '"' && !inSingle) { inDouble = true; sb.append(c); continue; }
            if (c == '\\' && i + 1 < n) { sb.append(c).append(segmentCommand.charAt(++i)); continue; }
            if (c == '>') {
                // 剥离 FD 前缀（2> 的 2）：sb 尾部若为数字且前有空白/开头，一并删除
                int last = sb.length() - 1;
                if (last >= 0 && Character.isDigit(sb.charAt(last))) {
                    int j = last;
                    while (j >= 0 && Character.isDigit(sb.charAt(j))) j--;
                    if (j < 0 || Character.isWhitespace(sb.charAt(j))) {
                        sb.setLength(j + 1);
                    }
                }
                // 跳过 > 与可选的第二个 >（>>）
                i++;
                if (i < n && segmentCommand.charAt(i) == '>') i++;
                // 跳过空白与目标 token（简单目标无引号）
                while (i < n && Character.isWhitespace(segmentCommand.charAt(i))) i++;
                while (i < n && !Character.isWhitespace(segmentCommand.charAt(i))) i++;
                i--;
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * 引号感知 pipe 段切分：顶层单 {@code |} 切段，{@code ||} 视为 or-logic 不切
     * （对齐 CC ParsedCommand.getPipeSegments）。子shell 深度内不切。
     */
    static List<String> getPipeSegments(String command) {
        List<String> result = new ArrayList<>();
        if (command == null) return result;
        int depth = 0;
        boolean inSingle = false, inDouble = false;
        StringBuilder cur = new StringBuilder();
        int n = command.length();
        for (int i = 0; i < n; i++) {
            char c = command.charAt(i);
            if (inSingle) { if (c == '\'') inSingle = false; cur.append(c); continue; }
            if (inDouble) { if (c == '"') inDouble = false; cur.append(c); continue; }
            if (c == '\'' && !inDouble) { inSingle = true; cur.append(c); continue; }
            if (c == '"' && !inSingle) { inDouble = true; cur.append(c); continue; }
            if (c == '\\' && i + 1 < n) { cur.append(c).append(command.charAt(++i)); continue; }
            if (c == '(') { depth++; cur.append(c); continue; }
            if (c == ')') { if (depth > 0) depth--; cur.append(c); continue; }
            if (c == '|' && depth == 0) {
                if (i + 1 < n && command.charAt(i + 1) == '|') { cur.append("||"); i++; continue; }
                if (cur.length() > 0) { result.add(cur.toString().trim()); cur.setLength(0); }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) result.add(cur.toString().trim());
        return result.stream().filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 结构扫描近似 CC tree-sitter compoundStructure（bashCommandHelpers.ts:218-221）：
     * subshell {@code (cmd)} / command group {@code { cmd; }} → true（unsafe）。
     *
     * <p>排除项（对齐 tree-sitter 语义）：
     * <ul>
     *   <li>{@code $(...)} 命令替换（prev '$'）</li>
     *   <li>{@code <(...)} 进程替换（prev '&lt;'）</li>
     *   <li>{@code ((...))} 算术（prev '(' 或下一字符 '('）</li>
     *   <li>brace expansion {@code echo {a,b}}（{ 非命令起始位置）</li>
     * </ul>
     */
    static boolean isUnsafeCompound(String command) {
        if (command == null || command.isEmpty()) return false;
        int depth = 0;
        boolean inSingle = false, inDouble = false;
        int n = command.length();
        char prev = 0;
        for (int i = 0; i < n; i++) {
            char c = command.charAt(i);
            if (inSingle) { if (c == '\'') inSingle = false; prev = c; continue; }
            if (inDouble) { if (c == '"') inDouble = false; prev = c; continue; }
            if (c == '\'' && !inDouble) { inSingle = true; prev = c; continue; }
            if (c == '"' && !inSingle) { inDouble = true; prev = c; continue; }
            if (c == '\\' && i + 1 < n) { i++; prev = 0; continue; }
            if (c == '(' && depth == 0) {
                // $( 命令替换 / <( 进程替换 / (( 算术 → 非子shell
                boolean commandSubst = prev == '$' || prev == '<' || prev == '(';
                boolean arithmetic = (i + 1 < n && command.charAt(i + 1) == '(');
                if (!commandSubst && !arithmetic) {
                    if (log.isDebugEnabled()) {
                        log.debug("bash 检测到子shell (…), unsafe-compound 命中");
                    }
                    return true;
                }
                depth++;
                prev = c;
                continue;
            }
            if (c == ')') { if (depth > 0) depth--; prev = c; continue; }
            if (c == '{' && depth == 0) {
                // 命令组 { cmd; } — { 处于命令起始位置（前非空白字符为空或分隔符）
                int j = i - 1;
                while (j >= 0 && Character.isWhitespace(command.charAt(j))) j--;
                boolean atCommandStart = j < 0 || ";|&({".indexOf(command.charAt(j)) >= 0;
                if (atCommandStart) {
                    if (log.isDebugEnabled()) {
                        log.debug("bash 检测到命令组 {{ … }}, unsafe-compound 命中");
                    }
                    return true;
                }
                prev = c;
                continue;
            }
            prev = c;
        }
        return false;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() > 60 ? s.substring(0, 60) + "..." : s);
    }
}
