package com.nexusai.wechatlog.key.elevation;

import com.nexusai.wechatlog.key.WeChatKeyExtractor;
import com.nexusai.wechatlog.key.WindowsKeyExtractor;

import java.nio.file.Path;
import java.util.List;

/**
 * 提权取密钥子进程的入口。
 *
 * <h2>它只做一件事</h2>
 * 读微信进程内存里的密钥候选，写进父进程指定的临时文件，然后退出。
 * <b>不启动 Spring、不解密数据库、不写任何缓存</b>（这些留在普通权限的主进程里）。
 *
 * <h2>为什么由 {@code NexusAiApplication.main} 转进来</h2>
 * 生产包是 Spring Boot 可执行 jar（{@code java -jar nexusai-backend.jar}），
 * fat jar 里的类在 {@code BOOT-INF/classes} 下，无法用 {@code -cp <jar> <other-main>}
 * 启动。因此统一让 {@code -jar} 进入 {@code NexusAiApplication.main}，由它识别哨兵参数后
 * 转给本类 —— 这样 dev（classpath 启动）与生产（fat jar）走同一条命令形态。
 *
 * <h2>安全约束</h2>
 * 全程不打印密钥候选；只通过退出码与输出文件交接。
 */
public final class WeChatKeyExtractMain {

    private WeChatKeyExtractMain() {
    }

    /**
     * 执行一次提取。
     *
     * @param payload {@code [accountDir, outputFile]}
     * @return 进程退出码，见 {@link ElevatedKeyExtractProtocol}
     */
    public static int run(String[] payload) {
        if (payload == null || payload.length < 2) {
            return ElevatedKeyExtractProtocol.EXIT_USAGE;
        }
        Path accountDir = Path.of(payload[0]);
        Path output = Path.of(payload[1]);
        try {
            WeChatKeyExtractor extractor = new WindowsKeyExtractor();
            List<byte[]> candidates = extractor.extract(accountDir);
            if (candidates == null || candidates.isEmpty()) {
                return ElevatedKeyExtractProtocol.EXIT_NO_CANDIDATES;
            }
            ElevatedKeyExtractProtocol.writeCandidates(output, candidates);
            return ElevatedKeyExtractProtocol.EXIT_OK;
        } catch (WeChatKeyExtractor.KeyExtractionException e) {
            // 失败原因写旁车文件（不含密钥），让父进程能给出「版本/预算」这类具体文案。
            ElevatedKeyExtractProtocol.writeReason(output, e.getMessage());
            return switch (e.kind()) {
                case NO_PROCESS -> ElevatedKeyExtractProtocol.EXIT_NO_PROCESS;
                case NO_CANDIDATES -> ElevatedKeyExtractProtocol.EXIT_NO_CANDIDATES;
                case SCAN_BUDGET_EXHAUSTED -> ElevatedKeyExtractProtocol.EXIT_SCAN_BUDGET_EXHAUSTED;
                case VERSION_UNSUPPORTED -> ElevatedKeyExtractProtocol.EXIT_VERSION_UNSUPPORTED;
                default -> ElevatedKeyExtractProtocol.EXIT_EXTRACT_FAILED;
            };
        } catch (Throwable t) {
            ElevatedKeyExtractProtocol.writeReason(output, "提权取密钥异常: " + t.getClass().getSimpleName());
            return ElevatedKeyExtractProtocol.EXIT_EXTRACT_FAILED;
        }
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }
}
