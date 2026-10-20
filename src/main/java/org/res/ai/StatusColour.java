package org.res.ai;

/** Colour automatic notices without changing ordinary response output. */
final class StatusColour {
    private StatusColour() { }

    static String format(String text) {
        return format(text, Boolean.parseBoolean(System.getProperty("ai.retry.ansi", "true")));
    }

    static String format(String text, boolean enabled) {
        if (!enabled || text.isEmpty()) return text;
        // Reset before the trailing newline so subsequent output uses its normal colour.
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
        return "\u001b[32;4m" + text.substring(0, end) + "\u001b[0m" + text.substring(end);
    }
}
