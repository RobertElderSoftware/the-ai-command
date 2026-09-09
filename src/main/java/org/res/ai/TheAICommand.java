package org.res.ai;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Command-line entry point. */
public final class TheAICommand {
    private static final String DEFAULT_BACKEND = "codex";
    private static final String DEFAULT_MODEL = "gpt-5.2";

    private TheAICommand() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        try {
            CliOptions options = parseOptions(args);
            byte[] stdin = System.in.readAllBytes();
            LLMProvider provider = switch (options.backend) {
                case "openai" -> new OpenAILLMProvider(options.model);
                case "codex" -> new CodexExecLLMProvider();
                case "loopback" -> new LoopbackLLMProvider();
                default -> throw new IllegalArgumentException("Unknown backend: " + options.backend);
            };
            try (AICommandApplication application =
                         new AICommandApplication(provider, System.out, Path.of("."))) {
                application.run(stdin, options.files);
            }
            return 0;
        } catch (IOException | RuntimeException exception) {
            System.err.println("The AI command failed:");
            exception.printStackTrace(System.err);
            return 1;
        }
    }

    private static CliOptions parseOptions(String[] args) {
        String backend = DEFAULT_BACKEND;
        String model = DEFAULT_MODEL;
        List<String> files = new ArrayList<>();
        boolean options = true;
        for (int index = 0; args != null && index < args.length; index++) {
            String argument = args[index];
            if (options && "--".equals(argument)) {
                options = false;
            } else if (options && "--backend".equals(argument)) {
                backend = requireValue(args, ++index, "--backend");
            } else if (options && argument.startsWith("--backend=")) {
                backend = argument.substring("--backend=".length());
            } else if (options && "--model".equals(argument)) {
                model = requireValue(args, ++index, "--model");
            } else if (options && argument.startsWith("--model=")) {
                model = argument.substring("--model=".length());
            } else {
                files.add(argument);
            }
        }
        if (backend.isBlank() || model.isBlank()) throw new IllegalArgumentException("Options must not be blank");
        return new CliOptions(backend.toLowerCase(Locale.ROOT), model, List.copyOf(files));
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index] == null || args[index].isBlank()) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private record CliOptions(String backend, String model, List<String> files) {
    }
}
