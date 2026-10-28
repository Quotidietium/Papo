#!/usr/bin/env python
# Papo allocation census aggregator (R24 methodology, reusable from R38 on).
# Input: one or more .jfr files recorded with jdk.ObjectAllocationSample#threshold=1ms.
# Output: total events, top sites by event COUNT (trips - the credibility metric;
# weights are interval estimates only: raw + >100MB-filtered), and in-stack counts
# for caller-supplied target substrings.
#
# Counting-rule precedent (R24): a sample's weight is EVERYTHING the thread allocated
# since its last threshold crossing, attributed to the crossing site - so single
# giant-weight events are not attributable; sites are judged by count (>=20 threshold),
# weights only bound the interval. Lambda frames carry runtime identity hashes
# (Class$$Lambda$123/0x...) which are normalized away for cross-leg comparison.
import re
import subprocess
import sys
from collections import Counter

JFR = "/f/Java/21/bin/jfr.exe" if sys.platform == "cygwin" else "F:/Java/21/bin/jfr.exe"

HEX_SUFFIX = re.compile(r"/0x[0-9a-fA-F]+")
WEIGHT_RE = re.compile(r"weight = ([0-9.]+) (B|kB|MB|GB)")
MULT = {"B": 1.0, "kB": 1024.0, "MB": 1024.0 * 1024.0, "GB": 1024.0 * 1024.0 * 1024.0}


def parse_weight(line):
    m = WEIGHT_RE.search(line)
    if not m:
        return 0.0
    return float(m.group(1)) * MULT[m.group(2)]


def normalize(frame):
    # strip lambda identity hash so the same site compares equal across legs
    return HEX_SUFFIX.sub("", frame.strip())


def aggregate(path):
    proc = subprocess.run([JFR, "print", "--events", "jdk.ObjectAllocationSample",
                           "--stack-depth", "8", path],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    events = 0
    site_count = Counter()          # (allocFrame, callerFrame) -> trips
    site_weight = Counter()         # same key -> raw weight bytes
    site_weight_filtered = Counter()  # same key -> weight with >100MB single events dropped
    instack = Counter()             # substring -> events whose stack contains it
    total_weight = 0.0
    cur_frames = []
    cur_weight = 0.0
    in_stack = False
    in_event = False
    for line in proc.stdout.splitlines():
        ls = line.strip()
        if ls.startswith("jdk.ObjectAllocationSample {"):
            in_event = True
            cur_frames = []
            cur_weight = 0.0
            events += 1
        elif in_event and ls.startswith("}"):
            in_event = False
            key = (cur_frames[0] if cur_frames else "<no-frames>",
                   cur_frames[1] if len(cur_frames) > 1 else "")
            site_count[key] += 1
            site_weight[key] += cur_weight
            if cur_weight <= 100.0 * 1024 * 1024:
                site_weight_filtered[key] += cur_weight
            total_weight += cur_weight
        elif in_event and ls.startswith("weight = "):
            cur_weight = parse_weight(ls)
        elif in_event and ls.startswith("stackTrace = ["):
            in_stack = True
        elif in_stack and ls.startswith("]"):
            in_stack = False
        elif in_stack and ls and not ls.startswith("..."):
            cur_frames.append(normalize(ls.split(" line:")[0].split("(")[0]))
    return events, total_weight, site_count, site_weight, site_weight_filtered


def main():
    targets = [a for a in sys.argv[1:] if not a.endswith(".jfr")]
    jfr_files = [a for a in sys.argv[1:] if a.endswith(".jfr")]
    if not jfr_files:
        print("usage: allocsum.py [--target-substr ...] file.jfr [file2.jfr ...]")
        print("       non-.jfr args are treated as in-stack target substrings")
        sys.exit(1)
    for path in jfr_files:
        events, total_weight, sc, sw, swf = aggregate(path)
        print(f"===== {path} =====")
        print(f"events={events} rawWeight={total_weight/1024/1024:.1f}MB")
        print("-- top 40 sites by count (trips) --")
        for (f0, f1), n in sc.most_common(40):
            w = sw[(f0, f1)] / 1024 / 1024
            wf = swf[(f0, f1)] / 1024 / 1024
            print(f"{n:6d}  {w:10.1f}MB (filt {wf:9.1f}MB)  {f0}  <-  {f1}")
        if targets:
            print("-- in-stack counts --")
            # need a second pass for substring counting across full stacks
            proc = subprocess.run([JFR, "print", "--events", "jdk.ObjectAllocationSample",
                                   "--stack-depth", "8", path],
                                  capture_output=True, text=True, encoding="utf-8", errors="replace")
            stack_buf = []
            in_stack = False
            counts = Counter()
            for line in proc.stdout.splitlines():
                ls = line.strip()
                if ls.startswith("stackTrace = ["):
                    in_stack = True
                    stack_buf = []
                elif in_stack and ls.startswith("]"):
                    in_stack = False
                    blob = "\n".join(stack_buf)
                    for t in targets:
                        if t in blob:
                            counts[t] += 1
                elif in_stack and ls and not ls.startswith("..."):
                    stack_buf.append(ls)
            for t in targets:
                print(f"  {counts.get(t, 0):6d}  contains '{t}'")


if __name__ == "__main__":
    main()
