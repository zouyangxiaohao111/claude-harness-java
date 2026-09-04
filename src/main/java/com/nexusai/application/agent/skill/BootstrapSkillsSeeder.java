package com.nexusai.application.agent.skill;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 内置技能首次初始化引导 · 把打包在 classpath {@code skills/} 的内置技能(anthropics/skills +
 * software-design-skill-chain + craft-studio 视觉)在首次启动复制到用户 skills 根。
 *
 * <p><b>WHY(用户 2026-09-04 拍板)</b>:新机器(无 {@code ~/.{appName}})或 skills 目录为空时,自动
 * 铺一批开箱技能,用户无需手动装。资源随 jar 分发(classpath),不依赖外部目录。
 *
 * <p><b>复制条件</b>:skills 根不存在 或 <b>为空</b> 才复制 —— 已有用户技能(非空)绝不覆盖/追加
 * (尊重用户自装;项目约定"空则引导")。日志中文。
 *
 * <p><b>实现</b>:PathMatchingResourcePatternResolver 枚举 {@code classpath:skills/*-skill-md} 匹配
 * 各技能根(实际模式 {@code classpath:skills/} + 每技能目录下 SKILL.md,代码里以 SKILL.md 定位技能名),
 * 再按技能名枚举其全部文件(含子资源 fonts/references/scripts 等)逐文件复制(jar 内 resource 不可
 * getFile,须 getInputStream)。Javadoc 不写字面 {@code star-slash} 以免提前终止注释。
 */
@Component
public class BootstrapSkillsSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapSkillsSeeder.class);

    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /** 用户 skills 根 · 对齐技能加载器目录: {@code {configHome}/skills}(SkillChangeDetector 监听同一目录)。 */
    Path skillsRoot() {
        return NexusaiPaths.getAppConfigHomePath().resolve("skills");
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfEmpty();
    }

    /** 空则从 classpath 复制内置技能(幂等 · 非空跳过)。独立方法便于测试注入空/非空目录。 */
    public int seedIfEmpty() {
        Path root;
        try {
            root = skillsRoot();
        } catch (Exception e) {
            log.warn("[BootstrapSkillsSeeder] skills 根解析失败,跳过内置技能引导: {}", e.getMessage());
            return 0;
        }
        try {
            if (Files.exists(root)) {
                try (Stream<Path> s = Files.list(root)) {
                    if (s.findAny().isPresent()) {
                        log.info("[BootstrapSkillsSeeder] skills 目录已有内容({}),跳过内置技能引导(空才复制)",
                            root);
                        return 0;
                    }
                }
            }
            Files.createDirectories(root);
            int n = copyClasspathSkills(root);
            log.info("[BootstrapSkillsSeeder] 首次初始化:从内置 resource 复制 {} 个技能到 {}", n, root);
            return n;
        } catch (Exception e) {
            log.warn("[BootstrapSkillsSeeder] 内置技能复制失败(不阻断启动): {}", e.getMessage());
            return 0;
        }
    }

    /** 从 classpath skills/ 复制全部技能目录(含子资源)到 targetRoot。返回复制技能数。 */
    private int copyClasspathSkills(Path targetRoot) throws Exception {
        Resource[] skillMarks = resolver.getResources("classpath*:skills/*/SKILL.md");
        int copied = 0;
        for (Resource mark : skillMarks) {
            String path = classPathOf(mark); // skills/<name>/SKILL.md
            String rel = path.substring("skills/".length()); // <name>/SKILL.md
            int slash = rel.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            String skillName = rel.substring(0, slash);
            Path skillDir = targetRoot.resolve(skillName);
            Files.createDirectories(skillDir);
            Resource[] files = resolver.getResources("classpath*:skills/" + skillName + "/**");
            int filesCopied = 0;
            for (Resource rf : files) {
                // file 形态(开发 target/classes)resolver 会返回目录条目,getInputStream 抛 —— 过滤目录。
                // jar 形态只枚举文件条目,无此问题(仍兼容)。jar:file: URI 非 "file" scheme,不判目录安全。
                java.net.URI uri = rf.getURI();
                if ("file".equals(uri.getScheme()) && Files.isDirectory(java.nio.file.Paths.get(uri))) {
                    continue;
                }
                String fp = classPathOf(rf); // skills/<name>/relative
                String prefix = "skills/" + skillName + "/";
                if (!fp.startsWith(prefix)) {
                    continue;
                }
                String relFile = fp.substring(prefix.length());
                if (relFile.isEmpty()) {
                    continue;
                }
                Path dest = skillDir.resolve(relFile).normalize();
                if (!dest.startsWith(skillDir)) {
                    continue; // 防穿越
                }
                if (dest.getParent() != null) {
                    Files.createDirectories(dest.getParent());
                }
                try (InputStream in = rf.getInputStream()) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                filesCopied++;
            }
            if (log.isDebugEnabled()) {
                log.debug("[BootstrapSkillsSeeder] 复制技能 '{}' 共 {} 文件", skillName, filesCopied);
            }
            copied++;
        }
        return copied;
    }

    /** 从 classpath resource 取相对 classpath 路径(如 "skills/canvas-design/SKILL.md")。
     *  统一用 URL(file:/D:/.../skills/x.md 或 jar:file:...!/skills/x.md)解析 —— Windows 下
     *  FileSystemResource.getDescription() 用反斜杠,indexOf("skills/") 找不到。 */
    private String classPathOf(Resource r) throws java.io.IOException {
        if (r instanceof org.springframework.core.io.ClassPathResource cpr) {
            return cpr.getPath();
        }
        String url = r.getURL().toString();
        int i = url.indexOf("skills/");
        return i >= 0 ? url.substring(i) : "";
    }
}
