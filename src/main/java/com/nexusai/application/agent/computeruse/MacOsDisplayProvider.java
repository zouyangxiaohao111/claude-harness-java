package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * macOS 显示器枚举实现（JXA + CoreGraphics）· computer-use 的 {@code switch_display} 用。
 *
 * <h2>CCB 对照</h2>
 * <p>CCB {@code packages/@ant/computer-use-swift/src/backends/darwin.ts:92-152}
 * 的 {@code display.listAll()}（JXA 走 {@code CGGetActiveDisplayList}；
 * 失败时回落 {@code NSScreen.screens}；再失败回落 {@code 1920x1080@2.0}）。
 * <br>⇒ 本仓沿用同一路线（{@link MacOsJxa#displayList()}），
 * 差异（缩放比方向 / 补 origin）见 {@link MacOsJxa#displayList()} 的类注释。
 *
 * <h2>显示器名 = {@code "display N"}</h2>
 * <p>与 Windows 侧（{@link Win32DisplayProvider}）同款 —— CCB 的 macOS
 * {@code listAll} 也<b>不产出 label</b> ⇒ CCB 的 {@code uniqueDisplayLabels}
 * 回落到 {@code `display ${d.displayId}`}。
 */
public final class MacOsDisplayProvider implements ComputerUseDisplayProvider {

    private static final Logger log = LoggerFactory.getLogger(MacOsDisplayProvider.class);

    private final MacOsDisplays displays;

    /** 生产构造器（真起进程）。 */
    public MacOsDisplayProvider() {
        this(new MacOsDisplays());
    }

    /**
     * @param displays 显示器几何来源
     */
    public MacOsDisplayProvider(MacOsDisplays displays) {
        if (displays == null) {
            throw new IllegalArgumentException("[computer-use] MacOsDisplayProvider 依赖不能为空");
        }
        this.displays = displays;
    }

    @Override
    public List<ComputerUseDisplay> listDisplays() {
        List<MacOsDisplays.Info> infos = displays.list();
        List<ComputerUseDisplay> out = new ArrayList<>();
        double ppt = displays.pixelsPerPoint();
        for (int i = 0; i < infos.size(); i++) {
            MacOsDisplays.Info info = infos.get(i);
            // 单位约定与 Windows 侧一致：宽高 = 屏幕像素；原点 = 虚拟屏幕中的像素偏移
            int originX = (int) Math.round(info.originX() * ppt);
            int originY = (int) Math.round(info.originY() * ppt);
            out.add(new ComputerUseDisplay(
                    i,
                    "display " + i,
                    info.pixelWidth(),
                    info.pixelHeight(),
                    originX,
                    originY,
                    info.primary(),
                    "cg:" + info.displayId()));
        }
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] macOS 显示器枚举结果: {}", out);
        }
        return List.copyOf(out);
    }
}
