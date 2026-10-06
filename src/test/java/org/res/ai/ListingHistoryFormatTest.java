package org.res.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class ListingHistoryFormatTest {
    @Test
    void preservesEveryReportFieldInReadableLines() {
        String report = object("complete", false, "entries", lines(
                object("path", "src", "type", "directory", "access", "READ_WRITE"),
                object("path", "src/café.txt", "type", "file", "access", "READ",
                        "size_bytes", 4294967296L, "line_count", 12),
                object("path", "empty.txt", "type", "file", "access", "READ_WRITE",
                        "size_bytes", 0, "line_count", 0),
                object("path", "image.bin", "type", "file", "access", "READ",
                        "size_bytes", 255)), "errors", lines(
                object("path", "unreadable.txt", "error", "Could not inspect"))).toString();
        assertEquals("Complete: no\n"
                + "Permissions: 4=READ, 6=READ_WRITE (application access)\n"
                + "directory: \"src\" | permission: 6\n"
                + "file: \"src/café.txt\" | permission: 4 | bytes: 4294967296 | lines: 12\n"
                + "file: \"empty.txt\" | permission: 6 | bytes: 0 | lines: 0\n"
                + "file: \"image.bin\" | permission: 4 | bytes: 255\n"
                + "Errors: 1\n"
                + "error: \"unreadable.txt\" | \"Could not inspect\"\n",
                ListingHistoryFormat.format(report));
    }

    @Test
    void emptyCompleteReportIsExplicit() {
        assertEquals("Complete: yes\n"
                + "Permissions: 4=READ, 6=READ_WRITE (application access)\n"
                + "Entries: none\nErrors: 0\n",
                ListingHistoryFormat.format(object("complete", true,
                        "entries", lines(), "errors", lines()).toString()));
    }

    @Test
    void escapesNamesAndDiagnosticsWithoutCreatingExtraLines() {
        String report = object("complete", false, "entries", lines(
                object("path", "a\"b\\c", "type", "file", "access", "READ", "size_bytes", 1)),
                "errors", lines(object("path", "bad", "error", "first\nsecond\tline"))).toString();
        String formatted = ListingHistoryFormat.format(report);
        assertEquals(5, formatted.lines().count());
        assertTrue(formatted.contains("file: \"a\\\"b\\\\c\" | permission: 4 | bytes: 1\n"));
        assertTrue(formatted.contains("\"first\\nsecond\\tline\""));
    }
}
