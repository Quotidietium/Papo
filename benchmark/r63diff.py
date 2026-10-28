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
    import sys
    if len(sys.argv) != 3:
        print("usage: r63diff.py A.jfr B.jfr  (B-A site differential by count)")
        sys.exit(1)
    ea, _, sca, swa, _ = aggregate(sys.argv[1])
    eb, _, scb, swb, _ = aggregate(sys.argv[2])
    print(f"events A={ea} B={eb} delta={eb-ea} ({(eb-ea)/ea*100:+.1f}%)")
    keys = set(sca) | set(scb)
    diff = sorted(keys, key=lambda k: (scb.get(k,0)-sca.get(k,0)), reverse=True)
    print("-- B-A top 60 by delta count (A/B/delta) --")
    shown = 0
    for k in diff:
        d = scb.get(k,0) - sca.get(k,0)
        if d < 5 and shown >= 30:
            break
        if d <= 0:
            break
        wa = swa.get(k,0)/1024/1024; wb = swb.get(k,0)/1024/1024
        print(f"A={sca.get(k,0):5d} B={scb.get(k,0):5d} d={d:+5d}  wA={wa:8.1f} wB={wb:8.1f}MB  {k[0]}  <-  {k[1]}")
        shown += 1
        if shown >= 60:
            break
    print("-- A-only top 15 (B=0) --")
    n=0
    for k in sorted(keys, key=lambda k: sca.get(k,0), reverse=True):
        if scb.get(k,0)==0 and n<15:
            print(f"A={sca.get(k,0):5d}  {k[0]}  <-  {k[1]}"); n+=1


if __name__ == "__main__":
    main()
