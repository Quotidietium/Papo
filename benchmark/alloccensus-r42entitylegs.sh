#!/usr/bin/env bash
# 批次183（R42）：实体密集+保存窗分配普查配对腿——0.106.0（批183 前）vs 0.107.0（后）。
# 验证 PAP0_LONG_ARRAY.encode 流往返收割面：实体驻留 150-210s JFR 窗（与发现腿
# alloccensus-r42entity.sh 逐字同参数，窗内含 5 分钟自动保存=收割面的真实触发相位）。
cd "$(dirname "$0")"

JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

run_leg() {
    local label="$1" jar="$2"
    local stamp out
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/allocentity-$stamp-$label"
    mkdir -p "$out"
    echo "================ leg $label start $(date +%T) ================"

    /f/Java/21/bin/java -cp "$CP" papo.bot.EntitySoakCensus "$jar" 12 150000 300000 \
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
    if [ "$bootseen" != "1" ]; then echo "leg $label: NO BOOT LINE"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label boot line seen $(date +%T)"

    sleep 310
    $JCMD "$pid" JFR.start name=entity settings=profile duration=60s \
        filename="$PWD/$out/entity.jfr" jdk.ObjectAllocationSample#threshold=1ms \
        > "$out/jfr-start.txt" 2>&1
    echo "leg $label entity jfr started $(date +%T)"

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"
    grep -E "EntitySoak\.(gate|entityTotal)" "$logf"
    echo "$out" > "results/last-allocentity-leg-$label.txt"
}

run_leg A-106en ../paper-server/build/libs/Papo-1.21.11-0.106.0.jar
run_leg B-107en ../paper-server/build/libs/Papo-1.21.11-0.107.0.jar

echo "entity census pair done"
