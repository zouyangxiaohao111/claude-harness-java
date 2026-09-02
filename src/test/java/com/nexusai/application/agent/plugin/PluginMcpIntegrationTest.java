package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.ChannelNotificationGate;
import com.nexusai.application.agent.mcp.ChannelAllowlist;
import com.nexusai.application.agent.mcp.EnvExpansion;
import com.nexusai.application.agent.mcp.McpTypesRegistry;
import com.nexusai.application.agent.mcp.McpTypesRegistry.ConfigScope;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpSSEServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpSdkServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpStdioServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.McpWebSocketServerConfig;
import com.nexusai.application.agent.mcp.McpTypesRegistry.ScopedMcpServerConfig;
import com.nexusai.application.agent.plugin.PluginMcpIntegration.PluginError;
import com.nexusai.application.agent.plugin.PluginMcpIntegration.PluginView;
import com.nexusai.application.agent.plugin.PluginMcpIntegration.UnconfiguredChannel;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 插件 MCP 加载全链 F1-F5 测试（S08 验收 1/2/3/4）· 对齐 CC mcpPluginIntegration.ts:131-582。
 *
 * <p>WHY（规则九 · 验证意图）：F1-F4 全链 + F5 校验链是插件 MCP 供给的唯一生产路径，
 * 且 0 消费方状态消除（验收 3）与 pluginSource 注入联动（验收 4）需行为断言而非存在性断言。
 */
class PluginMcpIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path pluginPath;
    private PluginMcpIntegration integration;
    private McpbHandler mcpbHandler;

    @BeforeEach
    void setUp() {
        pluginPath = tempDir.resolve("plugin");
        mcpbHandler = new McpbHandler();
        // 存储 stub（f5/saveMcpServerUserConfig 需要）：与 McpbHandlerTest 同构
        mcpbHandler.setConfigStorage(new McpbHandlerTest.MapConfigStorage());
        mcpbHandler.setSecureValueStore(new McpbHandlerTest.MapSecureStore());
        // env 查找函数可控：API_KEY/TOKEN 命中，其它缺失
        integration = new PluginMcpIntegration(mcpbHandler,
            new EnvExpansion(name -> Map.of("API_KEY", "k1", "TOKEN", "t0").get(name)));
        PluginDirectories.setPluginCacheDirOverride(tempDir.resolve("plugins-home").toString());
    }

    @AfterEach
    void tearDown() {
        PluginDirectories.setPluginCacheDirOverride(null);
    }

    // ============== F1 loadPluginMcpServers（CC :131-212） ==============

    @Test
    @DisplayName("F1：.mcp.json 最低优先加载 + 空返 null")
    void f1_dotMcpJsonLowestPriority() throws Exception {
        Files.createDirectories(pluginPath);
        writeJson(pluginPath.resolve(".mcp.json"), json(
            "mcpServers", json("lowSrv", json("command", "echo", "args", List.of("a")))));
        // manifest 无 mcpServers → 仅 .mcp.json
        PluginView plugin = plugin("p1", json());
        Map<String, McpServerConfig> servers = integration.loadPluginMcpServers(plugin);
        assertThat(servers).containsOnlyKeys("lowSrv");
        assertThat(((McpStdioServerConfig) servers.get("lowSrv")).command()).isEqualTo("echo");
        // manifest mcpServers 同名校覆盖 .mcp.json（:143 合并序）
        writeJson(pluginPath.resolve(".mcp.json"), json(
            "mcpServers", json("dup", json("command", "low"))));
        PluginView plugin2 = plugin("p1", json("mcpServers", json("dup", json("command", "high"))));
        Map<String, McpServerConfig> servers2 = integration.loadPluginMcpServers(plugin2);
        assertThat(((McpStdioServerConfig) servers2.get("dup")).command()).isEqualTo("high");
        // 空 → null（:211）· 用无 .mcp.json 的独立目录
        Path emptyPath = tempDir.resolve("empty-plugin");
        Files.createDirectories(emptyPath);
        PluginView empty = new PluginView("empty", emptyPath, "empty", true, json());
        assertThat(integration.loadPluginMcpServers(empty)).isNull();
    }

    @Test
    @DisplayName("F1：manifest mcpServers object 直接合并（含 sse/sdk 校验）")
    void f1_manifestObject() throws Exception {
        Files.createDirectories(pluginPath);
        ObjectNode manifest = json("mcpServers", json(
            "stdio", json("command", "node", "args", List.of("a", "b")),
            "remote", json("type", "sse", "url", "http://x", "headers", json("Auth", "Bearer")),
            "sdkSrv", json("type", "sdk", "name", "my-sdk")));
        Map<String, McpServerConfig> servers = integration.loadPluginMcpServers(plugin("p1", manifest));
        assertThat(servers).containsOnlyKeys("stdio", "remote", "sdkSrv");
        McpSSEServerConfig remote = (McpSSEServerConfig) servers.get("remote");
        assertThat(remote.url()).isEqualTo("http://x");
        assertThat(remote.headers()).isEqualTo(Map.of("Auth", "Bearer"));
        // 无效配置（command 缺失）跳过
        ObjectNode badManifest = json("mcpServers", json("bad", json("type", "stdio"), "ok", json("command", "c")));
        assertThat(integration.loadPluginMcpServers(plugin("p2", badManifest))).containsOnlyKeys("ok");
    }

    @Test
    @DisplayName("F1：manifest mcpServers string 三态（MCPB / 文件路径 / 其它）")
    void f1_manifestString() throws Exception {
        Files.createDirectories(pluginPath);
        // string = 文件路径
        writeJson(pluginPath.resolve("servers.json"), json("s1", json("command", "from-file")));
        Map<String, McpServerConfig> fromFile = integration.loadPluginMcpServers(
            plugin("p1", json("mcpServers", "servers.json")));
        assertThat(((McpStdioServerConfig) fromFile.get("s1")).command()).isEqualTo("from-file");
        // string = MCPB（isMcpbSource → loadMcpServersFromMcpb，server 名取 manifest.name）
        writeJson(pluginPath.resolve("mcpb.json"), json("name", "bundleSrv", "version", "1.0.0",
            "author", json("name", "t"),
            "server", json("entrypoint", json("command", "bundle-cmd"))));
        byte[] zip = McpbHandlerTest.buildZip(Map.of("manifest.json",
            Files.readString(pluginPath.resolve("mcpb.json")).getBytes(StandardCharsets.UTF_8)));
        Files.write(pluginPath.resolve("bundle.mcpb"), zip);
        Map<String, McpServerConfig> fromMcpb = integration.loadPluginMcpServers(
            plugin("p1", json("mcpServers", "bundle.mcpb")));
        assertThat(fromMcpb).containsOnlyKeys("bundleSrv");
        assertThat(((McpStdioServerConfig) fromMcpb.get("bundleSrv")).command()).isEqualTo("bundle-cmd");
        // 不存在路径 → null 不炸
        assertThat(integration.loadPluginMcpServers(plugin("p1", json("mcpServers", "nope.json")))).isNull();
    }

    @Test
    @DisplayName("F1：manifest mcpServers array 多源按序 last-wins + 单 spec 失败不丢其它")
    void f1_manifestArray() throws Exception {
        Files.createDirectories(pluginPath);
        writeJson(pluginPath.resolve("a.json"), json("dup", json("command", "a"), "onlyA", json("command", "a2")));
        writeJson(pluginPath.resolve("b.json"), json("dup", json("command", "b")));
        writeJson(pluginPath.resolve("bad.json"), "{not json");
        ObjectNode manifest = json("mcpServers", List.of("a.json", "bad.json", "b.json",
            json("inline", json("command", "inline-cmd"))));
        Map<String, McpServerConfig> servers = integration.loadPluginMcpServers(plugin("p1", manifest));
        // bad.json 失败被防御性 catch（:189-197），不丢 a/b/inline 结果
        assertThat(servers).containsOnlyKeys("dup", "onlyA", "inline");
        assertThat(((McpStdioServerConfig) servers.get("dup")).command()).isEqualTo("b"); // last-wins
        assertThat(((McpStdioServerConfig) servers.get("inline")).command()).isEqualTo("inline-cmd");
    }

    // ============== F2 addPluginScopeToServers（CC :341-360） ==============

    @Test
    @DisplayName("F2：plugin: 前缀 + scope=dynamic + pluginSource 透传 + 注册表")
    void f2_scopeAndPluginSource() {
        Map<String, McpServerConfig> servers = Map.of(
            "srv1", new McpStdioServerConfig("echo", List.of(), null));
        Map<String, ScopedMcpServerConfig> scoped = integration.addPluginScopeToServers(
            servers, "slack", "slack@anthropic");
        assertThat(scoped).containsOnlyKeys("plugin:slack:srv1");
        ScopedMcpServerConfig s = scoped.get("plugin:slack:srv1");
        assertThat(s.scope()).isEqualTo(ConfigScope.DYNAMIC);
        assertThat(s.pluginSource()).isEqualTo("slack@anthropic");
        // 运行期注册表（McpServerService resolver 消费）
        assertThat(integration.pluginSourceFor("plugin:slack:srv1")).isEqualTo("slack@anthropic");
        // 未登记 scoped 名 → 派生（enabledPlugins 键匹配）
        integration.setEnabledPluginsSupplier(() -> Map.of("slack@anthropic", true));
        assertThat(integration.pluginSourceFor("plugin:slack:other")).isEqualTo("slack@anthropic");
        // 非 plugin 前缀 → null
        assertThat(integration.pluginSourceFor("mcp__server")).isNull();
        // pluginSource 为 null → derive（Q-09-R2-4 边界 B）
        Map<String, ScopedMcpServerConfig> derived = integration.addPluginScopeToServers(
            servers, "slack", null);
        assertThat(derived.get("plugin:slack:srv1").pluginSource()).isEqualTo("slack@anthropic");
    }

    // ============== F3 extractMcpServersFromPlugins（CC :366-429） ==============

    @Test
    @DisplayName("F3：enabled 过滤 + 逐 server 坏配置不炸整体 + 未解析缓存")
    void f3_extractWithIsolation() throws Exception {
        Files.createDirectories(pluginPath);
        // 插件 A：enabled，两个 server —— 一个 env 缺失（mcp-config-invalid），一个正常
        ObjectNode manifestA = json("mcpServers", json(
            "good", json("command", "echo", "env", json("K", "${API_KEY}")),
            "bad", json("command", "echo", "env", json("K", "${MISSING_VAR}"))));
        // 插件 B：disabled → 不贡献
        ObjectNode manifestB = json("mcpServers", json("bSrv", json("command", "b")));
        PluginView a = plugin("alpha", manifestA, "alpha@market");
        PluginView b = new PluginView("beta", pluginPath, "beta@market", false, manifestB);
        List<PluginError> errors = new ArrayList<>();

        Map<String, ScopedMcpServerConfig> all = integration.extractMcpServersFromPlugins(List.of(a, b), errors);

        assertThat(all).containsOnlyKeys("plugin:alpha:good", "plugin:alpha:bad");
        assertThat(all).doesNotContainKeys("plugin:beta:bSrv"); // disabled 过滤（:374）
        // 坏配置不炸整体（:396-403 只对 resolvePluginMcpEnvironment 内部 throw 生效；
        // 此处缺变量 → mcp-config-invalid 入列，server 保留字面量）
        assertThat(errors).anyMatch(e -> "mcp-config-invalid".equals(e.type())
            && "plugin:alpha".equals(e.source()) && "bad".equals(e.serverName()));
        // 未解析 servers 缓存（:408）：命令仍含 ${...} 字面量
        assertThat(a.mcpServers()).containsOnlyKeys("good", "bad");
        McpStdioServerConfig cachedGood = (McpStdioServerConfig) a.mcpServers().get("good");
        assertThat(cachedGood.env()).isEqualTo(Map.of("K", "${API_KEY}"));
        // resolved 侧 env 已展开
        McpStdioServerConfig resolvedGood =
            (McpStdioServerConfig) ((ScopedMcpServerConfig) all.get("plugin:alpha:good")).config();
        assertThat(resolvedGood.env()).containsEntry("K", "k1");
    }

    @Test
    @DisplayName("F3：单 server resolve 抛错（user_config 缺失）→ generic-error 不炸全插件")
    void f3_userConfigMissingGeneratesGenericError() throws Exception {
        Files.createDirectories(pluginPath);
        // manifest 声明 userConfig（触发 topLevel={}）+ server 引用 ${user_config.X} →
        // substituteUserConfigVariables throw → per-server catch → generic-error（:396-403）
        ObjectNode manifest = json(
            "userConfig", json("token", json("type", "string")),
            "mcpServers", json(
                "usesCfg", json("command", "echo", "args", List.of("${user_config.token}")),
                "plain", json("command", "plain-cmd")));
        List<PluginError> errors = new ArrayList<>();
        Map<String, ScopedMcpServerConfig> all = integration.extractMcpServersFromPlugins(
            List.of(plugin("alpha", manifest, "alpha@market")), errors);
        // usesCfg 失败（generic-error），plain 仍加载
        assertThat(all).containsOnlyKeys("plugin:alpha:plain");
        assertThat(errors).anyMatch(e -> "generic-error".equals(e.type())
            && "alpha".equals(e.plugin()) && "usesCfg".equals(e.source()));
    }

    // ============== F4 resolvePluginMcpEnvironment（CC :465-582） ==============

    @Test
    @DisplayName("F4：stdio 分支 ROOT/DATA 注入 + command/args/env 解析 + user_config 替换")
    void f4_stdioResolution() throws Exception {
        Files.createDirectories(pluginPath);
        PluginView plugin = plugin("p1", json(), "p1@market");
        McpStdioServerConfig config = new McpStdioServerConfig(
            "${CLAUDE_PLUGIN_ROOT}/bin/srv",
            List.of("--token", "${user_config.token}", "--raw", "${MISSING_KEEP}"),
            new LinkedHashMap<>(Map.of(
                "API", "${API_KEY}",
                "ROOT", "${CLAUDE_PLUGIN_ROOT}",
                "DATA", "${CLAUDE_PLUGIN_DATA}")));
        List<PluginError> errors = new ArrayList<>();
        McpServerConfig resolved = integration.resolvePluginMcpEnvironment(
            config, plugin, Map.of("token", "t1"), errors, "p1", "srv1");

        McpStdioServerConfig stdio = (McpStdioServerConfig) resolved;
        assertThat(stdio.command()).isEqualTo(
            normalize(pluginPath.resolve("bin/srv").toString()));
        assertThat(stdio.args()).containsExactly("--token", "t1", "--raw", "${MISSING_KEEP}");
        assertThat(stdio.env()).containsEntry("CLAUDE_PLUGIN_ROOT", pluginPath.toString());
        assertThat(stdio.env()).containsEntry("API", "k1");
        assertThat(stdio.env()).containsEntry("ROOT", normalize(pluginPath.toString()));
        assertThat(stdio.env()).containsKey("CLAUDE_PLUGIN_DATA");
        // 缺失变量（MISSING_KEEP）→ mcp-config-invalid 入列
        assertThat(errors).anyMatch(e -> "mcp-config-invalid".equals(e.type())
            && "p1".equals(e.plugin()) && "srv1".equals(e.serverName()));
    }

    @Test
    @DisplayName("F4：${user_config.X} 缺失 → throw（插件作者 bug 响亮失败）")
    void f4_userConfigMissingThrows() throws Exception {
        Files.createDirectories(pluginPath);
        PluginView plugin = plugin("p1", json(), "p1@market");
        McpStdioServerConfig config = new McpStdioServerConfig("echo",
            List.of("${user_config.nope}"), null);
        assertThatThrownBy(() -> integration.resolvePluginMcpEnvironment(
            config, plugin, Map.of(), null, "p1", "srv"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required user configuration value: nope");
        // 无 userConfig（null）→ 跳过替换，字面量保留
        McpServerConfig raw = integration.resolvePluginMcpEnvironment(
            new McpStdioServerConfig("echo", List.of("${user_config.nope}"), null),
            plugin, null, null, "p1", "srv");
        assertThat(((McpStdioServerConfig) raw).args()).containsExactly("${user_config.nope}");
    }

    @Test
    @DisplayName("F4：sse/http/ws 分支 url+headers 解析；sdk/透传类型原样")
    void f4_remoteAndPassthrough() throws Exception {
        Files.createDirectories(pluginPath);
        PluginView plugin = plugin("p1", json(), "p1@market");
        // sse：url + headers 解析
        McpSSEServerConfig sse = new McpSSEServerConfig("${user_config.host}/events",
            new LinkedHashMap<>(Map.of("Authorization", "Bearer ${TOKEN}")), null, null);
        McpSSEServerConfig resolvedSse = (McpSSEServerConfig) integration.resolvePluginMcpEnvironment(
            sse, plugin, Map.of("host", "http://h"), null, "p1", "s1");
        assertThat(resolvedSse.url()).isEqualTo("http://h/events");
        assertThat(resolvedSse.headers()).isEqualTo(Map.of("Authorization", "Bearer t0"));
        // ws 同链
        McpWebSocketServerConfig ws = new McpWebSocketServerConfig("${user_config.host}/ws", null, null);
        McpWebSocketServerConfig resolvedWs = (McpWebSocketServerConfig) integration.resolvePluginMcpEnvironment(
            ws, plugin, Map.of("host", "ws://h"), null, "p1", "s2");
        assertThat(resolvedWs.url()).isEqualTo("ws://h/ws");
        // sdk 透传原样（:550-556）
        McpSdkServerConfig sdk = new McpSdkServerConfig("x");
        assertThat(integration.resolvePluginMcpEnvironment(sdk, plugin, null, null, "p1", "s3"))
            .isSameAs(sdk);
    }

    @Test
    @DisplayName("F4：${VAR:-default} 默认值展开（复用 EnvExpansion，CC :484-489）")
    void f4_envDefaultExpansion() throws Exception {
        Files.createDirectories(pluginPath);
        PluginView plugin = plugin("p1", json(), "p1@market");
        McpStdioServerConfig config = new McpStdioServerConfig("echo",
            List.of("${UNSET_VAR:-fallback}"), null);
        McpStdioServerConfig resolved = (McpStdioServerConfig) integration.resolvePluginMcpEnvironment(
            config, plugin, null, null, "p1", "s");
        assertThat(resolved.args()).containsExactly("fallback");
    }

    // ============== F5 getUnconfiguredChannels（CC :290-318） ==============

    @Test
    @DisplayName("F5：无 schema 跳过 / 已配置跳过 / 未配置列出 + displayName 回退")
    void f5_unconfiguredChannels() {
        ObjectNode manifest = json("channels", List.of(
            json("server", "s1", "userConfig", json("k", json("type", "string", "required", true))),
            json("server", "s2", "displayName", "Nice Name",
                "userConfig", json("k", json("type", "string", "required", true))),
            json("server", "s3"), // 无 userConfig schema → 跳过（:304-306）
            json("server", "s4", "userConfig", json("k", json("type", "string", "required", true)))));
        // s4 已保存配置满足 → 跳过
        mcpbHandler.saveMcpServerUserConfig("plug@market", "s4",
            Map.of("k", "v"), Map.of("k", Map.of("type", "string", "required", true)));

        PluginView plugin = new PluginView("plug", pluginPath, "plug@market", true, manifest, "plug@market");
        List<UnconfiguredChannel> unconfigured = integration.getUnconfiguredChannels(plugin);
        assertThat(unconfigured).extracting(UnconfiguredChannel::server).containsExactly("s1", "s2");
        assertThat(unconfigured).extracting(UnconfiguredChannel::displayName)
            .containsExactly("s1", "Nice Name"); // displayName ?? server（:312）
        assertThat(unconfigured.get(0).configSchema()).containsKey("k");
        // 无 channels → []
        assertThat(integration.getUnconfiguredChannels(plugin("p2", json()))).isEmpty();
    }

    // ============== 接线联动（S08 验收 3/4：0 消费方消除 + gate 门序[4]） ==============

    @Test
    @DisplayName("联动①：F1 经 PluginMcpIntegration 消费 McpbHandler（生产消费点 ≥1）")
    void linkage_f1ConsumesMcpbHandler() throws Exception {
        Files.createDirectories(pluginPath);
        // mcpServers = MCPB → F1 内部走 McpbHandler.loadMcpbFile（needs-config 非错误路径）
        ObjectNode needsConfigManifest = json("name", "needy", "version", "1.0.0",
            "author", json("name", "t"),
            "user_config", json("apiKey", json("type", "string", "required", true)),
            "server", json("entrypoint", json("command", "needy-cmd")));
        Files.write(pluginPath.resolve("needy.mcpb"), McpbHandlerTest.buildZip(
            Map.of("manifest.json", needsConfigManifest.toString().getBytes(StandardCharsets.UTF_8))));
        // needs-config → 非错误 null（:55-64）
        assertThat(integration.loadPluginMcpServers(
            plugin("needy", json("mcpServers", "needy.mcpb")))).isNull();
        // 错误分类：损坏 MCPB → mcpb-invalid-manifest（消费链 + 错误分类双证明）
        Files.write(pluginPath.resolve("broken.mcpb"), McpbHandlerTest.buildZip(
            Map.of("manifest.json", "{bad".getBytes(StandardCharsets.UTF_8))));
        List<PluginError> errors = new ArrayList<>();
        assertThat(integration.loadPluginMcpServers(
            plugin("needy", json("mcpServers", "broken.mcpb")), errors)).isNull();
        assertThat(errors).anyMatch(e -> "mcpb-invalid-manifest".equals(e.type())
            && "needy".equals(e.plugin()) && "broken.mcpb".equals(e.mcpbPath()));
    }

    @Test
    @DisplayName("联动②：pluginSourceFor → gate 门序[4] marketplace 校验 → register")
    void linkage_pluginSourceToGate() {
        // scoped server 登记（addPluginScopeToServers 侧）
        integration.addPluginScopeToServers(
            Map.of("ch1", new McpStdioServerConfig("echo", List.of(), null)),
            "slack", "slack@anthropic");
        // 门序前置：capability + channelsEnabled + session 白名单 + ledger 匹配（:278-301）
        ChannelNotificationGate gate = new ChannelNotificationGate(
            () -> true,
            () -> List.of(new ChannelAllowlist.ChannelEntry("plugin", "slack", "anthropic", false),
                new ChannelAllowlist.ChannelEntry("plugin", "unknown", "evil", false)),
            () -> List.of(new ChannelAllowlistEntry("anthropic", "slack")),
            s -> s == null ? "" : s);
        ChannelNotificationGate.ServerCapabilities caps =
            new ChannelNotificationGate.ServerCapabilities(Map.of("claude/channel", Map.of()));
        // resolver → gateChannelServer（McpToolPool:1359 同参调用形态）
        ChannelNotificationGate.ChannelGateResult r = gate.gateChannelServer(
            "plugin:slack:ch1", caps, integration.pluginSourceFor("plugin:slack:ch1"));
        assertThat(r.action()).isEqualTo("register");
        // 反例：marketplace 不匹配 → 门序[4] fail-closed（MARKETPLACE）
        ChannelNotificationGate.GateKind wrongKind = gate.gateChannelServer(
            "plugin:slack:ch1", caps, "slack@evil").kind();
        assertThat(wrongKind).isEqualTo(ChannelNotificationGate.GateKind.MARKETPLACE);
        // 反例：resolver 无来源（未登记 + 无 enabledPlugins 键）→ pluginSource=null → fail-closed
        ChannelNotificationGate.GateKind nullKind = gate.gateChannelServer(
            "plugin:unknown:ch1", caps, integration.pluginSourceFor("plugin:unknown:ch1")).kind();
        assertThat(nullKind).isEqualTo(ChannelNotificationGate.GateKind.MARKETPLACE);
    }

    @Test
    @DisplayName("联动③：McpServerService.pluginSourceResolver 未装配 fail-closed + 装配后供给")
    void linkage_mcpServerServiceResolver() {
        // 未装配（null）→ 恒 null（测试直构/插件域禁用 → fail-closed，不破坏现有测试）
        Function<String, String> noop = McpServerService.pluginSourceResolver(null);
        assertThat(noop.apply("plugin:slack:ch1")).isNull();
        // 装配 PluginMcpIntegration（含登记）→ 供给真实来源
        integration.addPluginScopeToServers(
            Map.of("ch1", new McpStdioServerConfig("echo", List.of(), null)),
            "slack", "slack@anthropic");
        Function<String, String> wired = McpServerService.pluginSourceResolver(integration);
        assertThat(wired.apply("plugin:slack:ch1")).isEqualTo("slack@anthropic");
    }

    // ============== 测试辅助 ==============

    private PluginView plugin(String name, ObjectNode manifest) {
        return new PluginView(name, pluginPath, name, true, manifest);
    }

    private PluginView plugin(String name, ObjectNode manifest, String source) {
        return new PluginView(name, pluginPath, source, true, manifest);
    }

    static ObjectNode json(Object... kvs) {
        ObjectNode node = JSON.createObjectNode();
        for (int i = 0; i < kvs.length; i += 2) {
            Object k = kvs[i];
            Object v = kvs[i + 1];
            if (v instanceof String s) {
                node.put((String) k, s);
            } else if (v instanceof Boolean b) {
                node.put((String) k, b);
            } else if (v instanceof Integer n) {
                node.put((String) k, n);
            } else if (v instanceof List<?> list) {
                node.set((String) k, JSON.valueToTree(list));
            } else if (v instanceof JsonNode jn) {
                node.set((String) k, jn);
            } else if (v instanceof Map<?, ?> m) {
                node.set((String) k, JSON.valueToTree(m));
            } else if (v == null) {
                node.putNull((String) k);
            } else {
                throw new IllegalArgumentException("unsupported value: " + v);
            }
        }
        return node;
    }

    static void writeJson(Path path, JsonNode node) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, node.toString(), StandardCharsets.UTF_8);
    }

    /** 写原始文本（无效 JSON 等测试场景）。 */
    static void writeJson(Path path, String raw) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, raw, StandardCharsets.UTF_8);
    }

    /** win32 反斜杠归一（CC substitutePluginVariables :330-331）。 */
    private static String normalize(String p) {
        return java.io.File.separatorChar == '\\' ? p.replace('\\', '/') : p;
    }
}
