#!/usr/bin/env bash
# 批次180（R39）：分配源普查配对腿——0.104.0（批180 前）vs 0.105.0（后）。
# 与 alloccensus-r32.sh 同协议：12 bot 流送窗（行走 t≈30-90s）JFR
# jdk.ObjectAllocationSample#threshold=1ms duration=60s；串行纪律：期间禁并发重腿。
# 归因：python allocsum.py（R24 计数口径判例：站点以计数+阈值≥20 为准，权重只作区间）。
cd "$(dirname "$0")"

JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

run_leg() {
    local label="$1" jar="$2"
    local stamp out
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/alloc-$stamp-$label"
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

    # 流送峰窗（bot 行走 t≈30-90s）：JFR 分配采样
    sleep 25
    $JCMD "$pid" JFR.start name=burst settings=profile duration=60s \
        filename="$PWD/$out/burst.jfr" jdk.ObjectAllocationSample#threshold=1ms \
        > "$out/jfr-start.txt" 2>&1
    echo "leg $label jfr started $(date +%T)"

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"
    grep -E "MemSoak.gate" "results/alloccensus-$stamp-$label.log"
    echo "$out" > "results/last-alloc-leg-$label.txt"
}

ORDER="${1:-main}"
if [ "$ORDER" = "reverse" ]; then
    run_leg B-105 ../paper-server/build/libs/Papo-1.21.11-0.105.0.jar
    run_leg A-104 ../paper-server/build/libs/Papo-1.21.11-0.104.0.jar
else
    run_leg A-104 ../paper-server/build/libs/Papo-1.21.11-0.104.0.jar
    run_leg B-105 ../paper-server/build/libs/Papo-1.21.11-0.105.0.jar
fi

echo "all census legs done"
