#!/usr/bin/env bash
# 批次189（R48）：方块交互/挖掘风暴窗分配普查（发现轮）——A-110nat（自然驻留）vs
# B-110dig（挖掘风暴）同二进制 0.110.0 配对。挖掘负载=creative 即时挖掘（零工具/零
# 掉落物/确定性），每 bot y=200 石立方内保守可达球 ~650 格×12 bot（~7,800 挖/窗）；
# A 腿同 fill 同节拍驻留零挖掘包=delta 纯挖掘面。
# 门：TRACE≥bots + 零类版本错误 + exit 0 + B: DIGDONE≥bots·digsTotal≥400×bots·
# 探针 air×3+stone；A: 探针 stone·digsTotal=0；服务器日志零 "Unknown or incomplete"。
cd "$(dirname "$0")"

JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

run_leg() {
    local label="$1" jar="$2" dig="$3"
    local stamp out
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/allocdig-$stamp-$label"
    mkdir -p "$out"
    echo "================ leg $label start $(date +%T) ================"

    /f/Java/21/bin/java -cp "$CP" papo.bot.DigStormCensus "$jar" 12 150000 160000 "$dig" \
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
    local walkseen=0
    for i in $(seq 1 240); do
        if grep -q "walkPhaseStart" "$logf" 2>/dev/null; then walkseen=1; break; fi
        sleep 5
    done
    if [ "$walkseen" != "1" ]; then echo "leg $label: NO WALK LINE"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label walk line seen $(date +%T)"

    # marker≈boot+175s，latch=marker+20；JFR boot+190 起 60s=marker+15..75 覆盖挖掘主体
    sleep 188
    $JCMD "$pid" JFR.start name=dig settings=profile duration=60s \
        filename="$PWD/$out/dig.jfr" jdk.ObjectAllocationSample#threshold=1ms \
        > "$out/jfr-start.txt" 2>&1
    echo "leg $label dig jfr started $(date +%T)"

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"

    # ---- 门（批次186 纪律：bot 在线正证据+零类版本错误+挖掘正证据）----
    local traces=$(grep -c "TRACE\[" "$logf")
    local vererr=$(grep -c "has been compiled by a more recent version" "$logf")
    local digdone=$(grep -c "DIGDONE\[" "$logf")
    local digs=$(grep -o "digsTotal=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    local air=$(grep -c "DIG-DUG-AIR" "$logf")
    local ctrl=$(grep -c "DIG-CTRL-STONE" "$logf")
    local unknown=$(grep -c "Unknown or incomplete" "$logf")
    echo "leg $label GATE traces=$traces vererr=$vererr digdone=$digdone digsTotal=${digs:--1} airProbes=$air ctrlProbes=$ctrl unknown=$unknown"
    if [ "$vererr" != "0" ] || [ "$unknown" != "0" ] || [ "$rc" != "0" ] || [ "$traces" -lt 12 ]; then
        echo "leg $label: GATE FAIL (base)"; return 1
    fi
    if [ "$dig" = "1" ]; then
        if [ "$digdone" -lt 12 ] || [ "${digs:--1}" -lt 4800 ] || [ "$air" -lt 3 ] || [ "$ctrl" -lt 2 ]; then
            echo "leg $label: GATE FAIL (dig evidence)"; return 1
        fi
    else
        if [ "${digs:--1}" != "0" ] || [ "$ctrl" -lt 2 ]; then
            echo "leg $label: GATE FAIL (natural symmetry)"; return 1
        fi
    fi
    echo "$out" > "results/last-allocdig-leg-$label.txt"
}

run_leg A-110nat ../paper-server/build/libs/Papo-1.21.11-0.110.0.jar 0
run_leg B-110dig ../paper-server/build/libs/Papo-1.21.11-0.110.0.jar 1

echo "dig census pair done"
