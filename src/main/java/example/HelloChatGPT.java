package example;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class HelloChatGPT {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private HelloChatGPT() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);

        /*
         * At this point, our executors have terminated successfully.
         * System.exit() is only needed to return a non-zero status.
         */
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int run(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("OPENAI_API_KEY is not set.");
            System.err.println(
                "Run: export OPENAI_API_KEY=\"your-api-key\""
            );
            return 1;
        }

        final byte[] stdinData;

        try {
            /*
             * Reads stdin as raw bytes until EOF. No character decoding
             * happens here, so NUL bytes and invalid UTF-8 are preserved.
             */
            stdinData = System.in.readAllBytes();
        } catch (IOException exception) {
            System.err.println("Could not read standard input:");
            exception.printStackTrace(System.err);
            return 1;
        }

        if (stdinData.length == 0) {
            System.err.println("No data was received on standard input.");
            return 1;
        }

        String prompt;
        try {
            prompt = buildPromptFromStdinAndFiles(stdinData, args);
        } catch (IOException exception) {
            System.err.println("Could not read one or more input files:");
            exception.printStackTrace(System.err);
            return 1;
        }

        ExecutorService httpExecutor =
            Executors.newCachedThreadPool(
                namedThreadFactory("openai-http-")
            );

        ExecutorService streamExecutor =
            Executors.newCachedThreadPool(
                namedThreadFactory("openai-stream-")
            );

        OpenAIClient client = null;
        int exitCode = 0;

        try {
            client = OpenAIOkHttpClient.builder()
                .fromEnv()
                .dispatcherExecutorService(httpExecutor)
                .streamHandlerExecutor(streamExecutor)
                .build();

            //System.out.println(prompt);
            ResponseCreateParams request = ResponseCreateParams.builder()
                .model("gpt-5.2")
                .input(prompt)
                .build();

            Response response = client.responses().create(request);

            String output = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(outputText -> outputText.text())
                .collect(Collectors.joining());

            System.out.println(output);

        } catch (RuntimeException exception) {
            System.err.println("The OpenAI request failed:");
            exception.printStackTrace(System.err);
            exitCode = 1;

        } finally {
            /*
             * The OpenAI client owns the executors supplied to its builder
             * and requests their shutdown when close() is called.
             */
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException exception) {
                    System.err.println(
                        "The OpenAI client did not close cleanly:"
                    );
                    exception.printStackTrace(System.err);
                    exitCode = 1;
                }
            }

            /*
             * shutdown() is safe to call even if client.close() has already
             * called it. Retaining the executor references lets us wait for
             * their complete termination.
             */
            boolean httpStopped = shutdownAndAwait(
                httpExecutor,
                "OpenAI HTTP executor"
            );

            boolean streamStopped = shutdownAndAwait(
                streamExecutor,
                "OpenAI stream executor"
            );

            if (!httpStopped || !streamStopped) {
                exitCode = 1;
            }
        }

        return exitCode;
    }

    private static String buildPromptFromStdinAndFiles(
        byte[] stdinData,
        String[] args
    ) throws IOException {
        StringBuilder prompt = new StringBuilder();

        // Always include stdin first (original behavior).
        prompt.append("=== STDIN ===\n");
        prompt.append(convertBytesToPrompt(stdinData));
        prompt.append('\n');

        // Zero or more file arguments.
        //System.out.println("args.length= " + args.length);
        if (args != null && args.length > 0) {
            for (String arg : args) {
                if (arg == null || arg.isBlank()) {
                    continue;
                }

                //System.out.println("Processing file " + arg);
                Path path = Path.of(arg);
                byte[] fileData = Files.readAllBytes(path);
                //System.out.println("contents " + new String(fileData, "UTF-8"));

                prompt.append("\n=== FILE: ")
                    .append(path)
                    .append(" ===\n");

                prompt.append(convertBytesToPrompt(fileData));
                prompt.append('\n');
            }
        }

        return prompt.toString();
    }

    /**
     * Sends valid UTF-8 directly. Invalid UTF-8 is represented as Base64 so
     * that no byte values are lost or replaced.
     */
    private static String convertBytesToPrompt(byte[] data) {
        String utf8Text = decodeStrictUtf8(data);

        if (utf8Text != null) {
            return utf8Text;
        }

        String base64 = Base64.getEncoder().encodeToString(data);

        return """
            The standard-input payload is arbitrary binary data.

            It has been encoded with standard Base64 so that every input byte
            is preserved exactly. Decode the Base64 payload before interpreting
            the data. Do not treat the Base64 characters themselves as the
            original content.

            Original byte length: %d

            Base64 payload:
            %s
            """.formatted(data.length, base64);
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

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger threadNumber = new AtomicInteger(1);

        return task -> {
            Thread thread = new Thread(
                task,
                prefix + threadNumber.getAndIncrement()
            );

            /*
             * Non-daemon threads ensure that a shutdown defect is visible
             * rather than being hidden by JVM termination.
             */
            thread.setDaemon(false);
            return thread;
        };
    }

    private static boolean shutdownAndAwait(
        ExecutorService executor,
        String description
    ) {
        executor.shutdown();

        try {
            if (executor.awaitTermination(
                SHUTDOWN_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )) {
                return true;
            }

            /*
             * Do not use shutdownNow() here: that would interrupt running
             * work rather than completing a fully graceful shutdown.
             */
            System.err.printf(
                "%s did not terminate gracefully within %d seconds.%n",
                description,
                SHUTDOWN_TIMEOUT_SECONDS
            );

            return false;

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            System.err.println(
                "Interrupted while waiting for " +
                description +
                " to terminate."
            );

            return false;
        }
    }
}
