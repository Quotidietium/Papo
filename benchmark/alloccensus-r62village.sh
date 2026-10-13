#!/usr/bin/env bash
# 批次203（R62 村民调度/POI 轴）：librarian 村庄调度风暴——brain 每 20gt 完整调度
# （refreshActivities+acquirePOI/findClosestHomework POI 查询）+工作行为走位从未
# 被任何时代的任何口径碰过（全史 Villager/PoiManager/acquirePOI 零命中；R25 只有
# 自然 mob 的 Brain 驻留画像+寻路 Node 收割）=第十九域真新格（R60 探针腿先行
# 判例：先跑风暴腿验证形态，数据留用再补对照腿）。
#
# 形态：vill=2（石板+围墙+lectern×12 POI+librarian×12/bot=144 村民调度风暴）vs
# vill=1 对照（同设施+POI 注册在场、零村民——方块更新/POI 注册面两腿相消）。
# bot y=200 驻留观察者。B−A=纯村民调度链。
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

run_leg() { # label vill
    local label="$1" vill="$2"
    local stamp out logf
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/village-$stamp-$label"
    mkdir -p "$out"
    logf="$out/leg.log"
    echo "== leg $label start $(date +%T) vill=$vill jfr=$JFRDUR =="
    echo "$out" > "last-village-leg-$label.txt"

    /f/Java/21/bin/java -cp "build/classes" papo.bot.CompositeCensus "$JAR110" "$BOTS" "$SPREAD" "$DIGMS" 0 10 3 0 0 0 0 "$vill" \
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

    "$JCMD" "$pid" JFR.start name=village settings=profile duration=$JFRDUR \
        filename="$PWD/$out/village.jfr" \
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
    local lectern=$(grep -c "CompositeCensus.probe.*VLGR-LECTERN" "$logf")
    local present=$(grep -c "CompositeCensus.probe.*VLGR-PRESENT" "$logf")
    local absent=$(grep -c "CompositeCensus.probe.*VLGR-ABSENT" "$logf")
    local vecho=$(grep -o "CompositeCensus.villagerSummonEcho=[0-9]*" "$logf" | head -1 | cut -d= -f2 | cut -d' ' -f1)
    echo "leg $label GATE: traces=$traces lectern=$lectern present=$present absent=$absent vecho=${vecho:-none} heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    [ -n "$gate" ] || ok=0
    [ "${lectern:-0}" -ge 1 ] || ok=0
    if [ "$vill" = "1" ]; then
        # 对照：设施在场+零村民（unless 反向探针可门化，R60 判例）。
        # vecho：vill=1 无 summon 分支=harness 不打印该行（none）——none 即 0
        # 同过（门设计一致性判例：门与 harness 输出约定腿前对齐）
        [ "${absent:-0}" -ge 1 ] || ok=0
        [ "${vecho:-0}" = "0" ] || ok=0
    fi
    if [ "$vill" = "2" ]; then
        # 144 村民回显（"Summoned new Villager" 恒英文）+在场探针；
        # 调度正证据复合门：JFR Villager/Brain/Poi 特征帧差分阶段承担
        [ "${vecho:-0}" -ge 140 ] || ok=0
        [ "${present:-0}" -ge 1 ] || ok=0
    fi
    echo "leg $label gateOk=$ok"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-B}"
case "$LEGS" in
    *B*) run_leg B-110village 2 || { echo "LEG-B FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *A*) run_leg A-110ctrl 1 || { echo "LEG-A FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
for f in $(ls -d results/village-*-A-110ctrl 2>/dev/null | tail -1)/village.jfr $(ls -d results/village-*-B-110village 2>/dev/null | tail -1)/village.jfr; do
    echo "== allocsum $f =="
    python allocsum.py "$f" 2>/dev/null | head -42
done
