#!/usr/bin/env bash
# 批次199（R58 红石 wire 翻转轴）：大网络激活/去激活传播风暴分配普查——R46 判例
# 亲证 wire 在 y=200 空中负载窗从未出现（缺支撑弹跳失效，R46 只有观察者时钟+漏斗）；
# R4（batch123）是内存战役前的语义/常数评估。红石 wire 传播链（最深递归更新链
# 之一）的分配面=第十五域真新格。
#
# 形态：每 bot 侧向 wire 网络（stone 平台 15×15 y=200 + redstone_wire y=201 +
# 中心 redstone_block 驱动=2,700 wire 全网）→JFR 窗内 marker+25 起每 3s 交替
# 驱动块 ×30 次=激活/去激活传播风暴 →双态正证据（EDGE-ON power=8/EDGE-OFF
# power=0）。腿：A rs=0 vs B rs=1（bot 零改动在线驻立，零挖掘零农场=轴纯净）。
set -u
cd "$(dirname "$0")"
JAVAC=/f/Java/21/bin/javac
JCMD=/f/Java/21/bin/jcmd.exe
JAR110=/f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.110.0.jar
BOTS=12
SPREAD=150000
DIGMS=360000
JFRDUR=240s

resid=$("$JCMD" -l 2>/dev/null | grep -c "server\.jar")
if [ "$resid" != "0" ]; then echo "PORT-GATE: residual server.jar procs=$resid"; exit 1; fi

echo "== compile harness (foreground, R49 判例) =="
"$JAVAC" -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/CompositeCensus.java || exit 1
echo "COMPILE-OK"

run_leg() { # label rs
    local label="$1" rs="$2"
    local stamp out logf
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/rs-$stamp-$label"
    mkdir -p "$out"
    logf="$out/leg.log"
    echo "== leg $label start $(date +%T) rs=$rs jfr=$JFRDUR =="

    /f/Java/21/bin/java -cp "build/classes" papo.bot.CompositeCensus "$JAR110" "$BOTS" "$SPREAD" "$DIGMS" 0 10 3 0 "$rs" \
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

    "$JCMD" "$pid" JFR.start name=rs settings=profile duration=$JFRDUR \
        filename="$PWD/$out/rs.jfr" \
        jdk.ObjectAllocationSample#threshold=1ms \
        jdk.OldObjectSample#enabled=true \
        > "$out/jfr-start.txt" 2>&1

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"
    if [ "$rc" != "0" ]; then return 1; fi

    # ---- 门（EDGE 双态=传播真实到达边缘；wire 网络回显复核走服务器日志） ----
    local ok=1
    local traces=$(grep -c "CompositeCensus.botFinalPos" "$logf")
    local digs=$(grep -o "CompositeCensus.digsTotal=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    local heap=$(grep -o "CompositeCensus.heapUsedKb=[0-9-]*" "$logf" | head -1 | cut -d= -f2)
    local gate=$(grep -o "CompositeCensus.gate logErrors=0 exited=true" "$logf" | head -1)
    local edgeOn=$(grep -c "CompositeCensus.probe.*RS-EDGE-ON" "$logf")
    local edgeOff=$(grep -c "CompositeCensus.probe.*RS-EDGE-OFF" "$logf")
    echo "leg $label GATE: traces=$traces digs=${digs:-none} edgeOn=$edgeOn edgeOff=$edgeOff heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    [ "${digs:-1}" = "0" ] || ok=0
    if [ "$rs" != "0" ]; then
        [ "$edgeOn" -ge 1 ] || ok=0
        [ "$edgeOff" -ge 1 ] || ok=0
    fi
    [ -n "$gate" ] || ok=0
    echo "leg $label gateOk=$ok"
    echo "$out" > "last-rs-leg-$label.txt"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-AB}"
case "$LEGS" in
    *A*) run_leg A-110nat0 0 || { echo "LEG-A FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *B*) run_leg B-110rs 1 || { echo "LEG-B FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
for f in results/rs-*-A-110nat0/rs.jfr results/rs-*-B-110rs/rs.jfr; do
    echo "== allocsum $f =="
    python allocsum.py "$f" 2>/dev/null | head -42
done
