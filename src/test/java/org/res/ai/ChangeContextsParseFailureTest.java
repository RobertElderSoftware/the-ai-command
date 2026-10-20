package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChangeContextsParseFailureTest {
    @Test void invalidJsonPreservesConfigurationAndCause(@TempDir Path directory) throws Exception {
        String original = "{\"contexts\":[],\"permissions\":[]}";
        Files.writeString(directory.resolve("context.json"), original);
        RequestContext context = RequestContext.load(directory, "context.json");
        for (String data : List.of(
                "{\"op\":\"change_contexts\",\"add\":[],\"remove\":[],\"update\":[{\"path\":\"source.txt\",\"lines\":[[1,1]],\"lines\":[[2,2]]}]}",
                "{", "[]", "null", "true")) {
            IllegalArgumentException applied = assertThrows(IllegalArgumentException.class,
                    () -> ChangeContexts.apply(directory, context, List.of(), data));
            IllegalArgumentException planned = assertThrows(IllegalArgumentException.class,
                    () -> ChangeContexts.plan(directory, context, List.of(), data));
            for (IllegalArgumentException failure : List.of(applied, planned)) {
                assertTrue(failure.getMessage().startsWith("Invalid change_contexts JSON:"));
                assertNotNull(failure.getCause());
            }
            assertEquals(original, Files.readString(directory.resolve("context.json")));
        }
    }
}
