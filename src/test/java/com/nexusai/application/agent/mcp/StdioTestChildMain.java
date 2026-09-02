package com.nexusai.application.agent.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * [S02 测试基建] stdio 子进程辅助 main（供 StdioMcpTransportStderrTest /
 * StdioMcpTransportCleanupTest 以真实子进程驱动）· 非测试类（surefire 不扫描）。
 *
 * <p>模式：
 * <ul>
 *   <li>{@code flood}：每收到一行 stdin（JSON-RPC）→ 先洪泛 stderr（200k 行 × ~410B ≈ 82MB，
 *       超过 64MB cap 后继续写——证明无管道死锁 + cap 生效）→ 再回写
 *       {@code {"jsonrpc":"2.0","id":N,"result":{"ok":true}}} 到 stdout（先洪泛后响应：
 *       响应到达 ⇔ 洪泛已完成，父进程 drain 时内容确定）</li>
 *   <li>{@code sleep}：忽略 stdin 常驻（close 升级序列目标进程）</li>
 * </ul>
 */
public class StdioTestChildMain {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "sleep";
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if ("sleep".equals(mode)) {
            Thread.sleep(Long.MAX_VALUE);
            return;
        }
        // flood 模式：逐行响应 + 洪泛 stderr
        String line;
        while ((line = in.readLine()) != null) {
            if ("flood".equals(mode)) {
                for (int i = 0; i < 200_000; i++) {
                    System.err.println("flood-line-" + i + "-" + "x".repeat(400));
                }
            }
            long id = extractId(line);
            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"ok\":true}}");
            System.out.flush();
        }
    }

    /** 极简 JSON-RPC id 提取（行内 {@code "id":N}，测试用不需完整 JSON 解析）。 */
    private static long extractId(String line) {
        int idx = line.indexOf("\"id\":");
        if (idx < 0) {
            return 0;
        }
        int start = idx + 5;
        while (start < line.length() && line.charAt(start) == ' ') {
            start++;
        }
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        return end > start ? Long.parseLong(line.substring(start, end)) : 0;
    }
}
