package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 对齐 CC toolExecution.ts:761-793 — defense-in-depth + backfill。
 *
 * <h2>职责</h2>
 * <ol>
 *   <li><b>§3.4 defense-in-depth</b>：仅对 Bash 剥离模型不应提供的内部字段
 *       {@code _simulatedSedEdit}（CC toolExecution.ts:762-773）。该字段是权限系统内部注入
 *       （SedEditPermissionRequest），模型若自行提供即为注入攻击面；剥离防止意外行为。</li>
 *   <li><b>§3.5 backfillObservableInput</b>：调用工具的 {@link Tool#backfillObservableInput(JsonNode)}
 *       补全遗留字段（如 file_path 展开），<b>仅用于观察/hook/审计</b>，
 *       不传给 {@code tool.call()}（保护 prompt cache 一致性）。</li>
 * </ol>
 *
 * <h2>[P4 · OPD-WF4-BC-04 拍板：按 CC 收敛]</h2>
 * <p>CC 真源（toolExecution.ts:762-773）<b>仅对 Bash 剥 {@code _simulatedSedEdit}</b>；
 * 旧 Java 实现的 {@code _internal}/{@code __} 前缀 + 全工具范围是超剥（⊕，CC 无对应），
 * 拍板移除。非 Bash 工具输入不再剥任何字段。
 *
 * <h2>使用场景</h2>
 * <p>在 {@link com.nexusai.application.agent.tool.StreamingToolExecutor#executeAsync} 中，
 * 工具执行前调用 {@link #stripInternalFields}；权限检查时调用 {@link #backfill}
 * 生成 observable input 供日志/审计使用。
 *
 * @see Tool#backfillObservableInput(JsonNode)
 */
@Component
public class InputSanitizer {

    private static final Logger log = LoggerFactory.getLogger(InputSanitizer.class);

    /** CC BASH_TOOL_NAME（tools/BashTool/toolName.ts:2 {@code 'Bash'}）。 */
    private static final String BASH_TOOL_NAME = "Bash";

    /** CC 内部字段 · toolExecution.ts:762-767 仅对 Bash 剥 {@code _simulatedSedEdit}。 */
    private static final String SIMULATED_SED_EDIT_FIELD = "_simulatedSedEdit";

    /**
     * 剥离模型不应提供的内部字段（§3.4 defense-in-depth）· 对齐 CC toolExecution.ts:762-773。
     *
     * <p>CC 真源（toolExecution.ts:762-773）：仅当 {@code tool.name === BASH_TOOL_NAME}
     * 且 input 含 {@code _simulatedSedEdit} 时，用解构剥掉该字段：
     * {@code const { _simulatedSedEdit: _, ...rest } = processedInput; processedInput = rest}。
     * <ul>
     *   <li><b>仅 Bash</b>：非 Bash 工具输入原样返回（CC 无全工具剥离）</li>
     *   <li><b>仅 {@code _simulatedSedEdit}</b>：{@code _internal}/{@code __} 前缀不再剥
     *       （[P4] 旧 Java 超剥移除）</li>
     * </ul>
     *
     * <h2>性能优化</h2>
     * <p>如果字段不存在，直接返回原引用（避免不必要的 deepCopy）；仅剥离时返回新 JsonNode
     * （不修改原对象）。
     *
     * @param toolName 工具名（CC 主名；仅 {@code Bash} 触发剥离）
     * @param input    LLM 输出的原始 input
     * @return 剥离后的 input（新 JsonNode，不修改原对象）；null / 非 object / 非 Bash / 无该字段原样返回
     */
    public JsonNode stripInternalFields(String toolName, JsonNode input) {
        if (input == null || !input.isObject() || !BASH_TOOL_NAME.equals(toolName)) {
            return input;
        }
        if (!input.has(SIMULATED_SED_EDIT_FIELD)) {
            return input;
        }
        ObjectNode result = input.deepCopy();
        result.remove(SIMULATED_SED_EDIT_FIELD);
        if (log.isDebugEnabled()) {
            log.debug("InputSanitizer: 剥离 Bash 输入中的 _simulatedSedEdit（CC toolExecution.ts:762-773 defense-in-depth）");
        }
        return result;
    }

    /**
     * 调用 tool.backfillObservableInput() 补全遗留字段（§3.5）。
     *
     * <p>对齐 CC toolExecution.ts:784-793 的 backfill 逻辑：
     * <ul>
     *   <li>补全后的 input 仅用于观察/hook/审计（<b>不传给 tool.call()</b>，保护
     *       prompt cache 一致性）</li>
     *   <li>CC 在浅克隆 {@code {...processedInput}} 上回填（原 processedInput 不动），
     *       Java 端用 {@link JsonNode#deepCopy()} 做防御性隔离，即使未来某 override
     *       试图 in-place 改动，原 input 也不被污染（对齐 CC Tool.ts:475-484
     *       "original API-bound input is never mutated" 契约）</li>
     * </ul>
     *
     * <p>接线点：{@link com.nexusai.application.agent.tool.StreamingToolExecutor#executeAsync}
     * 在字段剥离（stripInternalFields）之后、PreToolUse hook 串联之前调用本方法，把
     * backfilled input 传给 hook（防 ~/相对路径绕过 hook allowlist）。tool.execute 仍用
     * 原始 {@code t.call.input()}（CC callInput 不突变契约）。
     *
     * @param tool  工具实例（null → 直接返回 input）
     * @param input LLM 输出的原始 input（或 stripInternalFields 后的 input）
     * @return 补全后的 input（防御性 deepCopy，原引用不被改动）
     */
    public JsonNode backfill(Tool tool, JsonNode input) {
        if (tool == null || input == null) {
            return input;
        }
        // 防御性 deepCopy：对齐 CC 浅克隆语义（toolExecution.ts:784-787），确保工具
        // backfillObservableInput 的 in-place 改动（若某 override 复刻 CC mutate 语义）
        // 不污染原始 input。
        JsonNode copy = input.deepCopy();
        JsonNode backfilled = tool.backfillObservableInput(copy);
        if (log.isDebugEnabled()) {
            log.debug("InputSanitizer.backfill: tool={} backfilled={} (CC toolExecution.ts:784-793 浅克隆回填)",
                tool.name(), backfilled != null && backfilled != copy);
        }
        return backfilled != null ? backfilled : copy;
    }
}
