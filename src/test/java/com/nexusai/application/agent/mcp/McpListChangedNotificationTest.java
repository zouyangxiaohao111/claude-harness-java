package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.skill.CreateSkillCommand;
import com.nexusai.application.agent.skill.McpSkillBuilders;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-15: MCP list_changed 通知集成测试（RED→GREEN）· 对齐 CC
 * {@code useManageMCPConnections.ts:618-751}（setNotificationHandler 注册门控 +
 * fetch* 缓存失效 + skill 池刷新）。
 *
 * <p>链路：InProcessMcpTransport.createLinkedPair() —— [0]=客户端（McpToolPool 持有，
 * assemble 时经 capabilities.listChanged 门控注册 3 类通知处理器），[1]=服务端（fake
 * requestHandler 返回 initialize/tools/list/resources/list/resources/read/prompts/list）。
 * 服务端经 {@code sendNotification("notifications/{tools,prompts,resources}/list_changed")}
 * 推送 → deliverNotification → 客户端 dispatchNotification → 处理器执行缓存失效。
 *
 * <p>断言基于协议往返计数：处理器删除缓存后再 fetch → 必然 miss → 往返计数 +1；
 * 若缓存未失效（如 prompts 不删 skills cache）→ 走缓存命中 → 计数不变。
 */
@DisplayName("P2-15 MCP list_changed 通知：缓存失效 + skill 池刷新")
class McpListChangedNotificationTest {

    private static final McpTransport.TransportConfig CONFIG =
        new McpTransport.TransportConfig("inproc", List.of(), Map.of(), null, null);

    /** skill:// 资源内容（ParseSkillFrontmatter 需合法 frontmatter 才能产出 Command）。 */
    private static final String SKILL_CONTENT =
        "---\nname: Greeting\ndescription: A greeting skill\nuser-invocable: true\n---\n# Greeting\n";

