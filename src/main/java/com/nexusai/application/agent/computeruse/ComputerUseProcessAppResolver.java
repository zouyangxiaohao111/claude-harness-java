package com.nexusai.application.agent.computeruse;

/**
 * 进程 → 应用授权键的解析器 · computer-use Phase 2b 的<b>绑定窗口门控</b>底座。
 *
 * <p><b>WHY 需要它</b>：Phase 2b 引入「窗口绑定」后，窗口定向输入打向的是
 * <b>绑定窗口所属的应用</b>，而不再必然是前台应用 ⇒ 档位门控必须查
 * <b>绑定窗口的进程</b>对应的授权条目（否则会出现「绑了未授权的窗口还能往里打字」的洞）。
 *
 * <p><b>与前台解析器的关系</b>：{@link ComputerUseForegroundAppResolver} 解析的是
 * <b>前台</b>窗口；本接口解析的是<b>指定 pid</b>。两者共用同一套「pid → exe 全路径 →
 * 归一 exe 名」的取值链路（见 {@link Win32ForegroundAppResolver}）。
 */
public interface ComputerUseProcessAppResolver {

    /**
     * 取进程所属应用的授权键。
     *
     * @param pid 进程 id
     * @return 归一后的 exe 名（如 {@code "chrome"}）；<b>未知 / 解析失败 ⇒ null</b>
     */
    String appIdForPid(int pid);
}
