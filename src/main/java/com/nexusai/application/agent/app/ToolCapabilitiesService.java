package com.nexusai.application.agent.app;

import com.nexusai.application.agent.browser.BrowserToolRegistry;
import com.nexusai.application.agent.browser.BrowserWsChannel;
import com.nexusai.application.agent.computeruse.ComputerUseKnownTools;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.model.app.dto.BrowserCapabilityDto;
import com.nexusai.model.app.dto.ToolCapabilityDto;
import com.nexusai.model.app.dto.ToolCapabilitiesResponse;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具能力目录（CU / BR）：已知工具名清单 + 未注册原因 + 浏览器扩展真实连接态。
 *
 * <p><b>⛔ 只读、不改注册</b>：flag 关闭时工具仍不注册、不碰 JNA（{@code ComputerUseToolConfig}
 * 的「flag 关不碰 JNA」不变式保持不变）；本目录只告诉 UI「有哪些能力、为什么现在不可用」，
 * 避免把「功能未开启」误显示成「需安装扩展」。
 */
@Service
public class ToolCapabilitiesService {

    static final String FLAG_BROWSER = "nexusai.feature.browser-mcp";
    static final String FLAG_COMPUTER_USE = "nexusai.feature.computer-use";

    static final String STATUS_DISABLED = "disabled";
    static final String STATUS_UNREGISTERED = "unregistered";
    static final String STATUS_DISCONNECTED = "disconnected";
    static final String STATUS_READY = "ready";

    static final String REASON_FLAG_OFF = "功能未开启";
    static final String REASON_UNREGISTERED = "工具未注册";
    static final String REASON_EXTENSION_DISCONNECTED = "Chrome 扩展未连接";

    private final ToolRegistry toolRegistry;
    private final BrowserWsChannel browserWsChannel;
    private final Environment environment;

    public ToolCapabilitiesService(ToolRegistry toolRegistry, BrowserWsChannel browserWsChannel,
                                   Environment environment) {
        this.toolRegistry = toolRegistry;
        this.browserWsChannel = browserWsChannel;
        this.environment = environment;
    }

    /** 能力目录（CU → BR）+ 浏览器连接态。 */
    public ToolCapabilitiesResponse capabilities() {
        List<ToolCapabilityDto> out = new ArrayList<>(2);
        out.add(computerUse());
        out.add(browser());
        return new ToolCapabilitiesResponse(out, browserConnection());
    }

    private ToolCapabilityDto computerUse() {
        boolean enabled = flag(FLAG_COMPUTER_USE);
        List<String> known = ComputerUseKnownTools.ALL;
        int registered = registeredCount(SessionToolCategory.CU_PREFIX);
        String status;
        String reason;
        if (!enabled) {
            status = STATUS_DISABLED;
            reason = REASON_FLAG_OFF;
        } else if (registered == 0) {
            status = STATUS_UNREGISTERED;
            reason = REASON_UNREGISTERED;
        } else {
            status = STATUS_READY;
            reason = null;
        }
        return new ToolCapabilityDto(SessionToolCategory.CU, "电脑操作", enabled,
            registered > 0, registered, known, status, reason);
    }

    private ToolCapabilityDto browser() {
        boolean enabled = flag(FLAG_BROWSER);
        List<String> known = new ArrayList<>(BrowserToolRegistry.SPECS.size());
        for (var spec : BrowserToolRegistry.SPECS) {
            known.add(BrowserToolRegistry.TOOL_PREFIX + spec.toolName());
        }
        int registered = registeredCount(SessionToolCategory.BR_PREFIX);
        boolean connected = browserConnected();
        String status;
        String reason;
        if (!enabled) {
            status = STATUS_DISABLED;
            reason = REASON_FLAG_OFF;
        } else if (registered == 0) {
            status = STATUS_UNREGISTERED;
            reason = REASON_UNREGISTERED;
        } else if (!connected) {
            status = STATUS_DISCONNECTED;
            reason = REASON_EXTENSION_DISCONNECTED;
        } else {
            status = STATUS_READY;
            reason = null;
        }
        return new ToolCapabilityDto(SessionToolCategory.BR, "浏览器控制", enabled,
            registered > 0, registered, known, status, reason);
    }

    private BrowserCapabilityDto browserConnection() {
        long seen = browserWsChannel == null ? 0L : browserWsChannel.lastSeenMillis();
        return new BrowserCapabilityDto(browserConnected(), seen > 0
            ? OffsetDateTime.ofInstant(Instant.ofEpochMilli(seen), ZoneId.systemDefault()).toString()
            : null);
    }

    private boolean browserConnected() {
        return browserWsChannel != null && browserWsChannel.hasSessionConnection();
    }

    private boolean flag(String key) {
        return Boolean.TRUE.equals(environment.getProperty(key, Boolean.class, Boolean.FALSE));
    }

    private int registeredCount(String prefix) {
        List<Tool> tools;
        try {
            tools = toolRegistry == null ? List.of() : toolRegistry.all();
        } catch (Exception e) {
            return 0;
        }
        int n = 0;
        for (Tool t : tools) {
            String name = t == null ? null : t.name();
            if (name != null && name.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }
}
