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
  --enable-context [file]  Alias for --context; defaults to context.json when no filename is supplied.
  --  End option parsing; all following arguments are file paths.

Value options accept --name value or --name=value; the last value wins.
--enable-context accepts an optional filename or =filename; context aliases share the last supplied value.
Bare --enable-context selects context.json. Use --enable-context -- files... to keep following files as inputs.
Context example: {"contexts":["README.md"],"permissions":[{"README.md":"READ"},{"regex:src/.*":"READ_WRITE"}]}
Context entries may be filename strings for whole files or objects selecting line or byte ranges.
Line selection example: {"path":"src/example.java","lines":[[10,20],[45,45]]}
Byte selection example: {"path":"data.bin","bytes":[[0,16],[4096,1]]}
Lines use one-based inclusive [first,last]; bytes use zero-based [offset,count] with positive counts.
Ranges must be nonempty, ordered, non-overlapping, and within an existing file; adjacent ranges are allowed.
Use exactly one of lines or bytes. Line selections require the entire source to be valid UTF-8.
LF delimits lines; CRLF and selected terminators are preserved. Bare CR is content; trailing LF adds no extra line.
Command-line duplicates retain the configured subset. History registration also preserves existing excerpts.
Selections do not restrict READ_WRITE permission: whole-file replacement from an excerpt is permitted.
To request a whole file already selected as an excerpt: {"op":"change_contexts","add":[],"remove":[],"update":["src/example.java"]}
The update array also accepts subset objects and preserves entry order; add never replaces an existing selection.
CIOP/1.1 retains string-only configurations and ordinary output operations; CIOP/1.0 harnesses cannot handle subsets.
Permission rules match normalized relative paths in order; the first match wins and no match denies access.
Without --context, context.json in the working directory is used.
Context paths and command-line files are relative to the working directory.
The embedded read-only CIOP document comes first, then context files, extra files, and stdin.
Duplicate context files are errors. Argument duplicates retain context permissions.
With a context configuration, command-line files require permission and never grant access.
Without a loaded configuration, explicit command-line files retain READ_WRITE access.
Permissions can authorize output files absent from contexts. Missing READ_WRITE files may be created.
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

Once you have the 'ai' command set up in your path, you can run it like this:

```
echo "This is my prompt." | ai file1.txt File2.java
```

If you haven't yet set it up to run as a simple script called 'ai' (see below), you'll have to run it like this:

```
echo "This is my prompt." | java -jar /home/robert/2026-09-04-ai/target/the-ai-command.jar file1.txt File2.java
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
