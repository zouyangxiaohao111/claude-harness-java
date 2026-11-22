package com.nexusai.application.agent.ast;

import com.nexusai.application.agent.tool.AbortController;

import java.nio.file.Path;
import java.util.List;

/**
 * ast-grep CLI 调用抽象 · 让 {@link AstGrepRunner} 的解析/分页逻辑可离线单测。
 *
 * <p>生产实现 {@link ProcessAstGrepCli}（ProcessBuilder 起子进程）；测试用 fake 实现返回
 * 预置的 JSON 行，从而覆盖「参数组装 → 输出解析 → 排序/分页」而不依赖真实二进制与平台。
 */
public interface AstGrepCli {

    /**
     * 一次 CLI 调用的原始结果。
     *
     * @param exitCode      进程退出码（-1 = 非正常退出，如被 kill）
     * @param stdoutLines   stdout 逐行（已剥尾 {@code \r}；超出上限时只保留已读行）
     * @param stderrLines   stderr 逐行（上限 2_000 行，超出丢弃）
     * @param outputTruncated true = stdout 命中行数超上限、进程被本端 kill（调用方不得按退出码判错）
     */
    record Result(int exitCode, List<String> stdoutLines, List<String> stderrLines, boolean outputTruncated) {}

    /** CLI 层失败 · kind 决定工具层文案（abort/timeout 不是「ast-grep 报错」）。 */
    final class AstCliException extends Exception {

        /** 失败类别。 */
        public enum Kind {
            /** 用户取消（AbortController 触发）。 */
            ABORTED,
            /** 超过 timeoutMs 未结束。 */
            TIMEOUT,
            /** 进程无法启动（二进制缺失/不可执行）。 */
            SPAWN
        }

        private final Kind kind;

        public AstCliException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /**
     * 执行一次 ast-grep。
     *
     * @param args      ast-grep 参数（不含二进制路径；{@code run -p ... --json=stream ...}）
     * @param workdir   子进程工作目录（只影响 sgconfig.yml 等项目配置发现；路径参数本身是绝对路径）
     * @param abort     取消信号（null → NOOP）
     * @param timeoutMs 本次调用超时（毫秒）
     * @return 原始输出
     * @throws AstCliException 取消 / 超时 / 启动失败
     */
    Result run(List<String> args, Path workdir, AbortController abort, long timeoutMs) throws AstCliException;
}
