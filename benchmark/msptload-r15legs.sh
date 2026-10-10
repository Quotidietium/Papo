#!/usr/bin/env bash
# 批次155（R15）：tick 性能守护配对腿——浸泡负载态 MSPT 采样，0.80.0 vs 0.88.0。
# 内存批（141-150）改动的热路径在流送期承压；守护红线=交互体验一致（延迟不变差）。
cd "$(dirname "$0")"

BOTS=12
SPREAD=150000
DWELL=180000
STAMP=$(date +%Y%m%d-%H%M%S)
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

run_leg() {
    local label="$1" jar="$2"
    echo "================ leg $label ($(date +%T)) ================"
    /f/Java/21/bin/java -cp "$CP" papo.bot.MsptLoadProbe "$jar" "$BOTS" "$SPREAD" "$DWELL" \
        > "results/msptload-$STAMP-leg-$label.log" 2>&1
    local rc=$?
    echo "leg $label exit=$rc"
    grep -E "MsptLoad\.(boot|mspt\[|gate)" "results/msptload-$STAMP-leg-$label.log"
}

run_leg A-080 ../paper-server/build/libs/Papo-1.21.11-0.80.0.jar
run_leg B-088 ../paper-server/build/libs/Papo-1.21.11-0.88.0.jar

echo "all legs done: results/msptload-$STAMP-leg-*.log"
