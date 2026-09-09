package org.res.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.concurrent.atomic.AtomicInteger;
import java.lang.management.ManagementFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Routes prompts through an LLM provider and applies the returned CIOP operations. */
public final class AICommandApplication implements AutoCloseable {
    private static final String BEGIN = "---BEGIN CIOP/1.0---";
    private static final String END = "---END CIOP/1.0---";
    private static final String END_SECTION = "---END SECTION---";
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS").withZone(ZoneId.systemDefault());
    private static final AtomicInteger LOG_SEQ = new AtomicInteger(1);
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final String RESPONSE_INSTRUCTIONS = """
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
            - Input section length and sha256 describe the original bytes, before base64 encoding.
            - For an input utf-8 section, the payload is literal UTF-8 text, not a JSON string.
              The newline immediately before ---END SECTION--- is framing, not part of the payload.
              Preserve all other payload whitespace, including any trailing newline.
            - For encoding "utf-8": output "data" is a JSON string; interpret bytes as UTF-8.
            - For encoding "base64": "data" is standard RFC4648 base64 of raw bytes.
            Now process the CIOP/1.0 envelope provided.
            """;

    private final LLMProvider provider;
    private final OutputStream stdout;
    private final Path workingDirectory;

    public AICommandApplication(LLMProvider provider, OutputStream stdout) {
        this(provider, stdout, Path.of("."));
    }

