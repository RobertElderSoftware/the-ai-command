#  PULL REQUESTS

Do not make pull requests for this project.  I will ignore them.

#  USAGE

```
Usage: java -jar the-ai-command.jar [options] [--] [files...]
Read a prompt from stdin, include the supplied files, and apply the LLM response.

  --help  Print this help and exit without reading stdin or contacting an LLM.
  --enable-conversation-history  Enable history recording, creating the conversation_history directory if missing.
  --disable-conversation-history  Disable conversation history recording and registration.
  --ignore-context  Ignore the default or explicitly selected context file, including history registration.
  --backend <value>  Choose codex, openai, or loopback. Default: codex.
  --model <value>  Choose the model used by the openai backend. Default: gpt-5.2.
  --context <value>  Load ordered files and READ/READ_WRITE permissions from a JSON file. Default: context.json.
  --  End option parsing; all following arguments are file paths.

Value options accept --name value or --name=value; the last value wins.
Context example: {"files":[{"README.md":"READ"},{"src/example.txt":"READ_WRITE"}]}
Without --context, context.json in the working directory is used.
Context paths and command-line files are relative to the working directory.
The embedded read-only CIOP document comes first, then context files, extra files, and stdin.
Duplicate context files are errors. Argument duplicates retain context permissions.
Additional command-line files allow writes. Missing READ_WRITE files may be created.
READ files must exist. Absolute paths, '..', symlinks, and __ciop__ paths are rejected.
Unrecognized arguments are treated as file paths; use -- for option-like filenames.
The openai backend requires OPENAI_API_KEY; codex requires the codex executable.
Loopback echoes the prompt unchanged and is primarily intended for testing.
Prompt/response logs are written to /tmp by default, except with loopback.
History is recorded in conversation_history/YYYY-MM-DD.txt only if the directory already exists.
Use --enable-conversation-history to create the history directory; --disable-conversation-history disables recording.
The enable and disable conversation-history flags cannot be combined.
History files are registered as READ in the selected context file before it is loaded.
A missing context file is not created; the request uses an empty context.
The history directory and its context configuration cannot be overwritten by response operations.
```

#  BUILD

```
mvn clean package
```

#  Run

You can run the program like this:

```
ai "This is my prompt." | java -jar /home/robert/2026-09-04-ai/target/the-ai-command.jar file1.txt File2.java
```

#  Example 'ai' script, located at '~/roberts-ai/ai'

Instead of typing out 'java -jar ...' all the time, you can configure your terminal to just treat 'ai' as the same thing.

Point the 'ai' script to the latest version of the ai command that you've built:

```
#!/bin/bash

java -jar /home/robert/2026-09-04-ai/target/the-ai-command.jar "$@"
```

# PATH Variable

Add this at the end of '~/.profile' so that 'ai' will be correctly found in the path variable:

```
PATH="$HOME/roberts-ai:$PATH"
```

Also, if you want to use the API access to OpenAI, you can set your API key in ~/.profile as well:

```
export OPENAI_API_KEY="XXXXXXXXXXXXXXXXXXX"
```

Now, you can just do 

```
echo "This is my prompt." | ai file1.txt File2.java
```

# Options

```
echo "This is my prompt." | ai --backend openai --model "gpt-5.2" #  Route request to the OpenAI API, model gpt-5.2
echo "This is my prompt." | ai --backend codex   #  Route request to the Codex exec backend
```
