#!/usr/bin/env bash
# 批次108：0.67.0 在位 A/B（before=0.66.0 批次107 数据，同 harness）
set -u
CP="F:/Github/repo/Papo/benchmark/build/classes"
JAR="F:/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.67.0.jar"
OUT="F:/Github/repo/Papo/benchmark/results"
for N in 2000 500; do
  echo "== N=$N start $(date +%H:%M:%S)"
  "F:/Java/21/bin/java" -cp "$CP" papo.bot.EntityScaleBench "$JAR" "$N" 180000 10 > "$OUT/batch108-ab-N$N.log" 2>&1
  echo "== N=$N exit=$? $(date +%H:%M:%S)"
  grep -aE "cowsPresent|presence gate" "$OUT/batch108-ab-N$N.log" | head -3
done
