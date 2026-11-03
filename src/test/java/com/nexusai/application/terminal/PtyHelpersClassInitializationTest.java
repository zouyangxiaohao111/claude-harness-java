package com.nexusai.application.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>钉住「pty4j Unix 路径能初始化」这条根因</b>：断言 {@code com.pty4j.unix.PtyHelpers} 的
 * 静态初始化在本机能走完。
 *
 * <p><b>WHY 必须有这个测试</b>：pty4j 0.12.x 的 {@code PtyHelpers} 静态初始化要读
 * {@code jtermios.JTermios} 的 23 个 POSIX 常量，而 jtermios 来自 {@code purejavacomm} ——
 * 该构件全网拿不到，构建时只能 exclude ⇒ 类初始化直接失败，用户看到的是
 * 「创建终端」报 {@code Could not initialize class com.pty4j.unix.PtyHelpers}。
 * 这个缺陷<b>只在真机 Unix 上暴露</b>，mock 覆盖不到；而一旦换回 0.12.x，本测试立刻变红
 * （{@code ExceptionInInitializerError} / {@code NoClassDefFoundError: jtermios/JTermios}）。
 *
 * <p><b>为什么直接 {@code Class.forName} 而不是起终端</b>：起终端还会受 shell 白名单、cwd、
 * 原生库解压目录影响，噪音大；类初始化成功是「Unix 路径结构性可用」的最小充分条件，
 * 真机起 shell 另由 {@link TerminalServiceSmokeTest} 覆盖。
 *
 * <p>Windows 走 {@code com.pty4j.windows.*}，压根不加载这个类 ⇒ 跳过（不是失败）。
 */
@DisplayName("pty4j Unix 路径：PtyHelpers 静态初始化可完成（jtermios 依赖已消失）")
@DisabledOnOs(OS.WINDOWS)
class PtyHelpersClassInitializationTest {

    @Test
    @DisplayName("Class.forName(PtyHelpers, initialize=true) 不抛 LinkageError")
    void ptyHelpersInitializes() throws Exception {
        Class<?> helpers;
        try {
            // initialize=true：只有真的跑完静态块才算通过 —— 0.12.x 会在这里抛
            // ExceptionInInitializerError（真因 NoClassDefFoundError: jtermios/JTermios）。
            helpers = Class.forName("com.pty4j.unix.PtyHelpers", true,
                Thread.currentThread().getContextClassLoader());
        } catch (LinkageError e) {
            throw new AssertionError("PtyHelpers 类初始化失败（Unix PTY 路径不可用；"
                + "检查 pty4j 是否被降回 0.13.0 以下、或 purejavacomm 是否被 exclude）。"
                + " os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"), e);
        }
        assertThat(helpers.getName()).isEqualTo("com.pty4j.unix.PtyHelpers");
    }

    @Test
    @DisplayName("jtermios 不应出现在 classpath（0.13.0+ 已彻底移除该依赖）")
    void jtermiosIsNotOnClasspath() {
        assertThat(isLoadable("jtermios.JTermios"))
            .as("jtermios 若重新出现在 classpath，说明 pty4j 被降回 0.12.x 且 purejavacomm 被引入 —— "
                + "这会重新引入 macOS/Linux 创建终端失败")
            .isFalse();
    }

    private static boolean isLoadable(String className) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
