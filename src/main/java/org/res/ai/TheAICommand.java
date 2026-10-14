package org.res.ai;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Command-line entry point. */
public final class TheAICommand {
    private static final Map<String, String> DEFAULTS = Map.of(
            "--backend", "codex", "--model", "gpt-5.2", "--context", "context.json");
    private static final Set<String> VALUE_OPTIONS = Set.of("--backend", "--model", "--context");
    private static final Map<String, String> DESCRIPTIONS = descriptions();

    private TheAICommand() { }

    private static Map<String, String> descriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("--help", "Print this help and exit without reading stdin or contacting an LLM.");
        descriptions.put("--no-auto-retry", "Disable both network recovery waiting and provider-limit waiting.");
        descriptions.put("--enable-conversation-history", "Enable history recording, creating the conversation_history directory if missing.");
        descriptions.put("--disable-conversation-history", "Disable conversation history recording and registration.");
        descriptions.put("--ignore-context", "Ignore the default or explicitly selected context file, including history registration.");
        descriptions.put("--backend", "Choose codex, openai, or loopback.");
        descriptions.put("--model", "Choose the model used by the openai backend.");
        descriptions.put("--context", "Load ordered files and READ/READ_WRITE permissions from a JSON file.");
        descriptions.put("--enable-context", "Select a context file and create it if missing; defaults to context.json. New configurations have empty contexts and permissions.");
        return descriptions;
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        try {
            List<String> files = new ArrayList<>();
            Map<String, String> options = parseOptions(args, files);
            if (options.containsKey("--help")) {
                printHelp();
                return 0;
            }
            Path directory = Path.of(".");
            byte[] stdin = System.in.readAllBytes();
            String backend = options.get("--backend").toLowerCase(Locale.ROOT);
            LLMProvider provider = switch (backend) {
                case "openai" -> new OpenAILLMProvider(options.get("--model"));
                case "codex" -> new CodexExecLLMProvider();
                case "loopback" -> new LoopbackLLMProvider();
                default -> throw new IllegalArgumentException("Unknown backend: " + backend);
            };
            try (AICommandApplication application =
                         new AICommandApplication(provider, System.out, directory)) {
                application.setAutomaticRetry(!options.containsKey("--no-auto-retry"));
                ContextInitialization.prepare(directory, options);
                application.runWithHistory(stdin, files, options.get("--context"),
                        options.containsKey("--disable-conversation-history"),
                        options.containsKey("--ignore-context"),
                        options.containsKey("--enable-conversation-history"));
            }
            return 0;
        } catch (IOException | RuntimeException exception) {
            return FailureReporting.report(exception, System.out, System.err);
        }
    }

    private static void printHelp() {
        System.out.println("Usage: java -jar the-ai-command.jar [options] [--] [files...]");
        System.out.println("Read a prompt from stdin, include the supplied files, and apply the LLM response.");
        System.out.println("Exit codes: 0 success; 1 general failure; 3 provider limit; 4 network failure or uncertain submission.");
        System.out.println("Provider calls automatically retry after a known future reset plus a five-second buffer.");
        System.out.println("Ctrl+C exits while waiting. Coloured ANSI countdowns refresh every second, including with piped stdin. Use -Dai.retry.ansi=false for plain transition notices when redirecting output.");
        System.out.println("Only the exact failed provider prompt is resent; committed context operations are not repeated.");
        System.out.println("Unknown, incomplete, stale, or time-only reset information exits with code 3; billing quotas are not retried.");
        System.out.println("Codex reset lookup uses a three-second App Server query by default; -Dai.codex.rateLimitLookup=false disables it.");
        System.out.println("Use -Dai.codex.limitId=codex to select the metered bucket. OpenAI SDK retries are disabled; this application owns retry scheduling.");
        System.out.println("Network preflight requires curl on PATH. Credential-free HEAD checks have a 10-second total budget, including DNS.");
        System.out.println("Use -Dai.network.probeSeconds=10 and -Dai.network.retrySeconds=15 to configure checks and waits (1..3600).");
        System.out.println("Model generation has no new deadline; OpenAI connect timeout is 10 seconds and read/write/request timeouts are disabled.");
        System.out.println("Codex preflight reads effective App Server configuration/account kind. If unavailable, set -Dai.codex.networkEndpoint=https://provider/path.");
        System.out.println("Codex probes inherit HTTP(S)_PROXY/ALL_PROXY/NO_PROXY; OpenAI probes use the same Java proxy route pinned to its SDK.");
        System.out.println("Probe TLS trust uses curl configuration; configure it consistently with your provider. Certificate/configuration failures are not retried.");
        System.out.println("Transport recovery permits at most 3 fresh resubmissions per prompt; delays are 30, 60, 120 seconds plus 0..10% jitter.");
        System.out.println("Configure -Dai.network.maxResubmissions=3 (0..10), -Dai.network.backoffSeconds=30 (1..3600); delay caps at one hour before jitter.");
        System.out.println("Use -Dai.network.replayUncertain=false to prohibit uncertain fresh submissions; original-result retrieval remains allowed.");
        System.out.println("OpenAI: -Dai.openai.background=true enables background polling and retrieval by response ID; requires backend/model support and server retention.");
        System.out.println("Without a received response ID, OpenAI falls back to bounded resubmission. Codex session resume is not original-result retrieval.");
        System.out.println("Recovery lookups are attempted at most 3 times per interrupted submission; failed lookups do not prove the original was rejected.");
        System.out.println("A background job may continue on the server after Ctrl+C. Retries can consume duplicate quota; exactly-once execution is not guaranteed.");
        System.out.println("Network status shows outage detection time, elapsed wait, check retries, resubmissions and recovery lookups. Dates use local time as dd MMM HH:mm:ss; history omits countdown ticks.");
        System.out.println("Ctrl+C attempts a bounded outage summary; forced termination can leave an incomplete episode.");
        System.out.println("Custom Codex subprocess constructors do not infer a network endpoint or add preflight checks.");
        System.out.println("Retries retain the local history request ID, not a provider deduplication key; history omits countdown ticks. Use --no-auto-retry to disable waiting.");
        System.out.println();
        DESCRIPTIONS.forEach((name, description) -> {
            String defaultValue = DEFAULTS.get(name);
            System.out.println("  " + name + (name.equals("--enable-context") ? " [file]"
                    : VALUE_OPTIONS.contains(name) ? " <value>" : "")
                    + "  " + description
                    + (defaultValue == null ? "" : " Default: " + defaultValue + "."));
        });
        System.out.println("  --  End option parsing; all following arguments are file paths.");
        System.out.println();
        System.out.println("Value options accept --name value or --name=value; the last value wins.");
        System.out.println("--enable-context accepts an optional filename or =filename; context aliases share the last supplied value.");
        System.out.println("Bare --enable-context selects context.json. Use --enable-context -- files... to keep following files as inputs.");
        System.out.println("Context example: {\"contexts\":[\"README.md\"],\"permissions\":[{\"README.md\":\"READ\"},{\"regex:src/.*\":\"READ_WRITE\"}]}");
        System.out.println("Context entries may be filename strings for whole files or objects selecting line or byte ranges.");
        System.out.println("Line selection example: {\"path\":\"src/example.java\",\"lines\":[[10,20],[45,45]]}");
        System.out.println("Byte selection example: {\"path\":\"data.bin\",\"bytes\":[[0,16],[4096,1]]}");
        System.out.println("Lines use one-based inclusive [first,last]; bytes use zero-based [offset,count] with positive counts.");
        System.out.println("Ranges must be nonempty, ordered, non-overlapping, and within an existing file; adjacent ranges are allowed.");
        System.out.println("Use exactly one of lines or bytes. Line selections require the entire source to be valid UTF-8.");
        System.out.println("LF delimits lines; CRLF and selected terminators are preserved. Bare CR is content; trailing LF adds no extra line.");
        System.out.println("Command-line duplicates retain the configured subset. History registration also preserves existing excerpts.");
        System.out.println("Selections do not restrict READ_WRITE permission: whole-file replacement from an excerpt is permitted.");
        System.out.println("To request a whole file already selected as an excerpt: {\"op\":\"change_contexts\",\"add\":[],\"remove\":[],\"update\":[\"src/example.java\"]}");
        System.out.println("The update array also accepts subset objects and preserves entry order; add never replaces an existing selection.");
        System.out.println("CIOP/1.1 retains string-only configurations and ordinary output operations; CIOP/1.0 harnesses cannot handle subsets.");
        System.out.println("Permission rules match normalized relative paths in order; the first match wins and no match denies access.");
        System.out.println("Without --context, context.json in the working directory is used.");
        System.out.println("Context paths and command-line files are relative to the working directory.");
        System.out.println("The embedded read-only CIOP document comes first, then context files, extra files, and stdin.");
        System.out.println("Duplicate context files are errors. Argument duplicates retain context permissions.");
        System.out.println("With a context configuration, command-line files require permission and never grant access.");
        System.out.println("Without a loaded configuration, explicit command-line files retain READ_WRITE access.");
        System.out.println("Permissions can authorize output files absent from contexts. Missing READ_WRITE files may be created.");
        System.out.println("READ files must exist. Absolute paths, '..', symlinks, and __ciop__ paths are rejected.");
        System.out.println("Unrecognized arguments are treated as file paths; use -- for option-like filenames.");
        System.out.println("The openai backend requires OPENAI_API_KEY; codex requires the codex executable.");
        System.out.println("Loopback echoes the prompt unchanged and is primarily intended for testing.");
        System.out.println("Prompt/response logs are written to /tmp by default, except with loopback.");
        System.out.println("History is recorded in conversation_history/YYYY-MM-DD.txt only if the directory already exists.");
        System.out.println("Use --enable-conversation-history to create the history directory; --disable-conversation-history disables recording.");
        System.out.println("The enable and disable conversation-history flags cannot be combined.");
        System.out.println("History files are registered as READ in the selected context file before it is loaded.");
        System.out.println("Only --enable-context creates a missing configuration; --ignore-context suppresses creation. Add permission rules before including project files.");
        System.out.println("The history directory and its context configuration cannot be overwritten by response operations.");
    }

    static Map<String, String> parseOptions(String[] args, List<String> files) {
        Map<String, String> values = new LinkedHashMap<>(DEFAULTS);
        boolean options = true;
        for (int index = 0; args != null && index < args.length; index++) {
            String argument = args[index];
            if (options && "--".equals(argument)) {
                options = false;
                continue;
            }
            int equals = argument.indexOf('=');
            String name = equals < 0 ? argument : argument.substring(0, equals);
            if (!options || !DESCRIPTIONS.containsKey(name)) {
                files.add(argument);
            } else if (name.equals("--enable-context")) {
                String value = equals >= 0 ? argument.substring(equals + 1)
                        : index + 1 < args.length && !args[index + 1].startsWith("--")
                        ? args[++index] : DEFAULTS.get("--context");
                if (value.isBlank())
                    throw new IllegalArgumentException(name + " requires a nonblank filename when supplied");
                values.put("--context", value);
                values.put("--enable-context", "true");
            } else if (!VALUE_OPTIONS.contains(name)) {
                if (equals >= 0) throw new IllegalArgumentException(name + " does not accept a value");
                values.put(name, "true");
            } else {
                String value = equals >= 0 ? argument.substring(equals + 1)
                        : ++index < args.length ? args[index] : null;
                if (value == null || value.isBlank())
                    throw new IllegalArgumentException(name + " requires a value");
                values.put(name, value);
            }
        }
        if (values.containsKey("--enable-conversation-history")
                && values.containsKey("--disable-conversation-history"))
            throw new IllegalArgumentException("Cannot combine --enable-conversation-history and --disable-conversation-history");
        return values;
    }
}
