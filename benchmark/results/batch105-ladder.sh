#!/usr/bin/env bash
# 批次105：实体规模阶梯 0/500/1000/2000 牛 × 6min（0.65.0，10 行走 bot 维持激活）
set -u
cd "$(dirname "$0")/.."
CP="build/classes;lib/gson-2.11.0.jar;lib/jmh-core-1.37.jar;lib/jmh-generator-annprocess-1.37.jar;lib/jopt-simple-5.0.4.jar;lib/commons-math3-3.6.1.jar;lib/fastutil-8.5.18.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar;lib/concurrentutil-0.0.8.jar;lib/slf4j-api-2.0.1.jar"
JAR="../paper-server/build/libs/Papo-1.21.11-0.65.0.jar"
for ENT in 0 500 1000 2000; do
  for AT in 1 2 3 4 5; do
    PID=$(netstat -ano | grep -E ":25594 .*LISTENING" | awk '{print $NF}' | head -1)
    [ -n "$PID" ] && { powershell -Command "Stop-Process -Id $PID -Force" 2>/dev/null; sleep 3; }
    java -cp "$CP" papo.bot.EntityScaleBench "$JAR" "$ENT" 360000 10 > "results/batch105-entscale-$ENT.log" 2>&1
    DONE=$(grep -ac "walk window done" "results/batch105-entscale-$ENT.log")
    echo "=== ents=$ENT attempt=$AT walkDone=$DONE $(date +%H:%M:%S) ==="
    [ "$DONE" = "1" ] && break
    sleep 20
  done
done
echo "BATCH105 LADDER DONE"
