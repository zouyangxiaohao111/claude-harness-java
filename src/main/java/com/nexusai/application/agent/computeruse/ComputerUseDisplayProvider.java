package com.nexusai.application.agent.computeruse;

import java.util.List;

/**
 * 显示器枚举原语 · computer-use Phase 2a 的 {@code switch_display} 用。
 *
 * <p><b>WHY 抽接口</b>：纯单测需要「不碰真实桌面」的假实现 ⇒ 能力必须可注入
 * （与 {@link ComputerUseCursor} / {@link ComputerUseClipboard} 同款约定）。
 *
 * <h2>CCB 对照</h2>
 * <ul>
 *   <li>枚举：CCB {@code src/utils/computerUse/executorCrossPlatform.ts:240-244}
 *       {@code async listDisplays()} → {@code platform.display.listAll()}。</li>
 *   <li>Windows 实现：CCB {@code src/utils/computerUse/platforms/win32.ts:452-479}
 *       （PowerShell {@code System.Windows.Forms.Screen::AllScreens}）。</li>
 * </ul>
 */
public interface ComputerUseDisplayProvider {

    /**
     * 列出全部已连接的显示器。
     *
     * @return 显示器列表（按 Win32 枚举顺序；可能为空 = 枚举失败）
     */
    List<ComputerUseDisplay> listDisplays();
}
