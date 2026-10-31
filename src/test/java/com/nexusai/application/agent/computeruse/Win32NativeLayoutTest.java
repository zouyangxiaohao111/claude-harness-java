package com.nexusai.application.agent.computeruse;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JNA 结构体 / 原生内存布局单测。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：{@code SendInput} 的 {@code INPUT} 联合体是<b>手工</b>按
 * Win32 x64 ABI 拼字节的（type@0 / union@8 / dwFlags@20）。偏移写错 ⇒ 事件类型或标志位错位
 * ⇒ 点击变成别的动作，或完全不生效，且<b>不报任何错</b>（SendInput 照样返回 1）。
 * 本测试把每个偏移锁死，让「写错偏移」在单测阶段就红。
 *
 * <p>（本测试的这次红是真的抓到了 bug：初版用 {@code mem.getPointer(0)} 取指针，
 * 而 JNA 的 {@code Memory.getPointer(long)} 返回 null ⇒ 派发点击时 NPE。）
 */
class Win32NativeLayoutTest {

    @Test
    @DisplayName("手工构造的 INPUT 内存：type@0 / union@8 / dwFlags@20 逐字节正确")
    void mouseInputLayoutIsCorrect() {
        assumeTrue(Platform.isWindows(), "Win32 专属布局断言");
        assumeTrue(Native.SIZE_T_SIZE == 8, "仅覆盖 64 位 Windows ABI");

        Memory mem = Win32Native.buildMouseInput(Win32Native.MOUSEEVENTF_LEFTDOWN);
        assertThat((int) mem.size()).isEqualTo(40);
        assertThat(mem.getInt(Win32Native.INPUT_TYPE_OFFSET)).isEqualTo(Win32Native.INPUT_MOUSE);
        assertThat(mem.getInt(Win32Native.MOUSEINPUT_DX_OFFSET)).isZero();
        assertThat(mem.getInt(Win32Native.MOUSEINPUT_DY_OFFSET)).isZero();
        assertThat(mem.getInt(Win32Native.MOUSEINPUT_MOUSEDATA_OFFSET)).isZero();
        assertThat(mem.getInt(Win32Native.MOUSEINPUT_DWFLAGS_OFFSET))
                .isEqualTo(Win32Native.MOUSEEVENTF_LEFTDOWN);
        assertThat(mem.getInt(Win32Native.MOUSEINPUT_TIME_OFFSET)).isZero();
    }

    @Test
    @DisplayName("LEFTDOWN / LEFTUP 标志位与 CCB win32 backend 一致（0x0002 / 0x0004）")
    void mouseEventFlagsMatchCcb() {
        assertThat(Win32Native.MOUSEEVENTF_LEFTDOWN).isEqualTo(0x0002);
        assertThat(Win32Native.MOUSEEVENTF_LEFTUP).isEqualTo(0x0004);
        assertThat(Win32Native.INPUT_MOUSE).isEqualTo(0);
        assertThat(Win32Native.PW_RENDERFULLCONTENT).isEqualTo(2);
    }

    @Test
    @DisplayName("JNA 结构体布局自证通过（MOUSEINPUT=32 / INPUT=40 / BITMAPINFOHEADER=40）")
    void structLayoutSelfCheckPasses() {
        assumeTrue(Platform.isWindows(), "Win32 专属布局断言");
        Win32Native.assertStructLayout();
    }
}
