package com.nexusai.wechatlog.key;

import java.nio.file.Path;
import java.util.List;

/**
 * 从一个运行中的微信进程里取出密钥候选。
 *
 * <h2>为什么返回「候选列表」而不是「密钥」</h2>
 * 各平台抓到的产物形态不统一，且与微信版本相关（见 {@code KeyMatcher} 类注释）。
 * 把判定推给 {@code KeyMatcher}（用 HMAC 校验第 1 页）比让每个平台自己判断更可靠 ——
 * 平台实现只需要老实把「可能是密钥的 32 字节」都交回来。
 *
 * <h2>三平台实现对照</h2>
 * <table>
 *   <tr><th>实现</th><th>机制</th><th>抓取点</th><th>是否需要提权</th></tr>
 *   <tr><td>{@code MacOsKeyExtractor}</td><td>lldb 断点</td>
 *       <td>{@code CCCryptorCreate}（raw key）或 {@code CCKeyDerivationPBKDF}（passphrase）</td>
 *       <td>是（SIP 关闭 + 微信 ad-hoc 重签名 + osascript 授权）</td></tr>
 *   <tr><td>{@code WindowsKeyExtractor}</td><td>JNA 只读内存扫描</td>
 *       <td>{@code com.Tencent.WCDB.Config.Cipher} 附近的候选</td>
 *       <td>是（管理员 / UAC）</td></tr>
 *   <tr><td>{@code LinuxKeyExtractor}</td><td>gdb 断点</td>
 *       <td>WCDB 密钥派生函数</td>
 *       <td>是（root + ptrace_scope 放开）</td></tr>
 * </table>
 */
public interface WeChatKeyExtractor {

    /**
     * 抽取密钥候选。
     *
     * @param accountDir 当前账号目录（部分实现需要读库来做自校验）
     * @return 候选字节集合（每个 32 字节）；失败时返回空列表并记录日志
     * @throws KeyExtractionException 当前置条件不满足（SIP 未关、权限不足、微信未运行）
     */
    List<byte[]> extract(Path accountDir) throws KeyExtractionException;

    /** 该实现是否适用于当前运行环境。 */
    boolean isSupported();

    /** 人类可读的平台名，用于日志与错误提示。 */
    String platformName();

    /**
     * 取密钥失败的原因，带可执行的修复建议。
     *
     * <p>消息必须是<b>可操作</b>的：用户看到「失败」但不知道下一步做什么时，
     * 这个功能就等于不可用。所以每条消息都带具体命令或操作步骤。
     */
    class KeyExtractionException extends RuntimeException {

        /**
         * 失败类别。
         *
         * <p>为什么需要它：提权子进程要靠<b>结构化类别</b>把「微信没启动」与「有权限但取不到」
         * 分开（父进程据此给不同的用户文案）。靠 {@code message} 子串匹配会在文案改动时静默失配。
         */
        public enum Kind {
            /** 未归类的失败。 */
            UNKNOWN,
            /** 没找到运行中的微信进程（属事实，不是权限问题）。 */
            NO_PROCESS,
            /** 权限不足（读进程内存需要提权）。 */
            PERMISSION,
            /** 读到了进程，但扫描预算不足、未覆盖到特征数据。 */
            SCAN_BUDGET_EXHAUSTED,
            /** 当前微信版本没有经过验证的偏移链（或 best-effort 尝试失败）。 */
            VERSION_UNSUPPORTED,
            /** 版本支持范围内的「0 候选」（常见于配置对象尚未初始化）。 */
            NO_CANDIDATES
        }

        private final String remedy;
        private final Kind kind;

        public KeyExtractionException(String message, String remedy) {
            this(message, remedy, Kind.UNKNOWN, null);
        }

        public KeyExtractionException(String message, String remedy, Throwable cause) {
            this(message, remedy, Kind.UNKNOWN, cause);
        }

        public KeyExtractionException(String message, String remedy, Kind kind) {
            this(message, remedy, kind, null);
        }

        public KeyExtractionException(String message, String remedy, Kind kind, Throwable cause) {
            super(message, cause);
            this.remedy = remedy;
            this.kind = kind == null ? Kind.UNKNOWN : kind;
        }

        /** 给用户看的具体修复步骤。 */
        public String remedy() {
            return remedy;
        }

        /** 失败类别（用于分支，不要靠 message 子串判断）。 */
        public Kind kind() {
            return kind;
        }
    }
}
