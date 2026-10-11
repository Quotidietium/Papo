#!/usr/bin/env bash
# 批次165（R24）：堆外面与分配率普查腿——NMT（baseline/diff）+ Metaspace 构成 +
# 流送峰窗 JFR jdk.ObjectAllocationSample 分配源归因（R22 观察的年轻代再填充突发）。
cd "$(dirname "$0")"
JAR="${1:-../paper-server/build/libs/Papo-1.21.11-0.97.0.jar}"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="results/alloc-$STAMP"
mkdir -p "$OUT"
JCMD=/f/Java/21/bin/jcmd.exe

/f/Java/21/bin/java -cp "build/classes;lib/gson-2.11.0.jar;lib/netty-buffer-4.2.7.Final.jar;lib/netty-common-4.2.7.Final.jar;lib/netty-transport-4.2.7.Final.jar;lib/netty-codec-base-4.2.7.Final.jar" \
    papo.bot.MemSoakVerify "$JAR" 12 150000 180000 120000 \
    "-XX:NativeMemoryTracking=summary" > "results/alloccensus-$STAMP.log" 2>&1 &
HARNESS=$!

PID=""
for i in $(seq 1 60); do
    sleep 5
    PID=$($JCMD -l 2>/dev/null | grep "server.jar" | awk '{print $1}' | head -1)
    [ -n "$PID" ] && break
done
echo "server pid=$PID"
if [ -z "$PID" ]; then echo "NO SERVER PID"; wait $HARNESS; exit 1; fi

# 引导后：NMT 基线（此后 diff 反映运行期净增长）
sleep 5
$JCMD $PID VM.native_memory baseline > "$OUT/nmt-baseline.txt" 2>&1
echo "baseline done $(date +%T)"

# 流送峰窗（bot 行走 t≈30-90s）：JFR 分配采样
sleep 25
$JCMD $PID JFR.start name=burst settings=profile duration=60s filename="$PWD/$OUT/burst.jfr" jdk.ObjectAllocationSample#threshold=1ms > "$OUT/jfr-start.txt" 2>&1
echo "jfr started $(date +%T)"

# 驻留期（行走 150s+驻留 60s 后）：NMT diff + metaspace + 线程计数 + GC heap
sleep 185
$JCMD $PID VM.native_memory summary.diff > "$OUT/nmt-diff.txt" 2>&1
$JCMD $PID VM.native_memory summary > "$OUT/nmt-summary.txt" 2>&1
$JCMD $PID VM.metaspace > "$OUT/metaspace.txt" 2>&1
$JCMD $PID Thread.print > "$OUT/threads.txt" 2>&1
$JCMD $PID GC.heap_info > "$OUT/heapinfo-dwell.txt" 2>&1
echo "dwell census done $(date +%T)"

wait $HARNESS
RC=$?
echo "harness exit=$RC"
grep -E "MemSoak.gate" "results/alloccensus-$STAMP.log"
ls -la "$OUT/"
