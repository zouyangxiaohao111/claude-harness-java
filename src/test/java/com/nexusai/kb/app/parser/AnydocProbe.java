package com.nexusai.kb.app.parser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 探针：验证「内置 anydoc 原生库」这条链路是否真的能用。
 *
 * <p><b>为什么是 {@code main} 而不是 JUnit 用例</b>：本类要做的事（{@code Native.load} +
 * 真解析）依赖<b>当前机器与当前平台的二进制</b>；CI 上跑 Linux 容器没有 x64-win32 的库，
 * 断言「必须可用」会恒红，断言「必须不可用」又会在真正的 Windows 打包机上恒红。
 * ⇒ 做成手动探针，由人决定在哪台机器上跑、看输出判断（与 {@code TestRunner} 同类）。
 *
 * <p>判据四条：
 * <ol>
 *   <li>{@code resourceName()} 推导正确（{@code /anydoc/x64-win32/anydoc_go.dll}）</li>
 *   <li>从 classpath 提取到 {@code %TEMP%\nexusai-anydoc\0.2.4\<arch>-<os>\}</li>
 *   <li>{@code isAvailable()=true} —— 且此时 {@code user.dir} 故意指向没有 dll 的目录，
 *       排除「靠外部文件兜底成功」的假绿</li>
 *   <li>真能解析 docx（表格要在）</li>
 * </ol>
 *
 * <p>用法（见 {@code front/scripts/build-anydoc.md}）：
 * <pre>
 * java -Dfile.encoding=UTF-8 -Duser.dir=D:/tmp/probe-cwd \
 *      -cp "&lt;target/classes&gt;;&lt;deps&gt;" com.nexusai.kb.app.parser.AnydocProbe &lt;docx&gt;
 * </pre>
 */
public final class AnydocProbe {

    public static void main(String[] args) throws Exception {
        System.out.println("[1] platformDirName = " + AnydocNative.platformDirName());
        System.out.println("[1] resourceName    = " + AnydocNative.resourceName());
        System.out.println("[1] user.dir        = " + System.getProperty("user.dir")
            + "（故意指向无 dll 的目录，排除外部兜底假绿）");

        boolean ok = AnydocNative.isAvailable();
        System.out.println("[2] isAvailable = " + ok);
        if (!ok) {
            System.out.println("[2] reason = " + AnydocNative.unavailableReason());
            return;
        }

        Path cache = Path.of(System.getProperty("java.io.tmpdir", "/tmp"))
            .resolve("nexusai-anydoc");
        System.out.println("[3] 提取缓存目录 = " + cache + " 存在=" + Files.isDirectory(cache));
        if (Files.isDirectory(cache)) {
            try (Stream<Path> s = Files.walk(cache)) {
                s.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(p -> {
                        try {
                            System.out.println("      " + p + "  size=" + Files.size(p));
                        } catch (Exception e) {
                            System.out.println("      " + p + "  size=<读取失败>");
                        }
                    });
            }
        }

        if (args.length == 0) {
            System.out.println("[4] 未给 docx 参数，跳过转换");
            return;
        }
        Path doc = Path.of(args[0]);
        String md = AnydocNative.toMarkdown(doc);
        System.out.println("[4] 转换成功：文件=" + doc.getFileName() + " markdown字符数=" + md.length());
        String preview = md.length() > 300 ? md.substring(0, 300) : md;
        System.out.println("[4] 预览:\n" + preview.replace("\r", ""));
        System.out.println("[4] 含表格 = " + md.contains("|"));
    }
}
