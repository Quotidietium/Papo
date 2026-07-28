import difflib, sys

def make_patch(vanilla_path, modified_path, rel, out_path):
    a = open(vanilla_path, encoding='utf-8').read().split('\n')
    b = open(modified_path, encoding='utf-8').read().split('\n')
    if a and a[-1] == '': a.pop()
    if b and b[-1] == '': b.pop()
    diff = list(difflib.unified_diff(a, b, lineterm='', n=3))
    out = ['--- a/' + rel, '+++ b/' + rel]
    for line in diff:
        if line.startswith('---') or line.startswith('+++'):
            continue
        if line.startswith('@@'):
            # rewrite +start to _
            import re
            line = re.sub(r'@@ -(\d+),(\d+) \+\d+,(\d+) @@', r'@@ -\1,\2 +_,\3 @@', line)
        out.append(line)
    open(out_path, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
    print('wrote', out_path)

make_patch(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
