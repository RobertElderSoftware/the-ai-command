package org.res.ai;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import static org.res.ai.InputSpacePartitionTestNode.fixtureLeaf;

import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

/** Independent context scenarios sampled by the application test loop. */
class RequestContextTest {
    InputSpacePartitionTestSpace<Path> space(Path parent) {
        return InputSpacePartitionTestSpace.inDirectory("context", parent, List.of(
                fixtureLeaf("envelope", this::envelope),
                fixtureLeaf("empty", this::empty),
                fixtureLeaf("permissions", this::permissions),
                fixtureLeaf("duplicates", this::duplicates),
                fixtureLeaf("malformed", this::malformed),
                fixtureLeaf("missing", this::missing),
                fixtureLeaf("unsafe", this::unsafe),
                fixtureLeaf("reserved", this::reserved),
                fixtureLeaf("cli", directory -> cli()),
                fixtureLeaf("late_alias", this::lateAlias)), directory -> {
            Files.writeString(directory.resolve("read.txt"), "original\n");
            Files.writeString(directory.resolve("write.txt"), "original\n");
            return directory;
        }, directory -> directory);
    }

    private void envelope(Path directory) throws Exception {
        Files.createDirectory(directory.resolve("config"));
        RequestContext context = load(directory, "config/context.json",
                contextJson(read("read.txt"), readWrite("write.txt")));
        byte[] binary = {0, (byte) 255, 10};
        Files.write(directory.resolve("binary.dat"), binary);
        CapturingLLMProvider provider = new CapturingLLMProvider("[]");
        byte[] stdin = "question: café\n".getBytes(StandardCharsets.UTF_8);
        run(directory, context, List.of("./read.txt", "write.txt", "binary.dat", "binary.dat"), stdin, provider);
        String prompt = provider.prompt();
        byte[] protocol = ProtocolDocument.bytes();
        assertTrue(prompt.startsWith("---BEGIN CIOP/1.0---\n" + sectionHeader(
                "utf-8", protocol, FileAccess.READ, ProtocolDocument.PATH)));
        assertTrue(prompt.contains(new String(protocol, StandardCharsets.UTF_8) + "\n---END SECTION---\n"));
        assertEquals(List.of(ProtocolDocument.PATH, "read.txt", "write.txt", "binary.dat"), prompt.lines()
                .filter(line -> line.startsWith("---SECTION FILE "))
                .map(line -> line.substring(line.indexOf(" path=") + 6, line.length() - 3)).toList());
        assertTrue(prompt.contains(sectionHeader("base64", binary, FileAccess.READ_WRITE, "binary.dat")
                + Base64.getEncoder().encodeToString(binary) + "\n---END SECTION---\n"));
        assertTrue(prompt.endsWith("---SECTION STDIN utf-8 " + stdin.length + " "
                + AICommandApplication.sha256(stdin) + "---\n" + new String(stdin, StandardCharsets.UTF_8)
                + "\n---END SECTION---\n---END CIOP/1.0---\n"));
        assertFalse(prompt.contains("path=config/context.json"));
        assertFalse(prompt.contains("path=./read.txt"));
        assertEquals(1, provider.calls());
    }

    private void empty(Path directory) throws Exception {
        Files.writeString(directory.resolve("context.json"), "not JSON; must not be auto-loaded");
        CapturingLLMProvider provider = new CapturingLLMProvider("[]");
        try (AICommandApplication application = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            application.run(null, null);
        }
        assertEquals(1, provider.prompt().lines().filter(line -> line.startsWith("---SECTION FILE ")).count());
        assertTrue(provider.prompt().endsWith("---END CIOP/1.0---\n"));
        assertTrue(load(directory, "empty.json", contextJson()).files().isEmpty());
    }

    private void permissions(Path directory) throws Exception {
        RequestContext context = load(directory, "context.json",
                contextJson(read("read.txt"), readWrite("write.txt")));
        Files.createLink(directory.resolve("alias.txt"), directory.resolve("read.txt"));
        List<String> arguments = List.of("./read.txt", "alias.txt", "extra.txt");
        for (boolean patch : List.of(false, true)) {
            for (String path : List.of("read.txt", "./read.txt", "alias.txt", ProtocolDocument.PATH)) {
                assertThrows(IllegalArgumentException.class,
                        () -> run(directory, context, arguments, edit(path, patch)));
                assertContents(directory, "original\n", "read.txt", "alias.txt");
            }
            Files.writeString(directory.resolve("write.txt"), "original\n");
            run(directory, context, arguments, edit("write.txt", patch));
            assertEquals("changed\n", Files.readString(directory.resolve("write.txt")));
        }
        run(directory, context, arguments, edit("extra.txt", false));
        assertEquals("changed\n", Files.readString(directory.resolve("extra.txt")));
        Files.writeString(directory.resolve("write.txt"), "original\n");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String batch = batch(stdout("hidden"), edit("write.txt", false), edit("read.txt", false));
        try (AICommandApplication application = new AICommandApplication(new CapturingLLMProvider(batch), output, directory)) {
            assertThrows(IllegalArgumentException.class, () -> application.run(new byte[0], arguments, context));
        }
        assertEquals(0, output.size());
        assertEquals("original\n", Files.readString(directory.resolve("write.txt")));
    }

