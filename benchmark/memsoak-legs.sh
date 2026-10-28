#!/usr/bin/env bash
# 批次141（R5）：区块包缓存驻留内存三腿对比
#   A: 0.80.0 基线（缓存永久驻留）
#   B: 0.81.0 默认 TTL=600（30s 驱逐）
#   C: 0.81.0 TTL=0（驱逐禁用 = 同二进制对照腿，复现 0.80.0 驻留行为）
# 每腿：12 bot × 150s 扩散行走 + 180s 原地驻留，驻留第 120s jcmd 双 Full GC 后测堆。
set -uo pipefail
cd "$(dirname "$0")"

BOTS=12
SPREAD=150000
DWELL=180000
MEASURE_AT=120000
STAMP=$(date +%Y%m%d-%H%M%S)

run_leg() {
    local label="$1" jar="$2" flags="$3"
    echo "================ leg $label ($(date +%T)) ================"
    /f/Java/21/bin/java -cp "build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar" papo.bot.MemSoakVerify "$jar" "$BOTS" "$SPREAD" "$DWELL" "$MEASURE_AT" "$flags" \
        > "results/memsoak-$STAMP-leg-$label.log" 2>&1
    local rc=$?
    echo "leg $label exit=$rc"
    grep -E "MemSoak.heapUsedKb|MemSoak.cls |MemSoak.gate" "results/memsoak-$STAMP-leg-$label.log" | head -12
}

run_leg A ../paper-server/build/libs/Papo-1.21.11-0.80.0.jar ""
run_leg B ../paper-server/build/libs/Papo-1.21.11-0.81.0.jar ""
run_leg C ../paper-server/build/libs/Papo-1.21.11-0.81.0.jar "-Dpapo.chunkPacketCacheTtlTicks=0"

echo "all legs done: results/memsoak-$STAMP-leg-*.log"
