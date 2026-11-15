package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.MonitorTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件 monitors 自动挂载 · 对齐 claude.exe v2.1.325 的 plugin monitors。
 *
 * <p><b>CC 语义</b>（off≈348,307,xxx 的 {@code kae/vae}）：
 * <ul>
 *   <li>插件 manifest 的 {@code monitors} 可以是数组，也可以是文件路径（缺省
 *       {@code monitors/monitors.json}）；</li>
 *   <li>每个 monitor 有 {@code name} / {@code command} / {@code description} / {@code when}；</li>
 *   <li>{@code when="always"}：会话启动 / 插件重载时挂；{@code when="on-skill-invoke:&lt;skill&gt;"}：
 *       该技能第一次被调用时挂；</li>
 *   <li>按 {@code plugin:name} 去重，失败回滚（允许下次重试）。</li>
 * </ul>
 *
 * <p><b>本仓多会话差异</b>：CC 单进程单会话，本仓每个用户会话独立 ⇒ 去重键加 sessionId，
 * 即「每个会话各 arm 一份」（对齐 CC 的「会话启动时 arm」语义，而不是进程级单例）。
 */
@Component
public class PluginMonitorArmService {

    private static final Logger log = LoggerFactory.getLogger(PluginMonitorArmService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<PluginLoader> pluginLoaderProvider;
    private final ObjectProvider<MonitorTaskRunner> monitorRunnerProvider;

    /** 已 arm 的键：sessionId:pluginName:monitorName。 */
    private final Set<String> armed = ConcurrentHashMap.newKeySet();

    @Autowired(required = false)
    public PluginMonitorArmService(ObjectProvider<PluginLoader> pluginLoaderProvider,
                                   ObjectProvider<MonitorTaskRunner> monitorRunnerProvider) {
        this.pluginLoaderProvider = pluginLoaderProvider;
        this.monitorRunnerProvider = monitorRunnerProvider;
    }

    /** 会话启动时挂载 when=always 的插件监控（幂等）。 */
    public void armForSession(String sessionId) {
        arm(sessionId, monitor -> "always".equals(monitor.when) || monitor.when == null || monitor.when.isBlank());
    }

    /** 技能被调用时挂载 when=on-skill-invoke:&lt;skill&gt; 的插件监控（幂等）。 */
    public void armOnSkillInvoke(String sessionId, String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        String expected = "on-skill-invoke:" + skillName;
        arm(sessionId, monitor -> expected.equals(monitor.when));
    }

    private void arm(String sessionId, java.util.function.Predicate<MonitorDef> filter) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        PluginLoader loader = pluginLoaderProvider != null ? pluginLoaderProvider.getIfAvailable() : null;
        MonitorTaskRunner runner = monitorRunnerProvider != null ? monitorRunnerProvider.getIfAvailable() : null;
        if (loader == null || runner == null) {
            return;
        }
        PluginLoader.PluginLoadResult result;
        try {
            result = loader.loadAllPluginsCacheOnly();
        } catch (Exception e) {
            log.warn("[PluginMonitorArm] 读取插件列表失败 session={}: {}", sessionId, e.toString());
            return;
        }
        for (PluginLoader.LoadedPlugin plugin : result.enabled()) {
            for (MonitorDef def : readMonitors(plugin)) {
                if (!filter.test(def)) {
                    continue;
                }
                String key = sessionId + ":" + plugin.name() + ":" + def.name;
                if (!armed.add(key)) {
                    continue;
                }
                try {
                    runner.startCommand(def.command, def.description, 0L, null, null, sessionId);
                    log.info("[PluginMonitorArm] armed plugin={} monitor={} session={}",
                        plugin.name(), def.name, sessionId);
                } catch (Exception e) {
                    armed.remove(key);
                    log.warn("[PluginMonitorArm] arm failed plugin={} monitor={} session={}: {}",
                        plugin.name(), def.name, sessionId, e.toString());
                }
            }
        }
    }

    /** 单个插件声明的 monitor。 */
    private record MonitorDef(String name, String command, String description, String when) {}

    /** 文件可以是裸数组，也可以是 {@code {"monitors":[...]}} 包装。 */
    private static JsonNode unwrapMonitors(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        JsonNode inner = node.path("monitors");
        return inner.isArray() ? inner : null;
    }

    private List<MonitorDef> readMonitors(PluginLoader.LoadedPlugin plugin) {
        List<MonitorDef> out = new ArrayList<>();
        try {
            Path root = plugin.localPath();
            if (root == null) {
                return out;
            }
            JsonNode manifest = readJson(root.resolve(".claude-plugin").resolve("plugin.json"));
            JsonNode monitors = manifest == null ? null : manifest.path("monitors");
            JsonNode array = null;
            if (monitors != null && monitors.isArray()) {
                array = monitors;
            } else if (monitors != null && monitors.isTextual() && !monitors.asText().isBlank()) {
                array = unwrapMonitors(readJson(root.resolve(monitors.asText())));
            } else if (monitors == null || monitors.isMissingNode()) {
                Path fallback = root.resolve("monitors").resolve("monitors.json");
                if (Files.exists(fallback)) {
                    array = unwrapMonitors(readJson(fallback));
                }
            }
            if (array == null || !array.isArray()) {
                return out;
            }
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode item : array) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String name = item.path("name").asText("");
                String command = item.path("command").asText("");
                if (name.isBlank() || command.isBlank() || !names.add(name)) {
                    continue;
                }
                out.add(new MonitorDef(name, command,
                    item.path("description").asText(""),
                    item.path("when").asText("always")));
            }
        } catch (Exception e) {
            log.warn("[PluginMonitorArm] 解析插件 {} 的 monitors 失败: {}", plugin.name(), e.toString());
        }
        return out;
    }

    private static JsonNode readJson(Path file) {
        try {
            if (file == null || !Files.exists(file)) {
                return null;
            }
            return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
