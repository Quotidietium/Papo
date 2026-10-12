#!/usr/bin/env bash
# 批次193（R52）：复合负载轴（机械+实体+方块交互多域同窗叠加）分配普查发现腿——
# A-110nat（自然驻立基线，R47 A 形）vs B-110cmp（R48 挖掘立方 + R47 农场几何
# z+8 平移=挖掘与漏斗吸排/物品旋涡同窗叠加全窗）同二进制 0.110.0 配对差分。
# B−A=复合窗全谱，逐面归因既有十域清单（R46 机械/R47 旋涡/R48 挖掘/流送族），
# 未归因过阈面=跨域交互面候选（多变更者并发批处理/光照并发重传播/脏标记竞争）。
# JFR ObjectAllocationSample 60s 窗锚 CompositeCensus.loadWarmed=marker+12s
# （同刻 latch：挖掘 M+12..91 与 drain M+0..~75 同窗全叠加）。
# 工具链纪律（批次186/187 判例）：编译/运行全部钉死 /f/Java/21；端口独占门
# （R50 判例：不可并行两个 harness，同端口 25599 互踩连锁杀连接）。
# 门：TRACE≥bots + 零类版本错误 + exit 0 + B: DIGDONE≥bots·digsTotal≥400×bots·
# 探针 air×3+ctrl×2+漏斗 Items+chest cobblestone+实体在场×2；A: digsTotal=0。
# 用法: ./alloccensus-r52composite.sh [jar]
set -uo pipefail
cd "$(dirname "$0")"

JAR=${1:-../paper-server/build/libs/Papo-1.21.11-0.110.0.jar}
BOTS=12
SPREAD=150000
DIGMS=160000
JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar"

echo "== 编译（钉死 J21 javac；R49 判例：管道掩盖编译失败=跑旧类，独立验证）=="
/f/Java/21/bin/javac -encoding UTF-8 -sourcepath src \
  -cp "lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base64-4.2.7.Final.jar" \
  -d build/classes src/papo/bot/CompositeCensus.java || exit 1
[ -f build/classes/papo/bot/CompositeCensus.class ] || { echo "class missing"; exit 1; }

port_gate() {
    local linger=$($JCMD -l 2>/dev/null | grep -c "server.jar")
    if [ "$linger" != "0" ]; then
        echo "PORT-GATE FAIL: $linger 个 server.jar 进程残留（R50 判例：端口互踩）——先清理再跑"
        $JCMD -l 2>/dev/null | grep "server.jar"
        exit 1
    fi
}

run_leg() {
    local label="$1" cmp="$2"
    port_gate
    local stamp out
    stamp=$(date +%Y%m%d-%H%M%S)
    out="results/alloccmp-$stamp-$label"
    mkdir -p "$out"
    echo "================ leg $label start $(date +%T) ================"

    /f/Java/21/bin/java -cp "$CP" papo.bot.CompositeCensus "$JAR" "$BOTS" "$SPREAD" "$DIGMS" "$cmp" \
        > "results/alloccensus-$stamp-$label.log" 2>&1 &
    local harness=$!

    local pid=""
    for i in $(seq 1 60); do
        sleep 5
        pid=$($JCMD -l 2>/dev/null | grep "server.jar" | awk '{print $1}' | head -1)
        [ -n "$pid" ] && break
    done
    if [ -z "$pid" ]; then echo "leg $label: NO SERVER PID"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label server pid=$pid"

    local logf="results/alloccensus-$stamp-$label.log"
    local anchored=0
    for i in $(seq 1 300); do
        if grep -q "CompositeCensus.loadWarmed" "$logf" 2>/dev/null; then anchored=1; break; fi
        sleep 5
    done
    if [ "$anchored" != "1" ]; then echo "leg $label: NO loadWarmed 锚点"; kill $harness 2>/dev/null; return 1; fi
    echo "leg $label loadWarmed 锚点到达 $(date +%T)，起 JFR 60s（marker+12..72=挖掘+drain 叠加窗）"

    $JCMD "$pid" JFR.start name=cmp settings=profile duration=60s \
        filename="$PWD/$out/cmp.jfr" jdk.ObjectAllocationSample#threshold=1ms \
        > "$out/jfr-start.txt" 2>&1

    wait $harness
    local rc=$?
    echo "leg $label harness exit=$rc"

    # ---- 门（批次186 纪律：bot 在线正证据+零类版本错误+复合正证据）----
    local traces=$(grep -c "TRACE\[" "$logf")
    local vererr=$(grep -c "has been compiled by a more recent version" "$logf")
    local digdone=$(grep -c "DIGDONE\[" "$logf")
    local digs=$(grep -o "digsTotal=[0-9]*" "$logf" | head -1 | cut -d= -f2)
    local air=$(grep -c "CMP-DUG-AIR" "$logf")
    local ctrl=$(grep -c "CMP-CTRL-STONE" "$logf")
    local unknown=$(grep -c "Unknown or incomplete" "$logf")
    local present=$(grep -c "CMP-ITEMS-PRESENT" "$logf")
    echo "leg $label GATE traces=$traces vererr=$vererr digdone=$digdone digsTotal=${digs:--1} airProbes=$air ctrlProbes=$ctrl itemsPresent=$present unknown=$unknown"
    if [ "$vererr" != "0" ] || [ "$unknown" != "0" ] || [ "$rc" != "0" ] || [ "$traces" -lt "$BOTS" ]; then
        echo "leg $label: GATE FAIL (base)"; tail -30 "$logf"; return 1
    fi
    if [ "$cmp" = "1" ]; then
        # 旋涡流转正证据（R47 门式）：探针①（loadWarmed 前）漏斗 Items 非空 +
        # 探针②（后）chest 含 cobblestone=全链收物完成
        local hopper=$(awk '/loadWarmed/{exit} /CompositeCensus\.probe.*has the following block data/{n++} END{print n+0}' "$logf")
        local chest=$(awk '/loadWarmed/{seen=1} seen&&/CompositeCensus\.probe.*has the following block data.*cobblestone/{n++} END{print n+0}' "$logf")
        echo "leg $label churnEvidence hopperItems=$hopper (need >=1) chestCobble=$chest (need >=1) itemsPresent=$present (need >=2)"
        if [ "$digdone" -lt "$BOTS" ] || [ "${digs:--1}" -lt 4800 ] || [ "$air" -lt 3 ] || [ "$ctrl" -lt 2 ] \
            || [ "$hopper" -lt 1 ] || [ "$chest" -lt 1 ] || [ "$present" -lt 2 ]; then
            echo "leg $label: GATE FAIL (composite evidence)"; return 1
        fi
    else
        if [ "${digs:--1}" != "0" ]; then
            echo "leg $label: GATE FAIL (natural symmetry: digs != 0)"; return 1
        fi
    fi
    echo "$out" > "results/last-alloccmp-leg-$label.txt"
    echo "results/alloccensus-$stamp-$label.log" > "results/last-alloccmp-log-$label.txt"
}

run_leg A-110nat 0
[ $? -eq 0 ] || exit 1
run_leg B-110cmp 1
[ $? -eq 0 ] || exit 1

echo "== 双腿完成，聚合（B-A 差分判定另行）=="
python allocsum.py "$(cat results/last-alloccmp-leg-A-110nat.txt)/cmp.jfr" > "results/alloccmp-A-sum.txt" || true
python allocsum.py "$(cat results/last-alloccmp-leg-B-110cmp.txt)/cmp.jfr" > "results/alloccmp-B-sum.txt" || true
head -45 "results/alloccmp-B-sum.txt"