    private void duplicates(Path directory) throws Exception {
        Files.createLink(directory.resolve("alias.txt"), directory.resolve("read.txt"));
        for (String duplicate : List.of("read.txt", "./read.txt", "alias.txt")) {
            String json = contextJson(read("read.txt"), readWrite(duplicate));
            assertThrows(IllegalArgumentException.class, () -> load(directory, "context.json", json));
        }
        assertThrows(IllegalArgumentException.class, () -> load(directory, "context.json",
                contextJson(readWrite("new.txt"), readWrite("./new.txt"))));
        RequestContext context = new RequestContext(List.of(new ContextFile("read.txt", FileAccess.READ)));
        List<ContextFile> merged = context.merge(new WorkingDirectoryPaths(directory),
                List.of("alias.txt", "./read.txt", "new.txt", "./new.txt"));
        assertEquals(List.of("read.txt", "new.txt"), merged.stream().map(ContextFile::path).toList());
        assertEquals(FileAccess.READ, merged.get(0).access());
    }

    private void malformed(Path directory) throws Exception {
        for (String json : List.of("{}", "[]", "null", "{files:[]}", "{\"files\":null}",
                "{\"files\":[],\"files\":[]}", "{\"files\":[],\"extra\":1}", "{\"files\":[]} {}",
                "{\"files\":[{}]}", "{\"files\":[null]}", "{\"files\":[\"read.txt\"]}",
                "{\"files\":[{\"read.txt\":\"read\"}]}", "{\"files\":[{\"read.txt\":\"UNKNOWN\"}]}",
                "{\"files\":[{\"read.txt\":true}]}", "{\"files\":[{\"read.txt\":null}]}",
                "{\"files\":[{\"read.txt\":\"READ\",\"read.txt\":\"READ_WRITE\"}]}",
                "{\"files\":[{\"read.txt\":\"READ\",\"write.txt\":\"READ\"}]}")) {
            assertThrows(Exception.class, () -> load(directory, "context.json", json), json);
        }
    }

