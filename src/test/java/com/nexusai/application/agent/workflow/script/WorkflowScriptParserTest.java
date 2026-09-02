package com.nexusai.application.agent.workflow.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkflowScriptParser 单测（映射 CC packages/workflow-engine/src/__tests__/script.test.ts 的纯 parser 组）。
 *
 * <p>测试意图（规则九 WHY）：meta 必须是编译期可求值的纯数据（任何标识符引用/调用/插值 → 拒绝），
 * import / 动态 import / 额外 export 必须被拒——因为 journal resume 需要同样的输入产生同样的 agent 调用序列，
 * 且错误要给精确引导而非 AsyncFunction 泛化 Syntax error。断言的是「确定性 + 可复现」这一意图，
 * 而非「解析器能跑」。</p>
 *
 * <p>P0 边界：无 JS 引擎（DEC-P0-02 未拍板），以 {@link #FAKE} fake executor 验证 parser 纯行为；
 * Date/Math/8 参数注入端到端组待引擎选定后启用（P0-plan §3 P0 落地边界）。</p>
 */
class WorkflowScriptParserTest {

    private final WorkflowScriptParser parser = new WorkflowScriptParser();

    /** P0 fake executor：无 JS 引擎，验证 parser 纯行为（P0-plan §3）。 */
    private static final WorkflowScriptExecutor FAKE = (hooks, args, budget) ->
            CompletableFuture.completedFuture(args);

    // ---- extractMeta（对齐 CC script.test.ts:27-112）----

    @Nested
    @DisplayName("extractMeta：命中/剥离/拒绝")
    class ExtractMeta {

        @Test
        @DisplayName("纯字面量提取 + body 剥离（CC 27-34）")
        void extractsPlainLiteralAndStripsStatement() {
            String src = "export const meta = { name: 'x', description: 'y' }\nreturn 1";
            WorkflowScriptParser.ExtractResult r = parser.extractMeta(src);
            assertEquals("x", r.meta().name());
            assertEquals("y", r.meta().description());
            assertFalse(r.body().contains("export const meta"), "剥离后 body 不应含 export const meta");
            assertTrue(r.body().contains("return 1"), "剥离后 body 应保留 return 1");
        }

        @Test
        @DisplayName("无 meta → null + body 原样（CC 36-41）")
        void returnsNullWhenNoMeta() {
            String src = "return 42";
            WorkflowScriptParser.ExtractResult r = parser.extractMeta(src);
            assertNull(r.meta());
            assertEquals(src, r.body());
        }

        @Test
        @DisplayName("多行 export\\nconst\\nmeta = 也能命中（script-doc §1.1：\\s 含换行）")
        void matchesMultilineMetaDeclaration() {
            String src = "export\nconst\nmeta = { name: 'x', description: 'y' }\nreturn 1";
            WorkflowScriptParser.ExtractResult r = parser.extractMeta(src);
            assertEquals("x", r.meta().name());
            assertFalse(r.body().contains("meta ="));
        }

        @Test
        @DisplayName("非纯字面量（变量引用 description: y）→ ScriptError（CC 43-46）")
        void rejectsNonPlainLiteral() {
            String src = "const x = 1\nexport const meta = { name: 'x', description: y }\nreturn 1";
            ScriptError e = assertThrows(ScriptError.class, () -> parser.extractMeta(src));
            assertTrue(e.getMessage().contains("meta must be a plain literal"),
                    "变量引用必须报 'must be a plain literal'，实得: " + e.getMessage());
        }

        @Test
        @DisplayName("meta 为数组 [1,2] → ScriptError（CC 81；'=' 后非 '{' 分支）")
        void rejectsArrayMeta() {
            String src = "export const meta = [1, 2]\nreturn 1";
            assertThrows(ScriptError.class, () -> parser.extractMeta(src));
        }

        @Test
        @DisplayName("缺 name → ScriptError（CC 87-91）")
        void rejectsMissingName() {
            String src = "export const meta = { description: \"d\" }\nreturn 1";
            ScriptError e = assertThrows(ScriptError.class, () -> parser.extractMeta(src));
            assertTrue(e.getMessage().contains("meta must include string name and description"));
        }

        @Test
        @DisplayName("缺 description → ScriptError（CC 93-97）")
        void rejectsMissingDescription() {
            String src = "export const meta = { name: \"n\" }\nreturn 1";
            assertThrows(ScriptError.class, () -> parser.extractMeta(src));
        }

        @Test
        @DisplayName("未闭合括号 → ScriptError（CC 99-103）")
        void rejectsUnclosedBraces() {
            String src = "export const meta = { name: \"n\", description: \"d\"\nreturn 1";
            ScriptError e = assertThrows(ScriptError.class, () -> parser.extractMeta(src));
            assertTrue(e.getMessage().contains("meta literal braces are not closed"));
        }

        @Test
        @DisplayName("嵌套 phases 数组透传（CC 105-112）")
        void passesThroughNestedPhases() {
            String src = "export const meta = { name: 'x', description: 'y', phases: [{ title: 'A' }, { title: 'B' }] }\nreturn 1";
            WorkflowScriptParser.ExtractResult r = parser.extractMeta(src);
            assertEquals(2, r.meta().phases().size());
            assertEquals("A", r.meta().phases().get(0).title());
            assertEquals("B", r.meta().phases().get(1).title());
        }

        @Test
        @DisplayName("字符串内的花括号不计入深度（script.ts:47-62 字符串感知状态机）")
        void ignoresBracesInsideStrings() {
            String src = "export const meta = { name: 'a{b}c', description: 'y' }\nreturn 1";
            WorkflowScriptParser.ExtractResult r = parser.extractMeta(src);
            assertEquals("a{b}c", r.meta().name());
        }
    }

    // ---- assertScriptBody（对齐 CC script.test.ts:118-168）----

    @Nested
    @DisplayName("assertScriptBody：三条规则精确引导报错")
    class AssertBody {

        @Test
        @DisplayName("静态 import → import is not supported（CC 118-129）")
        void rejectsStaticImport() {
            String src = "import { foo } from 'bar'\nexport const meta = { name: 'n', description: 'd' }\nreturn foo()";
            ScriptError e = assertThrows(ScriptError.class, () -> parser.parse(src));
            assertTrue(e.getMessage().contains("import is not supported"),
                    "必须报带引导的 import 错误而非泛化 Syntax error，实得: " + e.getMessage());
            assertTrue(e.getMessage().contains("injected as parameters"), "报错须含 8 参数引导语");
        }

        @Test
        @DisplayName("meta 剥离后额外 export → allow only one export（CC 131-142）")
        void rejectsExtraExport() {
            String src = "export const meta = { name: 'n', description: 'd' }\nexport const X = 1\nreturn X";
            ScriptError e = assertThrows(ScriptError.class, () -> parser.parse(src));
            assertTrue(e.getMessage().contains("allow only one export const meta"),
                    "meta 已剥离，命中即第二个 export，实得: " + e.getMessage());
        }

        @Test
        @DisplayName("动态 import(...) → sandbox anti-escape（CC 151-160）")
        void rejectsDynamicImport() {
            String src = "const cp = await import('node:child_process')\nreturn cp.execSync('id').toString()";
            ScriptError e = assertThrows(ScriptError.class, () -> parser.parse(src));
            assertTrue(e.getMessage().contains("import"), "动态 import 必须被拒");
            assertTrue(e.getMessage().contains("sandbox"), "报错须指向沙箱逃逸语义");
        }

        @Test
        @DisplayName("字符串内含 'import' 不误伤（CC 162-168：\\b 防误报）")
        void doesNotMisfireOnStringImport() {
            String src = "export const meta = { name: 'n', description: 'd' }\nconst r = await agent('please import this module')\nreturn r";
            ParsedScript ps = parser.parse(src, FAKE);
            assertNotNull(ps, "prompt 含 'import' 是合法脚本，不得被静态规则误杀");
        }

        @Test
        @DisplayName("正常纯 JS 脚本不误伤（CC 144-149）")
        void doesNotMisfireOnNormalScript() {
            String src = "export const meta = { name: 'n', description: 'd' }\nconst r = await agent('hi')\nreturn r";
            ParsedScript ps = parser.parse(src, FAKE);
            assertNotNull(ps);
        }
    }

    // ---- parse + execute 注入参数模型（8 业务 + 2 沙箱 = 10 形参）----

    @Nested
    @DisplayName("parse + execute 注入参数模型")
    class ParseAndExecute {

        @Test
        @DisplayName("无 meta 脚本：parse 校验通过，execute 委派注入的 executor（args 透传）")
        void parsesAndExecutesWithInjectedExecutor() throws Exception {
            ParsedScript ps = parser.parse("return args.n + 1", FAKE);
            Object out = ps.execute(null, Map.of("n", 41), Map.of("total", 0)).get();
            // FAKE 返回 args 原样（P0 无 JS 引擎，端到端运算待 DEC-P0-02 引擎选定后启用）
            assertEquals(Map.of("n", 41), out);
        }

        @Test
        @DisplayName("未注入 executor 时默认编译 RestrictedScriptExecutor（G-2 生产接线，替代 NOT_WIRED）")
        void parseDefaultsToRestrictedInterpreter() {
            // WHY（G-2）：生产 WorkflowServiceImpl → new WorkflowRunEngine() 无注入 executor，
            //   parser 必须默认编译真实解释器而非 NOT_WIRED，否则任何生产 run 都抛 IllegalStateException。
            ParsedScript ps = parser.parse("return 1");
            assertTrue(ps.executor() instanceof RestrictedScriptExecutor,
                    "未注入 executor 应默认 RestrictedScriptExecutor，实得: " + ps.executor().getClass().getSimpleName());
            // 真实解释器（null hooks 防御 → fail loud；非静默）
            CompletableFuture<Object> f = ps.execute(null, null, null);
            assertTrue(f.isCompletedExceptionally(), "null hooks 必须显式失败而非静默返回");
        }

        @Test
        @DisplayName("显式注入 NOT_WIRED 仍 fail loud（兜底保留，API 稳定）")
        void explicitNotWiredFailsLoud() {
            ParsedScript ps = parser.parse("return 1", WorkflowScriptExecutor.NOT_WIRED);
            CompletableFuture<Object> f = ps.execute(null, null, null);
            assertTrue(f.isCompletedExceptionally(), "显式 NOT_WIRED 必须显式失败");
        }

        @Test
        @DisplayName("meta 剥离后 body 不含 export const meta（script.ts:88-92）")
        void bodyStrippedOfMetaStatement() {
            ParsedScript ps = parser.parse("export const meta = { name: 'n', description: 'd' }\nreturn 1", FAKE);
            assertFalse(ps.body().contains("export const meta"));
            assertTrue(ps.body().contains("return 1"));
        }
    }

    // ---- DateMathSandbox 确定性沙箱（对齐 CC script.test.ts:54-77 + script-doc §4）----

    @Nested
    @DisplayName("DateMathSandbox 确定性沙箱")
    class DateMathSandboxTest {

        @Test
        @DisplayName("Date.now() → NonDeterministicError 含 Date.now（CC 54-59）")
        void dateNowRejected() {
            NonDeterministicError e = assertThrows(NonDeterministicError.class, DateMathSandbox::rejectDateNow);
            assertTrue(e.getMessage().contains("Date.now()"));
            assertTrue(e.getMessage().contains("resume determinism"), "报错须点明 resume 确定性动机");
        }

        @Test
        @DisplayName("无参 new Date() → NonDeterministicError 含 new Date（CC 68-72）")
        void newDateNoArgRejected() {
            NonDeterministicError e = assertThrows(NonDeterministicError.class, DateMathSandbox::rejectNewDateNoArg);
            assertTrue(e.getMessage().contains("new Date"));
        }

        @Test
        @DisplayName("Math.random() → NonDeterministicError 含 Math.random（CC 61-66）")
        void mathRandomRejected() {
            NonDeterministicError e = assertThrows(NonDeterministicError.class, DateMathSandbox::rejectMathRandom);
            assertTrue(e.getMessage().contains("Math.random"));
        }
    }
}
