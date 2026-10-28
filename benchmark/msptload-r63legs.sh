#!/usr/bin/env bash
# 批次204（R63）：tick 性能守护配对腿——0.112.0（批203 后）vs 0.113.0（后，0301 流体 SpreadContext 收割），
# 主跑 A→B + 倒序复跑 B→A + 钉死对（R15/R23/R38 判例；串行纪律：期间禁并发重腿）。
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
    # 批次186 判例：腿有效性验收必须含 bot 在线正证据（TRACE[bot 行为打印），
    # 且零类版本错误——共享 build/classes 曾被异版 javac 污染产出空服腿。
    local traces
    traces=$(grep -c "TRACE\[" "results/msptload-$STAMP-leg-$label.log")
    local vererr
    vererr=$(grep -c "UnsupportedClassVersionError\|NoClassDefFoundError" "results/msptload-$STAMP-leg-$label.log")
    echo "leg $label botEvidence traces=$traces (need >= $BOTS) classVersionErrs=$vererr (need 0)"
    if [ "$traces" -lt "$BOTS" ] || [ "$vererr" -ne 0 ]; then
        echo "leg $label 判废：bot 在线证据不足或类版本错误"
        return 99
    fi
}

ORDER="${1:-main}"
if [ "$ORDER" = "main" ]; then
    run_leg A-112 ../paper-server/build/libs/Papo-1.21.11-0.112.0.jar
    run_leg B-113 ../paper-server/build/libs/Papo-1.21.11-0.113.0.jar
elif [ "$ORDER" = "reverse" ]; then
    run_leg B-113r ../paper-server/build/libs/Papo-1.21.11-0.113.0.jar
    run_leg A-112r ../paper-server/build/libs/Papo-1.21.11-0.112.0.jar
else
    # pinned pair (R24 判例 #5): pin young gen to exclude G1 adaptive-sizing confounder;
    # JDK_JAVA_OPTIONS is inherited by the server via ProcessBuilder
    export JDK_JAVA_OPTIONS="-XX:NewSize=1G -XX:MaxNewSize=1G"
    run_leg A-112p ../paper-server/build/libs/Papo-1.21.11-0.112.0.jar
    run_leg B-113p ../paper-server/build/libs/Papo-1.21.11-0.113.0.jar
fi

echo "all legs done: results/msptload-$STAMP-leg-*.log"
