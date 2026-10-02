package com.example.paytm.seatManagement.observability;

/** Tiny helpers for the Prometheus text exposition format (version 0.0.4). */
public final class PromText {

    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private PromText() {
    }

    /** Writes the # HELP and # TYPE lines for a metric family. */
    public static void header(StringBuilder sb, String name, String type, String help) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    /** Writes one sample; {@code labels} is the already-formatted label body (may be empty). */
    public static void sample(StringBuilder sb, String name, String labels, String value) {
        sb.append(name);
        if (labels != null && !labels.isEmpty()) {
            sb.append('{').append(labels).append('}');
        }
        sb.append(' ').append(value).append('\n');
    }

    public static void sample(StringBuilder sb, String name, String labels, long value) {
        sample(sb, name, labels, Long.toString(value));
    }

    /** {@code key="value"} with the value escaped per the exposition format. */
    public static String label(String key, String value) {
        return key + "=\"" + escape(value) + "\"";
    }

    public static String escape(String v) {
        StringBuilder sb = null;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            String rep = null;
            if (c == '\\') {
                rep = "\\\\";
            } else if (c == '"') {
                rep = "\\\"";
            } else if (c == '\n') {
                rep = "\\n";
            }
            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(v.length() + 8);
                    sb.append(v, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? v : sb.toString();
    }
}
