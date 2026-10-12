#!/usr/bin/env bash
# 批次200（R59 方块放置轴）：R48 挖掘风暴的对偶——right-click 放方块链
# （SET_CREATIVE_MODE_SLOT+USE_ITEM_ON→BlockItem.place/canPlace 碰撞检查/
# BlockPlaceEvent 事件机器/状态广播）从未成像（R48 报告纯破坏，事实核查零放置提及）。
#
# 形态：cmp=1 几何（挖掘立方+农场 fill 两腿同发=挖掘/农场面同量相消）；bot 半段制
# dig(digMs/2)+place(digMs/2)——B 腿放置风暴紧跟挖尽段把立方表面外贴格逐格放回；
# A 腿=同节拍纯挖（后段驻留）。B−A 差分=纯放置面。
set -u
cd "$(dirname "$0")"
JAVAC=/f/Java/21/bin/javac
JCMD=/f/Java/21/bin/jcmd.exe
JAR110=/f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.110.0.jar
BOTS=12
SPREAD=150000
DIGMS=280000   # dig 140s（80s 挖尽+60s 驻留）+place 140s（~20s 放回+120s 驻留）
JFRDUR=300s

resid=$("$JCMD" -l 2>/dev/null | grep -c "server\.jar")
if [ "$resid" != "0" ]; then echo "PORT-GATE: residual server.jar procs=$resid"; exit 1; fi

echo "== compile harness (foreground, R49 判例) =="
"$JAVAC" -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/CompositeCensus.java || exit 1
echo "COMPILE-OK"

run_leg() { # label place
    local label="$1" place="$2"
    local stamp out logf
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/place-$stamp-$label"
    mkdir -p "$out"
    logf="$out/leg.log"
    echo "== leg $label start $(date +%T) place=$place jfr=$JFRDUR =="

    /f/Java/21/bin/java -cp "build/classes" papo.bot.CompositeCensus "$JAR110" "$BOTS" "$SPREAD" "$DIGMS" 1 10 3 0 0 "$place" \
        > "$logf" 2>&1 &
    local harness=$!
    local pid=""
    local anchored=0 i
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

    "$JCMD" "$pid" JFR.start name=place settings=profile duration=$JFRDUR \
        filename="$PWD/$out/place.jfr" \
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
    local digs=$(grep -o "CompositeCensus.digsTotal=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    local places=$(grep -o "places=[0-9]*" "$logf" | awk -F= '{s+=$2} END{print s+0}')
    local heap=$(grep -o "CompositeCensus.heapUsedKb=[0-9-]*" "$logf" | head -1 | cut -d= -f2)
    local gate=$(grep -o "CompositeCensus.gate logErrors=0 exited=true" "$logf" | head -1)
    local pstone=$(grep -c "CompositeCensus.probe.*PLACE-STONE" "$logf")
    local air=$(grep -c "CompositeCensus.probe.*CMP-DUG-AIR" "$logf")
    echo "leg $label GATE: traces=$traces digs=${digs:-none} places=$places pstone=$pstone air=$air heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    [ "${digs:-0}" -ge 4800 ] || ok=0
    [ "$air" -ge 3 ] || ok=0
    if [ "$place" != "0" ]; then
        # 候选池真值 ~95/bot×12=1140（d2≤25 面格筛后）——批次200 判例：门阈值须按
        # 候选池校准，预估值 1800 是无据外推；1080=真值 −5% 余量
        [ "${places:-0}" -ge 1080 ] || ok=0
        [ "$pstone" -ge 3 ] || ok=0
    else
        [ "${places:-1}" = "0" ] || ok=0
    fi
    [ -n "$gate" ] || ok=0
    echo "leg $label gateOk=$ok"
    echo "$out" > "last-place-leg-$label.txt"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-AB}"
case "$LEGS" in
    *A*) run_leg A-110dig 0 || { echo "LEG-A FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *B*) run_leg B-110digplace 1 || { echo "LEG-B FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
for f in results/place-*-A-110dig/place.jfr results/place-*-B-110digplace/place.jfr; do
    echo "== allocsum $f =="
    python allocsum.py "$f" 2>/dev/null | head -42
done
