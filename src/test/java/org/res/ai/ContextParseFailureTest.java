package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextParseFailureTest {
    @Test
    void invalidDocumentsUseContextExceptionContract(@TempDir Path directory) throws Exception {
        for (String json : List.of(
                "{\"files\":[],\"files\":[]}",
                "{\"contexts\":[],\"permissions\":[],\"permissions\":[]}",
                "{\"contexts\":[],\"permissions\":[{\"x\":\"READ\",\"x\":\"READ_WRITE\"}]}",
                "{", "[]", "null", "42",
                "{\"contexts\":[],\"permissions\":[],\"workflow\":null}")) {
            Path config = directory.resolve("custom.json");
            Files.writeString(config, json);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> RequestContext.load(directory, "custom.json"), json);
            assertTrue(failure.getMessage().startsWith("Invalid context custom.json:"));
            assertNotNull(failure.getCause());
            assertEquals(json, Files.readString(config));
        }
    }
}
