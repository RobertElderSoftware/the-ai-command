package org.res.ai;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Reads effective configuration/account kind, never tokens or guessed user config files. */
final class CodexNetworkEndpoint {
    private CodexNetworkEndpoint() { }

    static URI resolve() {
        String override = System.getProperty("ai.codex.networkEndpoint");
        if (override != null) return NetworkProbe.endpoint(override);
        try (ShellProcessRunner runner = new ShellProcessRunner(List.of("codex", "app-server"),
                null, null, true, Duration.ofSeconds(3), 262144, 262144)) {
            OutputStream input = runner.getOutputStreamForStdin();
            send(input, "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"ai_network\",\"version\":\"1.0\"}}}");
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            JsonObject configuration = null;
            JsonObject account = null;
            boolean accountReceived = false;
            boolean initialized = false;
            long started = System.nanoTime();
            while (System.nanoTime() - started < Duration.ofSeconds(3).toNanos()) {
                boolean finished = runner.isFinished();
                byte[] bytes = finished ? runner.getFinalResult().getOutput().getStdoutOutput()
                        : runner.getPartialResult().getStdoutOutput();
                for (byte value : bytes) {
                    if (value != '\n') { pending.write(value); continue; }
                    String line = pending.toString(StandardCharsets.UTF_8);
                    pending.reset();
                    if (line.isBlank()) continue;
                    JsonObject event = StrictJson.parse(line).getAsJsonObject();
                    if (!event.has("id") || !event.get("id").isJsonPrimitive()) continue;
                    String id = event.get("id").getAsString();
                    if (!List.of("1", "2", "3").contains(id)) continue;
                    if (event.has("error")) throw new IllegalStateException("Codex configuration query failed");
                    JsonObject result = CodexEvents.object(event, "result");
                    if (result == null) throw new IllegalStateException("Missing Codex configuration result");
                    if (id.equals("1") && !initialized) {
                        initialized = true;
                        send(input, "{\"method\":\"initialized\",\"params\":{}}");
                        send(input, "{\"id\":2,\"method\":\"config/read\",\"params\":{\"includeLayers\":false}}");
                        send(input, "{\"id\":3,\"method\":\"account/read\",\"params\":{\"refreshToken\":false}}");
                    } else if (id.equals("2")) {
                        configuration = CodexEvents.object(result, "config");
                    } else if (id.equals("3")) {
                        account = CodexEvents.object(result, "account");
                        accountReceived = true;
                    }
                    if (configuration != null && accountReceived)
                        return select(configuration, account, System.getenv("OPENAI_BASE_URL"));
                }
                if (finished) break;
                Thread.sleep(10);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Codex endpoint lookup cancelled", interrupted);
        } catch (Exception failure) {
            // Do not expose RPC payloads, which can include sensitive configuration.
            throw new IllegalStateException("Cannot resolve Codex network endpoint. Set "
                    + "-Dai.codex.networkEndpoint=https://your-provider-endpoint to enable preflight.");
        }
        throw new IllegalStateException("Codex endpoint lookup timed out. Set -Dai.codex.networkEndpoint.");
    }

    static URI select(JsonObject config, JsonObject account, String environmentBase) {
        String provider = CodexEvents.string(config, "model_provider");
        if (provider == null) provider = "openai";
        JsonObject providers = CodexEvents.object(config, "model_providers");
        JsonObject selected = CodexEvents.object(providers, provider);
        String base = CodexEvents.string(selected, "base_url");
        if (base != null) return NetworkProbe.endpoint(base);
        if (!provider.equals("openai"))
            throw new IllegalArgumentException("Custom Codex provider needs an explicit network endpoint");
        if ("chatgpt".equals(CodexEvents.string(account, "type"))) {
            base = CodexEvents.string(config, "chatgpt_base_url");
            return NetworkProbe.endpoint(base == null ? "https://chatgpt.com/backend-api/codex" : base);
        }
        if ("apiKey".equals(CodexEvents.string(account, "type")))
            return NetworkProbe.endpoint(environmentBase == null
                    ? "https://api.openai.com/v1" : environmentBase);
        throw new IllegalArgumentException("Unknown Codex account kind; configure the network endpoint");
    }

    private static void send(OutputStream output, String message) throws java.io.IOException {
        output.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
