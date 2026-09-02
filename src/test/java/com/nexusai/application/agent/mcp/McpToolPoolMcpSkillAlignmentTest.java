package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.mcp.McpToolPool.McpToolEntry;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.ListMcpResourcesTool;
import com.nexusai.application.agent.tool.impl.ReadMcpResourceTool;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ALIGN-MC-1: mcp-skills 3△ + fetchPrompt/isMcp/resource 对齐（RED→GREEN）。
 *
 * <p>WHY（CLAUDE.md 规则 9 · 测试验证意图，而非仅行为）:
 * <ol>
 *   <li><b>MC-04a recursivelySanitizeUnicode</b>（client.ts:2051）——prompts/list 产物的
 *       prompt.name/description 含隐藏 Unicode 攻击字符（HackerOne #3086545）时必须清洗，
 *       否则模型可被隐藏指令注入。用例 {@link #fetchCommands_sanitizesHiddenUnicode}。</li>
 *   <li><b>OQ-MC-01 promptFn 接线</b>（client.ts:2073-2094）——MCP server 暴露的 prompt 命令
 *       必须可执行（Command.promptFn 非 null + 返回 prompts/get text 内容），否则模型发现
 *       mcp__&lt;server&gt;__&lt;prompt&gt; 却无法取其内容（fetchPrompt 孤儿）。用例
 *       {@link #fetchCommands_wiresPromptFn_executable}。</li>
 *   <li><b>[决策 #65] resource 工具恒注册</b>（tools.ts:245-246 getAllBaseTools 恒含）——
 *       ListMcpResourcesTool/ReadMcpResourceTool 由 {@code @Component} 恒注册（不再由 McpToolPool
 *       按 resources 能力条件注册）。原 client.ts:2182-2191 / 2360-2364 resourceToolsAdded
 *       条件注册语义被 getAllBaseTools 恒含取代。用例
 *       {@link #mc09_resourceToolsAlwaysRegisteredViaComponent}。</li>
 *   <li><b>NG-4 promptName 直捕</b>（client.ts:2078 闭包捕获 prompt.name，非反解复合名）——
 *       server 名含 {@code __}（如 my__server）时 mcpInfoFromString 反解会把 server 截断为
 *       "my" 导致 promptName 错成 "server__summarize"；直捕需保证 prompts/get 携带原始
 *       promptName "summarize"。用例
 *       {@link #fetchCommands_serverNameWithDoubleUnderscore_capturesPromptNameDirectly}。</li>
 *   <li><b>NG-3 mimeType 空串回退</b>（client.ts:2611 {@code mimeType || 'unknown type'}）——
 *       server 显式发 {@code "mimeType": ""} 时（Jackson asText(null) 对存在的文本节点返回其值，
 *       即 {@code ''}），JS 空串为 falsy → 必须回退 'unknown type'；否则错误文本渲染
 *       {@code Binary content (, N bytes)} 与 CC 语义漂移。用例
 *       {@link #persistBlob_explicitEmptyMimeType_fallsBackToUnknownType}。</li>
 * </ol>
 */
@DisplayName("ALIGN-MC-1 mcp-skills 对齐")
class McpToolPoolMcpSkillAlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonRpcMcpClient client = new JsonRpcMcpClient();

    // ═══════════════ MC-04a: prompts/list sanitize（client.ts:2051 recursivelySanitizeUnicode）═══════════════

    @Test
    @DisplayName("MC-04a fetchCommands: prompt.name/description 隐藏 Unicode 被清洗")
    void fetchCommands_sanitizesHiddenUnicode() {
        // ​ = ZERO WIDTH SPACE（Cf 格式控制，隐藏字符攻击向量，CC sanitization.ts:42 移除）
        PromptTransport fake = new PromptTransport(true, true,
            "​sum​marize", "Summarize﻿ the text");
        McpToolPool pool = new McpToolPool(new PromptFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("srv", null);

        List<Command> cmds = pool.fetchCommands("srv");

        assertThat(cmds).hasSize(1);
        Command cmd = cmds.get(0);
        assertThat(cmd.getName()).doesNotContain("​").isEqualTo("mcp__srv__summarize");
        assertThat(cmd.getDescription()).doesNotContain("﻿").isEqualTo("Summarize the text");
    }

    // ═══════════════ FIX-A1: tools/list sanitize（client.ts:1758 recursivelySanitizeUnicode）═══════════════

    @Test
    @DisplayName("FIX-A1 fetchTools: tool.name/description/inputSchema 属性名 隐藏 Unicode 被清洗")
    void fetchTools_sanitizesHiddenUnicode() {
        // 隐藏 Unicode 攻击向量（HackerOne #3086545）：恶意 MCP server 在 tools/list 的
        // tool.name / description / inputSchema 属性名 中注入 ZERO WIDTH SPACE（Cf 格式控制）。
        // CC client.ts:1758 在 tools/list 产物转 Tool 前递归清洗 → 暴露给 LLM 的工具契约干净。
        ToolsTransport fake = new ToolsTransport(
            "sum​marize",                  // tool.name 含隐藏字符
            "Summarize﻿ the text",              // tool.description 含 U+FEFF
            "arg​name");                   // inputSchema 属性名含隐藏字符（攻击者可控键）
        McpToolPool pool = new McpToolPool(new PromptFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("srv", null);

        List<McpToolEntry> tools = pool.fetchTools("srv");
        assertThat(tools).hasSize(1);
        McpToolEntry entry = tools.get(0);

        // tool.name 清洗 → 注册名/工具名不含隐藏字符
        assertThat(entry.toolName()).doesNotContain("​").isEqualTo("summarize");
        assertThat(entry.mcpToolName()).doesNotContain("​").isEqualTo("mcp__srv__summarize");
        // tool.description 清洗（wrapMcpTool 透传 description）
        assertThat(entry.tool().description()).doesNotContain("﻿").isEqualTo("Summarize the text");
        // inputSchema 属性名（攻击者可控键）清洗 —— FIX-A1 键清洗升级点
        JsonNode schema = entry.inputSchema();
        assertThat(schema.path("properties").has("arg​name")).isFalse();
        assertThat(schema.path("properties").has("argname")).isTrue();
    }

    @Test
    @DisplayName("FIX-A1 doFetchTools: fetchTools 直接路径（缓存 miss）同样清洗")
    void fetchTools_cacheMiss_stillSanitizes() {
        ToolsTransport fake = new ToolsTransport("a​b", "Desc﻿", "k​ey");
        McpToolPool pool = new McpToolPool(new PromptFactory(fake), new ToolRegistry(), client);
        // assembleToolPool 会预热 toolsCache → 清缓存强制走 doFetchTools 本体
        pool.assembleToolPool("srv", null);
        pool.toolsCache().delete("srv");

        List<McpToolEntry> tools = pool.fetchTools("srv");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).toolName()).isEqualTo("ab");
        assertThat(tools.get(0).tool().description()).isEqualTo("Desc");
        assertThat(tools.get(0).inputSchema().path("properties").has("key")).isTrue();
    }

    // ═══════════════ OQ-MC-01: fetchPrompt → promptFn 接线（client.ts:2073-2094）═══════════════

    @Test
    @DisplayName("OQ-MC-01 fetchCommands: promptFn 非 null 且可执行返回 prompts/get text")
    void fetchCommands_wiresPromptFn_executable() {
        PromptTransport fake = new PromptTransport(true, true, "summarize", "Summarize the text");
        McpToolPool pool = new McpToolPool(new PromptFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("srv", null);

        List<Command> cmds = pool.fetchCommands("srv");
        assertThat(cmds).hasSize(1);
        Command cmd = cmds.get(0);

        // MC-04b: isMcp 独立布尔已置 true（client.ts:2064）
        assertThat(cmd.getIsMcp()).isTrue();
        // promptFn 已接线（孤儿 fetchPrompt 落地为可执行闭包）
        assertThat(cmd.getPromptFn()).isNotNull();
        // P2-16: promptFn 返回内容块数组（对齐 CC getPromptForCommand → ContentBlockParam[]）——
        // prompts/get text 内容为 text 块
        List<ContentBlockParam> blocks = cmd.getPromptFn().apply("hello world", PromptFnContext.of(null, List.of(), null));
        assertThat(blocks).hasSize(1);
        assertThat(((ContentBlockParam.TextBlockParam) blocks.get(0)).text())
            .isEqualTo("prompt summarize content");
    }

    // ═══════════════ NG-4: server 名含 __ 时 promptName 直捕（CC 闭包捕获 prompt.name，非反解）═══════════════

    @Test
    @DisplayName("NG-4 server 名含 __：promptName 直捕（不复解复合名，prompts/get 携带原始 promptName）")
    void fetchCommands_serverNameWithDoubleUnderscore_capturesPromptNameDirectly() {
        PromptTransport fake = new PromptTransport(true, true, "summarize", "desc");
        McpToolPool pool = new McpToolPool(new PromptFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("my__server", null);

        List<Command> cmds = pool.fetchCommands("my__server");
        assertThat(cmds).hasSize(1);
        Command cmd = cmds.get(0);
        // 复合命令名 = mcp__<normalizedServer>__<promptName>（normalize 保留 __）
        assertThat(cmd.getName()).isEqualTo("mcp__my__server__summarize");
        // promptFn 已接线（直捕原始 promptName 落位闭包）
        assertThat(cmd.getPromptFn()).isNotNull();
        // P2-16: promptFn 返回内容块数组（对齐 CC getPromptForCommand → ContentBlockParam[]）
        List<ContentBlockParam> blocks = cmd.getPromptFn().apply("hello", null);
        assertThat(blocks).hasSize(1);
        assertThat(((ContentBlockParam.TextBlockParam) blocks.get(0)).text())
            .isEqualTo("prompt summarize content");
        // prompts/get 请求携带原始 promptName（若走 mcpInfoFromString 反解会截断为 "server__summarize"）
        assertThat(fake.lastPromptGetName()).isEqualTo("summarize");
    }

    // ═══════════════ P2-16: 图片块通道（client.ts:2503-2523 image → ImageBlockParam）═══════════════

    /** 1x1 PNG base64（ImageIO 可解码；标准缩放原样返回 → data 逐字一致）。 */
    private static final String TINY_PNG =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Test
    @DisplayName("P2-16: prompt image 内容 → ImageBlockParam（对齐 CC client.ts:2503-2523，模型可见内联图）")
    void promptImageContent_producesImageBlock() {
        // WHY: CC transformResultContent image 分支缩放后保留 image 块（模型可见内联图）；Java 旧实现
        // 文本约束降级为落盘提示（△-1）→ P2-16 补图片块通道。若 image 分支仍落盘文本，本断言 fail。
        ObjectNode image = MAPPER.createObjectNode();
        image.put("type", "image");
        image.put("data", TINY_PNG);
        image.put("mimeType", "image/png");
        McpToolPool pool = new McpToolPool(
            new PromptFactory(new ContentPromptTransport(image)), new ToolRegistry(), client);
        pool.assembleToolPool("srv", null);

        List<ContentBlockParam> blocks = pool.fetchCommands("srv").get(0).getPromptFn().apply("", null);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ContentBlockParam.ImageBlockParam.class);
        ContentBlockParam.ImageBlockParam img = (ContentBlockParam.ImageBlockParam) blocks.get(0);
        assertThat(img.source().mediaType())
            .as("CC client.ts:2517 media_type = image/${resized.mediaType}（png → image/png）")
            .isEqualTo("image/png");
        assertThat(img.source().data())
            .as("小图标准缩放原样返回（CC imageResizer.ts:212-227 原图可用直通）")
            .isEqualTo(TINY_PNG);
    }

    @Test
    @DisplayName("P2-16: resource blob 为图片 → [text prefix, ImageBlockParam]（对齐 CC client.ts:2535-2563）")
    void promptResourceBlobImage_producesTextPrefixAndImageBlock() {
        // WHY: CC resource blob 为 IMAGE_MIME_TYPES 内类型 → 保留 [text prefix, image block]（:2538-2563）；
        // 非图片 blob 才落盘（:2564-2571）。若 blob 分支把图片也落盘文本，本断言 fail。
        ObjectNode resource = MAPPER.createObjectNode();
        resource.put("type", "resource");
        ObjectNode res = resource.putObject("resource");
        res.put("uri", "mcp://srv/image.png");
        res.put("mimeType", "image/png");
        res.put("blob", TINY_PNG);
        McpToolPool pool = new McpToolPool(
            new PromptFactory(new ContentPromptTransport(resource)), new ToolRegistry(), client);
        pool.assembleToolPool("srv", null);

        List<ContentBlockParam> blocks = pool.fetchCommands("srv").get(0).getPromptFn().apply("", null);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isInstanceOf(ContentBlockParam.TextBlockParam.class);
        assertThat(((ContentBlockParam.TextBlockParam) blocks.get(0)).text())
            .as("CC client.ts:2549 prefix 文本块先于 image 块")
            .isEqualTo("[Resource from srv at mcp://srv/image.png] ");
        assertThat(blocks.get(1)).isInstanceOf(ContentBlockParam.ImageBlockParam.class);
        assertThat(((ContentBlockParam.ImageBlockParam) blocks.get(1)).source().mediaType())
            .isEqualTo("image/png");
    }

    // ═══════════════ NG-3: mimeType 空串回退（client.ts:2611 `${mimeType || 'unknown type'}`）═══════════════

    @Test
    @DisplayName("NG-3 mimeType 显式空串: persist 错误文本回退 unknown type（CC JS 空串 falsy）")
    void persistBlob_explicitEmptyMimeType_fallsBackToUnknownType() throws Exception {
        // CC client.ts:2611 `${mimeType || 'unknown type'}`：JS 中空串 '' 为 falsy → 回退 'unknown type'。
        // server 显式发 "mimeType": ""（Jackson asText(null) 对存在的文本节点返回其值，即 ''）时，
        // 旧实现 mimeTypeLabel 仅判 null → 错误文本渲染 "Binary content (, N bytes)"（mimeType 段为空）
        // 与 CC "Binary content (unknown type, N bytes)" 语义漂移。本用例锁定空串也必须回退。
        McpToolPool pool = new McpToolPool(
            new PromptFactory(new PromptTransport(false, false, "x", "x")), new ToolRegistry(), client);
        Method persist = McpToolPool.class.getDeclaredMethod(
            "persistBlobToText", String.class, String.class, String.class, String.class);
        persist.setAccessible(true);

        // 空串 mimeType → 'unknown type'（decode 失败分支 = Java 特有路径，0 bytes）
        String emptyMime = (String) persist.invoke(pool, "!!!not-base64!!!", "", "[Image from srv] ", "srv");
        assertThat(emptyMime).contains("Binary content (unknown type, 0 bytes)");

        // 非空 mimeType 原样保留（回归保护：不因空串修复误伤正常值）
        String nonEmptyMime = (String) persist.invoke(pool, "!!!not-base64!!!", "text/plain", "[Image from srv] ", "srv");
        assertThat(nonEmptyMime).contains("Binary content (text/plain, 0 bytes)");
    }

    // ═══════════════ FIX-C3: isMcpCommand 消费侧接线（拍板#11 part · NG-5，CC utils.ts:254-255）═══════════════

    @Test
    @DisplayName("FIX-C3 fetchCommands: isMcpCommand 生产消费方判别 MCP prompt 命令为 true")
    void fetchCommands_isMcpCommand_productionConsumer() {
        // WHY（规则九）：NG-5 复验发现 isMcpCommand 全仓 0 生产调用方、isMcp 字段生产零读取方。
        // 本用例锁定 FIX-C3 接线——wirePromptFunctions 以 McpServerUtils.isMcpCommand（CC
        // utils.ts:254-255 name.startsWith('mcp__') || isMcp === true）判别产出 isMcp 值，
        // 使判别成为生产消费方；prompts/list 产物恒带 mcp__ 前缀 → 判别恒 true（对齐 CC
        // client.ts:2064 无条件 isMcp: true 的可观测行为）。
        PromptTransport fake = new PromptTransport(true, true, "summarize", "Summarize the text");
        McpToolPool pool = new McpToolPool(new PromptFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("srv", null);

        List<Command> cmds = pool.fetchCommands("srv");
        assertThat(cmds).hasSize(1);
        Command cmd = cmds.get(0);

        // isMcpCommand 直接消费真实 Command 模型 → true（name mcp__ 前缀命中第一臂）
        assertThat(McpServerUtils.isMcpCommand(cmd)).isTrue();
        // 判别结果已落入字段（wirePromptFunctions 生产消费方接线，替代原硬编码 Boolean.TRUE）
        assertThat(cmd.getIsMcp()).isTrue();
    }

    @Test
    @DisplayName("FIX-C3 isMcpCommand 判别双臂语义（CC utils.ts:255）：前缀/字段任一命中 → true")
    void isMcpCommand_dualArm_semantics() {
        // WHY（规则九）：CC 判别式是 name.startsWith('mcp__') 与 isMcp === true 的 OR 双臂——
        // 若未来命令源改造去掉 mcp__ 前缀，字段成为唯一判别依据（复验 NG-5 风险点），双臂必须保留。
        Command plain = new Command();
        plain.setName("my-skill");
        assertThat(McpServerUtils.isMcpCommand(plain)).isFalse();

        // 第一臂：name mcp__ 前缀命中（isMcp 未置）
        Command prefixed = new Command();
        prefixed.setName("mcp__srv__summarize");
        assertThat(McpServerUtils.isMcpCommand(prefixed)).isTrue();

        // 第二臂：isMcp=true 命中（name 无 mcp__ 前缀）
        Command flagged = new Command();
        flagged.setName("server:skill");
        flagged.setIsMcp(true);
        assertThat(McpServerUtils.isMcpCommand(flagged)).isTrue();

        // null / 空名 → false（CC name?. 可选链等价）
        assertThat(McpServerUtils.isMcpCommand(null)).isFalse();
        Command noName = new Command();
        assertThat(McpServerUtils.isMcpCommand(noName)).isFalse();
    }

    // ═══════════════ MC-09: resource 工具条件注册（client.ts:2182-2191 / 2360-2364）═══════════════

    @Test
    @DisplayName("[决策#65] ListMcpResourcesTool/ReadMcpResourceTool 恒注册（@Component，不再由 McpToolPool 条件注册）")
    void mc09_resourceToolsAlwaysRegisteredViaComponent() {
        // WHY: 决策 #65 反转原 MC-09 条件注册——CC tools.ts:245-246 getAllBaseTools 恒含
        //   ListMcpResourcesTool/ReadMcpResourceTool（无条件），Java 以 @Component 恒注册。
        //   McpToolPool 不再按 resources 能力注册/反注册两工具（原 client.ts:2182-2191/2360-2364
        //   resourceToolsAdded 语义由 getAllBaseTools 恒含取代）。
        // 断言两工具类携带 @Component 注解（Spring 启动经 ToolRegistry @Autowired List<Tool> 恒注册）。
        assertThat(ListMcpResourcesTool.class.isAnnotationPresent(org.springframework.stereotype.Component.class))
            .as("ListMcpResourcesTool 必须 @Component 恒注册（决策#65，对齐 CC getAllBaseTools 恒含 tools.ts:245）")
            .isTrue();
        assertThat(ReadMcpResourceTool.class.isAnnotationPresent(org.springframework.stereotype.Component.class))
            .as("ReadMcpResourceTool 必须 @Component 恒注册（决策#65，对齐 CC getAllBaseTools 恒含 tools.ts:246）")
            .isTrue();
    }

    @Test
    @DisplayName("[决策#65] McpToolPool assemble 不再条件注册 resource 工具（两工具恒注册不依赖 resources 能力）")
    void assembleToolPool_noLongerConditionallyRegistersResourceTools() {
        // WHY: 移除 ensureResourceToolsRegistered 后，McpToolPool 不按 resources 能力往 registry 注入
        //   ListMcpResourcesTool/ReadMcpResourceTool（恒注册由 @Component 承担）。本测试锁定「池不再
        //   条件驱动两工具」—— 有/无 resources 能力注册表均不含两工具（直接 new ToolRegistry 无 Spring 扫描）。
        ToolRegistry withCap = new ToolRegistry();
        new McpToolPool(new PromptFactory(new PromptTransport(true, false, "summarize", "desc")), withCap, client)
            .assembleToolPool("srv", null);
        assertThat(withCap.get("ListMcpResourcesTool")).isEmpty();
        assertThat(withCap.get("ReadMcpResourceTool")).isEmpty();

        ToolRegistry withoutCap = new ToolRegistry();
        new McpToolPool(new PromptFactory(new PromptTransport(false, true, "summarize", "desc")), withoutCap, client)
            .assembleToolPool("srv", null);
        assertThat(withoutCap.get("ListMcpResourcesTool")).isEmpty();
        assertThat(withoutCap.get("ReadMcpResourceTool")).isEmpty();
    }

    @Test
    @DisplayName("[决策#65] McpToolPool teardown 不再反注册 resource 工具（恒注册不受 server 断开影响）")
    void teardown_noLongerUnregistersResourceTools() {
        // WHY: 移除 maybeUnregisterResourceTools 后，teardown 不触碰 ListMcpResourcesTool/ReadMcpResourceTool
        //   （恒注册由 @Component 承担）。锁定「teardown 不再反注册」—— 注册表不含两工具且 teardown 后仍不含。
        ToolRegistry registry = new ToolRegistry();
        McpToolPool pool = new McpToolPool(new PromptFactory(new PromptTransport(true, false, "summarize", "desc")), registry, client);
        pool.assembleToolPool("srv", null);
        pool.teardown("srv");

        assertThat(registry.get("ListMcpResourcesTool")).isEmpty();
        assertThat(registry.get("ReadMcpResourceTool")).isEmpty();
    }

    // ═══════════════ helpers ═══════════════

    /** fake factory: 每次 create 返回指定 fake transport。 */
    static class PromptFactory extends McpTransportFactory {
        private final McpTransport transport;

        PromptFactory(McpTransport transport) { this.transport = transport; }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return transport;
        }
    }

    /**
     * 可控 capabilities + prompts/list + prompts/get 往返的 fake transport。
     * 返回单条 prompt（name/description 可注入隐藏 Unicode），prompts/get 返回 text 内容。
     */
    static class PromptTransport implements McpTransport {
        final boolean resourcesCap;
        final boolean promptsCap;
        final String promptName;
        final String promptDescription;
        /** NG-4: 最近一次 prompts/get 请求携带的 prompt name（验证 promptFn 直捕原始 promptName）。 */
        private volatile String lastPromptGetName;

        PromptTransport(boolean resourcesCap, boolean promptsCap,
                        String promptName, String promptDescription) {
            this.resourcesCap = resourcesCap;
            this.promptsCap = promptsCap;
            this.promptName = promptName;
            this.promptDescription = promptDescription;
        }

        String lastPromptGetName() {
            return lastPromptGetName;
        }

        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            switch (method) {
                case "initialize" -> {
                    ObjectNode caps = result.putObject("capabilities");
                    if (resourcesCap) caps.putObject("resources");
                    if (promptsCap) caps.putObject("prompts");
                }
                case "tools/list" -> result.putArray("tools");
                case "resources/list" -> result.putArray("resources");
                case "prompts/list" -> {
                    ArrayNode prompts = result.putArray("prompts");
                    ObjectNode p = prompts.addObject();
                    p.put("name", promptName);
                    p.put("description", promptDescription);
                    p.putArray("arguments").addObject().put("name", "text").put("description", "input text");
                }
                case "prompts/get" -> {
                    // NG-4: 记录请求携带的 prompt name（CC getPrompt {name: prompt.name, ...} client.ts:2078-2080）
                    if (params instanceof Map<?, ?> map && map.get("name") != null) {
                        lastPromptGetName = String.valueOf(map.get("name"));
                    }
                    ArrayNode messages = result.putArray("messages");
                    messages.addObject()
                        .put("role", "user")
                        .putObject("content")
                        .put("type", "text")
                        .put("text", "prompt summarize content");
                }
                default -> { }
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void sendNotification(String method, Object params) {}

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {}

        @Override
        public void close() {}

        @Override
        public State getState() { return State.CONNECTED; }
    }

    /**
     * P2-16: prompts/get 返回自定义 content（image / resource-blob 等）的 fake transport。
     * 复用 PromptTransport 的 capabilities/prompts-list 装配，仅覆盖 prompts/get 的 content 形状。
     */
    static class ContentPromptTransport extends PromptTransport {
        private final JsonNode content;

        ContentPromptTransport(JsonNode content) {
            super(true, true, "ctprompt", "returns custom content");
            this.content = content;
        }

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            if ("prompts/get".equals(method)) {
                ObjectNode result = MAPPER.createObjectNode();
                ArrayNode messages = result.putArray("messages");
                ObjectNode msg = messages.addObject();
                msg.put("role", "user");
                msg.set("content", content);
                return CompletableFuture.completedFuture(result);
            }
            return super.sendRequest(method, params);
        }
    }

    /**
     * FIX-A1: 返回单条 tool（name/description/inputSchema 属性名可注入隐藏 Unicode）的 fake transport。
     * capabilities.tools 声明 → doFetchTools 通过能力门控；tools/list 产物供 sanitize 断言。
     */
    static class ToolsTransport implements McpTransport {
        final String toolName;
        final String toolDescription;
        final String schemaKey;

        ToolsTransport(String toolName, String toolDescription, String schemaKey) {
            this.toolName = toolName;
            this.toolDescription = toolDescription;
            this.schemaKey = schemaKey;
        }

        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            switch (method) {
                case "initialize" -> result.putObject("capabilities").putObject("tools");
                case "tools/list" -> {
                    ArrayNode tools = result.putArray("tools");
                    ObjectNode tool = tools.addObject();
                    tool.put("name", toolName);
                    tool.put("description", toolDescription);
                    // inputSchema 属性名（攻击者可控键）注入隐藏 Unicode
                    ObjectNode schema = tool.putObject("inputSchema");
                    schema.put("type", "object");
                    schema.putObject("properties").putObject(schemaKey).put("type", "string");
                }
                case "resources/list" -> result.putArray("resources");
                case "prompts/list" -> result.putArray("prompts");
                default -> { }
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public void sendNotification(String method, Object params) {}

        @Override
        public void setNotificationHandler(String method, McpNotificationHandler handler) {}

        @Override
        public void close() {}

        @Override
        public State getState() { return State.CONNECTED; }
    }
}
