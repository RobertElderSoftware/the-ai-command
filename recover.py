import json 

#  Recover any files that come back in a response from the LLM
#  but are not written (due to an unexpected exception or something)
with open('ai-20260829-213551.407-10301-2-response.txt') as f:
    ops = json.load(f)
    for o in ops:
        print(o['path'])
        with open(o['path'], 'w') as outfile:
            outfile.write(o['data'])
