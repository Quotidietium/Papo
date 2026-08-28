#!/usr/bin/env python
# 批次106：实体规模轴阶梯解析——从 EntityScaleBench v5 输出提取各相位稳态 avg/tick。
# 与 batch97_parse.py 同正则；额外抽取在场门行（cowsPresentA/B、presence gate）与
# 牛群在场后的稳态窗（取探针 A 之后的窗口：稳态 = 全部窗口去掉首 2（boot/建造）尾 1）。
# 用法: python batch106_parse.py results/batch106-scale-N500.log [more.log ...]
import re
import sys

PHASE_RE = re.compile(
    r"PapoTickProfile\.phase\s+(\S+)\s+total=\s*([\d.]+)ms avg/tick=\s*([\d.]+)us share=\s*([\d.]+)% n=(\d+)")
WIN_RE = re.compile(r"PapoTickProfile window=400ticks totalWallMs=(\d+) measuredMs=(\d+)(?: gcMs=(\d+))?")

def parse(path):
    windows = []
    wall = []
    gc = []
    present = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.search(r"cowsPresent([AB])=(\d+)", line)
        if m:
            present[m.group(1)] = int(m.group(2))
            continue
        if "presence gate" in line:
            present["gate"] = "PASS" if "PASS" in line else "FAIL"
            continue
        m = WIN_RE.search(line)
        if m:
            windows.append({})
            wall.append(int(m.group(1)))
            gc.append(int(m.group(3)) if m.group(3) else -1)
            continue
        m = PHASE_RE.search(line)
        if m and windows:
            windows[-1][m.group(1)] = float(m.group(3))
    return windows, wall, gc, present

for path in sys.argv[1:]:
    wins, wall, gc, present = parse(path)
    n = len(wins)
    tag = path.split("-")[-1].split(".")[0]
    print(f"== {path} windows={n} present={present}")
    print(f"   wallMs={' '.join(str(w) for w in wall)}")
    if n < 5:
        print("   (too few windows)")
        continue
    # 牛群在场窗：去掉 boot/join/build 段的前 2 窗与探针尾 1 窗
    steady = wins[2:-1]
    phases = sorted({p for w in steady for p in w})
    total = 0.0
    for p in phases:
        vals = [w[p] for w in steady if p in w]
        avg = sum(vals) / len(vals)
        total += avg
        print(f"   {p:<28s} avg={avg:8.1f}us min={min(vals):8.1f}us n_win={len(vals)}")
    print(f"   {'TOTAL(main-thread)':<28s} avg={total:8.1f}us  util@50ms={100*total/50000:5.2f}%")
