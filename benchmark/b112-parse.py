#!/usr/bin/env python3
"""批次112 阶梯矩阵解析器：PapoTickProfile 窗序列 → 稳态摘要 TSV。

口径：每相位/计数器取尾 1/4 窗的中位数（批次 106 尾窗惯例，与 harness 活性门一致）；
窗口利用率 = totalWallMs / 20000（400 tick × 50ms 名义）。
用法：python b112-parse.py <log> [<log> ...]
"""
import re
import sys

PHASE_RE = re.compile(r"PapoTickProfile\.phase (\S+)\s+total=\s*[\d.]+ms avg/tick=\s*([\d.]+)us")
COUNT_RE = re.compile(r"PapoTickProfile\.count (\S+)\s+total=\s*\d+ avg/tick=\s*([\d.]+)")
WALL_RE = re.compile(r"PapoTickProfile window=400ticks totalWallMs=(\d+)")


def tail_median(values):
    if not values:
        return None
    tail = values[len(values) * 3 // 4:]
    tail = sorted(tail)
    return tail[len(tail) // 2]


def parse(path):
    phases, counts, walls = {}, {}, []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = WALL_RE.search(line)
            if m:
                walls.append(int(m.group(1)))
                continue
            m = PHASE_RE.search(line)
            if m:
                phases.setdefault(m.group(1), []).append(float(m.group(2)))
                continue
            m = COUNT_RE.search(line)
            if m:
                counts.setdefault(m.group(1), []).append(float(m.group(2)))
    return phases, counts, walls


def main():
    keys = ["level.blockTicks", "level.fluidTicks", "level.blockEvents",
            "level.chunkSource", "level.entities", "level.tickPending+misc"]
    print("log\twindows\twallUtil%\t" + "\t".join(k.replace("level.", "") for k in keys)
          + "\trs.blockTickRuns\tusPerSchedTick")
    for path in sys.argv[1:]:
        phases, counts, walls = parse(path)
        name = path.split("/")[-1].replace(".log", "")
        util = tail_median(walls)
        util_pct = f"{100.0 * util / 20000:.1f}" if util else "?"
        vals = []
        for k in keys:
            med = tail_median(phases.get(k, []))
            vals.append(f"{med:.1f}" if med is not None else "?")
        sched = tail_median(counts.get("rs.blockTickRuns", []))
        bt = tail_median(phases.get("level.blockTicks", []))
        per = f"{bt / sched:.1f}" if (sched and bt and sched > 0) else "?"
        sched_s = f"{sched:.1f}" if sched is not None else "?"
        print(f"{name}\t{len(walls)}\t{util_pct}\t" + "\t".join(vals) + f"\t{sched_s}\t{per}")


if __name__ == "__main__":
    main()
