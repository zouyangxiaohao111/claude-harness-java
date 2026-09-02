package com.nexusai.application.agent.tool.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WebFetch 预批准域名表 · 对齐 CC {@code Open-ClaudeCode/src/tools/WebFetchTool/preapproved.ts}。
 *
 * <p><b>CC original:</b> {@code preapproved.ts:14-166}：
 * <ul>
 *   <li>{@code PREAPPROVED_HOSTS}（:14-131）：仅代码相关域名，WebFetch（GET 请求）例外放行。
 *       安全警告：沙箱系统<b>刻意不继承</b>此表做网络限制（POST/上传可能造成数据外泄）。</li>
 *   <li>模块加载时一次性拆分（:136-152）：无 '/' 的进 {@code HOSTNAME_ONLY} Set；含 '/' 的按 host
 *       聚合进 {@code PATH_PREFIXES} Map（如 {@code github.com/anthropics}、{@code vercel.com/docs}）。</li>
 *   <li>{@code isPreapprovedHost(hostname, pathname)}（:154-166）：hostname 在 HOSTNAME_ONLY 直接
 *       true；PATH_PREFIXES 命中时强制路径段边界——{@code "/anthropics"} 不得匹配
 *       {@code "/anthropics-evil/malware"}，仅精确相等或 prefix + "/" 前缀放行。</li>
 * </ul>
 *
 * <p><b>WHY 存在</b>: WebFetch SSRF 安全链 P0-3（IMP-H1）把 preapproved.ts 完整移植；本类承担
 * 域名表 + 路径段边界判定，{@link WebFetchSecurity}（utils.ts 移植）与 {@link WebFetchTool}
 * （WebFetchTool.ts 移植）共用。
 *
 * <p><b>线程安全</b>: 全部 static final 不可变集合，模块加载时一次构建，无并发写。
 */
public final class WebFetchPreapprovedHosts {

    private WebFetchPreapprovedHosts() {
    }

    /** CC original: PREAPPROVED_HOSTS（preapproved.ts:14-131）。 */
    private static final Set<String> PREAPPROVED_HOSTS = Set.of(
            // Anthropic
            "platform.claude.com",
            "code.claude.com",
            "modelcontextprotocol.io",
            "github.com/anthropics",
            "agentskills.io",

            // Top Programming Languages
            "docs.python.org",
            "en.cppreference.com",
            "docs.oracle.com",
            "learn.microsoft.com",
            "developer.mozilla.org",
            "go.dev",
            "pkg.go.dev",
            "www.php.net",
            "docs.swift.org",
            "kotlinlang.org",
            "ruby-doc.org",
            "doc.rust-lang.org",
            "www.typescriptlang.org",

            // Web & JavaScript Frameworks/Libraries
            "react.dev",
            "angular.io",
            "vuejs.org",
            "nextjs.org",
            "expressjs.com",
            "nodejs.org",
            "bun.sh",
            "jquery.com",
            "getbootstrap.com",
            "tailwindcss.com",
            "d3js.org",
            "threejs.org",
            "redux.js.org",
            "webpack.js.org",
            "jestjs.io",
            "reactrouter.com",

            // Python Frameworks & Libraries
            "docs.djangoproject.com",
            "flask.palletsprojects.com",
            "fastapi.tiangolo.com",
            "pandas.pydata.org",
            "numpy.org",
            "www.tensorflow.org",
            "pytorch.org",
            "scikit-learn.org",
            "matplotlib.org",
            "requests.readthedocs.io",
            "jupyter.org",

            // PHP Frameworks
            "laravel.com",
            "symfony.com",
            "wordpress.org",

            // Java Frameworks & Libraries
            "docs.spring.io",
            "hibernate.org",
            "tomcat.apache.org",
            "gradle.org",
            "maven.apache.org",

            // .NET & C# Frameworks
            "asp.net",
            "dotnet.microsoft.com",
            "nuget.org",
            "blazor.net",

            // Mobile Development
            "reactnative.dev",
            "docs.flutter.dev",
            "developer.apple.com",
            "developer.android.com",

            // Data Science & Machine Learning
            "keras.io",
            "spark.apache.org",
            "huggingface.co",
            "www.kaggle.com",

            // Databases
            "www.mongodb.com",
            "redis.io",
            "www.postgresql.org",
            "dev.mysql.com",
            "www.sqlite.org",
            "graphql.org",
            "prisma.io",

            // Cloud & DevOps
            "docs.aws.amazon.com",
            "cloud.google.com",
            "kubernetes.io",
            "www.docker.com",
            "www.terraform.io",
            "www.ansible.com",
            "vercel.com/docs",
            "docs.netlify.com",
            "devcenter.heroku.com",

            // Testing & Monitoring
            "cypress.io",
            "selenium.dev",

            // Game Development
            "docs.unity.com",
            "docs.unrealengine.com",

            // Other Essential Tools
            "git-scm.com",
            "nginx.org",
            "httpd.apache.org"
    );

