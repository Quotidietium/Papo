#!/usr/bin/env bash
# 批次107：扇出包型归因运行（0259 探针，0.66.0 jar）
set -u
CP="F:/Github/repo/Papo/benchmark/build/classes"
JAR="F:/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.66.0.jar"
OUT="F:/Github/repo/Papo/benchmark/results"
for N in 500 2000; do
  echo "== N=$N start $(date +%H:%M:%S)"
  "F:/Java/21/bin/java" -cp "$CP" papo.bot.EntityScaleBench "$JAR" "$N" 360000 10 > "$OUT/batch107-attr-N$N.log" 2>&1
  echo "== N=$N exit=$? $(date +%H:%M:%S)"
  grep -E "cowsPresent|presence gate" "$OUT/batch107-attr-N$N.log" | head -3
done
