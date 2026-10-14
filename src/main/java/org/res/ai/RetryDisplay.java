package org.res.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A temporary ANSI status block, erased before ordinary output resumes. */
final class RetryDisplay implements AutoCloseable {
    private final OutputStream output;
    private int rows;

    RetryDisplay(OutputStream output) {
        this.output = output;
    }

    void update(String message) throws IOException {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String field : message.split(" \\| ", -1)) {
            String next = line.isEmpty() ? field : line + " | " + field;
            if (!line.isEmpty() && width(next) > 64) {
                lines.add(line);
                line = field;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        clear();
        try {
            // Prevent wrapped physical rows from invalidating cursor movement.
            // On very narrow terminals, long rows are clipped until the next redraw.
            write("\u001b[?7l");
            for (String value : lines) {
                if (rows > 0) write("\u001b[0m\n\r");
                rows++;
                write("\r\u001b[2K\u001b[1;36m" + value);
            }
        } finally {
            write("\u001b[0m\u001b[?7h");
        }
    }

    private static int width(String value) {
        String plain = value.replaceAll("\u001b\\[[0-9;]*m", "");
        return plain.codePointCount(0, plain.length());
    }

    private void clear() throws IOException {
        if (rows == 0) return;
        StringBuilder sequence = new StringBuilder("\u001b[0m\r\u001b[2K");
        for (int index = 1; index < rows; index++)
            sequence.append("\u001b[1A\r\u001b[2K");
        write(sequence.toString());
        rows = 0;
    }

    @Override public void close() throws IOException {
        try {
            clear();
        } finally {
            write("\u001b[0m\u001b[?7h");
        }
    }

    private void write(String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
        if (output instanceof PrintStream stream && stream.checkError())
            throw new IOException("Could not publish retry status");
    }
}
