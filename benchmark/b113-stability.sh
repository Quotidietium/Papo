#!/bin/bash
# 批次113 TPS 稳定性矩阵：实体 N=500/2000 + 红石 441（10 bot，6min 窗，tickProfile=1）
set -u
JAR=../paper-server/build/libs/Papo-1.21.11-0.70.0.jar
run_ent () {
  local N=$1 TAG=$2 EXTRA=$3
  echo "=== ent N=$N $TAG start $(date +%H:%M:%S) ==="
  F:/Java/21/bin/java -cp build/classes papo.bot.EntityScaleBench "$JAR" "$N" 360000 10 $EXTRA \
    > "F:/TEMP/papo-b113-ent${N}-${TAG}.log" 2>&1
  echo "=== ent N=$N $TAG exit=$? $(date +%H:%M:%S) ==="
}
echo "=== rs 441 vanilla start $(date +%H:%M:%S) ==="
F:/Java/21/bin/java -cp build/classes papo.bot.RedstoneScaleBench "$JAR" 441 360000 10 vanilla \
  > "F:/TEMP/papo-b113-rs441.log" 2>&1
echo "=== rs 441 exit=$? $(date +%H:%M:%S) ==="
run_ent 500 base ""
run_ent 2000 wall ""
