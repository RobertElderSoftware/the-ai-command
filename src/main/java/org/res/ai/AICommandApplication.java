package org.res.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.atomic.AtomicInteger;

/** Routes prompts through an LLM provider and applies returned CIOP operations. */
public class AICommandApplication implements AutoCloseable {
    private static final String BEGIN = "---BEGIN CIOP/1.0---";
    private static final String END = "---END CIOP/1.0---";
    private static final String END_SECTION = "---END SECTION---";
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSS").withZone(ZoneId.systemDefault());
    private static final AtomicInteger LOG_SEQ = new AtomicInteger(1);
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private final LLMProvider provider;
    private final OutputStream stdout;
    private final Path workingDirectory;
    private final boolean loggingEnabled;

    public AICommandApplication(LLMProvider provider, OutputStream stdout) {
        this(provider, stdout, Path.of("."));
    }

    public AICommandApplication(LLMProvider provider, OutputStream stdout, Path workingDirectory) {
        this(provider, stdout, workingDirectory,
                Objects.requireNonNull(provider, "provider").isLoggingEnabled());
    }

    /** Overrides the provider's default prompt/response file logging policy. */
    public AICommandApplication(LLMProvider provider, OutputStream stdout,
            Path workingDirectory, boolean loggingEnabled) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.stdout = Objects.requireNonNull(stdout, "stdout");
        this.workingDirectory = WorkingDirectoryPaths.canonical(Objects.requireNonNull(workingDirectory, "workingDirectory"));
        this.loggingEnabled = loggingEnabled;
    }

    public void run(byte[] stdin, List<String> inputPaths) throws IOException {
        run(stdin, inputPaths, RequestContext.empty());
    }

    public void run(byte[] stdin, List<String> inputPaths, RequestContext context) throws IOException {
        List<ContextFile> files = Objects.requireNonNull(context, "context")
                .merge(new WorkingDirectoryPaths(workingDirectory), inputPaths);
        Set<String> writable = new HashSet<>();
        Set<String> readOnly = new HashSet<>();
        for (ContextFile file : files)
            (file.access() == FileAccess.READ_WRITE ? writable : readOnly).add(file.path());
        executePrompt(buildPrompt(stdin == null ? new byte[0] : stdin, files), writable, readOnly);
    }

    public void executePrompt(String prompt, Set<String> allowedFilePaths) throws IOException {
        executePrompt(prompt, allowedFilePaths, Set.of());
    }

    private void executePrompt(String prompt, Set<String> allowedFilePaths,
            Set<String> readOnlyPaths) throws IOException {
        String value = Objects.requireNonNull(prompt, "prompt");
        log("prompt.txt", value);
        provider.initialize();
        String response = provider.complete(value);
        log("response.txt", response);
        applyOperations(parseOperations(response),
                allowedFilePaths == null ? Set.of() : Set.copyOf(allowedFilePaths), readOnlyPaths);
    }

    private void log(String suffix, String text) {
        if (!loggingEnabled) return;
        try {
            writeTmpLog(suffix, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            System.err.println("Warning: could not write /tmp " + suffix + " log:");
            exception.printStackTrace(System.err);
        }
    }

    private String buildPrompt(byte[] stdin, List<ContextFile> files) throws IOException {
        StringBuilder result = new StringBuilder(BEGIN).append('\n');
        result.append(section("FILE", ProtocolDocument.bytes(),
                "access=READ path=" + ProtocolDocument.PATH));
        for (ContextFile entry : files) {
            Path file = resolve(entry.path());
            String metadata = "access=" + entry.access() + " path=" + entry.path();
            if (Files.notExists(file, NO_FOLLOW) && entry.access() == FileAccess.READ_WRITE) {
                result.append("---SECTION FILE missing 0 ").append(sha256(new byte[0]))
                        .append(' ').append(metadata).append("---\n\n")
                        .append(END_SECTION).append('\n');
            } else {
                result.append(section("FILE", WorkingDirectoryPaths.read(file), metadata));
            }
        }
        result.append(section("STDIN", stdin, null));
        return result.append(END).append('\n').toString();
    }

    private static String section(String source, byte[] bytes, String metadata) {
        String text = strictUtf8(bytes);
        String encoding = text == null || !safeText(text) ? "base64" : "utf-8";
        String payload = "base64".equals(encoding) ? Base64.getEncoder().encodeToString(bytes) : text;
        return "---SECTION " + source + " " + encoding + " " + bytes.length + " " + sha256(bytes)
                + (metadata == null ? "" : " " + metadata) + "---\n"
                + payload + "\n" + END_SECTION + "\n";
    }

    private void applyOperations(List<Operation> operations, Set<String> allowedPaths,
            Set<String> readOnlyPaths) throws IOException {
        java.util.Map<Path, byte[]> originals = new java.util.LinkedHashMap<>();
        java.util.Map<Path, byte[]> staged = new java.util.LinkedHashMap<>();
        java.util.Map<Path, Object> identities = new java.util.LinkedHashMap<>();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        for (int index = 0; index < operations.size(); index++) {
            Operation operation = operations.get(index);
            try {
                byte[] bytes = null;
                if (operation.type != OperationType.FILE_PATCH) {
                    bytes = operation.decode();
                    if (operation.length != null && operation.length != bytes.length)
                        throw new IllegalArgumentException("Output length verification failed");
                    if (operation.sha256 != null && !sha256(bytes).equalsIgnoreCase(operation.sha256))
                        throw new IllegalArgumentException("Output sha256 verification failed");
                }
                if (operation.type == OperationType.STDOUT) {
                    output.writeBytes(bytes);
                    continue;
                }
                requireAllowed(operation.path, allowedPaths, operation.type.opcode());
                Path target = resolve(operation.path);
                requireWritable(target, readOnlyPaths);
                if (!originals.containsKey(target)) {
                    for (Path other : originals.keySet()) {
                        if (target.startsWith(other) || other.startsWith(target))
                            throw new IllegalArgumentException("Conflicting file/directory destinations: " + target + " and " + other);
                        if (Files.exists(target, NO_FOLLOW) && Files.exists(other, NO_FOLLOW)
                                && Files.isSameFile(target, other))
                            throw new IllegalArgumentException("Distinct hard-linked destinations: " + target + " and " + other);
                    }
                    for (Path parent = target.getParent(); parent != null && parent.startsWith(workingDirectory);
                            parent = parent.getParent()) {
                        if (Files.exists(parent, NO_FOLLOW) && !Files.isDirectory(parent, NO_FOLLOW))
                            throw new IllegalArgumentException("Unsafe output directory: " + parent);
                    }
                    originals.put(target, readTarget(target));
                    identities.put(target, fileIdentity(target));
                }
                if (operation.type == OperationType.FILE_PATCH) {
                    byte[] source = staged.containsKey(target) ? staged.get(target) : originals.get(target);
                    if (source == null)
                        throw new IllegalArgumentException("file_patch requires an existing regular file: " + operation.path);
                    bytes = calculatePatch(operation.patch, source);
                }
                staged.put(target, bytes);
            } catch (IOException | RuntimeException exception) {
                String message = "Operation " + (index + 1) + "/" + operations.size()
                        + " (" + operation.type.opcode()
                        + (operation.path == null ? "" : ", path=" + quote(operation.path))
                        + ") rejected: " + exception.getMessage()
                        + ". Batch validation failed; no destination files or stdout were changed.";
                if (exception instanceof IOException) throw new IOException(message, exception);
                throw new IllegalArgumentException(message, exception);
            }
        }
        // Recheck the entire snapshot before the first filesystem mutation.
        for (Path target : staged.keySet()) {
            requireWritable(target, readOnlyPaths);
            checkSnapshot(target, originals.get(target), identities.get(target));
        }
        List<Path> attempted = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        try {
            for (Path target : staged.keySet()) {
                requireWritable(target, readOnlyPaths);
                checkSnapshot(target, originals.get(target), identities.get(target));
                createBatchDirectories(target.getParent(), directories);
                attempted.add(target); // Include a destination even if its write fails partway through.
                commitFile(target, staged.get(target));
            }
        } catch (IOException | RuntimeException failure) {
            List<Exception> recoveryFailures = new ArrayList<>();
            for (int index = attempted.size() - 1; index >= 0; index--) {
                Path target = attempted.get(index);
                try {
                    restoreFile(target, originals.get(target));
                } catch (IOException | RuntimeException exception) {
                    recoveryFailures.add(new IOException("Could not restore " + target, exception));
                }
            }
            for (int index = directories.size() - 1; index >= 0; index--) {
                Path directory = directories.get(index);
                try {
                    resolve(relative(directory));
                    Files.delete(directory); // Never recursively remove contents.
                } catch (IOException | RuntimeException exception) {
                    recoveryFailures.add(new IOException("Could not remove created directory " + directory, exception));
                }
            }
            IOException exception = new IOException("Batch commit failed; "
                    + (recoveryFailures.isEmpty() ? "rollback completed." : "rollback incomplete.")
                    + " No stdout was published.", failure);
            recoveryFailures.forEach(exception::addSuppressed);
            throw exception;
        }
        try {
            output.writeTo(stdout);
            stdout.flush();
            if (stdout instanceof java.io.PrintStream stream && stream.checkError())
                throw new IOException("PrintStream reported an output error");
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Files committed; stdout publication failed and may be incomplete. Do not retry the batch automatically.", exception);
        }
    }

    private String relative(Path target) {
        return workingDirectory.relativize(target).toString();
    }

    private byte[] readTarget(Path target) throws IOException {
        if (Files.notExists(target, NO_FOLLOW)) return null;
        return WorkingDirectoryPaths.read(target);
    }

    private Object fileIdentity(Path target) throws IOException {
        return Files.notExists(target, NO_FOLLOW) ? null
                : Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private void checkSnapshot(Path target, byte[] original, Object identity) throws IOException {
        resolve(relative(target));
        if (!Objects.equals(identity, fileIdentity(target))
                || !java.util.Arrays.equals(original, readTarget(target)))
            throw new IOException("Batch snapshot changed before commit: " + target
                    + "; this destination was not written by the current commit attempt");
    }

    private void createBatchDirectories(Path directory, List<Path> created) throws IOException {
        if (directory.equals(workingDirectory)) return;
        Path current = workingDirectory;
        for (Path component : workingDirectory.relativize(directory)) {
            current = resolve(relative(current.resolve(component)));
            if (Files.notExists(current, NO_FOLLOW)) {
                Files.createDirectory(current);
                created.add(current);
            } else if (!Files.isDirectory(current, NO_FOLLOW)) {
                throw new IOException("Unsafe output directory: " + current);
            }
        }
    }

    /** Override only for deterministic commit-failure tests. */
    protected void commitFile(Path target, byte[] bytes) throws IOException {
        writeOrReplace(target, bytes);
    }

    /** Restore bytes independently of an overridden commit hook. */
    protected void restoreFile(Path target, byte[] original) throws IOException {
        writeOrReplace(target, original);
    }

    private void writeOrReplace(Path target, byte[] bytes) throws IOException {
        resolve(relative(target));
        if (bytes == null) {
            Files.deleteIfExists(target);
        } else if (Files.exists(target, NO_FOLLOW)) {
            atomicReplace(relative(target), target, bytes);
        } else {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static void requireAllowed(String path, Set<String> allowedPaths, String opcode) {
        if (path == null || !allowedPaths.contains(path)) {
            throw new IllegalArgumentException("Rejected " + opcode + " to non-allowlisted path: " + path);
        }
    }

    private byte[] calculatePatch(FilePatch patch, byte[] sourceBytes) {
        String actualHash = sha256(sourceBytes);
        String expectedHash = normalizeHash(patch.sourceSha256(), "source_sha256");
        if (!actualHash.equals(expectedHash)) {
            throw new IllegalArgumentException("file_patch source_sha256 mismatch: expected="
                    + expectedHash + ", actual=" + actualHash + "; target was not modified by this patch");
        }
        String source = strictUtf8(sourceBytes);
        if (source == null) throw new IllegalArgumentException("file_patch source is not valid UTF-8");
        boolean sourceFinalNewline = source.endsWith("\n");
        List<String> oldLines = sourceLines(source);
        List<String> result = new ArrayList<>();
        int oldCursor = 0;
        for (int hunkIndex = 0; hunkIndex < patch.hunks().size(); hunkIndex++) {
            PatchHunk hunk = patch.hunks().get(hunkIndex);
            String location = "hunk " + (hunkIndex + 1) + " (old_start=" + hunk.oldStart()
                    + ", old_count=" + hunk.oldCount() + ", new_start=" + hunk.newStart()
                    + ", new_count=" + hunk.newCount() + ")";
            int hunkStart = hunk.oldStart() - 1;
            if (hunkStart < oldCursor || hunkStart > oldLines.size()) {
                throw new IllegalArgumentException(location + ": hunks overlap or start outside the source; "
                        + "next source line=" + (oldCursor + 1) + ", source line count=" + oldLines.size());
            }
            while (oldCursor < hunkStart) result.add(oldLines.get(oldCursor++));
            if (hunk.newStart() != result.size() + 1) {
                throw new IllegalArgumentException(location + ": new_start must be " + (result.size() + 1));
            }
            for (int lineIndex = 0; lineIndex < hunk.lines().size(); lineIndex++) {
                PatchLine line = hunk.lines().get(lineIndex);
                if (line.type() != PatchLineType.ADD) {
                    requireMatchingLine(oldLines, oldCursor, line,
                            location + ", hunk line " + (lineIndex + 1));
                    oldCursor++;
                }
                if (line.type() != PatchLineType.REMOVE) result.add(line.text());
            }
        }
        while (oldCursor < oldLines.size()) result.add(oldLines.get(oldCursor++));
        boolean finalNewline = patch.finalNewline() == null ? sourceFinalNewline : patch.finalNewline();
        String resultText = String.join("\n", result) + (finalNewline ? "\n" : "");
        byte[] resultBytes = resultText.getBytes(StandardCharsets.UTF_8);
        if (patch.resultSha256() != null
                && !sha256(resultBytes).equals(normalizeHash(patch.resultSha256(), "result_sha256"))) {
            throw new IllegalArgumentException("file_patch result_sha256 mismatch: expected="
                    + patch.resultSha256() + ", actual=" + sha256(resultBytes)
                    + "; target was not modified by this patch");
        }
        return resultBytes;
    }

    /** Empty files have no lines; a newline by itself terminates one empty line. */
    private static List<String> sourceLines(String source) {
        if (source.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(List.of(source.split("\n", -1)));
        // Strip CR only where it is part of a CRLF terminator, never at bare EOF.
        for (int index = 0; index < lines.size() - 1; index++) {
            String line = lines.get(index);
            if (line.endsWith("\r")) lines.set(index, line.substring(0, line.length() - 1));
        }
        if (source.endsWith("\n")) lines.remove(lines.size() - 1);
        return lines;
    }

    private static void requireMatchingLine(List<String> lines, int index,
            PatchLine line, String location) {
        if (index >= lines.size() || !lines.get(index).equals(line.text())) {
            throw new IllegalArgumentException(location + " (" + line.type().token()
                    + "), source line " + (index + 1)
                    + ": patch text=" + quote(line.text()) + ", source text="
                    + (index >= lines.size() ? "<EOF>" : quote(lines.get(index)))
                    + "; source_sha256 matched, but context/removal text did not; "
                    + "target was not modified by this patch");
        }
    }

    /** JSON escaping makes whitespace differences visible without emitting raw control characters. */
    private static String quote(String text) {
        String preview = text.length() <= 240 ? text : text.substring(0, 240);
        return new JsonPrimitive(preview).toString()
                + (text.length() <= 240 ? "" : " [truncated; " + text.length() + " UTF-16 units]");
    }

    private void atomicReplace(String pathText, Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        Path temporary = Files.createTempFile(parent, ".ciop-patch-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            Path checkedTarget = resolve(pathText);
            if (!checkedTarget.equals(target) || !Files.isRegularFile(checkedTarget, NO_FOLLOW)) {
                throw new IllegalArgumentException("Patch target changed during validation");
            }
            var permissions = Files.getFileAttributeView(checkedTarget,
                    java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (permissions != null)
                Files.setPosixFilePermissions(temporary, permissions.readAttributes().permissions());
            try {
                Files.move(temporary, checkedTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, checkedTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }


    private Path resolve(String pathText) {
        return WorkingDirectoryPaths.resolve(workingDirectory, pathText);
    }

    private void requireWritable(Path target, Set<String> readOnlyPaths) throws IOException {
        WorkingDirectoryPaths.rejectReserved(workingDirectory, target);
        for (String path : readOnlyPaths) {
            Path protectedFile = resolve(path);
            if (target.startsWith(protectedFile) || protectedFile.startsWith(target)
                    || WorkingDirectoryPaths.sameFile(target, protectedFile))
                throw new IllegalArgumentException("Rejected write affecting READ file: " + path);
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
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Operation must be an object");
            JsonObject object = element.getAsJsonObject();
            OperationType type = OperationType.fromOpcode(requiredString(object, "op"));
            if (type == OperationType.FILE_PATCH) {
                FilePatch patch = parsePatch(object);
                operations.add(new Operation(type, patch.path(), null, null, null, null, patch));
            } else {
                String encoding = requiredString(object, "encoding");
                Encoding.fromToken(encoding);
                operations.add(new Operation(type, optionalString(object, "path"), encoding,
                        requiredString(object, "data"), optionalLong(object, "length"),
                        optionalString(object, "sha256")));
            }
        }
        return operations;
    }

    private static FilePatch parsePatch(JsonObject object) {
        String path = requiredString(object, "path");
        String sourceHash = normalizeHash(requiredString(object, "source_sha256"), "source_sha256");
        String resultHash = optionalString(object, "result_sha256");
        if (resultHash != null) resultHash = normalizeHash(resultHash, "result_sha256");
        Boolean finalNewline = optionalBoolean(object, "final_newline");
        JsonElement value = object.get("hunks");
        if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("file_patch missing 'hunks'");
        List<PatchHunk> hunks = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Patch hunk must be an object");
            JsonObject hunk = element.getAsJsonObject();
            JsonElement lineValue = hunk.get("lines");
            if (lineValue == null || !lineValue.isJsonArray()) throw new IllegalArgumentException("Patch hunk missing 'lines'");
            List<PatchLine> lines = new ArrayList<>();
            for (JsonElement lineElement : lineValue.getAsJsonArray()) {
                if (!lineElement.isJsonObject()) throw new IllegalArgumentException("Patch line must be an object");
                JsonObject line = lineElement.getAsJsonObject();
                lines.add(new PatchLine(PatchLineType.fromToken(requiredString(line, "type")), requiredString(line, "text")));
            }
            hunks.add(new PatchHunk(requiredInt(hunk, "old_start"), requiredInt(hunk, "old_count"),
                    requiredInt(hunk, "new_start"), requiredInt(hunk, "new_count"), lines));
        }
        return new FilePatch(path, sourceHash, resultHash, finalNewline, hunks);
    }

    private static String normalizeHash(String hash, String field) {
        String normalized = Objects.requireNonNull(hash, field).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " must be 64 hexadecimal characters");
        return normalized;
    }

    private static int requiredInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Missing integer '" + name + "'");
        try { return value.getAsInt(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid integer '" + name + "'", exception); }
    }

    private static Long optionalLong(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid number '" + name + "'");
        return value.getAsLong();
    }

    private static Boolean optionalBoolean(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid boolean '" + name + "'");
        return value.getAsBoolean();
    }

    private static String requiredString(JsonObject object, String name) {
        String value = optionalString(object, name);
        if (value == null) throw new IllegalArgumentException("Operation missing '" + name + "'");
        return value;
    }

    private static String optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString() ? null : value.getAsString();
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) { return null; }
    }

    private static boolean safeText(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isISOControl(ch) && ch != '\t' && ch != '\n' && ch != '\r') return false;
        }
        return text.lines().map(String::strip).noneMatch(line -> line.equals(BEGIN)
                || line.equals(END) || line.equals(END_SECTION) || line.startsWith("---SECTION "));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Override
    public void close() { provider.close(); }

    private static void writeTmpLog(String suffix, byte[] data) throws IOException {
        String timestamp = LOG_TS.format(Instant.now());
        String pid = getPidBestEffort();
        int sequence = LOG_SEQ.getAndIncrement();
        Files.write(Path.of("/tmp", "ai-" + timestamp + "-" + pid + "-" + sequence + "-" + suffix),
                data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String getPidBestEffort() {
        try { return Long.toString(ProcessHandle.current().pid()); }
        catch (Throwable ignored) {
            String jvmName = ManagementFactory.getRuntimeMXBean().getName();
            int at = jvmName.indexOf('@');
            return at > 0 ? jvmName.substring(0, at) : jvmName;
        }
    }
}
