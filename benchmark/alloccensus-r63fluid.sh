#!/usr/bin/env bash
# 批次204（R63 流体轴）：水盆流动风暴——FlowingFluid.tick→spread→spreadToSides
# 再计算链从未被专轴普查成像（批30/31=0104/0106 微基准单点；R52 附带 500 trips
# 仅物品掉水结果对象归因）=第二十域真新格（R58 wire 同构判例；探针腿先行）。
#
# 形态：fluid=2（石板水盆+围墙+24 波×水条/气条互质扫位=水流 7 格限距持续展开）
# vs fluid=1 对照（同盆零水零扰动）。bot y=200 驻留观察者。B−A=纯水流链。
set -u
cd "$(dirname "$0")"
JAVAC=/f/Java/21/bin/javac
JCMD=/f/Java/21/bin/jcmd.exe
JAR112=/f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.112.0.jar
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

run_leg() { # label fluid
    local label="$1" fluid="$2"
    local stamp out logf
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/fluidleg-$stamp-$label"
    mkdir -p "$out"
    logf="$out/leg.log"
    echo "== leg $label start $(date +%T) fluid=$fluid jfr=$JFRDUR =="
    echo "$out" > "last-fluidleg-leg-$label.txt"

    /f/Java/21/bin/java -cp "build/classes" papo.bot.CompositeCensus "$JAR112" "$BOTS" "$SPREAD" "$DIGMS" 0 10 3 0 0 0 0 0 "$fluid" \
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

    "$JCMD" "$pid" JFR.start name=fluid settings=profile duration=$JFRDUR \
        filename="$PWD/$out/fluid.jfr" \
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
    local water=$(grep -c "CompositeCensus.probe.*FLUID-WATER" "$logf")
    local dry=$(grep -c "CompositeCensus.probe.*FLUID-DRY" "$logf")
    local wall=$(grep -c "CompositeCensus.probe.*FLUID-WALL" "$logf")
    local fecho=$(grep -o "CompositeCensus.fluidFillEcho=[0-9]*" "$logf" | head -1 | cut -d= -f2 | cut -d' ' -f1)
    echo "leg $label GATE: traces=$traces water=$water dry=$dry wall=$wall fecho=${fecho:-none} heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    [ -n "$gate" ] || ok=0
    [ "${wall:-0}" -ge 1 ] || ok=0
    if [ "$fluid" = "1" ]; then
        # 对照：盆在场零水（unless 反向探针）；fecho 无 fill 驱动分支=none 即 0
        # 同过（R62 判例：门与 harness 输出约定腿前对齐）
        [ "${dry:-0}" -ge 1 ] || ok=0
        [ "${fecho:-0}" = "0" ] || ok=0
    fi
    if [ "$fluid" = "2" ]; then
        # 批次204 判例（R61 wq 同族）：fill 命令在 server 控制台零回显（非本地化，
        # 是根本不打印——探针腿实证 576 命令 0 行）→fecho 门退役，仅作观测行；
        # 流动正证据=FLUID-WATER 探针+JFR FlowingFluid/LiquidBlock 特征帧（差分阶段）
        [ "${water:-0}" -ge 1 ] || ok=0
    fi
    echo "leg $label gateOk=$ok"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-B}"
case "$LEGS" in
    *B*) run_leg B-112storm 2 || { echo "LEG-B FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *A*) run_leg A-112basin 1 || { echo "LEG-A FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
for f in $(ls -d results/fluidleg-*-A-112basin 2>/dev/null | tail -1)/fluid.jfr $(ls -d results/fluidleg-*-B-112storm 2>/dev/null | tail -1)/fluid.jfr; do
    echo "== allocsum $f =="
    python allocsum.py "$f" 2>/dev/null | head -42
done
