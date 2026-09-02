package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.infra.util.StringWidth;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 技能目录生成器 · 对齐 CC tools/SkillTool/prompt.ts formatCommandsWithinBudget()
 *
 * <h2>CC 对齐</h2>
 * <p>将技能列表格式化为模型可见的目录文本，受字符预算限制：
 * <table>
 *   <tr><th>CC 常量</th><th>值</th><th>Java 常量</th></tr>
 *   <tr><td>{@code SKILL_BUDGET_CONTEXT_PERCENT}</td><td>0.01 (1%)</td><td>{@link #SKILL_BUDGET_CONTEXT_PERCENT}</td></tr>
 *   <tr><td>{@code CHARS_PER_TOKEN}</td><td>4</td><td>{@link #CHARS_PER_TOKEN}</td></tr>
 *   <tr><td>{@code DEFAULT_CHAR_BUDGET}</td><td>8000</td><td>{@link #DEFAULT_CHAR_BUDGET}</td></tr>
 *   <tr><td>{@code MAX_LISTING_DESC_CHARS}</td><td>250</td><td>{@link #MAX_DESC_CHARS}</td></tr>
 * </table>
 *
 * <h2>格式化策略（对齐 CC prompt.ts:70-171）</h2>
 * <ol>
 *   <li>全部完整描述能放入预算 → 全部完整输出</li>
 *   <li>超出预算 → bundled 技能保持完整描述，user/plugin 技能截断描述</li>
 *   <li>极端情况 → 非 bundled 技能仅显示名称</li>
 * </ol>
 *
 * <p>[P2-21] 预算/截断改用 {@link StringWidth}（CC prompt.ts:85/:107/:118 stringWidth + truncate.ts
 * 宽度感知截断）——CJK 全宽字符计 2 终端列，使中文技能名/描述的字符预算与实际终端宽度一致
 * （旧 {@code String::length} 计宽把 CJK 算 1，预算被低估/截断位置偏后，自验 E1）。bundled 分区
 * 判别仍用 {@code source==='bundled'}（CC prompt.ts:97），非 loadedFrom。
 */
public class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);

    /** 技能清单预算占上下文窗口比例（1%）· CC original: SKILL_BUDGET_CONTEXT_PERCENT
     *  (tools/SkillTool/prompt.ts:21) */
    public static final double SKILL_BUDGET_CONTEXT_PERCENT = 0.01;

    /** 每 token 估算字符数 · CC original: CHARS_PER_TOKEN (tools/SkillTool/prompt.ts:22) */
    public static final int CHARS_PER_TOKEN = 4;

    /**
     * 默认字符预算（1% × 200k tokens × 4 chars/token）= 8000 chars · 对齐 CC DEFAULT_CHAR_BUDGET
     * (tools/SkillTool/prompt.ts:23)。
     *
     * <p>[P2-19] 现为 {@link #getCharBudget(Integer)} 的回落值（当 env 未设且 contextWindowTokens
     * 缺省时），而非唯一预算来源 —— 静态预算字面量已由动态计算取代。
     */
    public static final int DEFAULT_CHAR_BUDGET = 8_000;

    /** 单个条目描述最大字符数 · 对齐 CC MAX_LISTING_DESC_CHARS = 250 */
    public static final int MAX_DESC_CHARS = 250;

    /** 最短描述长度（低于此值则只显示名称）· 对齐 CC MIN_DESC_LENGTH = 20 */
    private static final int MIN_DESC_LENGTH = 20;

    /**
     * 截断遥测事件名 · CC original: {@code logEvent('tengu_skill_descriptions_truncated', {...})}
     * (tools/SkillTool/prompt.ts:126/:150)。
     */
    public static final String TENGU_SKILL_DESCRIPTIONS_TRUNCATED = "tengu_skill_descriptions_truncated";

    /** 截断模式 · CC prompt.ts:127 {@code truncation_mode: 'names_only'}（极端情况非 bundled 仅显示名称） */
    private static final String MODE_NAMES_ONLY = "names_only";

    /** 截断模式 · CC prompt.ts:152 {@code truncation_mode: 'description_trimmed'}（描述宽度截断） */
    private static final String MODE_DESCRIPTION_TRIMMED = "description_trimmed";

    private final SkillRegistry registry;

    /** 遥测 · CC original: logEvent('tengu_skill_descriptions_truncated')（prompt.ts:126/:150）.
     *  null（测试/未接线）→ 静默跳过，零行为变化（SkillChangeDetector.java:124-125 同款 null-safe）。 */
    private volatile Telemetry telemetry;

    /**
     * USER_TYPE==='ant' 判定 · CC original: {@code process.env.USER_TYPE === 'ant'}
     * （prompt.ts:125/:149 截断遥测门控）。默认读 {@code System.getenv("USER_TYPE")} 严格相等
     * （CC {@code ===} 语义，非 equalsIgnoreCase）；测试经 {@link #setUserTypeIsAnt} 注入覆盖
     * （System.getenv 在测试环境不可设值，CLAUDE.md 规则 9 抽注入缝）。
     */
    private volatile BooleanSupplier userTypeIsAnt = () -> isAntUser(System.getenv("USER_TYPE"));

    public SkillCatalog(SkillRegistry registry) {
        this.registry = registry;
    }

    /** 注入遥测（spring 可选接线，@Bean 实例同样经过 AutowiredAnnotationBeanPostProcessor）·
     *  参考 SkillChangeDetector.java:124-125 setTelemetry 先例。 */
    @Autowired(required = false)
    public void setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /** 注入 USER_TYPE==='ant' 判定（null → 恒 false，CC 非 ant 门控关闭）·
     *  参考 ExtractMemoriesAgent.java:242-245 setUserTypeIsAnt 先例。 */
    public void setUserTypeIsAnt(BooleanSupplier ant) {
        this.userTypeIsAnt = ant != null ? ant : () -> false;
    }

    /**
     * CC {@code process.env.USER_TYPE === 'ant'} 严格相等判定 · 纯谓词
     * （CLAUDE.md 规则 9：System.getenv 测试环境不可设值，抽 package-private 纯函数，镜像
     * {@link #parseEnvBudget} 测试模式）。
     *
     * @param userType env USER_TYPE 原始值；null/非 "ant" → false
     * @return 恰为 "ant" 时为 true
     */
    static boolean isAntUser(String userType) {
        return "ant".equals(userType);
    }

    /**
     * [P1-10] 暴露 model-invocable 命令全量列表 · 对齐 CC {@code getSkillToolCommands(cwd)} +
     * {@code getMcpSkillCommands} 合并后的 allCommands（attachments.ts:2680-2683）。
     *
     * <p><b>WHY</b>: computeSkillListingDelta 需要以 {@code List<Command>} 作 dedup 源（按 name），
     * 不再经 buildCatalog() 文本中转（旧 isSkillCatalogAlreadySent 存 catalogText.hashCode() 语义已废弃）。
     *
     * @return registry 过滤后的 model-invocable 命令列表（buildCatalog 同源）
     */
    public List<Command> getModelInvocableCommands() {
        return registry.getModelInvocableCommands();
    }

    /**
     * [P2-9] 暴露 model-invocable 命令 + MCP 技能的 listing 合并视图 · CC original:
     * {@code getSkillListingAttachments} attachments.ts:2677-2682
     * {@code uniqBy([...localCommands, ...mcpSkills], 'name')}。
     *
     * <p><b>WHY</b>: computeSkillListingDelta 的 skill_listing 注入源（LlmAgentLoop:2213）——
     * CC 本地命令经 getSkillToolCommands（commands.ts:563-581），MCP 技能经 getMcpSkillCommands
     * （commands.ts:547-559）thread-in 合并（MCP live outside getCommands）。旧实现只注入
     * {@link #getModelInvocableCommands()}（纯本地，MCP 缺失，偏离 CC attachments.ts:2680-2683）。
     *
     * @return 本地模型可调用命令 + MCP 技能的合并列表（本地优先，按 name 去重）；MCP 未注入
     *         （mcpServerService null / gate 关）时退回纯本地视图
     */
    public List<Command> getModelInvocableCommandsForListing() {
        return registry.getModelInvocableCommandsForListing();
    }

    /**
     * 生成模型可见的技能目录 · 对齐 CC formatCommandsWithinBudget()
     *
     * <p>[P1-10] 保留供 X20 Haiku 摘要 / 其他消费方（对全量 model-invocable 命令取预算内文本）。
     *
     * <p>[P2-19] 动态预算：contextWindowTokens 缺省 → {@link #getCharBudget(Integer)} 回落
     * DEFAULT_CHAR_BUDGET（语义 = CC DEFAULT_CHAR_BUDGET 回退分支）。
     *
     * @return 格式化的目录文本（不超过字符预算）
     */
    public String buildCatalog() {
        return buildCatalog(getCharBudget(null));
    }

    /**
     * 使用模型上下文窗口计算预算后生成目录 · CC original: formatCommandsWithinBudget 委托
     * getCharBudget(contextWindowTokens) (tools/SkillTool/prompt.ts:70-76)。
     *
     * @param contextWindowTokens 模型上下文窗口 tokens（可 null；null → 回落 DEFAULT_CHAR_BUDGET）
     */
    public String buildCatalog(Integer contextWindowTokens) {
        return formatListing(registry.getModelInvocableCommands(), getCharBudget(contextWindowTokens));
    }

    /**
     * 计算技能清单字符预算 · CC original: getCharBudget(contextWindowTokens?)
     * (tools/SkillTool/prompt.ts:31-41)。
     *
     * <p>三分支对齐 CC：
     * <ol>
     *   <li>env {@code SLASH_COMMAND_TOOL_CHAR_BUDGET} 解析为非零整数 → 直接返回（CC prompt.ts:32-34
     *       {@code Number(env)} truthy 语义：0/空白/NaN 落穿）</li>
     *   <li>{@code contextWindowTokens} 非 null 且 > 0 → {@code floor(tokens × 4 × 0.01)}
     *       （CC prompt.ts:35-39）</li>
     *   <li>否则 → {@link #DEFAULT_CHAR_BUDGET}（CC prompt.ts:40）</li>
     * </ol>
     *
     * @param contextWindowTokens 模型上下文窗口 tokens（可选；null/≤0 落穿）
     * @return 字符预算
     */
    public int getCharBudget(Integer contextWindowTokens) {
        Integer envBudget = parseEnvBudget(System.getenv("SLASH_COMMAND_TOOL_CHAR_BUDGET"));
        if (envBudget != null) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillCatalog] getCharBudget env 优先: SLASH_COMMAND_TOOL_CHAR_BUDGET={} → budget={}",
                    System.getenv("SLASH_COMMAND_TOOL_CHAR_BUDGET"), envBudget);
            }
            return envBudget;
        }
        if (contextWindowTokens != null && contextWindowTokens > 0) {
            int budget = (int) Math.floor(contextWindowTokens * CHARS_PER_TOKEN * SKILL_BUDGET_CONTEXT_PERCENT);
            if (log.isDebugEnabled()) {
                log.debug("[SkillCatalog] getCharBudget 动态计算: contextWindowTokens={} → budget={}",
                    contextWindowTokens, budget);
            }
            return budget;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillCatalog] getCharBudget 回落默认: contextWindowTokens={} → DEFAULT_CHAR_BUDGET={}",
                contextWindowTokens, DEFAULT_CHAR_BUDGET);
        }
        return DEFAULT_CHAR_BUDGET;
    }

    /**
     * 解析 {@code SLASH_COMMAND_TOOL_CHAR_BUDGET} env 值 · CC original: prompt.ts:32-33
     * {@code Number(process.env.SLASH_COMMAND_TOOL_CHAR_BUDGET)} truthy 语义。
     *
     * <p><b>P2-5 浮点/十六进制对齐</b>（EV-WF2-SA-020 △-2 修复）：CC {@code Number("500.5")=500.5}
     * truthy 直接返回浮点预算；旧 Java {@code Integer.parseInt("500.5")} 抛异常 → 落穿下一分支。
     * 现按 JS {@code Number()} 可解析格式对齐：
     * <ul>
     *   <li>十进制整数/浮点/科学计数法（{@code "500"} / {@code "500.5"} / {@code "1e3"}）→
     *       {@code Double.parseDouble}，非零 → {@code (int)} 截断（JS 预算为浮点；Java
     *       {@link #getCharBudget} 返回 int，截断向零 —— 因下游 fullTotal/availableForDescs 均
     *       为整数值，整数比较下 500.5 与 500 语义等价）</li>
     *   <li>十六进制（{@code "0x1F4"}=500）、二进制（{@code "0b101"}=5）、八进制（{@code "0o17"}=15）→
     *       按对应进制解析（CC {@code Number()} 同支持）</li>
     *   <li>{@code null}/空白/0/0x0 → null（CC Number() 对 0/NaN 为 falsy 落穿）</li>
     * </ul>
     *
     * <p><b>WHY</b>（CLAUDE.md 规则 9）: System.getenv 在测试环境不可设值，解析逻辑抽离为
     * package-private 纯函数，建立稳定 GREEN（SkillCatalogBudgetTest）。
     *
     * @param raw env 原始值；null/空白/0/无效 → null（CC Number() falsy 落穿）
     * @return 非零预算解析值（浮点/进制值截断为 int）；无效 → null
     */
    static Integer parseEnvBudget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            double value;
            if (s.startsWith("0x") || s.startsWith("0X")) {
                // CC Number("0x1F4")=500 —— Integer.decode 支持 0x 前缀（含负数 -0x）
                value = Integer.decode(s).intValue();
            } else if (s.startsWith("0b") || s.startsWith("0B")) {
                value = Integer.parseInt(s.substring(2), 2);
            } else if (s.startsWith("0o") || s.startsWith("0O")) {
                value = Integer.parseInt(s.substring(2), 8);
            } else {
                // CC Number("500.5")=500.5 / Number("1e3")=1000 —— Double.parseDouble 同语义
                value = Double.parseDouble(s);
            }
            // CC Number("NaN")=NaN falsy → 落穿（Double.parseDouble("NaN")=NaN 且 NaN!=0 为 true，
            // 不加守卫会误返 (int)NaN=0）
            if (Double.isNaN(value)) {
                return null;
            }
            return value != 0 ? (int) value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * [P1-10] 对 newSkills 子集取预算内清单文本 · 对齐 CC {@code formatCommandsWithinBudget(newSkills, contextWindowTokens)}
     * (tools/SkillTool/prompt.ts:70-134) + attachments.ts:2741。
     *
     * <p><b>WHY</b>: skill_listing attachment 只对 {@code computeSkillListingDelta} 算出的增量子集
     * (newSkills) 渲染，不再对全量 catalog 渲染（P1-10 dedup 语义）。复用 buildTruncatedCatalog 的
     * bundled 保完整 / 其余截断逻辑（CC formatCommandsWithinBudget 同源）。
     *
     * <p>[P2-19] contextWindowTokens 缺省 → 经 {@link #getCharBudget(Integer)} 回落 DEFAULT_CHAR_BUDGET
     * （对齐 CC prompt.ts:70-74 缺省分支）。
     *
     * @param newSkills 增量技能子集（按 name 去重后的新技能）
     * @return 预算内清单文本；空/ null → 空串
     */
    public String formatListing(List<Command> newSkills) {
        return formatListing(newSkills, getCharBudget(null));
    }

    /**
     * 以模型上下文窗口为入参对子集取清单文本 · CC original:
     * {@code formatCommandsWithinBudget(commands, contextWindowTokens?)}
     * (tools/SkillTool/prompt.ts:70-76)。委托 {@link #getCharBudget(Integer)} 计算预算。
     *
     * @param newSkills          增量技能子集
     * @param contextWindowTokens 模型上下文窗口 tokens（可 null；null → 回落 DEFAULT_CHAR_BUDGET）
     */
    public String formatListing(List<Command> newSkills, Integer contextWindowTokens) {
        return formatListing(newSkills, getCharBudget(contextWindowTokens));
    }

    /**
     * 使用指定预算对子集取清单文本 · {@link #formatListing(List)} 的预算重载（内部核心）。
     *
     * @param newSkills  增量技能子集
     * @param charBudget 字符预算上限
     */
    public String formatListing(List<Command> newSkills, int charBudget) {
        if (newSkills == null || newSkills.isEmpty()) return "";

        // 1. 计算完整描述的总宽度（终端列 · P2-21 stringWidth，CC prompt.ts:84-86
        //    fullTotal = sum(stringWidth(full)) + (N-1) newlines）
        List<String> fullEntries = newSkills.stream()
            .map(this::formatEntry)
            .toList();

        int fullTotal = fullEntries.stream().mapToInt(StringWidth::stringWidth).sum();
        fullTotal += Math.max(0, fullEntries.size() - 1);  // newlines

        if (fullTotal <= charBudget) {
            return String.join("\n", fullEntries);
        }

        // 2. 超出预算：bundled 保持完整，其余截断
        // fullTotal 传入供截断遥测 full_total 字段（CC prompt.ts:130/:153，格式化为字符串后无法
        // 可靠反算 → 源头传递）
        return buildTruncatedCatalog(newSkills, fullEntries, charBudget, fullTotal);
    }

    /**
     * 生成截断目录 — bundled 技能完整，其余按比例截断
     *
     * @param fullTotal 全量清单宽度（sum(stringWidth(full)) + (N-1) 换行），供截断遥测 full_total
     *                  字段（CC prompt.ts:130/:153）
     */
    private String buildTruncatedCatalog(List<Command> commands, List<String> fullEntries, int budget,
                                         int fullTotal) {
        StringBuilder sb = new StringBuilder();

        // 计算 bundled 占用的宽度与数量（P2-21 stringWidth + 1 for newline · CC prompt.ts:105-109
        //   bundledChars = sum(stringWidth(full)+1)；bundled_count = bundledIndices.size()（CC :134/:161））
        int bundledChars = 0;
        int bundledCount = 0;
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).getSource() == CommandSource.BUNDLED) {
                bundledChars += StringWidth.stringWidth(fullEntries.get(i)) + 1;  // +1 for newline
                bundledCount++;
            }
        }

        int remainingBudget = budget - bundledChars;

        // 过滤非 bundled 命令
        List<Integer> restIndices = new java.util.ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).getSource() != CommandSource.BUNDLED) {
                restIndices.add(i);
            }
        }

        if (restIndices.isEmpty()) {
            // 只有 bundled 技能
            return String.join("\n", fullEntries);
        }

        // 计算剩余技能可用预算（P2-21 stringWidth(name)+4 · CC prompt.ts:117-119
        //   restNameOverhead = sum(stringWidth(name)+4) + (N-1) newlines）
        int restNameOverhead = restIndices.stream()
            .mapToInt(i -> StringWidth.stringWidth(commands.get(i).getName()) + 4)
            .sum();
        restNameOverhead += Math.max(0, restIndices.size() - 1);

        int availableForDescs = remainingBudget - restNameOverhead;
        int maxDescLen = availableForDescs / restIndices.size();

        if (maxDescLen < MIN_DESC_LENGTH) {
            // 极端情况：非 bundled 只显示名称（CC prompt.ts:123-141 names_only 分支）
            if (log.isDebugEnabled()) {
                log.debug("[SkillCatalog] Names-only mode: budget={}, total={}, maxDescLen={}",
                    budget, restIndices.size(), maxDescLen);
            }
            // [ALIGN-CATALOG-1] 截断遥测 · CC prompt.ts:125-139（USER_TYPE==='ant' 门控 :125）
            if (userTypeIsAnt.getAsBoolean()) {
                emitDescriptionsTruncated(commands.size(), budget, fullTotal, MODE_NAMES_ONLY,
                    maxDescLen, 0, bundledCount, bundledChars);
            }
            for (int i = 0; i < commands.size(); i++) {
                if (sb.length() > 0) sb.append("\n");
                if (commands.get(i).getSource() == CommandSource.BUNDLED) {
                    sb.append(fullEntries.get(i));
                } else {
                    sb.append("- ").append(commands.get(i).getName());
                }
            }
        } else {
            // 截断描述（CC prompt.ts:143-170 description_trimmed 分支）
            // truncated_count · CC prompt.ts:145-147
            //   count(restCommands, cmd => stringWidth(getCommandDescription(cmd)) > maxDescLen)
            int truncatedCount = 0;
            for (int idx : restIndices) {
                if (StringWidth.stringWidth(getDescription(commands.get(idx))) > maxDescLen) {
                    truncatedCount++;
                }
            }
            // [ALIGN-CATALOG-1] 截断遥测 · CC prompt.ts:149-171（USER_TYPE==='ant' 门控 :149）
            if (userTypeIsAnt.getAsBoolean()) {
                emitDescriptionsTruncated(commands.size(), budget, fullTotal, MODE_DESCRIPTION_TRIMMED,
                    maxDescLen, truncatedCount, bundledCount, bundledChars);
            }
            for (int i = 0; i < commands.size(); i++) {
                if (sb.length() > 0) sb.append("\n");
                Command cmd = commands.get(i);
                if (cmd.getSource() == CommandSource.BUNDLED) {
                    sb.append(fullEntries.get(i));
                } else {
                    String desc = getDescription(cmd);
                    sb.append("- ").append(cmd.getName()).append(": ").append(truncate(desc, maxDescLen));
                }
            }
        }

        return sb.toString();
    }

    /**
     * 发射 {@code tengu_skill_descriptions_truncated} 遥测 · CC original:
     * {@code logEvent('tengu_skill_descriptions_truncated', {...})}（prompt.ts:126 names_only /
     * :150 description_trimmed）。字段名与 CC 逐项对齐（snake_case；names_only 模式不携带
     * truncated_count，与 CC :126-138 载荷一致）。
     *
     * <p>null-safe：telemetry 未注入 → 跳过（对齐 CC logEvent 可空上下文，不破坏主链）。
     *
     * @param skillCount     skill_count = commands.length（CC :128/:151）
     * @param budget         budget（CC :129/:152）
     * @param fullTotal      full_total（CC :130/:153）
     * @param truncationMode truncation_mode（CC :131-133/:154-156）
     * @param maxDescLen     max_desc_length（CC :134/:157）
     * @param truncatedCount truncated_count（CC :157，仅 description_trimmed 模式携带）
     * @param bundledCount   bundled_count（CC :135/:159）
     * @param bundledChars   bundled_chars（CC :136/:160）
     */
    private void emitDescriptionsTruncated(int skillCount, int budget, int fullTotal, String truncationMode,
                                           int maxDescLen, int truncatedCount, int bundledCount,
                                           int bundledChars) {
        if (telemetry == null) {
            return;
        }
        java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put("skill_count", skillCount);
        attrs.put("budget", budget);
        attrs.put("full_total", fullTotal);
        attrs.put("truncation_mode", truncationMode);
        attrs.put("max_desc_length", maxDescLen);
        if (MODE_DESCRIPTION_TRIMMED.equals(truncationMode)) {
            attrs.put("truncated_count", truncatedCount);
        }
        attrs.put("bundled_count", bundledCount);
        attrs.put("bundled_chars", bundledChars);
        telemetry.recordEvent(TENGU_SKILL_DESCRIPTIONS_TRUNCATED, attrs);
        if (log.isDebugEnabled()) {
            log.debug("[SkillCatalog] 遥测: tengu_skill_descriptions_truncated mode={} skill_count={} "
                    + "budget={} full_total={} max_desc_length={} bundled_count={} bundled_chars={} "
                    + "(CC tools/SkillTool/prompt.ts:126/:150)",
                truncationMode, skillCount, budget, fullTotal, maxDescLen, bundledCount, bundledChars);
        }
    }

    /**
     * 格式化单个条目 · 对齐 CC formatCommandDescription()（tools/SkillTool/prompt.ts:52-63）。
     *
     * <p>[P3-1] 补 plugin 命令 userFacingName 与 name 偏差的 debug 日志 —— CC prompt.ts:56-60
     * {@code cmd.name !== displayName && cmd.type === 'prompt' && cmd.source === 'plugin'} →
     * {@code logForDebugging(`Skill prompt: showing "${cmd.name}" (userFacingName="${displayName}")`)}。
     * 返回文本 {@code - name: desc} 不变。
     */
    private String formatEntry(Command cmd) {
        if (isPluginUserFacingNameMismatch(cmd)) {
            if (log.isDebugEnabled()) {
                log.debug("技能清单 plugin 命令显示名偏差: showing \"{}\" (userFacingName=\"{}\") "
                        + "(CC tools/SkillTool/prompt.ts:52-63)",
                    cmd.getName(), cmd.userFacingName());
            }
        }
        return "- " + cmd.getName() + ": " + getDescription(cmd);
    }

    /**
     * [P3-1] 是否 plugin 命令且 userFacingName 与 name 偏差 · 纯谓词。
     *
     * <p>对齐 CC prompt.ts:56-60 判定：{@code cmd.name !== displayName && cmd.type === 'prompt' &&
     * cmd.source === 'plugin'}。抽为 package-private static 便于 RED→GREEN 单测直接断言
     * （镜像 {@link SkillCatalogBudgetTest#parseEnvBudget} 的抽纯函数测试模式）。
     *
     * @param cmd 待判定的命令
     * @return plugin 源 + prompt 类型 + userFacingName != name 时为 true
     */
    static boolean isPluginUserFacingNameMismatch(Command cmd) {
        String displayName = cmd.userFacingName();
        return !cmd.getName().equals(displayName)
            && "prompt".equals(cmd.getType())
            && cmd.getSource() == CommandSource.PLUGIN;
    }

    /** 获取显示用描述 · 对齐 CC getCommandDescription() */
    private String getDescription(Command cmd) {
        String desc = cmd.getWhenToUse() != null
            ? cmd.getDescription() + " - " + cmd.getWhenToUse()
            : cmd.getDescription();
        if (desc != null && desc.length() > MAX_DESC_CHARS) {
            return desc.substring(0, MAX_DESC_CHARS - 1) + "\u2026";  // ellipsis
        }
        return desc != null ? desc : "";
    }

    /**
     * 截断文本到最大宽度 · 对齐 CC truncate()（utils/truncate.ts:134-158 宽度感知 + … 后缀）。
     *
     * <p>[P2-21] 由 {@code String::substring} 字符截断改为 {@link StringWidth#truncateToWidth}
     * 宽度感知截断（code point 遍历累计终端列宽，CJK 全宽计 2，超 {@code maxLen-1} 截断 + '…'，
     * 不劈代理对）——中文技能描述截断后宽度不再超出预算。
     */
    static String truncate(String s, int maxLen) {
        return StringWidth.truncateToWidth(s, maxLen);
    }
}
