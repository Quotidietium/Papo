#!/usr/bin/env bash
# 批次197（R56 长时复合浸泡窗）：时间维度×复合负载交叉真空格——R50 长时=自然
# 负载+仅 OldObjectSample；R52-R55 复合=全部 60s 短窗。本腿=复合形态（R52 原形
# gridN=10）+ ~23min bot 在线驻留，JFR 双事件集（ObjectAllocationSample#1ms +
# OldObjectSample）全程：分配侧=长窗新面（周期保存窗×复合负载、慢积累相），
# 存活侧=复合负载存活归因首像（R50 第九域的复合对偶）。
#
# 腿：A-110nat（自然长窗对照，cmp=0 同节拍驻立）→ B-110cmp（复合长窗发现）。
# 串行（端口独占门）。聚合：allocsum.py（分配）+ oldsum.py（存活）。
set -u
cd "$(dirname "$0")"
JAVAC=/f/Java/21/bin/javac
JAVA=/f/Java/21/bin/java.exe
JCMD=/f/Java/21/bin/jcmd.exe
JAR110=/f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.110.0.jar
BOTS=12
SPREAD=150000
DIGMS=1400000   # bot 段 23.3min：挖尽 ~80s → 驻留 ~22min（跨 4-5 个 5min 自动保存窗）
JFRDUR=1350s    # marker+12 起 ≈ measure 前（measure=12s+digMs-60s）
CP="build/classes"

# 端口独占门（R50 判例：并行 harness 同端口互踩）
resid=$("$JCMD" -l 2>/dev/null | grep -c "server\.jar")
if [ "$resid" != "0" ]; then echo "PORT-GATE: residual server.jar procs=$resid"; exit 1; fi

echo "== compile harness (foreground, R49 判例) =="
"$JAVAC" -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/CompositeCensus.java || exit 1
echo "COMPILE-OK"

run_leg() { # label jar cmp
    local label="$1" jar="$2" cmp="$3"
    local stamp out logf
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/soak-$stamp-$label"
    mkdir -p "$out"
    logf="$out/leg.log"
    echo "== leg $label start $(date +%T) jar=$jar cmp=$cmp digMs=$DIGMS jfr=$JFRDUR =="

    /f/Java/21/bin/java -cp "$CP" papo.bot.CompositeCensus "$jar" "$BOTS" "$SPREAD" "$DIGMS" "$cmp" 10 3 \
        > "$logf" 2>&1 &
    local harness=$!
    # 找 server pid + 等 loadWarmed 锚（marker+12 同刻 latch）
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

    "$JCMD" "$pid" JFR.start name=soak settings=profile duration=$JFRDUR \
        filename="$PWD/$out/soak.jfr" \
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
    local air=$(grep -c "CompositeCensus.probe.*CMP-DUG-AIR" "$logf")
    local ctrl=$(grep -c "CompositeCensus.probe.*CMP-CTRL-STONE" "$logf")
    local hopper=$(awk '/loadWarmed/{exit} /CompositeCensus\.probe.*has the following block data/{n++} END{print n+0}' "$logf")
    local chest=$(awk '/loadWarmed/{seen=1} seen&&/CompositeCensus\.probe.*has the following block data.*cobblestone/{n++} END{print n+0}' "$logf")
    local present=$(grep -c "CompositeCensus.probe.*CMP-ITEMS-PRESENT" "$logf")
    local gate=$(grep -o "CompositeCensus.gate logErrors=0 exited=true" "$logf" | head -1)
    local items=$(grep -o "CompositeCensus.cls net.minecraft.world.entity.item.ItemEntity instances=[0-9]*" "$logf" | head -1 | sed 's/.*instances=//')
    echo "leg $label GATE: traces=$traces digs=${digs:-none} air=$air ctrl=$ctrl hopper=$hopper chest=$chest present=$present items=${items:-none} heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    if [ "$cmp" != "0" ]; then
        [ "${digs:-0}" -ge 4800 ] || ok=0
        [ "$air" -ge 3 ] || ok=0
        [ "$ctrl" -ge 2 ] || ok=0
        [ "$hopper" -ge 1 ] || ok=0
        [ "$chest" -ge 1 ] || ok=0
        [ "$present" -ge 2 ] || ok=0
    else
        [ "${digs:-0}" = "0" ] || ok=0
    fi
    [ -n "$gate" ] || ok=0
    echo "leg $label gateOk=$ok"
    echo "$out" > "last-soak-leg-$label.txt"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-AB}"
case "$LEGS" in
    *A*) run_leg A-110nat "$JAR110" 0 || { echo "LEG-A FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *B*) run_leg B-110cmp "$JAR110" 1 || { echo "LEG-B FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
echo "== aggregate: allocation census =="
python allocsum.py results/soak-*-A-110nat/soak.jfr results/soak-*-B-110cmp/soak.jfr 2>/dev/null | head -100
echo "== aggregate: old-object survivorship =="
python oldsum.py results/soak-*-A-110nat/soak.jfr results/soak-*-B-110cmp/soak.jfr 2>/dev/null | head -60
