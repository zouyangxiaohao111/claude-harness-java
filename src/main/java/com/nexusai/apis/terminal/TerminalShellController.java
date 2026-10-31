package com.nexusai.apis.terminal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusai.application.terminal.TerminalService;
import com.nexusai.application.terminal.TerminalShellResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 终端能力探测 REST（无会话维度）· 供前端「基础设置 · 默认终端」下拉使用。
 *
 * <ul>
 *   <li>{@code GET /api/v1/terminals/shells} → 200 {@code {shells:[{value,label}], default}}</li>
 * </ul>
 *
 * <p><b>value 为什么必须原样取自 resolver</b>：它是 {@code POST /sessions/{sessionId}/terminals}
 * 的 {@code shell} 参数，后端按 {@link TerminalShellResolver#validate} 逐字比对白名单——换成任何
 * 「看起来一样」的写法（换大小写以外的规范化、去后缀）都会变成 400。
 *
 * <p>只读、无副作用：不创建 PTY、不写库；探测结果在 resolver 内缓存（WSL 探测最多一次）。
 */
@RestController
@RequestMapping("/api/v1/terminals")
public class TerminalShellController {

    private static final Logger log = LoggerFactory.getLogger(TerminalShellController.class);

    @Autowired private TerminalService terminalService;

    /**
     * 本机可用 shell 列表 + 默认值。
     *
     * @return 200 {@code {shells, default}}；本机一个都没有 ⇒ 空数组 + 空串（并打 WARN）
     */
    @GetMapping("/shells")
    public ShellsResponse shells() {
        List<String> available = terminalService.availableShells();
        List<ShellOption> options = new ArrayList<>(available.size());
        for (String value : available) {
            options.add(new ShellOption(value, TerminalShellResolver.labelFor(value)));
        }
        String defaultShell;
        if (available.isEmpty()) {
            defaultShell = "";
            log.warn("[TerminalShellController] 本机未探测到任何可用 shell（前端下拉只剩默认项）");
        } else {
            defaultShell = terminalService.defaultShell();
            if (log.isDebugEnabled()) {
                log.debug("[TerminalShellController] shell 列表: {} 默认={}", options, defaultShell);
            }
        }
        return new ShellsResponse(options, defaultShell);
    }

    /** 单个 shell 选项：{@code value} = 白名单原值（可直接回传 create），{@code label} = 友好名。 */
    public record ShellOption(String value, String label) {}

    /** 响应体；{@code default} 是 Java 关键字 ⇒ 组件名用 defaultShell + Jackson 改名。 */
    public record ShellsResponse(List<ShellOption> shells,
                                 @JsonProperty("default") String defaultShell) {}
}
