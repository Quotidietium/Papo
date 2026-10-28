#!/usr/bin/env bash
# 批次203（R62）：0299 村民 brain 收割面配对终验——0.110.0 vs 0.111.0 同 vill=2
# 村民风暴形态（144 村民调度），B−A 差分应见三收割面归零：
#   Brain 行为集迭代器族（LinkedKeySet.iterator ← Brain.tickEach/startEach）
#   PoiAccess BFS 工作集（LongOpenHashSet.rehash + LongArrayFIFOQueue.resize ← AcquirePoi）
#   SecondaryPoiSensor offset（BlockPos.offset ← SecondaryPoiSensor.doTick）
set -u
cd "$(dirname "$0")"
JAVAC=/f/Java/21/bin/javac
JCMD=/f/Java/21/bin/jcmd.exe
BOTS=12
SPREAD=150000
DIGMS=280000
JFRDUR=300s

resid=$("$JCMD" -l 2>/dev/null | grep -c "server\.jar")
if [ "$resid" != "0" ]; then echo "PORT-GATE: residual server.jar procs=$resid"; exit 1; fi

"$JAVAC" -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/CompositeCensus.java || exit 1
echo "COMPILE-OK"

run_leg() { # label jar
    local label="$1" jar="$2"
    local stamp out logf
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/vpair-$stamp-$label"
    mkdir -p "$out"
    logf="$out/leg.log"
    echo "== leg $label start $(date +%T) jar=$(basename "$jar") =="
    echo "$out" > "last-vpair-leg-$label.txt"

    /f/Java/21/bin/java -cp "build/classes" papo.bot.CompositeCensus "$jar" "$BOTS" "$SPREAD" "$DIGMS" 0 10 3 0 0 0 0 2 \
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

    "$JCMD" "$pid" JFR.start name=vpair settings=profile duration=$JFRDUR \
        filename="$PWD/$out/vpair.jfr" \
        jdk.ObjectAllocationSample#threshold=1ms \
        jdk.OldObjectSample#enabled=true \
        > "$out/jfr-start.txt" 2>&1

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"
    if [ "$rc" != "0" ]; then return 1; fi

    local ok=1
    local traces=$(grep -c "CompositeCensus.botFinalPos" "$logf")
    local heap=$(grep -o "CompositeCensus.heapUsedKb=[0-9-]*" "$logf" | head -1 | cut -d= -f2)
    local gate=$(grep -o "CompositeCensus.gate logErrors=0 exited=true" "$logf" | head -1)
    local lectern=$(grep -c "CompositeCensus.probe.*VLGR-LECTERN" "$logf")
    local present=$(grep -c "CompositeCensus.probe.*VLGR-PRESENT" "$logf")
    local vecho=$(grep -o "CompositeCensus.villagerSummonEcho=[0-9]*" "$logf" | head -1 | cut -d= -f2 | cut -d' ' -f1)
    echo "leg $label GATE: traces=$traces lectern=$lectern present=$present vecho=${vecho:-none} heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    [ -n "$gate" ] || ok=0
    [ "${lectern:-0}" -ge 1 ] || ok=0
    [ "${present:-0}" -ge 1 ] || ok=0
    [ "${vecho:-0}" -ge 140 ] || ok=0
    echo "leg $label gateOk=$ok"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-AB}"
case "$LEGS" in
    *A*) run_leg A-V110 /f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.110.0.jar || { echo "LEG-A FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *B*) run_leg B-V112 /f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.112.0.jar || { echo "LEG-B FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
