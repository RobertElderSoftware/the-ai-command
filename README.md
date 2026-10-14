#  PULL REQUESTS
 
Do not make pull requests for this project.  I will ignore them.

# The AI Command

A Java command-line application that sends a prompt and selected files to an LLM, then applies its structured response. Responses can print output, create or update permitted files, delete permitted files, and request more context.

The default backend is Codex. An OpenAI API backend is also available.

## Build

Requirements:

- JDK 17 or later and Maven.
- For the Codex backend: an installed, authenticated `codex` executable on `PATH`.
- For the OpenAI backend: an `OPENAI_API_KEY` environment variable.
- For network preflight checks: `curl` on `PATH`.

From the project directory:

```sh
mvn clean verify
```

This runs the tests and builds `target/the-ai-command.jar`, including its dependencies. To run tests without packaging:

```sh
mvn test
```

The build recreates the application JAR before shading, so subsequent `mvn verify` runs do not reuse an already-shaded JAR as input.

## Quick start

Run the command from the directory containing the files you want it to work on. File paths and context paths are relative to that working directory.

```sh
printf '%s\n' 'Explain what this project does.' |
  java -jar /absolute/path/to/the-ai-command.jar README.md pom.xml
```

Without a loaded context configuration, explicitly supplied files have `READ_WRITE` access. Use a context configuration to provide read-only inputs or control which other files may be changed.

The application applies valid file operations automatically within its configured permissions. It does not present a separate approval prompt.

### Install an `ai` wrapper

Create a script named `ai` in a directory on your `PATH`, such as `~/bin`, and point it at your built JAR:

```sh
#!/bin/sh
exec java -jar /absolute/path/to/the-ai-command/target/the-ai-command.jar "$@"
```

Make the script executable:

```sh
chmod +x ~/bin/ai
```

If needed, add this to your shell profile and reload it:

```sh
export PATH="$HOME/bin:$PATH"
```

You can then run:

```sh
printf '%s\n' 'Explain this class.' | ai src/main/java/org/res/ai/TheAICommand.java
```

### Choose a backend

```sh
printf '%s\n' 'Explain this project.' | ai --backend codex README.md

printf '%s\n' 'Explain this project.' |
  ai --backend openai --model gpt-5.2 README.md
```

`--model` controls the OpenAI backend and defaults to `gpt-5.2`. The Codex backend uses its own configuration. Set `OPENAI_API_KEY` through your environment or secret-management setup; `OPENAI_BASE_URL` optionally selects a different API endpoint.

The `loopback` backend echoes the generated protocol prompt and is primarily for testing. Its output is not an ordinary model response containing operations.

## Command-line options

```text
ai [options] [--] [files...]
```

| Option | Behavior |
| --- | --- |
| `--help` | Show help without reading stdin or contacting a provider. |
| `--backend <name>` | Select `codex` (default), `openai`, or `loopback`. |
| `--model <name>` | Select the OpenAI model; default `gpt-5.2`. |
| `--context <file>` | Load a context configuration; default `context.json`. |
| `--enable-context [file]` | Select a context configuration and create it if missing; defaults to `context.json`. |
| `--ignore-context` | Ignore the context configuration, including its creation and history registration. |
| `--enable-conversation-history` | Enable history and create its directory if missing. |
| `--disable-conversation-history` | Disable history recording and registration. |
| `--no-auto-retry` | Disable automatic network recovery and provider-limit waits. |
| `--` | Treat all remaining arguments as file paths. |

Value options accept either `--name value` or `--name=value`; the last supplied value wins. The history enable and disable flags cannot be combined.

`--enable-context` consumes a following filename unless it starts with `--`. To create the default configuration while passing files separately, use:

```sh
ai --enable-context -- README.md
```

A newly created configuration has empty contexts and permissions, so add permission rules before including project files. Unrecognized arguments are treated as file paths.

## Context and permissions

When `context.json` exists in the working directory, the application loads it by default. A different configuration can be selected with `--context`. Only `--enable-context` creates a missing configuration.

