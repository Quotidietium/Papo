#!/usr/bin/env bash
# 批次191（R50）：老年代驻留的分配站点归因轴（长时相变窗）——单发现腿。
# JFR jdk.OldObjectSample 携带 allocationTime/objectAge/分配栈=驻留可归因到
# 分配站点（R24-R49 八域全测分配站点，从未测存活/晋升；R5-R22 驻留普查只有
# 类级直方图）。12 bot 标准 MemSoakVerify 形态（walk 150s+驻留 22min），
# JFR 全程录制，OldObjectSample 末端采样=流送相分配而驻留晋升的对象。
# 判例：不可并行两个 harness（同端口 25599 互踩——本腿首轮事故）。
cd "$(dirname "$0")"

JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

label=B-110old
jar=../paper-server/build/libs/Papo-1.21.11-0.110.0.jar
[ -f "$jar" ] || { echo "missing $jar"; exit 1; }
stamp=$(date +%Y%m%d-%H%M%S)
out="results/allocold-$stamp-$label"
mkdir -p "$out"
echo "================ leg $label start $(date +%T) ================"

# 端口独占门：残留 harness/服务器即中止（R50 首轮事故判例）
if jcmd_out=$($JCMD -l 2>/dev/null | grep -iE "memsoak|DigStorm|JoinQuit|ItemChurn|server\.jar"); then
    echo "leg $label: PORT-HOLDER PRESENT, abort:"; echo "$jcmd_out"; exit 1
fi

/f/Java/21/bin/java -cp "$CP" papo.bot.MemSoakVerify "$jar" 12 150000 1320000 120000 \
    > "results/alloccensus-$stamp-$label.log" 2>&1 &
harness=$!

pid=""
for i in $(seq 1 60); do
    sleep 5
    pid=$($JCMD -l 2>/dev/null | grep "server.jar" | awk '{print $1}' | head -1)
    [ -n "$pid" ] && break
done
if [ -z "$pid" ]; then echo "leg $label: NO SERVER PID"; kill $harness 2>/dev/null; exit 1; fi
echo "leg $label server pid=$pid"

bootseen=0
for i in $(seq 1 240); do
    if grep -q "^boot:" "results/alloccensus-$stamp-$label.log" 2>/dev/null; then bootseen=1; break; fi
    sleep 5
done
[ "$bootseen" = "1" ] || { echo "leg $label: NO BOOT LINE"; kill $harness 2>/dev/null; exit 1; }
echo "leg $label boot line seen $(date +%T)"

# boot+60s 起全程录制 1500s（覆盖 walk+全驻留；OldObjectSample 周期+末端采样）
sleep 60
$JCMD "$pid" JFR.start name=old settings=profile duration=1500s \
    filename="$PWD/$out/old.jfr" \
    jdk.OldObjectSample#enabled=true \
    > "$out/jfr-start.txt" 2>&1
echo "leg $label old jfr started $(date +%T)"

wait $harness
rc=$?
echo "leg $label harness exit=$rc"
grep -E "MemSoak.gate" "results/alloccensus-$stamp-$label.log"
echo "$out" > "results/last-allocold-leg-$label.txt"
echo "leg $label done $(date +%T)"
