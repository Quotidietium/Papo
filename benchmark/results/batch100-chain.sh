#!/usr/bin/env bash
# 批次100 全链：内部提交 + rebuildPatches + test + 0.62.0 jar + 160bot A/B
set -u
cd "$(dirname "$0")/.."
export GRADLE_USER_HOME=F:/TEMP/.gradle

echo "=== internal commit ==="
( cd paper-server/src/minecraft/java && git add -A && git commit -q -m "Papo: batch 100 per-player scratch list for aiStep touch scan (kill per-tick ArrayList, predicate verbatim NO_SPECTATORS)" ) && echo committed

echo "=== rebuildPatches ==="
for A in 1 2 3 4; do ../gradlew :paper-server:rebuildPatches --no-daemon > /tmp/papo100-rebuild.log 2>&1; OK=$(grep -c "BUILD SUCCESSFUL" /tmp/papo100-rebuild.log); echo "rebuild attempt=$A ok=$OK"; [ "$OK" -ge 1 ] && break; sleep 25; done
ls ../paper-server/patches/features | tail -1

echo "=== version bump ==="
sed -i 's/^papoVersion=0.61.0$/papoVersion=0.62.0/' ../gradle.properties && grep papoVersion ../gradle.properties

echo "=== test ==="
for A in 1 2 3 4; do ../gradlew :paper-server:test --no-daemon > /tmp/papo100-test.log 2>&1; OK=$(grep -c "BUILD SUCCESSFUL" /tmp/papo100-test.log); echo "test attempt=$A ok=$OK"; [ "$OK" -ge 1 ] && break; sleep 25; done

echo "=== jar ==="
for A in 1 2 3 4; do ../gradlew :paper-server:createPapoJar --no-daemon > /tmp/papo100-jar.log 2>&1; OK=$(grep -c "BUILD SUCCESSFUL" /tmp/papo100-jar.log); echo "jar attempt=$A ok=$OK"; [ "$OK" -ge 1 ] && break; sleep 25; done
ls -la ../paper-server/build/libs/Papo-1.21.11-0.62.0.jar

echo "=== 160bot A/B ==="
CP="build/classes;lib/gson-2.11.0.jar;lib/jmh-core-1.37.jar;lib/jmh-generator-annprocess-1.37.jar;lib/jopt-simple-5.0.4.jar;lib/commons-math3-3.6.1.jar;lib/fastutil-8.5.18.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar;lib/concurrentutil-0.0.8.jar;lib/slf4j-api-2.0.1.jar"
for ATTEMPT in 1 2 3 4 5 6; do
  java -cp "$CP" papo.bot.TickSurveyBench "../paper-server/build/libs/Papo-1.21.11-0.62.0.jar" 160 360000 papo90 > results/batch100-decomp-160.log 2>&1
  EC=$?
  DONE=$(grep -ac "walk window done" results/batch100-decomp-160.log)
  echo "decomp160 attempt=$ATTEMPT exit=$EC walkDone=$DONE $(date +%H:%M:%S)"
  [ "$DONE" = "1" ] && break
  sleep 20
done
echo "BATCH100 CHAIN DONE"
