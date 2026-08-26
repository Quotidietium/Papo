#!/usr/bin/env python
# 批次97：阶梯日志解析——从 TickSurveyBench 输出提取各相位稳态 avg/tick。
# 稳态窗口 = 全部窗口去掉首尾各 1 个（join/quit 过渡），对剩余窗口取 avg/tick 均值。
# 用法: python batch97_parse.py results/batch97-ladder-40.log [more.log ...]
import re
import sys

PHASE_RE = re.compile(
    r"PapoTickProfile\.phase\s+(\S+)\s+total=\s*([\d.]+)ms avg/tick=\s*([\d.]+)us share=\s*([\d.]+)% n=(\d+)")
WIN_RE = re.compile(r"PapoTickProfile window=400ticks totalWallMs=(\d+) measuredMs=(\d+)(?: gcMs=(\d+))?")

def parse(path):
    # 每个窗口: {phase: avg_us}; 另存 totalWallMs 序列（tick 滞后信号）
    windows = []
    wall = []
    gc = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = WIN_RE.search(line)
        if m:
            windows.append({})
            wall.append(int(m.group(1)))
            if m.group(3) is not None:
                gc.append(int(m.group(3)))
            continue
        m = PHASE_RE.search(line)
        if m and windows:
            windows[-1][m.group(1)] = float(m.group(3))
    return windows, wall, gc

for path in sys.argv[1:]:
    wins, wall, gc = parse(path)
    n = len(wins)
    tag = path.split("-")[-1].split(".")[0]
    print(f"== {path} windows={n} gcMs={' '.join(str(g) for g in gc)}")
    print(f"   wallMs={' '.join(str(w) for w in wall)}")
    if n < 4:
        print("   (too few windows)")
        continue
    steady = wins[1:-1]
    phases = sorted({p for w in steady for p in w})
    total = 0.0
    for p in phases:
        vals = [w[p] for w in steady if p in w]
        avg = sum(vals) / len(vals)
        total += avg
        mn = min(vals)
        print(f"   {p:<28s} avg={avg:8.1f}us min={mn:8.1f}us n_win={len(vals)}")
    print(f"   {'TOTAL(main-thread)':<28s} avg={total:8.1f}us  util@50ms={100*total/50000:5.2f}%")
