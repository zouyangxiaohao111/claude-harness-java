package com.nexusai.application.agent.computeruse;

/**
 * computer-use 的能力记录 · 对齐 CCB {@code buildComputerUseTools(caps, ...)} 的 {@code caps} 参数
 * （CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:118-127}）。
 *
 * <p>CCB 的 CLI 侧 {@code CLI_CU_CAPABILITIES}（{@code src/utils/computerUse/common.ts:54-63}）为
 * {@code {screenshotFiltering, platform}} —— <b>不含 teachMode</b> ⇒ teach 三件套恒不下发。
 *
 * @param screenshotFiltering {@code "native"} | {@code "none"}（决定 screenshot 描述文案）
 * @param platform            {@code "darwin"} | {@code "win32"} | {@code "linux"}
 *                            （决定 Windows 专属 11 个工具是否展开）
 * @param teachMode           是否下发 teach 三件套；本仓与 CCB CLI 一样恒 {@code false}
 */
public record ComputerUseCapabilities(String screenshotFiltering, String platform, boolean teachMode) {
}
