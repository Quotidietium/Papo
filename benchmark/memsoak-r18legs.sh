#!/usr/bin/env bash
# 批次158（R18）：SWMR 冻结壳规范共享——同会话配对浸泡 A-090 vs B-091。
# B 腿带堆转储供 census 归因（预期 SWMRNibbleArray 539k → ~30-60k）。
# 配对有效性门：LevelChunk ±1%（批146 判例）。
cd "$(dirname "$0")"

BOTS=12
SPREAD=150000
DWELL=180000
MEASURE_AT=120000
STAMP=$(date +%Y%m%d-%H%M%S)
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

run_leg() {
    local label="$1" jar="$2" jflags="$3"
    echo "================ leg $label ($(date +%T)) ================"
    /f/Java/21/bin/java $jflags -cp "$CP" papo.bot.MemSoakVerify "$jar" "$BOTS" "$SPREAD" "$DWELL" "$MEASURE_AT" \
        > "results/memsoak-$STAMP-leg-$label.log" 2>&1
    local rc=$?
    echo "leg $label exit=$rc"
    grep -E "MemSoak.heapUsedKb|MemSoak.cls|MemSoak.gate" "results/memsoak-$STAMP-leg-$label.log" | head -12
}

run_leg A-090 ../paper-server/build/libs/Papo-1.21.11-0.90.0.jar ""
run_leg B-091 ../paper-server/build/libs/Papo-1.21.11-0.91.0.jar "-Dmemsoak.dump=true"

echo "all legs done: results/memsoak-$STAMP-leg-*.log"
