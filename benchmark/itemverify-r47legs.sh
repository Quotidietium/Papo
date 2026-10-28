#!/usr/bin/env bash
# 批次188（R47）：物品旋涡配对验证腿——V109=0.109.0 出厂件 vs V110=0.110.0 收割件，
# 双腿同为旋涡负载（fill=1，探针门全开），唯一变量=jar 版本。验收门（R41/R46 判例）：
#   1) bot 在线正证据 TRACE\[ICB 计 >= BOTS、零类版本错误（批次186 判例）；
#   2) 旋涡流转正证据（漏斗吸取 + chest cobblestone + 物品在场——探针①②）；
#   3) 面坍缩门：V110 的 LongOpenHashSet.<init>（travel-dedupe）与 betweenCornersInDirection
#      迭代器簇（BlockPos$6 / lambda$8 / allocateInstance←betweenCorners）相对 V109 归零，
#      替代面（MutableBlockPos 新增应远低于阈）。
# 工具链纪律：编译/运行全部钉死 /f/Java/21。
# 用法: ./itemverify-r47legs.sh [jar109] [jar110]
set -uo pipefail
cd "$(dirname "$0")"

JAR109=${1:-../paper-server/build/libs/Papo-1.21.11-0.109.0.jar}
JAR110=${2:-../paper-server/build/libs/Papo-1.21.11-0.110.0.jar}
BOTS=12
SPREAD=150000
DWELL=300000
JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

echo "== 编译（钉死 J21 javac）=="
/f/Java/21/bin/javac -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/ItemChurnCensus.java || exit 1

STAMP=$(date +%Y%m%d-%H%M%S)
for LEG in V109 V110; do
  if [ "$LEG" = "V109" ]; then JAR="$JAR109"; else JAR="$JAR110"; fi
  LOG="results/itemverify-r47-$STAMP-$LEG.log"
  OUT="results/allocitem-$STAMP-$LEG"
  mkdir -p "$OUT"
  echo "== 腿 $LEG jar=$JAR (fill=1) -> $LOG  $(date +%T) =="

  /f/Java/21/bin/java -cp "$CP" papo.bot.ItemChurnCensus "$JAR" "$BOTS" "$SPREAD" "$DWELL" 1 > "$LOG" 2>&1 &
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
    grep -q "ItemChurnCensus.loadWarmed" "$LOG" 2>/dev/null && break
    sleep 5
  done
  if ! grep -q "ItemChurnCensus.loadWarmed" "$LOG" 2>/dev/null; then
    echo "腿 $LEG: NO loadWarmed 锚点"; kill $harness 2>/dev/null; exit 1
  fi
  echo "腿 $LEG loadWarmed 锚点到达 $(date +%T)，起 JFR 60s"

  $JCMD "$pid" JFR.start name=item settings=profile duration=60s \
    filename="$PWD/$OUT/churn.jfr" jdk.ObjectAllocationSample#threshold=1ms \
    > "$OUT/jfr-start.txt" 2>&1

  wait $harness
  rc=$?
  echo "腿 $LEG harness exit=$rc"

  traces=$(grep -c "TRACE\[ICB" "$LOG")
  vererr=$(grep -c "UnsupportedClassVersionError\|NoClassDefFoundError" "$LOG")
  echo "腿 $LEG botEvidence traces=$traces (need >= $BOTS) classVersionErrs=$vererr (need 0)"
  grep -E "ItemChurnCensus\.(gate|fillsSent|fillMarkerReached|loadWarmed|heapUsedKb)" "$LOG"
  hopper=$(awk '/loadWarmed/{exit} /ItemChurnCensus\.probe.*has the following block data/{n++} END{print n+0}' "$LOG")
  chest=$(awk '/loadWarmed/{seen=1} seen&&/ItemChurnCensus\.probe.*has the following block data.*cobblestone/{n++} END{print n+0}' "$LOG")
  present=$(grep -c "ItemChurnCensus.probe.*CHURN-ITEMS-PRESENT" "$LOG")
  echo "腿 $LEG churnEvidence hopperItems=$hopper chestCobble=$chest itemsPresent=$present (each need >= 1)"
  if [ $rc -ne 0 ] || [ "$traces" -lt "$BOTS" ] || [ "$vererr" -ne 0 ] \
     || [ "$hopper" -lt 1 ] || [ "$chest" -lt 1 ] || [ "$present" -lt 1 ]; then
    echo "腿 $LEG 判废"; tail -30 "$LOG"; exit 1
  fi
  echo "$OUT" > "results/last-itemverify-leg-$LEG.txt"
done

echo "== 双腿完成，聚合 =="
python allocsum.py "$(cat results/last-itemverify-leg-V109.txt)/churn.jfr" > "results/allocitem-$STAMP-V109-sum.txt" || true
python allocsum.py "$(cat results/last-itemverify-leg-V110.txt)/churn.jfr" > "results/allocitem-$STAMP-V110-sum.txt" || true
echo "-- V109 (0.109.0) 收割面 --"
grep -E "LongOpenHashSet|betweenCorners|forEachBlockIntersected|BlockPos\\\$6" "results/allocitem-$STAMP-V109-sum.txt" | head -8
echo "-- V110 (0.110.0) 收割面（应归零；MutableBlockPos 替代面应远低于阈）--"
grep -E "LongOpenHashSet|betweenCorners|forEachBlockIntersected|BlockPos\\\$6|MutableBlockPos" "results/allocitem-$STAMP-V110-sum.txt" | head -8
