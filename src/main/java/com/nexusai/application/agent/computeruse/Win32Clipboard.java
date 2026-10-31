package com.nexusai.application.agent.computeruse;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Win32 剪贴板实现（JNA 直调 user32 + kernel32）· computer-use Phase 2a。
 *
 * <h2>WHY 用 JNA 而不是 CCB 的 PowerShell</h2>
 * <p>CCB 的 Windows 剪贴板读写走 PowerShell 子进程
 * （{@code src/utils/computerUse/executorCrossPlatform.ts:319-355}：
 * {@code powershell -NoProfile -Command Get-Clipboard} /
 * {@code Set-Clipboard -Value '...'}）。
 * <br>本仓改用 JNA 直调 Win32 剪贴板 API：<b>同款语义</b>（CF_UNICODETEXT 文本剪贴板），
 * 但<b>不起子进程</b>（无 PowerShell 转义陷阱、无进程启动开销）。
 * <br>⛔ <b>不引入新依赖</b>：JNA 已是本仓既有依赖（{@code backend/pom.xml} 的
 * {@code net.java.dev.jna:jna} + {@code jna-platform}）。
 *
 * <h2>协议（标准 Win32 剪贴板文本流程）</h2>
 * <ol>
 *   <li>{@code OpenClipboard(NULL)} —— 独占剪贴板（失败 = 别的进程持有）；</li>
 *   <li><b>写</b>：{@code EmptyClipboard()} → {@code GlobalAlloc(GMEM_MOVEABLE, n)}
 *       → {@code GlobalLock} + 写 UTF-16LE + NUL → {@code GlobalUnlock}
 *       → {@code SetClipboardData(CF_UNICODETEXT, hMem)}；</li>
 *   <li><b>读</b>：{@code GetClipboardData(CF_UNICODETEXT)} → {@code GlobalLock}
 *       → 读 UTF-16LE → {@code GlobalUnlock}；</li>
 *   <li>{@code CloseClipboard()}。</li>
 * </ol>
 *
 * <h2>⚠️ 所有权</h2>
 * <p>{@code SetClipboardData} 成功后，<b>内存所有权转移给系统</b>（系统负责释放）
 * ⇒ 本仓<b>不</b>再调 {@code GlobalFree}（重复释放 = 崩溃）。
 */
public final class Win32Clipboard implements ComputerUseClipboard {

    private static final Logger log = LoggerFactory.getLogger(Win32Clipboard.class);

    /** CF_UNICODETEXT —— Unicode 文本剪贴板格式。 */
    private static final int CF_UNICODETEXT = 13;

    /** GMEM_MOVEABLE —— {@code GlobalAlloc} 的可移动内存标志（剪贴板要求）。 */
    private static final int GMEM_MOVEABLE = 0x0002;

    /** user32 剪贴板 API。 */
    private interface ClipboardUser32 extends Library {
        ClipboardUser32 INSTANCE = Native.load("user32", ClipboardUser32.class, W32APIOptions.UNICODE_OPTIONS);

        boolean OpenClipboard(WinDef.HWND hwndNewOwner);

        boolean CloseClipboard();

        boolean EmptyClipboard();

        Pointer GetClipboardData(int uFormat);

        WinNT.HANDLE SetClipboardData(int uFormat, WinNT.HANDLE hMem);
    }

    /** kernel32 全局内存 API（剪贴板数据必须放在全局内存里）。 */
    private interface ClipboardKernel32 extends Library {
        ClipboardKernel32 INSTANCE = Native.load("kernel32", ClipboardKernel32.class);

        WinNT.HANDLE GlobalAlloc(int uFlags, Pointer dwBytes);

        Pointer GlobalLock(WinNT.HANDLE hMem);

        boolean GlobalUnlock(WinNT.HANDLE hMem);

        WinNT.HANDLE GlobalFree(WinNT.HANDLE hMem);
    }

    private final ClipboardUser32 user32 = ClipboardUser32.INSTANCE;
    private final ClipboardKernel32 kernel32 = ClipboardKernel32.INSTANCE;

    @Override
    public String readText() throws Exception {
        if (!user32.OpenClipboard(null)) {
            throw new IllegalStateException("[computer-use] OpenClipboard 失败（剪贴板被其它进程占用）");
        }
        try {
            Pointer data = user32.GetClipboardData(CF_UNICODETEXT);
            if (data == null) {
                // 剪贴板为空或不含 Unicode 文本 ⇒ 空串（不抛）
                return "";
            }
            WinNT.HANDLE handle = new WinNT.HANDLE(data);
            Pointer locked = kernel32.GlobalLock(handle);
            if (locked == null) {
                throw new IllegalStateException("[computer-use] GlobalLock 失败（读剪贴板）");
            }
            try {
                return readUtf16(locked);
            } finally {
                kernel32.GlobalUnlock(handle);
            }
        } finally {
            user32.CloseClipboard();
        }
    }

    @Override
    public void writeText(String text) throws Exception {
        String value = text == null ? "" : text;
        byte[] bytes = toUtf16Le(value);

        if (!user32.OpenClipboard(null)) {
            throw new IllegalStateException("[computer-use] OpenClipboard 失败（剪贴板被其它进程占用）");
        }
        try {
            user32.EmptyClipboard();
            // 剪贴板内存必须是可移动的全局内存
            WinNT.HANDLE mem = kernel32.GlobalAlloc(GMEM_MOVEABLE, new Pointer(bytes.length));
            if (mem == null) {
                throw new IllegalStateException("[computer-use] GlobalAlloc 失败（写剪贴板）");
            }
            Pointer locked = kernel32.GlobalLock(mem);
            if (locked == null) {
                throw new IllegalStateException("[computer-use] GlobalLock 失败（写剪贴板）");
            }
            try {
                locked.write(0, bytes, 0, bytes.length);
            } finally {
                kernel32.GlobalUnlock(mem);
            }
            if (user32.SetClipboardData(CF_UNICODETEXT, mem) == null) {
                // SetClipboardData 失败 ⇒ 所有权未转移 ⇒ 本仓负责释放
                kernel32.GlobalFree(mem);
                throw new IllegalStateException("[computer-use] SetClipboardData 失败");
            }
            // 成功 ⇒ 所有权已转移给系统 ⇒ 不再 GlobalFree
        } finally {
            user32.CloseClipboard();
        }
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] 剪贴板已写入: {} 字符", value.length());
        }
    }

    /**
     * 从 UTF-16LE 缓冲区读字符串（遇到 NUL 终止符停）。
     *
     * @param ptr 缓冲区首地址
     * @return 字符串
     */
    private static String readUtf16(Pointer ptr) {
        // 剪贴板文本长度没有上限字段 ⇒ 读到 NUL 为止（上限 1M 字符，防病态数据）
        int maxChars = 1 << 20;
        char[] chars = new char[maxChars];
        int count = 0;
        while (count < maxChars) {
            char ch = ptr.getChar((long) count * 2);
            if (ch == '\0') {
                break;
            }
            chars[count] = ch;
            count++;
        }
        return new String(chars, 0, count);
    }

    /**
     * Java 字符串 → UTF-16LE 字节（含结尾 NUL）。
     *
     * @param text 文本
     * @return 字节数组
     */
    private static byte[] toUtf16Le(String text) {
        char[] chars = text.toCharArray();
        byte[] bytes = new byte[(chars.length + 1) * 2];
        for (int i = 0; i < chars.length; i++) {
            bytes[i * 2] = (byte) (chars[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((chars[i] >> 8) & 0xFF);
        }
        // 末尾 NUL 已由数组长度保证为 0
        return bytes;
    }
}
