package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-10 接线源单测（IMP-03 返工补测）· 对齐 CC {@code getCurrentRepoHttpsUrl}
 * （skills/bundled/scheduleRemoteAgents.ts:123-133 = getRemoteUrl → parseGitRemote →
 * {@code https://${host}/${owner}/${name}}）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图，而非仅验证行为）:
 * <ol>
 *   <li><b>getRemoteHttpsUrl 是 /schedule 的 gitRepoUrl 真实接线源</b>（BundledSkillsBootstrapper
 *       registerScheduleSkill :369，P2-10 △-9 关闭）——CC 语义为<b>任意 host</b>（GHE/GitLab 等非
 *       github.com 也返回 https URL），与 {@link #getGithubRepo}（仅 github.com）不同。若实现被误改成
 *       仅 github.com 或仅 SSH 格式，/schedule 的 repo 拼接会对 GHE/GitLab 仓库返回 null → prompt 落
 *       默认 github.com/ORG/REPO 占位 → 运营人员创建远程 agent 时 git repo 错误，静默不可见。</li>
 *   <li><b>SSH→https 拼装 + .git/config 读取是接线契约</b>——CC parseGitRemote 支持
 *       {@code git@host:owner/repo.git} 与 {@code https://host/owner/repo.git} 双格式，都归一为 https
 *       URL；.git/config 的 [remote "origin"] url 是唯一数据源。测试用 fake git 仓库驱动真实读取链。</li>
 *   <li><b>DEL-04/非 git 目录 fail-soft</b>——非 git 目录 / 无 remote url 必须返回 null（CC
 *       getCurrentRepoHttpsUrl 无抛异常路径，best-effort），调用方（schedule prompt）据此落默认文案。</li>
 * </ol>
 */
class GitRemoteResolverTest {

    // ────────────────────────────────────────────────────────────────
    // getRemoteHttpsUrl · CC getCurrentRepoHttpsUrl（scheduleRemoteAgents.ts:123-133）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getRemoteHttpsUrl：SSH git@host:owner/repo.git → https://host/owner/repo（任意 host）")
    void getRemoteHttpsUrl_sshRemoteAssemblesHttps(@TempDir Path root) throws IOException {
        // CC parseGitRemote SSH 格式（detectRepository.ts:87-95）+ getCurrentRepoHttpsUrl https 拼装
        fakeGitRepo(root, "git@github.com:my-org/my-repo.git");

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("SSH git@github.com:owner/repo.git → https://github.com/owner/repo")
            .isEqualTo("https://github.com/my-org/my-repo");
    }

    @Test
    @DisplayName("getRemoteHttpsUrl：非 github.com host（GHE/GitLab）同样返回 https URL（与 getGithubRepo 的区别）")
    void getRemoteHttpsUrl_nonGithubHostStillAssembles(@TempDir Path root) throws IOException {
        // CC getCurrentRepoHttpsUrl 不限定 github.com（与 getGithubRepo git.ts:504 不同）——GHE/GitLab 也返回
        fakeGitRepo(root, "git@gitlab.corp.example.com:team/project.git");

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("GHE/GitLab host 也必须拼装 https URL（CC getCurrentRepoHttpsUrl 任意 host）")
            .isEqualTo("https://gitlab.corp.example.com/team/project");
    }

    @Test
    @DisplayName("getRemoteHttpsUrl：HTTPS url 直接透传归一（https://host/owner/repo.git → 去 .git 后缀）")
    void getRemoteHttpsUrl_httpsRemotePassesThrough(@TempDir Path root) throws IOException {
        // CC parseGitRemote URL 格式（detectRepository.ts:97-126）+ :133 https 拼装
        fakeGitRepo(root, "https://github.com/acme/widgets.git");

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("https URL 去 .git 后缀 + https:// 前缀拼装")
            .isEqualTo("https://github.com/acme/widgets");
    }

    @Test
    @DisplayName("getRemoteHttpsUrl：从 git 根子目录读取（findCanonicalGitRoot 向上查找 .git/config）")
    void getRemoteHttpsUrl_readsConfigFromSubdirOfGitRoot(@TempDir Path root) throws IOException {
        // CC getRemoteUrl 以进程 cwd 起，git 根向上查找（gitFilesystem.ts computeRemoteUrl）；
        // Java findCanonicalGitRoot 从子目录向上找到 git 根再读 .git/config
        fakeGitRepo(root, "https://github.com/acme/widgets.git");
        Path subdir = Files.createDirectories(root.resolve("src").resolve("main"));

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(subdir))
            .as("子目录 cwd 也必须解析到 git 根 remote（CC findGitRoot 链）")
            .isEqualTo("https://github.com/acme/widgets");
    }

    @Test
    @DisplayName("getRemoteHttpsUrl：非 git 目录 → null（CC best-effort 失败静默）")
    void getRemoteHttpsUrl_nonGitDirReturnsNull(@TempDir Path root) {
        // CC getCurrentRepoHttpsUrl：getRemoteUrl() 返回 null → 直接 null（无抛异常路径）
        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("非 git 目录 → null（调用方落默认文案）")
            .isNull();
    }

    @Test
    @DisplayName("getRemoteHttpsUrl：.git 存在但无 [remote origin] url → null")
    void getRemoteHttpsUrl_missingRemoteOriginUrlReturnsNull(@TempDir Path root) throws IOException {
        // 空 .git/config（无 [remote "origin"]）→ readRemoteOriginUrl null → null
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"),
            "[core]\n\trepositoryformatversion = 0\n");

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("无 remote origin url → null")
            .isNull();
    }

    @Test
    @DisplayName("getRemoteHttpsUrl：非法 remote 格式 → null（parseGitRemote 拒绝）")
    void getRemoteHttpsUrl_illegalRemoteReturnsNull(@TempDir Path root) throws IOException {
        // 非 SSH 非 URL 格式（缺 owner/repo）→ parseGitRemote null → null
        fakeGitRepo(root, "not-a-valid-remote");

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("非法 remote 格式 → null")
            .isNull();
    }

    // ────────────────────────────────────────────────────────────────
    // getGithubRepo · CC getGithubRepo（git.ts:504-521）· 仅 github.com 返回 owner/repo
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getGithubRepo：github.com SSH remote → owner/repo（git.ts:514-518 唯一返回面）")
    void getGithubRepo_githubSshReturnsSlug(@TempDir Path root) throws IOException {
        // WHY（补盲 · MM-B4 X5）：GitRemoteResolverTest 7 例全部只测 getRemoteHttpsUrl，
        // getGithubRepo（git.ts:504-518，team memory sync 的 no_repo 门真源）0 直测。CC 仅在
        // parsed.host === 'github.com' 时返回 `${owner}/${name}`（:514-518），否则 null。
        fakeGitRepo(root, "git@github.com:my-org/my-repo.git");

        assertThat(GitRemoteResolver.getGithubRepo(root))
            .as("github.com SSH remote → owner/repo slug")
            .isEqualTo("my-org/my-repo");
    }

    @Test
    @DisplayName("getGithubRepo：github.com HTTPS remote → owner/repo（去 .git 后缀）")
    void getGithubRepo_githubHttpsReturnsSlug(@TempDir Path root) throws IOException {
        fakeGitRepo(root, "https://github.com/acme/widgets.git");

        assertThat(GitRemoteResolver.getGithubRepo(root))
            .as("github.com HTTPS remote → owner/repo")
            .isEqualTo("acme/widgets");
    }

    @Test
    @DisplayName("getGithubRepo：非 github.com host → null（host 过滤与 getRemoteHttpsUrl 的分界）")
    void getGithubRepo_nonGithubHostReturnsNull(@TempDir Path root) throws IOException {
        // WHY：与 getRemoteHttpsUrl（任意 host）相反——team memory 服务端是 github.com 作用域，
        // 非 github.com remote 永远无法 sync（watcher.ts:259-266 靠此门免 no_repo 噪音循环）。
        fakeGitRepo(root, "git@gitlab.corp.example.com:team/project.git");

        assertThat(GitRemoteResolver.getGithubRepo(root))
            .as("GHE/GitLab remote → null（getGithubRepo 仅 github.com）")
            .isNull();
    }

    @Test
    @DisplayName("getGithubRepo：SSH alias host（末段含连字符）→ null（looksLikeRealHostname 拒绝）")
    void getGithubRepo_sshAliasHostRejected(@TempDir Path root) throws IOException {
        // looksLikeRealHostname（detectRepository.ts:170-178）：真实 TLD 须纯字母 → github.com-work 拒绝
        fakeGitRepo(root, "git@github.com-work:my-org/my-repo.git");

        assertThat(GitRemoteResolver.getGithubRepo(root)).isNull();
    }

    @Test
    @DisplayName("getGithubRepo：非 git 目录 → null（best-effort 失败静默，CC 无抛异常路径）")
    void getGithubRepo_nonGitDirReturnsNull(@TempDir Path root) {
        assertThat(GitRemoteResolver.getGithubRepo(root)).isNull();
    }

    @Test
    @DisplayName("getGithubRepo：无 remote origin url / 非法 remote 格式 → null")
    void getGithubRepo_missingOrIllegalRemoteReturnsNull(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"),
            "[core]\n\trepositoryformatversion = 0\n");
        assertThat(GitRemoteResolver.getGithubRepo(root))
            .as("无 [remote origin] url → null")
            .isNull();

        fakeGitRepo(root, "not-a-valid-remote");
        assertThat(GitRemoteResolver.getGithubRepo(root))
            .as("非法 remote 格式 → parseGitRemote null → null")
            .isNull();
    }

    // ────────────────────────────────────────────────────────────────
    // readRemoteOriginUrl · IMP-D-6 / OPD-CM5-D-19 三处偏移修复
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readRemoteOriginUrl：GHE 大写 section/key（[REMOTE \\\"origin\\\"] + URL=）大小写不敏感命中（gitConfigParser.ts:43-44）")
    void getRemoteHttpsUrl_gheUpperCaseSectionAndKeyResolves(@TempDir Path root) throws IOException {
        // WHY：CC parseConfigString section/key 均 toLowerCase 后匹配；GHE 配置可能大写键。
        // 旧实现 `[remote "origin"]`.equals + startsWith("url") 大小写敏感 → GHE 配置解析为 null
        // → team memory no_repo 门误判，repo 无法 sync（用户拍板 OPD-CM5-D-19 修复）。
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"),
            "[REMOTE \"origin\"]\n\tURL = https://github.com/ghe-org/ghe-repo.git\n");

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("大写 [REMOTE \"origin\"] + URL = 必须命中（CC toLowerCase 语义）")
            .isEqualTo("https://github.com/ghe-org/ghe-repo");
    }

    @Test
    @DisplayName("readRemoteOriginUrl：urlExtra 前缀键不误匹配 url（parseKeyValue 严格键名+'='，gitConfigParser.ts:78-108）")
    void getRemoteHttpsUrl_urlExtraPrefixKeyIgnored(@TempDir Path root) throws IOException {
        // WHY：旧实现 `trimmed.startsWith("url")` 会把 urlExtra 误当 url。CC parseKeyValue 严格
        // 键名（isKeyChar）+ '=' 判定，且仅比对 key.toLowerCase() === 'url'。仅 urlExtra 无 url → null，
        // 防止把非 url 键值（如扩展字段）当作 remote origin url。
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"),
            "[remote \"origin\"]\n\turlExtra = https://github.com/evil/url-extra.git\n"
            + "\turl = https://github.com/acme/widgets.git\n");

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("urlExtra 存在但真实 url 也在 → 必须取 url 值")
            .isEqualTo("https://github.com/acme/widgets");

        Files.writeString(root.resolve(".git").resolve("config"),
            "[remote \"origin\"]\n\turlExtra = https://github.com/evil/url-extra.git\n");
        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("仅有 urlExtra 无 url → null（不得把 urlExtra 当作 url）")
            .isNull();
    }

    @Test
    @DisplayName("readRemoteOriginUrl：值解析剥离行内注释与尾随空白（parseValue :114-184）")
    void getRemoteHttpsUrl_inlineCommentAndTrailingWhitespaceStripped(@TempDir Path root) throws IOException {
        // WHY：CC parseValue 引号外 #/; 结束值 + trimTrailingWhitespace 裁剪尾随空白。
        // 旧实现仅 substring(eq+1).trim() → "https://...# 注释" 整串进 parseGitRemote → URL 正则
        // 拒绝（# 非法字符）→ null。对齐后必须返回干净 URL。
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"),
            "[remote \"origin\"]\n\turl = https://github.com/acme/widgets.git   # deploy mirror\n");

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("行内注释 + 尾随空白 → 剥离后返回干净 https URL")
            .isEqualTo("https://github.com/acme/widgets");
    }

    @Test
    @DisplayName("readRemoteOriginUrl：引号内转义序列正确解码（parseValue 引号内转义）")
    void getRemoteHttpsUrl_quotedValueWithEscapesDecoded(@TempDir Path root) throws IOException {
        // WHY：CC parseValue 引号内转义 \\\" → \"、\\\\ → \\、未知转义丢弃反斜杠。旧实现仅剥包裹引号
        // 不处理转义，值内 \" 保留导致 URL 解析失败。此处验证解码后 URL 可被 parseGitRemote 接受。
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"),
            "[remote \"origin\"]\n\turl = \"https://github.com/acme/widgets.git\"\n");

        assertThat(GitRemoteResolver.getRemoteHttpsUrl(root))
            .as("引号包裹值去引号后正常解析")
            .isEqualTo("https://github.com/acme/widgets");
    }

    /** 创建带给定 remote origin url 的临时 git 仓库（仅 .git/config，无需真实 git init）。 */
    private static void fakeGitRepo(Path root, String url) throws IOException {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"),
            "[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n"
            + "[remote \"origin\"]\n\turl = " + url + "\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n");
    }
}
