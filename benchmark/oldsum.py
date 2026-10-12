#!/usr/bin/env python
# Papo old-generation residency aggregator (R50 methodology).
# Input: one .jfr recorded with jdk.OldObjectSample (settings=profile; allocation
# stacks present when ObjectAllocationSample stack tracing was active at alloc time).
# Each event = one SAMPLED long-lived (old) object: allocationTime + objectAge +
# class + allocation stack. Sampled bytes are estimates of old-gen residency
# attributable to allocation site — the first tier measuring SURVIVORSHIP (R24-R49
# measured allocation sites only; R5-R22 residency was class-histogram only).
#
# Usage: python oldsum.py file.jfr [--cutoff HH:MM:SS] [--depth 4] [substr ...]
#   --cutoff  drop events allocated before this wall-clock time (boot-constant
#             exclusion: R22 boot tables allocate at boot, pre-dating the session)
#   --depth   stack frames per site key (default 4)
#   substr    optional in-stack substring counts (judgment aids)
import re
import subprocess
import sys
from collections import Counter

JFR = "/f/Java/21/bin/jfr.exe" if sys.platform == "cygwin" else "F:/Java/21/bin/jfr.exe"

HEX_SUFFIX = re.compile(r"/0x[0-9a-fA-F]+")
TIME_RE = re.compile(r"allocationTime = (\d\d:\d\d:\d\d)")
SIZE_RE = re.compile(r"objectSize = (\d+) bytes")
AGE_RE = re.compile(r"objectAge = (\d+ \w+|\d+ m \d+ s)")


def norm(frame):
    return HEX_SUFFIX.sub("", frame.strip())


def main():
    args = sys.argv[1:]
    cutoff = None
    depth = 4
    targets = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--cutoff":
            i += 1
            cutoff = args[i]
        elif a == "--depth":
            i += 1
            depth = int(args[i])
        elif a.endswith(".jfr"):
            path = a
        else:
            targets.append(a)
        i += 1

    proc = subprocess.run([JFR, "print", "--events", "jdk.OldObjectSample",
                           "--stack-depth", "16", path],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")

    sites = Counter()          # (class, frames-tuple) -> sampled bytes
    site_count = Counter()
    class_bytes = Counter()
    total_events = 0
    kept = 0
    dropped_cutoff = 0
    no_stack = 0
    in_stack = Counter()

    cls = None
    size = 0
    alloc_t = None
    frames = []
    in_obj = False
    in_stack_block = False

    def flush():
        nonlocal kept, dropped_cutoff, no_stack, total_events
        nonlocal cls, size, alloc_t, frames
        if cls is None:
            return
        total_events += 1
        if cutoff and alloc_t and alloc_t < cutoff:
            dropped_cutoff += 1
        elif not frames:
            no_stack += 1
        else:
            kept += 1
            key = (cls, tuple(frames[:depth]))
            sites[key] += size
            site_count[key] += 1
            class_bytes[cls] += size
            blob = "\n".join(frames)
            for t in targets:
                if t in blob:
                    in_stack[t] += 1
        cls = None
        size = 0
        alloc_t = None
        frames = []

    for line in proc.stdout.splitlines():
        s = line.strip()
        if s.startswith("jdk.OldObjectSample {"):
            flush()
        elif s.startswith("allocationTime ="):
            m = TIME_RE.search(s)
            alloc_t = m.group(1) if m else None
        elif s.startswith("objectSize ="):
            m = SIZE_RE.search(s)
            size = int(m.group(1)) if m else 0
        elif s.startswith("object =  ["):
            in_obj = True
        elif in_obj and s == "]":
            in_obj = False
        elif in_obj and s:
            cls = s
        elif s.startswith("stackTrace = ["):
            in_stack_block = True
        elif in_stack_block:
            if s == "]":
                in_stack_block = False
            elif s and not s.startswith("..."):
                frames.append(norm(s))
    flush()

    print(f"===== {path} =====")
    print(f"oldEvents={total_events} kept={kept} droppedByCutoff={dropped_cutoff} noStack={no_stack}"
          + (f" cutoff={cutoff}" if cutoff else ""))
    total_bytes = sum(sites.values())
    print(f"sampledOldBytes={total_bytes/1024/1024:.1f}MB (sites kept)")
    print("-- top 40 sites by sampled old bytes --")
    for (cl, fr), b in sites.most_common(40):
        print(f"{site_count[(cl, fr)]:5d}x {b/1024:9.1f}KB  {cl}")
        print(f"        {' <- '.join(fr)}")
    print("-- by class (top 20) --")
    for cl, b in class_bytes.most_common(20):
        print(f"  {b/1024:10.1f}KB  {cl}")
    if targets:
        print("-- in-stack counts --")
        for t in targets:
            print(f"  {in_stack.get(t, 0):5d}  contains '{t}'")


if __name__ == "__main__":
    main()
