package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-M-R2-DEL-01] MemoryStorage 对齐 CC memdir/memoryScan 的收窄行为。
 *
 * <p>WHY (规则九 · 测试验证意图):
 * <ul>
 *   <li><b>G-16</b>（08 矩阵 G-16 行）：CC {@code ensureMemoryDirExists} 仅在 loadMemoryPrompt
 *       的 auto/team 分支调用（memdir.ts:459/:479），构造期无 mkdir 副作用 —— 禁用 auto memory
 *       时 Java 不得启动即建目录。旧 MemoryStorage 构造器无条件 createDirectories（△-4）已移除，
 *       本测试锁定「构造不产生目录副作用」。</li>
 *   <li><b>D-05</b>（08 矩阵 DEL-D-05 行）：CC {@code scanMemoryFiles} 恒 mtime 降序
 *       （memoryScan.ts:72-73），无 name 排序模式 —— {@code list} 签名收敛（去 boolean 参数），
 *       本测试锁定恒 mtime desc 行为。</li>
 * </ul>
 */
@DisplayName("[IMP-M-R2-DEL-01] MemoryStorage 构造无 mkdir 副作用 + list 恒 mtime desc")
class MemoryStorageTest {

    @Test
    @DisplayName("构造不创建缺失的记忆目录（G-16 · CC ensureMemoryDirExists 仅 prompt 分支 memdir.ts:459/:479）")
    void constructor_doesNotCreateMissingDirectory(@TempDir Path tempDir) {
        // WHY: 旧实现 bean 构造期无条件 createDirectories（△-4），即使 auto memory 禁用也建目录；
        //      CC 仅在 loadMemoryPrompt auto/team 分支 ensureMemoryDirExists。删除后构造零副作用，
        //      目录由 prompt 构建路径（MemoryPromptBuilder.loadMemoryPrompt）按需确保。
        Path missing = tempDir.resolve("does-not-exist");

        new MemoryStorage(missing);

        assertThat(Files.exists(missing))
            .as("构造 MemoryStorage 不得创建记忆目录（目录确保仅在 prompt 构建分支 · memdir.ts:459/:479）")
            .isFalse();
    }

    @Test
    @DisplayName("list 恒按 mtime 降序（D-05 · CC scanMemoryFiles memoryScan.ts:72-73，无 name 排序分支）")
    void list_alwaysSortedByMtimeDescending(@TempDir Path tempDir) throws Exception {
        // WHY: CC scanMemoryFiles 恒 mtime desc（memoryScan.ts:72-73），旧 list 的
        //      sortByMtimeDesc=false → name 排序分支（MemoryStorage.java:74-76）无 CC 对应
        //      （生产唯一调用方 ExtractMemoriesAgent:456 恒传 true）→ 签名收敛后恒 mtime desc。
        Path memoryDir = Files.createDirectories(tempDir.resolve("mem"));
        // 文件 mtime 显式错序（newest 在前写入，mtime 后被更新）
        Files.writeString(memoryDir.resolve("old.md"), "---\ndescription: old\n---\nbody\n");
        Thread.sleep(10);
        Files.writeString(memoryDir.resolve("new.md"), "---\ndescription: new\n---\nbody\n");

        MemoryStorage storage = new MemoryStorage(memoryDir);
        var entries = storage.list();

        assertThat(entries)
            .as("list 恒 mtime 降序（memoryScan.ts:72-73）")
            .extracting(MemoryEntry::filename)
            .containsExactly("new.md", "old.md");
    }

    @Test
    @DisplayName("list 在目录缺失时返回空列表（CC readdir 失败 catch → [] · memoryScan.ts:74-75）")
    void list_missingDirectoryReturnsEmpty(@TempDir Path tempDir) {
        // WHY: 构造不再建目录（G-16）后，list 必须对缺失目录 fail-soft 返回 []（对齐
        //      scanMemoryFiles 整体 catch → []，memoryScan.ts:74-75），不抛异常。
        MemoryStorage storage = new MemoryStorage(tempDir.resolve("not-here"));

        assertThat(storage.list()).isEmpty();
    }

    @Test
    @DisplayName("[TL-W2 P9] 解析型 storage 按 sessionId/projectRoot 显式现算；未绑定 → null（无参 memoryDir() 已删，零 ThreadLocal 读）")
    void resolverStorage_memoryDir_isSessionScoped_noThreadLocalFallback(@TempDir Path tempDir) throws Exception {
        // WHY（规则九 · 审计 P9）：旧无参 memoryDir() 经 autoMemPaths.getAutoMemPath()（无参）在
        //   **消费线程**读 AutoMemPaths.currentSessionProjectRoot() —— 非会话线程（REST / ForkJoinPool
        //   fork / hook / TaskStop）ThreadLocal 必空 ⇒ 回落 config home ⇒ A′ 判无效返回 null ⇒
        //   下游 new ConsolidationLock(null) / memoryDir().toString() NPE（静默写错目录或 500）。
        //   现契约：解析型必须显式传会话（sessionId 或 projectRoot），未绑定 → null（A′）。
        // RED: 把 memoryDir(sessionId) 改回忽略入参的 autoMemPaths.getAutoMemPath() → 首断言
        //   （绑定会话必须解析出 per-project 目录）变红（测试线程 ThreadLocal 空 → config home → A′ null）。
        Path proj = Files.createDirectories(tempDir.resolve("proj"));
        com.nexusai.common.SessionProjectRoot.setForSession("sess-p9", proj.toString());
        try {
            MemoryStorage storage = new MemoryStorage(AutoMemPaths.defaultInstance());

            Path resolved = storage.memoryDir("sess-p9");
            assertThat(resolved)
                .as("绑定会话 → per-project 记忆目录（getAutoMemPath(explicitRoot)，零 ThreadLocal）")
                .isNotNull();
            assertThat(resolved.toString())
                .as("目录必须锚绑定项目 slug（非 config home 自身 slug）")
                .contains(AutoMemPaths.sanitizePath(proj.toString()));

            assertThat(storage.memoryDir("sess-p9-unbound"))
                .as("未绑定会话 → null（A′：无有效项目，绝不回落 config home）")
                .isNull();
            assertThat(storage.memoryDirForProjectRoot(proj.toString()))
                .as("显式 projectRoot 入口与 sessionId 入口同源（同一解析器）")
                .isEqualTo(resolved);
            assertThat(storage.memoryDirForProjectRoot(null))
                .as("projectRoot null → null（不猜、不回落）")
                .isNull();
        } finally {
            com.nexusai.common.SessionProjectRoot.clearSession("sess-p9");
        }
    }
}