Example:

```json
{
  "contexts": ["README.md", "src/main/java/org/res/ai/TheAICommand.java"],
  "permissions": [
    {"README.md": "READ"},
    {"regex:src/.*\\.java": "READ_WRITE"}
  ]
}
```

`contexts` selects the files sent to the model, in order. `permissions` controls access independently of that selection:

- `READ` permits a file to be included but prevents response operations from modifying or deleting it.
- `READ_WRITE` permits inclusion, creation, replacement, patching, and deletion.
- The first matching permission rule wins. No matching rule means access is denied.
- Literal rules select a path; `regex:` rules use Java regular expressions matching the entire normalized relative path.
- A permitted output file does not have to be included in `contexts`.

With a loaded configuration, additional command-line files must match its permissions. Supplying a file on the command line never upgrades its access. Without a loaded configuration, explicitly supplied files receive `READ_WRITE` access.

All paths are relative to the application's working directory, including paths inside a configuration stored elsewhere. Absolute paths, parent traversal, symlinks, and the reserved `__ciop__` namespace are rejected. Read-only files must exist; missing writable files may be selected as creation targets.

Duplicate context identities are rejected. Duplicate command-line inputs are included only once and retain the configured access and selection.

### Include only part of a file

CIOP/1.1 supports line or byte selections in the `contexts` array:

```json
{
  "contexts": [
    {"path": "README.md", "lines": [[1, 12], [20, 25]]},
    {"path": "data.bin", "bytes": [[0, 16], [4096, 1]]}
  ],
  "permissions": [
    {"README.md": "READ"},
    {"data.bin": "READ"}
  ]
}
```

Choose ranges that exist in your files:

- Lines use one-based, inclusive `[first, last]` pairs.
- Bytes use zero-based `[offset, count]` pairs with positive counts.
- Ranges must be nonempty, ordered, non-overlapping, and entirely within an existing file. Adjacent ranges are allowed.
- Each selection uses exactly one of `lines` or `bytes`.
- Line selection requires the entire source to be valid UTF-8. Original selected line terminators are preserved.

A filename string selects the whole file. A subset reduces transmitted context; it does not narrow `READ_WRITE` permission, which still applies to the entire file.

The model can request file discovery or change the configured context selection. These requests preserve permissions and share a maximum of five follow-ups per invocation. Context changes are saved before the follow-up request, so they remain saved if that later request fails.

## Network recovery and provider limits

Automatic retries are enabled by default. Only the retained provider prompt is retried; already-committed context changes and file operations are not replayed.

Before submission, credential-free network checks test reachability. Each check has a default ten-second limit, including DNS. While offline, the application waits fifteen seconds between checks without submitting prompts or spending the resubmission budget. This wait can continue until connectivity returns or you press Ctrl+C.

If a submitted request loses its connection, the application prefers retrieval of the original response when the backend supports it. Otherwise, it permits at most three fresh resubmissions per prompt, with delays of 30, 60, and 120 seconds plus up to 10% nonnegative jitter. Connectivity changes and usage-limit waits do not reset that budget. Silence during generation does not trigger resubmission.

An interrupted request may already have consumed quota. Fresh submissions can consume it again; retries do not guarantee exactly-once provider execution.

### Backend recovery

OpenAI background polling and retrieval can be enabled explicitly:

```sh
printf '%s\n' 'Explain this project.' |
  java -Dai.openai.background=true -jar /absolute/path/to/the-ai-command.jar \
  --backend openai README.md
```

This requires compatible endpoint/model support and server-side response retention. The application keeps a received response ID in memory and can retrieve that response after a transport failure. It cannot recover by ID if the initial create response was lost before the ID arrived, and IDs do not survive application restarts. A background job may continue on the server after Ctrl+C.

The Codex backend uses ephemeral `exec` calls and falls back to bounded resubmission. Session resume is not used as retrieval of an interrupted generation. Codex may also retry internally within an individual process invocation.

