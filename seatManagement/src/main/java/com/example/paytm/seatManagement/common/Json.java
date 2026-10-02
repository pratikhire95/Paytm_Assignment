package com.example.paytm.seatManagement.common;

import java.util.List;
import java.util.Map;

/**
 * Minimal, allocation-light JSON writer.
 *
 * <p>Responses are written by hand (rather than via an ObjectMapper) so that the wire format is explicit and
 * stable, independent of Jackson version defaults, and cheap for the 20k-seat show-state payloads.
 * Parsing of request bodies is still done by the framework.
 */
public final class Json {

    private Json() {
    }

    /** Appends {@code s} as a JSON string literal (or {@code null}), escaping everything that must be escaped. */
    public static void appendQuoted(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (ch < 0x20 || ch == 0x2028 || ch == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('"');
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder((s == null ? 4 : s.length()) + 2);
        appendQuoted(sb, s);
        return sb.toString();
    }

    /** Appends null, String, Number, Boolean, List or Map (String keys). Anything else is written as a string. */
    @SuppressWarnings("unchecked")
    public static void appendValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            appendQuoted(sb, (String) v);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendValue(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendQuoted(sb, String.valueOf(e.getKey()));
                sb.append(':');
                appendValue(sb, e.getValue());
            }
            sb.append('}');
        } else {
            appendQuoted(sb, v.toString());
        }
    }

    /** Fluent builder for a flat-ish JSON object. Use once, then call {@link #build()}. */
    public static final class Obj {
        private final StringBuilder sb = new StringBuilder(192);
        private boolean first = true;

        public Obj() {
            sb.append('{');
        }

        private void key(String k) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendQuoted(sb, k);
            sb.append(':');
        }

        public Obj str(String k, String v) {
            key(k);
            appendQuoted(sb, v);
            return this;
        }

        public Obj num(String k, long v) {
            key(k);
            sb.append(v);
            return this;
        }

        public Obj bool(String k, boolean v) {
            key(k);
            sb.append(v);
            return this;
        }

        /** Inserts an already-serialised JSON fragment. */
        public Obj raw(String k, String json) {
            key(k);
            sb.append(json);
            return this;
        }

        public Obj value(String k, Object v) {
            key(k);
            appendValue(sb, v);
            return this;
        }

        public Obj strings(String k, List<String> vs) {
            key(k);
            sb.append('[');
            for (int i = 0; i < vs.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendQuoted(sb, vs.get(i));
            }
            sb.append(']');
            return this;
        }

        public String build() {
            return sb.toString() + "}";
        }
    }
}
