#!/usr/bin/env bash
# 批次187（R46）：机械负载 tick 窗分配普查发现腿——A=自然基线 vs B=漏斗环+红石时钟机械
# （同二进制 0.108.0 配对差分；JFR ObjectAllocationSample 60s 窗锚 MechCensus.loadWarmed）。
# 工具链纪律（批次186 判例）：编译/运行全部钉死 /f/Java/21；每腿验收 bot 在线正证据 + 零类版本错误。
# 用法: ./mechcensus-r46legs.sh [jar]
set -uo pipefail
cd "$(dirname "$0")"

JAR=${1:-../paper-server/build/libs/Papo-1.21.11-0.108.0.jar}
BOTS=12
SPREAD=150000
DWELL=300000
JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

echo "== 编译（钉死 J21 javac）=="
/f/Java/21/bin/javac -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/MechCensus.java || exit 1

STAMP=$(date +%Y%m%d-%H%M%S)
for LEG in A B; do
  FILL=0
  [ "$LEG" = "B" ] && FILL=1
  LOG="results/mechcensus-r46-$STAMP-$LEG.log"
  OUT="results/allocmech-$STAMP-$LEG"
  mkdir -p "$OUT"
  echo "== 腿 $LEG (fill=$FILL) -> $LOG  $(date +%T) =="

  /f/Java/21/bin/java -cp "$CP" papo.bot.MechCensus "$JAR" "$BOTS" "$SPREAD" "$DWELL" "$FILL" > "$LOG" 2>&1 &
  harness=$!

  pid=""
  for i in $(seq 1 60); do
    sleep 5
    pid=$($JCMD -l 2>/dev/null | grep "server.jar" | awk '{print $1}' | head -1)
    [ -n "$pid" ] && break
  done
  if [ -z "$pid" ]; then echo "腿 $LEG: NO SERVER PID"; kill $harness 2>/dev/null; exit 1; fi
  echo "腿 $LEG server pid=$pid"

  for i in $(seq 1 240); do
    grep -q "MechCensus.loadWarmed" "$LOG" 2>/dev/null && break
    sleep 5
  done
  if ! grep -q "MechCensus.loadWarmed" "$LOG" 2>/dev/null; then
    echo "腿 $LEG: NO loadWarmed 锚点"; kill $harness 2>/dev/null; exit 1
  fi
  echo "腿 $LEG loadWarmed 锚点到达 $(date +%T)，起 JFR 60s"

  $JCMD "$pid" JFR.start name=mech settings=profile duration=60s \
    filename="$PWD/$OUT/mech.jfr" jdk.ObjectAllocationSample#threshold=1ms \
    > "$OUT/jfr-start.txt" 2>&1

  wait $harness
  rc=$?
  echo "腿 $LEG harness exit=$rc"

  # 批次186 判例：bot 在线正证据 + 零类版本错误
  traces=$(grep -c "TRACE\[McB" "$LOG")
  vererr=$(grep -c "UnsupportedClassVersionError\|NoClassDefFoundError" "$LOG")
  echo "腿 $LEG botEvidence traces=$traces (need >= $BOTS) classVersionErrs=$vererr (need 0)"
  grep -E "MechCensus\.(gate|fillsSent|fillMarkerReached|loadWarmed|heapUsedKb)" "$LOG"
  grep -E "MechCensus\.cls" "$LOG"
  if [ $rc -ne 0 ] || [ "$traces" -lt "$BOTS" ] || [ "$vererr" -ne 0 ]; then
    echo "腿 $LEG 判废"; tail -30 "$LOG"; exit $rc
  fi
  echo "$OUT" > "results/last-allocmech-leg-$LEG.txt"
done

echo "== 双腿完成，聚合（B-A 差分判定另行人工）=="
python allocsum.py "$(cat results/last-allocmech-leg-A.txt)/mech.jfr" > "results/allocmech-$STAMP-A-sum.txt" || true
python allocsum.py "$(cat results/last-allocmech-leg-B.txt)/mech.jfr" > "results/allocmech-$STAMP-B-sum.txt" || true
head -45 "results/allocmech-$STAMP-B-sum.txt"
