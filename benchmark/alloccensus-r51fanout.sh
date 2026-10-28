#!/usr/bin/env bash
# 批次192（R51）：高并发流送轴（60 bot 持续行走）分配普查 + 收割面高并发坍缩终验。
# A-107fan vs B-110fan 同会话配对：R27 只做过 60-bot join 相（无持续流送 JFR 窗），
# 5× 玩家规模的持续流送（广播扇出/NearbyPlayers/票据结构高压）从未成像；同时验证
# R45/R46/R47 收割面（0.108-0.110 落地）在 5× 规模下仍坍缩（R47 inside-block 面
# 的原生栖息地=移动实体×60）。端口独占门（R50 判例）。
cd "$(dirname "$0")"

JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

run_leg() {
    local label="$1" jar="$2"
    local stamp out
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/allocfan-$stamp-$label"
    mkdir -p "$out"
    echo "================ leg $label start $(date +%T) ================"

    if jcmd_out=$($JCMD -l 2>/dev/null | grep -iE "memsoak|DigStorm|JoinQuit|ItemChurn|server\.jar"); then
        echo "leg $label: PORT-HOLDER PRESENT, abort:"; echo "$jcmd_out"; return 1
    fi

    /f/Java/21/bin/java -cp "$CP" papo.bot.MemSoakVerify "$jar" 60 150000 180000 120000 \
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
    local bootseen=0
    for i in $(seq 1 240); do
        if grep -q "^boot:" "$logf" 2>/dev/null; then bootseen=1; break; fi
        sleep 5
    done
    [ "$bootseen" = "1" ] || { echo "leg $label: NO BOOT LINE"; kill $harness 2>/dev/null; return 1; }
    echo "leg $label boot line seen $(date +%T)"

    # 流送峰窗（60 bot 行走 t≈30-90s，与 r40arc 同锚）
    sleep 25
    $JCMD "$pid" JFR.start name=burst settings=profile duration=60s \
        filename="$PWD/$out/burst.jfr" jdk.ObjectAllocationSample#threshold=1ms \
        > "$out/jfr-start.txt" 2>&1
    echo "leg $label jfr started $(date +%T)"

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"

    local traces=$(grep -c "TRACE\[" "$logf")
    local vererr=$(grep -c "has been compiled by a more recent version" "$logf")
    echo "leg $label GATE traces=$traces vererr=$vererr"
    if [ "$vererr" != "0" ] || [ "$rc" != "0" ] || [ "$traces" -lt 60 ]; then
        echo "leg $label: GATE FAIL"; return 1
    fi
    echo "$out" > "results/last-allocfan-leg-$label.txt"
}

run_leg A-107fan ../paper-server/build/libs/Papo-1.21.11-0.107.0.jar
run_leg B-110fan ../paper-server/build/libs/Papo-1.21.11-0.110.0.jar

echo "fanout census pair done"
