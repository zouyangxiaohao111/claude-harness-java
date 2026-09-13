package com.nexusai.apis.session;

import com.nexusai.application.agent.memory.ConsolidationLock;
import com.nexusai.application.agent.memory.ConsolidationPrompt;
import com.nexusai.application.agent.memory.MemoryStorage;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [OPD-CM5-E-06] ExtractMemoriesController POST /api/agent/dream 手动整合 Web 等价测试。
 *
 * <p>WHY (CLAUDE.md 规则 9 · 测试验证意图): CC /dream 是 REPL 斜杠命令（dream.ts:19-49），Web 后端
 * 无终端斜杠命令 → 本端点提供 REST 载体：前端 POST 获取手动 dream prompt 文本，注入会话运行。本测试
 * 锁定<b>端点语义</b>:
 * <ol>
 *   <li><b>成功 → 200 + prompt 文本</b>——CC {@code DREAM_PROMPT_PREFIX + buildConsolidationPrompt(
 *       memoryRoot, transcriptDir, '')}（dream.ts:39-40）逐字对齐：前缀头 + 4 阶段 base prompt + 注入的
 *       memoryRoot / transcriptDir。若 200 但 body 缺前缀/内存目录 → 前端注入会话运行的是不完整 prompt。</li>
 *   <li><b>手动 /dream 乐观盖章锁</b>——CC dream.ts:36-37 {@code await recordConsolidation()} 在
 *       prompt 构建时盖章（consolidationLock.ts:130-140）。若端点不盖章 → 手动 /dream 不推进
 *       lastConsolidatedAt，后续自动 dream 时间门不受影响。</li>
 *   <li><b>args 可选附加上下文</b>——CC dream.ts:42-44 args 非空追加
 *       {@code "\n\n## Additional context from user\n\n" + args}。</li>
 * </ol>
 */
@DisplayName("[OPD-CM5-E-06] ExtractMemoriesController POST /api/agent/dream")
class ExtractMemoriesControllerTest {

    /** CC dream.ts:15-18 DREAM_PROMPT_PREFIX 前缀头（断言锚点，逐字对齐）。 */
    private static final String DREAM_PREFIX_HEADER = "# Dream: Memory Consolidation (manual run)";
    /** CC dream.ts:39 prompt = DREAM_PROMPT_PREFIX + buildConsolidationPrompt(...)，base 头。 */
    private static final String BASE_PROMPT_HEADER = "# Dream: Memory Consolidation";
    /** CC dream.ts:42-44 args 追加段头。 */
    private static final String ARGS_SECTION = "\n\n## Additional context from user\n\n";

    @TempDir
    Path tempDir;

    private MemoryStorage memoryStorage;

    @BeforeEach
    void setUp() {
        // 记忆目录 = @TempDir（MemoryStorage 纯读取，构造不创建目录）
        memoryStorage = new MemoryStorage(tempDir);
    }

