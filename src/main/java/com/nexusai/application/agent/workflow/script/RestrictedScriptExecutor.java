package com.nexusai.application.agent.workflow.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 受限脚本解释执行器 · 生产 G-2 接线实现（DEC-P0-02 受限模型落地）。
 *
 * <p><b>对齐 CC</b>：{@code Open-ClaudeCode/packages/workflow-engine/src/engine/script.ts:150-229
 * ParsedScript.execute} —— 脚本函数体在 parseScript 时编译为可执行闭包（CC 用 {@code new AsyncFunction}
 * 编译 10 形参：6 hook + args + budget + Date + Math），execute 时把 hooks/args/budget 绑定后运行。</p>
 *
 * <p><b>受限模型边界（文档化部分对齐差距）</b>：Java 侧无 JS 引擎。核查本地 maven 仓库，
 * {@code org.graalvm.js:js-community:24.1.1} 仅有 .pom 与 {@code .pom.lastUpdated}（下载失败标记），
 * 引擎 jar 缺失，且构建必须 {@code mvn -o}（离线）——GraalJS 全 JS 执行<b>不可行</b>。本实现以
 * <b>JS 子集 DSL 解释执行</b>对齐核心语义，而非简单实现：</p>
 * <ul>
 *   <li><b>语法子集</b>：const/let/var、赋值、if/else、while、for...of、continue/break、return；
 *       对象/数组字面量（含 shorthand / 展开 spread）、模板字符串、箭头函数、await、.then/.catch 链、
 *       成员访问（.prop / [idx] / ?.）、方法调用（map/filter/flat/includes/push/find/forEach/length/join/indexOf）、
 *       运算符（+ - * / % 比较 / === !== 逻辑 && || 一元 ! - typeof 三元 ?: 自增 ++ --）、new Date(...)、
 *       Boolean/String/Number/Object.keys-entries-values。Date.now()/new Date() 无参/Math.random() 抛
 *       {@link NonDeterministicError}（沙箱保 resume 确定性，CC script.ts:116-143）。</li>
 *   <li><b>不支持 → 编译期 fail-loud ScriptError</b>（不是静默跳过）：import（parser 已拦）、function 声明、
 *       class、正则、解构、原生模块（fs/process）、generator、非 Date 的 new、C 风格 for(...;...;...) 等。</li>
 *   <li><b>async 语义</b>：hook 返回 {@link CompletableFuture}，await 用 join() 在专用解释器线程阻塞等待
 *       （CC await 挂起等价，单解释器线程串行驱动脚本控制流；parallel/pipeline 的真实并发由
 *       WorkflowHooksImpl 的 allOf/thenCompose 保证，与 CC 一致）。</li>
 *   <li><b>执行线程</b>：{@link #execute} 在专用守护线程池运行，不阻塞 launch 调用线程（detached 语义，
 *       service.ts:226-250）；解释期间对 hook future 的 join() 只阻塞该解释器线程。</li>
 * </ul>
 *
 * <p><b>接线</b>：{@link WorkflowScriptParser#parse(String, WorkflowScriptExecutor)} 在 executor 为 null 时
 * 默认 {@code new RestrictedScriptExecutor(body)} 编译（生产 WorkflowServiceImpl → new WorkflowRunEngine()
 * 走此路径，替换 {@link WorkflowScriptExecutor#NOT_WIRED}）。编译失败（子集外构造）→ {@link ScriptError}
 * 在 parse 期抛出，WorkflowRunEngine parse fail → run_done failed（fail loud）。</p>
 */
public final class RestrictedScriptExecutor implements WorkflowScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(RestrictedScriptExecutor.class);

    /** 专用解释器线程池（守护线程）：解释期间的 hook future join() 只阻塞本池线程，不占业务线程。 */
    private static final ExecutorService INTERPRETER_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "workflow-script-interpreter");
        t.setDaemon(true);
        return t;
    });

    /** 已编译的语句序列（构造时完成词法/语法分析，等价 CC parseScript 的 new AsyncFunction 编译）。 */
    private final List<Stmt> program;
    /** 原始函数体（剥离 meta 后），日志 / 调试用。 */
    private final String body;

    /**
     * 编译脚本函数体。
     *
     * @param body 剥离 {@code export const meta = {...}} 后的脚本函数体（parser extractMeta 产物）
     * @throws ScriptError 子集外构造（词法/语法错误）时抛出（fail loud）
     */
    public RestrictedScriptExecutor(String body) {
        this.body = body;
        this.program = new Parser(new Lexer(body).tokenize()).parseProgram();
        if (log.isDebugEnabled()) {
            log.debug("RestrictedScriptExecutor 编译完成：body 长度={}，语句数={}（受限 DSL 子集）",
                    body.length(), program.size());
        }
    }

    /**
     * 执行脚本 · 对齐 CC script.ts:214-227 execute（10 参绑定：6 hook + args + budget + Date/Math 沙箱）。
     *
     * @param hooks  6 个 hook 能力（agent/parallel/pipeline/phase/log/workflow，位置 1-6）
     * @param args   调用参数（位置 7，Workflow tool input 透传）
     * @param budget 预算对象（位置 8，CC ctx.resources.budget）
     * @return 脚本返回结果（解释器线程执行；异常 → failedFuture）
     */
    @Override
    public CompletableFuture<Object> execute(WorkflowHooks hooks, Object args, Object budget) {
        if (hooks == null) {
            // 防御：WorkflowHooksImpl 必为真实对象；null 只出现在单测裸调 execute 的场景
            return CompletableFuture.failedFuture(
                    new ScriptError("RestrictedScriptExecutor.execute 需要非 null WorkflowHooks（6 hook 注入）"));
        }
        if (log.isDebugEnabled()) {
            log.debug("RestrictedScriptExecutor.execute 入口：hooks={}，args={}，budget={}（6 hook + args + budget 绑定）",
                    hooks.getClass().getSimpleName(), args, budget);
        }
        return CompletableFuture.supplyAsync(() -> {
            Interp interp = new Interp(hooks, args, budget);
            Object result = interp.run(program);
            if (log.isDebugEnabled()) {
                log.debug("RestrictedScriptExecutor.execute 完成：returnValue={}（脚本 return / 默认 null）", result);
            }
            return result;
        }, INTERPRETER_POOL);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 值模型：JS 子集值 = Java 装箱值（String/Number/Boolean/List/Map/Date/
    // CompletableFuture）+ 可调用对象 JsCallable（箭头函数 / 原生 hook 包装）
    // ═══════════════════════════════════════════════════════════════════════

    /** 可调用值：箭头函数（捕获词法作用域）或原生函数（hook / 内置 / 绑定方法）。 */
    interface JsCallable {
        Object apply(List<Object> args, Interp interp);
    }

    /** 用户箭头函数（script 内的 lambda）。bodyExpr / bodyBlock 恰一非 null。 */
    record JsArrow(List<String> params, Expr bodyExpr, BlockStmt bodyBlock, Env closure) implements JsCallable {
        @Override
        public Object apply(List<Object> args, Interp interp) {
            Env local = new Env(closure);
            for (int i = 0; i < params.size(); i++) {
                local.set(params.get(i), i < args.size() ? args.get(i) : null);
            }
            try {
                if (bodyExpr != null) {
                    return interp.eval(bodyExpr, local);
                }
                interp.execBlock(bodyBlock.stmts(), local);
                return null;
            } catch (ReturnSignal r) {
                return r.value;
            }
        }
    }

    /** 原生函数：hook 包装 / 内置（Boolean/String/Number/Object/Math/Date）/ 数组·字符串·future 绑定方法。 */
    record JsNative(String name, NativeFn fn) implements JsCallable {
        @Override
        public Object apply(List<Object> args, Interp interp) {
            return fn.call(args, interp);
        }
    }

    /** JsNative 的 Java 实现体。 */
    interface NativeFn {
        Object call(List<Object> args, Interp interp);
    }

    /** 缺失标识符哨兵（区别于「值为 null」）。 */
    static final Object MISSING = new Object();

    /** JS undefined 语义在受限模型中映射为 null；仅保留此常量用于布尔语境说明。 */
    static final Object UNDEFINED = null;

    // ═══════════════════════════════════════════════════════════════════════
    // 词法（对齐 JS 语法子集）
    // ═══════════════════════════════════════════════════════════════════════

    private enum TokenType {
        IDENT, NUMBER, STRING, TEMPLATE, EOF,
        CONST, LET, VAR, RETURN, IF, ELSE, WHILE, FOR, OF, IN, CONTINUE, BREAK,
        NEW, TYPEOF, TRUE, FALSE, NULL, UNDEFINED, AWAIT, ASYNC, FUNCTION, CLASS,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA, SEMI, DOT, OPT_CHAIN,
        COLON, QUESTION, ARROW, SPREAD,
        ASSIGN, EQ_EQ, EQ_EQ_EQ, NEQ, NEQ_EQ, LT, GT, LE, GE,
        PLUS, MINUS, STAR, SLASH, PERCENT, AMP_AMP, PIPE_PIPE, BANG, INC, DEC
    }

    private record Token(TokenType type, String text, int pos) {
    }

    private static final Map<String, TokenType> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("const", TokenType.CONST);
        KEYWORDS.put("let", TokenType.LET);
        KEYWORDS.put("var", TokenType.VAR);
        KEYWORDS.put("return", TokenType.RETURN);
        KEYWORDS.put("if", TokenType.IF);
        KEYWORDS.put("else", TokenType.ELSE);
        KEYWORDS.put("while", TokenType.WHILE);
        KEYWORDS.put("for", TokenType.FOR);
        KEYWORDS.put("of", TokenType.OF);
        KEYWORDS.put("in", TokenType.IN);
        KEYWORDS.put("continue", TokenType.CONTINUE);
        KEYWORDS.put("break", TokenType.BREAK);
        KEYWORDS.put("new", TokenType.NEW);
        KEYWORDS.put("typeof", TokenType.TYPEOF);
        KEYWORDS.put("true", TokenType.TRUE);
        KEYWORDS.put("false", TokenType.FALSE);
        KEYWORDS.put("null", TokenType.NULL);
        KEYWORDS.put("undefined", TokenType.UNDEFINED);
        KEYWORDS.put("await", TokenType.AWAIT);
        KEYWORDS.put("async", TokenType.ASYNC);
        KEYWORDS.put("function", TokenType.FUNCTION);
        KEYWORDS.put("class", TokenType.CLASS);
    }

    /** 词法器 · 支持注释 / 字符串 / 模板（backtick 整体采集）/ 数字 / 运算符。 */
    private static final class Lexer {
        private final String src;
        private int pos;

        Lexer(String src) {
            this.src = src;
        }

        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (isJsWhitespace(c)) {
                    pos++;
                    continue;
                }
                if (c == '/' && peek(1) == '/') {
                    skipLineComment();
                    continue;
                }
                if (c == '/' && peek(1) == '*') {
                    skipBlockComment();
                    continue;
                }
                if (c == '`') {
                    out.add(scanTemplate());
                    continue;
                }
                if (c == '"' || c == '\'') {
                    out.add(scanString(c));
                    continue;
                }
                if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
                    out.add(scanNumber());
                    continue;
                }
                if (isIdentStart(c)) {
                    out.add(scanIdent());
                    continue;
                }
                out.add(scanOp());
            }
            out.add(new Token(TokenType.EOF, "", pos));
            return out;
        }

        private char peek(int off) {
            return pos + off < src.length() ? src.charAt(pos + off) : '\0';
        }

        private void skipLineComment() {
            while (pos < src.length() && src.charAt(pos) != '\n') {
                pos++;
            }
        }

        private void skipBlockComment() {
            pos += 2;
            while (pos + 1 < src.length() && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                pos++;
            }
            pos = Math.min(pos + 2, src.length());
        }

        private Token scanIdent() {
            int start = pos;
            while (pos < src.length() && isIdentPart(src.charAt(pos))) {
                pos++;
            }
            String word = src.substring(start, pos);
            TokenType kw = KEYWORDS.get(word);
            return new Token(kw != null ? kw : TokenType.IDENT, word, start);
        }

        private Token scanNumber() {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.'
                    || src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
            }
            return new Token(TokenType.NUMBER, src.substring(start, pos), start);
        }

        private Token scanString(char quote) {
            int start = pos;
            pos++; // 开引号
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == quote) {
                    pos++;
                    return new Token(TokenType.STRING, sb.toString(), start);
                }
                if (c == '\\') {
                    pos++;
                    if (pos >= src.length()) {
                        break;
                    }
                    char e = src.charAt(pos);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'v' -> sb.append('');
                        case '0' -> sb.append('\0');
                        case '\\' -> sb.append('\\');
                        case '\'' -> sb.append('\'');
                        case '"' -> sb.append('"');
                        case '`' -> sb.append('`');
                        default -> sb.append(e);
                    }
                    pos++;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            throw new ScriptError("unterminated string literal at index " + start);
        }

        /** 模板整体采集（含 ${...} 原样保留），parser 再按 ${...} 拆分并子解析表达式。 */
        private Token scanTemplate() {
            int start = pos;
            pos++; // `
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\\') {
                    pos += 2;
                    continue;
                }
                if (c == '`') {
                    pos++;
                    return new Token(TokenType.TEMPLATE, sb.toString(), start);
                }
                sb.append(c);
                pos++;
            }
            throw new ScriptError("unterminated template literal at index " + start);
        }

        private Token scanOp() {
            int start = pos;
            char c = src.charAt(pos);
            if (src.startsWith("...", pos)) {
                pos += 3;
                return new Token(TokenType.SPREAD, "...", start);
            }
            // 三字符运算符须先于二字符匹配（=== / !==，二字符前缀会误切 == + =）
            String three = pos + 2 < src.length() ? src.substring(pos, pos + 3) : "";
            switch (three) {
                case "===" -> { pos += 3; return new Token(TokenType.EQ_EQ_EQ, three, start); }
                case "!==" -> { pos += 3; return new Token(TokenType.NEQ_EQ, three, start); }
                default -> {
                }
            }
            String two = pos + 1 < src.length() ? src.substring(pos, pos + 2) : "";
            switch (two) {
                case "==" -> { pos += 2; return new Token(TokenType.EQ_EQ, two, start); }
                case "!=" -> { pos += 2; return new Token(TokenType.NEQ, two, start); }
                case "<=" -> { pos += 2; return new Token(TokenType.LE, two, start); }
                case ">=" -> { pos += 2; return new Token(TokenType.GE, two, start); }
                case "&&" -> { pos += 2; return new Token(TokenType.AMP_AMP, two, start); }
                case "||" -> { pos += 2; return new Token(TokenType.PIPE_PIPE, two, start); }
                case "++" -> { pos += 2; return new Token(TokenType.INC, two, start); }
                case "--" -> { pos += 2; return new Token(TokenType.DEC, two, start); }
                case "=>" -> { pos += 2; return new Token(TokenType.ARROW, two, start); }
                case "?." -> { pos += 2; return new Token(TokenType.OPT_CHAIN, two, start); }
                default -> {
                }
            }
            pos++;
            return switch (c) {
                case '(' -> new Token(TokenType.LPAREN, "(", start);
                case ')' -> new Token(TokenType.RPAREN, ")", start);
                case '{' -> new Token(TokenType.LBRACE, "{", start);
                case '}' -> new Token(TokenType.RBRACE, "}", start);
                case '[' -> new Token(TokenType.LBRACKET, "[", start);
                case ']' -> new Token(TokenType.RBRACKET, "]", start);
                case ',' -> new Token(TokenType.COMMA, ",", start);
                case ';' -> new Token(TokenType.SEMI, ";", start);
                case '.' -> new Token(TokenType.DOT, ".", start);
                case ':' -> new Token(TokenType.COLON, ":", start);
                case '?' -> new Token(TokenType.QUESTION, "?", start);
                case '=' -> new Token(TokenType.ASSIGN, "=", start);
                case '<' -> new Token(TokenType.LT, "<", start);
                case '>' -> new Token(TokenType.GT, ">", start);
                case '+' -> new Token(TokenType.PLUS, "+", start);
                case '-' -> new Token(TokenType.MINUS, "-", start);
                case '*' -> new Token(TokenType.STAR, "*", start);
                case '/' -> new Token(TokenType.SLASH, "/", start);
                case '%' -> new Token(TokenType.PERCENT, "%", start);
                case '!' -> new Token(TokenType.BANG, "!", start);
                default -> throw new ScriptError("unexpected character '" + c + "' at index " + start);
            };
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AST（JS 子集）
    // ═══════════════════════════════════════════════════════════════════════

    sealed interface Stmt permits VarDecl, AssignStmt, ExprStmt, ReturnStmt, IfStmt, WhileStmt,
            ForOfStmt, BlockStmt, ContinueStmt, BreakStmt, EmptyStmt {
    }

    record VarDecl(String name, Expr init, boolean mutable) implements Stmt {
    }

    record AssignStmt(Expr target, Expr value) implements Stmt {
    }

    record ExprStmt(Expr expr) implements Stmt {
    }

    record ReturnStmt(Expr value) implements Stmt {
    }

    record IfStmt(Expr cond, Stmt then, Stmt els) implements Stmt {
    }

    record WhileStmt(Expr cond, Stmt body) implements Stmt {
    }

    record ForOfStmt(String var, Expr iterable, Stmt body) implements Stmt {
    }

    record BlockStmt(List<Stmt> stmts) implements Stmt {
    }

    record ContinueStmt() implements Stmt {
    }

    record BreakStmt() implements Stmt {
    }

    record EmptyStmt() implements Stmt {
    }

    sealed interface Expr permits Literal, Ident, MemberExpr, CallExpr, AwaitExpr, ArrowExpr,
            ObjectLit, ArrayLit, UnaryExpr, BinaryExpr, LogicalExpr, CondExpr, AssignExpr,
            UpdateExpr, TemplateExpr, NewExpr, SpreadExpr {
    }

    record Literal(Object value) implements Expr {
    }

    record Ident(String name) implements Expr {
    }

    /** computed != null → obj[computed]；否则 obj.prop。 */
    record MemberExpr(Expr obj, String prop, Expr computed) implements Expr {
    }

    record CallExpr(Expr callee, List<Expr> args) implements Expr {
    }

    record AwaitExpr(Expr inner) implements Expr {
    }

    /** bodyExpr / bodyBlock 恰一非 null。 */
    record ArrowExpr(List<String> params, Expr bodyExpr, BlockStmt bodyBlock) implements Expr {
    }

    sealed interface ObjectLitEntry permits KeyValue, SpreadEntry {
    }

    record KeyValue(String key, Expr value) implements ObjectLitEntry {
    }

    record SpreadEntry(Expr inner) implements ObjectLitEntry {
    }

    record ObjectLit(List<ObjectLitEntry> entries) implements Expr {
    }

    record ArrayLit(List<Expr> elems) implements Expr {
    }

    record UnaryExpr(String op, Expr operand) implements Expr {
    }

    record BinaryExpr(String op, Expr left, Expr right) implements Expr {
    }

    record LogicalExpr(String op, Expr left, Expr right) implements Expr {
    }

    record CondExpr(Expr cond, Expr t, Expr f) implements Expr {
    }

    /** target 为 Ident 或 MemberExpr。 */
    record AssignExpr(Expr target, Expr value) implements Expr {
    }

    /** ++/--；prefix=false 时返回旧值，prefix=true 返回新值。 */
    record UpdateExpr(String op, Expr target, boolean prefix) implements Expr {
    }

    record TemplatePart(String text, Expr expr) {
    }

    record TemplateExpr(List<TemplatePart> parts) implements Expr {
    }

    /** 受限模型仅支持 new Date(...)。 */
    record NewExpr(Expr callee, List<Expr> args) implements Expr {
    }

    record SpreadExpr(Expr inner) implements Expr {
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 语法分析（递归下降）
    // ═══════════════════════════════════════════════════════════════════════

    private static final class Parser {
        private final List<Token> tokens;
        private int idx;
        private int loopDepth;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        // ── 语句 ──

        List<Stmt> parseProgram() {
            List<Stmt> out = new ArrayList<>();
            while (!check(TokenType.EOF)) {
                if (match(TokenType.SEMI)) {
                    continue;
                }
                out.add(parseStatement());
            }
            return out;
        }

        private Stmt parseStatement() {
            if (check(TokenType.LBRACE)) {
                return new BlockStmt(parseBlock());
            }
            if (match(TokenType.CONST, TokenType.LET, TokenType.VAR)) {
                boolean mutable = previous().type() != TokenType.CONST;
                String name = expectIdent("variable name");
                Expr init = null;
                if (match(TokenType.ASSIGN)) {
                    init = parseExpression();
                }
                expectSemicolon();
                if (check(TokenType.COMMA)) {
                    throw err("`const a = 1, b = 2` 多声明符超出受限 DSL 子集（每行一个声明）");
                }
                return new VarDecl(name, init, mutable);
            }
            if (match(TokenType.RETURN)) {
                Expr value = check(TokenType.SEMI) || check(TokenType.EOF) || check(TokenType.RBRACE)
                        ? null : parseExpression();
                expectSemicolon();
                return new ReturnStmt(value);
            }
            if (match(TokenType.IF)) {
                expect(TokenType.LPAREN, "if (");
                Expr cond = parseExpression();
                expect(TokenType.RPAREN, ")");
                Stmt then = parseStatement();
                Stmt els = null;
                if (match(TokenType.ELSE)) {
                    els = parseStatement();
                }
                return new IfStmt(cond, then, els);
            }
            if (match(TokenType.WHILE)) {
                expect(TokenType.LPAREN, "while (");
                Expr cond = parseExpression();
                expect(TokenType.RPAREN, ")");
                loopDepth++;
                try {
                    return new WhileStmt(cond, parseStatement());
                } finally {
                    loopDepth--;
                }
            }
            if (match(TokenType.FOR)) {
                expect(TokenType.LPAREN, "for (");
                boolean isOf = check(TokenType.CONST, TokenType.LET, TokenType.VAR);
                if (isOf) {
                    advance();
                    String var = expectIdent("for-of variable");
                    if (!match(TokenType.OF)) {
                        throw err("仅支持 for...of 循环（受限 DSL 子集）；C 风格 for(;;) 不支持");
                    }
                    Expr iterable = parseExpression();
                    expect(TokenType.RPAREN, ")");
                    loopDepth++;
                    try {
                        return new ForOfStmt(var, iterable, parseStatement());
                    } finally {
                        loopDepth--;
                    }
                }
                throw err("仅支持 for...of 循环（受限 DSL 子集）；for(init;cond;update) 不支持");
            }
            if (match(TokenType.CONTINUE)) {
                if (loopDepth == 0) {
                    throw err("continue outside of loop");
                }
                expectSemicolon();
                return new ContinueStmt();
            }
            if (match(TokenType.BREAK)) {
                if (loopDepth == 0) {
                    throw err("break outside of loop");
                }
                expectSemicolon();
                return new BreakStmt();
            }
            if (match(TokenType.SEMI)) {
                return new EmptyStmt();
            }
            if (check(TokenType.FUNCTION, TokenType.CLASS)) {
                throw err("`function`/`class` 声明超出受限 DSL 子集；请用箭头函数 `(x) => ...`");
            }
            Expr expr = parseExpression();
            expectSemicolon();
            if (expr instanceof AssignExpr a) {
                return new AssignStmt(a.target(), a.value());
            }
            return new ExprStmt(expr);
        }

        private List<Stmt> parseBlock() {
            expect(TokenType.LBRACE, "{");
            List<Stmt> out = new ArrayList<>();
            while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                if (match(TokenType.SEMI)) {
                    continue;
                }
                out.add(parseStatement());
            }
            expect(TokenType.RBRACE, "}");
            return out;
        }

        private void expectSemicolon() {
            if (match(TokenType.SEMI)) {
                return;
            }
            // ASI 语义（受限 DSL）：省略分号 = 换行即语句边界（CC canonical 脚本不写分号）。
            // 仅当下一 token 明显是悬空表达式延续（运算符/括号关闭符/逗号等）才报错。
            if (blockerAfterStatement()) {
                throw err("expected ';' but got " + peekText());
            }
        }

        private boolean blockerAfterStatement() {
            return switch (tokens.get(idx).type()) {
                case RPAREN, RBRACKET, COMMA, COLON, QUESTION, ARROW, DOT, OPT_CHAIN,
                        ASSIGN, EQ_EQ, EQ_EQ_EQ, NEQ, NEQ_EQ, LT, GT, LE, GE,
                        PLUS, MINUS, STAR, SLASH, PERCENT, AMP_AMP, PIPE_PIPE, SPREAD -> true;
                default -> false;
            };
        }

        // ── 表达式（优先级爬升）──

        private Expr parseExpression() {
            return parseAssignment();
        }

        private Expr parseAssignment() {
            Expr left = parseConditional();
            if (match(TokenType.ASSIGN)) {
                if (!(left instanceof Ident) && !(left instanceof MemberExpr)) {
                    throw err("invalid assignment target");
                }
                Expr value = parseAssignment();
                return new AssignExpr(left, value);
            }
            return left;
        }

        private Expr parseConditional() {
            Expr cond = parseLogicalOr();
            if (match(TokenType.QUESTION)) {
                Expr t = parseExpression();
                expect(TokenType.COLON, ":");
                Expr f = parseConditional();
                return new CondExpr(cond, t, f);
            }
            return cond;
        }

        private Expr parseLogicalOr() {
            Expr left = parseLogicalAnd();
            while (match(TokenType.PIPE_PIPE)) {
                left = new LogicalExpr("||", left, parseLogicalAnd());
            }
            return left;
        }

        private Expr parseLogicalAnd() {
            Expr left = parseEquality();
            while (match(TokenType.AMP_AMP)) {
                left = new LogicalExpr("&&", left, parseEquality());
            }
            return left;
        }

        private Expr parseEquality() {
            Expr left = parseRelational();
            while (true) {
                if (match(TokenType.EQ_EQ_EQ, TokenType.EQ_EQ)) {
                    left = new BinaryExpr("===", left, parseRelational());
                } else if (match(TokenType.NEQ_EQ, TokenType.NEQ)) {
                    left = new BinaryExpr("!==", left, parseRelational());
                } else {
                    return left;
                }
            }
        }

        private Expr parseRelational() {
            Expr left = parseAdditive();
            while (true) {
                if (match(TokenType.LT)) {
                    left = new BinaryExpr("<", left, parseAdditive());
                } else if (match(TokenType.GT)) {
                    left = new BinaryExpr(">", left, parseAdditive());
                } else if (match(TokenType.LE)) {
                    left = new BinaryExpr("<=", left, parseAdditive());
                } else if (match(TokenType.GE)) {
                    left = new BinaryExpr(">=", left, parseAdditive());
                } else {
                    return left;
                }
            }
        }

        private Expr parseAdditive() {
            Expr left = parseMultiplicative();
            while (true) {
                if (match(TokenType.PLUS)) {
                    left = new BinaryExpr("+", left, parseMultiplicative());
                } else if (match(TokenType.MINUS)) {
                    left = new BinaryExpr("-", left, parseMultiplicative());
                } else {
                    return left;
                }
            }
        }

        private Expr parseMultiplicative() {
            Expr left = parseUnary();
            while (true) {
                if (match(TokenType.STAR)) {
                    left = new BinaryExpr("*", left, parseUnary());
                } else if (match(TokenType.SLASH)) {
                    left = new BinaryExpr("/", left, parseUnary());
                } else if (match(TokenType.PERCENT)) {
                    left = new BinaryExpr("%", left, parseUnary());
                } else {
                    return left;
                }
            }
        }

        private Expr parseUnary() {
            if (match(TokenType.BANG)) {
                return new UnaryExpr("!", parseUnary());
            }
            if (match(TokenType.MINUS)) {
                return new UnaryExpr("-", parseUnary());
            }
            if (match(TokenType.PLUS)) {
                return new UnaryExpr("+", parseUnary());
            }
            if (match(TokenType.TYPEOF)) {
                return new UnaryExpr("typeof", parseUnary());
            }
            if (match(TokenType.INC)) {
                return new UpdateExpr("++", parseUnaryTarget(), true);
            }
            if (match(TokenType.DEC)) {
                return new UpdateExpr("--", parseUnaryTarget(), true);
            }
            if (match(TokenType.AWAIT)) {
                return new AwaitExpr(parseUnary());
            }
            Expr postfix = parseCallMember();
            if (match(TokenType.INC)) {
                return new UpdateExpr("++", postfix, false);
            }
            if (match(TokenType.DEC)) {
                return new UpdateExpr("--", postfix, false);
            }
            return postfix;
        }

        private Expr parseUnaryTarget() {
            Expr e = parseCallMember();
            if (!(e instanceof Ident) && !(e instanceof MemberExpr)) {
                throw err("++/-- requires a variable or member target");
            }
            return e;
        }

        private Expr parseCallMember() {
            Expr expr = parsePrimary();
            while (true) {
                if (match(TokenType.DOT, TokenType.OPT_CHAIN)) {
                    String prop = expectIdent("property name");
                    expr = new MemberExpr(expr, prop, null);
                } else if (match(TokenType.LBRACKET)) {
                    Expr computed = parseExpression();
                    expect(TokenType.RBRACKET, "]");
                    expr = new MemberExpr(expr, null, computed);
                } else if (match(TokenType.LPAREN)) {
                    expr = new CallExpr(expr, parseCallArgs());
                } else {
                    return expr;
                }
            }
        }

        private List<Expr> parseCallArgs() {
            List<Expr> out = new ArrayList<>();
            if (!check(TokenType.RPAREN)) {
                do {
                    if (match(TokenType.SPREAD)) {
                        out.add(new SpreadExpr(parseExpression()));
                    } else {
                        out.add(parseExpression());
                    }
                } while (match(TokenType.COMMA) && !check(TokenType.RPAREN));
            }
            expect(TokenType.RPAREN, ")");
            return out;
        }

        private Expr parsePrimary() {
            if (match(TokenType.LPAREN)) {
                // `(a, b) => ...` 箭头 vs `(expr)` 分组：扫描到配对 ')' 后是否跟 '=>'
                if (looksLikeArrowParams()) {
                    List<String> params = parseArrowParamsAfterOpenParen();
                    expect(TokenType.ARROW, "=>");
                    return buildArrow(params);
                }
                Expr inner = parseExpression();
                expect(TokenType.RPAREN, ")");
                return inner;
            }
            if (match(TokenType.LBRACKET)) {
                return parseArrayLiteral();
            }
            if (match(TokenType.LBRACE)) {
                return parseObjectLiteral();
            }
            if (match(TokenType.NUMBER)) {
                return new Literal(parseNumber(previous().text()));
            }
            if (match(TokenType.STRING)) {
                return new Literal(previous().text());
            }
            if (match(TokenType.TEMPLATE)) {
                return parseTemplate(previous().text());
            }
            if (match(TokenType.TRUE)) {
                return new Literal(Boolean.TRUE);
            }
            if (match(TokenType.FALSE)) {
                return new Literal(Boolean.FALSE);
            }
            if (match(TokenType.NULL)) {
                return new Literal(null);
            }
            if (match(TokenType.UNDEFINED)) {
                return new Literal(null);
            }
            if (match(TokenType.NEW)) {
                String callee = expectIdent("constructor name");
                if (!"Date".equals(callee)) {
                    throw err("new 仅支持 Date（受限 DSL 子集）；`new " + callee + "` 不支持");
                }
                expect(TokenType.LPAREN, "(");
                List<Expr> args = parseCallArgs();
                return new NewExpr(new Ident("Date"), args);
            }
            if (match(TokenType.ASYNC)) {
                // async (x) => ... 等价于 (x) => ...（受限模型无显式 async 状态）
                if (match(TokenType.LPAREN)) {
                    if (looksLikeArrowParams()) {
                        List<String> params = parseArrowParamsAfterOpenParen();
                        expect(TokenType.ARROW, "=>");
                        return buildArrow(params);
                    }
                    throw err("async 后必须接箭头函数（受限 DSL 子集）");
                }
                String single = expectIdent("async arrow param");
                expect(TokenType.ARROW, "=>");
                return buildArrow(List.of(single));
            }
            if (match(TokenType.IDENT)) {
                String name = previous().text();
                if (match(TokenType.ARROW)) {
                    return buildArrow(List.of(name));
                }
                return new Ident(name);
            }
            throw err("unexpected token " + peekText() + " in expression");
        }

        /** 从左括号当前位置（已消费 '('）扫描：到配对 ')' 后是否跟 '=>'。 */
        private boolean looksLikeArrowParams() {
            int save = idx;
            int depth = 1;
            while (idx < tokens.size()) {
                Token t = tokens.get(idx);
                if (t.type() == TokenType.LPAREN) {
                    depth++;
                } else if (t.type() == TokenType.RPAREN) {
                    depth--;
                    if (depth == 0) {
                        idx++;
                        boolean arrow = check(TokenType.ARROW);
                        idx = save;
                        return arrow;
                    }
                } else if (t.type() == TokenType.EOF) {
                    break;
                }
                idx++;
            }
            idx = save;
            return false;
        }

        /** 已消费 '('：解析 (a, b) 参数表直到 ')'（调用前 looksLikeArrowParams 已确认为箭头）。 */
        private List<String> parseArrowParamsAfterOpenParen() {
            List<String> params = new ArrayList<>();
            if (!check(TokenType.RPAREN)) {
                do {
                    if (check(TokenType.SPREAD)) {
                        throw err("rest 参数超出受限 DSL 子集");
                    }
                    params.add(expectIdent("arrow param"));
                } while (match(TokenType.COMMA));
            }
            expect(TokenType.RPAREN, ")");
            return params;
        }

        private Expr buildArrow(List<String> params) {
            if (match(TokenType.LBRACE)) {
                // match 已消费 '{'；读取块体直到配对的 '}'
                List<Stmt> stmts = parseBlockBodyAfterOpenBrace();
                return new ArrowExpr(params, null, new BlockStmt(stmts));
            }
            return new ArrowExpr(params, parseExpression(), null);
        }

        private List<Stmt> parseBlockBodyAfterOpenBrace() {
            // 已消费 '{'（parsePrimary 的 match(LBRACE) 分支走 parseObjectLiteral；buildArrow 分支需读块）
            List<Stmt> out = new ArrayList<>();
            while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                if (match(TokenType.SEMI)) {
                    continue;
                }
                out.add(parseStatement());
            }
            expect(TokenType.RBRACE, "}");
            return out;
        }

        private Expr parseArrayLiteral() {
            List<Expr> elems = new ArrayList<>();
            if (!check(TokenType.RBRACKET)) {
                do {
                    if (match(TokenType.SPREAD)) {
                        elems.add(new SpreadExpr(parseExpression()));
                    } else {
                        elems.add(parseExpression());
                    }
                } while (match(TokenType.COMMA) && !check(TokenType.RBRACKET));
            }
            expect(TokenType.RBRACKET, "]");
            return new ArrayLit(elems);
        }

        private Expr parseObjectLiteral() {
            List<ObjectLitEntry> entries = new ArrayList<>();
            if (!check(TokenType.RBRACE)) {
                do {
                    if (match(TokenType.SPREAD)) {
                        entries.add(new SpreadEntry(parseExpression()));
                    } else {
                        String key = parseObjectKey();
                        if (match(TokenType.COLON)) {
                            entries.add(new KeyValue(key, parseExpression()));
                        } else {
                            // shorthand `{ confirmed }` → { confirmed: confirmed }
                            entries.add(new KeyValue(key, new Ident(key)));
                        }
                    }
                } while (match(TokenType.COMMA) && !check(TokenType.RBRACE));
            }
            expect(TokenType.RBRACE, "}");
            return new ObjectLit(entries);
        }

        private String parseObjectKey() {
            if (match(TokenType.STRING, TokenType.NUMBER)) {
                return previous().text();
            }
            return expectIdent("object key");
        }

        /** 模板 ${...} 拆分：brace 感知；每个 ${expr} 子串用独立 Lexer+Parser 解析单表达式。 */
        private Expr parseTemplate(String raw) {
            List<TemplatePart> parts = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            int i = 0;
            while (i < raw.length()) {
                int dollar = raw.indexOf("${", i);
                if (dollar < 0) {
                    text.append(raw, i, raw.length());
                    i = raw.length();
                    break;
                }
                text.append(raw, i, dollar);
                // 定位配对 '}'
                int j = dollar + 2;
                int depth = 1;
                while (j < raw.length()) {
                    char c = raw.charAt(j);
                    if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                    j++;
                }
                if (j >= raw.length()) {
                    throw new ScriptError("unterminated ${...} in template literal");
                }
                String exprSrc = raw.substring(dollar + 2, j);
                Expr expr = new Parser(new Lexer(exprSrc).tokenize()).parseSingleExpression();
                parts.add(new TemplatePart(text.toString(), expr));
                text.setLength(0);
                i = j + 1;
            }
            if (text.length() > 0 || parts.isEmpty()) {
                parts.add(new TemplatePart(text.toString(), null));
            }
            return new TemplateExpr(parts);
        }

        private Expr parseSingleExpression() {
            Expr e = parseExpression();
            if (!check(TokenType.EOF)) {
                throw new ScriptError("template expression has trailing content: " + peekText());
            }
            return e;
        }

        // ── 工具 ──

        private boolean check(TokenType... types) {
            Token t = tokens.get(idx);
            for (TokenType tt : types) {
                if (t.type() == tt) {
                    return true;
                }
            }
            return false;
        }

        private boolean match(TokenType... types) {
            if (check(types)) {
                idx++;
                return true;
            }
            return false;
        }

        private Token previous() {
            return tokens.get(idx - 1);
        }

        private Token expect(TokenType type, String what) {
            if (check(type)) {
                return tokens.get(idx++);
            }
            throw err("expected " + what + " but got " + peekText());
        }

        private String expectIdent(String what) {
            if (check(TokenType.IDENT)) {
                return tokens.get(idx++).text();
            }
            throw err("expected " + what + " but got " + peekText());
        }

        private void advance() {
            idx++;
        }

        private String peekText() {
            Token t = tokens.get(idx);
            return t.type() == TokenType.EOF ? "<EOF>" : "'" + t.text() + "'";
        }

        private ScriptError err(String message) {
            Token t = tokens.get(Math.min(idx, tokens.size() - 1));
            return new ScriptError(message + " at index " + t.pos()
                    + " [受限脚本解释器 RestrictedScriptExecutor：JS 子集 DSL，非全 JS 引擎]");
        }

        private static Number parseNumber(String text) {
            if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 作用域
    // ═══════════════════════════════════════════════════════════════════════

    static final class Env {
        private final Map<String, Object> vars = new LinkedHashMap<>();
        private final Env parent;

        Env(Env parent) {
            this.parent = parent;
        }

        Object getOrMissing(String name) {
            if (vars.containsKey(name)) {
                return vars.get(name);
            }
            return parent != null ? parent.getOrMissing(name) : MISSING;
        }

        void set(String name, Object value) {
            vars.put(name, value);
        }

        /** 已存在则更新，否则新建（JS 赋值语义）。 */
        void assign(String name, Object value) {
            if (vars.containsKey(name) || parent == null) {
                vars.put(name, value);
                return;
            }
            if (parent.getOrMissing(name) != MISSING) {
                parent.assign(name, value);
                return;
            }
            vars.put(name, value);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 控制流信号（返回 / break / continue）
    // ═══════════════════════════════════════════════════════════════════════

    static final class ReturnSignal extends RuntimeException {
        final Object value;

        ReturnSignal(Object value) {
            this.value = value;
        }
    }

    static final class BreakSignal extends RuntimeException {
    }

    static final class ContinueSignal extends RuntimeException {
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 解释器
    // ═══════════════════════════════════════════════════════════════════════

    static final class Interp {
        private final WorkflowHooks hooks;
        private final Env global;

        Interp(WorkflowHooks hooks, Object args, Object budget) {
            this.hooks = hooks;
            this.global = new Env(null);
            seedGlobals(args, budget);
        }

        Object run(List<Stmt> program) {
            try {
                execBlock(program, global);
                return null;
            } catch (ReturnSignal r) {
                return r.value;
            }
        }

        // ── 语句执行 ──

        void execBlock(List<Stmt> stmts, Env env) {
            for (Stmt s : stmts) {
                execStmt(s, env);
            }
        }

        void execStmt(Stmt stmt, Env env) {
            switch (stmt) {
                case VarDecl v -> {
                    Object val = v.init() != null ? eval(v.init(), env) : null;
                    env.set(v.name(), val);
                }
                case AssignStmt a -> assignTarget(a.target(), eval(a.value(), env), env);
                case ExprStmt e -> eval(e.expr(), env);
                case ReturnStmt r -> throw new ReturnSignal(r.value() != null ? eval(r.value(), env) : null);
                case IfStmt i -> {
                    if (truthy(eval(i.cond(), env))) {
                        execStmt(i.then(), env);
                    } else if (i.els() != null) {
                        execStmt(i.els(), env);
                    }
                }
                case WhileStmt w -> {
                    while (truthy(eval(w.cond(), env))) {
                        try {
                            execStmt(w.body(), env);
                        } catch (ContinueSignal c) {
                            // 继续下一轮
                        } catch (BreakSignal b) {
                            break;
                        }
                    }
                }
                case ForOfStmt f -> {
                    Object iterable = eval(f.iterable(), env);
                    if (!(iterable instanceof List<?> list)) {
                        throw new ScriptError("for...of iterable must be an array");
                    }
                    for (Object item : list) {
                        Env loopEnv = env;
                        loopEnv.set(f.var(), item);
                        try {
                            execStmt(f.body(), loopEnv);
                        } catch (ContinueSignal c) {
                            // 继续
                        } catch (BreakSignal b) {
                            break;
                        }
                    }
                }
                case BlockStmt b -> execBlock(b.stmts(), env);
                case ContinueStmt c -> throw new ContinueSignal();
                case BreakStmt b -> throw new BreakSignal();
                case EmptyStmt e -> {
                }
            }
        }

        // ── 表达式求值 ──

        Object eval(Expr expr, Env env) {
            switch (expr) {
                case Literal l -> {
                    return l.value();
                }
                case Ident id -> {
                    Object v = env.getOrMissing(id.name());
                    if (v == MISSING) {
                        throw new ScriptError("undefined variable '" + id.name() + "'");
                    }
                    return v;
                }
                case MemberExpr m -> {
                    Object base = eval(m.obj(), env);
                    return resolveMember(base, m, env);
                }
                case CallExpr c -> {
                    Object callee = eval(c.callee(), env);
                    return invokeCallable(callee, evalArgs(c.args(), env));
                }
                case AwaitExpr a -> {
                    Object v = eval(a.inner(), env);
                    return unwrapFuture(v);
                }
                case ArrowExpr a -> {
                    return new JsArrow(a.params(), a.bodyExpr(), a.bodyBlock(), env);
                }
                case ObjectLit o -> {
                    return buildObject(o, env);
                }
                case ArrayLit a -> {
                    return buildArray(a, env);
                }
                case UnaryExpr u -> {
                    return evalUnary(u, env);
                }
                case BinaryExpr b -> {
                    return evalBinary(b, env);
                }
                case LogicalExpr l -> {
                    return evalLogical(l, env);
                }
                case CondExpr c -> {
                    return truthy(eval(c.cond(), env)) ? eval(c.t(), env) : eval(c.f(), env);
                }
                case AssignExpr a -> {
                    Object value = eval(a.value(), env);
                    assignTarget(a.target(), value, env);
                    return value;
                }
                case UpdateExpr u -> {
                    return evalUpdate(u, env);
                }
                case TemplateExpr t -> {
                    return evalTemplate(t, env);
                }
                case NewExpr n -> {
                    return evalNew(n, env);
                }
                case SpreadExpr s -> {
                    throw new ScriptError("spread is only allowed inside array/object/call arguments");
                }
            }
        }

        private Object evalUnary(UnaryExpr u, Env env) {
            Object v = eval(u.operand(), env);
            switch (u.op()) {
                case "!" -> {
                    return !truthy(v);
                }
                case "-" -> {
                    Object n = toNumber(v);
                    if (n instanceof Double d) {
                        return -d;
                    }
                    return -((Number) n).longValue();
                }
                case "+" -> {
                    return toNumber(v);
                }
                case "typeof" -> {
                    return typeOf(v);
                }
                default -> throw new ScriptError("unsupported unary operator '" + u.op() + "'");
            }
        }

        private Object evalBinary(BinaryExpr b, Env env) {
            Object l = eval(b.left(), env);
            Object r = eval(b.right(), env);
            return switch (b.op()) {
                case "+" -> jsPlus(l, r);
                case "-" -> numericOp("-", l, r);
                case "*" -> numericOp("*", l, r);
                case "/" -> (toNumber(l)).doubleValue() / (toNumber(r)).doubleValue();
                case "%" -> (toNumber(l)).doubleValue() % (toNumber(r)).doubleValue();
                case "===", "==" -> jsEquals(l, r);
                case "!==", "!=" -> !jsEquals(l, r);
                case "<" -> compare(l, r) < 0;
                case ">" -> compare(l, r) > 0;
                case "<=" -> compare(l, r) <= 0;
                case ">=" -> compare(l, r) >= 0;
                default -> throw new ScriptError("unsupported binary operator '" + b.op() + "'");
            };
        }

        private Object evalLogical(LogicalExpr l, Env env) {
            Object left = eval(l.left(), env);
            if ("&&".equals(l.op())) {
                return truthy(left) ? eval(l.right(), env) : left;
            }
            // ||
            return truthy(left) ? left : eval(l.right(), env);
        }

        private Object evalUpdate(UpdateExpr u, Env env) {
            Object old = readTarget(u.target(), env);
            long delta = "++".equals(u.op()) ? 1 : -1;
            Object nv;
            if (old instanceof Double d) {
                nv = d + delta;
            } else if (old instanceof Number n) {
                nv = n.longValue() + delta;
            } else {
                throw new ScriptError("++/-- requires a numeric target, got " + typeOf(old));
            }
            assignTarget(u.target(), nv, env);
            return u.prefix() ? nv : old;
        }

        private Object evalTemplate(TemplateExpr t, Env env) {
            StringBuilder sb = new StringBuilder();
            for (TemplatePart p : t.parts()) {
                sb.append(p.text());
                if (p.expr() != null) {
                    sb.append(jsString(eval(p.expr(), env)));
                }
            }
            return sb.toString();
        }

        private Object evalNew(NewExpr n, Env env) {
            if (n.args().isEmpty()) {
                // CC sandboxDate：new Date() 无参 → NonDeterministicError（script.ts:118-120）
                DateMathSandbox.rejectNewDateNoArg();
            }
            Object first = eval(n.args().get(0), env);
            return new Date(toNumber(first).longValue());
        }

        // ── 目标读写 / 成员解析 ──

        private Object readTarget(Expr target, Env env) {
            if (target instanceof Ident id) {
                Object v = env.getOrMissing(id.name());
                if (v == MISSING) {
                    throw new ScriptError("undefined variable '" + id.name() + "'");
                }
                return v;
            }
            if (target instanceof MemberExpr m) {
                Object base = eval(m.obj(), env);
                String prop = memberName(m, env);
                if (base instanceof Map<?, ?> map) {
                    return map.get(prop);
                }
                if (base instanceof List<?> list) {
                    Integer idx = asArrayIndex(prop);
                    if (idx != null) {
                        return list.get(idx);
                    }
                }
                throw new ScriptError("cannot assign member '" + prop + "' on " + typeOf(base));
            }
            throw new ScriptError("invalid assignment target");
        }

        private void assignTarget(Expr target, Object value, Env env) {
            if (target instanceof Ident id) {
                env.assign(id.name(), value);
                return;
            }
            if (target instanceof MemberExpr m) {
                Object base = eval(m.obj(), env);
                String prop = memberName(m, env);
                if (base instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m2 = (Map<String, Object>) map;
                    m2.put(prop, value);
                    return;
                }
                if (base instanceof List<?> list) {
                    Integer idx = asArrayIndex(prop);
                    if (idx != null) {
                        @SuppressWarnings("unchecked")
                        List<Object> mutable = (List<Object>) list;
                        mutable.set(idx, value);
                        return;
                    }
                }
                throw new ScriptError("cannot assign member '" + prop + "' on " + typeOf(base));
            }
            throw new ScriptError("invalid assignment target");
        }

        private String memberName(MemberExpr m, Env env) {
            if (m.computed() != null) {
                Object idx = eval(m.computed(), env);
                return jsString(idx);
            }
            return m.prop();
        }

        private Object resolveMember(Object base, MemberExpr m, Env env) {
            String prop = memberName(m, env);
            if (base == null) {
                throw new ScriptError("cannot read property '" + prop + "' of null/undefined");
            }
            if (base instanceof List<?> list) {
                Integer idx = asArrayIndex(prop);
                if (idx != null) {
                    return list.get(idx);
                }
                return arrayMember(list, prop);
            }
            if (base instanceof Map<?, ?> map) {
                if (map.containsKey(prop)) {
                    return map.get(prop);
                }
                Object method = objectMethod(map, prop);
                if (method != null) {
                    return method;
                }
                return null; // JS 对象缺属性 = undefined(null)
            }
            if (base instanceof String s) {
                return stringMember(s, prop);
            }
            if (base instanceof CompletableFuture<?> f) {
                return futureMember(f, prop);
            }
            if (base instanceof Date d) {
                return dateMember(d, prop);
            }
            throw new ScriptError("cannot read property '" + prop + "' on " + typeOf(base));
        }

        /** JS 数组下标：'0'/'1'... 或 Integer/Long/Double 整值。 */
        private Integer asArrayIndex(String prop) {
            try {
                long v = Long.parseLong(prop);
                return (int) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // ── 方法绑定（数组 / 对象 / 字符串 / future / Date）──

        private Object arrayMember(List<?> list, String prop) {
            switch (prop) {
                case "length":
                    return (long) list.size();
                case "map":
                    return new JsNative("Array.map", (a, i) -> {
                        requirePredicate(a, "map");
                        List<Object> out = new ArrayList<>();
                        for (int x = 0; x < list.size(); x++) {
                            out.add(invokeCallable(a.get(0), List.of(list.get(x), x, list)));
                        }
                        return out;
                    });
                case "filter":
                    return new JsNative("Array.filter", (a, i) -> {
                        requirePredicate(a, "filter");
                        List<Object> out = new ArrayList<>();
                        for (int x = 0; x < list.size(); x++) {
                            if (truthy(invokeCallable(a.get(0), List.of(list.get(x), x, list)))) {
                                out.add(list.get(x));
                            }
                        }
                        return out;
                    });
                case "flat":
                    return new JsNative("Array.flat", (a, i) -> {
                        List<Object> out = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof List<?> sub) {
                                out.addAll(sub);
                            } else {
                                out.add(item);
                            }
                        }
                        return out;
                    });
                case "includes":
                    return new JsNative("Array.includes", (a, i) ->
                            a.isEmpty() ? false : list.contains(a.get(0)));
                case "indexOf":
                    return new JsNative("Array.indexOf", (a, i) ->
                            (long) (a.isEmpty() ? -1 : list.indexOf(a.get(0))));
                case "push":
                    return new JsNative("Array.push", (a, i) -> {
                        @SuppressWarnings("unchecked")
                        List<Object> mutable = (List<Object>) list;
                        mutable.addAll(a);
                        return (long) mutable.size();
                    });
                case "find":
                    return new JsNative("Array.find", (a, i) -> {
                        requirePredicate(a, "find");
                        for (int x = 0; x < list.size(); x++) {
                            if (truthy(invokeCallable(a.get(0), List.of(list.get(x), x, list)))) {
                                return list.get(x);
                            }
                        }
                        return null;
                    });
                case "forEach":
                    return new JsNative("Array.forEach", (a, i) -> {
                        requirePredicate(a, "forEach");
                        for (int x = 0; x < list.size(); x++) {
                            invokeCallable(a.get(0), List.of(list.get(x), x, list));
                        }
                        return null;
                    });
                case "join":
                    return new JsNative("Array.join", (a, i) -> {
                        String sep = !a.isEmpty() && a.get(0) != null ? jsString(a.get(0)) : ",";
                        StringBuilder sb = new StringBuilder();
                        for (int x = 0; x < list.size(); x++) {
                            if (x > 0) {
                                sb.append(sep);
                            }
                            Object v = list.get(x);
                            sb.append(v == null ? "" : jsString(v));
                        }
                        return sb.toString();
                    });
                default:
                    return null; // 数组缺属性 → undefined(null)
            }
        }

        private Object objectMethod(Map<?, ?> map, String prop) {
            switch (prop) {
                case "keys":
                    return new JsNative("Object.keys", (a, i) -> new ArrayList<>(map.keySet()));
                case "values":
                    return new JsNative("Object.values", (a, i) -> new ArrayList<>(map.values()));
                case "entries":
                    return new JsNative("Object.entries", (a, i) -> {
                        List<Object> out = new ArrayList<>();
                        map.forEach((k, v) -> out.add(List.of(k, v)));
                        return out;
                    });
                default:
                    return null;
            }
        }

        private Object stringMember(String s, String prop) {
            switch (prop) {
                case "length":
                    return (long) s.length();
                case "startsWith":
                    return new JsNative("String.startsWith", (a, i) ->
                            a.isEmpty() ? false : s.startsWith(jsString(a.get(0))));
                case "endsWith":
                    return new JsNative("String.endsWith", (a, i) ->
                            a.isEmpty() ? false : s.endsWith(jsString(a.get(0))));
                case "includes":
                    return new JsNative("String.includes", (a, i) ->
                            a.isEmpty() ? false : s.contains(jsString(a.get(0))));
                case "trim":
                    return new JsNative("String.trim", (a, i) -> s.trim());
                case "toLowerCase":
                    return new JsNative("String.toLowerCase", (a, i) -> s.toLowerCase());
                case "toUpperCase":
                    return new JsNative("String.toUpperCase", (a, i) -> s.toUpperCase());
                case "split":
                    return new JsNative("String.split", (a, i) -> {
                        String sep = a.isEmpty() ? "," : jsString(a.get(0));
                        List<Object> out = new ArrayList<>();
                        for (String part : s.split(java.util.regex.Pattern.quote(sep), -1)) {
                            out.add(part);
                        }
                        return out;
                    });
                default:
                    return null;
            }
        }

        private Object futureMember(CompletableFuture<?> f, String prop) {
            switch (prop) {
                case "then":
                    return new JsNative("Promise.then", (a, i) -> {
                        requirePredicate(a, "then");
                        @SuppressWarnings("unchecked")
                        CompletableFuture<Object> f0 = (CompletableFuture<Object>) f;
                        return f0.thenCompose(v -> {
                            try {
                                return toFuture(invokeCallable(a.get(0), List.of(v)));
                            } catch (Throwable t) {
                                return failedFuture(t);
                            }
                        });
                    });
                case "catch":
                    return new JsNative("Promise.catch", (a, i) -> {
                        requirePredicate(a, "catch");
                        @SuppressWarnings("unchecked")
                        CompletableFuture<Object> f0 = (CompletableFuture<Object>) f;
                        return f0.handle((v, e) -> {
                            if (e == null) {
                                return CompletableFuture.completedFuture(v);
                            }
                            try {
                                return toFuture(invokeCallable(a.get(0), List.of(unwrap(e))));
                            } catch (Throwable t) {
                                return failedFuture(t);
                            }
                        }).thenCompose(x -> (CompletableFuture<Object>) x);
                    });
                default:
                    return null;
            }
        }

        private Object dateMember(Date d, String prop) {
            if ("getTime".equals(prop)) {
                return new JsNative("Date.getTime", (a, i) -> d.getTime());
            }
            return null;
        }

        // ── 调用 ──

        private Object invokeCallable(Object callee, List<Object> args) {
            if (callee instanceof JsCallable jc) {
                return jc.apply(args, this);
            }
            throw new ScriptError("'" + callee + "' is not a function (" + typeOf(callee) + ")");
        }

        private List<Object> evalArgs(List<Expr> argExprs, Env env) {
            List<Object> out = new ArrayList<>();
            for (Expr e : argExprs) {
                if (e instanceof SpreadExpr s) {
                    Object spread = eval(s.inner(), env);
                    if (spread instanceof List<?> l) {
                        out.addAll(l);
                    } else {
                        out.add(spread);
                    }
                } else {
                    out.add(eval(e, env));
                }
            }
            return out;
        }

        private void requirePredicate(List<Object> args, String what) {
            if (args.isEmpty() || !(args.get(0) instanceof JsCallable)) {
                throw new ScriptError(what + " requires a function argument");
            }
        }

        // ── hook / 内置全局种子（对齐 script.ts:194-206 的 10 形参注入）──

        private void seedGlobals(Object args, Object budget) {
            // 位置 1-6：6 个 hook
            global.set("agent", hooksAgent());
            global.set("parallel", hooksParallel());
            global.set("pipeline", hooksPipeline());
            global.set("phase", hooksPhase());
            global.set("log", hooksLog());
            global.set("workflow", hooksWorkflow());
            // 位置 7-8：args / budget
            global.set("args", args);
            global.set("budget", budget);
            // 位置 9-10：Date / Math 沙箱
            global.set("Date", dateSandbox());
            global.set("Math", mathSandbox());
            // 内置
            global.set("Boolean", new JsNative("Boolean", (a, i) -> truthy(a.isEmpty() ? null : a.get(0))));
            global.set("String", new JsNative("String", (a, i) -> jsString(a.isEmpty() ? null : a.get(0))));
            global.set("Number", new JsNative("Number", (a, i) -> toNumber(a.isEmpty() ? null : a.get(0))));
            global.set("Object", objectSandbox());
        }

        private JsNative hooksAgent() {
            return new JsNative("agent", (a, i) -> {
                if (a.isEmpty() || a.get(0) == null) {
                    throw new ScriptError("agent(prompt[, opts]) requires a prompt string");
                }
                String prompt = jsString(a.get(0));
                Map<String, Object> opts = a.size() > 1 ? toOptsMap(a.get(1)) : Map.of();
                if (log.isDebugEnabled()) {
                    log.debug("RestrictedScriptExecutor agent() → hooks.agent：prompt={}，opts={}", prompt, opts);
                }
                try {
                    return hooks.agent(prompt, opts);
                } catch (Throwable t) {
                    return failedFuture(t);
                }
            });
        }

        private JsNative hooksParallel() {
            return new JsNative("parallel", (a, i) -> {
                if (a.isEmpty() || !(a.get(0) instanceof List<?> thunks)) {
                    throw new ScriptError("parallel(thunks) requires an array of functions");
                }
                if (log.isDebugEnabled()) {
                    log.debug("RestrictedScriptExecutor parallel() → hooks.parallel：thunks={}", thunks.size());
                }
                List<Supplier<CompletableFuture<Object>>> suppliers = new ArrayList<>();
                for (Object thunk : thunks) {
                    suppliers.add(() -> toFutureSafe(thunk));
                }
                try {
                    return hooks.parallel(suppliers);
                } catch (Throwable t) {
                    return failedFuture(t);
                }
            });
        }

        private JsNative hooksPipeline() {
            return new JsNative("pipeline", (a, i) -> {
                if (a.isEmpty() || !(a.get(0) instanceof List<?> items)) {
                    throw new ScriptError("pipeline(items, ...stages) requires an array");
                }
                if (log.isDebugEnabled()) {
                    log.debug("RestrictedScriptExecutor pipeline() → hooks.pipeline：items={}，stages={}",
                            items.size(), a.size() - 1);
                }
                List<Object> itemsList = new ArrayList<>(items);
                List<WorkflowHooks.PipelineStage> stages = new ArrayList<>();
                for (int s = 1; s < a.size(); s++) {
                    final Object stageFn = a.get(s);
                    stages.add((prev, item, index) -> {
                        try {
                            return toFuture(invokeCallable(stageFn, List.of(prev, item, index)));
                        } catch (Throwable t) {
                            return failedFuture(t);
                        }
                    });
                }
                try {
                    return hooks.pipeline(itemsList, stages);
                } catch (Throwable t) {
                    return failedFuture(t);
                }
            });
        }

        private JsNative hooksPhase() {
            return new JsNative("phase", (a, i) -> {
                hooks.phase(jsString(a.isEmpty() ? null : a.get(0)));
                return null;
            });
        }

        private JsNative hooksLog() {
            return new JsNative("log", (a, i) -> {
                hooks.log(jsString(a.isEmpty() ? null : a.get(0)));
                return null;
            });
        }

        private JsNative hooksWorkflow() {
            return new JsNative("workflow", (a, i) -> {
                if (a.isEmpty() || a.get(0) == null) {
                    throw new ScriptError("workflow(name[, args]) requires a workflow name");
                }
                String name = jsString(a.get(0));
                Object args = a.size() > 1 ? a.get(1) : null;
                if (log.isDebugEnabled()) {
                    log.debug("RestrictedScriptExecutor workflow() → hooks.workflow：name={}，args={}", name, args);
                }
                try {
                    return hooks.workflow(name, args);
                } catch (Throwable t) {
                    return failedFuture(t);
                }
            });
        }

        /** Date 沙箱（对齐 sandboxDate，script.ts:116-130）：now/无参 new Date 拒绝，parse/UTC 透传。 */
        private Map<String, Object> dateSandbox() {
            Map<String, Object> date = new LinkedHashMap<>();
            date.put("now", new JsNative("Date.now", (a, i) -> {
                DateMathSandbox.rejectDateNow();
                return null;
            }));
            date.put("parse", new JsNative("Date.parse", (a, i) -> {
                String s = jsString(a.isEmpty() ? null : a.get(0));
                try {
                    return java.time.Instant.parse(s).toEpochMilli();
                } catch (Exception e) {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException nf) {
                        throw new ScriptError("Date.parse unsupported format: " + s);
                    }
                }
            }));
            date.put("UTC", new JsNative("Date.UTC", (a, i) -> {
                int y = intOf(a, 0, 1970);
                int mo = intOf(a, 1, 0); // JS 0-based
                int d = intOf(a, 2, 1);
                int h = intOf(a, 3, 0);
                int mi = intOf(a, 4, 0);
                int s = intOf(a, 5, 0);
                int ms = intOf(a, 6, 0);
                LocalDateTime ldt = LocalDateTime.of(y, mo + 1, d, h, mi, s);
                return ldt.toInstant(ZoneOffset.UTC).toEpochMilli() + ms;
            }));
            return date;
        }

        /** Math 沙箱（对齐 sandboxMath，script.ts:132-143）：random 拒绝，其余确定性函数透传。 */
        private Map<String, Object> mathSandbox() {
            Map<String, Object> math = new LinkedHashMap<>();
            math.put("random", new JsNative("Math.random", (a, i) -> {
                DateMathSandbox.rejectMathRandom();
                return null;
            }));
            math.put("floor", new JsNative("Math.floor", (a, i) -> (long) Math.floor(toNumber(first(a)).doubleValue())));
            math.put("ceil", new JsNative("Math.ceil", (a, i) -> (long) Math.ceil(toNumber(first(a)).doubleValue())));
            math.put("round", new JsNative("Math.round", (a, i) -> (long) Math.round(toNumber(first(a)).doubleValue())));
            math.put("abs", new JsNative("Math.abs", (a, i) -> Math.abs(toNumber(first(a)).doubleValue())));
            math.put("sqrt", new JsNative("Math.sqrt", (a, i) -> Math.sqrt(toNumber(first(a)).doubleValue())));
            math.put("pow", new JsNative("Math.pow", (a, i) -> Math.pow(toNumber(first(a)).doubleValue(),
                    toNumber(a.size() > 1 ? a.get(1) : null).doubleValue())));
            math.put("min", new JsNative("Math.min", (a, i) -> {
                double m = Double.POSITIVE_INFINITY;
                for (Object x : a) {
                    m = Math.min(m, toNumber(x).doubleValue());
                }
                return m;
            }));
            math.put("max", new JsNative("Math.max", (a, i) -> {
                double m = Double.NEGATIVE_INFINITY;
                for (Object x : a) {
                    m = Math.max(m, toNumber(x).doubleValue());
                }
                return m;
            }));
            math.put("log", new JsNative("Math.log", (a, i) -> Math.log(toNumber(first(a)).doubleValue())));
            return math;
        }

        /** Object 内置（keys/values/entries/assign）。 */
        private Map<String, Object> objectSandbox() {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("keys", new JsNative("Object.keys", (a, i) -> {
                Map<?, ?> m = asMap(first(a), "Object.keys");
                return new ArrayList<>(m.keySet());
            }));
            obj.put("values", new JsNative("Object.values", (a, i) -> {
                Map<?, ?> m = asMap(first(a), "Object.values");
                return new ArrayList<>(m.values());
            }));
            obj.put("entries", new JsNative("Object.entries", (a, i) -> {
                Map<?, ?> m = asMap(first(a), "Object.entries");
                List<Object> out = new ArrayList<>();
                m.forEach((k, v) -> out.add(List.of(k, v)));
                return out;
            }));
            obj.put("assign", new JsNative("Object.assign", (a, i) -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Object src : a) {
                    if (src instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> out.put(String.valueOf(k), v));
                    }
                }
                return out;
            }));
            return obj;
        }

        // ── 值构造 ──

        private Object buildObject(ObjectLit o, Env env) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (ObjectLitEntry e : o.entries()) {
                if (e instanceof KeyValue kv) {
                    map.put(kv.key(), eval(kv.value(), env));
                } else if (e instanceof SpreadEntry sp) {
                    Object spread = eval(sp.inner(), env);
                    if (spread instanceof Map<?, ?> m) {
                        m.forEach((k, v) -> map.put(String.valueOf(k), v));
                    } else if (spread == null) {
                        // { ...undefined } 是空展开（JS 允许）
                    } else {
                        throw new ScriptError("object spread requires an object, got " + typeOf(spread));
                    }
                }
            }
            return map;
        }

        private Object buildArray(ArrayLit a, Env env) {
            List<Object> out = new ArrayList<>();
            for (Expr e : a.elems()) {
                if (e instanceof SpreadExpr s) {
                    Object spread = eval(s.inner(), env);
                    if (spread instanceof List<?> l) {
                        out.addAll(l);
                    } else {
                        out.add(spread);
                    }
                } else {
                    out.add(eval(e, env));
                }
            }
            return out;
        }

        // ── 值工具 ──

        private CompletableFuture<Object> toFutureSafe(Object callableOrValue) {
            if (callableOrValue instanceof JsCallable jc) {
                try {
                    return toFuture(jc.apply(List.of(), this));
                } catch (Throwable t) {
                    return failedFuture(t);
                }
            }
            return toFuture(callableOrValue);
        }

        private CompletableFuture<Object> toFuture(Object v) {
            if (v instanceof CompletableFuture<?> f) {
                @SuppressWarnings("unchecked")
                CompletableFuture<Object> f0 = (CompletableFuture<Object>) f;
                return f0;
            }
            return CompletableFuture.completedFuture(v);
        }

        private Object unwrapFuture(Object v) {
            if (v instanceof CompletableFuture<?> f) {
                try {
                    return f.join();
                } catch (CompletionException e) {
                    Throwable c = e.getCause();
                    throw c instanceof RuntimeException re ? re : e;
                }
            }
            return v;
        }

        private static Throwable unwrap(Throwable t) {
            while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
                    && t.getCause() != null) {
                t = t.getCause();
            }
            return t;
        }

        private static CompletableFuture<Object> failedFuture(Throwable t) {
            return CompletableFuture.failedFuture(t);
        }

        private static Object first(List<Object> a) {
            return a.isEmpty() ? null : a.get(0);
        }

        private static int intOf(List<Object> a, int i, int def) {
            if (i >= a.size() || a.get(i) == null) {
                return def;
            }
            return toNumber(a.get(i)).intValue();
        }

        private static Number toNumber(Object v) {
            if (v instanceof Number n) {
                return n;
            }
            if (v instanceof Boolean b) {
                return b ? 1L : 0L;
            }
            if (v == null) {
                return 0L;
            }
            if (v instanceof String s) {
                try {
                    if (s.indexOf('.') >= 0) {
                        return Double.parseDouble(s);
                    }
                    return Long.parseLong(s);
                } catch (NumberFormatException e) {
                    return Double.NaN;
                }
            }
            throw new ScriptError("cannot coerce " + typeOf(v) + " to number");
        }

        private static String jsString(Object v) {
            if (v == null) {
                return "null";
            }
            if (v instanceof String s) {
                return s;
            }
            if (v instanceof Map || v instanceof List) {
                return String.valueOf(v); // 对象/数组 String 化仅调试用途
            }
            if (v instanceof Double d && d == Math.floor(d)) {
                long l = d.longValue();
                return String.valueOf(l);
            }
            return String.valueOf(v);
        }

        private static Object jsPlus(Object l, Object r) {
            if (l instanceof String || r instanceof String
                    || l instanceof Character || r instanceof Character) {
                return jsString(l) + jsString(r);
            }
            if (l instanceof Number && r instanceof Number) {
                if (l instanceof Double || r instanceof Double) {
                    return toNumber(l).doubleValue() + toNumber(r).doubleValue();
                }
                return toNumber(l).longValue() + toNumber(r).longValue();
            }
            throw new ScriptError("cannot apply + to " + typeOf(l) + " and " + typeOf(r));
        }

        private static Object numericOp(String op, Object l, Object r) {
            if (l instanceof Number && r instanceof Number) {
                if (l instanceof Double || r instanceof Double) {
                    double a = toNumber(l).doubleValue();
                    double b = toNumber(r).doubleValue();
                    return switch (op) {
                        case "-" -> a - b;
                        case "*" -> a * b;
                        default -> throw new ScriptError("unsupported numeric operator '" + op + "'");
                    };
                }
                long a = toNumber(l).longValue();
                long b = toNumber(r).longValue();
                return switch (op) {
                    case "-" -> a - b;
                    case "*" -> a * b;
                    default -> throw new ScriptError("unsupported numeric operator '" + op + "'");
                };
            }
            throw new ScriptError("cannot apply arithmetic to " + typeOf(l) + " and " + typeOf(r));
        }

        private static int compare(Object l, Object r) {
            if (l instanceof Number && r instanceof Number) {
                return Double.compare(toNumber(l).doubleValue(), toNumber(r).doubleValue());
            }
            if (l instanceof String && r instanceof String) {
                return ((String) l).compareTo((String) r);
            }
            throw new ScriptError("cannot compare " + typeOf(l) + " and " + typeOf(r));
        }

        private static boolean jsEquals(Object l, Object r) {
            if (l instanceof Number nl && r instanceof Number nr) {
                return nl.doubleValue() == nr.doubleValue();
            }
            return Objects.equals(l, r);
        }

        private static boolean truthy(Object v) {
            if (v == null) {
                return false;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof Number n) {
                return n.doubleValue() != 0;
            }
            if (v instanceof String s) {
                return !s.isEmpty();
            }
            return true;
        }

        private static String typeOf(Object v) {
            if (v == null) {
                return "undefined";
            }
            if (v instanceof String) {
                return "string";
            }
            if (v instanceof Boolean) {
                return "boolean";
            }
            if (v instanceof Number) {
                return "number";
            }
            if (v instanceof List) {
                return "array";
            }
            if (v instanceof Map) {
                return "object";
            }
            if (v instanceof JsCallable) {
                return "function";
            }
            return v.getClass().getSimpleName();
        }

        private static Map<?, ?> asMap(Object v, String what) {
            if (v instanceof Map<?, ?> m) {
                return m;
            }
            throw new ScriptError(what + " requires an object, got " + typeOf(v));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> toOptsMap(Object v) {
            if (v instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, val) -> out.put(String.valueOf(k), val));
                return out;
            }
            throw new ScriptError("agent opts must be an object literal, got " + typeOf(v));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // JS 空白对齐（parser 与 lexer 共用）
    // ═══════════════════════════════════════════════════════════════════════

    private static boolean isJsWhitespace(char c) {
        return Character.isWhitespace(c) || c == ' ' || c == ' ' || c == ' ' || c == '﻿';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
