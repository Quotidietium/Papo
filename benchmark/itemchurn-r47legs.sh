#!/usr/bin/env bash
# 批次188（R47）：物品实体旋涡轴分配普查发现腿——A=自然基线 vs B=summon 批式农场旋涡
# （每 bot：漏斗排6→双箱 + 石台 10×10；召唤 漏斗柱 150/列×6 + 石台 3/格=300 →
#  吸取/合并/tick/入箱全链持续负载；探针#4 实证。同二进制 0.109.0 配对差分；
#  JFR ObjectAllocationSample 60s 窗锚 ItemChurnCensus.loadWarmed=marker+12s 覆盖 drain 相）。
# 工具链纪律（批次186/187 判例）：编译/运行全部钉死 /f/Java/21；每腿验收 bot 在线正证据
# + 零类版本错误 + 旋涡流转正证据（漏斗吸取进行中 + chest 收物 + 物品实体在场——防空转假负载）。
# 用法: ./itemchurn-r47legs.sh [jar]
set -uo pipefail
cd "$(dirname "$0")"

JAR=${1:-../paper-server/build/libs/Papo-1.21.11-0.109.0.jar}
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
for LEG in A B; do
  FILL=0
  [ "$LEG" = "B" ] && FILL=1
  LOG="results/itemchurn-r47-$STAMP-$LEG.log"
  OUT="results/allocchurn-$STAMP-$LEG"
  mkdir -p "$OUT"
  echo "== 腿 $LEG (fill=$FILL) -> $LOG  $(date +%T) =="

  /f/Java/21/bin/java -cp "$CP" papo.bot.ItemChurnCensus "$JAR" "$BOTS" "$SPREAD" "$DWELL" "$FILL" > "$LOG" 2>&1 &
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

  $JCMD "$pid" JFR.start name=churn settings=profile duration=60s \
    filename="$PWD/$OUT/churn.jfr" jdk.ObjectAllocationSample#threshold=1ms \
    > "$OUT/jfr-start.txt" 2>&1

  wait $harness
  rc=$?
  echo "腿 $LEG harness exit=$rc"

  traces=$(grep -c "TRACE\[ICB" "$LOG")
  vererr=$(grep -c "UnsupportedClassVersionError\|NoClassDefFoundError" "$LOG")
  echo "腿 $LEG botEvidence traces=$traces (need >= $BOTS) classVersionErrs=$vererr (need 0)"
  grep -E "ItemChurnCensus\.(gate|fillsSent|fillMarkerReached|loadWarmed|heapUsedKb)" "$LOG"
  grep -E "ItemChurnCensus\.cls" "$LOG"
  # 旋涡流转正证据（B 腿）：漏斗吸取进行中（探针①=loadWarmed 前的 data 响应，非空 Items 值）
  # + chest 全链收物（探针②=loadWarmed 后含 cobblestone）+ 物品在场（say 成功回显；错误回显已被 harness 过滤）
  if [ "$LEG" = "B" ]; then
    hopper=$(awk '/loadWarmed/{exit} /ItemChurnCensus\.probe.*has the following block data/{n++} END{print n+0}' "$LOG")
    chest=$(awk '/loadWarmed/{seen=1} seen&&/ItemChurnCensus\.probe.*has the following block data.*cobblestone/{n++} END{print n+0}' "$LOG")
    present=$(grep -c "ItemChurnCensus.probe.*CHURN-ITEMS-PRESENT" "$LOG")
    echo "腿 $LEG churnEvidence hopperItems=$hopper (need >= 1) chestCobble=$chest (need >= 1) itemsPresent=$present (need >= 1)"
    if [ "$hopper" -lt 1 ] || [ "$chest" -lt 1 ] || [ "$present" -lt 1 ]; then
      echo "腿 $LEG 判废：旋涡未流转（summon 设计或吸取链失效）"
      grep -E "ItemChurnCensus.probe" "$LOG" | head -5
      exit 1
    fi
  fi
  if [ $rc -ne 0 ] || [ "$traces" -lt "$BOTS" ] || [ "$vererr" -ne 0 ]; then
    echo "腿 $LEG 判废"; tail -30 "$LOG"; exit $rc
  fi
  echo "$OUT" > "results/last-allocchurn-leg-$LEG.txt"
done

echo "== 双腿完成，聚合（B-A 差分判定另行）=="
python allocsum.py "$(cat results/last-allocchurn-leg-A.txt)/churn.jfr" > "results/allocchurn-$STAMP-A-sum.txt" || true
python allocsum.py "$(cat results/last-allocchurn-leg-B.txt)/churn.jfr" > "results/allocchurn-$STAMP-B-sum.txt" || true
head -45 "results/allocchurn-$STAMP-B-sum.txt"
