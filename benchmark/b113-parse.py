#!/usr/bin/env python3
"""批次113 TPS 稳定性解析器：PapoTickProfile.tickdist 窗序列 → 摘要。

口径：全窗聚合（每个 400-tick 窗的 p50/p95/p99/max 已由服务器算好；本脚本对
窗序列取 min/max/median，暴露"波动"——窗间 TPS 极差、最差 p99、超时计数总和）。
用法：python b113-parse.py <log> [<log> ...]
"""
import re
import sys

TD_RE = re.compile(
    r"PapoTickProfile\.tickdist n=(\d+) "
    r"durMs\[p50=([\d.]+) p95=([\d.]+) p99=([\d.]+) max=([\d.]+)\] "
    r"over45=(\d+) over50=(\d+) "
    r"\| gapMs\[p95=([\d.]+) p99=([\d.]+) max=([\d.]+)\] stalls100ms=(\d+) "
    r"\| tps=([\d.]+)")


def parse(path):
    rows = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = TD_RE.search(line)
            if m:
                rows.append({
                    "n": int(m.group(1)),
                    "p50": float(m.group(2)), "p95": float(m.group(3)),
                    "p99": float(m.group(4)), "max": float(m.group(5)),
                    "over45": int(m.group(6)), "over50": int(m.group(7)),
                    "gp95": float(m.group(8)), "gp99": float(m.group(9)),
                    "gmax": float(m.group(10)), "stalls": int(m.group(11)),
                    "tps": float(m.group(12)),
                })
    return rows


def med(vals):
    s = sorted(vals)
    return s[len(s) // 2]


def main():
    print("log\twindows\tworstP50\tworstP95\tworstP99\tworstMax\tmedMax\tover50sum\tstallSum\t"
          "tpsMin\ttpsMax\ttpsSpread")
    for path in sys.argv[1:]:
        rows = parse(path)
        name = path.split("/")[-1].replace(".log", "")
        if not rows:
            print(f"{name}\tNO TICKDIST LINES")
            continue
        # 丢弃爬坡/停机窗：n<400 或 tps<5（boot 期）
        steady = [r for r in rows if r["n"] >= 400 and r["tps"] > 5]
        if not steady:
            steady = rows
        tps = [r["tps"] for r in steady]
        print(f"{name}\t{len(rows)}\t"
              f"{max(r['p50'] for r in steady):.2f}\t{max(r['p95'] for r in steady):.2f}\t"
              f"{max(r['p99'] for r in steady):.2f}\t{max(r['max'] for r in steady):.1f}\t"
              f"{med(r['max'] for r in steady):.1f}\t"
              f"{sum(r['over50'] for r in steady)}\t{sum(r['stalls'] for r in steady)}\t"
              f"{min(tps):.2f}\t{max(tps):.2f}\t{max(tps) - min(tps):.2f}")


if __name__ == "__main__":
    main()
