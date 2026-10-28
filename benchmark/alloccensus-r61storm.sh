#!/usr/bin/env bash
# 批次202（R61 天气/雷电轴）：weather thunder+LightningBolt summon 风暴——
# 雷击链（实体 spawn+点火尝试 BlockIgnite 0119 收割面回流点+音效广播+天气
# tick ServerLevel.tickWeather）从未被任何时代的任何口径碰过（全史 Lightning/
# Thunder 零命中）=第十八域真新格（R60 探针腿先行判例：先跑风暴腿验证形态）。
#
# 形态：boom=3（石板介质+雷暴天气+24 波×2 雷/bot=576 雷击）vs boom=1 对照
# （石板+默认 clear 零雷击）。bot y=200 驻留观察者。B−A=纯雷电链。
set -u
cd "$(dirname "$0")"
JAVAC=/f/Java/21/bin/javac
JCMD=/f/Java/21/bin/jcmd.exe
JAR110=/f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.110.0.jar
BOTS=12
SPREAD=150000
DIGMS=280000
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
    out="results/storm-$stamp-$label"
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

    "$JCMD" "$pid" JFR.start name=storm settings=profile duration=$JFRDUR \
        filename="$PWD/$out/storm.jfr" \
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
    local fire=$(grep -c "CompositeCensus.probe.*LTNG-FIRE" "$logf")
    local summons=$(grep -o "CompositeCensus.stormSummonEcho=[0-9]*" "$logf" | head -1 | cut -d= -f2 | cut -d' ' -f1)
    # 批次202 判例：weather query 回显本地化（zh_cn）不可 grep——天气正证据由
    # JFR Thunder/Weather 帧承担（聚合阶段符号扫描），wq 门退役
    local wq=1
    echo "leg $label GATE: traces=$traces slab=$slab fire=$fire summons=${summons:-none} heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    [ -n "$gate" ] || ok=0
    if [ "$boom" = "1" ]; then
        [ "${slab:-0}" -ge 1 ] || ok=0
    fi
    if [ "$boom" = "3" ]; then
        # 576 雷击回显+火统计探针 ≥1；JFR LightningBolt/FireBlock 帧为差分铁证
        [ "${summons:-0}" -ge 500 ] || ok=0
        [ "${fire:-0}" -ge 1 ] || ok=0
    fi
    echo "leg $label gateOk=$ok"
    echo "$out" > "last-storm-leg-$label.txt"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-AB}"
case "$LEGS" in
    *A*) run_leg A-110clear 1 || { echo "LEG-A FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *B*) run_leg B-110thunder 3 || { echo "LEG-B FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
for f in results/storm-*-A-110clear/storm.jfr results/storm-*-B-110thunder/storm.jfr; do
    echo "== allocsum $f =="
    python allocsum.py "$f" 2>/dev/null | head -42
done
