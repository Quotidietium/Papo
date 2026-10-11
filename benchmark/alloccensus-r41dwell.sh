#!/usr/bin/env bash
# 批次182（R41）：驻留稳态 tick 窗分配普查（发现轮）——B-105 单腿。
# 背景：R24-R40 分配普查窗全部落在行走/流送段（世界生成支配）；驻留段（12 bot
# 在线持有区块票据、无流送、纯稳态 tick）的分配面从未被普查。本腿 JFR 窗对准
# 驻留 25-85s：以 harness 日志 "walk phase started" 为行走零点（bot join 错峰
# ≤8s），+150s 行走结束进驻留，再 +25s 让尾随流送排空；MemSoak histogram 于
# 驻留+120s 采集，不与 JFR 窗重叠。
cd "$(dirname "$0")"

JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

label=B105-dwell
jar=../paper-server/build/libs/Papo-1.21.11-0.105.0.jar
[ -f "$jar" ] || { echo "missing $jar"; exit 1; }
stamp=$(date +%Y%m%d-%H%M%S)
out="results/allocdwell-$stamp-$label"
mkdir -p "$out"
echo "================ leg $label start $(date +%T) ================"

/f/Java/21/bin/java -cp "$CP" papo.bot.MemSoakVerify "$jar" 12 150000 180000 120000 \
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

logf="results/alloccensus-$stamp-$label.log"
walkseen=0
for i in $(seq 1 240); do
    if grep -q "walk phase started" "$logf" 2>/dev/null; then walkseen=1; break; fi
    sleep 5
done
if [ "$walkseen" != "1" ]; then echo "leg $label: NO WALK LINE"; kill $harness 2>/dev/null; exit 1; fi
echo "leg $label walk line seen $(date +%T)"

sleep 175
$JCMD "$pid" JFR.start name=dwell settings=profile duration=60s \
    filename="$PWD/$out/dwell.jfr" jdk.ObjectAllocationSample#threshold=1ms \
    > "$out/jfr-start.txt" 2>&1
echo "leg $label dwell jfr started $(date +%T)"

wait $harness
rc=$?
echo "leg $label harness exit=$rc"
grep -E "MemSoak.gate" "$logf"
echo "$out" > "results/last-allocdwell-leg-$label.txt"
echo "leg $label done"
