#!/usr/bin/env bash
# 批次97：真并发规模阶梯探测 40/80/120/160 bot × 6min（种子 papo90，0.59.0 jar）
# 每点独立临时目录+服务器实例，顺序执行。输出 results/batch97-ladder-<bots>.log
set -u
cd "$(dirname "$0")/.."
CP="build/classes;lib/gson-2.11.0.jar;lib/jmh-core-1.37.jar;lib/jmh-generator-annprocess-1.37.jar;lib/jopt-simple-5.0.4.jar;lib/commons-math3-3.6.1.jar;lib/fastutil-8.5.18.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar;lib/concurrentutil-0.0.8.jar;lib/slf4j-api-2.0.1.jar"
JAR="../paper-server/build/libs/Papo-1.21.11-0.59.0.jar"
for BOTS in 40 80 120 160; do
  echo "=== ladder point bots=$BOTS start $(date +%H:%M:%S) ==="
  java -cp "$CP" papo.bot.TickSurveyBench "$JAR" "$BOTS" 360000 papo90 \
    > "results/batch97-ladder-$BOTS.log" 2>&1
  echo "=== ladder point bots=$BOTS exit=$? $(date +%H:%M:%S) ==="
done
echo "LADDER DONE"
