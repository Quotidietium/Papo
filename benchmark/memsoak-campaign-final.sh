#!/usr/bin/env bash
# 批次154（R14）：内存主线收官直对——campaign 首尾二进制同会话配对（0.80.0 vs 0.88.0）。
# 主线 −397.0MB 至今由逐轮同会话配对差链拼接（跨会话绝对值含 ±2% 漂移判例）；
# 本腿以一次会话内的 A-080/B-088 直对给出干净的 campaign 级头号数字。B 腿带转储。
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

run_leg A-080 ../paper-server/build/libs/Papo-1.21.11-0.80.0.jar ""
run_leg B-088 ../paper-server/build/libs/Papo-1.21.11-0.88.0.jar "-Dmemsoak.dump=true"

echo "all legs done: results/memsoak-$STAMP-leg-*.log"
