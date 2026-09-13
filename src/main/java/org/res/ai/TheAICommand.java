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
            "--backend", "codex", "--model", "gpt-5.2");
    private static final Set<String> VALUE_OPTIONS = Set.of("--backend", "--model", "--context");
    private static final Map<String, String> DESCRIPTIONS = descriptions();

    private TheAICommand() { }

    private static Map<String, String> descriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("--help", "Print this help and exit without reading stdin or contacting an LLM.");
        descriptions.put("--backend", "Choose codex, openai, or loopback.");
        descriptions.put("--model", "Choose the model used by the openai backend.");
        descriptions.put("--context", "Load ordered files and READ/READ_WRITE permissions from a JSON file.");
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
            RequestContext context = options.containsKey("--context")
                    ? RequestContext.load(directory, options.get("--context")) : RequestContext.empty();
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
                application.run(stdin, files, context);
            }
            return 0;
        } catch (IOException | RuntimeException exception) {
            System.err.println("The AI command failed:");
            exception.printStackTrace(System.err);
            return 1;
        }
    }

    private static void printHelp() {
        System.out.println("Usage: java -jar the-ai-command.jar [options] [--] [files...]");
        System.out.println("Read a prompt from stdin, include the supplied files, and apply the LLM response.");
        System.out.println();
        DESCRIPTIONS.forEach((name, description) -> {
            String defaultValue = DEFAULTS.get(name);
            System.out.println("  " + name + (VALUE_OPTIONS.contains(name) ? " <value>" : "")
                    + "  " + description
                    + (defaultValue == null ? "" : " Default: " + defaultValue + "."));
        });
        System.out.println("  --  End option parsing; all following arguments are file paths.");
        System.out.println();
        System.out.println("Value options accept --name value or --name=value; the last value wins.");
        System.out.println("Context example: {\"files\":[{\"README.md\":\"READ\"},{\"src/example.txt\":\"READ_WRITE\"}]}");
        System.out.println("No context file is loaded unless --context is supplied.");
        System.out.println("Context paths and command-line files are relative to the working directory.");
        System.out.println("The embedded read-only CIOP document comes first, then context files, extra files, and stdin.");
        System.out.println("Duplicate context files are errors. Argument duplicates retain context permissions.");
        System.out.println("Additional command-line files allow writes. Missing READ_WRITE files may be created.");
        System.out.println("READ files must exist. Absolute paths, '..', symlinks, and __ciop__ paths are rejected.");
        System.out.println("Unrecognized arguments are treated as file paths; use -- for option-like filenames.");
        System.out.println("The openai backend requires OPENAI_API_KEY; codex requires the codex executable.");
        System.out.println("Loopback echoes the prompt unchanged and is primarily intended for testing.");
        System.out.println("Prompt/response logs are written to /tmp by default, except with loopback.");
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
        return values;
    }
}
