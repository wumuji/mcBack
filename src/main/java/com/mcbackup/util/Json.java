package com.mcbackup.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写器(零第三方依赖)。
 *
 * <p>为什么自己写:第一阶段只需要读写一个很小的 config.json,以及从启动器配置文件里
 * 提取字符串。为了不引入第三方库(体积、供应链、离线可用性),这里实现一个带完整校验的
 * 递归下降解析器。解析失败会抛出 {@link JsonException},由调用方决定回退策略。</p>
 *
 * <p>类型映射:对象 -> {@link LinkedHashMap},数组 -> {@link ArrayList},字符串 -> String,
 * 数字 -> Long(可整除)或 Double,布尔 -> Boolean,空 -> null。</p>
 */
public final class Json {

    private Json() {
    }

    /** JSON 解析失败。 */
    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    /** 解析任意 JSON 文本。 */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("输入为 null");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("第 " + parser.errorOffset() + " 个字符后存在多余内容");
        }
        return value;
    }

    /** 解析 JSON 对象;根节点不是对象时抛异常。 */
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        throw new JsonException("根节点不是 JSON 对象");
    }

    /** 序列化为带缩进的可读文本(便于用户手工检查配置文件)。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, 0);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 便捷读取
    // ------------------------------------------------------------------

    public static String optString(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value instanceof String s ? s : fallback;
    }

    public static int optInt(Map<String, Object> object, String key, int fallback) {
        Object value = object.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    public static long optLong(Map<String, Object> object, String key, long fallback) {
        Object value = object.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        return fallback;
    }

    public static boolean optBoolean(Map<String, Object> object, String key, boolean fallback) {
        Object value = object.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    /** 读取字符串数组;非数组或元素非字符串时忽略该元素。 */
    public static List<String> optStringList(Map<String, Object> object, String key) {
        List<String> result = new ArrayList<>();
        Object value = object.get(key);
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof String s && !s.isBlank()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, indent);
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(sb, indent + 1);
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(": ");
            writeValue(sb, entry.getValue(), indent + 1);
            if (++index < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i < list.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int level) {
        sb.append("  ".repeat(level));
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        int errorOffset() {
            return pos;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new JsonException("内容意外结束");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new JsonException("对象中第 " + pos + " 个字符处缺少 ',' 或 '}'");
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new JsonException("数组中第 " + pos + " 个字符处缺少 ',' 或 ']'");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("字符串未闭合");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("转义符后内容结束");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new JsonException("\\u 转义不完整");
                        }
                        String hex = text.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException("非法 \\u 转义: " + hex);
                        }
                        pos += 4;
                    }
                    default -> throw new JsonException("非法转义: \\" + esc);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            boolean floating = false;
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty() || "-".equals(raw) || "+".equals(raw)) {
                throw new JsonException("第 " + start + " 个字符处不是合法值");
            }
            try {
                if (floating) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new JsonException("非法数字: " + raw);
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (text.startsWith(literal, pos)) {
                pos += literal.length();
                return value;
            }
            throw new JsonException("第 " + pos + " 个字符处期望 " + literal);
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("内容意外结束");
            }
            return text.charAt(pos);
        }

        private void expect(char expected) {
            if (atEnd() || text.charAt(pos) != expected) {
                throw new JsonException("第 " + pos + " 个字符处期望 '" + expected + "'");
            }
            pos++;
        }
    }
}
