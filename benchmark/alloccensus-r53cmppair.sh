#!/usr/bin/env bash
# 批次194（R53）：复合负载收割面版本配对终验——0.107.0 vs 0.110.0 同复合负载
# （R52 CompositeCensus 形态：R48 挖掘立方+R47 农场 z+8 同窗叠加）双 cmp=1 腿。
# R52 已证同二进制下叠加线性+零新面；本轮补跨版本维：R41/R47 收割面（between
# Corners 迭代器簇/StepBasedCollector/走查族）在复合负载（3.6k 站立实体+drain+
# ~7.2k 挖/窗，0298 收割面的原始高压形态）下 0.107→0.110 坍缩零回流；其余已知
# 族带内摆动；零 0.110 独有新面。R51 高并发终验的复合负载对偶。
# JFR ObjectAllocationSample 60s 窗锚 CompositeCensus.loadWarmed=marker+12s。
# 工具链纪律（批次186/187 判例）：J21 钉死；端口独占门（R50 判例）。
# 门（双腿同复合正证据）：TRACE≥bots+零类版本错误+exit 0+DIGDONE≥bots+
# digsTotal≥400×bots+air×3+ctrl×2+漏斗 Items+chest cobblestone+实体在场×2。
# 用法: ./alloccensus-r53cmppair.sh
set -uo pipefail
cd "$(dirname "$0")"

JAR107=../paper-server/build/libs/Papo-1.21.11-0.107.0.jar
JAR110=../paper-server/build/libs/Papo-1.21.11-0.110.0.jar
BOTS=12
SPREAD=150000
DIGMS=160000
JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar"

echo "== 编译（钉死 J21 javac；R49 判例：编译独立验证）=="
/f/Java/21/bin/javac -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/CompositeCensus.java || exit 1
[ -f build/classes/papo/bot/CompositeCensus.class ] || { echo "class missing"; exit 1; }

port_gate() {
    local linger=$($JCMD -l 2>/dev/null | grep -c "server.jar")
    if [ "$linger" != "0" ]; then
        echo "PORT-GATE FAIL: $linger 个 server.jar 进程残留（R50 判例：端口互踩）——先清理再跑"
        $JCMD -l 2>/dev/null | grep "server.jar"
        exit 1
    fi
}

run_leg() {
    local label="$1" jar="$2"
    port_gate
    local stamp out
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/alloccmppair-$stamp-$label"
    mkdir -p "$out"
    echo "================ leg $label jar=$jar start $(date +%T) ================"

    /f/Java/21/bin/java -cp "$CP" papo.bot.CompositeCensus "$jar" "$BOTS" "$SPREAD" "$DIGMS" 1 \
        > "results/alloccensus-$stamp-$label.log" 2>&1 &
    local harness=$!

    local pid=""
    for i in $(seq 1 60); do
        sleep 5
        pid=$($JCMD -l 2>/dev/null | grep "server.jar" | awk '{print $1}' | head -1)
        [ -n "$pid" ] && break
    done
    if [ -z "$pid" ]; then echo "leg $label: NO SERVER PID"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label server pid=$pid"

    local logf="results/alloccensus-$stamp-$label.log"
    local anchored=0
    for i in $(seq 1 300); do
        if grep -q "CompositeCensus.loadWarmed" "$logf" 2>/dev/null; then anchored=1; break; fi
        sleep 5
    done
    if [ "$anchored" != "1" ]; then echo "leg $label: NO loadWarmed 锚点"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label loadWarmed 锚点到达 $(date +%T)，起 JFR 60s"

    $JCMD "$pid" JFR.start name=cmppair settings=profile duration=60s \
        filename="$PWD/$out/cmp.jfr" jdk.ObjectAllocationSample#threshold=1ms \
        > "$out/jfr-start.txt" 2>&1

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"

    # ---- 门（双腿同复合正证据；工作量等价锚=digsTotal 两腿同量级）----
    local traces=$(grep -c "TRACE\[" "$logf")
    local vererr=$(grep -c "has been compiled by a more recent version" "$logf")
    local digdone=$(grep -c "DIGDONE\[" "$logf")
    local digs=$(grep -o "digsTotal=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    local air=$(grep -c "CMP-DUG-AIR" "$logf")
    local ctrl=$(grep -c "CMP-CTRL-STONE" "$logf")
    local unknown=$(grep -c "Unknown or incomplete" "$logf")
    local present=$(grep -c "CMP-ITEMS-PRESENT" "$logf")
    local hopper=$(awk '/loadWarmed/{exit} /CompositeCensus\.probe.*has the following block data/{n++} END{print n+0}' "$logf")
    local chest=$(awk '/loadWarmed/{seen=1} seen&&/CompositeCensus\.probe.*has the following block data.*cobblestone/{n++} END{print n+0}' "$logf")
    echo "leg $label GATE traces=$traces vererr=$vererr digdone=$digdone digsTotal=${digs:--1} airProbes=$air ctrlProbes=$ctrl hopperItems=$hopper chestCobble=$chest itemsPresent=$present unknown=$unknown"
    if [ "$vererr" != "0" ] || [ "$unknown" != "0" ] || [ "$rc" != "0" ] || [ "$traces" -lt "$BOTS" ] \
        || [ "$digdone" -lt "$BOTS" ] || [ "${digs:--1}" -lt 4800 ] || [ "$air" -lt 3 ] || [ "$ctrl" -lt 2 ] \
        || [ "$hopper" -lt 1 ] || [ "$chest" -lt 1 ] || [ "$present" -lt 2 ]; then
        echo "leg $label: GATE FAIL"; tail -30 "$logf"; return 1
    fi
    echo "$out" > "results/last-alloccmppair-leg-$label.txt"
}

run_leg A-107cmp "$JAR107"
[ $? -eq 0 ] || exit 1
run_leg B-110cmp "$JAR110"
[ $? -eq 0 ] || exit 1

echo "== 双腿完成，聚合（版本配对差分判定另行）=="
python allocsum.py "$(cat results/last-alloccmppair-leg-A-107cmp.txt)/cmp.jfr" > "results/alloccmppair-A-sum.txt" || true
python allocsum.py "$(cat results/last-alloccmppair-leg-B-110cmp.txt)/cmp.jfr" > "results/alloccmppair-B-sum.txt" || true
head -45 "results/alloccmppair-A-sum.txt"
echo "..."
head -45 "results/alloccmppair-B-sum.txt"
