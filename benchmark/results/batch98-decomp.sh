#!/usr/bin/env bash
# 批次98：探针分解基准 40/120/160 × 6min（0.60.0 jar，-Dpapo.tickProfile=1 由 TickSurveyBench 内置）
# 外部击杀防御：每点带 walkDone 判定重试（最多 4 次）
set -u
cd "$(dirname "$0")/.."
CP="build/classes;lib/gson-2.11.0.jar;lib/jmh-core-1.37.jar;lib/jmh-generator-annprocess-1.37.jar;lib/jopt-simple-5.0.4.jar;lib/commons-math3-3.6.1.jar;lib/fastutil-8.5.18.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar;lib/concurrentutil-0.0.8.jar;lib/slf4j-api-2.0.1.jar"
JAR="../paper-server/build/libs/Papo-1.21.11-0.60.0.jar"
for BOTS in 40 120 160; do
  for ATTEMPT in 1 2 3 4 5 6; do
    java -cp "$CP" papo.bot.TickSurveyBench "$JAR" "$BOTS" 360000 papo90 \
      > "results/batch98-decomp-$BOTS.log" 2>&1
    EC=$?
    DONE=$(grep -ac "walk window done" "results/batch98-decomp-$BOTS.log")
    echo "=== bots=$BOTS attempt=$ATTEMPT exit=$EC walkDone=$DONE $(date +%H:%M:%S) ==="
    [ "$DONE" = "1" ] && break
    sleep 20
  done
done
echo "BATCH98 BENCH DONE"
