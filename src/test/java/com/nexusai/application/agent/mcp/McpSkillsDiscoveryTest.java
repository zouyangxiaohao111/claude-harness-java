package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.CreateSkillCommand;
import com.nexusai.application.agent.skill.McpSkillBuilders;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-13: MCP skill:// 资源发现测试（RED→GREEN）· 对齐 CC mcpSkills.ts fetchMcpSkillsForClient
 * （消费点 client.ts:2174/2348，E4 推断）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>skill:// 资源 → skill Command</b>——resources/list → 过滤 skill:// 前缀 → resources/read
 *       取内容 → parseSkillFrontmatterFields → createSkillCommand（loadedFrom='mcp'）。CC 中只有
 *       skill:// 资源才成为 MCP skill（client.ts:2347 注释「Discover skills from skill:// resources」）；
 *       若过滤缺失，非 skill 资源会被错误提升（旧 X23 桥接把 prompts/list 全量提升的同类错误）。</li>
 *   <li><b>非 skill:// 资源排除</b>——mock:// 等普通资源不得进入 skill 池。</li>
 *   <li><b>无 resources capability → []（不发请求）</b>——client.ts:2005-2007 门控。</li>
 *   <li><b>协议异常 → fail-soft []</b>——client.ts:2021-2027 模式，不抛。</li>
 *   <li><b>gate 语义由 McpServerService 侧验证</b>——refreshMcpSkillCommands gate 关不生产
 *       （client.ts:2174 feature('MCP_SKILLS') && supportsResources）。</li>
 * </ol>
 */
