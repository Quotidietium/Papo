#!/usr/bin/env bash
# 批次110：实体链 AI/非AI 分量分解（NoAI 变体 A/B，同 harness 同 N=2000 容量墙点）
# AI 版 = 完整 serverAiStep（goal/nav/look）+ 物理/push/EAR/追踪
# NoAI 版 = 跳过 serverAiStep，其余不动 → 每牛 AI 分量 = (AI - NoAI)/N
set -u
CP="F:/Github/repo/Papo/benchmark/build/classes"
JAR="F:/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.67.0.jar"
OUT="F:/Github/repo/Papo/benchmark/results"
N=2000
for MODE in ai noai; do
  EXTRA=""
  [ "$MODE" = "noai" ] && EXTRA="noai"
  echo "== N=$N mode=$MODE start $(date +%H:%M:%S)"
  "F:/Java/21/bin/java" -cp "$CP" papo.bot.EntityScaleBench "$JAR" "$N" 180000 10 $EXTRA > "$OUT/batch110-N$N-$MODE.log" 2>&1
  echo "== N=$N mode=$MODE exit=$? $(date +%H:%M:%S)"
  grep -aE "cowsPresent|presence gate" "$OUT/batch110-N$N-$MODE.log" | head -3
done
