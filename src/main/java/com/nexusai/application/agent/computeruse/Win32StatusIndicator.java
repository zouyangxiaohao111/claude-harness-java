package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 状态指示浮层实现（PowerShell + WinForms 独立进程）· computer-use Phase 2b。
 *
 * <p><b>机制</b>：把一段 WinForms 脚本写到临时 {@code .ps1}，用
 * {@code powershell -File} 起一个<b>独立进程</b>画浮层（点击穿透、置顶、2 秒淡出），
 * 进程退出即消失。{@link #hide()} 结束该进程。
 *
 * <p><b>⚠️ 与 CCB 的差异（如实标注）</b>：CCB 的 {@code inputIndicator.ts} 是
 * 「常驻进程 + 消息文件轮询」（{@code showIndicator} / {@code updateIndicator} /
 * {@code hideIndicator} 三个入口 + 一个 stop 文件）；
 * 本仓是「一次性进程 + 显式 hide」。可观察差异：CCB 在 2 秒内连续 {@code show}
 * 不会重启浮层进程；本仓每次 {@code show} 都是新进程。浮层的<b>可见效果</b>相同。
 */
public final class Win32StatusIndicator implements ComputerUseStatusIndicator {

    private static final Logger log = LoggerFactory.getLogger(Win32StatusIndicator.class);

    /** 浮层自动淡出时间（毫秒）· CCB {@code inputIndicator.ts:17}（{@code FADE_AFTER_MS}）。 */
    private static final int FADE_AFTER_MS = 2000;

    /** 浮层宽高 · CCB {@code inputIndicator.ts:14-15}。 */
    private static final int WIDTH = 350;
    private static final int HEIGHT = 28;

    private final ComputerUsePowerShell powerShell;
    private final AtomicReference<Process> current = new AtomicReference<>();

    /** 默认构造（60s 超时的 PowerShell 执行器）。 */
    public Win32StatusIndicator() {
        this(new ComputerUsePowerShell());
    }

    /**
     * @param powerShell PowerShell 执行器
     */
    public Win32StatusIndicator(ComputerUsePowerShell powerShell) {
        this.powerShell = powerShell == null ? new ComputerUsePowerShell() : powerShell;
    }

    @Override
    public void show(long hwnd, String message) {
        // 先结束上一个（CCB 的 showIndicator 也是先 hideIndicator）
        hide();
        try {
            Process process = new ProcessBuilder(buildCommand(hwnd, message))
                    .redirectErrorStream(true)
                    .start();
            current.set(process);
            if (log.isInfoEnabled()) {
                log.info("[computer-use] 状态浮层已启动: hwnd={} message=\"{}\"", hwnd, message);
            }
        } catch (IOException e) {
            // 浮层是<b>辅助可视化</b>，起不来不该让工具失败（CCB 的 updateIndicator 同样吞异常）
            log.warn("[computer-use] 状态浮层启动失败（忽略）: {}", e.getMessage());
        }
    }

    @Override
    public void hide() {
        Process process = current.getAndSet(null);
        if (process == null) {
            return;
        }
        try {
            process.destroy();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public boolean isActive() {
        Process process = current.get();
        return process != null && process.isAlive();
    }

    /** 构造 {@code powershell -File <script.ps1> <hwnd> <message>} 命令行。 */
    private List<String> buildCommand(long hwnd, String message) {
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell");
        cmd.add("-NoProfile");
        cmd.add("-NonInteractive");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-File");
        cmd.add(writeScript());
        cmd.add(Long.toString(hwnd));
        cmd.add(message == null ? "" : message);
        return cmd;
    }

    /**
     * 写浮层脚本到临时文件并返回路径。
     *
     * @return {@code .ps1} 路径
     */
    private String writeScript() {
        try {
            java.nio.file.Path script = java.nio.file.Files.createTempFile("cu_indicator_", ".ps1");
            java.nio.file.Files.writeString(script, scriptBody(), java.nio.charset.StandardCharsets.UTF_8);
            script.toFile().deleteOnExit();
            return script.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("无法写状态浮层脚本: " + e.getMessage(), e);
        }
    }

    /**
     * 浮层脚本正文（WinForms）· 结构对齐 CCB {@code inputIndicator.ts:39-152}。
     *
     * @return PowerShell 脚本文本
     */
    static String scriptBody() {
        return """
                param([long]$hwnd, [string]$message)
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -AssemblyName System.Drawing
                Add-Type @'
                using System;
                using System.Runtime.InteropServices;
                public class CuIndicator {
                    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);
                    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
                    [DllImport("user32.dll", SetLastError=true)] public static extern int GetWindowLong(IntPtr h, int i);
                    [DllImport("user32.dll", SetLastError=true)] public static extern int SetWindowLong(IntPtr h, int i, int v);
                    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
                    public const int GWL_EXSTYLE = -20;
                    public const int WS_EX_LAYERED = 0x80000;
                    public const int WS_EX_TRANSPARENT = 0x20;
                    public const int WS_EX_TOOLWINDOW = 0x80;
                    public const int WS_EX_NOACTIVATE = 0x08000000;
                    public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
                    public const uint SWP_NOACTIVATE = 0x0010;
                    public const uint SWP_SHOWWINDOW = 0x0040;
                    public const uint SWP_NOSIZE = 0x0001;
                    public static void MakeOverlay(IntPtr h) {
                        int ex = GetWindowLong(h, GWL_EXSTYLE);
                        ex |= WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE;
                        SetWindowLong(h, GWL_EXSTYLE, ex);
                    }
                }
                '@
                $target = [IntPtr]::new([long]$hwnd)
                $form = New-Object System.Windows.Forms.Form
                $form.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::None
                $form.ShowInTaskbar = $false
                $form.TopMost = $true
                $form.StartPosition = [System.Windows.Forms.FormStartPosition]::Manual
                $form.Size = New-Object System.Drawing.Size(%d, %d)
                $form.Location = New-Object System.Drawing.Point(-32000, -32000)
                $form.BackColor = [System.Drawing.Color]::FromArgb(240, 30, 30, 30)
                $form.Opacity = 0.92
                $label = New-Object System.Windows.Forms.Label
                $label.Dock = [System.Windows.Forms.DockStyle]::Fill
                $label.ForeColor = [System.Drawing.Color]::FromArgb(220, 220, 220)
                $label.Font = New-Object System.Drawing.Font("Segoe UI", 10)
                $label.TextAlign = [System.Windows.Forms.ContentAlignment]::MiddleLeft
                $label.Padding = New-Object System.Windows.Forms.Padding(8, 0, 8, 0)
                $label.Text = $message
                $form.Controls.Add($label)
                $form.Show()
                [CuIndicator]::MakeOverlay($form.Handle)
                $timer = New-Object System.Windows.Forms.Timer
                $timer.Interval = 50
                $script:shownAt = [DateTime]::Now
                $timer.Add_Tick({
                    if (-not [CuIndicator]::IsWindow($target)) { $timer.Stop(); $form.Close(); exit 0 }
                    if (([DateTime]::Now - $script:shownAt).TotalMilliseconds -gt %d) {
                        $timer.Stop()
                        $form.Close()
                        exit 0
                    }
                    $wr = New-Object CuIndicator+RECT
                    [CuIndicator]::GetWindowRect($target, [ref]$wr) | Out-Null
                    $ww = $wr.R - $wr.L
                    $fx = $wr.L + [int](($ww - %d) / 2)
                    $fy = $wr.B - %d - 8
                    [CuIndicator]::SetWindowPos(
                        $form.Handle, [CuIndicator]::HWND_TOPMOST,
                        $fx, $fy, 0, 0,
                        [CuIndicator]::SWP_NOSIZE -bor [CuIndicator]::SWP_NOACTIVATE -bor [CuIndicator]::SWP_SHOWWINDOW) | Out-Null
                })
                $timer.Start()
                [System.Windows.Forms.Application]::Run()
                """.formatted(WIDTH, HEIGHT, FADE_AFTER_MS, WIDTH, HEIGHT);
    }
}
