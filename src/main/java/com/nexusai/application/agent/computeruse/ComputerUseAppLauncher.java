package com.nexusai.application.agent.computeruse;

/**
 * 应用启动原语 · computer-use Phase 2a 的 {@code open_application} 用。
 *
 * <p><b>WHY 抽接口</b>：纯单测需要「不真启动应用」的假实现 ⇒ 能力必须可注入。
 *
 * <h2>CCB 对照</h2>
 * <ul>
 *   <li>CCB {@code packages/@ant/computer-use-mcp/src/toolCalls.ts:2836-2895}
 *       的 {@code handleOpenApplication}：先把 app 名解析成 allowlist 里的条目，
 *       再 {@code await adapter.executor.openApp(targetBundleId)}。</li>
 *   <li>Windows 实现：CCB {@code src/utils/computerUse/platforms/win32.ts:565-756}
 *       的 {@code apps.open(name)}（PowerShell {@code Start-Process} + 找 HWND + 绑定到
 *       屏幕外 {@code (-32000,-32000)}）。</li>
 * </ul>
 */
public interface ComputerUseAppLauncher {

    /**
     * 启动（或置前）一个应用。
     *
     * @param appKey 应用标识（<b>归一后的可执行文件名</b>，如 {@code "chrome"}；
     *                本仓的授权键语义，见 {@link ComputerUseAppPolicy#normalizeAppKey(String)}）
     * @throws Exception 启动失败（fail loud，由调用方转成工具错误）
     */
    void launch(String appKey) throws Exception;
}
