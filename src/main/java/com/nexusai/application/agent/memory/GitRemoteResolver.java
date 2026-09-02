package com.nexusai.application.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub remote 解析 · 对齐 CC {@code Open-ClaudeCode/src/utils/git.ts:504 getGithubRepo}
 * + {@code detectRepository.ts:87-128 parseGitRemote}。
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：{@code getGithubRepo} git.ts:504-518 —— 读 remote url →
 * {@code parseGitRemote} 解析 → 仅 host 为 github.com 时返回 {@code owner/repo}；
 * {@code parseGitRemote} detectRepository.ts:87-128（SSH {@code git@host:owner/repo.git} +
 * URL {@code https://host/owner/repo.git} 等四种格式）；{@code looksLikeRealHostname}
 * detectRepository.ts:170-178（真实 TLD 必须纯字母）。
 *
 * <p><b>为什么新文件</b>：CC 无 git 进程调用——{@code getRemoteUrl}（git.ts:269-271）→
 * {@code computeRemoteUrl}（gitFilesystem.ts:527-542）→ {@code parseGitConfigValue}
 * （gitConfigParser.ts:18-30）直接 {@code readFile .git/config} 解析 {@code [remote "origin"] url}
 * 键；Java 同步直接读 {@code .git/config}（规则五：确定性数据转换用代码）。git 根解析复用
 * {@link AutoMemPaths#findCanonicalGitRoot}
 * （worktree 自动解析到主仓库，与 CC findGitRoot 链一致）。
 *
 * <p>仅返回 github.com 仓库 —— team memory 在服务端是 GitHub 作用域，非 github.com remote
 * 永远无法 sync（watcher 用此门避免 no_repo 噪音循环，watcher.ts:259-266）。
 */
public final class GitRemoteResolver {

    private static final Logger log = LoggerFactory.getLogger(GitRemoteResolver.class);

    /** SSH 格式：git@host:owner/repo.git（detectRepository.ts:87-95）。 */
    private static final Pattern SSH_REMOTE = Pattern.compile("^git@([^:]+):([^/]+)/([^/]+?)(?:\\.git)?$");
    /** URL 格式：https://host/owner/repo.git、ssh://git@host/owner/repo、git://host/owner/repo（:97-126）。 */
    private static final Pattern URL_REMOTE = Pattern.compile(
        "^(https?|ssh|git)://(?:[^@]+@)?([^/:]+(?::\\d+)?)/([^/]+)/([^/]+?)(?:\\.git)?$");

    private GitRemoteResolver() {}

    /** 解析结果：host / owner / name（detectRepository.ts:5-8 ParsedRepository）。 */
    record ParsedRemote(String host, String owner, String name) {}

    /**
     * 返回 GitHub 仓库 slug（{@code owner/repo}）或 null · CC original: {@code getGithubRepo}
     * （git.ts:504-518）。仅当 remote origin 是 github.com 时返回；否则 null。
     *
     * @param cwd 工作目录（git 根解析起点）
     */
    public static String getGithubRepo(Path cwd) {
        try {
            String gitRoot = AutoMemPaths.findCanonicalGitRoot(cwd.toString());
            if (gitRoot == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[GitRemoteResolver] 非 git 目录，返回 null: {}", cwd);
                }
                return null;
            }
            Path config = Paths.get(gitRoot, ".git", "config");
            if (!Files.isRegularFile(config)) {
                return null;
            }
            String remoteUrl = readRemoteOriginUrl(config);
            if (remoteUrl == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[GitRemoteResolver] 无 remote origin url（.git/config），返回 null");
                }
                return null;
            }
            ParsedRemote parsed = parseGitRemote(remoteUrl);
            if (parsed != null && "github.com".equals(parsed.host)) {
                String slug = parsed.owner() + "/" + parsed.name();
                if (log.isDebugEnabled()) {
                    log.debug("[GitRemoteResolver] 解析 GitHub repo: {}", slug);
                }
                return slug;
            }
            if (log.isDebugEnabled()) {
                log.debug("[GitRemoteResolver] 非 github.com remote，返回 null: {}", remoteUrl);
            }
            return null;
        } catch (Exception e) {
            // git 解析是 best-effort（CC getGithubRepo 无抛异常路径，失败静默返回 null）
            if (log.isDebugEnabled()) {
                log.debug("[GitRemoteResolver] 读取 remote 失败，返回 null: {}", e.toString());
            }
            return null;
        }
    }

    /**
     * 返回当前 git 仓库的 HTTPS 远程 URL（{@code https://host/owner/name}）或 null ·
     * CC original: {@code getCurrentRepoHttpsUrl}（scheduleRemoteAgents.ts:123-133：
     * getRemoteUrl → parseGitRemote → {@code https://${parsed.host}/${parsed.owner}/${parsed.name}}）。
     *
     * <p>与 {@link #getGithubRepo} 不同：不限定 github.com，接受任意 host（GHE/GitLab 等），
     * 供 /schedule 技能的 gitRepoUrl 拼接（P2-10 接线）。git 解析为 best-effort，失败静默返回 null。
     *
     * @param cwd 工作目录（git 根解析起点）
     * @return https 形式的远程 URL 或 null
     */
    public static String getRemoteHttpsUrl(Path cwd) {
        try {
            String gitRoot = AutoMemPaths.findCanonicalGitRoot(cwd.toString());
            if (gitRoot == null) {
                return null;
            }
            Path config = Paths.get(gitRoot, ".git", "config");
            if (!Files.isRegularFile(config)) {
                return null;
            }
            String remoteUrl = readRemoteOriginUrl(config);
            if (remoteUrl == null) {
                return null;
            }
            ParsedRemote parsed = parseGitRemote(remoteUrl);
            if (parsed == null) {
                return null;
            }
            return "https://" + parsed.host() + "/" + parsed.owner() + "/" + parsed.name();
        } catch (Exception e) {
            // git 解析是 best-effort（CC getCurrentRepoHttpsUrl 无抛异常路径，失败静默返回 null）
            if (log.isDebugEnabled()) {
                log.debug("[GitRemoteResolver] 读取 remote https URL 失败，返回 null: {}", e.toString());
            }
            return null;
        }
    }

    // [IMP-D-6 · OPD-CM5-D-19 修复] △-4：.git/config 解析对齐 CC gitConfigParser.ts 三处偏移
    //   （原 B-21 登记不修，用户拍板修复）：① 大小写（section/key 均 toLowerCase 后匹配 :43-44，
    //   GHE 大写键如 [REMOTE "origin"]/URL = 也能命中）；② 键前缀（parseKeyValue isKeyChar 严格
    //   键名 + 必须 '=' :78-108，废弃 startsWith("url") 对 urlExtra 的前缀误匹配）；③ 转义/行内注释
    //   （parseValue :114-184：引号/转义序列/行内注释 #;/尾随空白裁剪，废弃仅剥包裹引号）。
    /** 读取 .git/config 中 {@code [remote "origin"]} 的 url 键（CC getRemoteUrl 等价）。 */
    private static String readRemoteOriginUrl(Path config) throws IOException {
        List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
        boolean inSection = false;
        for (String line : lines) {
            String trimmed = line.trim();
            // CC parseConfigString：跳过空行与注释行（'#' / ';'）
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == ';') {
                continue;
            }
            // Section 头
            if (trimmed.charAt(0) == '[') {
                inSection = matchesSectionHeader(trimmed, "remote", "origin");
                continue;
            }
            if (!inSection) {
                continue;
            }
            KeyValue kv = parseKeyValue(trimmed);
            if (kv != null && kv.key().equalsIgnoreCase("url")) {
                return kv.value();
            }
        }
        return null;
    }

    /** CC {@code parseKeyValue}（gitConfigParser.ts:78-108）返回值。 */
    private record KeyValue(String key, String value) {}

    /**
     * Section 头匹配 · CC original: {@code matchesSectionHeader}（gitConfigParser.ts:186-236）。
     * section 名大小写不敏感（:190-191）；subsection 名大小写敏感 + 反斜杠转义（\\ 与 \"）。
     */
    private static boolean matchesSectionHeader(String line, String section, String subsection) {
        // line 以 '[' 开头（:187 起 i=1）
        int i = 1;
        // 读 section 名
        while (i < line.length() && line.charAt(i) != ']' && line.charAt(i) != ' '
            && line.charAt(i) != '\t' && line.charAt(i) != '"') {
            i++;
        }
        if (!line.substring(1, i).equalsIgnoreCase(section)) {
            return false;
        }
        if (subsection == null) {
            // 简单 section：必须以 ']' 结尾
            return i < line.length() && line.charAt(i) == ']';
        }
        // 跳过 subsection 前的空白
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        // 必须有开始引号
        if (i >= line.length() || line.charAt(i) != '"') {
            return false;
        }
        i++; // 跳过开始引号
        // 读 subsection —— 大小写敏感，处理 \\ 和 \" 转义
        StringBuilder foundSubsection = new StringBuilder();
        while (i < line.length() && line.charAt(i) != '"') {
            if (line.charAt(i) == '\\' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                if (next == '\\' || next == '"') {
                    foundSubsection.append(next);
                    i += 2;
                    continue;
                }
                // git 对 subsection 其他转义丢弃反斜杠
                foundSubsection.append(next);
                i += 2;
                continue;
            }
            foundSubsection.append(line.charAt(i));
            i++;
        }
        // 必须有结束引号后跟 ']'
        if (i >= line.length() || line.charAt(i) != '"') {
            return false;
        }
        i++; // 跳过结束引号
        if (i >= line.length() || line.charAt(i) != ']') {
            return false;
        }
        return foundSubsection.toString().equals(subsection);
    }

    /**
     * 解析 {@code key = value} 行 · CC original: {@code parseKeyValue}（gitConfigParser.ts:78-108）。
     * 严格键名（isKeyChar：字母数字连字符）+ 跳过空白 + 必须 '='。行无有效键返回 null（布尔键无值）。
     */
    private static KeyValue parseKeyValue(String line) {
        // 读键：字母数字 + 连字符（isKeyChar :186-194）
        int i = 0;
        while (i < line.length() && isKeyChar(line.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return null;
        }
        String key = line.substring(0, i);
        // 跳过空白
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        // 必须有 '='
        if (i >= line.length() || line.charAt(i) != '=') {
            return null;
        }
        i++; // 跳过 '='
        // 跳过 '=' 后空白
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return new KeyValue(key, parseValue(line, i));
    }

    private static boolean isKeyChar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
            || (ch >= '0' && ch <= '9') || ch == '-';
    }

    /**
     * 解析值 · CC original: {@code parseValue}（gitConfigParser.ts:114-184）。
     * 处理引号、转义序列、行内注释（#/;）、未引号部分尾随空白裁剪。
     */
    private static String parseValue(String line, int start) {
        StringBuilder result = new StringBuilder();
        boolean inQuote = false;
        int i = start;
        while (i < line.length()) {
            char ch = line.charAt(i);
            // 引号外的行内注释结束值
            if (!inQuote && (ch == '#' || ch == ';')) {
                break;
            }
            if (ch == '"') {
                inQuote = !inQuote;
                i++;
                continue;
            }
            if (ch == '\\' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                if (inQuote) {
                    // 引号内：识别转义序列
                    switch (next) {
                        case 'n':
                            result.append('\n');
                            break;
                        case 't':
                            result.append('\t');
                            break;
                        case 'b':
                            result.append('\b');
                            break;
                        case '"':
                            result.append('"');
                            break;
                        case '\\':
                            result.append('\\');
                            break;
                        default:
                            // git 对未知转义静默丢弃反斜杠
                            result.append(next);
                            break;
                    }
                    i += 2;
                    continue;
                }
                // 引号外：\\ 按字面（行尾反斜杠续行本实现不处理多行，与 CC 一致）
                if (next == '\\') {
                    result.append('\\');
                    i += 2;
                    continue;
                }
                // 引号外其他反斜杠按字面处理
            }
            result.append(ch);
            i++;
        }
        // 裁剪未引号部分的尾随空白
        if (!inQuote) {
            return trimTrailingWhitespace(result.toString());
        }
        return result.toString();
    }

    /** CC {@code trimTrailingWhitespace}（gitConfigParser.ts:155-161）。 */
    private static String trimTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * 解析 git remote URL 为 host/owner/name · CC original: {@code parseGitRemote}
     * （detectRepository.ts:87-128）。接受任意 host；仅 github.com 由调用方过滤。
     * 注：repo 名可含点（e.g. cc.kurs.web），非贪婪匹配保证正确截断。
     */
    static ParsedRemote parseGitRemote(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();

        // SSH 格式：git@host:owner/repo.git
        Matcher ssh = SSH_REMOTE.matcher(trimmed);
        if (ssh.matches()) {
            if (!looksLikeRealHostname(ssh.group(1))) {
                return null;
            }
            return new ParsedRemote(ssh.group(1), ssh.group(2), ssh.group(3));
        }

        // URL 格式：https://host/owner/repo.git、ssh://git@host/owner/repo、git://host/owner/repo
        Matcher url = URL_REMOTE.matcher(trimmed);
        if (url.matches()) {
            String protocol = url.group(1);
            String hostWithPort = url.group(2);
            String hostWithoutPort = hostWithPort.split(":")[0];
            if (!looksLikeRealHostname(hostWithoutPort)) {
                return null;
            }
            // 仅 HTTPS/HTTP 保留端口（SSH/git 端口对构造 web URL 无意义，detectRepository.ts:112-118）
            String host = ("https".equals(protocol) || "http".equals(protocol))
                ? hostWithPort : hostWithoutPort;
            return new ParsedRemote(host, url.group(3), url.group(4));
        }
        return null;
    }

    /** 真实 hostname 校验 · CC original: {@code looksLikeRealHostname}（detectRepository.ts:170-178）。 */
    static boolean looksLikeRealHostname(String host) {
        if (host == null || !host.contains(".")) {
            return false;
        }
        String lastSegment = host.substring(host.lastIndexOf('.') + 1);
        if (lastSegment.isEmpty()) {
            return false;
        }
        // 真实 TLD 纯字母（e.g. com/org/io）；SSH alias 如 github.com-work 末段含连字符 → 拒绝
        return lastSegment.matches("[a-zA-Z]+");
    }
}
