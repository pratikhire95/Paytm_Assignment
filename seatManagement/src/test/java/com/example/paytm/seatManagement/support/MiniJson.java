package com.example.paytm.seatManagement.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny strict JSON parser for test assertions (objects -> Map, arrays -> List, numbers -> Long or Double).
 * Deliberately independent of Jackson so the tests do not care which Jackson generation Spring Boot ships.
 */
public final class MiniJson {

    private final String s;
    private int i = 0;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.fail("trailing characters");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        return (Map<String, Object>) parse(text);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(String text) {
        return (List<Object>) parse(text);
    }

    private Object value() {
        if (i >= s.length()) {
            throw fail("unexpected end");
        }
        char c = s.charAt(i);
        if (c == '{') {
            return obj();
        }
        if (c == '[') {
            return arr();
        }
        if (c == '"') {
            return str();
        }
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        return num();
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = str();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            if (peek() == ',') {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++; // [
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek() == ',') {
                i++;
                continue;
            }
            expect(']');
            return l;
        }
    }

    private String str() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw fail("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw fail("bad escape");
                }
            } else {
                if (c < 0x20) {
                    throw fail("raw control character in string");
                }
                sb.append(c);
            }
        }
    }

    private Object num() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw fail("unexpected character");
        }
        String t = s.substring(start, i);
        if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
            return Double.valueOf(t);
        }
        return Long.valueOf(t);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw fail("expected '" + c + "'");
        }
        i++;
    }

    private IllegalArgumentException fail(String m) {
        return new IllegalArgumentException("invalid JSON at " + i + ": " + m);
    }
}
