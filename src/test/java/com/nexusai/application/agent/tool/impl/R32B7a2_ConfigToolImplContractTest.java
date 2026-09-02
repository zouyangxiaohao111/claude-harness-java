package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ConfigToolNames;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7a-2 · Phase 1 · ConfigToolImpl Tool 接口契约验证.
 *
 * <p><b>WHY (意图验证)</b>: ConfigToolImpl 是 LLM 可见的工具入口, 必须严格遵守 CC
 * {@code tools.ts:ConfigTool} Tool 协议 (name/schema/permission/execute). 这些契约测试
 * 不依赖任何 Spring 上下文 — 纯单元测试快速反馈:
 * <ul>
 *   <li>{@code name() = "Config"} — 对齐 CC tools/ConfigTool/constants.ts</li>
 *   <li>inputSchema 是 strict object (additionalProperties=false, setting 必填)</li>
 *   <li>outputSchema 含 success/operation/setting/value/previousValue/newValue/error</li>
 *   <li>isConcurrencySafe=true (CC 显式声明; 内部并发安全由 ConfigStorage 锁保证)</li>
 *   <li>isReadOnly(input) = !input.has("value") — GET/SET 区分核心</li>
 *   <li>shouldDefer=true — CC ConfigTool.ts:446 (等待挂起工具执行完后再应用)</li>
 *   <li>maxResultSizeChars = 100000 — 阈值足够展示完整 model options</li>
 *   <li>checkPermissions: GET → Allow, SET → Ask (含 setting 名)</li>
 *   <li>execute() 依赖未注入时 fail loud 返回 ToolResult.error</li>
 *   <li>execute() 输入校验失败 (setting 缺失) → ToolResult.error</li>
 * </ul>
 *
 * <p>此测试不验证 execute() 完整路径 — 完整路径在 {@code R32B7a2_ConfigToolImplExecuteTest}
 * 中验证 (需要 mock SupportedSettings + ConfigStorage).
 *
 * @see ConfigToolImpl
 */
class R32B7a2_ConfigToolImplContractTest {

    /** 无注入的 ConfigToolImpl — Phase 1 skeleton (Phase 2/3/4 setter 之前). */
    private final ConfigToolImpl tool = new ConfigToolImpl();

    @Test
    @DisplayName("name() 返回 'Config' (对齐 CC constants.ts CONFIG_TOOL_NAME)")
    void nameReturnsConfig() {
        assertThat(tool.name())
            .as("CC tools/ConfigTool/constants.ts:1 — CONFIG_TOOL_NAME = 'Config'")
            .isEqualTo(ConfigToolNames.CONFIG_TOOL_NAME)
            .isEqualTo("Config");
    }

    @Test
    @DisplayName("description() 不为 null (prompt 注入前降级 static DESCRIPTION)")
    void descriptionNotNull() {
        String desc = tool.description();
        // WHY: 即使无 ConfigToolPrompt 注入, 也必须返回非 null description —
        // Tool.description() 在 ToolRegistry 注册时使用, null 会破坏 tool list 渲染.
        assertThat(desc)
            .as("Phase 1 skeleton: 无 ConfigToolPrompt 注入时降级 static DESCRIPTION")
            .isNotNull()
            .isNotBlank();
    }

