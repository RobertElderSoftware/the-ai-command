package example;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class HelloChatGPT {

    private static boolean strictMode = false;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private static final DateTimeFormatter LOG_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS")
                    .withZone(ZoneId.systemDefault());

    private static final AtomicInteger LOG_SEQ = new AtomicInteger(1);

    // CIOP constants
    private static final String CIOP_BEGIN = "---BEGIN CIOP/1.0---";
    private static final String CIOP_END = "---END CIOP/1.0---";
    private static final String CIOP_END_SECTION = "---END SECTION---";

    // GSON instance for strict JSON parsing (no hand-written parser)
    private static final Gson GSON = new Gson();

    /**
     * Important: CIOP/1.0 output must be EXACTLY one JSON value: a top-level array of operations.
     * No extra commentary text.
     *
     * Also important: file_write "path" MUST exactly match one of the provided FILE section paths.
     */
    private static final String CIOP_RESPONSE_INSTRUCTIONS = """
            You are communicating with a CLI that implements CIOP/1.0.

            OUTPUT REQUIREMENTS (MUST FOLLOW):
            1) Your entire response MUST be exactly one JSON value: a top-level JSON array of operation objects.
            2) Do NOT output any non-JSON text before or after the array (no markdown fences, no explanations).
            3) Supported operations:
               - {"op":"stdout","encoding":"utf-8"|"base64","data": "...", optional "length": <int>, optional "sha256":"<64 hex>"}
               - {"op":"file_write","path":"<exact provided path>","encoding":"utf-8"|"base64","data":"...", optional "length": <int>, optional "sha256":"<64 hex>"}
            4) "path" in file_write MUST exactly match one of the FILE section path= values you received (byte-for-byte string match).
            5) For binary or non-UTF8 bytes, use encoding="base64".
            6) If you detect any input verification failure (length/sha256 mismatch), respond with a stdout op explaining it, but otherwise continue to the best of your ability.

            NOTES:
            - For encoding "utf-8": "data" is a JSON string; interpret bytes as UTF-8.
            - For encoding "base64": "data" is standard RFC4648 base64 of raw bytes.

            Now process the CIOP/1.0 envelope provided.
            """;

    private HelloChatGPT() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int run(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("OPENAI_API_KEY is not set.");
            System.err.println("Run: export OPENAI_API_KEY=\"your-api-key\"");
            return 1;
        }

        final byte[] stdinData;

        try {
            // Keep: stdin read as raw bytes (arbitrary binary)
            stdinData = System.in.readAllBytes();
        } catch (IOException exception) {
            System.err.println("Could not read standard input: ");
            exception.printStackTrace(System.err);
            return 1;
        }

        // Do not regress the existing behavior: stdin must be allowed to be arbitrary binary.
        // (We no longer reject empty stdin; CIOP/1.0 allows length 0.)
        List<Path> inputFiles;
        try {
            inputFiles = collectInputFiles(args);
        } catch (RuntimeException exception) {
            System.err.println("Invalid file arguments: ");
            exception.printStackTrace(System.err);
            return 1;
        }

        String prompt;
        try {
            prompt = buildCiopPrompt(stdinData, inputFiles);
        } catch (IOException exception) {
            System.err.println("Could not read one or more input files:");
            exception.printStackTrace(System.err);
            return 1;
        }

        // Log raw prompt as sent to API (post-marshaling).
        try {
            writeTmpLog("prompt.txt", prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            System.err.println("Warning: could not write /tmp prompt log:");
            exception.printStackTrace(System.err);
        }

        ExecutorService httpExecutor =
                Executors.newCachedThreadPool(namedThreadFactory("openai-http-"));

        ExecutorService streamExecutor =
                Executors.newCachedThreadPool(namedThreadFactory("openai-stream-"));

        OpenAIClient client = null;
        int exitCode = 0;

        try {
            client = OpenAIOkHttpClient.builder()
                    .fromEnv()
                    .dispatcherExecutorService(httpExecutor)
                    .streamHandlerExecutor(streamExecutor)
                    .build();

            ResponseCreateParams request = ResponseCreateParams.builder()
                    .model("gpt-5.2")
                    .input(prompt) // Keep: single .input() call
                    .build();

            Response response = client.responses().create(request);

            String output = response.output().stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .map(outputText -> outputText.text())
                    .collect(Collectors.joining());

            // Log raw model output before parsing.
            try {
                writeTmpLog("response.txt", output.getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                System.err.println("Warning: could not write /tmp response log:");
                exception.printStackTrace(System.err);
            }

            // Parse CIOP operations array JSON and apply
            List<CiopOperation> ops = parseOperationsJsonArrayStrict(output);

            // Apply in order; enforce file_write path allowlist == provided args exactly.
            Set<String> allowedPaths = inputFiles.stream()
                    .map(Path::toString)
                    .collect(Collectors.toSet());

            applyOperations(ops, allowedPaths);

        } catch (RuntimeException exception) {
            System.err.println("The OpenAI request failed: ");
            exception.printStackTrace(System.err);
            exitCode = 1;

        } catch (IOException exception) {
            System.err.println("Failed while writing outputs: ");
            exception.printStackTrace(System.err);
            exitCode = 1;

        } finally {
            // Keep: graceful client close + wait for executors
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException exception) {
                    System.err.println("The OpenAI client did not close cleanly: ");
                    exception.printStackTrace(System.err);
                    exitCode = 1;
                }
            }

            boolean httpStopped = shutdownAndAwait(httpExecutor, "OpenAI HTTP executor");
            boolean streamStopped = shutdownAndAwait(streamExecutor, "OpenAI stream executor");

            if (!httpStopped || !streamStopped) {
                exitCode = 1;
            }
        }

        return exitCode;
    }

    private static List<Path> collectInputFiles(String[] args) {
        List<Path> paths = new ArrayList<>();

        if (args == null || args.length == 0) {
            return paths;
        }

        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            paths.add(Path.of(arg));
        }

        return paths;
    }

    /**
     * Build a CIOP/1.0 envelope with:
     * - STDIN section (always present)
     * - FILE sections for each arg (including missing -> encoding=missing)
     *
     * Payload is always base64 for byte-safety and to avoid any marker collisions.
     * (CIOP supports utf-8 but base64 is the safe default for arbitrary binary input.)
     */
    private static String buildCiopPrompt(byte[] stdinData, List<Path> inputFiles) throws IOException {
        StringBuilder sb = new StringBuilder();

        sb.append(CIOP_BEGIN).append('\n');
        // STDIN section (always included, even if empty)
        sb.append(buildSection(
                "STDIN",
                "base64",
                stdinData,
                null
        ));

        if (inputFiles != null && !inputFiles.isEmpty()) {
            for (Path path : inputFiles) {
                // Support non-existent files as args: encode as "missing" to disambiguate from empty.
                if (!Files.exists(path)) {
                    sb.append(buildMissingFileSection(path.toString()));
                    continue;
                }

                byte[] fileData;
                try {
                    fileData = Files.readAllBytes(path);
                } catch (IOException exception) {
                    // Treat unreadable as missing at request time (still non-destructive).
                    sb.append(buildMissingFileSection(path.toString()));
                    continue;
                }

                sb.append(buildSection(
                        "FILE",
                        "base64",
                        fileData,
                        "path=" + path.toString()
                ));
            }
        }

        sb.append(CIOP_END).append('\n');

        // Strongly discouraged by spec but allowed: add minimal response instructions AFTER envelope.
        sb.append('\n').append(CIOP_RESPONSE_INSTRUCTIONS);

        return sb.toString();
    }

    private static String buildMissingFileSection(String pathString) {
        // encoding=missing, length=0, sha256 = sha256(empty)
        byte[] empty = new byte[0];
        String sha = sha256Hex(empty);
        return new StringBuilder()
                .append("---SECTION FILE missing 0 ").append(sha).append(' ')
                .append("path=").append(pathString)
                .append("---\n")
                // No payload block for missing; still follow "payload begins next line" notion by just ending section.
                // Since length is 0, recipient should decode to empty bytes.
                .append('\n')
                .append(CIOP_END_SECTION).append('\n')
                .toString();
    }

    private static String buildSection(
            String source,          // STDIN or FILE
            String encoding,        // base64 or utf-8
            byte[] rawBytes,
            String metaOrNull       // e.g. "path=notes.txt"
    ) {
        if (!"STDIN".equals(source) && !"FILE".equals(source)) {
            throw new IllegalArgumentException("Invalid CIOP source: " + source);
        }
        if (!"base64".equals(encoding) && !"utf-8".equals(encoding)) {
            throw new IllegalArgumentException("Invalid CIOP encoding: " + encoding);
        }

        String sha = sha256Hex(rawBytes);
        int length = rawBytes.length;

        String header = "---SECTION " + source + " " + encoding + " " + length + " " + sha +
                (metaOrNull == null ? "" : (" " + metaOrNull)) +
                "---";

        String payloadText;
        if ("utf-8".equals(encoding)) {
            // Only safe if bytes are valid UTF-8; for our default we always use base64.
            String utf8 = decodeStrictUtf8(rawBytes);
            if (utf8 == null) {
                // Fall back to base64 if caller tried utf-8 on non-utf8 bytes.
                encoding = "base64";
                header = "---SECTION " + source + " " + encoding + " " + length + " " + sha +
                        (metaOrNull == null ? "" : (" " + metaOrNull)) +
                        "---";
                payloadText = Base64.getEncoder().encodeToString(rawBytes);
            } else {
                payloadText = utf8;
            }
        } else {
            payloadText = Base64.getEncoder().encodeToString(rawBytes);
        }

        return new StringBuilder()
                .append(header).append('\n')
                .append(payloadText).append('\n')
                .append(CIOP_END_SECTION).append('\n')
                .toString();
    }

    /**
     * Returns null instead of silently replacing malformed byte sequences.
     */
    private static String decodeStrictUtf8(byte[] data) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            // Lowercase hex as required
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen on a normal JVM
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ---------------- CIOP output operations parsing/apply ----------------

    private enum OpEncoding {
        UTF_8("utf-8"),
        BASE64("base64");

        final String token;

        OpEncoding(String token) {
            this.token = token;
        }

        static OpEncoding fromToken(String token) {
            if (token == null) return null;
            String t = token.toLowerCase(Locale.ROOT);
            return switch (t) {
                case "utf-8" -> UTF_8;
                case "base64" -> BASE64;
                default -> null;
            };
        }
    }

    private static final class CiopOperation {
        final String op;
        final String path; // for file_write
        final OpEncoding encoding;
        final String data;
        final Long length;   // optional
        final String sha256; // optional (lowercase hex expected)

        CiopOperation(String op, String path, OpEncoding encoding, String data, Long length, String sha256) {
            this.op = op;
            this.path = path;
            this.encoding = encoding;
            this.data = data;
            this.length = length;
            this.sha256 = sha256;
        }

        byte[] decodeDataBytes() {
            if (encoding == null) {
                throw new IllegalArgumentException("Operation missing/invalid encoding");
            }
            if (data == null) {
                throw new IllegalArgumentException("Operation missing data");
            }
            return switch (encoding) {
                case UTF_8 -> data.getBytes(StandardCharsets.UTF_8);
                case BASE64 -> {
                    String normalized = data.strip();
                    if (normalized.isEmpty()) {
                        yield new byte[0];
                    }
                    yield Base64.getDecoder().decode(normalized);
                }
            };
        }
    }

    private static void applyOperations(List<CiopOperation> ops, Set<String> allowedFilePaths) throws IOException {
        if (ops == null) {
            throw new IllegalArgumentException("Operations array was null");
        }

        for (CiopOperation op : ops) {
            if (op == null || op.op == null) {
                throw new IllegalArgumentException("Invalid operation (null)");
            }

            byte[] bytes = op.decodeDataBytes();

            // Optional verification (recommended by spec): if present and mismatched, abort file writes.
            if (strictMode && op.length != null && op.length != bytes.length) {
                throw new IllegalArgumentException(
                        "Output verification failed for op=" + op.op + ": length mismatch; expected " + op.length + " got " + bytes.length
                );
            }
            if (strictMode && op.sha256 != null) {
                String expected = op.sha256.toLowerCase(Locale.ROOT);
                String actual = sha256Hex(bytes);
                if (!actual.equals(expected)) {
                    throw new IllegalArgumentException(
                            "Output verification failed for op=" + op.op + ": sha256 mismatch; expected " + expected + " got " + actual
                    );
                }
            }

            switch (op.op) {
                case "stdout" -> {
                    System.out.write(bytes);
                    System.out.flush();
                }
                case "file_write" -> {
                    if (op.path == null || op.path.isBlank()) {
                        throw new IllegalArgumentException("file_write missing path");
                    }
                    // Security requirement: impossible to write any file not supplied as args.
                    if (!allowedFilePaths.contains(op.path)) {
                        throw new IllegalArgumentException(
                                "Rejected file_write to non-allowlisted path: " + op.path
                        );
                    }

                    Path out = Path.of(op.path);
                    Path parent = out.getParent();
                    if (parent != null) {
                        // Fix: gracefully handle creation of any necessary parent directories.
                        Files.createDirectories(parent);
                    }

                    Files.write(out, bytes);
                }
                default -> throw new IllegalArgumentException("Unknown op: " + op.op);
            }
        }
    }

    /**
     * Strictly parses the model output as exactly one JSON value (array).
     * No leading/trailing non-whitespace.
     */
    private static List<CiopOperation> parseOperationsJsonArrayStrict(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Model output was null");
        }

        final JsonElement root;
        try {
            root = com.google.gson.JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Model output was not valid JSON", e);
        }

        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Model output must be a top-level JSON array");
        }

        JsonArray arr = root.getAsJsonArray();
        List<CiopOperation> ops = new ArrayList<>();

        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) {
                throw new IllegalArgumentException("Operations array must contain only objects");
            }
            JsonObject obj = el.getAsJsonObject();

            String op = getAsStringOrNull(obj.get("op"));
            String path = getAsStringOrNull(obj.get("path"));
            OpEncoding enc = OpEncoding.fromToken(getAsStringOrNull(obj.get("encoding")));
            String data = getAsStringOrNull(obj.get("data"));
            Long length = getAsLongOrNull(obj.get("length"));
            String sha256 = getAsStringOrNull(obj.get("sha256"));

            if (op == null || op.isBlank()) {
                throw new IllegalArgumentException("Operation missing 'op'");
            }
            if ("stdout".equals(op)) {
                // tolerate but ignore path
            } else if ("file_write".equals(op)) {
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException("file_write missing 'path'");
                }
            } else {
                throw new IllegalArgumentException("Unknown op: " + op);
            }
            if (enc == null) {
                throw new IllegalArgumentException("Operation has unknown/unsupported encoding: " + getAsStringOrNull(obj.get("encoding")));
            }
            if (data == null) {
                throw new IllegalArgumentException("Operation missing 'data'");
            }

            ops.add(new CiopOperation(op, path, enc, data, length, sha256));
        }

        return ops;
    }

    private static String getAsStringOrNull(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
        }
        return null;
    }

    private static Long getAsLongOrNull(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        try {
            // Enforce integer-ness
            double d = p.getAsDouble();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            long l = (long) d;
            if (Math.abs(d - l) < 1e-9) return l;
            return null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------------- existing shutdown/log helpers (kept) ----------------

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger threadNumber = new AtomicInteger(1);

        return task -> {
            Thread thread = new Thread(task, prefix + threadNumber.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }

    private static boolean shutdownAndAwait(ExecutorService executor, String description) {
        executor.shutdown();

        try {
            if (executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }

            System.err.printf(
                    "%s did not terminate gracefully within %d seconds.%n",
                    description,
                    SHUTDOWN_TIMEOUT_SECONDS
            );

            return false;

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println(
                    "Interrupted while waiting for " + description + " to terminate."
            );

            return false;
        }
    }

    private static void writeTmpLog(String suffix, byte[] data) throws IOException {
        String ts = LOG_TS.format(Instant.now());
        String pid = getPidBestEffort();
        int seq = LOG_SEQ.getAndIncrement();

        String filename = "ai-" + ts + "-" + pid + "-" + seq + "-" + suffix;
        Path path = Path.of("/tmp", filename);

        Files.write(
                path,
                data,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    private static String getPidBestEffort() {
        try {
            return Long.toString(ProcessHandle.current().pid());
        } catch (Throwable ignored) {
            String jvmName = ManagementFactory.getRuntimeMXBean().getName();
            int at = jvmName.indexOf('@');
            if (at > 0) {
                return jvmName.substring(0, at);
            }
            return jvmName;
        }
    }
}
