package com.nexusai.application.agent.computeruse;

/**
 * 剪贴板读写原语 · computer-use Phase 2a 的 {@code read_clipboard} / {@code write_clipboard} 共用。
 *
 * <p><b>WHY 抽接口</b>：本仓既有约定（见 {@code ComputerUseCursor} 类 javadoc）——
 * 纯单测需要「不碰真实桌面」的假实现 ⇒ 能力必须可注入。
 *
 * <h2>CCB 对照</h2>
 * <ul>
 *   <li>读：CCB {@code toolCalls.ts:3576} {@code const text = await adapter.executor.readClipboard()}
 *       ⇒ 返回 {@code okJson({ text })}（{@code toolCalls.ts:3577}）。</li>
 *   <li>写：CCB {@code toolCalls.ts:3627} {@code await adapter.executor.writeClipboard(text)}
 *       ⇒ 返回 {@code 'Clipboard written.'}（{@code toolCalls.ts:3628}）。</li>
 *   <li>Windows 实现：CCB {@code src/utils/computerUse/executorCrossPlatform.ts:319-355}
 *       走 PowerShell {@code Get-Clipboard} / {@code Set-Clipboard}；
 *       <b>本仓改用 JNA 直调 Win32 剪贴板 API</b>（不引入新依赖、不起子进程）。</li>
 * </ul>
 */
public interface ComputerUseClipboard {

    /**
     * 读取剪贴板文本。
     *
     * <p>非文本格式 / 剪贴板为空 ⇒ 返回空串（不抛）。
     *
     * @return 剪贴板文本（空串 = 无内容）
     * @throws Exception 读取失败（fail loud）
     */
    String readText() throws Exception;

    /**
     * 写入剪贴板文本。
     *
     * @param text 要写入的文本
     * @throws Exception 写入失败（fail loud）
     */
    void writeText(String text) throws Exception;
}
