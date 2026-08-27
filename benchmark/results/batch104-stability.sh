#!/usr/bin/env bash
# 批次104 稳定性回归轮：0.65.0 长时浸泡（10bot×10min）+ 停机竞态扩样（×5）
set -u
cd "$(dirname "$0")/.."
CP="build/classes;lib/gson-2.11.0.jar;lib/jmh-core-1.37.jar;lib/jmh-generator-annprocess-1.37.jar;lib/jopt-simple-5.0.4.jar;lib/commons-math3-3.6.1.jar;lib/fastutil-8.5.18.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar;lib/concurrentutil-0.0.8.jar;lib/slf4j-api-2.0.1.jar"
JAR="../paper-server/build/libs/Papo-1.21.11-0.65.0.jar"

echo "=== soak 10bot x 10min ==="
for AT in 1 2 3 4; do
  PID=$(netstat -ano | grep -E ":25594 .*LISTENING" | awk '{print $NF}' | head -1)
  [ -n "$PID" ] && { powershell -Command "Stop-Process -Id $PID -Force" 2>/dev/null; sleep 3; }
  java -cp "$CP" papo.bot.TickSurveyBench "$JAR" 10 600000 papo90 > results/batch104-soak10.log 2>&1
  DONE=$(grep -ac "walk window done" results/batch104-soak10.log)
  echo "soak attempt=$AT walkDone=$DONE $(date +%H:%M:%S)"; [ "$DONE" = "1" ] && break; sleep 20
done

echo "=== shutdown race x5 ==="
for AT in 1 2 3; do
  java -cp "$CP" papo.bot.ShutdownRaceVerify "$JAR" 5 > results/batch104-race5.log 2>&1
  EC=$?; PASS=$(grep -ac "PASS\|exit 0" results/batch104-race5.log | head -1)
  echo "race attempt=$AT exit=$EC $(date +%H:%M:%S)"; [ "$EC" = "0" ] && break; sleep 20
done
echo "BATCH104 STABILITY DONE"
