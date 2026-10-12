#!/usr/bin/env bash
# 批次201（R60 爆炸轴）：TNT summon 风暴——批量方块破坏+爆抗查询+destroyBlock
# 事件机器+ItemEntity 掉落风暴+SectionBlocksUpdate 广播复合链从未在 R24+ 分配
# 普查口径成像（批 57=稳定性防护修复、批 73=R2/R3 时代网络包共享否决、R48=单格
# PLAYER_ACTION 破坏非批量）=第十七域真新格。
#
# 形态：boom=1 对照（石板介质 24×24×3 y[148,150]/bot+零爆炸）vs boom=2 风暴
# （+24 波×每 bot 2 TNT=576 爆，互质步长确定性扫描位）。bot 全程 y=200 驻留
# （观察者+票据，远离爆心）——B−A 差分=纯爆炸链。dig=0（cmp 关）。
set -u
cd "$(dirname "$0")"
JAVAC=/f/Java/21/bin/javac
JCMD=/f/Java/21/bin/jcmd.exe
JAR110=/f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.110.0.jar
BOTS=12
SPREAD=150000
DIGMS=280000   # 全程驻留段（dig=false）
JFRDUR=300s

resid=$("$JCMD" -l 2>/dev/null | grep -c "server\.jar")
if [ "$resid" != "0" ]; then echo "PORT-GATE: residual server.jar procs=$resid"; exit 1; fi

echo "== compile harness (foreground, R49 判例) =="
"$JAVAC" -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/CompositeCensus.java || exit 1
echo "COMPILE-OK"

run_leg() { # label boom
    local label="$1" boom="$2"
    local stamp out logf
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/boom-$stamp-$label"
    mkdir -p "$out"
    logf="$out/leg.log"
    echo "== leg $label start $(date +%T) boom=$boom jfr=$JFRDUR =="

    /f/Java/21/bin/java -cp "build/classes" papo.bot.CompositeCensus "$JAR110" "$BOTS" "$SPREAD" "$DIGMS" 0 10 3 0 0 0 "$boom" \
        > "$logf" 2>&1 &
    local harness=$!
    local pid="" anchored=0 i
    for i in $(seq 1 400); do
        sleep 3
        if [ -z "$pid" ]; then
            pid=$("$JCMD" -l 2>/dev/null | grep "server\.jar" | awk '{print $1}' | head -1)
        fi
        if grep -q "CompositeCensus.loadWarmed" "$logf" 2>/dev/null; then anchored=1; break; fi
        if ! kill -0 $harness 2>/dev/null; then break; fi
    done
    if [ "$anchored" != "1" ]; then echo "leg $label: NO loadWarmed"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label loadWarmed $(date +%T)，起 JFR $JFRDUR 双事件集"

    "$JCMD" "$pid" JFR.start name=boom settings=profile duration=$JFRDUR \
        filename="$PWD/$out/boom.jfr" \
        jdk.ObjectAllocationSample#threshold=1ms \
        jdk.OldObjectSample#enabled=true \
        > "$out/jfr-start.txt" 2>&1

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"
    if [ "$rc" != "0" ]; then return 1; fi

    # ---- 门 ----
    local ok=1
    local traces=$(grep -c "CompositeCensus.botFinalPos" "$logf")
    local heap=$(grep -o "CompositeCensus.heapUsedKb=[0-9-]*" "$logf" | head -1 | cut -d= -f2)
    local gate=$(grep -o "CompositeCensus.gate logErrors=0 exited=true" "$logf" | head -1)
    local slab=$(grep -c "CompositeCensus.probe.*BOOM-SLAB-INTACT" "$logf")
    local crater=$(grep -c "CompositeCensus.probe.*BOOM-CRATER" "$logf")
    local items=$(grep -c "CompositeCensus.probe.*BOOM-ITEMS" "$logf")
    local cleared=$(grep -c "CompositeCensus.probe.*BOOM-TNT-CLEARED" "$logf")
    local summons=$(grep -o "CompositeCensus.boomSummonEcho=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    echo "leg $label GATE: traces=$traces slab=$slab crater=$crater items=$items cleared=$cleared summons=${summons:-none} heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    [ -n "$gate" ] || ok=0
    if [ "$boom" = "1" ]; then
        [ "${slab:-0}" -ge 1 ] || ok=0
        [ "${summons:-0}" = "0" ] || ok=0
    fi
    if [ "$boom" = "2" ]; then
        # 576 TNT summon 服务器日志回显（R59 判例：门阈值按真值校准——summon
        # 回显行数=实际执行数，500=576 的 −13% 余量）
        [ "${summons:-0}" -ge 500 ] || ok=0
        [ "${crater:-0}" -ge 1 ] || ok=0
        [ "${items:-0}" -ge 1 ] || ok=0
        [ "${cleared:-0}" -ge 1 ] || ok=0
    fi
    echo "leg $label gateOk=$ok"
    echo "$out" > "last-boom-leg-$label.txt"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-AB}"
case "$LEGS" in
    *A*) run_leg A-110slab 1 || { echo "LEG-A FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *B*) run_leg B-110storm 2 || { echo "LEG-B FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
for f in results/boom-*-A-110slab/boom.jfr results/boom-*-B-110storm/boom.jfr; do
    echo "== allocsum $f =="
    python allocsum.py "$f" 2>/dev/null | head -42
done
