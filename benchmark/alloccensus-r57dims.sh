#!/usr/bin/env bash
# 批次198（R57 维度轴）：下界+末地生成+多世界并驻分配普查——R5-R56 十三域全部
# overworld，跨维度（nether 生成器/末地岛屿噪声/三维并驻 Level 常量+光照引擎+
# 实体查找结构）从未成像（事实核查：报告 nether/end 命中全为附带提及）。
#
# 腿：A-110 dims=0（自然驻立基线）vs B-110 dims=1（marker+25s JFR 窗内触发
# 24 处跨维度同步生成 fill+forceload 钉探针区块）。bot 零改动、零挖掘零农场
# =差分纯维度面。JFR 120s（覆盖生成尾部）双事件集。
set -u
cd "$(dirname "$0")"
JAVAC=/f/Java/21/bin/javac
JAVA=/f/Java/21/bin/java.exe
JCMD=/f/Java/21/bin/jcmd.exe
JAR110=/f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.110.0.jar
BOTS=12
SPREAD=150000
DIGMS=360000
JFRDUR=300s

resid=$("$JCMD" -l 2>/dev/null | grep -c "server\.jar")
if [ "$resid" != "0" ]; then echo "PORT-GATE: residual server.jar procs=$resid"; exit 1; fi

echo "== compile harness (foreground, R49 判例) =="
"$JAVAC" -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/CompositeCensus.java || exit 1
echo "COMPILE-OK"

run_leg() { # label dims
    local label="$1" dims="$2"
    local stamp out logf
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/dims-$stamp-$label"
    mkdir -p "$out"
    logf="$out/leg.log"
    echo "== leg $label start $(date +%T) dims=$dims jfr=$JFRDUR =="

    /f/Java/21/bin/java -cp "build/classes" papo.bot.CompositeCensus "$JAR110" "$BOTS" "$SPREAD" "$DIGMS" 0 10 3 "$dims" \
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

    "$JCMD" "$pid" JFR.start name=dims settings=profile duration=$JFRDUR \
        filename="$PWD/$out/dims.jfr" \
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
    local heap=$(grep -o "CompositeCensus.heapUsedKb=[0-9-]*" "$logf" | head -1 | cut -d= -f2)
    local gate=$(grep -o "CompositeCensus.gate logErrors=0 exited=true" "$logf" | head -1)
    local dimn=$(grep -c "CompositeCensus.probe.*DIM-N-OK" "$logf")
    local filled=$(grep -c "CompositeCensus.probe.*Filled" "$logf")
    local dime=$(grep -c "CompositeCensus.probe.*DIM-E-OK" "$logf")
    echo "leg $label GATE: traces=$traces digs=${digs:-none} dimN=$dimn dimE=$dime filled=$filled heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    [ "${digs:-1}" = "0" ] || ok=0
    if [ "$dims" != "0" ]; then
        [ "$dimn" -ge 1 ] || ok=0
        [ "$dime" -ge 1 ] || ok=0
        [ "${filled:-0}" -ge 40 ] || ok=0
    fi
    [ -n "$gate" ] || ok=0
    echo "leg $label gateOk=$ok"
    echo "$out" > "last-dims-leg-$label.txt"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-AB}"
case "$LEGS" in
    *A*) run_leg A-110nat0 0 || { echo "LEG-A FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *B*) run_leg B-110dims 1 || { echo "LEG-B FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
for f in results/dims-*-A-110nat0/dims.jfr results/dims-*-B-110dims/dims.jfr; do
    echo "== allocsum $f =="
    python allocsum.py "$f" 2>/dev/null | head -42
done
