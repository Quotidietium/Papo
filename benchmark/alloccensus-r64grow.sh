#!/usr/bin/env bash
# 批次205（R64 农业生长轴）：骨粉催熟风暴——BoneMealItem→growCrop→setBlock
# （captureTreeGeneration 捕获路径）+growth levelEvent 广播+useOn 尾
# BlockFertilizeEvent/StructureGrowEvent 排空链从未被任何口径碰过
# （CropBlock/骨粉/竹子全史零命中）=第二十一域真新格（R59 USE_ITEM_ON 协议
# 复用；探针腿先行）。参数序：9 值于 DIGMS 后（grow=args[13]，批次204 参数
# 位错判例逐位重数）。形态：grow=2（5×5 农田+24 波 fill 重植+bot 骨粉
# USE_ITEM_ON×12 互质扫位=3,456 use）vs grow=1 对照（田在场零骨粉）。B−A=纯催熟链。
set -u
cd "$(dirname "$0")"
JAVAC=/f/Java/21/bin/javac
JCMD=/f/Java/21/bin/jcmd.exe
JAR113=/f/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.113.0.jar
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
    local label="$1" grow="$2"
    local stamp out logf
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/growleg-$stamp-$label"
    mkdir -p "$out"
    logf="$out/leg.log"
    echo "== leg $label start $(date +%T) grow=$grow jfr=$JFRDUR =="
    echo "$out" > "last-growleg-leg-$label.txt"

    /f/Java/21/bin/java -cp "build/classes" papo.bot.CompositeCensus "$JAR113" "$BOTS" "$SPREAD" "$DIGMS" 0 10 3 0 0 0 0 0 0 "$grow" \
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

    "$JCMD" "$pid" JFR.start name=grow settings=profile duration=$JFRDUR \
        filename="$PWD/$out/grow.jfr" \
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
    local field=$(grep -c "CompositeCensus.probe.*GROW-FIELD" "$logf")
    local ageadv=$(grep -c "CompositeCensus.probe.*GROW-AGE[2-9]" "$logf")
    local age0=$(grep -c "CompositeCensus.probe.*GROW-AGE0" "$logf")
    local uses=$(grep -o "CompositeCensus.growUsesSent=[0-9]*" "$logf" | head -1 | cut -d= -f2 | cut -d' ' -f1)
    echo "leg $label GATE: traces=$traces field=$field ageadv=$ageadv age0=$age0 uses=${uses:-none} heap=${heap:-none} gate='$gate'"
    [ "$traces" -ge 12 ] || ok=0
    [ -n "$gate" ] || ok=0
    [ "${field:-0}" -ge 1 ] || ok=0
    if [ "$grow" = "1" ]; then
        # 对照：田在场零骨粉（wheat 恒 age=0）；uses 无风暴分支=none 即 0 同过
        # （R62 判例：门与 harness 输出约定腿前对齐）
        [ "${age0:-0}" -ge 1 ] || ok=0
        [ "${uses:-0}" = "0" ] || ok=0
    fi
    if [ "$grow" = "2" ]; then
        # 工作量正证据=harness 内 use 计数（bot 包序无服务器回显）+多龄探针 ≥1
        # （1 use 推进 2-5 龄，age 精确谓词多探针任一命中）；链正证据由 JFR
        # BoneMealItem/CropBlock/BlockFertilizeEvent 特征帧差分阶段承担
        [ "${uses:-0}" -ge 3000 ] || ok=0
        [ "${ageadv:-0}" -ge 1 ] || ok=0
    fi
    echo "leg $label gateOk=$ok"
    if [ "$ok" = "1" ]; then return 0; else return 1; fi
}

LEGS="${1:-B}"
case "$LEGS" in
    *B*) run_leg B-113storm 2 || { echo "LEG-B FAILED"; exit 1; } ;;
esac
case "$LEGS" in
    *A*) run_leg A-113idle 1 || { echo "LEG-A FAILED"; exit 1; } ;;
esac

echo "== ALL LEGS GREEN $(date +%T) =="
for f in $(ls -d results/growleg-*-A-113idle 2>/dev/null | tail -1)/grow.jfr $(ls -d results/growleg-*-B-113storm 2>/dev/null | tail -1)/grow.jfr; do
    echo "== allocsum $f =="
    python allocsum.py "$f" 2>/dev/null | head -42
done
