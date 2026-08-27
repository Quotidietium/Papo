#!/usr/bin/env bash
# 批次99 验证链：四态冒烟（0.61.0）+ 160bot 分解 A/B（vs 批次98 的 0.60.0 数据）
set -u
cd "$(dirname "$0")/.."
CP="build/classes;lib/gson-2.11.0.jar;lib/jmh-core-1.37.jar;lib/jmh-generator-annprocess-1.37.jar;lib/jopt-simple-5.0.4.jar;lib/commons-math3-3.6.1.jar;lib/fastutil-8.5.18.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar;lib/concurrentutil-0.0.8.jar;lib/slf4j-api-2.0.1.jar"
JAR="../paper-server/build/libs/Papo-1.21.11-0.61.0.jar"

echo "=== smoke (four-state) ==="
for ATTEMPT in 1 2 3; do
  java -cp "$CP" papo.bot.SmokeJoinVerify "$JAR" > results/batch99-smoke.log 2>&1
  EC=$?
  DONE=$(grep -ac "SMOKE OK\|smoke.*PASS\|ALL PASS" results/batch99-smoke.log)
  echo "smoke attempt=$ATTEMPT exit=$EC passmark=$DONE $(date +%H:%M:%S)"
  [ "$EC" = "0" ] && break
  sleep 20
done

echo "=== 160bot decomposition A/B (after) ==="
for ATTEMPT in 1 2 3 4 5 6; do
  java -cp "$CP" papo.bot.TickSurveyBench "$JAR" 160 360000 papo90 > results/batch99-decomp-160.log 2>&1
  EC=$?
  DONE=$(grep -ac "walk window done" results/batch99-decomp-160.log)
  echo "decomp160 attempt=$ATTEMPT exit=$EC walkDone=$DONE $(date +%H:%M:%S)"
  [ "$DONE" = "1" ] && break
  sleep 20
done
echo "BATCH99 VERIFY DONE"