    private void missing(Path directory) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> load(directory, "context.json",
                contextJson(read("absent.txt"))));
        RequestContext context = load(directory, "context.json",
                contextJson(readWrite("new/deep.txt")));
        CapturingLLMProvider provider = run(directory, context, List.of(), edit("new/deep.txt", false));
        assertTrue(provider.prompt().contains(sectionHeader("missing", new byte[0], FileAccess.READ_WRITE, "new/deep.txt")));
        assertEquals("changed\n", Files.readString(directory.resolve("new/deep.txt")));
        Files.createDirectory(directory.resolve("folder"));
        for (FileAccess access : FileAccess.values()) {
            RequestContext invalid = new RequestContext(List.of(new ContextFile("folder", access)));
            assertThrows(IllegalArgumentException.class,
                    () -> invalid.merge(new WorkingDirectoryPaths(directory), List.of()));
        }
        RequestContext read = new RequestContext(List.of(new ContextFile("read.txt", FileAccess.READ)));
        Files.delete(directory.resolve("read.txt"));
        CapturingLLMProvider unused = new CapturingLLMProvider("[]");
        assertThrows(IllegalArgumentException.class, () -> run(directory, read, List.of(), new byte[0], unused));
        assertEquals(0, unused.calls());
    }

    private void unsafe(Path directory) throws Exception {
        Path outside = Files.createDirectory(directory.resolveSibling(directory.getFileName() + "-outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "protected");
        Files.writeString(outside.resolve("context.json"), contextJson());
        Files.createSymbolicLink(directory.resolve("link"), outside);
        Files.createSymbolicLink(directory.resolve("internal-link"), directory.resolve("read.txt"));
        assertFalse(secret.toRealPath().startsWith(directory.toRealPath()));
        for (String path : List.of("../secret.txt", "a/../../secret.txt", "one/two/../../../secret.txt",
                "foo/../..", "..", secret.toAbsolutePath().toString(), "link/secret.txt", "link/new.txt", "internal-link")) {
            CapturingLLMProvider unused = new CapturingLLMProvider("[]");
            RequestContext context = new RequestContext(List.of(new ContextFile(path, FileAccess.READ_WRITE)));
            assertThrows(IllegalArgumentException.class, () -> run(directory, context, List.of(), new byte[0], unused));
            assertThrows(IllegalArgumentException.class,
                    () -> run(directory, RequestContext.empty(), List.of(path), new byte[0], unused));
            assertEquals(0, unused.calls());
        }
        for (String path : List.of("link/context.json", "../context.json", outside.resolve("context.json").toString()))
            assertThrows(IllegalArgumentException.class, () -> RequestContext.load(directory, path));
        assertEquals("protected", Files.readString(secret));
        assertFalse(Files.exists(outside.resolve("new.txt")));
    }

    private void reserved(Path directory) throws Exception {
        Files.createDirectory(directory.resolve("__ciop__"));
        Files.writeString(directory.resolve(ProtocolDocument.PATH), "must not shadow the resource");
        for (String path : List.of(ProtocolDocument.PATH, "./" + ProtocolDocument.PATH, "__ciop__/other.txt")) {
            for (FileAccess access : FileAccess.values()) {
                RequestContext context = new RequestContext(List.of(new ContextFile(path, access)));
                assertThrows(IllegalArgumentException.class,
                        () -> run(directory, context, List.of()));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> run(directory, RequestContext.empty(), List.of(path)));
        }
        assertEquals("must not shadow the resource", Files.readString(directory.resolve(ProtocolDocument.PATH)));
    }

    private void cli() {
        List<String> files = new ArrayList<>();
        var options = TheAICommand.parseOptions(new String[] {"--context", "first.json", "--context=second.json",
                "--backend=loopback", "read.txt", "--", "--context=filename"}, files);
        assertEquals("second.json", options.get("--context"));
        assertEquals("loopback", options.get("--backend"));
        assertEquals(List.of("read.txt", "--context=filename"), files);
        assertFalse(TheAICommand.parseOptions(new String[0], new ArrayList<>()).containsKey("--context"));
        assertTrue(TheAICommand.parseOptions(new String[] {"--help"}, new ArrayList<>()).containsKey("--help"));
        for (String[] args : List.of(new String[] {"--context"}, new String[] {"--context="}, new String[] {"--help=x"}))
            assertThrows(IllegalArgumentException.class, () -> TheAICommand.parseOptions(args, new ArrayList<>()));
    }

    private void lateAlias(Path directory) throws Exception {
        RequestContext context = new RequestContext(List.of(new ContextFile("read.txt", FileAccess.READ)));
        for (boolean patch : List.of(false, true)) {
            Files.deleteIfExists(directory.resolve("late.txt"));
            CapturingLLMProvider provider = new CapturingLLMProvider(prompt -> {
                try {
                    Files.createLink(directory.resolve("late.txt"), directory.resolve("read.txt"));
                } catch (java.io.IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                return batch(edit("late.txt", patch));
            });
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> run(directory, context, List.of("late.txt"), new byte[0], provider));
            assertTrue(failure.getMessage().contains("READ file"), failure.getMessage());
            assertContents(directory, "original\n", "read.txt", "late.txt");
        }
    }

    private static RequestContext load(Path directory, String filename, String json) throws Exception {
        Files.writeString(directory.resolve(filename), json);
        return RequestContext.load(directory, filename);
    }

    private static void assertContents(Path directory, String expected, String... paths) throws Exception {
        for (String path : paths) assertEquals(expected, Files.readString(directory.resolve(path)), path);
    }

    private static CapturingLLMProvider run(Path directory, RequestContext context,
            List<String> arguments, JsonObject... operations) throws Exception {
        return run(directory, context, arguments, new byte[0], new CapturingLLMProvider(batch(operations)));
    }

    private static CapturingLLMProvider run(Path directory, RequestContext context, List<String> arguments,
            byte[] stdin, CapturingLLMProvider provider) throws Exception {
        try (AICommandApplication application = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            application.run(stdin, arguments, context);
        }
        return provider;
    }

    private static String sectionHeader(String encoding, byte[] bytes, FileAccess access, String path) {
        return "---SECTION FILE " + encoding + " " + bytes.length + " " + AICommandApplication.sha256(bytes)
                + " access=" + access + " path=" + path + "---\n";
    }

    private static JsonObject edit(String path, boolean patch) {
        return patch ? replaceFirstLine(path, "original\n", "original", "changed")
                : fileWrite(path, "changed\n");
    }
}
