# 批次 41 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-041718.json`。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**3 项全部正收益（3.79×/1.25×/1.46×，CI 均不重叠）**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0166 漏斗吸取路径缓存 | HopperSuckCacheBench | 13.291 | 3.505 | **3.79×** | CI [12.62,13.96] vs [3.32,3.69] 不重叠。静止矿车场景每 tick 4 处构造（BE suck pos + 矿车 suck pos + suck AABB + 拾取 AABB）→ 键控命中；before 四对象均经 Blackhole 逃逸，分配真实 |
| 0167 弹射物扫描 AABB 2→1 | ProjectileScanAabbBench | 6.137 | 4.902 | **1.25×** | CI [5.79,6.49] vs [4.70,5.10] 不重叠。每弹射物每 tick 省 1 个中间 AABB；NaN/-0.0 语义经矩阵自检逐位一致 |
| 0168 活塞扫描盒 3→1 | PistonScanAabbBench | 9.558 | 6.557 | **1.46×** | CI [9.24,9.87] vs [6.05,7.06] 不重叠。每运动活塞每动画 tick 省 2 个中间 AABB；6 方向 × delta 符号 × bounds 变体矩阵逐位一致 |

## 等价性支点（源码实证）

- **0166**：
  - 方块漏斗 suck pos：getLevelX/Y/Z = worldPosition 各轴 + 0.5（精确 double），containing(x+0.5, (y+0.5)+1.0, z+0.5) = floor 各轴 = (x, y+1, z) = worldPosition.above()，逐位等价；worldPosition final，setBlock 重建 BE 自然失效（0113/0116 同论点）。
  - 矿车 suck pos / suck AABB：BlockPos.containing 与 AABB.move 均为三个坐标 double 的纯函数，键一致则值一致；键为 (getLevelX(), getLevelY(), getLevelZ()) 现场读取三元组；NaN 坐标 `==` 比较恒 false → 恒重算，结果仍与 before 一致（自检覆盖）；pos/aabb 双缓存共用键三元组，失效时同步重建保持键-值不变式。
  - 后备拾取 AABB：inflate(0.25,0,0.25) 为 bb 值的纯函数，AABB 不可变，以 bb 引用为键；setPos/移动替换 bb 对象自然失效。
  - 第三方 Hopper 实现（非 BE 非矿车）全部保持原 per-call 路径。
- **0167**：折叠式 min'=(min+(dm<0?dm:0))-1 / max'=(max+(dm>0?dm:0))+1 与 expandTowards 的 `if (x<0.0) min+=x; else if (x>0.0) max+=x;` + inflate(±1.0) 链逐轴对应：三元式对 NaN 同构（比较全 false → 0.0，与原版不动该轴后 ∓1.0 相同，min=-0.0 时 +0.0 差异被 -1.0 抹除）；左结合保持 ((min+t)∓1.0) 结合序；构造器归一化两式输入相同。
- **0168**：m = bounds.move(pos+progress·step) 后，PistonMath.getMovementArea 六向分支取 m 的 min 或 max 棱（负向取 min 棱、正向取 max 棱），minmax(m) 逐轴并集：运动轴 lo=min(m.edge+min(d',0), m.min)、hi=max(m.edge+max(d',0), m.max)，非运动轴 Math.min/max(x,x)=x 恒等直给；左结合加法链（(bounds±o)±min）与 Math.min/max 操作数序逐一对齐；并集恒已归一化（lo ≤ m.min ≤ m.max ≤ hi），两式构造器归一化均为恒等。d/progress/bounds 均有限（progress∈[0,1]），无 NaN 分歧。

## 勘察说明

- ItemEntity 合并 AABB（每 2/40 tick 一次单 inflate）已为单分配不可再折；radius≤0 早退会改变 radius=0 时重叠物品的合并行为，不满足可证等价，不做。
- Brain.tick / broadcastChanges / 漏斗 BE suck AABB 已有 Papo 优化（本批次仅补矿车与 suck pos 缺口）。
- 活塞内层 per-entity×shape 的 moveByPositionAndProgress+getMovementArea 双分配（仅实体在场时触发）频次低、改写面大，本批次不覆盖。