    /**
     * 构造端点全链：真实 MemoryStorage（记忆目录=tempDir）+ 真实 CwdResolution 回落 user.dir。
     *
     * <p>gate 注入 {@code () -> true}：本机 user settings.json 可能含 {@code autoMemoryEnabled:false}
     * （本仓验证机 ~/.claude/settings.json:58 实测），默认门 {@link com.nexusai.application.agent.skill
     * BundledSkillEnabledGates#isAutoMemoryEnabled()} 会读该文件返回 false → 阻断 200 断言。测试
     * 经 {@link ExtractMemoriesController#setAutoMemoryEnabled} 注入恒 true 隔离环境门（生产 @Bean
     * 不调用 setter，默认门即 CC dream.ts:31 isEnabled）；gate=false 路径由
     * {@link #autoMemoryDisabled_400} 单独锁定。
     */
    private MockMvc mockMvc() {
        ExtractMemoriesController controller = new ExtractMemoriesController();
        ReflectionTestUtils.setField(controller, "memoryStorage", memoryStorage);
        controller.setAutoMemoryEnabled(() -> true);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    /** 构造端点全链 + 注入 gate 恒 false（锁定 400 拒绝语义）。 */
    private MockMvc mockMvcGateClosed() {
        ExtractMemoriesController controller = new ExtractMemoriesController();
        ReflectionTestUtils.setField(controller, "memoryStorage", memoryStorage);
        controller.setAutoMemoryEnabled(() -> false);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("成功：POST /dream → 200 + DREAM_PROMPT_PREFIX + 4 阶段 base prompt（含 memoryRoot/transcriptDir）")
    void success_returnsManualDreamPrompt() throws Exception {
        // WHY: CC dream.ts:39-40 prompt = DREAM_PROMPT_PREFIX + buildConsolidationPrompt(memoryRoot,
        //   transcriptDir, '') —— 前端 POST 拿到完整手动 dream prompt 注入会话运行。若 200 但缺前缀头/
        //   缺 4 阶段指引 → 注入的是残缺 prompt，dream 无法按 CC 语义执行。
        String memoryRoot = tempDir.toString();
        // [S2] transcriptDir = getProjectDir(getOriginalCwd(sessionId)) —— config-home 项目 slug 目录
        //   （sess-dream-test 未绑 boundProject → CwdResolution 回落 user.dir，与端点同源派生）
        String transcriptDir = com.nexusai.application.agent.tool.SessionStorage
            .getProjectDir(java.nio.file.Path.of(System.getProperty("user.dir", "."))).toString();

        mockMvc().perform(post("/api/agent/dream?sessionId=sess-dream-test"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/plain"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(DREAM_PREFIX_HEADER)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(BASE_PROMPT_HEADER)))
            // CC dream.ts:33 memoryRoot=getAutoMemPath() 注入 prompt（buildConsolidationPrompt 模板）
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Memory directory: `" + memoryRoot + "`")))
            // CC dream.ts:34 transcriptDir=getProjectDir(getOriginalCwd()) 注入 prompt
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Session transcripts: `" + transcriptDir + "` (large JSONL files")));
    }

    @Test
    @DisplayName("手动 /dream 乐观盖章锁：POST 后 .consolidate-lock 存在且 body=当前 PID（dream.ts:36-37）")
    void success_stampsConsolidationLock() throws Exception {
        // WHY: CC dream.ts:36-37 await recordConsolidation() —— 手动 /dream 乐观盖章锁
        //   （consolidationLock.ts:130-140 mkdir + writeFile(lockPath(), String(process.pid))）。
        //   若端点不盖章 → lastConsolidatedAt 不推进，与 CC 手动 /dream 语义偏离。
        mockMvc().perform(post("/api/agent/dream?sessionId=sess-dream-test"))
            .andExpect(status().isOk());

        Path lockFile = tempDir.resolve(ConsolidationLock.LOCK_FILE);
        org.assertj.core.api.Assertions.assertThat(Files.exists(lockFile))
            .as("手动 /dream 必须在 prompt 构建时盖章 .consolidate-lock（dream.ts:36-37）")
            .isTrue();
        String body = Files.readString(lockFile, StandardCharsets.UTF_8).trim();
        org.assertj.core.api.Assertions.assertThat(body)
            .as("锁 body = 当前进程 PID（consolidationLock.ts:137 String(process.pid)）")
            .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
    }

    @Test
    @DisplayName("args 可选：body 传 args → prompt 追加 ## Additional context from user 段（dream.ts:42-44）")
    void args_appendsAdditionalContext() throws Exception {
        // WHY: CC dream.ts:42-44 if (args) prompt += '\n\n## Additional context from user\n\n' + args
        //   —— 用户可随 /dream 提供附加上下文指导 consolidation；若 args 被忽略 → 前端无法传达意图。
        mockMvc().perform(post("/api/agent/dream?sessionId=sess-dream-test")
                .contentType("application/json")
                .content("{\"args\":\"Focus on the build failure from yesterday\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                ARGS_SECTION + "Focus on the build failure from yesterday")));
    }

    @Test
    @DisplayName("gate=false（auto-memory 禁用）→ 400 拒绝（CC dream.ts:31 isEnabled gate）")
    void autoMemoryDisabled_400() throws Exception {
        // WHY: CC dream.ts:31 isEnabled: () => isAutoMemoryEnabled() —— auto-memory 关闭时 /dream
        //   skill 不可调用（命令未注册/未启用）。REST 以 400 表达 gate 关闭；若禁用时仍 200 →
        //   前端注入的 dream prompt 会被后续 LLM 消费但记忆目录未接线（memoryRoot 无意义）。
        mockMvcGateClosed().perform(post("/api/agent/dream?sessionId=sess-dream-test"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[批 3a] POST /dream 缺 sessionId → 400（memoryRoot/transcriptDir 均 per-project，必须显式锚定）")
    void missingSessionId_400() throws Exception {
        // WHY（缺值策略 (a)）：旧实现无 sessionId 入参 → 读**裸 MDC 会话槽**（批 3c 已删除），
        //   该槽第三态 = 上一请求残留的**别的会话** id ⇒ dream 锁会盖到别的项目的记忆目录上。
        //   变异点：把 requireSessionId 改回兜底 → 本用例红（200）。
        mockMvc().perform(post("/api/agent/dream"))
            .andExpect(status().isBadRequest());
        mockMvc().perform(post("/api/agent/dream").param("sessionId", ""))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[批 3a 反向实验 · 批 3c 诱饵已删] transcriptDir 取显式 sessionId 的 boundProject（不得回落 user.dir）")
    void transcriptDir_usesExplicitSessionId_notMdc() throws Exception {
        // WHY（规则九）：CC dream.ts:34 transcriptDir = getProjectDir(getOriginalCwd())。旧实现从
        //   裸 MDC 会话槽取会话 ⇒ 该槽第三态（上一请求残留的别的会话）会让 dream 去读**别的项目**的转录。
        //   装置：给显式 sessionId 绑一个与 user.dir 不同的 boundProject。
        //   若实现不用显式 sessionId（或回落 user.dir），transcriptDir 会变成 user.dir 派生值 → 红。
        //   [批 3c] 语义消失：原装置中的「残留别的会话」诱饵随裸 MDC 会话槽删除而不可制造；
        //   本用例现只剩「显式 sessionId 的 boundProject 生效、不得回落 user.dir」这一侧，仍具鉴别力。
        java.nio.file.Path explicitProject = tempDir.resolve("explicit-project");
        java.nio.file.Files.createDirectories(explicitProject);
        com.nexusai.common.SessionProjectRoot.setForSession("sess-dream-test", explicitProject.toString());
        try {
            String expectedTranscriptDir = com.nexusai.application.agent.tool.SessionStorage
                .getProjectDir(explicitProject).toString();
            mockMvc().perform(post("/api/agent/dream?sessionId=sess-dream-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                    "Session transcripts: `" + expectedTranscriptDir + "`")));
            org.assertj.core.api.Assertions.assertThat(expectedTranscriptDir)
                .as("装置有效性：显式会话的 boundProject 派生目录必须与 user.dir 派生值不同，否则本用例无鉴别力")
                .isNotEqualTo(com.nexusai.application.agent.tool.SessionStorage
                    .getProjectDir(java.nio.file.Path.of(System.getProperty("user.dir", "."))).toString());
        } finally {
            com.nexusai.common.SessionProjectRoot.clearSession("sess-dream-test");
        }
    }

    @Test
    @DisplayName("args 为空 body → 不追加 Additional context 段（CC dream.ts:42 if(args) 空值跳过）")
    void emptyArgs_skipsAdditionalContext() throws Exception {
        // WHY: CC dream.ts:42-44 仅 args 真值才追加；空/缺省 args → prompt 恒为前缀+base（无额外段）。
        mockMvc().perform(post("/api/agent/dream?sessionId=sess-dream-test"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString(ARGS_SECTION))));
    }
}
