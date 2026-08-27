#!/usr/bin/env bash
# 批次101 补测：同 epoch 背靠背 160bot 对拍 0.62.0 vs 0.63.0（相邻运行抵消争抢漂移）
set -u
cd "$(dirname "$0")/.."
CP="build/classes;lib/gson-2.11.0.jar;lib/jmh-core-1.37.jar;lib/jmh-generator-annprocess-1.37.jar;lib/jopt-simple-5.0.4.jar;lib/commons-math3-3.6.1.jar;lib/fastutil-8.5.18.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar;lib/concurrentutil-0.0.8.jar;lib/slf4j-api-2.0.1.jar"

run_one() { # $1=jar $2=outlog
  for AT in 1 2 3 4; do
    PID=$(netstat -ano | grep -E ":25594 .*LISTENING" | awk '{print $NF}' | head -1)
    [ -n "$PID" ] && { powershell -Command "Stop-Process -Id $PID -Force" 2>/dev/null; sleep 3; }
    java -cp "$CP" papo.bot.TickSurveyBench "$1" 160 360000 papo90 > "$2" 2>&1
    DONE=$(grep -ac "walk window done" "$2")
    echo "  run $1 attempt=$AT walkDone=$DONE $(date +%H:%M:%S)"
    [ "$DONE" = "1" ] && return 0
    sleep 20
  done
  return 1
}

for PAIR in 1 2; do
  echo "=== pair $PAIR (21:xx epoch) ==="
  run_one "../paper-server/build/libs/Papo-1.21.11-0.62.0.jar" "results/batch101-ab-062-p$PAIR.log"
  run_one "../paper-server/build/libs/Papo-1.21.11-0.63.0.jar" "results/batch101-ab-063-p$PAIR.log"
done
echo "AB BACK-TO-BACK DONE"
