package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.permission.PermissionRuleValue;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Rule 字符串解析器 · 对齐 CC {@code utils/permissions/permissionRuleParser.ts:93-198}
 *
 * <h2>支持的字符串格式</h2>
 * <ul>
 *   <li>{@code "Bash"} — 整个工具（无内容限定）</li>
 *   <li>{@code "Bash(npm publish:*)"} — 工具 + 命令前缀（CC 命令前缀匹配用 {@code :} 分隔）</li>
 *   <li>{@code "Edit(/Users/foo/**)"} — 工具 + 路径 glob</li>
 *   <li>{@code "mcp__server__tool"} — MCP 工具（双下划线分隔）</li>
 *   <li>{@code "Bash\\(escaped\\)"} — 转义括号（罕见，工具名含括号时用）</li>
 * </ul>
 *
 * <h2>转义规则（与序列化对称）</h2>
 * <p>{@link PermissionRuleValue#toRuleString()} 序列化顺序：
 * <ol>
 *   <li>{@code \} → {@code \\}</li>
 *   <li>{@code (} → {@code \(}</li>
 *   <li>{@code )} → {@code \)}</li>
 * </ol>
 *
 * <p>本解析器反序列化顺序（与序列化对称）：
 * <ol>
 *   <li>{@code \(} → {@code (}</li>
 *   <li>{@code \)} → {@code )}</li>
 *   <li>{@code \\} → {@code \}</li>
 * </ol>
 *
 * <p>注意反序列化的最后一步是 {@code \\} → {@code \}，这样之前反转义产生的 {@code \}
 * 不会被二次反转义。CC 解析器也是这样做的（{@code permissionRuleParser.ts:30-37}）。
 *
 * <h2>语法解析算法</h2>
 * <ol>
 *   <li>若字符串不含 {@code (} → 整个工具（无 content）</li>
 *   <li>否则找<b>第一个未转义的 {@code (}</b>，以此切分 toolName / content</li>
 *   <li>找<b>最后一个未转义的 {@code )}</b>（应对内容中含括号的情况）</li>
 *   <li>提取 content 部分并反转义</li>
 * </ol>
 *
 * <h2>legacy tool name aliases</h2>
 * <p>对齐 CC {@code permissionRuleParser.ts:31-33}。CC 早期用过几个工具名，后来重命名，
 * settings.json 中可能保留旧名 → 解析时统一替换为 canonical 名：
 * <ul>
 *   <li>{@code Task} → {@code Agent}（CC 旧版 Task 工具被 Agent 取代）</li>
 *   <li>{@code KillShell} → {@code TaskStop}（CC 改名）</li>
 *   <li>{@code AgentOutputTool} → {@code TaskOutput}（CC 别名，permissionRuleParser.ts:24）</li>
 *   <li>{@code BashOutputTool} → {@code TaskOutput}（CC 别名，permissionRuleParser.ts:25）</li>
 *   <li>{@code WebFetch} → {@code WebFetch}（CC 占位，未来可能重命名）——O12 已删</li>
 * </ul>
 *
 * <h2>无状态 / 线程安全</h2>
 * <p>{@link #LEGACY_TO_CANONICAL} 是不可变 {@link Map}，所有方法无副作用。可作 Spring 单例。
 *
 * <h2>Phase 2 简化</h2>
 * <p>当前只解析 rule 字符串，不实现 ruleContent 的 glob/regex 匹配（CC
 * {@code matchesContent} 在 Phase 3 加）。本解析器的输出是
 * {@link PermissionRuleValue}，实际匹配交给 PermissionPipeline。
 *
 * @see PermissionRuleValue#toRuleString()
 */
@Component
public class PermissionRuleValueParser {

    /**
     * legacy tool name → canonical name 映射（CC {@code permissionRuleParser.ts:31-33}）。
     *
     * <p>用 {@link Map#of} 不可变 map（最多 10 entry 限制未触达），保证线程安全。
     *
     * <p><b>O12 删除说明（S13）</b>: KillBash→TaskStop 条目已删——CC 全仓 0 命中；
     * WebFetch→WebFetch 自映射已删（identity 无行为，映射无意义）。
     *
     * <p><b>补登说明（S13 r1，OPD-PERM-10「补登缺失」）</b>: 补齐
     * AgentOutputTool→TaskOutput / BashOutputTool→TaskOutput 两条——CC
     * {@code LEGACY_TOOL_NAME_ALIASES}（permissionRuleParser.ts:21-29）含
     * Task/KillShell/AgentOutputTool/BashOutputTool（+KAIROS 门控 Brief）；
     * canonical {@code "TaskOutput"} = CC {@code TASK_OUTPUT_TOOL_NAME}
     * （tools/TaskOutputTool/constants.ts:1）= Java {@code TaskOutputTool.NAME}
     * （tool/impl/TaskOutputTool.java:28），已核一致。
     */
    private static final Map<String, String> LEGACY_TO_CANONICAL = Map.of(
        "Task", "Agent",
        "KillShell", "TaskStop",
        "AgentOutputTool", "TaskOutput",
        "BashOutputTool", "TaskOutput"
    );

    /**
     * canonical → legacy 反向映射（CC permissionRuleParser.ts:35-41）
     * 用于序列化时显示用户可读的 legacy 名称。
     */
    private static final Map<String, List<String>> CANONICAL_TO_LEGACY;

    static {
        Map<String, List<String>> reverse = new HashMap<>();
        reverse.put("Agent", List.of("Task"));
        reverse.put("TaskStop", List.of("KillShell"));
        reverse.put("TaskOutput", List.of("AgentOutputTool", "BashOutputTool"));
        CANONICAL_TO_LEGACY = Collections.unmodifiableMap(reverse);
    }

    /**
     * 解析 rule 字符串（如 {@code "Bash(npm publish:*)"}）。
     *
     * <p><b>S02 对齐 CC {@code permissionRuleValueFromString}（permissionRuleParser.ts:93-133）</b>：
     * malformed 规则<b>不丢弃</b>——整串作 toolName（死规则，恒非 null），与 CC 死规则语义一致
     * （CC :113 "Content after closing paren - treat as tool name"）。无括号分支<b>不 trim</b>
     * （" Bash" 保留空白当死规则，CC :100）。
     *
     * <h3>解析规则（CC :96-132 逐分支）</h3>
     * <ol>
     *   <li>无未转义 {@code (} → 整串作 toolName（CC :98-101，不 trim）</li>
     *   <li>无闭合 {@code )} 或 {@code )} 在 {@code (} 之前 → 整串作 toolName（CC :105-108）</li>
     *   <li>闭合 {@code )} 不在末尾 → 整串作 toolName（CC :111-114，死规则）</li>
     *   <li>{@code (} 前无工具名（如 {@code "(foo)"}）→ 整串作 toolName（CC :120-122）</li>
     *   <li>空 content 或 {@code "*"} → wholeTool（CC :126-128）</li>
     *   <li>其余 → withContent（CC :130-132）</li>
     * </ol>
     *
     * <h3>参数校验（Java 防御边界）</h3>
     * <p>{@code null} / 空白字符串 → 返回 {@code null}（fail soft）。CC 无 null 处理
     * （TS 类型保证非 null）；空白串 CC 会产出 {@code toolName:""} 死规则，但 Java
     * {@link PermissionRuleValue} 构造校验拒绝空白 toolName（DM-PERM-RP-06 保留），
     * 故以 null 跳过——与 CC 死规则均无运行时效果，语义等价。
     *
     * @param ruleString 原始 rule 字符串
     * @return 解析结果；null 仅用于 null/空白输入（fail soft）
     */
    public PermissionRuleValue parse(String ruleString) {
        if (ruleString == null || ruleString.isBlank()) {
            return null;
        }

        // 1. 找第一个未转义 (（CC :97-101）
        int openParen = findFirstUnescapedChar(ruleString, '(');
        if (openParen < 0) {
            // 无括号 → 整串作 toolName，不 trim（CC :100）
            return PermissionRuleValue.wholeTool(normalizeToolName(ruleString));
        }

        // 2. 找最后一个未转义 )（CC :103-108）
        int closeParen = findLastUnescapedChar(ruleString, ')');
        if (closeParen < 0 || closeParen <= openParen) {
            // 无闭合 ) / 顺序错误 / 缺失 → 整串作 toolName（CC :105-108）
            return PermissionRuleValue.wholeTool(normalizeToolName(ruleString));
        }

        // 3. 闭合 ) 必须在末尾（CC :110-114）——否则整串作 toolName（死规则）
        if (closeParen != ruleString.length() - 1) {
            return PermissionRuleValue.wholeTool(normalizeToolName(ruleString));
        }

        // 4. 提取 toolName + content（CC :116-117，不 trim）
        String toolName = normalizeToolName(ruleString.substring(0, openParen));
        if (toolName.isEmpty()) {
            // 形如 "(content)" —— 缺工具名，整串作 toolName（CC :119-122）
            return PermissionRuleValue.wholeTool(normalizeToolName(ruleString));
        }
        String content = unescapeRuleContent(
            ruleString.substring(openParen + 1, closeParen)
        );
        // 空 content 或 "*" → wholeTool（允许整个工具，CC :124-128）
        if (content.isEmpty() || "*".equals(content)) {
            return PermissionRuleValue.wholeTool(toolName);
        }
        return PermissionRuleValue.withContent(toolName, content);
    }

    /**
     * 获取 canonical 名称对应的 legacy 别名列表。
     *
     * @param canonicalName canonical 工具名
     * @return legacy 别名列表（可能为空）
     */
    public List<String> getLegacyToolNames(String canonicalName) {
        return CANONICAL_TO_LEGACY.getOrDefault(canonicalName, List.of());
    }

    /**
     * 把 legacy tool name 标准化为 canonical name · 对齐 CC
     * {@code permissionRuleParser.ts:31-33} {@code normalizeLegacyToolName}.
     *
     * <p>WHY (H1): {@link com.nexusai.application.agent.permission.hook.HookMatcherEngine}
     * 在 matchesPattern / if 条件比较时需要把 settings 中可能保留的 legacy 名 (如 "Task")
     * 归一化后再与事件工具名比较. 原 {@link #normalizeToolName(String)} 为 private,
     * 这里公开一个 CC 同名等价方法 (委托给原私有实现, 不重复映射表).
     *
     * @param name 原始 tool name
     * @return canonical name (不在映射表中原样返回; null → null)
     */
    public String normalizeLegacyToolName(String name) {
        return normalizeToolName(name);
    }

    /**
     * 把 legacy tool name 标准化为 canonical name。
     *
     * <p>不在映射表中的名字原样返回（不区分大小写 —— CC 也是大小写敏感）。
     *
     * @param name 原始 tool name
     * @return canonical name
     */
    private String normalizeToolName(String name) {
        return LEGACY_TO_CANONICAL.getOrDefault(name, name);
    }

    /**
     * 找第一个<b>未转义</b>的 {@code target} 字符索引。
     *
     * <p><b>[s03 P2 #5 修补]</b> 对齐 CC {@code permissionRuleParser.ts:158-175}
     * {@code findFirstUnescapedChar} — 计 {@code consecutiveBackslashes}，奇偶判定。
     * 修补前用 boolean {@code escaped} flag 状态机；当前用 int counter，逻辑等价
     * （两种实现数学上等价），但与 CC 结构一致 + 意图更明确（"连续反斜杠数"）。
     *
     * <p>{@code \} 是转义符 —— 跳过下一个字符的字面意义。
     * 形如 {@code "Bash\(foo"} 找 {@code (} 返回 {@code -1}（括号被转义）。
     *
     * @param s      源字符串
     * @param target 目标字符（如 {@code (}）
     * @return 第一个匹配索引；未找到返回 {@code -1}
     */
    private int findFirstUnescapedChar(String s, char target) {
        int consecutiveBackslashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                consecutiveBackslashes++;
            } else {
                // 偶数个连续反斜杠 = 未转义，奇数 = 已转义（对齐 CC % 2 == 0）
                if (c == target && consecutiveBackslashes % 2 == 0) {
                    return i;
                }
                consecutiveBackslashes = 0;
            }
        }
        return -1;
    }

    /**
     * 找最后一个<b>未转义</b>的 {@code target} 字符索引。
     *
     * <p><b>[s03 P2 #5 修补]</b> 对齐 CC {@code permissionRuleParser.ts} 算法族——
     * 计 {@code consecutiveBackslashes} + 奇偶判定，替换 boolean flag 状态机。
     *
     * <p>用于定位闭合括号 —— 内容中可能含 {@code )}（需转义），但闭合括号本身
     * 在最末尾。例如 {@code "Bash(echo \\) foo)"} 应该返回最后那个 {@code )}。
     *
     * <p>WHY 从左向右扫追踪 lastMatch 而非从右向左：右向左扫描时 {@code \}
     * 转义符的方向判断极易出错（{@code \} 在其右侧字符之前，但反向扫描时变成
     * "向后看"，语义混乱）。从左向右用与 {@link #findFirstUnescapedChar} 相同的
     * 转义逻辑追踪最后一个匹配位置，逻辑正确且无 corner case。
     *
     * <p>CC 源码从右向左扫是因为 TypeScript 中 {@code \} 转义判断方向更灵活；
     * Java 中 String.charAt 无状态，左→右扫描更清晰。
     *
     * @param s      源字符串
     * @param target 目标字符（如 {@code )}）
     * @return 最后一个未转义匹配索引；未找到返回 {@code -1}
     */
    private int findLastUnescapedChar(String s, char target) {
        int lastMatch = -1;
        int consecutiveBackslashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                consecutiveBackslashes++;
            } else {
                if (c == target && consecutiveBackslashes % 2 == 0) {
                    lastMatch = i;
                }
                consecutiveBackslashes = 0;
            }
        }
        return lastMatch;
    }

    /**
     * 反转义 rule content。
     *
     * <p>顺序很重要 —— <b>必须先反转义括号，再反转义反斜杠</b>。
     * 这是因为如果先反转义 {@code \\}，会把 {@code \(} 错误地变成 {@code (}
     * （吃掉反斜杠）。
     *
     * <p>正确顺序（对齐 CC {@code permissionRuleParser.ts:30-37}）：
     * <ol>
     *   <li>{@code \(} → {@code (}</li>
     *   <li>{@code \)} → {@code )}</li>
     *   <li>{@code \\} → {@code \}</li>
     * </ol>
     *
     * @param content 原始（含转义符的）content
     * @return 反转义后的 content
     */
    private String unescapeRuleContent(String content) {
        return content
            .replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\\\", "\\");
    }
}