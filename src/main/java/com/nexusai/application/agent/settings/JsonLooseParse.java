package com.nexusai.application.agent.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 解析 — 用于 remote-settings.json 这种"非 object 即 null"的叶子读取.
 *
 * <p>不复用 Jackson 是为了避免大型 dep,以及 SettingsJson 仅 Map&lt;String,Object&gt; 已知结构
 *    (settings.ts 端走 Schema.safeParse 完整校验,这里只做"是否是 object"判断).
 *
 * <p>L2 契约:
 * <ul>
 *   <li><b>A1</b>: parseObject(String) → Map|null (null 表示非 object / 解析失败 / 数组 / primitive)</li>
 *   <li><b>A3</b>: 纯函数;LinkedHashMap 保 key 顺序 (与 CC 一致)</li>
 *   <li><b>A4</b>: 顶层非 object → null (数组 primitive 等);空字符串 → null</li>
 * </ul>
 */
final class JsonLooseParse {

    private JsonLooseParse() {}

    @SuppressWarnings("unchecked")
    static Object parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Parser p = new Parser(json);
        p.skipWhitespace();
        Object result = p.parseValue();
        p.skipWhitespace();
        if (p.hasMore()) {
            return null;  // 顶层后还有非空白 → 非合法单值
        }
        return result;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean hasMore() {
            return pos < src.length();
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) return null;
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObjectInternal();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Object parseObjectInternal() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                if (peek() != '"') return null;
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                return null;
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                return list;
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    sb.append(switch (esc) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> {
                            if (pos + 4 > src.length()) yield '?';
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            yield (char) Integer.parseInt(hex, 16);
                        }
                        default -> esc;
                    });
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            return null;
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            return null;
        }

        private Object parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length() && "-0123456789.eE+".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            String num = src.substring(start, pos);
            if (num.isEmpty() || num.equals("-")) return null;
            try {
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalStateException("expected '" + c + "' at " + pos);
            }
            pos++;
        }
    }
}