    @Test
    @DisplayName("inputSchema() 返回 strict object schema (setting 必填, additionalProperties=false)")
    void inputSchemaIsStrictObject() {
        // WHY: LLM 按 schema 生成 tool call; strict 模式防止 LLM 加额外字段导致
        // 未知 setting 被静默忽略
        JsonNode schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.isObject()).isTrue();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean())
            .as("CC strict mode = true; LLM 不允许额外字段")
            .isFalse();

        JsonNode properties = schema.get("properties");
        assertThat(properties).isNotNull();
        assertThat(properties.has("setting")).isTrue();
        assertThat(properties.has("value")).isTrue();
        assertThat(properties.get("setting").get("type").asText()).isEqualTo("string");
        assertThat(properties.get("value").get("type").asText()).isEqualTo("string");

        JsonNode required = schema.get("required");
        assertThat(required).isNotNull();
        assertThat(required.isArray()).isTrue();
        assertThat(required).contains(JsonNodeFactory.instance.textNode("setting"));
    }

    @Test
    @DisplayName("outputSchema() 含 success/operation/setting/value/previousValue/newValue/error")
    void outputSchemaHasAllKeys() {
        // WHY: LLM 解析 tool result 时按 outputSchema 结构提取字段; 缺字段会让 LLM
        // 看不见 SET 的 previousValue → 用户误以为"原始值丢失"
        JsonNode schema = tool.outputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.isObject()).isTrue();
        assertThat(schema.get("type").asText()).isEqualTo("object");

        JsonNode props = schema.get("properties");
        assertThat(props.has("success")).as("success").isTrue();
        assertThat(props.has("operation")).as("operation").isTrue();
        assertThat(props.has("setting")).as("setting").isTrue();
        assertThat(props.has("value")).as("value").isTrue();
        assertThat(props.has("previousValue")).as("previousValue").isTrue();
        assertThat(props.has("newValue")).as("newValue").isTrue();
        assertThat(props.has("error")).as("error").isTrue();
    }

    @Test
    @DisplayName("isConcurrencySafe(input) 永远 true (CC ConfigTool.ts:437 显式声明)")
    void isConcurrencySafeAlwaysTrue() {
        // WHY: CC ConfigTool.ts:437 显式 isConcurrencySafe=true.
        // Java 端保留该语义; 真正的并发安全由 ConfigStorage 内部 ReentrantLock 保证
        // (Phase 3). 此处验证接口契约 — 即使无 lock, Tool 协议层声明安全.
        assertThat(tool.isConcurrencySafe(null)).isTrue();
        assertThat(tool.isConcurrencySafe(JsonNodeFactory.instance.objectNode())).isTrue();
    }

    @Test
    @DisplayName("isReadOnly(input) = !input.has('value') (GET/SET 区分)")
    void isReadOnlyDependsOnValuePresence() {
        // WHY: GET (无 value) → 只读, LLM 自动允许; SET (有 value) → 写,
        // 必须 Ask 用户确认. 这是 checkPermissions 分支判断的核心.
        assertThat(tool.isReadOnly(null))
            .as("null input 视为 GET → 只读 (defensive)")
            .isTrue();

        JsonNode getInput = JsonNodeFactory.instance.objectNode().put("setting", "theme");
        assertThat(tool.isReadOnly(getInput))
            .as("GET: 无 value → 只读")
            .isTrue();

        JsonNode setInput = JsonNodeFactory.instance.objectNode()
            .put("setting", "theme")
            .put("value", "dark");
        assertThat(tool.isReadOnly(setInput))
            .as("SET: 有 value → 写")
            .isFalse();
    }

    @Test
    @DisplayName("shouldDefer(input) 永远 true (CC ConfigTool.ts:446)")
    void shouldDeferAlwaysTrue() {
        // WHY: CC ConfigTool.ts:446 shouldDefer=true — 等待挂起工具执行完后再应用.
        // Java 端 LlmAgentLoop 据此把 ConfigTool 推迟到 abort 触发的并发工具之后.
        assertThat(tool.shouldDefer(null)).isTrue();
        assertThat(tool.shouldDefer(JsonNodeFactory.instance.objectNode())).isTrue();
    }

    @Test
    @DisplayName("maxResultSizeChars() = 100000 (对齐 CC ConfigTool.ts:466)")
    void maxResultSizeCharsIsOneHundredK() {
        // WHY: CC ConfigTool.ts:466 maxResultSizeChars=100_000 — 高阈值便于展示
        // 完整 model options 列表. 此处锁定数值防止后续修改破坏 LLM truncation.
        assertThat(tool.maxResultSizeChars())
            .isEqualTo(100_000L);
    }

    @Test
    @DisplayName("checkPermissions(GET) → Allow (auto-allow)")
    void checkPermissionsGetAllows() {
        // WHY: CC ConfigTool.ts:411 GET 自动允许; 用户不会因查看配置被弹窗打扰
        JsonNode getInput = JsonNodeFactory.instance.objectNode().put("setting", "theme");
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT);

        PermissionResult result = tool.checkPermissions(getInput, ctx);
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        assertThat(allow.updatedInput()).isEqualTo(getInput);
        assertThat(allow.reason()).isNotNull();
    }

    @Test
    @DisplayName("checkPermissions(SET) → Ask (含 setting 名)")
    void checkPermissionsSetAsks() {
        // WHY: SET 改变后续 LLM 行为 (model/permissions/mode), 影响面大;
        // 必须 Ask 用户确认. message 必须含 setting 名, 引导用户理解变更.
        JsonNode setInput = JsonNodeFactory.instance.objectNode()
            .put("setting", "permissions.defaultMode")
            .put("value", "plan");
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT);

        PermissionResult result = tool.checkPermissions(setInput, ctx);
        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask = (PermissionResult.Ask) result;
        assertThat(ask.message())
            .as("Ask message 必须引用 setting 名, 引导用户理解变更")
            .contains("permissions.defaultMode");
        assertThat(ask.reason()).isNotNull();
    }

    @Test
    @DisplayName("execute() 输入校验失败 (setting 缺失) → ToolResult.error")
    void executeFailsWhenSettingMissing() {
        // WHY: LLM 可能构造残缺 tool call; 必须 fail loud 拒绝执行, 不静默 no-op
        // 误导 LLM. 输入缺 setting 时直接返回 error, 不进入 coreConfigTool.call().
        ToolUseBlock call = new ToolUseBlock("call-bad-1", "Config",
            JsonNodeFactory.instance.objectNode());
        AgentToolResult result = tool.execute(call);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("setting 缺失 → isError=true")
            .isTrue();
        assertThat(tr.data())
            .as("error content 必须说明 missing setting (CLAUDE.md 规则 12 Fail loud)")
            .contains("setting");
    }

    @Test
    @DisplayName("execute() 依赖未注入时 fail loud 返回明确错误 (不静默 no-op)")
    void executeFailsLoudWhenDependenciesNotInjected() {
        // WHY: Phase 1 skeleton 无 SupportedSettings/ConfigStorage 注入 —
        // 不能静默吞错或返回 fake success. CLAUDE.md 规则 12 Fail loud:
        // 错误必须明确说"ConfigTool 未完成接线"让运维/LLM 知道是配置问题.
        ToolUseBlock call = new ToolUseBlock("call-skel-1", "Config",
            JsonNodeFactory.instance.objectNode().put("setting", "theme"));
        AgentToolResult result = tool.execute(call);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<String> tr = (ToolResult<String>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("skeleton execute → fail loud (不静默吞错)")
            .isTrue();
        assertThat(tr.data())
            .containsIgnoringCase("未完成接线")
            .contains("theme");
    }

    @Test
    @DisplayName("renderToolUseMessage() / toAutoClassifierInput() 在 null 输入时优雅降级")
    void renderHelpersHandleNullInput() {
        // WHY: Tool.execute/render 路径在多种 context 触发, null 输入不应 NPE —
        // 直接 return name() 给上层 fallback 用
        assertThat(tool.renderToolUseMessage(null)).isNull();
        assertThat(tool.toAutoClassifierInput(null)).isEqualTo("Config");
    }

    @Test
    @DisplayName("renderToolUseMessage() 区分 GET / SET")
    void renderToolUseMessageDistinguishesGetAndSet() {
        // WHY: 用户看到的工具消息区分读 / 写, 让用户一目了然变更类型
        JsonNode getInput = JsonNodeFactory.instance.objectNode().put("setting", "theme");
        assertThat(tool.renderToolUseMessage(getInput))
            .as("GET 渲染 → 读取配置 ...")
            .contains("读取")
            .contains("theme");

        JsonNode setInput = JsonNodeFactory.instance.objectNode()
            .put("setting", "theme")
            .put("value", "dark");
        assertThat(tool.renderToolUseMessage(setInput))
            .as("SET 渲染 → 写入配置 ... = ...")
            .contains("写入")
            .contains("theme")
            .contains("dark");
    }

    @Test
    @DisplayName("toAutoClassifierInput() 区分 get / set")
    void autoClassifierInputDistinguishesGetAndSet() {
        // WHY: auto-classifier 用此文本判断工具风险级别 — SET 必须显式标记为 set,
        // 否则分类器误判为只读, 跳过权限检查
        JsonNode getInput = JsonNodeFactory.instance.objectNode().put("setting", "theme");
        assertThat(tool.toAutoClassifierInput(getInput))
            .contains("Config")
            .contains("get")
            .contains("theme");

        JsonNode setInput = JsonNodeFactory.instance.objectNode()
            .put("setting", "theme")
            .put("value", "dark");
        assertThat(tool.toAutoClassifierInput(setInput))
            .contains("Config")
            .contains("set")
            .contains("theme");
    }

    @Test
    @DisplayName("isReadOnly(input) 边界: setting 存在 + value=null → 视为 SET (写 JSON null)")
    void isReadOnlyWhenValueIsExplicitJsonNull() {
        // WHY: null 与 absent 不同 — {"setting":"theme","value":null} 显式写 null
        // (JSON null), 仍是写操作. 不可漏写把"显式置空"误判为 GET.
        ObjectNode explicitNull = JsonNodeFactory.instance.objectNode()
            .put("setting", "theme");
        explicitNull.putNull("value");
        assertThat(tool.isReadOnly(explicitNull))
            .as("value 字段存在 (即使是 null) → SET")
            .isFalse();
    }

    @Test
    @DisplayName("inputSchema 的 setting 描述提示用户如何填")
    void inputSchemaDescriptionHintsUsage() {
        JsonNode schema = tool.inputSchema();
        JsonNode settingProp = schema.get("properties").get("setting");
        assertThat(settingProp.get("description").asText())
            .as("setting 描述必须给 LLM 用法提示 (required, 例子)")
            .containsIgnoringCase("setting")
            .containsIgnoringCase("required");

        JsonNode valueProp = schema.get("properties").get("value");
        assertThat(valueProp.get("description").asText())
            .as("value 描述必须提示: 省略 = GET")
            .containsIgnoringCase("value")
            .containsIgnoringCase("Omit");
    }

    @Test
    @DisplayName("permission 检查中 reason 是 PermissionDecisionReason.Other (CC other 类型)")
    void permissionReasonIsOther() {
        // WHY: checkPermissions 返回的 reason 在审计日志里展示 — 必须归类到
        // PermissionDecisionReason 的合法子类, 不能 null
        JsonNode getInput = JsonNodeFactory.instance.objectNode().put("setting", "theme");
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT);

        PermissionResult result = tool.checkPermissions(getInput, ctx);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);

        JsonNode setInput = JsonNodeFactory.instance.objectNode()
            .put("setting", "theme")
            .put("value", "dark");
        PermissionResult setResult = tool.checkPermissions(setInput, ctx);
        assertThat(((PermissionResult.Ask) setResult).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);
    }
}