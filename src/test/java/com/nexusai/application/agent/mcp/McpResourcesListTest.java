package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.CreateSkillCommand;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-17: MCP resources/list + prompts/list 协议往返（RED→GREEN）。
 *
 * <p>对齐 CC client.ts:2000-2031 fetchResourcesForClient + :2033-2107 fetchCommandsForClient：
 * <ul>
 *   <li>capability gate（client.ts:2005-2007 resources / :2038-2040 prompts）</li>
 *   <li>server 字段追加（client.ts:2017-2020）</li>
 *   <li>fail-soft（client.ts:2021-2027 / :2097-2103 不抛）</li>
 *   <li>Command 25 属性映射（loadSkillsDir.ts:270-401 createSkillCommand，N-1 目标）</li>
 *   <li>真实 StdioMcpTransport + mock-mcp-server.py 往返</li>
 * </ul>
 */
@DisplayName("P1-17 MCP resources/list + prompts/list 协议往返")
class McpResourcesListTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonRpcMcpClient client = new JsonRpcMcpClient();

    // ════════════════════════════════════════════════════════════════════════
    // 1. JsonRpcMcpClient 解析层（纯 JSON → 领域对象）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listResourcesFromJson: 映射字段 + 追加 server（client.ts:2017-2020）")
    void listResourcesFromJson_mapsFieldsAndAppendsServer() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode resources = result.putArray("resources");
        resources.addObject()
            .put("uri", "mock://docs/readme")
            .put("name", "Mock Readme")
            .put("description", "A mock resource for testing")
            .put("mimeType", "text/plain");
        ObjectNode r2 = resources.addObject();
        r2.put("uri", "mock://data/example.json");
        r2.put("name", "Example JSON");

        List<McpResource> list = client.listResourcesFromJson(result, "mock-server");

        assertThat(list).hasSize(2);
        assertThat(list.get(0).server()).isEqualTo("mock-server");
        assertThat(list.get(0).uri()).isEqualTo("mock://docs/readme");
        assertThat(list.get(0).name()).isEqualTo("Mock Readme");
        assertThat(list.get(0).mimeType()).isEqualTo("text/plain");
        assertThat(list.get(0).description()).isEqualTo("A mock resource for testing");
        // 可选字段缺失 → null（CC outputSchema mimeType?/description?）
        assertThat(list.get(1).mimeType()).isNull();
    }

    @Test
    @DisplayName("listResourcesFromJson: 无 resources 数组 → 空（client.ts:2014）")
    void listResourcesFromJson_noResourcesArray_returnsEmpty() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("foo", "bar");
        assertThat(client.listResourcesFromJson(result, "mock-server")).isEmpty();
        assertThat(client.listResourcesFromJson(null, "mock-server")).isEmpty();
    }

    @Test
    @DisplayName("listPromptsFromJson: Command 25 属性映射（client.ts:2054-2095）")
    void listPromptsFromJson_mapsCommandProperties() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode prompts = result.putArray("prompts");
        ObjectNode p1 = prompts.addObject();
        p1.put("name", "summarize");
        p1.put("description", "Summarize the given text");
        p1.putArray("arguments").addObject().put("name", "text").put("description", "Text to summarize");
        ObjectNode p2 = prompts.addObject();
        p2.put("name", "bare"); // 无 description / 无 arguments

        List<Command> cmds = client.listPromptsFromJson(result, "mock-server");

        assertThat(cmds).hasSize(2);
        Command summarize = cmds.get(0);
        assertThat(summarize.getType()).isEqualTo("prompt");                                  // :2057
        assertThat(summarize.getName()).isEqualTo("mcp__mock-server__summarize");              // :2058 normalizeNameForMCP
        assertThat(summarize.getDescription()).isEqualTo("Summarize the given text");          // :2059
        assertThat(summarize.getHasUserSpecifiedDescription()).isTrue();                       // :2060 !!
        assertThat(summarize.getContentLength()).isZero();                                     // :2061 contentLength: 0
        assertThat(summarize.isCommandEnabled()).isTrue();                                      // :2062 isEnabled: () => true（P2-6 统一 isCommandEnabled 入口）
        assertThat(summarize.getIsHidden()).isFalse();                                         // :2063
        assertThat(summarize.getProgressMessage()).isEqualTo("running");                       // :2065
        assertThat(summarize.getArgNames()).containsExactly("text");                           // :2055
        assertThat(summarize.getSource()).isEqualTo(CommandSource.MCP);                        // :2072 source: 'mcp'
        assertThat(summarize.userFacingName()).isEqualTo("mock-server:summarize (MCP)");       // :2066-2070

        Command bare = cmds.get(1);
        assertThat(bare.getHasUserSpecifiedDescription()).isFalse();
        assertThat(bare.getArgNames()).isNull();
    }

    @Test
    @DisplayName("listPromptsFromJson: 无 prompts 数组 → 空（client.ts:2048）")
    void listPromptsFromJson_noPromptsArray_returnsEmpty() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("foo", "bar");
        assertThat(client.listPromptsFromJson(result, "mock-server")).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. CreateSkillCommand 构建器（25 属性落位 · N-1 对齐目标）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CreateSkillCommand: 25 属性统一落位（loadSkillsDir.ts:317-399）")
    void createSkillCommand_builds25Properties() {
        Command c = CreateSkillCommand.create(new CreateSkillCommand.Params(
            "mcp__mock-server__summarize", null, "Summarize the given text", true, "content",
            List.of(), null, List.of("text"), null, null, null,
            false, true, CommandSource.MCP, null, CommandLoadedFrom.MCP,
            null, "inline", null, null, null, null));

        assertThat(c.getType()).isEqualTo("prompt");                                  // :318
        assertThat(c.getName()).isEqualTo("mcp__mock-server__summarize");             // :319
        assertThat(c.getDescription()).isEqualTo("Summarize the given text");         // :320
        assertThat(c.getHasUserSpecifiedDescription()).isTrue();                      // :321
        assertThat(c.getAllowedTools()).isEmpty();                                    // :322
        assertThat(c.getArgNames()).containsExactly("text");                          // :324
        assertThat(c.getDisableModelInvocation()).isFalse();                          // :328
        assertThat(c.getUserInvocable()).isTrue();                                    // :329
        assertThat(c.getContext()).isEqualTo("inline");                               // :330 context: executionContext
        assertThat(c.getContent()).isEqualTo("content");                              // :334 contentLength 派生源
        assertThat(c.getContentLength()).isEqualTo(7);                                // :334 contentLength: markdownContent.length
        assertThat(c.getIsHidden()).isFalse();                                        // :335 !userInvocable
        assertThat(c.getProgressMessage()).isEqualTo("running");                      // :336
        assertThat(c.userFacingName()).isEqualTo("mcp__mock-server__summarize");      // :337-339 displayName null → name
        assertThat(c.getSource()).isEqualTo(CommandSource.MCP);                       // :340/:341 source+loadedFrom
    }

    @Test
    @DisplayName("CreateSkillCommand: isHidden = !userInvocable（:335）")
    void createSkillCommand_isHidden_invertsUserInvocable() {
        Command c = CreateSkillCommand.create(new CreateSkillCommand.Params(
            "s", null, "", false, "", List.of(), null, null, null, null, null,
            false, false, CommandSource.MCP, null, CommandLoadedFrom.MCP,   // userInvocable=false
            null, null, null, null, null, null));
        assertThat(c.getUserInvocable()).isFalse();
        assertThat(c.getIsHidden()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. getMcpSkillCommands 纯过滤器（commands.ts:551-556）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getMcpSkillCommands: 纯过滤器 type=prompt && loadedFrom=mcp && !disableModelInvocation")
    void getMcpSkillCommands_pureFilter_filtersSkillCommands() throws Exception {
        McpServerService svc = new McpServerService();
        // P1-9: 默认 gate=false（对齐 CC 生产）→ 本测试验证过滤语义，显式开 gate
        svc.setMcpSkillsGate(() -> true);
        Field field = McpServerService.class.getDeclaredField("mcpSkillCommands");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Command> list = (List<Command>) field.get(svc);

        list.add(createSkill("mcp__s__on"));
        list.add(createSkillDisabled("mcp__s__off"));
        Command notPrompt = new Command();
        notPrompt.setName("mcp__s__bash");
        notPrompt.setType("bash");
        notPrompt.setSource(CommandSource.MCP);
        notPrompt.setDisableModelInvocation(Boolean.FALSE);
        list.add(notPrompt);

        List<Command> result = svc.getMcpSkillCommands();

        assertThat(result).extracting(Command::getName).containsExactly("mcp__s__on");
    }

    @Test
    @DisplayName("getMcpSkillCommands: 空池 → 空 list")
    void getMcpSkillCommands_emptyPool_returnsEmpty() {
        McpServerService svc = new McpServerService();
        assertThat(svc.getMcpSkillCommands()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. McpToolPool 能力门控 + 连接门控（stub transport）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fetchResources/fetchCommands: 连接门控（client.ts:2002/:2035）")
    void fetch_fetch_connectedGate_skipsUnknownServer() {
        McpToolPool pool = new McpToolPool(new McpTransportFactory(), new ToolRegistry(), client);
        assertThat(pool.fetchResources("unknown")).isEmpty();
        assertThat(pool.fetchCommands("unknown")).isEmpty();
    }

    @Test
    @DisplayName("fetchResources/fetchCommands: capabilities 门控不发请求（client.ts:2005-2007/:2038-2040）")
    void fetch_fetch_capabilityGate_skipsWithoutCapability() {
        GateTransport fake = new GateTransport(false, false);
        McpToolPool pool = new McpToolPool(new FakeFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("gate", null);

        assertThat(pool.fetchResources("gate")).isEmpty();
        assertThat(pool.fetchCommands("gate")).isEmpty();
        assertThat(fake.resourcesListCalled.get()).isFalse();
        assertThat(fake.promptsListCalled.get()).isFalse();
    }

    @Test
    @DisplayName("fetchResources: 有 resources 能力 → 真实发请求（stub 返回）")
    void fetchResources_withCapability_sendsRequest() {
        GateTransport fake = new GateTransport(true, true);
        McpToolPool pool = new McpToolPool(new FakeFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("gate", null);

        List<McpResource> resources = pool.fetchResources("gate");
        assertThat(fake.resourcesListCalled.get()).isTrue();
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).uri()).isEqualTo("mock://docs/readme");
        assertThat(resources.get(0).server()).isEqualTo("gate");
    }

    @Test
    @DisplayName("fetchResources: 协议异常 → fail-soft 空 list（client.ts:2021-2027）")
    void fetchResources_protocolError_returnsEmpty() {
        GateTransport fake = new GateTransport(true, true);
        fake.failResourcesList = true;
        McpToolPool pool = new McpToolPool(new FakeFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("gate", null);

        assertThat(pool.fetchResources("gate")).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. 真实 StdioMcpTransport + mock-mcp-server.py 往返
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("真实 stdio 往返: assembleToolPool → fetchResources/fetchCommands/fetchPrompt")
    void realStdio_roundTrip_fetchResourcesAndCommands() throws Exception {
        String script = mockScriptPath();
        Assumptions.assumeTrue(script != null, "mock-mcp-server.py 不在 classpath，跳过");
        Assumptions.assumeTrue(pythonAvailable(), "python 不可用，跳过真实 stdio 往返");

        McpToolPool pool = new McpToolPool(new McpTransportFactory(), new ToolRegistry(), client);
        McpTransport.TransportConfig config =
            new McpTransport.TransportConfig("python", List.of(script), Map.of(), null, null);
        pool.assembleToolPool("mock", config);

        List<McpResource> resources = pool.fetchResources("mock");
        assertThat(resources).hasSize(2);
        assertThat(resources.get(0).uri()).isEqualTo("mock://docs/readme");
        assertThat(resources.get(0).name()).isEqualTo("Mock Readme");
        assertThat(resources.get(0).mimeType()).isEqualTo("text/plain");
        assertThat(resources.get(0).server()).isEqualTo("mock");

        List<Command> cmds = pool.fetchCommands("mock");
        assertThat(cmds).hasSize(2);
        Command summarize = cmds.stream()
            .filter(c -> "mcp__mock__summarize".equals(c.getName()))
            .findFirst().orElseThrow();
        assertThat(summarize.getArgNames()).containsExactly("text");
        assertThat(summarize.userFacingName()).isEqualTo("mock:summarize (MCP)");
        assertThat(summarize.getHasUserSpecifiedDescription()).isTrue();

        // prompts/get 往返（client.ts:2077-2080 getPrompt）
        JsonNode promptResult = pool.fetchPrompt("mock", "summarize", Map.of("text", "hello")).join();
        assertThat(promptResult.path("description").asText()).isEqualTo("prompt summarize");

        pool.teardown("mock");
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════════

    private static Command createSkill(String name) {
        return CreateSkillCommand.create(new CreateSkillCommand.Params(
            name, null, "desc", false, "", List.of(), null, null, null, null, null,
            false, true, CommandSource.MCP, null, CommandLoadedFrom.MCP,
            null, null, null, null, null, null));
    }

    private static Command createSkillDisabled(String name) {
        return CreateSkillCommand.create(new CreateSkillCommand.Params(
            name, null, "desc", false, "", List.of(), null, null, null, null, null,
            true, true, CommandSource.MCP, null, CommandLoadedFrom.MCP,
            null, null, null, null, null, null));
    }

    private static String mockScriptPath() {
        var res = McpResourcesListTest.class.getResource("/mock-mcp-server.py");
        if (res == null) return null;
        try {
            return new File(res.toURI()).getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** fake factory: 每次 create 返回指定 fake transport（gate 测试用）. */
    static class FakeFactory extends McpTransportFactory {
        private final McpTransport transport;

        FakeFactory(McpTransport transport) { this.transport = transport; }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return transport;
        }
    }

    /**
     * gate/fail-soft 测试用 fake transport：capabilities 可控，记录 resources/list 与
     * prompts/list 是否被调用，可配置 resources/list 抛错（fail-soft 验证）。
     */
    static class GateTransport implements McpTransport {
        final boolean resourcesCap;
        final boolean promptsCap;
        final AtomicBoolean resourcesListCalled = new AtomicBoolean();
        final AtomicBoolean promptsListCalled = new AtomicBoolean();
        volatile boolean failResourcesList;

        GateTransport(boolean resourcesCap, boolean promptsCap) {
            this.resourcesCap = resourcesCap;
            this.promptsCap = promptsCap;
        }

        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                if (resourcesCap || promptsCap) {
                    ObjectNode caps = result.putObject("capabilities");
                    if (resourcesCap) caps.putObject("resources");
                    if (promptsCap) caps.putObject("prompts");
                }
            } else if ("tools/list".equals(method)) {
                result.putArray("tools");
            } else if ("resources/list".equals(method)) {
                resourcesListCalled.set(true);
                if (failResourcesList) {
                    CompletableFuture<JsonNode> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new IllegalStateException("boom"));
                    return failed;
                }
                ArrayNode arr = result.putArray("resources");
                arr.addObject()
                    .put("uri", "mock://docs/readme")
                    .put("name", "Mock Readme")
                    .put("mimeType", "text/plain");
            } else if ("prompts/list".equals(method)) {
                promptsListCalled.set(true);
                result.putArray("prompts");
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
