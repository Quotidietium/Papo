#!/usr/bin/env bash
# 批次186（R45）：BE 密集内容轴发现腿——A=自然基线 vs B=BE 密集（同二进制配对差分）。
# 用法: ./becensus-r45legs.sh [jar] [bots] [spreadMs] [dwellMs]
set -uo pipefail
cd "$(dirname "$0")"

JAR=${1:-../paper-server/build/libs/Papo-1.21.11-0.107.0.jar}
BOTS=${2:-12}
SPREAD=${3:-150000}
DWELL=${4:-240000}

LIB=lib
OUT=build/classes
CP="$LIB/gson-2.11.0.jar;$LIB/jmh-core-1.37.jar;$LIB/jmh-generator-annprocess-1.37.jar;$LIB/jopt-simple-5.0.4.jar;$LIB/commons-math3-3.6.1.jar;$LIB/fastutil-8.5.18.jar;$LIB/netty-buffer-4.2.7.Final.jar;$LIB/netty-common-4.2.7.Final.jar;$LIB/netty-transport-4.2.7.Final.jar;$LIB/netty-codec-base-4.2.7.Final.jar;$LIB/concurrentutil-0.0.8.jar;$LIB/slf4j-api-2.0.1.jar"

mkdir -p "$OUT" results
echo "== 编译 bot 源码（钉死 J21 javac：防 PATH javac 异版污染共享 classes；bench 包需 concurrentutil 完整版，非本工具依赖面）=="
/f/Java/21/bin/javac -encoding UTF-8 -sourcepath src -cp "$LIB/gson-2.11.0.jar;$LIB/netty-buffer-4.2.7.Final.jar;$LIB/netty-common-4.2.7.Final.jar;$LIB/netty-transport-4.2.7.Final.jar;$LIB/netty-codec-base-4.2.7.Final.jar" -d "$OUT" src/papo/bot/BeDensifyCensus.java || exit 1

STAMP=$(date +%Y%m%d-%H%M%S)
for LEG in A B; do
  FILL=0
  [ "$LEG" = "B" ] && FILL=1
  LOG="results/becensus-r45-$STAMP-$LEG.log"
  echo "== 腿 $LEG (fill=$FILL) -> $LOG =="
  /f/Java/21/bin/java -cp "$OUT;$CP" papo.bot.BeDensifyCensus "$JAR" "$BOTS" "$SPREAD" "$DWELL" "$FILL" > "$LOG" 2>&1
  RC=$?
  echo "leg $LEG rc=$RC"
  grep -E "BeCensus\.(heapUsedKb|gate|fillsSent|fillMarkerReached)" "$LOG"
  grep -E "BeCensus\.cls net.minecraft.(world.level.block.entity|core.NonNullList|world.item.ItemStack)" "$LOG"
  if [ $RC -ne 0 ]; then
    echo "腿 $LEG 失败，尾部日志："
    tail -30 "$LOG"
    exit $RC
  fi
done
echo "== 双腿完成 =="