    /** 纯 hostname（无路径前缀）集合 · CC original: HOSTNAME_ONLY（preapproved.ts:136-152）。 */
    private static final Set<String> HOSTNAME_ONLY;

    /** host → 路径前缀列表 · CC original: PATH_PREFIXES（preapproved.ts:136-152）。 */
    private static final Map<String, List<String>> PATH_PREFIXES;

    static {
        Set<String> hosts = new HashSet<>();
        Map<String, List<String>> paths = new HashMap<>();
        for (String entry : PREAPPROVED_HOSTS) {
            int slash = entry.indexOf('/');
            if (slash == -1) {
                hosts.add(entry);
            } else {
                String host = entry.substring(0, slash);
                String path = entry.substring(slash);
                List<String> prefixes = paths.computeIfAbsent(host, k -> new ArrayList<>());
                prefixes.add(path);
            }
        }
        HOSTNAME_ONLY = Set.copyOf(hosts);
        Map<String, List<String>> frozenPaths = new HashMap<>();
        paths.forEach((k, v) -> frozenPaths.put(k, List.copyOf(v)));
        PATH_PREFIXES = Map.copyOf(frozenPaths);
    }

    /**
     * 判定 hostname 是否预批准 · 对齐 CC {@code isPreapprovedHost(hostname, pathname)}
     * （preapproved.ts:154-166）。
     *
     * <p><b>路径段边界铁律</b>（CC :159-163 注释）："{@code /anthropics}" 不得匹配
     * "{@code /anthropics-evil/malware}"——仅 pathname 精确等于 prefix，或以 prefix + "/" 开头
     * 才放行，防止路径前缀伪造（SSRF 诱导抓取相邻路径）。
     *
     * @param hostname 目标 host（已解析小写，由调用方负责）
     * @param pathname 目标路径（含前导 '/'，由调用方负责）
     * @return true = 预批准域名（WebFetch 免 ask 直接 GET）
     */
    public static boolean isPreapprovedHost(String hostname, String pathname) {
        if (hostname == null) {
            return false;
        }
        if (HOSTNAME_ONLY.contains(hostname)) {
            return true;
        }
        List<String> prefixes = PATH_PREFIXES.get(hostname);
        if (prefixes != null) {
            for (String p : prefixes) {
                if (pathname == null) {
                    continue;
                }
                if (pathname.equals(p) || pathname.startsWith(p + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从 URL 判定是否预批准 · 对齐 CC {@code utils.ts:130-137 isPreapprovedUrl(url)}。
     *
     * <p>解析失败（非 URL / hostname 缺省）返回 {@code false}——fail-closed，不得因解析异常
     * 误判预批准。
     *
     * @param url 目标 URL
     * @return true = hostname+pathname 命中预批准表
     */
    public static boolean isPreapprovedUrl(String url) {
        try {
            URI parsed = URI.create(url);
            return isPreapprovedHost(parsed.getHost(), parsed.getPath());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
