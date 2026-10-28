#!/usr/bin/env bash
# R8 批次148：0.85.0 vs 0.86.0（SWMRNibbleArray 紧凑 uniform 表示）同场景配对对比；B 腿带堆转储供普查复测
# 每腿：12 bot × 150s 扩散行走 + 180s 原地驻留，驻留第 120s jcmd 双 Full GC 后测堆。
# 配对有效性门：LevelChunk 实例数 ±1% 以内（批146 判例：区块数失配的腿不可用于差分）。
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
    # -Dmemsoak.dump 由 MemSoakVerify 本体（Boolean.getBoolean）读取，须置于本 harness JVM 命令行
    /f/Java/21/bin/java $jflags -cp "$CP" papo.bot.MemSoakVerify "$jar" "$BOTS" "$SPREAD" "$DWELL" "$MEASURE_AT" \
        > "results/memsoak-$STAMP-leg-$label.log" 2>&1
    local rc=$?
    echo "leg $label exit=$rc"
    grep -E "MemSoak.heapUsedKb|MemSoak.cls|MemSoak.gate" "results/memsoak-$STAMP-leg-$label.log" | head -12
}

run_leg A-085 ../paper-server/build/libs/Papo-1.21.11-0.85.0.jar ""
run_leg B-086 ../paper-server/build/libs/Papo-1.21.11-0.86.0.jar "-Dmemsoak.dump=true"

echo "all legs done: results/memsoak-$STAMP-leg-*.log"
