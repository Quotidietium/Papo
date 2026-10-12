#!/usr/bin/env bash
# 批次185（R44）：终局二进制（0.107.0）驻留轴四重闭合复验——
# ① LongRunGrowth 12 bot × 40min（R12/R22 方法论：加载→卸载→重访重载周期，时间轴）；
# ② 同会话驻留配对 0.105.0 vs 0.107.0（R41/R42 两轮分配侧变更的驻留面终验，
#    R14 判例：比对必须同会话产生）。串行纪律：单脚本顺序执行。
cd "$(dirname "$0")"

CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

echo "================ longrun 0.107.0 start $(date +%T) ================"
/f/Java/21/bin/java -cp "$CP" papo.bot.LongRunGrowth \
    ../paper-server/build/libs/Papo-1.21.11-0.107.0.jar 12 2400000 240000 \
    > results/longrun-r44-107.log 2>&1
echo "longrun exit=$? $(date +%T)"
grep -E "LongRun\.(gate|sample#[0-9]+ heapUsedKb)" results/longrun-r44-107.log | tail -14

soak_leg() {
    local label="$1" jar="$2"
    echo "================ soak $label start $(date +%T) ================"
    /f/Java/21/bin/java -cp "$CP" papo.bot.MemSoakVerify "$jar" 12 150000 180000 120000 \
        > "results/r44-soak-$label.log" 2>&1
    echo "soak $label exit=$? $(date +%T)"
    grep -E "MemSoak\.(gate|heapUsedKb)" "results/r44-soak-$label.log"
    grep -E "MemSoak.top (net.minecraft.world.level.chunk.LevelChunk|net.minecraft.world.level.chunk.LevelChunkSection|net.minecraft.world.level.chunk.PalettedContainer|io.papermc.paper.chunk.system.io.RegionFileIOLoader|net.minecraft.server.level.ServerChunkCache)" "results/r44-soak-$label.log" | head -6
}

soak_leg A-105 ../paper-server/build/libs/Papo-1.21.11-0.105.0.jar
soak_leg B-107 ../paper-server/build/libs/Papo-1.21.11-0.107.0.jar

echo "r44 final verification done"
