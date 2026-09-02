package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.application.agent.agent.AgentMemorySnapshot;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S1 P0-3] loadAgentsDir 激活 + 16+ 字段解析对齐测试.
 *
 * <p>验证 {@code .claude/agents/*.md} 实际加载 (旧实现 dead code, 被完全忽略),
 * loadAgentFile 解析 16+ 字段 (对齐 CC parseAgentFromMarkdown loadAgentsDir.ts:541-755),
 * 缺 description 时 return null (对齐 :561, 非 'Custom agent' 兜底).
 */
class LoadAgentsDirCustomAgentLoadingTest {

    @TempDir Path tempDir;

    private String originalUserHome;   // T1/D1：user.home 原值（NexusaiPaths 根隔离复位用）

    private void writeAgent(Path base, String fileName, String frontmatter) throws Exception {
        Path agentsDir = base.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve(fileName), "---\n" + frontmatter + "---\n\nbody content");
    }

    /**
     * [IMP-SUB-17 D17/#10] 默认注入非 ant USER_TYPE（外部构建）——使 isolation 判定与宿主 shell
     * 环境解耦：若宿主机设 USER_TYPE=ant，直接读 System.getenv 会让 'remote' 合法化，导致
     * {@code loadAgentFile_isolation_remote_rejected} / JSON e10 断言不稳定。对齐 TaskService.ENV_READER
     * 注入缝惯例（TaskService.java:380）；ant 分支用例在方法体内覆盖 ENV_READER。
     */
    @BeforeEach
    void forceExternalUserType() {
        loadAgentsDir.ENV_READER = key -> "external";
        // T1/D1：NexusaiPaths 自有根隔离到 @TempDir（setAppNameOverride 使根 = {user.home}/.nexusai-test-<name>，
        //   不隔离 user.home 会写真实 C:\Users\WIN\.nexusai-test-* 目录，污染用户 home + 读不到 fixture）。
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserTypeReader() {
        loadAgentsDir.ENV_READER = System::getenv;
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
            originalUserHome = null;
        }
    }

    @Test
    @DisplayName("load() 解析 .claude/agents/*.md 并打 source 标记")
    void load_parses_claude_agents_md_files() throws Exception {
        // WHY: load() 旧实现是 dead code, .claude/agents/*.md 被忽略 (P0-3). 激活后加载生效,
        // source 打标 userSettings (对齐 CC loadMarkdownFilesForSubdir).
        writeAgent(tempDir, "test-agent.md", "name: test-agent\ndescription: A test agent\n");

        Map<String, AgentDefinition> agents = loadAgentsDir.load(tempDir, "userSettings");

        assertThat(agents).containsKey("test-agent");
        AgentDefinition def = agents.get("test-agent");
        assertThat(def.source()).isEqualTo("userSettings");
        assertThat(def.whenToUse()).isEqualTo("A test agent");
    }

    @Test
    @DisplayName("loadAgentFile 解析 16+ 字段 (对齐 CC parseAgentFromMarkdown)")
    void loadAgentFile_parses_16_plus_fields() throws Exception {
        // WHY: 旧实现仅解析 name/description/tools/body 4 字段, disallowedTools/skills/model 等
        // 全部丢弃. 对齐 CC parseAgentFromMarkdown 逐字段解析.
        writeAgent(tempDir, "rich-agent.md",
            "name: rich-agent\n" +
            "description: Rich agent\n" +
            "disallowedTools: [Edit, Write]\n" +
            "skills: [my-skill]\n" +
            "model: haiku\n" +
            "color: blue\n" +
            "permissionMode: acceptEdits\n" +
            "maxTurns: 5\n" +
            "background: true\n" +
            "memory: project\n" +
            "isolation: worktree\n" +
            "effort: high\n" +
            "initialPrompt: Start here\n" +
            "mcpServers:\n" +
            "  - name: my-server\n" +
            "    url: http://localhost:9999\n" +
            "hooks:\n" +
            "  PreToolUse:\n" +
            "    - matcher: '*'\n" +
            "      hooks:\n" +
            "        - type: command\n" +
            "          command: echo hi\n");

        Map<String, AgentDefinition> agents = loadAgentsDir.load(tempDir, "userSettings");
        AgentDefinition def = agents.get("rich-agent");
        assertThat(def).isNotNull();
        assertThat(def.disallowedTools()).hasValue(List.of("Edit", "Write"));
        assertThat(def.skills()).hasValue(List.of("my-skill"));
        assertThat(def.model()).hasValue("haiku");
        assertThat(def.color()).hasValue("blue");
        assertThat(def.permissionMode()).hasValue("acceptEdits");
        assertThat(def.maxTurns()).hasValue(5);
        assertThat(def.background()).hasValue(true);
        assertThat(def.memory()).hasValue("project");
        assertThat(def.isolation()).hasValue("worktree");
        assertThat(def.effort()).hasValue("high");
        assertThat(def.initialPrompt()).hasValue("Start here");
        assertThat(def.filename()).hasValue("rich-agent");
        assertThat(def.mcpServers()).isPresent();
        assertThat(def.hooks()).isPresent();
    }

    @Test
    @DisplayName("缺 name 时 return null (对齐 CC :555)")
    void loadAgentFile_returns_null_when_name_missing() throws Exception {
        // WHY: CC parseAgentFromMarkdown:549-555 缺 name 时 return null — 参考文档文件静默跳过.
        writeAgent(tempDir, "noname.md", "description: no name\n");
        assertThat(loadAgentsDir.load(tempDir, "userSettings")).doesNotContainKey("noname");
    }

    @Test
    @DisplayName("缺 description 时 return null, 非 'Custom agent' 兜底 (对齐 CC :561)")
    void loadAgentFile_returns_null_when_description_missing() throws Exception {
        // WHY: CC parseAgentFromMarkdown:561 缺 description 时 return null 拒绝该文件.
        // 旧 Java 'Custom agent' 兜底使无效 agent 文件静默加载 — Fail loud 应为跳过 + log.warn.
        writeAgent(tempDir, "nodesc.md", "name: nodesc\n");
        assertThat(loadAgentsDir.load(tempDir, "userSettings")).doesNotContainKey("nodesc");
    }

    @Test
    @DisplayName("缺 tools 字段 = 全部工具 (Optional.empty, 对齐 CC undefined)")
    void loadAgentFile_tools_missing_means_all_tools() throws Exception {
        // WHY: CC parseAgentToolsFromFrontmatter 缺字段 = undefined (全部工具).
        // Java tools() 应为 Optional.empty, 而非 present-empty.
        writeAgent(tempDir, "notools.md", "name: notools\ndescription: no tools\n");
        AgentDefinition def = loadAgentsDir.load(tempDir, "userSettings").get("notools");
        assertThat(def.tools()).isEmpty();
    }

    @Test
    @DisplayName("[IMP-SUB-23 #9 / DEL-WF2-LD-01] markdown tools 对象数组 → present-empty []（CC parseToolListString 过滤非 String 项）")
    void loadAgentFile_tools_object_array_parsed_to_empty() throws Exception {
        // WHY: DEL-WF2-LD-01 主战场正是 markdown 路径——旧自建 parseTools 把对象数组
        //   [{name:'Bash'}]（SnakeYAML/Jackson → List<Map>）解析为 name 列表 ['Bash']，与 CC
        //   值域不等价。CC parseToolListString 数组分支仅保留 string 项
        //   （markdownConfigLoader.ts:91-95 filter(item => typeof item === 'string')），对象项被
        //   过滤 → []。若未来有人在数组分支改回 Map.get("name") 提取，本测试即红（规则九：业务
        //   逻辑变更时测试必须报错）。Builder.tools(List.of()) → Optional.of([])（present-empty）。
        writeAgent(tempDir, "obj-arr-tools.md",
            "name: obj-arr-tools\n" +
            "description: object array tools\n" +
            "tools:\n" +
            "  - name: Bash\n" +
            "disallowedTools:\n" +
            "  - name: Edit\n" +
            "skills:\n" +
            "  - name: my-skill\n");
        AgentDefinition def = loadAgentsDir.load(tempDir, "userSettings").get("obj-arr-tools");
        assertThat(def).isNotNull();
        // tools 对象数组 → present-empty []（CC :91-95 数组仅保留 string 项；旧 parseTools → ['Bash']）
        assertThat(def.tools()).hasValue(List.of());
        // 同统一管线 disallowedTools / skills 对象数组同样 → []（廉价的附带断言）
        assertThat(def.disallowedTools()).hasValue(List.of());
        assertThat(def.skills()).hasValue(List.of());
    }

    @Test
    @DisplayName("mcpServers 支持 string 按名引用项 (对齐 CC AgentMcpServerSpec union string)")
    void loadAgentFile_mcpServers_string_reference_item() throws Exception {
        // WHY: CC loadAgentsDir.ts:58-68 AgentMcpServerSpec = union(z.string(), z.record()) —
        // 字符串项按名引用已配置 server (如 'my-server'), 旧实现仅处理 Map 内联定义, string 项被
        // 静默丢弃 (△A). 修复后 string 项以 {"name": <string>} 进入 mcpServers() 列表保留.
        writeAgent(tempDir, "str-mcp.md",
            "name: str-mcp\n" +
            "description: mcp by name\n" +
            "mcpServers:\n" +
            "  - my-server\n");

        AgentDefinition def = loadAgentsDir.load(tempDir, "userSettings").get("str-mcp");
        assertThat(def.mcpServers()).isPresent();
        assertThat(def.mcpServers().get()).hasSize(1);
        assertThat(def.mcpServers().get().get(0)).containsEntry("name", "my-server");
    }

    @Test
    @DisplayName("isolation: remote 被拒绝跳过（非 ant USER_TYPE，对齐 CC 外部构建仅 worktree）")
    void loadAgentFile_isolation_remote_rejected() throws Exception {
        // WHY: [IMP-SUB-17 D17/#10] CC loadAgentsDir.ts:609-610 VALID_ISOLATION_MODES =
        //   USER_TYPE==='ant' ? ['worktree','remote'] : ['worktree'] —— 非 ant（@BeforeEach 注入
        //   "external"）仅 ['worktree']，'remote' 拒绝跳过（CC :617-619 logForDebugging）。△B。
        writeAgent(tempDir, "remote-iso.md",
            "name: remote-iso\n" +
            "description: iso remote\n" +
            "isolation: remote\n");

        AgentDefinition def = loadAgentsDir.load(tempDir, "userSettings").get("remote-iso");
        assertThat(def.isolation()).isEmpty();
    }

    @Test
    @DisplayName("D17: USER_TYPE=ant → markdown isolation=remote 接受（对齐 CC loadAgentsDir.ts:609-610）")
    void loadAgentFile_isolation_remote_accepted_when_ant() throws Exception {
        // WHY: [IMP-SUB-17 D17/#10] CC VALID_ISOLATION_MODES = USER_TYPE==='ant' ?
        //   ['worktree','remote'] : ['worktree']（loadAgentsDir.ts:609-610）——'remote' 仅 ant 内部
        //   环境可用；旧 Java 硬编码仅 worktree，remote 恒拒绝（死分支，open-decisions §F2 #10 裁决
        //   "修正非删"）→ 动态化后 ant 分支必须接受 remote 并写入 AgentDefinition。
        loadAgentsDir.ENV_READER = key -> "ant";
        writeAgent(tempDir, "ant-remote.md",
            "name: ant-remote\n" +
            "description: ant remote\n" +
            "isolation: remote\n");

        AgentDefinition def = loadAgentsDir.load(tempDir, "userSettings").get("ant-remote");
        assertThat(def.isolation()).hasValue("remote");
    }

    @Test
    @DisplayName("memory 启用 + tools 显式非通配 → 注入 Write/Edit/Read（loadAgentsDir.ts:663-674）")
    void loadAgentFile_memory_injects_write_edit_read() throws Exception {
        // WHY: CC memory 启用时给 agent 补 Write/Edit/Read 三工具让其能读写 agent-memory 目录
        //   （loadAgentsDir.ts:663-674）。缺这些工具 agent 无法持久化记忆。用 project scope
        //   避开 initializeAgentMemorySnapshots（仅 memory='user' 处理）。
        writeAgent(tempDir, "mem-tools.md",
            "name: mem-tools\ndescription: memory agent\nmemory: project\ntools: [Bash, Read]\n");
        AgentDefinition def = loadAgentsDir.load(tempDir, "userSettings").get("mem-tools");
        assertThat(def.tools().orElseThrow())
            .contains("Bash", "Read")
            .contains("Write", "Edit", "Read");
    }

    @Test
    @DisplayName("memory 启用 + tools 通配 '*' → 不注入（CC parseAgentToolsFromFrontmatter '*' → undefined）")
    void loadAgentFile_memory_wildcard_tools_no_inject() throws Exception {
        // WHY: CC markdownConfigLoader.ts:124-126 — tools 含 '*' 返回 undefined（全部工具），
        //   'tools !== undefined' 为 false → 注入跳过。P1-1 接入统一管线后
        //   parseAgentToolsFromFrontmatter('*') → undefined → tools 不设置（全部工具），
        //   与缺字段（undefined）同语义，不再保留旧 Java 的 ['*'] 偏差。
        writeAgent(tempDir, "mem-wild.md",
            "name: mem-wild\ndescription: wildcard agent\nmemory: project\ntools: '*'\n");
        AgentDefinition def = loadAgentsDir.load(tempDir, "userSettings").get("mem-wild");
        assertThat(def.tools()).isEmpty();
    }

    @Test
    @DisplayName("memory 启用 + tools 缺字段 → 全部工具不注入（Optional.empty 对齐 CC undefined）")
    void loadAgentFile_memory_no_tools_field_no_inject() throws Exception {
        // WHY: CC tools 缺字段 = undefined（全部工具），注入跳过。Java tools() 应为 Optional.empty。
        writeAgent(tempDir, "mem-notools.md",
            "name: mem-notools\ndescription: no tools agent\nmemory: project\n");
        AgentDefinition def = loadAgentsDir.load(tempDir, "userSettings").get("mem-notools");
        assertThat(def.tools()).isEmpty();
    }

    @Test
    @DisplayName("快照初始化接线：memory=user + 无本地 .md → initialize 拷贝快照（loadAgentsDir.ts:262-294）")
    void initializeAgentMemorySnapshots_initializes_user_memory_agent() throws Exception {
        // WHY: 验收 #4 快照三态接线——memory='user' 且本地无 .md → initializeFromSnapshot
        //   （拷贝快照非 snapshot.json 文件 + 写 synced 元数据）。非 user scope 不处理。
        Path cwd = tempDir.resolve("snapcwd");
        Path memoryBase = tempDir.resolve("snapbase");
        AgentMemoryDirectory dir = new AgentMemoryDirectory(
            cwd::toString, () -> memoryBase, () -> null, () -> cwd,
            s -> s, path -> { /* no-op mkdir */ }, () -> null,
            () -> true,
            com.nexusai.application.agent.memory.MemoryPromptBuilder.productionDefault());
        AgentMemorySnapshot snapshot = new AgentMemorySnapshot(cwd::toString, dir);

        Path snapDir = cwd.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory-snapshots").resolve("my-user-agent");
        Files.createDirectories(snapDir);
        Files.writeString(snapDir.resolve("snapshot.json"), "{\"updatedAt\":\"2026-08-05T10:00:00Z\"}");
        Files.writeString(snapDir.resolve("topic.md"), "shared knowledge");

        AgentDefinition agent = AgentDefinition.CustomAgentDefinition.builder(
                "my-user-agent", "desc", "userSettings", "prompt").memory("user").build();
        Map<String, AgentDefinition> agents = new java.util.HashMap<>(Map.of("my-user-agent", agent));

        loadAgentsDir.initializeAgentMemorySnapshots(agents, dir, snapshot);

        Path userMemDir = memoryBase.resolve("agent-memory").resolve("my-user-agent");
        assertThat(Files.readString(userMemDir.resolve("topic.md"))).isEqualTo("shared knowledge");
    }

    @Test
    @DisplayName("快照初始化接线：本地有 .md 且快照更新 → pendingSnapshotUpdate 设置（loadAgentsDir.ts:283-287）")
    void initializeAgentMemorySnapshots_prompt_update_sets_pending() throws Exception {
        // WHY: 本地已有记忆但项目快照更新 → prompt-update，agent 挂上 pendingSnapshotUpdate
        //   （CC loadAgentsDir.ts:283-287，前端 dialog 消费；Java 仅存数据字段）。
        Path cwd = tempDir.resolve("snapcwd2");
        Path memoryBase = tempDir.resolve("snapbase2");
        AgentMemoryDirectory dir = new AgentMemoryDirectory(
            cwd::toString, () -> memoryBase, () -> null, () -> cwd,
            s -> s, path -> { /* no-op mkdir */ }, () -> null,
            () -> true,
            com.nexusai.application.agent.memory.MemoryPromptBuilder.productionDefault());
        AgentMemorySnapshot snapshot = new AgentMemorySnapshot(cwd::toString, dir);

        Path snapDir = cwd.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory-snapshots").resolve("upd-agent");
        Files.createDirectories(snapDir);
        Files.writeString(snapDir.resolve("snapshot.json"), "{\"updatedAt\":\"2026-08-05T10:00:00Z\"}");

        Path userMemDir = memoryBase.resolve("agent-memory").resolve("upd-agent");
        Files.createDirectories(userMemDir);
        Files.writeString(userMemDir.resolve("local.md"), "local memory");

        AgentDefinition agent = AgentDefinition.CustomAgentDefinition.builder(
                "upd-agent", "desc", "userSettings", "prompt").memory("user").build();
        Map<String, AgentDefinition> agents = new java.util.HashMap<>(Map.of("upd-agent", agent));

        loadAgentsDir.initializeAgentMemorySnapshots(agents, dir, snapshot);

        assertThat(agents.get("upd-agent").pendingSnapshotUpdate())
            .hasValue("2026-08-05T10:00:00Z");
    }

    @Test
    @DisplayName("parseAgentFromJson：memory schema + 工具注入 + 必填校验（loadAgentsDir.ts:445-516）")
    void parseAgentFromJson_memory_schema_and_tool_injection() throws Exception {
        // WHY: [FIX-AM REQ-M-19] JSON agent 加载补能力（CC loadAgentsDir.ts:92/:456-467）——
        //   memory 为 enum user/project/local；memory 启用 + tools 显式非通配 → 注入
        //   Write/Edit/Read；缺必填 description/prompt → null（对齐 AgentJsonSchema min1）。
        Map<String, Object> def = new java.util.HashMap<>();
        def.put("description", "JSON memory agent");
        def.put("prompt", "you are a memory agent");
        def.put("memory", "project");
        def.put("tools", List.of("Bash", "Read"));
        def.put("model", "haiku");
        def.put("maxTurns", 5);

        AgentDefinition agent = loadAgentsDir.parseAgentFromJson("json-mem-agent", def, "flagSettings");

        assertThat(agent).isNotNull();
        assertThat(agent.memory()).hasValue("project");
        assertThat(agent.source()).isEqualTo("flagSettings");
        assertThat(agent.model()).hasValue("haiku");
        assertThat(agent.maxTurns()).hasValue(5);
        // memory 启用 → Write/Edit/Read 注入（CC :456-467）
        assertThat(agent.tools().orElseThrow())
            .contains("Bash", "Read")
            .contains("Write", "Edit", "Read");
    }

    @Test
    @DisplayName("AM-05：任一字段非法 → 整 agent null（zod 整体拒绝，loadAgentsDir.ts:510-515）")
    void parseAgentFromJson_required_fields_and_invalid_memory() throws Exception {
        // WHY: [AM-05/OPD-R2-AM-05] CC AgentJsonSchema（loadAgentsDir.ts:73-99）全字段严格校验，
        //   任一字段非法（含 memory enum :92）→ parseAgentFromJson catch → 整 agent null。
        //   旧 Java 逐字段宽松降级（memory 非法→warn 保留）→ RED。
        Map<String, Object> noDesc = new java.util.HashMap<>();
        noDesc.put("prompt", "prompt only");
        assertThat(loadAgentsDir.parseAgentFromJson("a", noDesc, "flagSettings")).isNull();

        Map<String, Object> noPrompt = new java.util.HashMap<>();
        noPrompt.put("description", "desc only");
        assertThat(loadAgentsDir.parseAgentFromJson("b", noPrompt, "flagSettings")).isNull();

        Map<String, Object> badMem = new java.util.HashMap<>();
        badMem.put("description", "bad mem");
        badMem.put("prompt", "prompt");
        badMem.put("memory", "invalid-scope");
        assertThat(loadAgentsDir.parseAgentFromJson("c", badMem, "flagSettings")).isNull();
    }

    @Test
    @DisplayName("AM-05：字段级整体拒绝（effort/permissionMode/maxTurns/model/background/isolation/tools 非法 → null）")
    void parseAgentFromJson_field_level_rejection() throws Exception {
        // WHY: zod 全字段严格校验 —— 任一字段类型/值域非法即整 agent 拒绝（loadAgentsDir.ts:73-99）。
        //   旧 Java 宽松透传/截断（effort 任意串、maxTurns 截断、background 忽略）→ RED。
        assertThat(loadAgentsDir.parseAgentFromJson("e1", def("d", "p", "effort", "super"), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e2", def("d", "p", "effort", 1.5), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e3", def("d", "p", "permissionMode", "admin"), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e4", def("d", "p", "maxTurns", 0), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e5", def("d", "p", "maxTurns", -3), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e6", def("d", "p", "maxTurns", 1.5), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e7", def("d", "p", "model", "  "), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e8", def("d", "p", "model", 42), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e9", def("d", "p", "background", "true"), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e10", def("d", "p", "isolation", "remote"), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e11", def("d", "p", "tools", List.of(1, 2)), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e12", def("d", "p", "skills", List.of("ok", 7)), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e13", def("d", "p", "mcpServers", List.of(Map.of("name", 123))), "flagSettings")).isNull();
        assertThat(loadAgentsDir.parseAgentFromJson("e14", def("d", "p", "hooks", Map.of("NotARealEvent", List.of())), "flagSettings")).isNull();
    }

    @Test
    @DisplayName("D17: USER_TYPE=ant → JSON isolation=remote 合法（对齐 CC AgentJsonSchema :94-97）")
    void parseAgentFromJson_isolation_remote_accepted_when_ant() {
        // WHY: [IMP-SUB-17 D17/#10] CC AgentJsonSchema isolation = USER_TYPE==='ant' ?
        //   z.enum(['worktree','remote']) : z.enum(['worktree'])（loadAgentsDir.ts:94-97）——ant 时
        //   remote 合法；旧 Java VALID_ISOLATION_MODES 静态仅 worktree，validateAgentJsonSchema 恒拒
        //   remote（e10 依赖非 ant 分支）→ 动态化后 ant 分支必须通过 schema 校验并写入 isolation。
        loadAgentsDir.ENV_READER = key -> "ant";
        AgentDefinition agent = loadAgentsDir.parseAgentFromJson(
            "ant-json", def("d", "p", "isolation", "remote"), "flagSettings");
        assertThat(agent).isNotNull();
        assertThat(agent.isolation()).hasValue("remote");
    }

    @Test
    @DisplayName("AM-05/FINDING-2：显式 JSON null 可选字段 → 整 agent null（zod .optional() 只接受 undefined，JSON null 拒绝）")
    void parseAgentFromJson_explicit_null_optional_fields_rejected() {
        // WHY: [FINDING-2 返工] CC zod z.xxx().optional() 对显式 null 拒绝（仅缺省/undefined 通过）；
        //   旧 Java `field != null` 守卫把显式 null 当缺省放行（注释声称 null 非法但未实现）→ RED。
        //   七个可选字段逐一验证：键存在 + 值为 null → 整 agent null。
        for (String field : List.of("effort", "permissionMode", "maxTurns", "initialPrompt",
                "memory", "background", "isolation")) {
            Map<String, Object> def = new java.util.HashMap<>();
            def.put("description", "d");
            def.put("prompt", "p");
            def.put(field, null); // 显式 null（键存在）
            assertThat(loadAgentsDir.parseAgentFromJson("null-" + field, def, "flagSettings"))
                .as("显式 null 字段 %s 必须整 agent 拒绝（zod .optional() 语义）", field)
                .isNull();
        }
    }

    @Test
    @DisplayName("AM-05/FINDING-2：可选字段缺省（键不存在）仍解析成功（zod .optional() 接受 undefined）")
    void parseAgentFromJson_absent_optional_fields_parse_ok() {
        // WHY: 与显式 null 区分——缺省（无键）≠ null，必须照常解析（否则误杀合法 agent）。
        Map<String, Object> def = new java.util.HashMap<>();
        def.put("description", "d");
        def.put("prompt", "p");
        AgentDefinition agent = loadAgentsDir.parseAgentFromJson("absent-optional", def, "flagSettings");
        assertThat(agent).isNotNull();
        assertThat(agent.effort()).isEmpty();
        assertThat(agent.memory()).isEmpty();
    }

    @Test
    @DisplayName("AM-05：合法全字段仍解析成功（zod 通过路径，loadAgentsDir.ts:445-516）")
    void parseAgentFromJson_valid_full_definition() {
        Map<String, Object> def = new java.util.HashMap<>();
        def.put("description", "valid");
        def.put("prompt", "prompt");
        def.put("model", " haiku ");
        def.put("effort", "high");
        def.put("permissionMode", "acceptEdits");
        def.put("maxTurns", 3);
        def.put("background", true);
        def.put("isolation", "worktree");
        def.put("memory", "local");
        def.put("skills", List.of("s1"));
        def.put("initialPrompt", "start");
        def.put("mcpServers", List.of("my-server", Map.of("name", Map.of("type", "stdio", "command", "node"))));
        def.put("hooks", Map.of("PreToolUse", List.of(Map.of("matcher", "*", "hooks", List.of(Map.of("type", "command", "command", "echo hi"))))));
        AgentDefinition agent = loadAgentsDir.parseAgentFromJson("v1", def, "flagSettings");
        assertThat(agent).isNotNull();
        assertThat(agent.model()).hasValue("haiku"); // trim + inherit 变换（CC :79-84）
        assertThat(agent.memory()).hasValue("local");
        assertThat(agent.permissionMode()).hasValue("acceptEdits");
        assertThat(agent.maxTurns()).hasValue(3);
        // [IMP-SUB-08 REWORK R2-WF-E] 补 D7 核心消费断言：mcpServers 与 hooks 此前被静默丢弃
        //   （DEC-SUB-17），本期补消费后必须落到 AgentDefinition —— 旧测试仅断言
        //   model/memory/permissionMode/maxTurns，删除 D7 消费代码测试仍绿（违反规则九，
        //   IMP-SUB-08-reflection §6 断言缺口）。string 按名引用 + record 内联 + hooks raw Map
        //   均须 present（CC loadAgentsDir.ts:495-498）。
        assertThat(agent.mcpServers()).isPresent();
        assertThat(agent.mcpServers().get()).hasSize(2);
        assertThat(agent.mcpServers().get().get(0))
            .as("string 按名引用项以 {\"name\": <string>} 保留（CC :65）").containsEntry("name", "my-server");
        assertThat(agent.mcpServers().get().get(1))
            .as("record 内联项原样保留（CC :66 z.record）").containsKey("name");
        assertThat(agent.hooks()).isPresent();
        assertThat(agent.hooks().get())
            .as("hooks raw Map 透传（CC :498 parsed.hooks truthy 才设置）").containsKey("PreToolUse");
    }

    @Test
    @DisplayName("AM-05：批量任一非法 → 整批 []（AgentsJsonSchema 整体拒绝，loadAgentsDir.ts:521-536）")
    void parseAgentsFromJson_batch() throws Exception {
        // WHY: [AM-05] CC parseAgentsFromJson 先 AgentsJsonSchema().parse(agentsJson)（z.record），
        //   任一 entry 非法 → catch → 整批 []。旧 Java 逐条跳过非法项 → RED。
        Map<String, Object> json = new java.util.HashMap<>();
        json.put("ok-agent", java.util.Map.of("description", "d", "prompt", "p"));
        json.put("bad-agent", java.util.Map.of("prompt", "missing description"));
        List<AgentDefinition> agents = loadAgentsDir.parseAgentsFromJson(json, "flagSettings");
        assertThat(agents).isEmpty();
        // 全合法 → 整批返回
        Map<String, Object> allOk = new java.util.HashMap<>();
        allOk.put("a1", java.util.Map.of("description", "d1", "prompt", "p1"));
        allOk.put("a2", java.util.Map.of("description", "d2", "prompt", "p2"));
        List<AgentDefinition> parsed = loadAgentsDir.parseAgentsFromJson(allOk, "flagSettings");
        assertThat(parsed).hasSize(2);
    }

    @Test
    @DisplayName("AM-05/FINDING-3：effort 'max' 合法解析（CC EFFORT_LEVELS 含 max，utils/effort.ts:13-17）")
    void parseAgentFromJson_effort_max_accepted() {
        // WHY: [FINDING-3 返工] CC EFFORT_LEVELS = ['low','medium','high','max']（utils/effort.ts:13-17），
        //   loadAgentsDir.ts:85 z.union([z.enum(EFFORT_LEVELS), z.number().int()]) → 'max' 合法；
        //   旧 Java EFFORT_LEVELS = Set.of(low,medium,high) 缺 'max' → 整 agent null → RED。
        AgentDefinition agent = loadAgentsDir.parseAgentFromJson("max-effort", def("d", "p", "effort", "max"), "flagSettings");
        assertThat(agent).isNotNull();
        assertThat(agent.effort()).hasValue("max");
    }

    @Test
    @DisplayName("AM-05/FINDING-3：批量含 effort 'max' 项不被整批拒绝（CC :85 接受该值）")
    void parseAgentsFromJson_batch_effort_max_not_rejected() {
        // WHY: [FINDING-3 返工] effort 'max' 是合法值 → 该 entry 必须解析成功、整批不回落 []；
        //   旧 Java 缺 'max' → validateAgentJsonSchema 判非法 → 整批 [] → RED。
        Map<String, Object> json = new java.util.HashMap<>();
        json.put("max-agent", def("d", "p", "effort", "max"));
        json.put("plain-agent", java.util.Map.of("description", "d2", "prompt", "p2"));
        List<AgentDefinition> agents = loadAgentsDir.parseAgentsFromJson(json, "flagSettings");
        assertThat(agents).hasSize(2);
    }

    @Test
    @DisplayName("AM-05/FINDING-4：mcpServers stdio 配置 type 可选（向后兼容）+ command 非空 + args String 数组")
    void mcpServers_stdio_shape_backwards_compat() {
        // WHY: [FINDING-4 返工] CC McpStdioServerConfigSchema（services/mcp/types.ts:28-35）：
        //   type: z.literal('stdio').optional()（:30 向后兼容）→ {command:'node'} 无 type 必须接受；
        //   command min(1) 空串拒绝；args z.array(z.string()) 非 String 数组拒绝；
        //   旧 Java 强制 type 存在且仅检查 command 类型 → 无 type 配置被拒、空 command/坏 args 被放行 → RED。
        // 无 type 的 stdio 配置（向后兼容）
        assertThat(loadAgentsDir.parseAgentFromJson("s1",
            def("d", "p", "mcpServers", List.of(Map.of("name", Map.of("command", "node")))), "flagSettings")).isNotNull();
        // 显式 type=stdio + args 合法数组
        assertThat(loadAgentsDir.parseAgentFromJson("s2",
            def("d", "p", "mcpServers",
                List.of(Map.of("name", Map.of("type", "stdio", "command", "node",
                    "args", List.of("--port", "8080"))))), "flagSettings")).isNotNull();
        // command 空串拒绝（min(1)）
        assertThat(loadAgentsDir.parseAgentFromJson("s3",
            def("d", "p", "mcpServers", List.of(Map.of("name", Map.of("type", "stdio", "command", "")))), "flagSettings")).isNull();
        // args 非 String 数组拒绝
        assertThat(loadAgentsDir.parseAgentFromJson("s4",
            def("d", "p", "mcpServers",
                List.of(Map.of("name", Map.of("type", "stdio", "command", "node", "args", "x")))), "flagSettings")).isNull();
        // env 非 String→String record 拒绝（types.ts:33）
        assertThat(loadAgentsDir.parseAgentFromJson("s5",
            def("d", "p", "mcpServers",
                List.of(Map.of("name", Map.of("type", "stdio", "command", "node", "env", Map.of("K", 1))))), "flagSettings")).isNull();
    }


    @Test
    @DisplayName("load() 一致性缓存：同 baseDir 二调命中 + clearCache 失效")
    void load_cache_hit_and_clearCache() throws Exception {
        // WHY: [FIX-AM] 对齐 CC memoize（loadAgentsDir.ts:296）——同 baseDir 二次调用返回缓存
        //   （防御性 copy 防跨调用污染）；clearCache（:395）后重新加载。两次返回独立 Map 实例
        //   （copy），外部改动不污染缓存。
        writeAgent(tempDir, "cached-agent.md", "name: cached-agent\ndescription: cached\n");
        Map<String, AgentDefinition> first = loadAgentsDir.load(tempDir, "userSettings");
        Map<String, AgentDefinition> second = loadAgentsDir.load(tempDir, "userSettings");
        assertThat(second).containsKey("cached-agent");
        assertThat(second).isNotSameAs(first); // 防御性 copy
        // 外部改动（删除）不污染缓存
        second.clear();
        assertThat(loadAgentsDir.load(tempDir, "userSettings")).containsKey("cached-agent");
        // clearCache 后仍可再加载
        loadAgentsDir.clearCache();
        assertThat(loadAgentsDir.load(tempDir, "userSettings")).containsKey("cached-agent");
    }

    @Test
    @DisplayName("AM-01/G-75：快照 cwdSupplier 用 per-session projectRoot（对齐 ODF-A1，loadAgentsDir.java:125）")
    void initialize_uses_session_project_root_as_snapshot_cwd() throws Exception {
        // WHY: 旧实现 cwd=System.getProperty("user.dir") 进程级 → session root ≠ user.dir 时
        //   快照目录错位（?-1/OPD-R2-AM-01）。快照目录必须按 per-session projectRoot 解析。
        Path cfgHome = tempDir.resolve("cfg-home");
        Files.createDirectories(cfgHome);
        Path sessionRoot = tempDir.resolve("session-root");
        try {
            ClaudePaths.setConfigDirOverride(cfgHome.toString());
            // G5：agent-memory user scope 已迁 nexusai 自有根（AgentMemoryDirectory）→ 唯一 appName 隔离
            NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
            // R9-2：快照源目录随 appName 动态（getSnapshotDirForAgent = <sessionRoot>/<getProjectDirName()>/agent-memory-snapshots）
            //   → 夹具必须在 override 后用动态目录名创建
            Path snapDir = sessionRoot.resolve(NexusaiPaths.getProjectDirName())
                .resolve("agent-memory-snapshots").resolve("user-agent");
            Files.createDirectories(snapDir);
            Files.writeString(snapDir.resolve("snapshot.json"), "{\"updatedAt\":\"2026-08-05T10:00:00Z\"}");
            Files.writeString(snapDir.resolve("topic.md"), "session shared");
            AutoMemPaths.setCurrentProjectRoot(sessionRoot.toString());
            AgentDefinition agent = AgentDefinition.CustomAgentDefinition.builder(
                "user-agent", "desc", "userSettings", "prompt").memory("user").build();
            Map<String, AgentDefinition> agents = new java.util.HashMap<>(Map.of("user-agent", agent));
            loadAgentsDir.initializeAgentMemorySnapshots(agents);
            // 快照目录按 sessionRoot 解析 → 拷贝到 nexusai home/agent-memory/<type>（决策 D1）
            Path userMem = Path.of(NexusaiPaths.getAppConfigHomeDir(), "agent-memory", "user-agent");
            assertThat(Files.readString(userMem.resolve("topic.md"))).isEqualTo("session shared");
            assertThat(Files.readString(userMem.resolve(".snapshot-synced.json")))
                .contains("\"syncedFrom\":\"2026-08-05T10:00:00Z\"");
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);
        }
    }

    @Test
    @DisplayName("AM-03：快照初始化 mkdir 失败 → load() 降级返回空 map（对齐 CC 外层 catch → built-ins 回退，loadAgentsDir.ts:379-391）")
    void load_degrades_to_empty_when_snapshot_init_fails() throws Exception {
        // WHY: CC copySnapshotToLocal mkdir 在 try 外 → 失败 → initializeAgentMemorySnapshots 拒绝 →
        //   getAgentDefinitionsWithOverrides 外层 catch（:379-391）→ 回退（Java 表达：空 custom map，
        //   built-ins 由 AgentDefinitionRegistry 独立合并）。旧 Java 异常冒泡到调用方 → RED。
        loadAgentsDir.clearCache();
        Path cfgHome = tempDir.resolve("cfg-home-blocked");
        Files.createDirectories(cfgHome);
        Path sessionRoot = tempDir.resolve("session-root-blocked");
        try {
            ClaudePaths.setConfigDirOverride(cfgHome.toString());
            // G5：agent-memory user scope = nexusai 自有根（AgentMemoryDirectory）→ 唯一 appName 隔离
            NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
            // R9-2：快照源目录随 appName 动态 → 夹具在 override 后用 getProjectDirName() 创建
            Path snapDir = sessionRoot.resolve(NexusaiPaths.getProjectDirName())
                .resolve("agent-memory-snapshots").resolve("mem-agent");
            Files.createDirectories(snapDir);
            Files.writeString(snapDir.resolve("snapshot.json"), "{\"updatedAt\":\"2026-08-05T10:00:00Z\"}");
            AutoMemPaths.setCurrentProjectRoot(sessionRoot.toString());
            // agent-memory 以普通文件存在 → createDirectories 失败（确定性）
            // T1/D1：user.home 已隔离到 tempDir，需先建 NexusaiPaths 根再以文件覆盖 agent-memory 路径
            Files.createDirectories(Path.of(NexusaiPaths.getAppConfigHomeDir()));
            Files.writeString(Path.of(NexusaiPaths.getAppConfigHomeDir(), "agent-memory"), "blocking");
            Path base = tempDir.resolve("base");
            writeAgent(base, "mem-agent.md", "name: mem-agent\ndescription: mem agent\nmemory: user\n");
            Map<String, AgentDefinition> agents = loadAgentsDir.load(base, "userSettings");
            assertThat(agents).isEmpty();
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);
        }
    }

    @Test
    @DisplayName("loadAllSources 三源 + 递归子目录 + source 打标（CC loadMarkdownFilesForSubdir 三源）")
    void loadAllSources_threeSources_recursive_sourceTag() throws Exception {
        // WHY: [IMP-SUB-09 D8] 生产接线目标——SubagentTool 改调 loadAllSources 后，managed
        //   (policySettings)/user(userSettings)/project(projectSettings) 三源 agent 必须全部加载，
        //   且 source 打标正确（CC loadMarkdownFilesForSubdir markdownConfigLoader.ts:337-372）。
        //   旧生产仅 userSettings → policySettings/projectSettings agents 永不加载（△-2 HIGH）。
        //   用 ClaudePaths.setConfigDirOverride + setManagedFilePathOverride 隔离，避免读真实
        //   ~/.claude/agents（环境依赖 → 非可重复硬断言）。
        Path managedRoot = tempDir.resolve("managed-root");
        Path cfgRoot = tempDir.resolve("cfg-root");
        Path projRoot = tempDir.resolve("proj-root");

        // managed 源：<managedRoot>/.claude/agents/*.md（CC :337-345 managedDir → policySettings）
        Files.createDirectories(managedRoot.resolve(".claude").resolve("agents"));
        Files.writeString(managedRoot.resolve(".claude").resolve("agents").resolve("policy-agent.md"),
            "---\nname: policy-agent\ndescription: policy agent\n---\n\nbody");
        // user 源：<cfgRoot>/agents/*.md（CC :346-356 userDir → userSettings）
        Files.createDirectories(cfgRoot.resolve("agents"));
        Files.writeString(cfgRoot.resolve("agents").resolve("user-agent.md"),
            "---\nname: user-agent\ndescription: user agent\n---\n\nbody");
        // project 源：<projRoot>/.claude/agents/**/*.md（CC :357-372 projectDirs → projectSettings）
        Files.createDirectories(projRoot.resolve(".claude").resolve("agents").resolve("nested"));
        Files.writeString(projRoot.resolve(".claude").resolve("agents").resolve("proj-agent.md"),
            "---\nname: proj-agent\ndescription: proj agent\n---\n\nbody");
        // 递归子目录：nested/sub-agent.md（CC ripgrep --glob '*.md' 递归，:564-568）
        Files.writeString(projRoot.resolve(".claude").resolve("agents").resolve("nested").resolve("sub-agent.md"),
            "---\nname: sub-agent\ndescription: nested agent\n---\n\nbody");

        try {
            ClaudePaths.setConfigDirOverride(cfgRoot.toString());
            ClaudePaths.setManagedFilePathOverride(managedRoot.toString());
            // G5：loadAllSources user 源 = NexusaiPaths 自有根优先 → 唯一 appName 隔离（防读真实 ~/.nexusai）
            NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
            loadAgentsDir.clearCache(); // 清 MarkdownConfigLoader memoize，避免跨用例陈旧

            List<AgentDefinition> agents = loadAgentsDir.loadAllSources(projRoot);

            Map<String, AgentDefinition> byType = agents.stream()
                .collect(java.util.stream.Collectors.toMap(AgentDefinition::agentType, a -> a));
            assertThat(byType).containsOnlyKeys("policy-agent", "user-agent", "proj-agent", "sub-agent");
            assertThat(byType.get("policy-agent").source())
                .as("managed 源必须打 source=policySettings（CC :342）").isEqualTo("policySettings");
            assertThat(byType.get("user-agent").source())
                .as("user 源必须打 source=userSettings（CC :352）").isEqualTo("userSettings");
            assertThat(byType.get("proj-agent").source())
                .as("project 源必须打 source=projectSettings（CC :365）").isEqualTo("projectSettings");
            assertThat(byType.get("sub-agent").source())
                .as("递归子目录发现的 agent 同样打 source=projectSettings").isEqualTo("projectSettings");
        } finally {
            ClaudePaths.setConfigDirOverride(null);
            ClaudePaths.setManagedFilePathOverride(null);
            NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
            loadAgentsDir.clearCache();
        }
    }

    @Test
    @DisplayName("loadAllSources 同 realpath 去重 first-wins（CC dev:ino 去重 :377-414）")
    void loadAllSources_dedup_firstWins() throws Exception {
        // WHY: CC loadMarkdownFilesForSubdir:377-414 合并 [...managed, ...user, ...project] 后
        //   dev:ino 去重 first-wins——同一物理文件经多个来源发现时必须折叠为 1（本例 user 源
        //   与 project 源指向同一物理目录，仅 userSettings 保留）。去重缺失 → 同 agent 双注册。
        Path projRoot = tempDir.resolve("dedup-proj");
        Path projClaude = projRoot.resolve(".claude");
        Files.createDirectories(projClaude.resolve("agents"));
        Files.writeString(projClaude.resolve("agents").resolve("dup-agent.md"),
            "---\nname: dup-agent\ndescription: dup agent\n---\n\nbody");

        try {
            // userDir = <cfgHome>/agents = projRoot/.claude/agents；projectDir = projRoot/.claude/agents
            // → 同一物理文件被 user + project 两源发现 → realpath 去重 → 保留首个（userSettings）
            ClaudePaths.setConfigDirOverride(projClaude.toString());
            ClaudePaths.setManagedFilePathOverride(tempDir.resolve("no-managed").toString());
            // G5：loadAllSources user 源 = NexusaiPaths 自有根优先 → 唯一 appName 隔离（防读真实 ~/.nexusai）
            NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
            loadAgentsDir.clearCache();

            List<AgentDefinition> agents = loadAgentsDir.loadAllSources(projRoot);

            assertThat(agents).as("同一物理文件经 user+project 两源发现必须折叠为 1（realpath 去重）")
                .hasSize(1);
            assertThat(agents.get(0).agentType()).isEqualTo("dup-agent");
            assertThat(agents.get(0).source())
                .as("去重 first-wins：managed 空 → user 源先于 project 源 → source=userSettings")
                .isEqualTo("userSettings");
        } finally {
            ClaudePaths.setConfigDirOverride(null);
            ClaudePaths.setManagedFilePathOverride(null);
            NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
            loadAgentsDir.clearCache();
        }
    }

    private static Map<String, Object> def(String description, String prompt, Object... kv) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("description", description);
        m.put("prompt", prompt);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