    @BeforeEach
    void registerBuilders() {
        // fetchMcpSkills 内部经 McpSkillBuilders.get() 取函数引用，须先注册
        // （对齐 ToolRegistrationConfig.skillRegistry() @Bean init + McpSkillsDiscoveryTest）
        McpSkillBuilders.register(new McpSkillBuilders.Builders(
            CreateSkillCommand::create,
            ParseSkillFrontmatter::parseSkillFrontmatterFields));
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. resources/list_changed → skills/resources/commands cache 失效 + skill 池刷新
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("resources/list_changed: 删 3 cache + 三路重取 + skill 池刷新（CC :717-738）")
    void resourcesListChanged_invalidatesAllAndRefreshesSkillPool() throws Exception {
        // WHY: server 资源变化必须立即反映（CC useManageMCPConnections.ts:706-751）——
        // 否则 fetchMcpSkillsForClient 缓存快照让 skill 发现永不过期（陈旧技能）
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        CountingServer server = new CountingServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        // P1-9: 默认 gate=false（对齐 CC 生产）→ 本测试验证 MCP_SKILLS=true 分支（CC :718-738），显式开 gate
        pool.setMcpSkillsGate(() -> true);

        AtomicInteger refresherCount = new AtomicInteger();
        List<Command> refreshedSkills = new CopyOnWriteArrayList<>();
        pool.setSkillPoolRefresher(name -> {
            refresherCount.incrementAndGet();
            refreshedSkills.clear();
            // 对齐 McpServerService.refreshMcpSkillCommands：重建 skill 池
            refreshedSkills.addAll(pool.fetchMcpSkills(name));
        });

        pool.assembleToolPool("svr", CONFIG);

        // 预热缓存（各 1 次往返；fetchMcpSkills = resources/list + resources/read）
        assertThat(pool.fetchResources("svr")).isNotEmpty();
        assertThat(pool.fetchCommands("svr")).hasSize(1);
        assertThat(pool.fetchMcpSkills("svr")).isNotEmpty();
        assertThat(pool.fetchTools("svr")).hasSize(1);
        assertThat(server.resourcesList.get()).isEqualTo(2); // fetchResources + fetchMcpSkills
        assertThat(server.promptsList.get()).isEqualTo(1);
        assertThat(server.reads.get()).isEqualTo(1);
        assertThat(server.toolsList.get()).isEqualTo(1);

        // 服务端推送 resources/list_changed
        pair[1].sendNotification("notifications/resources/list_changed", Map.of());
        awaitTrue(() -> refresherCount.get() >= 1); // refresher 是处理器最后一步 → 完成信号

        // 三 cache 全部失效 → 各自重取 +1 次往返
        assertThat(server.resourcesList.get()).isEqualTo(4); // +fetchResources +fetchMcpSkills
        assertThat(server.promptsList.get()).isEqualTo(2);   // +fetchCommands
        assertThat(server.reads.get()).isEqualTo(2);         // +skills 重新 read
        // skill 池刷新回调携带新 skills（CC :738 clearSkillIndexCache 等价落点）
        assertThat(refreshedSkills).isNotEmpty();
        assertThat(refreshedSkills)
            .anyMatch(c -> c.getName() != null && c.getName().contains("skillA"));
        // 刷新后消费侧拿到新快照
        assertThat(pool.fetchResources("svr")).isNotEmpty();
        assertThat(pool.fetchMcpSkills("svr")).isNotEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // P3-5: list_changed → clearSkillIndexCache 挂钩（CC useManageMCPConnections.ts:694/:738）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P3-5 resources/list_changed: 触发 skillIndexClearer 挂钩（CC :738 clearSkillIndexCache?.()）")
    void resourcesListChanged_triggersSkillIndexClearer() throws Exception {
        // WHY: CC resources/list_changed 处理器在 updateServer 后调 clearSkillIndexCache?.()
        // （useManageMCPConnections.ts:738）——MCP skills 集合变化 → skill-search 索引失效，
        // 下次 discovery 用新集合重建。若 Java 挂钩不触发，索引将引用陈旧快照。
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        CountingServer server = new CountingServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        // P1-9: 默认 gate=false（对齐 CC 生产）→ 本测试验证 MCP_SKILLS=true 分支（CC :718-738），显式开 gate
        pool.setMcpSkillsGate(() -> true);
        AtomicInteger clearCount = new AtomicInteger();
        pool.setSkillIndexClearer(clearCount::incrementAndGet);
        pool.setSkillPoolRefresher(name -> {
            // 占位：断言 focus 在 skillIndexClearer，不放空 refresher 干扰 await
        });

        pool.assembleToolPool("svr", CONFIG);
        pool.fetchMcpSkills("svr");
        pool.fetchCommands("svr");

        pair[1].sendNotification("notifications/resources/list_changed", Map.of());
        awaitTrue(() -> clearCount.get() >= 1);

        assertThat(clearCount.get())
            .as("P3-5 resources/list_changed 必须触发 clearSkillIndexCache 挂钩")
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("P3-5 prompts/list_changed: 触发 skillIndexClearer 但 skill 池不刷新（CC :694 vs :681-687）")
    void promptsListChanged_triggersSkillIndexClearer_butNotSkillPoolRefresh() throws Exception {
        // WHY: CC prompts/list_changed 处理器「Skills come from resources, not prompts — don't
        // invalidate their cache here」（:684 注释），skills cache 不删；但 :694 仍调
        // clearSkillIndexCache?.()（updateServer 写入 commands 后索引可能引用旧快照）。
        // 两个断言必须同时成立：clear 触发 + skill 池 refresher 不被调。
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        CountingServer server = new CountingServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        AtomicInteger clearCount = new AtomicInteger();
        AtomicInteger refresherCount = new AtomicInteger();
        pool.setSkillIndexClearer(clearCount::incrementAndGet);
        pool.setSkillPoolRefresher(name -> refresherCount.incrementAndGet());

        pool.assembleToolPool("svr", CONFIG);
        pool.fetchCommands("svr");
        pool.fetchMcpSkills("svr");

        pair[1].sendNotification("notifications/prompts/list_changed", Map.of());
        // WHY await clearCount 而非 promptsList++：handlePromptsListChanged 的最后一步是
        // skillIndexClearer.run()（CC :694，McpToolPool.java:703），而 promptsList++ 在 :696
        // fetchCommands 时已发生。若 await 中间量 promptsList，断言会撞上处理器未跑到 clear 的
        // 竞态窗口 → clearCount==0（复现 P2-15 记录的「期望 1 实为 0」；batch 运行时共享
        // ForkJoinPool 被其他测试占用放大窗口）。clearCount>=1 即处理器完整跑完，随后 ==1 精确。
        awaitTrue(() -> clearCount.get() >= 1);

        assertThat(clearCount.get())
            .as("P3-5 prompts/list_changed 必须触发 clearSkillIndexCache 挂钩（CC :694）")
            .isEqualTo(1);
        assertThat(refresherCount.get())
            .as("prompts/list_changed 不调 skill 池刷新（skills 走缓存，CC :684 注释）")
            .isZero();
    }

    @Test
    @DisplayName("P3-5 默认未注入 skillIndexClearer → list_changed no-op 不抛")
    void listChanged_withoutSkillIndexClearer_noOp() throws Exception {
        // WHY: 默认 skillIndexClearer 是 no-op（concern #30 子系统范围外）。未接线时
        // resources/prompts list_changed 不得 NPE/抛异常 —— 若默认值改为 null 直接 run()，本测试 fail。
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        CountingServer server = new CountingServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());

        pool.assembleToolPool("svr", CONFIG);
        pool.fetchMcpSkills("svr");
        pool.fetchCommands("svr");
        int resBefore = server.resourcesList.get();
        int promptsBefore = server.promptsList.get();

        pair[1].sendNotification("notifications/resources/list_changed", Map.of());
        awaitTrue(() -> server.resourcesList.get() >= resBefore + 1);
        pair[1].sendNotification("notifications/prompts/list_changed", Map.of());
        awaitTrue(() -> server.promptsList.get() >= promptsBefore + 1);

        // 走到这里即证明 no-op 不抛（缓存刷新行为不受影响）
        assertThat(server.resourcesList.get()).isGreaterThanOrEqualTo(resBefore + 1);
        assertThat(server.promptsList.get()).isGreaterThanOrEqualTo(promptsBefore + 1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. prompts/list_changed → 只失效 commands cache，skills 走缓存
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("prompts/list_changed: 只删 commands cache、skills 走缓存命中（CC :681-687）")
    void promptsListChanged_invalidatesOnlyCommandsCache() throws Exception {
        // WHY: CC :681 注释「Skills come from resources, not prompts — don't invalidate
        // their cache here」——prompts 变化不影响 skills 缓存，删除会造成无谓重取
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        CountingServer server = new CountingServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        AtomicInteger refresherCount = new AtomicInteger();
        pool.setSkillPoolRefresher(name -> refresherCount.incrementAndGet());

        pool.assembleToolPool("svr", CONFIG);

        pool.fetchCommands("svr");
        pool.fetchMcpSkills("svr");
        int promptsBefore = server.promptsList.get();
        int resBefore = server.resourcesList.get();
        int readsBefore = server.reads.get();

        pair[1].sendNotification("notifications/prompts/list_changed", Map.of());
        awaitTrue(() -> server.promptsList.get() >= promptsBefore + 1);

        // commands cache 失效 → prompts/list +1 次往返
        assertThat(server.promptsList.get()).isEqualTo(promptsBefore + 1);
        // skills cache 未删 → 无新增 resources/list + resources/read 往返（缓存命中）
        assertThat(server.resourcesList.get()).isEqualTo(resBefore);
        assertThat(server.reads.get()).isEqualTo(readsBefore);
        // skill 池不刷新（CC :684 注释「Skills come from resources, not prompts」——skills 走缓存，
        // skillPoolRefresher 不被调；P3-5 后 clearSkillIndexCache 挂钩独立于 skill 池另行触发，
        // 见 promptsListChanged_triggersSkillIndexClearer_butNotSkillPoolRefresh）
        assertThat(refresherCount.get()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. tools/list_changed → tools cache 失效 + serverTools 更新
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("tools/list_changed: tools cache 失效并重取（CC :631/:656）")
    void toolsListChanged_invalidatesToolsCache() throws Exception {
        // WHY: server 工具集变化必须即时反映（CC useManageMCPConnections.ts:618-665）
        // ——否则陈旧 tools 快照继续暴露已下线工具
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        CountingServer server = new CountingServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());

        pool.assembleToolPool("svr", CONFIG);
        assertThat(pool.fetchTools("svr")).hasSize(1);
        int toolsBefore = server.toolsList.get();

        pair[1].sendNotification("notifications/tools/list_changed", Map.of());
        awaitTrue(() -> server.toolsList.get() >= toolsBefore + 1);

        // tools cache 失效 → tools/list +1 次往返；serverTools 更新为新集合
        assertThat(server.toolsList.get()).isEqualTo(toolsBefore + 1);
        assertThat(pool.fetchTools("svr")).hasSize(1);
        assertThat(pool.getServerTools("svr")).isPresent();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. gate 关 → resources/list_changed 仅重取 resources（CC :740-741 else 分支）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MCP_SKILLS gate 关: resources/list_changed 仅重取 resources、不刷新 skill 池")
    void resourcesListChanged_gateOff_refetchesResourcesOnly() throws Exception {
        // WHY: CC feature('MCP_SKILLS')=false 折叠 → :740-741 else 分支只 refetch resources，
        // 不删 skills/commands cache、不触发 skill 池刷新（X23 gate 默认 true，可配置关）
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        CountingServer server = new CountingServer(pair[1]);
        McpToolPool pool = new McpToolPool(new FakeFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        pool.setMcpSkillsGate(() -> false);
        AtomicInteger refresherCount = new AtomicInteger();
        pool.setSkillPoolRefresher(name -> refresherCount.incrementAndGet());

        pool.assembleToolPool("svr", CONFIG);

        pool.fetchResources("svr");
        pool.fetchMcpSkills("svr");
        int resBefore = server.resourcesList.get();
        int promptsBefore = server.promptsList.get();
        int readsBefore = server.reads.get();

        pair[1].sendNotification("notifications/resources/list_changed", Map.of());
        awaitTrue(() -> server.resourcesList.get() >= resBefore + 1);

        // 仅 resources cache 失效 → resources/list +1
        assertThat(server.resourcesList.get()).isEqualTo(resBefore + 1);
        // skills/commands cache 未删、skill 池不刷新
        assertThat(server.reads.get()).isEqualTo(readsBefore);
        assertThat(server.promptsList.get()).isEqualTo(promptsBefore);
        assertThat(refresherCount.get()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** fake factory: 每次 create 返回指定 transport（本测试为 InProcess 客户端侧 pair[0]）. */
    static class FakeFactory extends McpTransportFactory {
        private final McpTransport transport;

        FakeFactory(McpTransport transport) { this.transport = transport; }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return transport;
        }
    }

    /**
     * fake MCP server：记录各 method 往返计数，返回 initialize（listChanged=true 门控）、
     * tools/list、resources/list（skill:// + plain）、resources/read、prompts/list。
     */
    static class CountingServer {
        final AtomicInteger toolsList = new AtomicInteger();
        final AtomicInteger resourcesList = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger promptsList = new AtomicInteger();

        CountingServer(InProcessMcpTransport server) {
            // 服务端侧必须先 start（deliverRequest/deliverNotification 校验 peer 连接态）
            server.start(CONFIG);
            server.setRequestHandler((method, params) -> {
                switch (method) {
                    case "initialize":
                        return Map.of(
                            "protocolVersion", "2024-11-05",
                            "serverInfo", Map.of("name", "svr-server", "version", "1.0.0"),
                            "capabilities", Map.of(
                                "tools", Map.of("listChanged", true),
                                "resources", Map.of("listChanged", true),
                                "prompts", Map.of("listChanged", true)));
                    case "tools/list":
                        toolsList.incrementAndGet();
                        return Map.of("tools", List.of(
                            Map.of("name", "toolA", "description", "Tool A", "inputSchema", Map.of())));
                    case "resources/list":
                        resourcesList.incrementAndGet();
                        return Map.of("resources", List.of(
                            Map.of("uri", "skill://skillA", "name", "skillA"),
                            Map.of("uri", "plain://doc", "name", "doc")));
                    case "resources/read":
                        reads.incrementAndGet();
                        String uri = params.path("uri").asText("");
                        return Map.of("contents", List.of(Map.of("uri", uri, "text", SKILL_CONTENT)));
                    case "prompts/list":
                        promptsList.incrementAndGet();
                        return Map.of("prompts", List.of(
                            Map.of("name", "promptA", "description", "Prompt A")));
                    default:
                        return Map.of();
                }
            });
        }
    }

    /** 轮询等待异步通知处理器完成（InProcess 分发在 ForkJoinPool 线程执行）。 */
    private static void awaitTrue(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timeout: async list_changed handler did not complete");
            }
            Thread.sleep(10);
        }
    }
}
