#!/usr/bin/env bash
# 批次102 全链（探针轮）：内部提交→rebuildPatches→test→0.64.0 jar→冒烟+160 分解
set -u
cd "$(dirname "$0")/../.."
export GRADLE_USER_HOME=F:/TEMP/.gradle

( cd paper-server/src/minecraft/java && git add net/minecraft/server/level/ChunkMap.java && git commit -q -m "Papo: batch 102 sendChanges build-vs-fanout split probe" ) && echo committed
for A in 1 2 3 4; do ./gradlew :paper-server:rebuildPatches --no-daemon > /tmp/papo102-rebuild.log 2>&1; OK=$(grep -c "BUILD SUCCESSFUL" /tmp/papo102-rebuild.log); echo "rebuild attempt=$A ok=$OK"; [ "$OK" -ge 1 ] && break; sleep 25; done
ls paper-server/patches/features | tail -1
git reset -q -- paper-server/patches 2>/dev/null; git checkout -- paper-server/patches/sources 2>/dev/null; git add paper-server/patches/features/0257-*.patch 2>/dev/null || true
sed -i 's/^papoVersion=0.63.0$/papoVersion=0.64.0/' gradle.properties && grep papoVersion gradle.properties
for A in 1 2 3 4; do ./gradlew :paper-server:test --no-daemon > /tmp/papo102-test.log 2>&1; OK=$(grep -c "BUILD SUCCESSFUL" /tmp/papo102-test.log); echo "test attempt=$A ok=$OK"; [ "$OK" -ge 1 ] && break; sleep 25; done
for A in 1 2 3 4; do ./gradlew :paper-server:createPapoJar --no-daemon > /tmp/papo102-jar.log 2>&1; OK=$(grep -c "BUILD SUCCESSFUL" /tmp/papo102-jar.log); echo "jar attempt=$A ok=$OK"; [ "$OK" -ge 1 ] && break; sleep 25; done
ls -la paper-server/build/libs/Papo-1.21.11-0.64.0.jar

cd benchmark
CP="build/classes;lib/gson-2.11.0.jar;lib/jmh-core-1.37.jar;lib/jmh-generator-annprocess-1.37.jar;lib/jopt-simple-5.0.4.jar;lib/commons-math3-3.6.1.jar;lib/fastutil-8.5.18.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar;lib/concurrentutil-0.0.8.jar;lib/slf4j-api-2.0.1.jar"
for AT in 1 2 3; do java -cp "$CP" papo.bot.SmokeJoinVerify "../paper-server/build/libs/Papo-1.21.11-0.64.0.jar" > results/batch102-smoke.log 2>&1; EC=$?; echo "smoke attempt=$AT exit=$EC $(date +%H:%M:%S)"; [ "$EC" = "0" ] && break; sleep 20; done
for AT in 1 2 3 4 5 6; do
  PID=$(netstat -ano | grep -E ":25594 .*LISTENING" | awk '{print $NF}' | head -1)
  [ -n "$PID" ] && { powershell -Command "Stop-Process -Id $PID -Force" 2>/dev/null; sleep 3; }
  java -cp "$CP" papo.bot.TickSurveyBench "../paper-server/build/libs/Papo-1.21.11-0.64.0.jar" 160 360000 papo90 > results/batch102-decomp-160.log 2>&1
  DONE=$(grep -ac "walk window done" results/batch102-decomp-160.log)
  echo "decomp160 attempt=$AT walkDone=$DONE $(date +%H:%M:%S)"; [ "$DONE" = "1" ] && break; sleep 20
done
echo "BATCH102 CHAIN DONE"
