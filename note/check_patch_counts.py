import re, sys, glob

files = sorted(glob.glob('paper-server/patches/sources/**/*.patch', recursive=True))
if len(sys.argv) > 1:
    files = sys.argv[1:]
hdr = re.compile(r'^@@ -(\d+)(?:,(\d+))? \+(?:\d+|_)(?:,(\d+))? @@')
bad = False
for f in files:
    lines = open(f, encoding='utf-8').read().split('\n')
    if lines and lines[-1] == '':
        lines.pop()  # trailing newline artifact
    i = 0
    while i < len(lines):
        m = hdr.match(lines[i])
        if m:
            minus = int(m.group(2) or 1)
            plus = int(m.group(3) or 1)
            j = i + 1
            c_minus = 0
            c_plus = 0
            while j < len(lines) and not lines[j].startswith('@@') and not lines[j].startswith('diff ') and not lines[j].startswith('--- '):
                l = lines[j]
                if l.startswith('-'):
                    c_minus += 1
                elif l.startswith('+'):
                    c_plus += 1
                elif l.startswith('\\'):
                    pass
                else:
                    c_minus += 1
                    c_plus += 1
                j += 1
            if minus != c_minus or plus != c_plus:
                bad = True
                print(f'{f} line {i+1}: header -{minus}/+{plus} actual -{c_minus}/+{c_plus} MISMATCH')
            i = j
        else:
            i += 1
print('FAILURES FOUND' if bad else 'ALL OK')
