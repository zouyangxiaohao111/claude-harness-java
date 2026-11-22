package com.nexusai.wechatlog.key.elevation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 提权取密钥子进程的协议：启动参数哨兵、退出码、候选文件的读写。
 *
 * <h2>为什么需要一层协议</h2>
 * 非管理员主进程无法读微信进程内存，只能通过 {@code ShellExecute(runas)} 起一个临时提权的
 * 子进程去读。子进程只做「取密钥」这一件事，把结果写进一个仅当前用户可读的临时文件。
 * 本类把父子之间约定的东西集中在一处，避免散落的字符串/数字各写一份。
 *
 * <h2>退出码</h2>
 * <table>
 *   <tr><th>码</th><th>含义</th></tr>
 *   <tr><td>{@value #EXIT_OK}</td><td>成功，候选已写入输出文件</td></tr>
 *   <tr><td>{@value #EXIT_USAGE}</td><td>参数不合法</td></tr>
 *   <tr><td>{@value #EXIT_NO_PROCESS}</td><td>没找到运行中的微信进程</td></tr>
 *   <tr><td>{@value #EXIT_NO_CANDIDATES}</td><td>读到了进程但 0 候选（版本可能不支持）</td></tr>
 *   <tr><td>{@value #EXIT_EXTRACT_FAILED}</td><td>其它提取失败</td></tr>
 * </table>
 *
 * <p>「用户在 UAC 里点了取消」<b>没有</b>退出码 —— 那种情况下 {@code ShellExecuteEx}
 * 直接失败并给 {@code ERROR_CANCELLED}，子进程根本没起来。父进程侧单独判。
 *
 * <h2>安全边界</h2>
 * 候选文件里是数据库密钥：本类只做「写文件 / 读文件」，<b>绝不打印内容</b>。
 */
public final class ElevatedKeyExtractProtocol {

    /** 子进程启动哨兵：第一个参数等于它时，{@code NexusAiApplication.main} 直接走取密钥分支。 */
    public static final String SENTINEL = "--wechat-key-extract";

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_NO_PROCESS = 10;
    public static final int EXIT_NO_CANDIDATES = 11;
    public static final int EXIT_EXTRACT_FAILED = 12;
    public static final int EXIT_SCAN_BUDGET_EXHAUSTED = 13;
    public static final int EXIT_VERSION_UNSUPPORTED = 14;

    private ElevatedKeyExtractProtocol() {
    }

    /** 本次启动是否是提权取密钥子进程。 */
    public static boolean isInvocation(String[] args) {
        return args != null && args.length > 0 && SENTINEL.equals(args[0]);
    }

    /** 去掉哨兵后的有效参数。 */
    public static String[] payload(String[] args) {
        if (args == null || args.length <= 1) {
            return new String[0];
        }
        String[] out = new String[args.length - 1];
        System.arraycopy(args, 1, out, 0, out.length);
        return out;
    }

    /**
     * 写候选（每行一个 64 字符十六进制）。
     *
     * <p>输入校验在此收口：长度不是 32 字节的候选直接跳过，避免把脏数据写出去。
     */
    public static void writeCandidates(Path output, List<byte[]> candidates) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (candidates != null) {
            for (byte[] c : candidates) {
                if (c == null || c.length != 32) {
                    continue;
                }
                for (byte b : c) {
                    sb.append(String.format("%02x", b));
                }
                sb.append('\n');
            }
        }
        Files.writeString(output, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 失败原因旁车文件路径（与候选同目录）。 */
    public static Path reasonFileFor(Path output) {
        return output.resolveSibling(output.getFileName() + ".reason");
    }

    /** 子进程写失败原因（不含密钥；best-effort，写失败不影响退出码）。 */
    public static void writeReason(Path output, String reason) {
        try {
            String r = reason == null ? "" : reason;
            if (r.length() > 500) {
                r = r.substring(0, 500);
            }
            Files.writeString(reasonFileFor(output), r, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 原因只是给用户文案加细节，写不进去也不影响主流程
        }
    }

    /** 父进程读失败原因；不存在/读失败返回 null。 */
    public static String readReason(Path output) {
        try {
            Path f = reasonFileFor(output);
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读候选；文件不存在或格式不合法时返回空列表（不抛受检异常给调用方兜底）。 */
    public static List<byte[]> readCandidates(Path input) throws IOException {
        List<byte[]> out = new ArrayList<>();
        if (input == null || !Files.isRegularFile(input)) {
            return out;
        }
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            String h = line.trim().toLowerCase(java.util.Locale.ROOT);
            if (h.length() != 64 || !h.matches("[0-9a-f]+")) {
                continue;
            }
            byte[] b = new byte[32];
            for (int i = 0; i < 32; i++) {
                b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
            out.add(b);
        }
        return out;
    }
}
