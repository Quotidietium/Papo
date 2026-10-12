#!/usr/bin/env bash
# 批次187（R46）：机械负载配对验证腿——V108=0.108.0 出厂件 vs V109=0.109.0 收割件，
# 双腿同为机械负载（fill=1），唯一变量=jar 版本。验收门（R41 判例）：
#   1) bot 在线正证据 TRACE\[McB 计 >= BOTS、零类版本错误（批次186 判例）；
#   2) 面坍缩门：V109 的 getEntityContainer/getItemsAtAndAbove 分配面相对 V108 归零
#      （allocsum.py 聚合后按 (allocFrame, callerFrame) 位点比对）。
# 工具链纪律：编译/运行全部钉死 /f/Java/21。
# 用法: ./mechverify-r46legs.sh [jar108] [jar109]
set -uo pipefail
cd "$(dirname "$0")"

JAR108=${1:-../paper-server/build/libs/Papo-1.21.11-0.108.0.jar}
JAR109=${2:-../paper-server/build/libs/Papo-1.21.11-0.109.0.jar}
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
for LEG in V108 V109; do
  if [ "$LEG" = "V108" ]; then JAR="$JAR108"; else JAR="$JAR109"; fi
  FILL=1
  LOG="results/mechverify-r46-$STAMP-$LEG.log"
  OUT="results/allocmech-$STAMP-$LEG"
  mkdir -p "$OUT"
  echo "== 腿 $LEG jar=$JAR (fill=$FILL) -> $LOG  $(date +%T) =="

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

  traces=$(grep -c "TRACE\[McB" "$LOG")
  vererr=$(grep -c "UnsupportedClassVersionError\|NoClassDefFoundError" "$LOG")
  echo "腿 $LEG botEvidence traces=$traces (need >= $BOTS) classVersionErrs=$vererr (need 0)"
  grep -E "MechCensus\.(gate|fillsSent|fillMarkerReached|loadWarmed|heapUsedKb)" "$LOG"
  if [ $rc -ne 0 ] || [ "$traces" -lt "$BOTS" ] || [ "$vererr" -ne 0 ]; then
    echo "腿 $LEG 判废"; tail -30 "$LOG"; exit $rc
  fi
  echo "$OUT" > "results/last-mechverify-leg-$LEG.txt"
done

echo "== 双腿完成，聚合 =="
python allocsum.py "$(cat results/last-mechverify-leg-V108.txt)/mech.jfr" > "results/allocmech-$STAMP-V108-sum.txt" || true
python allocsum.py "$(cat results/last-mechverify-leg-V109.txt)/mech.jfr" > "results/allocmech-$STAMP-V109-sum.txt" || true
echo "-- V108 (0.108.0) hopper 面 --"
grep -E "HopperBlockEntity" "results/allocmech-$STAMP-V108-sum.txt" | head -8
echo "-- V109 (0.109.0) hopper 面（收割面应归零/仅剩共享 AABB 缓存残余）--"
grep -E "HopperBlockEntity" "results/allocmech-$STAMP-V109-sum.txt" | head -8
