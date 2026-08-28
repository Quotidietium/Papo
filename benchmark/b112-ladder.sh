#!/bin/bash
# 批次112 阶梯矩阵：VANILLA N=0/100/256/441（10 bot，6min 窗）+ 固定点 N=256 A/C、EIGENCRAFT 对比
set -u
JAR=../paper-server/build/libs/Papo-1.21.11-0.69.0.jar
for spec in "0 vanilla" "100 vanilla" "256 vanilla" "441 vanilla" "256 ac" "256 eigencraft"; do
  set -- $spec
  N=$1; IMPL=$2
  echo "=== ladder N=$N impl=$IMPL start $(date +%H:%M:%S) ==="
  F:/Java/21/bin/java -cp build/classes papo.bot.RedstoneScaleBench "$JAR" "$N" 360000 10 "$IMPL" \
    > "F:/TEMP/papo-b112-N${N}-${IMPL}.log" 2>&1
  echo "=== ladder N=$N impl=$IMPL exit=$? $(date +%H:%M:%S) ==="
done
