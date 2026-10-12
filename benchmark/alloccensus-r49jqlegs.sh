#!/usr/bin/env bash
# 批次190（R49）：玩家生命周期轴（join↔quit 循环风暴窗）分配普查（发现轮）——
# A-110base（持续在线基线）vs B-110storm（12 bot×4 轮 join(8s)/quit/gap(4s)）
# 同二进制 0.110.0 配对。delta=纯生命周期面（placeNewPlayer/PlayerList add/remove/
# 异步 quit 存档/playerdata 读/票据 churn/卸载-重载）。
# 门：TRACE≥bots + 零类版本错误 + exit 0 + B: joins/lefts≥3×bots·playerdataDat≥bots。
cd "$(dirname "$0")"

JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

run_leg() {
    local label="$1" jar="$2" storm="$3"
    local stamp out
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/allocjq-$stamp-$label"
    mkdir -p "$out"
    echo "================ leg $label start $(date +%T) ================"

    /f/Java/21/bin/java -cp "$CP" papo.bot.JoinQuitStormCensus "$jar" 12 4 8000 4000 "$storm" \
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
    local stormseen=0
    for i in $(seq 1 240); do
        if grep -q "stormPhaseStart" "$logf" 2>/dev/null; then stormseen=1; break; fi
        sleep 5
    done
    if [ "$stormseen" != "1" ]; then echo "leg $label: NO STORM LINE"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label storm line seen $(date +%T)"

    # 风暴第二秒起，15s 后开 60s 窗=风暴 18..78s（rounds 2-4 主体）
    sleep 15
    $JCMD "$pid" JFR.start name=jq settings=profile duration=60s \
        filename="$PWD/$out/jq.jfr" jdk.ObjectAllocationSample#threshold=1ms \
        > "$out/jfr-start.txt" 2>&1
    echo "leg $label jq jfr started $(date +%T)"

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"

    # ---- 门（批次186 纪律：bot 在线正证据+零类版本错误+生命周期正证据）----
    local traces=$(grep -c "TRACE\[" "$logf")
    local vererr=$(grep -c "has been compiled by a more recent version" "$logf")
    local joins=$(grep -o "joins=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    local lefts=$(grep -o "lefts=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    local dats=$(grep -o "playerdataDat=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    echo "leg $label GATE traces=$traces vererr=$vererr joins=${joins:--1} lefts=${lefts:--1} playerdataDat=${dats:--1}"
    if [ "$vererr" != "0" ] || [ "$rc" != "0" ] || [ "$traces" -lt 12 ]; then
        echo "leg $label: GATE FAIL (base)"; return 1
    fi
    if [ "$storm" = "1" ]; then
        if [ "${joins:--1}" -lt 36 ] || [ "${lefts:--1}" -lt 36 ] || [ "${dats:--1}" -lt 12 ]; then
            echo "leg $label: GATE FAIL (storm evidence)"; return 1
        fi
    else
        if [ "${joins:--1}" -lt 12 ] || [ "${lefts:--1}" -lt 12 ]; then
            echo "leg $label: GATE FAIL (baseline symmetry)"; return 1
        fi
    fi
    echo "$out" > "results/last-allocjq-leg-$label.txt"
}

run_leg A-110base ../paper-server/build/libs/Papo-1.21.11-0.110.0.jar 0
run_leg B-110storm ../paper-server/build/libs/Papo-1.21.11-0.110.0.jar 1

echo "jq census pair done"
