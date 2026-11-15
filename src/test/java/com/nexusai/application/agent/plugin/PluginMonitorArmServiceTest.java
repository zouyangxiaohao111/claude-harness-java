package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.tasks.MonitorTaskRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 插件 monitors 自动挂载测试 · 对齐 claude.exe v2.1.325 的 plugin monitors（kae/vae）。
 */
@DisplayName("[monitor-cc] PluginMonitorArmService")
class PluginMonitorArmServiceTest {

    @TempDir
    Path pluginRoot;

    private PluginLoader loader;
    private MonitorTaskRunner runner;
    private PluginMonitorArmService service;

    @BeforeEach
    void setUp() throws Exception {
        loader = mock(PluginLoader.class);
        runner = mock(MonitorTaskRunner.class);
        when(runner.startCommand(anyString(), anyString(), anyLong(), any(), any(), any()))
            .thenReturn("b-test");
        org.springframework.beans.factory.ObjectProvider<PluginLoader> lp = provider(loader);
        org.springframework.beans.factory.ObjectProvider<MonitorTaskRunner> rp = provider(runner);
        service = new PluginMonitorArmService(lp, rp);
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private void writeManifest(String json) throws Exception {
        Path dir = pluginRoot.resolve(".claude-plugin");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("plugin.json"), json, StandardCharsets.UTF_8);
    }

    private void givenPluginEnabled() {
        PluginLoader.LoadedPlugin plugin = new PluginLoader.LoadedPlugin(
            "p1", PluginLoader.InstallSource.PATH, pluginRoot, 0L, true);
        when(loader.loadAllPluginsCacheOnly())
            .thenReturn(new PluginLoader.PluginLoadResult(List.of(plugin), List.of(), List.of()));
    }

    @Test
    @DisplayName("when=always：会话启动 arm 一次，重复调用幂等")
    void always_armsOncePerSession() throws Exception {
        writeManifest("{\"monitors\":[{\"name\":\"m1\",\"command\":\"tail -f app.log\","
            + "\"description\":\"看日志\",\"when\":\"always\"}]}");
        givenPluginEnabled();

        service.armForSession("sess-1");
        service.armForSession("sess-1");

        verify(runner, times(1)).startCommand(eq("tail -f app.log"), eq("看日志"),
            eq(0L), eq(null), eq(null), eq("sess-1"));
    }

    @Test
    @DisplayName("monitors 是文件路径 ⇒ 读取数组")
    void monitorsAsFilePath() throws Exception {
        Files.writeString(pluginRoot.resolve("extra.json"),
            "{\"monitors\":[{\"name\":\"m2\",\"command\":\"watch ls\",\"description\":\"d\"}]}",
            StandardCharsets.UTF_8);
        writeManifest("{\"monitors\":\"extra.json\"}");
        givenPluginEnabled();

        service.armForSession("sess-1");

        verify(runner).startCommand(eq("watch ls"), eq("d"), eq(0L), eq(null), eq(null), eq("sess-1"));
    }

    @Test
    @DisplayName("缺省路径 monitors/monitors.json 存在 ⇒ 自动读取")
    void defaultMonitorsFile() throws Exception {
        Path dir = pluginRoot.resolve("monitors");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("monitors.json"),
            "{\"monitors\":[{\"name\":\"m3\",\"command\":\"echo hi\"}]}", StandardCharsets.UTF_8);
        writeManifest("{}");
        givenPluginEnabled();

        service.armForSession("sess-1");

        verify(runner).startCommand(eq("echo hi"), eq(""), eq(0L), eq(null), eq(null), eq("sess-1"));
    }

    @Test
    @DisplayName("when=on-skill-invoke:<skill>：只有该技能被调用时才挂")
    void onSkillInvoke_armsOnlyMatching() throws Exception {
        writeManifest("{\"monitors\":[{\"name\":\"m4\",\"command\":\"echo skill\","
            + "\"when\":\"on-skill-invoke:deploy\"}]}");
        givenPluginEnabled();

        service.armForSession("sess-1");
        service.armOnSkillInvoke("sess-1", "other");
        verify(runner, times(0)).startCommand(anyString(), anyString(), anyLong(), any(), any(), any());

        service.armOnSkillInvoke("sess-1", "deploy");
        verify(runner, times(1)).startCommand(eq("echo skill"), eq(""),
            eq(0L), eq(null), eq(null), eq("sess-1"));
    }

    @Test
    @DisplayName("同一插件内 monitor 名重复 ⇒ 只保留第一个")
    void duplicateNames_keepFirst() throws Exception {
        writeManifest("{\"monitors\":["
            + "{\"name\":\"dup\",\"command\":\"echo first\"},"
            + "{\"name\":\"dup\",\"command\":\"echo second\"}]}");
        givenPluginEnabled();

        service.armForSession("sess-1");

        verify(runner, times(1)).startCommand(eq("echo first"), anyString(), anyLong(), any(), any(), any());
    }
}
