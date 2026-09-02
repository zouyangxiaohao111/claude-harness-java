package com.nexusai.application.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * remote mode 统一判定 · Java 独有增强（CC {@code bootstrap/state.ts} 无对应配置）。
 *
 * <p><b>CC 真源</b>：{@code Open-ClaudeCode/src/bootstrap/state.ts}
 * <pre>{@code
 * // :199 isRemoteMode: boolean（STATE 字段，:390 默认 false）
 * export function getIsRemoteMode(): boolean {  // :1631
 *   return STATE.isRemoteMode
 * }
 * export function setIsRemoteMode(value: boolean): void {  // :1635
 *   STATE.isRemoteMode = value
 * }
 * }</pre>
 * CC 仅经 CLI 入口 {@code main.tsx:3328}（--teleport 远程挂接）/ {@code main.tsx:3447}
 * （remote session TUI）调用 {@code setIsRemoteMode(true)} 置位；无 env 通道（getIsRemoteMode
 * 只读 STATE，与 print.ts:512 的 CLAUDE_CODE_REMOTE env 是两路独立信号）。Java Web 后端无
 * {@code --remote}/--teleport argv 等价，用配置通道 {@code nexusai.memory.remote-mode} 建模
 * （Java 独有增强，CC 无对应配置项）。
 *
 * <p><b>门控语义</b>：isRemoteMode=true 时记忆功能跳过 —— {@code initSessionMemory}
 * （sessionMemory.ts:358）+ {@code executeExtractMemoriesImpl}（extractMemories.ts:549-552）。
 * 默认 false（对齐 CC state.ts:390 默认），web 后端行为等价不变。
 *
 * <p><b>静态桥接</b>：与 {@link MemoryBareModeConfig} 同款——Spring 注入值经构造器桥接到
 * 静态字段。POJO {@code new} 场景（单测）不触发 Spring 构造 → 桥接为 null → 走默认 false；
 * 测试用 {@link #reset()} 防跨测试污染。
 */
@Component
public class MemoryRemoteModeConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryRemoteModeConfig.class);

    /** Spring 注入的配置值桥接（{@code nexusai.memory.remote-mode}）；null = 未配置 = 默认 false */
    private static volatile Boolean springConfiguredRemoteMode;

    public MemoryRemoteModeConfig(@Value("${nexusai.memory.remote-mode:#{null}}") Boolean remoteMode) {
        springConfiguredRemoteMode = remoteMode;
        if (remoteMode != null) {
            log.info("MemoryRemoteModeConfig 启动注入 nexusai.memory.remote-mode={}（Java 独有增强，"
                + "CC 经 main.tsx setIsRemoteMode CLI 置位）", remoteMode);
        }
    }

    /**
     * remote mode 统一判定 · 对齐 CC {@code getIsRemoteMode()}（state.ts:1631-1633，
     * STATE.isRemoteMode 默认 false :390）。
     *
     * <p>优先级：配置（{@code nexusai.memory.remote-mode}）→ 默认 {@code false}
     * （对齐 CC 默认非 remote，state.ts:390）。true = SM 提取 init（sessionMemory.ts:358）/
     * extract 提取（extractMemories.ts:549-552）跳过。
     *
     * @return true = remote mode
     */
    public static boolean isRemoteMode() {
        Boolean configured = springConfiguredRemoteMode;
        if (configured != null) {
            if (log.isDebugEnabled()) {
                log.debug("remote mode 判定：nexusai.memory.remote-mode 配置生效 = {}", configured);
            }
            return configured;
        }
        if (log.isDebugEnabled()) {
            log.debug("remote mode 判定：配置未设，默认 false（对齐 CC state.ts:390）");
        }
        return false;
    }

    /** 测试辅助：重置静态桥接（防跨测试 / 跨 Spring 上下文污染，对齐 MemoryBareModeConfig.reset）。 */
    public static void reset() {
        springConfiguredRemoteMode = null;
    }
}