    public AICommandApplication(LLMProvider provider, OutputStream stdout, Path workingDirectory) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.stdout = Objects.requireNonNull(stdout, "stdout");
        this.workingDirectory = canonicalWorkingDirectory(
                Objects.requireNonNull(workingDirectory, "workingDirectory"));
    }

    public void run(byte[] stdin, List<String> inputPaths) throws IOException {
        List<String> paths = inputPaths == null ? List.of() : List.copyOf(inputPaths);
        executePrompt(buildPrompt(stdin == null ? new byte[0] : stdin, paths), new HashSet<>(paths));
    }

    /** Routes an already-created prompt and processes its response. Useful for focused tests. */
    public void executePrompt(String prompt, Set<String> allowedFilePaths) throws IOException {
        String nonNullPrompt = Objects.requireNonNull(prompt, "prompt");
        try {
            writeTmpLog("prompt.txt", nonNullPrompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            System.err.println("Warning: could not write /tmp prompt log:");
            exception.printStackTrace(System.err);
        }

        String response;
        provider.initialize();
        response = provider.complete(nonNullPrompt);

        try {
            writeTmpLog("response.txt", response.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            System.err.println("Warning: could not write /tmp response log:");
            exception.printStackTrace(System.err);
        }

        applyOperations(parseOperations(response), allowedFilePaths == null ? Set.of() : Set.copyOf(allowedFilePaths));
    }

    private String buildPrompt(byte[] stdin, List<String> inputPaths) throws IOException {
        StringBuilder result = new StringBuilder(BEGIN).append('\n');
        result.append(section("STDIN", stdin, null));
        for (String pathText : inputPaths) {
            Path file = resolve(pathText);
            if (!Files.exists(file, NO_FOLLOW) || !Files.isRegularFile(file, NO_FOLLOW)) {
                result.append("---SECTION FILE missing 0 ")
                        .append(sha256(new byte[0])).append(" path=").append(pathText).append("---\n\n")
                        .append(END_SECTION).append('\n');
            } else {
                result.append(section("FILE", Files.readAllBytes(file), "path=" + pathText));
            }
        }
        return result.append(END).append("\n\n").append(RESPONSE_INSTRUCTIONS).toString();
    }

    private static String section(String source, byte[] bytes, String metadata) {
        String text = strictUtf8(bytes);
        String encoding = text == null || !safeText(text) ? "base64" : "utf-8";
        String payload = "base64".equals(encoding) ? Base64.getEncoder().encodeToString(bytes) : text;
        return "---SECTION " + source + " " + encoding + " " + bytes.length + " " + sha256(bytes)
                + (metadata == null ? "" : " " + metadata) + "---\n"
                + payload + "\n" + END_SECTION + "\n";
    }

    private void applyOperations(List<Operation> operations, Set<String> allowedPaths) throws IOException {
        for (Operation operation : operations) {
            byte[] bytes = operation.decode();
            if (operation.length != null && operation.length != bytes.length) {
                throw new IllegalArgumentException("Output length verification failed");
            }
            if (operation.sha256 != null
                    && !sha256(bytes).equals(operation.sha256.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Output sha256 verification failed");
            }
            switch (operation.type) {
                case STDOUT -> {
                    stdout.write(bytes);
                    stdout.flush();
                }
                case FILE_WRITE -> {
                    if (operation.path == null || !allowedPaths.contains(operation.path)) {
                        throw new IllegalArgumentException(
                                "Rejected file_write to non-allowlisted path: " + operation.path);
                    }
                    writeFile(operation.path, bytes);
                }
            }
        }
    }

    private void writeFile(String pathText, byte[] bytes) throws IOException {
        Path target = resolve(pathText);
        createDirectoriesWithoutFollowingLinks(target.getParent());
        target = resolve(pathText);
        Files.write(target, bytes, new OpenOption[] {
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
        });
    }

    private void createDirectoriesWithoutFollowingLinks(Path directory) throws IOException {
        if (directory == null) return;
        Path relative = workingDirectory.relativize(directory);
        Path current = workingDirectory;
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.exists(current, NO_FOLLOW)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NO_FOLLOW)) {
                    throw new IllegalArgumentException("Unsafe output directory: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private Path resolve(String pathText) {
        Objects.requireNonNull(pathText, "pathText");
        Path supplied = Path.of(pathText);
        if (supplied.isAbsolute()) {
            throw new IllegalArgumentException("Absolute paths are not allowed: " + pathText);
        }
        for (Path component : supplied) {
            if ("..".equals(component.toString())) {
                throw new IllegalArgumentException("Parent path components are not allowed: " + pathText);
            }
        }

        Path resolved = workingDirectory.resolve(supplied).normalize();
        if (!resolved.startsWith(workingDirectory)) {
            throw new IllegalArgumentException("Path escapes working directory: " + pathText);
        }

        Path current = workingDirectory;
        Path relative = workingDirectory.relativize(resolved);
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic links are not allowed: " + pathText);
            }
            if (Files.exists(current, NO_FOLLOW)) {
                try {
                    Path canonical = current.toRealPath();
                    if (!canonical.startsWith(workingDirectory)) {
                        throw new IllegalArgumentException("Path escapes working directory: " + pathText);
                    }
                } catch (IOException exception) {
                    throw new IllegalArgumentException("Cannot validate path: " + pathText, exception);
                }
            }
        }
        return resolved;
    }

    private static Path canonicalWorkingDirectory(Path directory) {
        try {
            Path canonical = directory.toRealPath();
            if (!Files.isDirectory(canonical, NO_FOLLOW)) {
                throw new IllegalArgumentException("Working directory is not a directory: " + directory);
            }
            return canonical;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot resolve working directory: " + directory, exception);
        }
    }

    private static List<Operation> parseOperations(String text) {
        final JsonElement root;
        try {
            root = com.google.gson.JsonParser.parseString(text);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Model output was not valid JSON", exception);
        }
        if (!root.isJsonArray()) throw new IllegalArgumentException("Model output must be a JSON array");
        List<Operation> operations = new ArrayList<>();
        JsonArray array = root.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Operation must be an object");
            JsonObject object = element.getAsJsonObject();
            OperationType type = OperationType.fromOpcode(requiredString(object, "op"));
            String encoding = requiredString(object, "encoding");
            String data = requiredString(object, "data");
            String path = optionalString(object, "path");
            Long length = object.has("length") ? object.get("length").getAsLong() : null;
            String hash = optionalString(object, "sha256");
            if (!"utf-8".equals(encoding) && !"base64".equals(encoding)) {
                throw new IllegalArgumentException("Unsupported encoding: " + encoding);
            }
            operations.add(new Operation(type, path, encoding, data, length, hash));
        }
        return operations;
    }

    private static String requiredString(JsonObject object, String name) {
        String value = optionalString(object, name);
        if (value == null) throw new IllegalArgumentException("Operation missing '" + name + "'");
        return value;
    }

    private static String optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? null : value.getAsString();
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private static boolean safeText(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isISOControl(ch) && ch != '\t' && ch != '\n' && ch != '\r') return false;
        }
        return text.lines().map(String::strip).noneMatch(line ->
                line.equals(BEGIN) || line.equals(END) || line.equals(END_SECTION)
                        || line.startsWith("---SECTION "));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Override
    public void close() {
        provider.close();
    }

    private static void writeTmpLog(String suffix, byte[] data) throws IOException {
        String timestamp = LOG_TS.format(Instant.now());
        String pid = getPidBestEffort();
        int sequence = LOG_SEQ.getAndIncrement();
        Path path = Path.of("/tmp",
                "ai-" + timestamp + "-" + pid + "-" + sequence + "-" + suffix);
        Files.write(path, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String getPidBestEffort() {
        try {
            return Long.toString(ProcessHandle.current().pid());
        } catch (Throwable ignored) {
            String jvmName = ManagementFactory.getRuntimeMXBean().getName();
            int at = jvmName.indexOf('@');
            return at > 0 ? jvmName.substring(0, at) : jvmName;
        }
    }
}
