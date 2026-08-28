#!/usr/bin/env bash
# 批次111：ent.serverAiStep 子相位探针在位测量（AI 态，N=2000 容量墙）
# 分解：AI 束（批次110 Δ=11.64ms）= serverAiStep 计算 + 诱导物理/追踪
set -u
CP="F:/Github/repo/Papo/benchmark/build/classes"
JAR="F:/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.68.0.jar"
OUT="F:/Github/repo/Papo/benchmark/results"
echo "== N=2000 ai (probe 0261) start $(date +%H:%M:%S)"
"F:/Java/21/bin/java" -cp "$CP" papo.bot.EntityScaleBench "$JAR" 2000 180000 10 > "$OUT/batch111-N2000-ai.log" 2>&1
echo "== N=2000 ai exit=$? $(date +%H:%M:%S)"
grep -aE "cowsPresent|presence gate" "$OUT/batch111-N2000-ai.log" | head -3
