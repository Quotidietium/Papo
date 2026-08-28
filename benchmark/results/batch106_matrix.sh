#!/usr/bin/env bash
# 批次106：实体规模轴阶梯矩阵（冒烟门 PASS 后手动执行）
set -u
cd "$(dirname "$0")/.."
CP="F:/Github/repo/Papo/benchmark/build/classes"
JAR="F:/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.65.0.jar"
OUT="results"
for N in 0 500 1000 2000 4000; do
  echo "== N=$N start $(date +%H:%M:%S)"
  "F:/Java/21/bin/java" -cp "$CP" papo.bot.EntityScaleBench "$JAR" "$N" 360000 10 > "$OUT/batch106-scale-N$N.log" 2>&1
  rc=$?
  echo "== N=$N exit=$rc $(date +%H:%M:%S)"
  grep -E "cowsPresent|presence gate|logErrors" "$OUT/batch106-scale-N$N.log" | head -4
done
