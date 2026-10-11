#!/usr/bin/env bash
# 批次182（R41）：驻留稳态 tick 窗分配普查配对腿——0.105.0（批182 前）vs 0.106.0（后）。
# 验证 flushStep 空 addAll 收割面：驻留 25-85s JFR 窗（对齐发现腿 alloccensus-r41dwell.sh）。
cd "$(dirname "$0")"

JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

run_leg() {
    local label="$1" jar="$2"
    local stamp out
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/allocdwell-$stamp-$label"
    mkdir -p "$out"
    echo "================ leg $label start $(date +%T) ================"

    /f/Java/21/bin/java -cp "$CP" papo.bot.MemSoakVerify "$jar" 12 150000 180000 120000 \
        > "results/alloccensus-$stamp-$label.log" 2>&1 &
    local harness=$!

    local pid=""
    for i in $(seq 1 60); do
        sleep 5
        pid=$($JCMD -l 2>/dev/null | grep "server.jar" | awk '{print $1}' | head -1)
        [ -n "$pid" ] && break
    done
    if [ -z "$pid" ]; then echo "leg $label: NO SERVER PID"; wait $harness; return 1; fi
    echo "leg $label server pid=$pid"

    local logf="results/alloccensus-$stamp-$label.log"
    local walkseen=0
    for i in $(seq 1 240); do
        if grep -q "walk phase started" "$logf" 2>/dev/null; then walkseen=1; break; fi
        sleep 5
    done
    if [ "$walkseen" != "1" ]; then echo "leg $label: NO WALK LINE"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label walk line seen $(date +%T)"

    sleep 175
    $JCMD "$pid" JFR.start name=dwell settings=profile duration=60s \
        filename="$PWD/$out/dwell.jfr" jdk.ObjectAllocationSample#threshold=1ms \
        > "$out/jfr-start.txt" 2>&1
    echo "leg $label dwell jfr started $(date +%T)"

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"
    grep -E "MemSoak.gate" "$logf"
    echo "$out" > "results/last-allocdwell-leg-$label.txt"
}

run_leg A-105dw ../paper-server/build/libs/Papo-1.21.11-0.105.0.jar
run_leg B-106dw ../paper-server/build/libs/Papo-1.21.11-0.106.0.jar

echo "dwell census pair done"
