#!/usr/bin/env bash
# 批次195（R54）：复合负载 MSPT 版本配对守护腿——0.107.0 vs 0.110.0（R52 复合形态：
# 挖掘+漏斗 drain+站立实体同窗叠加）。0298 收割的延迟维守护在 R47 当时只在旋涡
# 单域形态跑过；复合高压格（tick 路径=实体物理+走查+漏斗+光照+邻居通知叠加）未测。
# 三序制式（R15/R23/R38 判例）：主跑 A→B + 倒序 B→A + 钉 young 对（R24 判例#5：
# JDK_JAVA_OPTIONS 钉 NewSize 排除 G1 自适应缩放混杂）；同序复跑仅在出现三序同号
# 槽时加跑（R48/R51 判例：同序复跑是同号槽终审）。
# 门（批次186 纪律）：TRACE≥bots + 零类版本错误 + exit 0 + digsTotal≥400×bots +
# 探针 air×3+ctrl+chest/实体在场（MSPT 腿负载真实性正证据）+ mspt 样本 ≥5 解析成功。
# 采样槽：stream@120s + cmp@marker+20/40/60 + post@marker+100/130（win5s avg 为主判
# 指标，win1m 稳定性核对）。串行纪律：期间禁并发重腿；端口独占门（R50 判例）。
# 用法: ./msptcmp-r54legs.sh [main|reverse|pinned|rerun]
set -uo pipefail
cd "$(dirname "$0")"

JAR107=../paper-server/build/libs/Papo-1.21.11-0.107.0.jar
JAR110=../paper-server/build/libs/Papo-1.21.11-0.110.0.jar
BOTS=12
SPREAD=150000
DIGMS=160000
JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar"

echo "== 编译（钉死 J21 javac；R49 判例独立验证）=="
/f/Java/21/bin/javac -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/MsptCompositeProbe.java || exit 1
[ -f build/classes/papo/bot/MsptCompositeProbe.class ] || { echo "class missing"; exit 1; }

port_gate() {
    local linger=$($JCMD -l 2>/dev/null | grep -c "server.jar")
    if [ "$linger" != "0" ]; then
        echo "PORT-GATE FAIL: $linger 个 server.jar 残留——先清理再跑"
        exit 1
    fi
}

STAMP=$(date +%Y%m%d-%H%M%S)
run_leg() {
    local label="$1" jar="$2"
    port_gate
    echo "================ leg $label ($(date +%T)) ================"
    /f/Java/21/bin/java -cp "$CP" papo.bot.MsptCompositeProbe "$jar" "$BOTS" "$SPREAD" "$DIGMS" \
        > "results/msptcmp-$STAMP-leg-$label.log" 2>&1
    local rc=$?
    echo "leg $label exit=$rc"
    local logf="results/msptcmp-$STAMP-leg-$label.log"
    grep -E "MsptCmp\.(boot|fillMarkerReached|latch|mspt\[|gate|digsTotal)" "$logf"
    local traces=$(grep -c "TRACE\[" "$logf")
    local vererr=$(grep -c "UnsupportedClassVersionError\|NoClassDefFoundError" "$logf")
    local digs=$(grep -o "digsTotal=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    local air=$(grep -c "MCMP-DUG-AIR" "$logf")
    local ctrl=$(grep -c "MCMP-CTRL-STONE" "$logf")
    local chest=$(grep -c "has the following block data.*cobblestone" "$logf")
    local present=$(grep -c "MCMP-ITEMS-PRESENT" "$logf")
    local msptn=$(grep -c "MsptCmp.mspt\[" "$logf")
    echo "leg $label GATE traces=$traces vererr=$vererr digsTotal=${digs:--1} air=$air ctrl=$ctrl chestCobble=$chest present=$present msptSamples=$msptn"
    if [ "$traces" -lt "$BOTS" ] || [ "$vererr" -ne 0 ] || [ "${digs:--1}" -lt 4800 ] \
        || [ "$air" -lt 3 ] || [ "$ctrl" -lt 1 ] || [ "$chest" -lt 1 ] || [ "$present" -lt 1 ] \
        || [ "$msptn" -lt 5 ] || [ "$rc" -ne 0 ]; then
        echo "leg $label 判废"; tail -20 "$logf"; return 99
    fi
}

ORDER="${1:-main}"
if [ "$ORDER" = "main" ]; then
    run_leg A-107 "$JAR107"
    run_leg B-110 "$JAR110"
elif [ "$ORDER" = "reverse" ]; then
    run_leg B-110r "$JAR110"
    run_leg A-107r "$JAR107"
elif [ "$ORDER" = "pinned" ]; then
    export JDK_JAVA_OPTIONS="-XX:NewSize=1G -XX:MaxNewSize=1G"
    run_leg A-107p "$JAR107"
    run_leg B-110p "$JAR110"
else
    # 同序复跑（R48/R51 判例：三序同号槽终审）
    run_leg A-107rr "$JAR107"
    run_leg B-110rr "$JAR110"
fi
echo "all legs done: results/msptcmp-$STAMP-leg-*.log"
