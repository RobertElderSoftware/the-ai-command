#  PULL REQUESTS

Do not make pull requests for this project.  I will ignore them.

#  BUILD

```
mvn clean package
```

#  Tests

To run test, run 

```
mvn test
```

#  Run

You can run the program like this:

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