@DisplayName("P2-13 MCP skill:// 资源发现（fetchMcpSkillsForClient 对齐）")
class McpSkillsDiscoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonRpcMcpClient client = new JsonRpcMcpClient();

    @BeforeEach
    void registerBuilders() {
        // fetchMcpSkills 内部经 McpSkillBuilders.get() 取两个函数引用，须先注册（对齐 ToolRegistrationConfig @Bean init）
        McpSkillBuilders.register(new McpSkillBuilders.Builders(
            CreateSkillCommand::create,
            ParseSkillFrontmatter::parseSkillFrontmatterFields));
    }

    @AfterEach
    void restoreBuilders() {
        McpSkillBuilders.register(new McpSkillBuilders.Builders(
            CreateSkillCommand::create,
            ParseSkillFrontmatter::parseSkillFrontmatterFields));
    }

    // ── ① skill:// 资源 → loadedFrom=MCP skill ──

    @Test
    @DisplayName("skill:// 资源经 readResource→parse→CreateSkillCommand 构建 loadedFrom=MCP skill")
    void fetchMcpSkills_discoversSkillResources() {
        SkillGateTransport fake = new SkillGateTransport(true);
        fake.skillContents.put("skill://greeting",
            "---\nname: Greeting\ndescription: A greeting skill\nuser-invocable: true\n---\n# Greeting\n");
        fake.skillContents.put("skill://summarize",
            "---\ndescription: Summarize the text\n---\n# Summarize\n");
        // 非 skill 资源（mock://）必须被过滤，不进 skill 池
        fake.plainContents.put("mock://docs/readme", "Readme body");
        McpToolPool pool = new McpToolPool(new FakeFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("gate", null);

        List<Command> skills = pool.fetchMcpSkills("gate");

        assertThat(fake.resourcesListCalled.get()).isTrue();
        assertThat(skills).hasSize(2);
        // greeting: frontmatter name→displayName、description 显式、content=body
        Command greeting = skills.stream().filter(c -> c.getName().equals("mcp__gate__greeting"))
            .findFirst().orElseThrow();
        assertThat(greeting.getType()).isEqualTo("prompt");                                    // :318
        assertThat(greeting.getSource()).isEqualTo(CommandSource.MCP);                          // :340/:341 loadedFrom=mcp
        assertThat(greeting.getDisplayName()).isEqualTo("Greeting");                            // :238-239 frontmatter.name
        assertThat(greeting.getDescription()).isEqualTo("A greeting skill");                    // :212-214 显式 description
        assertThat(greeting.getHasUserSpecifiedDescription()).isTrue();                         // :241 validated!=null
        assertThat(greeting.getUserInvocable()).isTrue();                                       // :216-219 未定义默认 true
        assertThat(greeting.getContent()).isEqualTo("# Greeting\n");                            // markdownContent=body
        assertThat(greeting.getIsHidden()).isFalse();                                           // :335 !userInvocable
        assertThat(greeting.getContentLength()).isEqualTo("# Greeting\n".length());             // :334
        // summarize: 无 frontmatter name → displayName null（userFacingName 回退 name）
        Command summarize = skills.stream().filter(c -> c.getName().equals("mcp__gate__summarize"))
            .findFirst().orElseThrow();
        assertThat(summarize.getDisplayName()).isNull();
        assertThat(summarize.userFacingName()).isEqualTo("mcp__gate__summarize");               // :337-339 displayName||name
        assertThat(summarize.getSource()).isEqualTo(CommandSource.MCP);
    }

    // ── ② 非 skill:// 资源排除 ──

    @Test
    @DisplayName("非 skill:// 资源排除（仅 skill:// 前缀进 skill 池，client.ts:2347 注释）")
    void fetchMcpSkills_excludesNonSkillResources() {
        SkillGateTransport fake = new SkillGateTransport(true);
        fake.skillContents.put("skill://only", "---\ndescription: Only skill\n---\n# Only\n");
        fake.plainContents.put("mock://docs/readme", "Should never be read as skill");
        McpToolPool pool = new McpToolPool(new FakeFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("gate", null);

        List<Command> skills = pool.fetchMcpSkills("gate");

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getName()).isEqualTo("mcp__gate__only");
        // mock:// 资源未被 resources/read 读取（过滤发生在 read 之前）
        assertThat(fake.readUriHistory).doesNotContain("mock://docs/readme");
    }

    // ── ③ 无 resources capability → []（不发请求）──

    @Test
    @DisplayName("无 resources capability → [] 且不发 resources/list（client.ts:2005-2007）")
    void fetchMcpSkills_noResourcesCapability_returnsEmptyWithoutRequest() {
        SkillGateTransport fake = new SkillGateTransport(false);
        McpToolPool pool = new McpToolPool(new FakeFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("gate", null);

        assertThat(pool.fetchMcpSkills("gate")).isEmpty();
        assertThat(fake.resourcesListCalled.get()).isFalse();
    }

    // ── ④ 协议异常 → fail-soft [] ──

    @Test
    @DisplayName("resources/list 协议异常 → fail-soft 空 list（client.ts:2021-2027）")
    void fetchMcpSkills_protocolError_returnsEmpty() {
        SkillGateTransport fake = new SkillGateTransport(true);
        fake.failResourcesList = true;
        McpToolPool pool = new McpToolPool(new FakeFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("gate", null);

        assertThat(pool.fetchMcpSkills("gate")).isEmpty();
    }

    @Test
    @DisplayName("resources/read 异常 → 该技能跳过（fail-soft），不整池失败")
    void fetchMcpSkills_readResourceError_skipsThatSkill() {
        SkillGateTransport fake = new SkillGateTransport(true);
        fake.skillContents.put("skill://bad", "---\ndescription: bad\n---\n# Bad\n");
        fake.skillContents.put("skill://good", "---\ndescription: good\n---\n# Good\n");
        fake.failReadUris.add("skill://bad");
        McpToolPool pool = new McpToolPool(new FakeFactory(fake), new ToolRegistry(), client);
        pool.assembleToolPool("gate", null);

        List<Command> skills = pool.fetchMcpSkills("gate");

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getName()).isEqualTo("mcp__gate__good");
    }

    // ── ⑤ gate 语义：McpServerService.refreshMcpSkillCommands gate 关不生产 ──

    @Test
    @DisplayName("refreshMcpSkillCommands gate 关 → 不调用 fetchMcpSkills（client.ts:2174 feature('MCP_SKILLS')）")
    void refreshMcpSkillCommands_gateOff_doesNotProduce() throws Exception {
        McpServerService svc = new McpServerService();
        // 反射注入 mcpToolPool + mcpSkillCommands
        Field poolField = McpServerService.class.getDeclaredField("mcpToolPool");
        poolField.setAccessible(true);
        poolField.set(svc, new RecordingPool());
        Field listField = McpServerService.class.getDeclaredField("mcpSkillCommands");
        listField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Command> list = (List<Command>) listField.get(svc);

        Method refresh = McpServerService.class.getDeclaredMethod("refreshMcpSkillCommands", String.class);
        refresh.setAccessible(true);

        // gate 关 → 不生产（CC :2174 feature('MCP_SKILLS') && supportsResources）
        svc.setMcpSkillsGate(() -> false);
        refresh.invoke(svc, "gate");
        assertThat(list).isEmpty();

        // gate 开 → 生产 skill（fetchMcpSkills 产物落位 mcpSkillCommands）
        svc.setMcpSkillsGate(() -> true);
        refresh.invoke(svc, "gate");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getName()).isEqualTo("mcp__gate__greeting");
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════════

    /** fake factory：每次 create 返回指定 fake transport. */
    static class FakeFactory extends McpTransportFactory {
        private final McpTransport transport;

        FakeFactory(McpTransport transport) { this.transport = transport; }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return transport;
        }
    }

    /**
     * skill 发现测试用 fake transport：resources capability 可控，返回 skill:// 与普通资源，
     * 记录 resources/list 与 resources/read 调用，可配置 resources/list 抛错 + 指定 URI read 失败。
     */
    static class SkillGateTransport implements McpTransport {
        final boolean resourcesCap;
        final AtomicBoolean resourcesListCalled = new AtomicBoolean();
        final AtomicInteger resourcesReadCalled = new AtomicInteger();
        final Map<String, String> skillContents = new java.util.LinkedHashMap<>();
        final Map<String, String> plainContents = new java.util.LinkedHashMap<>();
        final java.util.Set<String> failReadUris = new java.util.HashSet<>();
        final java.util.List<String> readUriHistory = new java.util.ArrayList<>();
        volatile boolean failResourcesList;

        SkillGateTransport(boolean resourcesCap) { this.resourcesCap = resourcesCap; }

        @Override
        public void start(McpTransport.TransportConfig config) {}

        @Override
        @SuppressWarnings("unchecked")
        public CompletableFuture<JsonNode> sendRequest(String method, Object params) {
            ObjectNode result = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                if (resourcesCap) {
                    ObjectNode caps = result.putObject("capabilities");
                    caps.putObject("resources");
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
                for (String uri : skillContents.keySet()) {
                    arr.addObject().put("uri", uri)
                        .put("name", uri.substring("skill://".length()));
                }
                for (String uri : plainContents.keySet()) {
                    arr.addObject().put("uri", uri).put("name", uri.substring(uri.lastIndexOf('/') + 1));
                }
            } else if ("resources/read".equals(method)) {
                resourcesReadCalled.incrementAndGet();
                String uri = (String) ((Map<String, Object>) params).get("uri");
                readUriHistory.add(uri);
                if (failReadUris.contains(uri)) {
                    CompletableFuture<JsonNode> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new IllegalStateException("read boom: " + uri));
                    return failed;
                }
                ArrayNode contents = result.putArray("contents");
                ObjectNode c = contents.addObject();
                c.put("uri", uri);
                String text = skillContents.get(uri);
                if (text == null) text = plainContents.get(uri);
                c.put("text", text == null ? "" : text);
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

    /** gate 语义测试用：记录 fetchMcpSkills 调用，返回固定 skill Command. */
    static class RecordingPool extends McpToolPool {
        private final AtomicInteger fetchMcpSkillsCalls = new AtomicInteger();

        RecordingPool() {
            super(new McpTransportFactory(), new ToolRegistry(), new JsonRpcMcpClient());
        }

        @Override
        public List<Command> fetchMcpSkills(String serverName) {
            fetchMcpSkillsCalls.incrementAndGet();
            Command c = new Command();
            c.setName("mcp__gate__greeting");
            c.setType("prompt");
            c.setSource(CommandSource.MCP);
            c.setDisableModelInvocation(Boolean.FALSE);
            return List.of(c);
        }

        int fetchMcpSkillsCallCount() { return fetchMcpSkillsCalls.get(); }
    }
}
