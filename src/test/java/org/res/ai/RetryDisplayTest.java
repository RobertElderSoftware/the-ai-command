package org.res.ai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetryDisplayTest {
    @Test void redrawErasesPreviousRowsAndReturnsToTheirOrigin() throws Exception {
        var output = new ByteArrayOutputStream();
        var display = new RetryDisplay(output);
        display.update("a".repeat(40) + " | " + "b".repeat(40));
        String first = output.toString(StandardCharsets.UTF_8);
        assertTrue(first.contains("\u001b[0m\n\r"));
        assertTrue(first.startsWith("\u001b[?7l"));
        assertTrue(first.endsWith("\u001b[0m\u001b[?7h"));
        output.reset();
        display.update("short");
        assertTrue(output.toString(StandardCharsets.UTF_8).startsWith(
                "\u001b[0m\r\u001b[2K\u001b[1A\r\u001b[2K"));
        output.reset();
        display.close();
        assertEquals("\u001b[0m\r\u001b[2K\u001b[0m\u001b[?7h",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test void colourDoesNotConsumeLayoutWidth() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var display = new RetryDisplay(output)) {
            display.update("\u001b[33m" + "a".repeat(30) + "\u001b[0m | " + "b".repeat(30));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("\n"));
        }
    }

    @Test void consecutiveWaitsReuseTheSameCursorOrigin() throws Exception {
        var output = new ByteArrayOutputStream();
        for (int attempt = 0; attempt < 3; attempt++) {
            try (var display = new RetryDisplay(output)) {
                display.update("a".repeat(40) + " | " + "b".repeat(40));
            }
        }
        String sequence = output.toString(StandardCharsets.UTF_8);
        assertEquals(3, sequence.chars().filter(value -> value == '\n').count());
        assertEquals(3, sequence.split("\u001b\\[1A", -1).length - 1);
        assertTrue(sequence.endsWith("\u001b[0m\u001b[?7h"));
    }
}
