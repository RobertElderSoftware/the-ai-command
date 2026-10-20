package org.res.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Live provider observations, independent of buffered CIOP response output. */
public final class ProviderActivity {
    private ProviderActivity() { }

    @FunctionalInterface
    public interface Listener {
        Listener NONE = (event, text) -> { };
        void accept(String event, String text) throws IOException;
    }

    /** A local reporting failure must never become a provider retry signal. */
    static final class ReportingFailure extends RuntimeException {
        ReportingFailure(Throwable cause) {
            super("Could not publish execution activity; no automatic resubmission", cause);
        }
    }

    static void emit(Listener listener, String event, String text) {
        try {
            listener.accept(event, text);
        } catch (IOException | RuntimeException failure) {
            throw new ReportingFailure(failure);
        }
    }

    static Listener publisher(OutputStream output, ConversationHistory history) {
        boolean ansi = Boolean.parseBoolean(System.getProperty("ai.retry.ansi", "true"));
        return (event, text) -> {
            String line = text + "\n";
            output.write(((ansi ? "\u001b[34;4m" : "") + text
                    + (ansi ? "\u001b[0m" : "") + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            if (output instanceof PrintStream stream && stream.checkError())
                throw new IOException("Could not publish execution activity");
            if (history != null) history.executionEvent(event, line);
        };
    }

    /** Escape controls, formatting characters and line separators before display. */
    static String safe(String value, int limit) {
        if (value == null) return "unavailable";
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            int cp = value.codePointAt(index);
            String next = Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp) == Character.SURROGATE || cp == 0x2028 || cp == 0x2029
                    ? String.format(java.util.Locale.ROOT, "\\u%04x", cp)
                    : new String(Character.toChars(cp));
            if (result.length() + next.length() > limit) break;
            result.append(next);
            index += Character.charCount(cp);
        }
        if (index < value.length()) result.append(" [truncated]");
        return result.toString();
    }
}
