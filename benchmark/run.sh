#!/usr/bin/env bash
# Papo 基准测试编译+运行脚本
# 用法: ./run.sh [JMH正则过滤器，默认全部]
set -euo pipefail
cd "$(dirname "$0")"

LIB=lib
OUT=build/classes
CP="$LIB/jmh-core-1.37.jar;$LIB/jmh-generator-annprocess-1.37.jar;$LIB/jopt-simple-5.0.4.jar;$LIB/commons-math3-3.6.1.jar;$LIB/fastutil-8.5.18.jar;$LIB/netty-buffer-4.2.7.Final.jar;$LIB/netty-common-4.2.7.Final.jar;$LIB/netty-transport-4.2.7.Final.jar;$LIB/netty-codec-base-4.2.7.Final.jar;$LIB/concurrentutil-0.0.8.jar;$LIB/slf4j-api-2.0.1.jar"

mkdir -p "$OUT" results "$LIB"

# 依赖缺失时自动下载（lib/ 不入库）
dl() { [ -f "$LIB/$2" ] || curl -sfL -o "$LIB/$2" "https://repo1.maven.org/maven2/$1" || { echo "下载失败: $2"; exit 1; }; }
dl org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar jmh-core-1.37.jar
dl org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar jmh-generator-annprocess-1.37.jar
dl net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar jopt-simple-5.0.4.jar
dl org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar commons-math3-3.6.1.jar
dl it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar fastutil-8.5.18.jar
dl io/netty/netty-buffer/4.2.7.Final/netty-buffer-4.2.7.Final.jar netty-buffer-4.2.7.Final.jar
dl io/netty/netty-common/4.2.7.Final/netty-common-4.2.7.Final.jar netty-common-4.2.7.Final.jar
dl io/netty/netty-transport/4.2.7.Final/netty-transport-4.2.7.Final.jar netty-transport-4.2.7.Final.jar
dl io/netty/netty-codec-base/4.2.7.Final/netty-codec-base-4.2.7.Final.jar netty-codec-base-4.2.7.Final.jar
dl ca/spottedleaf/concurrentutil/0.0.8/concurrentutil-0.0.8.jar concurrentutil-0.0.8.jar
dl org/slf4j/slf4j-api/2.0.1/slf4j-api-2.0.1.jar slf4j-api-2.0.1.jar

echo "== 编译基准源码 =="
find src -name '*.java' > build/sources.txt
# JDK 21+ 需显式 -proc:full 才运行 classpath 上的 JMH 注解处理器（生成 BenchmarkList 与桩类）
javac -proc:full -cp "$CP" -d "$OUT" @build/sources.txt

FILTER="${1:-papo.bench.*}"
STAMP=$(date +%Y%m%d-%H%M%S)
TXT="results/bench-$STAMP.txt"
JSON="results/bench-$STAMP.json"

echo "== 运行 JMH（过滤器: $FILTER）=="
java -cp "$OUT;$CP" org.openjdk.jmh.Main "$FILTER" \
    -wi 3 -i 5 -f 2 -r 1s -w 1s \
    -rf json -rff "$JSON" | tee "$TXT"

echo "== 完成。原始输出: $TXT / $JSON =="
