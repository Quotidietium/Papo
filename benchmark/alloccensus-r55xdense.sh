#!/usr/bin/env bash
# 批次196（R55）：极高实体密度复合轴——历史普查峰值 14.4k 召唤/3.7k 站立存活；
# 本轮 gridN=20（1,200 站立/bot=14.4k 站立+900 drain ≈15.3k 活体，站立 4×）。
# 一腿两得：①极端密度首成像（Q-110x vs A-110nat 全谱差分归因清单+密度非线性
# 面探测：合并扫描/实体节/tracker/NearbyPlayers）；②0298 收割面极端密度坍缩
# 终验（P-107x vs Q-110x 版本配对，第六维终验格）。
# 注：批次196 起 gridN>10 站立格移 z+10 起（漏斗排之后）+全格平台（R52 形的
# 带外落体相去除）——几何变更在报告 §2 声明。
# 门：标准复合门（traces/digs/air×3/ctrl×2/hopper/chest/present）+ 极端腿
# ItemEntity 活体普查 ≥12,000。端口独占门+J21 纪律。
# 用法: ./alloccensus-r55xdense.sh
set -uo pipefail
cd "$(dirname "$0")"

JAR107=../paper-server/build/libs/Papo-1.21.11-0.107.0.jar
JAR110=../paper-server/build/libs/Papo-1.21.11-0.110.0.jar
BOTS=12
SPREAD=150000
DIGMS=160000
GRID=20
JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar"

echo "== 编译（钉死 J21 javac）=="
/f/Java/21/bin/javac -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/CompositeCensus.java || exit 1

port_gate() {
    local linger=$($JCMD -l 2>/dev/null | grep -c "server.jar")
    if [ "$linger" != "0" ]; then
        echo "PORT-GATE FAIL: $linger 个 server.jar 残留"; exit 1
    fi
}

run_leg() {
    local label="$1" jar="$2" cmp="$3" grid="$4"
    port_gate
    local stamp out
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/allocxd-$stamp-$label"
    mkdir -p "$out"
    echo "================ leg $label jar=$(basename $jar) cmp=$cmp grid=$grid $(date +%T) ================"

    /f/Java/21/bin/java -cp "$CP" papo.bot.CompositeCensus "$jar" "$BOTS" "$SPREAD" "$DIGMS" "$cmp" "$grid" 3 \
        > "results/alloccensus-$stamp-$label.log" 2>&1 &
    local harness=$!
    local pid=""
    for i in $(seq 1 60); do
        sleep 5
        pid=$($JCMD -l 2>/dev/null | grep "server.jar" | awk '{print $1}' | head -1)
        [ -n "$pid" ] && break
    done
    if [ -z "$pid" ]; then echo "leg $label: NO SERVER PID"; kill $harness 2>/dev/null; return 1; fi

    local logf="results/alloccensus-$stamp-$label.log"
    local anchored=0
    for i in $(seq 1 300); do
        if grep -q "CompositeCensus.loadWarmed" "$logf" 2>/dev/null; then anchored=1; break; fi
        sleep 5
    done
    if [ "$anchored" != "1" ]; then echo "leg $label: NO loadWarmed"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label loadWarmed $(date +%T)，起 JFR 60s"

    $JCMD "$pid" JFR.start name=xd settings=profile duration=60s \
        filename="$PWD/$out/xd.jfr" jdk.ObjectAllocationSample#threshold=1ms \
        > "$out/jfr-start.txt" 2>&1

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"

    local traces=$(grep -c "TRACE\[" "$logf")
    local vererr=$(grep -c "has been compiled by a more recent version" "$logf")
    local digdone=$(grep -c "DIGDONE\[" "$logf")
    local digs=$(grep -o "digsTotal=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    local air=$(grep -c "CMP-DUG-AIR" "$logf")
    local ctrl=$(grep -c "CMP-CTRL-STONE" "$logf")
    local unknown=$(grep -c "Unknown or incomplete" "$logf")
    local present=$(grep -c "CMP-ITEMS-PRESENT" "$logf")
    local items=$(grep -o "cls net.minecraft.world.entity.item.ItemEntity instances=[0-9]*" "$logf" | head -1 | grep -o "[0-9]*$")
    echo "leg $label GATE traces=$traces vererr=$vererr digdone=$digdone digsTotal=${digs:--1} air=$air ctrl=$ctrl present=$present itemCensus=${items:--1} unknown=$unknown"
    if [ "$vererr" != "0" ] || [ "$unknown" != "0" ] || [ "$rc" != "0" ] || [ "$traces" -lt "$BOTS" ]; then
        echo "leg $label: GATE FAIL (base)"; tail -25 "$logf"; return 1
    fi
    if [ "$cmp" = "1" ]; then
        local hopper=$(awk '/loadWarmed/{exit} /CompositeCensus\.probe.*has the following block data/{n++} END{print n+0}' "$logf")
        local chest=$(awk '/loadWarmed/{seen=1} seen&&/CompositeCensus\.probe.*has the following block data.*cobblestone/{n++} END{print n+0}' "$logf")
        echo "leg $label churnEvidence hopperItems=$hopper chestCobble=$chest"
        if [ "$digdone" -lt "$BOTS" ] || [ "${digs:--1}" -lt 4800 ] || [ "$air" -lt 3 ] || [ "$ctrl" -lt 2 ] \
            || [ "$hopper" -lt 1 ] || [ "$chest" -lt 1 ] || [ "$present" -lt 2 ] || [ "${items:--1}" -lt 12000 ]; then
            echo "leg $label: GATE FAIL (extreme evidence; itemCensus=${items:--1})"; return 1
        fi
    else
        if [ "${digs:--1}" != "0" ]; then
            echo "leg $label: GATE FAIL (natural digs != 0)"; return 1
        fi
    fi
    echo "$out" > "results/last-allocxd-leg-$label.txt"
}

run_leg A-110nat "$JAR110" 0 10
[ $? -eq 0 ] || exit 1
run_leg P-107x "$JAR107" 1 "$GRID"
[ $? -eq 0 ] || exit 1
run_leg Q-110x "$JAR110" 1 "$GRID"
[ $? -eq 0 ] || exit 1

echo "== 三腿完成，聚合 =="
python allocsum.py "$(cat results/last-allocxd-leg-A-110nat.txt)/xd.jfr" > results/allocxd-A-sum.txt || true
python allocsum.py "$(cat results/last-allocxd-leg-P-107x.txt)/xd.jfr" > results/allocxd-P-sum.txt || true
python allocsum.py "$(cat results/last-allocxd-leg-Q-110x.txt)/xd.jfr" > results/allocxd-Q-sum.txt || true
for s in A P Q; do echo "-- $s --"; head -4 "results/allocxd-$s-sum.txt"; done
