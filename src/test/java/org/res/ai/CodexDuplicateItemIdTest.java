package org.res.ai;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodexDuplicateItemIdTest {
    private static final String WEB = "{\"type\":\"item.completed\",\"item\":{"
            + "\"id\":\"item_4\",\"type\":\"web_search\",\"id\":\"exec-123\","
            + "\"query\":\"example\",\"action\":{\"type\":\"search\"}}}\n";
    private static final String ANSWER = "{\"type\":\"item.completed\",\"item\":{"
            + "\"type\":\"agent_message\",\"text\":\"[]\"}}\n";
    private static final String DONE = "{\"type\":\"turn.completed\"}\n";

    @Test void duplicateToolIdsDoNotRejectSuccessfulResponse() {
        String stream = "{\"type\":\"thread.started\"}\n{\"type\":\"turn.started\"}\n"
                + WEB.replace("item.completed", "item.started") + WEB + ANSWER + DONE;
        assertEquals("[]", CodexEvents.response(0, stream, "Reading prompt from stdin..."));
    }

    @Test void ordinaryJsonStillRejectsDuplicateItemIds() {
        assertThrows(JsonParseException.class, () -> StrictJson.parse(WEB));
    }

    @Test void meaningfulFieldsAndOtherLocationsRemainStrict() {
        for (String event : List.of(
                "{\"type\":\"turn.completed\",\"type\":\"error\"}",
                "{\"id\":1,\"id\":2}",
                "{\"item\":{},\"item\":{}}",
                "{\"item\":{\"type\":\"web_search\",\"type\":\"agent_message\"}}",
                "{\"item\":{\"text\":\"[]\",\"text\":\"other\"}}",
                "{\"item\":{\"phase\":\"commentary\",\"phase\":\"final\"}}",
                "{\"error\":{\"code\":\"usage_limit_reached\",\"code\":\"other\"}}",
                "{\"item\":{\"action\":{\"id\":1,\"id\":2}}}",
                "[{\"item\":{\"id\":1,\"id\":2}}]")) {
            assertThrows(JsonParseException.class, () -> StrictJson.parseCodexEvent(event), event);
        }
    }

    @Test void duplicateIdsDoNotBypassCompletionOrExitChecks() {
        assertThrows(RuntimeException.class, () -> CodexEvents.response(0, WEB + ANSWER, ""));
        assertThrows(RuntimeException.class, () -> CodexEvents.response(1, WEB + ANSWER + DONE, ""));
        assertThrows(RuntimeException.class, () -> CodexEvents.response(0, ANSWER + DONE + WEB, ""));
        assertThrows(RuntimeException.class, () -> CodexEvents.response(0, WEB + "invalid\n" + ANSWER + DONE, ""));
    }
}
