package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S08 · {@link PermissionPaths} 路径展开单元测试（CC {@code fsOperations.ts:288-382
 * getPathsForPermissionCheck} 等价物）。
 *
 * <p><b>对齐锚点（CC 真源，行号当次 read 自验）</b>：
 * <ol>
 *   <li>tilde 防御性展开（fsOperations.ts:289-296）；</li>
 *   <li>UNC 在任何文件系统访问前返回（:304-308）；</li>
 *   <li>不存在路径 → resolveDeepestExistingAncestorSync 补真实落点（:325-339）；</li>
 *   <li>收尾 safeResolvePath 追加 realpath 形态（:374-379）。</li>
 * </ol>
 *
 * <p><b>环境说明</b>：本机无 symlink 创建特权（mklink 探测失败）——真实 symlink 链
 * 测试见 {@code SymlinkPermissionTest}（assumption 守卫）；本类用 Windows junction
 * （mklink /J，无需特权）覆盖"活父 symlink"的解析分支（junction 被 Java 视作普通目录，
 * 由 realpath 差异分支检出——与 CC 的 readlink 链分支殊途同归，落点集合一致）。
 */
@DisplayName("S08 · PermissionPaths 路径展开（CC fsOperations.ts:288-382）")
class PermissionPathsTest {
    private static String home() {
        // 与实现一致：不做分隔符转换（实现保留 user.home 原始形态，CC homedir() 同）
        return System.getProperty("user.home", "");
    }

    @Test
    @DisplayName("'~' → user.home（CC :293）")
    void tildeOnly_expandsToHome() {
        List<String> paths = PermissionPaths.getPathsForPermissionCheck("~");
        assertThat(paths).as("首元素必须是 home").first().isEqualTo(home());
    }

    @Test
    @DisplayName("'~/...' → user.home + 子路径（CC :294-295）")
    void tildeSlash_expandsUnderHome() {
        List<String> paths = PermissionPaths.getPathsForPermissionCheck("~/s08-absent-foo/bar.txt");
        assertThat(paths).contains(home() + "/s08-absent-foo/bar.txt");
    }

    @Test
    @DisplayName("UNC 路径提前返回，不触碰文件系统（CC :304-308）")
    void uncPath_returnsSinglePath() {
        assertThat(PermissionPaths.getPathsForPermissionCheck("\\\\server\\share\\file.txt"))
            .containsExactly("\\\\server\\share\\file.txt");
        assertThat(PermissionPaths.getPathsForPermissionCheck("//server/share/file.txt"))
            .containsExactly("//server/share/file.txt");
    }

    @Test
    @DisplayName("不存在路径且无 symlink 祖先 → 仅原始路径（CC :325-339 无解析结果）")
    void nonExistentPath_noSymlink_singlePath() {
        String p = Paths.get("s08-no-such-dir", "no-such-file.txt").toAbsolutePath().toString();
        List<String> paths = PermissionPaths.getPathsForPermissionCheck(p);
        assertThat(paths).containsExactly(p);
    }

    @Test
    @DisplayName("存在目录（@TempDir）→ 原始路径在集合内，无重复（CC :301-302 恒含原始）")
    void existingDir_containsOriginal(@TempDir Path workspace) {
        List<String> paths = PermissionPaths.getPathsForPermissionCheck(workspace.toString());
        assertThat(paths).contains(workspace.toString());
        assertThat(paths).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("resolveDeepestExistingAncestor：绝对路径无 symlink 祖先 → null（CC :269）")
    void deepestAncestor_noSymlink_returnsNull() {
        assertThat(PermissionPaths.resolveDeepestExistingAncestor(
            Paths.get("C:", "s08-no-such-dir", "a", "b.txt").toString()))
            .as("不存在的盘级绝对路径：最深存在祖先=盘根且解析到自身 → null")
            .isNull();
        assertThat(PermissionPaths.resolveDeepestExistingAncestor(
            Paths.get("target", "s08-no-such").resolve("child.txt").toAbsolutePath().toString()))
            .as("存在的 target/ 解析到自身 → null")
            .isNull();
    }

    @Test
    @DisplayName("Windows junction（活父 symlink 等价物）：路径经 junction 展开含真实落点（CC :325-339）")
    void junctionParentSymlink_resolvedTargetAdded(@TempDir Path workspace) throws Exception {
        assumeTrue(File.separatorChar == '\\', "junction 测试仅 Windows");
        Path realTarget = workspace.resolve("real-target");
        Files.createDirectories(realTarget);
        Path junction = workspace.resolve("link");
        assumeTrue(createJunction(junction, realTarget),
            "mklink /J 失败（环境不支持 junction）→ 跳过");
        // 1) 最深祖先解析：junction 目录 + 不存在尾段 → 真实落点 + 尾段
        Path through = junction.resolve("sub").resolve("file.txt");
        // 规范形（Windows 8.3 短名会被 realpath 展开）——lambda 外计算避免受检异常
        Path canonicalTarget = realTarget.toRealPath().resolve("sub").resolve("file.txt");
        String resolved = PermissionPaths.resolveDeepestExistingAncestor(through.toString());
        assertThat(resolved).isNotNull();
        assertThat(Paths.get(resolved)).isEqualTo(canonicalTarget);

        // 2) 全路径展开：原始 + 真实落点都在集合内
        List<String> paths = PermissionPaths.getPathsForPermissionCheck(through.toString());
        assertThat(paths).contains(through.toString());
        assertThat(paths).anyMatch(p -> Paths.get(p).equals(canonicalTarget));
    }

    /** mklink /J 创建 junction（Windows 无特权场景可用；失败返回 false）。 */
    private static boolean createJunction(Path link, Path target) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
            int code = p.waitFor();
            return code == 0 && Files.exists(link);
        } catch (Exception e) {
            return false;
        }
    }
}
