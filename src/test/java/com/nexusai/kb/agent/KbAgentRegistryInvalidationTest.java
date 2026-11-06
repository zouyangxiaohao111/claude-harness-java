package com.nexusai.kb.agent;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写 MD 后必须让注册表缓存失效 · 批次 W10 · 真机抓到的缺陷回归。
 *
 * <p><b>缺陷经过（2026-11-05 真机）</b>：通过 {@code POST /agents} 新建智能体（MD 写成功了），
 * 但随后 {@code PATCH /sessions/{id}} 绑定它时报
 * 「agent 类型不存在: w10-scope」—— 因为注册表是**启动时从磁盘扫描**的快照并按 cwd 缓存
 * （{@code SubagentTool.registriesByCwd}），不重新扫描就看不到新文件。
 *
 * <p>同理，删除智能体后它还能继续被绑定 —— 用户看到的是「删了还在」。
 * 两者都不会提示「文件没刷新」。
 *
 * <p>本测试锁住「写完就失效」这个契约（不启动 Spring，直接验缓存字段被清空）。
 */
@DisplayName("[W10] 智能体 MD 写入后注册表缓存失效")
class KbAgentRegistryInvalidationTest {

    private static void setField(Object target, String name, Object v) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, v);
    }

    @Test
    @DisplayName("⭐ write() 之后 per-cwd 注册表缓存被清空")
    @SuppressWarnings("unchecked")
    void writeInvalidatesCache() throws Exception {
        SubagentTool tool = new SubagentTool();
        // 注入一个假的缓存条目，模拟「已经扫过盘」
        Field cacheField = SubagentTool.class.getDeclaredField("registriesByCwd");
        cacheField.setAccessible(true);
        Map<String, com.nexusai.application.agent.subagent.AgentDefinitionRegistry> cache =
            (Map<String, com.nexusai.application.agent.subagent.AgentDefinitionRegistry>) cacheField.get(tool);
        cache.put("dummy-cwd", new com.nexusai.application.agent.subagent.AgentDefinitionRegistry(
            java.util.Map.of(), java.util.List.of()));
        assertThat(cache).isNotEmpty();

        KbAgentMdService md = new KbAgentMdService();
        setField(md, "subagentTool", tool);
        Path tmp = Files.createTempDirectory("kb-agent-md");
        try {
            NexusaiPaths.setConfigHomeDirOverride(tmp.toString());
            md.write("a", new KbAgentMdService.MdContent(
                "A", "描述", null, null, null, null, "正文"));

            Map<String, com.nexusai.application.agent.subagent.AgentDefinitionRegistry> after =
                (Map<String, com.nexusai.application.agent.subagent.AgentDefinitionRegistry>) cacheField.get(tool);
            assertThat(after)
                .as("⭐ 不清缓存 ⇒ 新智能体在会话里绑不上（报「agent 类型不存在」）")
                .isEmpty();
        } finally {
            NexusaiPaths.setConfigHomeDirOverride(null);
        }
    }

    @Test
    @DisplayName("⭐ delete() 之后缓存也被清空（否则删掉的智能体仍可绑定）")
    @SuppressWarnings("unchecked")
    void deleteInvalidatesCache() throws Exception {
        SubagentTool tool = new SubagentTool();
        Field cacheField = SubagentTool.class.getDeclaredField("registriesByCwd");
        cacheField.setAccessible(true);
        Map<String, com.nexusai.application.agent.subagent.AgentDefinitionRegistry> cache =
            (Map<String, com.nexusai.application.agent.subagent.AgentDefinitionRegistry>) cacheField.get(tool);

        KbAgentMdService md = new KbAgentMdService();
        setField(md, "subagentTool", tool);
        Path tmp = Files.createTempDirectory("kb-agent-md2");
        try {
            NexusaiPaths.setConfigHomeDirOverride(tmp.toString());
            md.write("a", new KbAgentMdService.MdContent(
                "A", "描述", null, null, null, null, "正文"));
            cache.put("dummy", new com.nexusai.application.agent.subagent.AgentDefinitionRegistry(
                java.util.Map.of(), java.util.List.of()));

            md.delete("a");
            assertThat(cache).isEmpty();
        } finally {
            NexusaiPaths.setConfigHomeDirOverride(null);
        }
    }

    @Test
    @DisplayName("未注入 SubagentTool 时写入不抛（单测/精简装配）")
    void worksWithoutSubagentTool() throws Exception {
        KbAgentMdService md = new KbAgentMdService();
        Path tmp = Files.createTempDirectory("kb-agent-md3");
        try {
            NexusaiPaths.setConfigHomeDirOverride(tmp.toString());
            md.write("b", new KbAgentMdService.MdContent(
                "B", "描述", null, null, null, null, "正文"));
            assertThat(md.exists("b")).isTrue();
        } finally {
            NexusaiPaths.setConfigHomeDirOverride(null);
        }
    }

    /**
     * 缺陷 29 回归（2026-11-05 真机）。
     *
     * <p><b>现象</b>：{@code POST /agents} 建了智能体（MD 已落盘），
     * 紧接着 {@code PATCH /sessions/{id}} 绑它报「agent 类型不存在」，
     * 而**更早创建的**智能体在可用列表里 —— 说明重建时读到的是旧文件清单。
     *
     * <p><b>根因</b>：只清 {@code registriesByCwd}，没清
     * {@code loadAgentsDir} / {@code MarkdownConfigLoader} 的**文件列表 memoize**。
     * 重建 registry 时文件发现层返回缓存清单 ⇒ 新 MD 不可见。
     *
     * <p><b>判据</b>：写入后再 {@code loadAllSources}，必须能看到刚写的文件。
     * 直接断「文件可见」而不是「清缓存被调用」—— 后者清错层也能过。
     */
    @Test
    @DisplayName("⭐ 失效之后重建注册表能看到刚写入的 MD（不是旧文件清单）")
    void invalidateAlsoClearsFileDiscoveryCache() throws Exception {
        Path tmp = Files.createTempDirectory("kb-agent-md4");
        try {
            NexusaiPaths.setConfigHomeDirOverride(tmp.toString());
            Path cwd = Files.createDirectories(tmp.resolve("proj"));

            // 先「预热」文件发现层：此时目录里还没有 agent
            com.nexusai.application.agent.subagent.loadAgentsDir.loadAllSources(cwd);

            KbAgentMdService md = new KbAgentMdService();
            md.write("fresh-agent", new KbAgentMdService.MdContent(
                "Fresh", "新写的", null, null, null, null, "正文"));

            SubagentTool tool = new SubagentTool();
            setField(md, "subagentTool", tool);
            // 写 already 触发过一次失效；这里显式再调一次，模拟「校验前重建」
            tool.invalidateAgentRegistries();

            List<com.nexusai.application.agent.subagent.AgentDefinition> agents =
                com.nexusai.application.agent.subagent.loadAgentsDir.loadAllSources(cwd);
            assertThat(agents).extracting(
                    com.nexusai.application.agent.subagent.AgentDefinition::agentType)
                .as("⭐ 不清文件发现缓存 ⇒ 新建的智能体在会话里绑不上")
                .contains("fresh-agent");
        } finally {
            NexusaiPaths.setConfigHomeDirOverride(null);
            com.nexusai.application.agent.subagent.loadAgentsDir.clearCache();
        }
    }
}
