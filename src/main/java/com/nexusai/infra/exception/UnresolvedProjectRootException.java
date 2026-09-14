package com.nexusai.infra.exception;

/**
 * 会话项目根解析失败 / 无法判定 → REST <b>400</b>（[S2 · F-03b 2026-09-14 · 用户裁定 #12 (B2)]）。
 *
 * <p><b>WHY 新类型而不是直接用 {@code ValidationException}</b>（三条实测理由，⛔ 勿"简化"回去）：
 * <ol>
 *   <li>{@code CwdResolutionTest} 有 4 处 {@code isInstanceOf(IllegalStateException.class)} 断言
 *       —— 换成 {@code ValidationException}（{@code RuntimeException} 子类）会一次打红；</li>
 *   <li>{@code BashTool} 的 {@code catch (IllegalStateException shellEx)} 包住
 *       {@code ShellExecutor.bash}，而 {@code ShellExecutor} 内部正是
 *       {@code CwdResolution.getOriginalCwdLayer} ⇒ 换类型会造成行为漂移；</li>
 *   <li>本仓已有正确范式 = <b>内部保持自己的异常语义、REST 边界单点翻译</b>
 *       （{@code SessionMemoryExportController} 注释明写「⛔ 不是 500」）。</li>
 * </ol>
 * ⇒ 采用「内部保持 {@code IllegalStateException} 家族 + 新增子类型 + HTTP 边界单点映射」。
 *
 * <p><b>为什么 extends {@link IllegalStateException} 而不是 {@code RuntimeException}</b>：
 * 既有 {@code catch (IllegalStateException)} 吞点与 4 处 {@code isInstanceOf} 断言<b>不受影响</b>
 * ⇒ 改动面最小（用户裁定 #12）。副作用：它会被一切 {@code catch (ISE)} 捕获，这是有意为之
 * （那些 catch 点本就该处理"项目根解析失败"）。
 *
 * <p><b>状态码取 400 而非 409</b>（用户裁定 #12）：与计划登记一致、与
 * {@code SessionMemoryExportController} 同族（「本该有的入参/链路缺了」= 客户端可见的请求前提不成立）；
 * ⛔ 不要改成 409。
 *
 * <p><b>生产接线</b>：{@code CwdResolution.unresolvedProjectRoot(...)} 的 4 个 throw 点
 * （绑定无效 / 有会话未绑定 / 解析失败 + {@code getOriginalCwdLayer} 同款）与 {@code PathGuard}
 * 消费侧 rethrow（F-10 用户裁定 #6 (B)）。{@link GlobalExceptionHandler} 单点译 400。
 */
public class UnresolvedProjectRootException extends IllegalStateException {

    public UnresolvedProjectRootException(String message) {
        super(message);
    }

    public UnresolvedProjectRootException(String message, Throwable cause) {
        super(message, cause);
    }
}