### Usage limits

When the provider supplies complete, future reset information, the application waits until the reset plus a five-second buffer, then resends the same prompt. Unknown, incomplete, stale, or time-only reset information causes it to exit. Billing-related quota exhaustion is not automatically retried.

Codex reset lookup uses a bounded App Server query. OpenAI SDK retries are disabled so this application controls its retry scheduling.

### Retry settings

These are Java system properties, so put them **before `-jar`**, or include them in the wrapper's `java` command.

| Property | Default | Purpose |
| --- | --- | --- |
| `ai.retry.ansi` | `true` | Show coloured countdowns refreshed every second, including with piped stdin. Set `false` for plain transition notices. |
| `ai.network.probeSeconds` | `10` | Network-check time limit; range 1–3600 seconds. |
| `ai.network.retrySeconds` | `15` | Wait between failed connectivity checks; range 1–3600 seconds. |
| `ai.network.maxResubmissions` | `3` | Fresh submissions allowed after transport failures per prompt; range 0–10. |
| `ai.network.backoffSeconds` | `30` | Initial exponential delay; range 1–3600 seconds. Delay caps at one hour before jitter. |
| `ai.network.replayUncertain` | `true` | Set `false` to prohibit fresh submissions with uncertain acceptance; original-response retrieval remains allowed. |
| `ai.openai.background` | `false` | Enable OpenAI background polling and retrieval. |
| `ai.codex.rateLimitLookup` | `true` | Enable Codex App Server reset-time lookup. |
| `ai.codex.limitId` | `codex` | Select the metered bucket for Codex reset lookup. |
| `ai.codex.networkEndpoint` | Automatically resolved | Override the Codex preflight endpoint if configuration/account discovery is unavailable. |

For example, to use plain status output and prohibit uncertain replays:

```sh
java -Dai.retry.ansi=false -Dai.network.replayUncertain=false \
  -jar /absolute/path/to/the-ai-command.jar README.md < prompt.txt
```

Retry status is printed to stdout alongside response output; disabling ANSI does not remove status notices. Press Ctrl+C to stop waiting. Use `--no-auto-retry` to exit on a retryable failure instead of waiting or retrieving.

Codex probes inherit curl's proxy environment settings. OpenAI probes use the Java proxy route selected for its SDK. Curl and the provider must have compatible TLS trust configuration. Certificate, authentication, configuration, parsing, and application-commit failures are not automatic replay triggers.

## Conversation history and logs

If `conversation_history` already exists, history is recorded in `conversation_history/YYYY-MM-DD.txt`. Use `--enable-conversation-history` to create the directory, or `--disable-conversation-history` to turn recording off.

History files are registered as read-only context in the selected configuration. Existing excerpt selections are preserved. The history directory and active context configuration are protected from ordinary response file operations.

Network history records compact outage start and finish events, including checks, resubmissions, recovery attempts, and possible duplicate quota use. It omits countdown ticks. Ctrl+C attempts a bounded outage summary; forced termination may leave an incomplete episode.

Prompt and response file logging is enabled by default except for loopback, with logs written under `/tmp`. These logs and conversation history can contain prompts, source files, and model output. Disabling conversation history does not disable prompt/response logging.

## Exit codes and operation handling

| Code | Meaning |
| --- | --- |
| `0` | Success. |
| `1` | General failure. |
| `3` | Provider limit that was not automatically resolved. |
| `4` | Network failure or uncertain submission that was not automatically resolved. |

The response batch is validated before destination changes. File commits use replacement and best-effort rollback on ordinary failures; multi-file updates are not crash-safe transactions. Model stdout operations are published after successful file commits.

If output fails after files were committed, do not automatically rerun the request. Likewise, saved context changes remain saved when a later follow-up fails.

See [specification.txt](specification.txt) for the full CIOP/1.1 envelope, operation formats, partial-context rules, and recovery contract. Run `ai --help` for the command's built-in reference.

## Pull requests

Do not make pull requests for this project. They will be ignored.
