#!/usr/bin/env bash
# 批次183（R42）：实体密集稳态窗分配普查（发现轮）——B-106 单腿。
# 背景：R25 在 EntitySoakCensus 场景只普查过驻留（~679 mob ≈6MB），分配面从未普查；
# 真实服务器稳态 GC 压力由 tick 路径支配（difficulty=normal+spawn-monsters=true）。
# 以 harness "boot:" 行为零点：+10s join 稳定 +150s 行走 +150s 驻留头（怪群成熟）
# 后开 60s 采样窗（覆盖驻留 150-210s）；EntitySoak measure 于驻留+270s 不重叠。
cd "$(dirname "$0")"

JCMD=/f/Java/21/bin/jcmd.exe
CP="build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar"

label=B106-entity
jar=../paper-server/build/libs/Papo-1.21.11-0.106.0.jar
[ -f "$jar" ] || { echo "missing $jar"; exit 1; }
stamp=$(date +%Y%m%d-%H%M%S)
out="results/allocentity-$stamp-$label"
mkdir -p "$out"
echo "================ leg $label start $(date +%T) ================"

/f/Java/21/bin/java -cp "$CP" papo.bot.EntitySoakCensus "$jar" 12 150000 300000 \
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
bootseen=0
for i in $(seq 1 240); do
    if grep -q "^boot:" "$logf" 2>/dev/null; then bootseen=1; break; fi
    sleep 5
done
if [ "$bootseen" != "1" ]; then echo "leg $label: NO BOOT LINE"; kill $harness 2>/dev/null; exit 1; fi
echo "leg $label boot line seen $(date +%T)"

sleep 310
$JCMD "$pid" JFR.start name=entity settings=profile duration=60s \
    filename="$PWD/$out/entity.jfr" jdk.ObjectAllocationSample#threshold=1ms \
    > "$out/jfr-start.txt" 2>&1
echo "leg $label entity jfr started $(date +%T)"

wait $harness
rc=$?
echo "leg $label harness exit=$rc"
grep -E "EntitySoak\.(gate|entityTotal|heapUsedKb)" "$logf"
echo "$out" > "results/last-allocentity-leg-$label.txt"
echo "leg $label done"
