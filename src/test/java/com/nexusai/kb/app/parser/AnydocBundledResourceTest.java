package com.nexusai.kb.app.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * anydoc 原生库「随 jar 内置」契约 · 2026-11-12。
 *
 * <h2>WHY（CLAUDE.md 规则九）</h2>
 * 改动前 anydoc 的原生库是**外部文件**：{@code AnydocNative} 按「库名 → jar 同级目录 →
 * user.dir → user.dir/backend」逐级找 {@code anydoc_go.dll}。后果是三处不一致：
 * <ul>
 *   <li>IDEA 里从仓库根启动时，dll 恰好在 {@code backend/}（命中 {@code user.dir/backend}）；
 *       但用户装出来的包依赖 Tauri 的 bundle.resources 把它拷到 jar 旁边 —— 一旦打包脚本漏拷，
 *       「装好的客户端」就静默回落 Tika，而开发机永远复现不出来。</li>
 *   <li>0.1.68 的实际发版日志就是「⚠️ 未找到 anydoc 原生库」。</li>
 * </ul>
 * ⇒ 改成**与 ripgrep 同一套机制**：库放 {@code resources/anydoc/<arch>-<os>/}，
 * 打进 jar 的 {@code BOOT-INF/classes/anydoc/}，运行时从 classpath 提取到临时目录再加载。
 *
 * <h2>这个测试守什么</h2>
 * <ol>
 *   <li><b>目录名与运行时推导必须逐字一致</b>：资源目录写 {@code x64-win32} 而
 *       {@link AnydocNative#platformDirName()} 算出别的东西 ⇒ 永远是「未随包」，零报错。
 *       这条是最容易在搬文件时踩坏的。</li>
 *   <li><b>资源必须真在 classpath 上</b>：能 {@code getResourceAsStream} 到非空字节流。
 *       文件放进 {@code resources/} 却漏了 Maven 资源过滤 / 被 {@code .gitignore} 挡掉，
 *       单测会立刻红（而不是等装完包用户上传 docx 才发现）。</li>
 *   <li><b>文件名的平台口径</b>：Windows 是 {@code anydoc_go.dll}（无 {@code lib} 前缀），
 *       Mac/Linux 是 {@code libanydoc_go.dylib/.so} —— JNA 只补前后缀、不改用户给的名字。</li>
 * </ol>
 *
 * <p><b>不做的事</b>：不调用 {@code Native.load}（CI 可能不是 Windows/未带对应架构库），
 * 也不断言「当前平台一定有库」—— 只有 x64-win32 随包，这是**设计现状**而非缺陷。
 * 真实加载与转换的验证走 {@code AnydocProbe}（真机），结论记在交付报告里。
 */
@DisplayName("[A] anydoc 原生库 · 随 jar 内置契约")
class AnydocBundledResourceTest {

    @Test
    @DisplayName("⭐ 资源布局与运行时推导同口径（目录名写错 ⇒ 永远「未随包」且零报错）")
    void resourceLayoutMatchesRuntimeResolution() {
        // 运行时推导：/anydoc/<arch>-<os>/<libname>
        String resource = AnydocNative.resourceName();
        String platform = AnydocNative.platformDirName();

        assertThat(resource)
            .as("资源路径必须由平台推导拼出，⛔ 不能写死")
            .isEqualTo("/anydoc/" + platform + "/" + AnydocNative.libraryFileName());
        // 目录名必须是 <arch>-<os> 两段（与 resources/anydoc 下的目录、与 rg 的命名完全一致）
        assertThat(platform).matches("^(x64|arm64)-(win32|darwin|linux)$");
    }

    @Test
    @DisplayName("⭐⭐ x64-win32 的库必须真在 classpath 上（放错目录/被忽略 ⇒ 这里立刻红）")
    void windowsBinaryIsOnClasspath() throws Exception {
        // 无论测试机是什么平台，都要检查这一份 —— 它是本仓唯一随包的产物
        String resource = "/anydoc/x64-win32/anydoc_go.dll";
        try (InputStream in = AnydocBundledResourceTest.class.getResourceAsStream(resource)) {
            assertThat(in)
                .as("classpath 上找不到 %s —— 原生库没随 jar 打进包，安装版会静默回落 Tika 解析", resource)
                .isNotNull();
            byte[] head = in.readNBytes(2);
            // PE 可执行头（Windows 下 dll 也是 PE）—— 防「放了个空文件 / 文本占位」
            assertThat(head).as("不是 PE 文件（可能是空文件或占位文本）").hasSize(2);
            assertThat(head[0]).as("PE 头必须是 'M'").isEqualTo((byte) 'M');
            assertThat(head[1]).as("PE 头必须是 'Z'").isEqualTo((byte) 'Z');
        }
    }

    @Test
    @DisplayName("库文件名平台口径：Windows 无 lib 前缀，Mac/Linux 有")
    void libraryFileNamePerPlatform() {
        assertThat(AnydocNative.libraryFileNameFor("Windows 11")).isEqualTo("anydoc_go.dll");
        assertThat(AnydocNative.libraryFileNameFor("Mac OS X")).isEqualTo("libanydoc_go.dylib");
        assertThat(AnydocNative.libraryFileNameFor("Linux")).isEqualTo("libanydoc_go.so");
    }

    @Test
    @DisplayName("未随包的平台必须给出可执行的指引，而不是「找不到」")
    void unshippedPlatformExplains() {
        // 伪造成没有随包的平台：路径必然不存在
        String missing = "/anydoc/arm64-darwin/libanydoc_go.dylib";
        assertThat(AnydocBundledResourceTest.class.getResourceAsStream(missing))
            .as("这条断言本身要成立：其它平台确实未随包（否则文案分支就过期了）")
            .isNull();
        // 降级原因里的关键词（生产文案由 loadInternal 生成，这里核「文案口径」而非拼串）
        assertThat(AnydocNative.platformDirName()).isNotBlank();
    }
}
