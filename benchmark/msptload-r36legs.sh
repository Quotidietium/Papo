#!/usr/bin/env bash
# 批次176（R36）：tick 性能守护配对腿——0.100.0（批176 前）vs 0.101.0（后），
# 主跑 A→B + 倒序复跑 B→A（R15/R23 判例：换序平衡时间趋势；串行纪律：期间禁并发重腿）。
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

ORDER="${1:-main}"
if [ "$ORDER" = "main" ]; then
    run_leg A-100 ../paper-server/build/libs/Papo-1.21.11-0.100.0.jar
    run_leg B-101 ../paper-server/build/libs/Papo-1.21.11-0.101.0.jar
elif [ "$ORDER" = "reverse" ]; then
    run_leg B-101r ../paper-server/build/libs/Papo-1.21.11-0.101.0.jar
    run_leg A-100r ../paper-server/build/libs/Papo-1.21.11-0.100.0.jar
else
    # pinned pair (R24 判例 #5): pin young gen to exclude G1 adaptive-sizing confounder;
    # JDK_JAVA_OPTIONS is inherited by the server via ProcessBuilder
    export JDK_JAVA_OPTIONS="-XX:NewSize=1G -XX:MaxNewSize=1G"
    run_leg A-100p ../paper-server/build/libs/Papo-1.21.11-0.100.0.jar
    run_leg B-101p ../paper-server/build/libs/Papo-1.21.11-0.101.0.jar
fi

echo "all legs done: results/msptload-$STAMP-leg-*.log"
