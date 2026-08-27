#!/usr/bin/env bash
# 批次101 全链（从 benchmark/ 运行；gradlew 在仓库根）
set -u
cd "$(dirname "$0")/../.."
export GRADLE_USER_HOME=F:/TEMP/.gradle

echo "=== internal commit ==="
( cd paper-server/src/minecraft/java && git add -A && git commit -q -m "Papo: batch 101 O(1) tracker purge probe (per-chunk updateCount-keyed view set)" ) && echo committed

echo "=== rebuildPatches ==="
for A in 1 2 3 4; do ./gradlew :paper-server:rebuildPatches --no-daemon > /tmp/papo101-rebuild.log 2>&1; OK=$(grep -c "BUILD SUCCESSFUL" /tmp/papo101-rebuild.log); echo "rebuild attempt=$A ok=$OK"; [ "$OK" -ge 1 ] && break; sleep 25; done
ls paper-server/patches/features | tail -1

echo "=== version bump ==="
sed -i 's/^papoVersion=0.62.0$/papoVersion=0.63.0/' gradle.properties && grep papoVersion gradle.properties

echo "=== test ==="
for A in 1 2 3 4; do ./gradlew :paper-server:test --no-daemon > /tmp/papo101-test.log 2>&1; OK=$(grep -c "BUILD SUCCESSFUL" /tmp/papo101-test.log); echo "test attempt=$A ok=$OK"; [ "$OK" -ge 1 ] && break; sleep 25; done

echo "=== jar ==="
for A in 1 2 3 4; do ./gradlew :paper-server:createPapoJar --no-daemon > /tmp/papo101-jar.log 2>&1; OK=$(grep -c "BUILD SUCCESSFUL" /tmp/papo101-jar.log); echo "jar attempt=$A ok=$OK"; [ "$OK" -ge 1 ] && break; sleep 25; done
ls -la paper-server/build/libs/Papo-1.21.11-0.63.0.jar

echo "=== smoke + 160 A/B ==="
cd benchmark
CP="build/classes;lib/gson-2.11.0.jar;lib/jmh-core-1.37.jar;lib/jmh-generator-annprocess-1.37.jar;lib/jopt-simple-5.0.4.jar;lib/commons-math3-3.6.1.jar;lib/fastutil-8.5.18.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar;lib/concurrentutil-0.0.8.jar;lib/slf4j-api-2.0.1.jar"
for AT in 1 2 3; do
  java -cp "$CP" papo.bot.SmokeJoinVerify "../paper-server/build/libs/Papo-1.21.11-0.63.0.jar" > results/batch101-smoke.log 2>&1
  EC=$?; echo "smoke attempt=$AT exit=$EC $(date +%H:%M:%S)"; [ "$EC" = "0" ] && break; sleep 20
done
for AT in 1 2 3 4 5 6; do
  PID=$(netstat -ano | grep -E ":25594 .*LISTENING" | awk '{print $NF}' | head -1)
  [ -n "$PID" ] && { echo "killing port orphan pid=$PID"; powershell -Command "Stop-Process -Id $PID -Force" 2>/dev/null; sleep 3; }
  java -cp "$CP" papo.bot.TickSurveyBench "../paper-server/build/libs/Papo-1.21.11-0.63.0.jar" 160 360000 papo90 > results/batch101-decomp-160.log 2>&1
  DONE=$(grep -ac "walk window done" results/batch101-decomp-160.log)
  echo "decomp160 attempt=$AT walkDone=$DONE $(date +%H:%M:%S)"; [ "$DONE" = "1" ] && break; sleep 20
done
echo "BATCH101 CHAIN DONE"
