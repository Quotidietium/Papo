package papo.bot;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.Inflater;

/**
 * 批次83：最小离线模式协议机器人（1.21.11，protocol 774）——真实服务器端到端 join 冒烟。
 *
 * 全部包 ID 与字段编码取自服务器源码注册序（LoginProtocols/ConfigurationProtocols 的
 * addPacket 顺序即 packetId，ProtocolInfoBuilder.listPackets 实证）：
 *
 * LOGIN C→S: 0x00 Hello(name≤16, uuid16B)  0x03 LoginAcknowledged
 * LOGIN S→C: 0x00 Disconnect  0x02 LoginFinished  0x03 Compression(varint threshold)
 * CONFIG C→S: 0x03 FinishConfiguration  0x04 KeepAlive(long)  0x07 SelectKnownPacks(varint count=0)
 * CONFIG S→C: 0x02 Disconnect  0x03 FinishConfiguration  0x04 KeepAlive  0x0E SelectKnownPacks
 *
 * join 判定：收到 FinishConfiguration(S→C) → 回 FinishConfiguration → 协议切 play →
 * 首个 play 包到达 = placeNewPlayer 完成（含批次82 预取消费点）。
 * 关闭 socket = 玩家退出（触发批次79 异步 quit 存档）。
 */
public final class OfflineJoinBot {

    public static final int PROTOCOL_VERSION = 774; // 1.21.11（mojang version.json 实证）

    private final String host;
    private final int port;
    private final String name;
    private final UUID offlineUuid;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    /** 批次205 判例：sendPacket/sendRaw 无锁=跨线程并发写帧损坏（storm 主线程 USE
     *  包与 bot 线程 move 包交错）——帧级互斥锁（R59 单线程发送从未踩中）。 */
    private final Object papoSendLock = new Object();
    private int compressionThreshold = -1;

    // 批次141（R5）：服务器权威位置跟踪。PLAY 0x45 PLAYER_POSITION（varint teleportId +
    // 6×double(x,y,z,dx,dy,dz) + 2×float(yaw,pitch) + int 亲属位掩码，X/Y/Z=0x01/02/04）
    // 携带真实出生/纠偏坐标——walk 原先写死 (0.5,100,0.5) 起点，与实际 world spawn（本种子
    // 在 z≈420）相距 400+ 格，首个移动包即被 "moved too quickly" 拒绝，"行走 bot" 实际从未
    // 离开过出生点（TickSurveyBench 批90/93-96 的隐性缺陷，本批顺带修复）。收到 0x45 即采纳
    // 坐标（按亲属位增量/绝对）并回 ACCEPT_TELEPORTATION(S 0x00, varint id)。
    private double posX, posY, posZ;
    private boolean posKnown;
    private int traceHex;   // 诊断探针（批次141）：0x46 转储计数
    private int traceMoves; // 诊断探针（批次141）：移动包转储计数

    // 批次189（R48 挖掘风暴轴）：creative 即时挖掘——serverbound PLAYER_ACTION 0x28
    // （GameProtocols 注册序，三锚点验证 ACCEPT_TELEPORT=0x00/KEEP_ALIVE=0x1B/
    // MOVE_PLAYER_POS=0x1D）；payload = varint action(START_DESTROY_BLOCK=0) +
    // long BlockPos.asLong 大端 + byte direction(3D data value) + varint sequence。
    // creative instabuild：START 即 destroyAndAck 即时破坏（ServerPlayerGameMode
    // handleBlockBreakAction L224 实证）；距离门 isWithinBlockInteractionRange(pos,1.0)
    // = eye 到方块 AABB 的点距 < (4.5+1.0)²，候选筛用同式保守半径 5.0=必然通过。
    private int digsSent;
    /** 前 3 挖目标的 (x,y,z)×3，volatile 数组单次写发布——harness 窗后 air 探针锚。 */
    public volatile int[] firstDigTargets;

    // 批次87：分段计时——每个收到的包记录 (状态, packetId, 自 connect 起毫秒)
    private final java.util.List<String> phaseTrace = new java.util.ArrayList<>();

    public List<String> getPhaseTrace() {
        return this.phaseTrace;
    }

    // 批次141（R5）：服务器权威终位（harness 打印验证行走扩散真实生效）
    public double getPosX() { return this.posX; }
    public double getPosZ() { return this.posZ; }
    public double getPosY() { return this.posY; } // 批次200 PlaceVerify：支撑格几何锚定

    private void trace(final String state, final int packetId, final long t0) {
        this.phaseTrace.add(state + ":0x" + Integer.toHexString(packetId) + ":" + (System.nanoTime() - t0) / 1_000_000 + "ms");
    }

    // 批次141（R5）：带载荷长度的追踪（未压缩包长度可直接用于包型识别）
    void traceLen(final String state, final int packetId, final int payloadLen, final long t0) {
        this.phaseTrace.add(state + ":0x" + Integer.toHexString(packetId) + ":len=" + payloadLen + ":" + (System.nanoTime() - t0) / 1_000_000 + "ms");
    }

    public OfflineJoinBot(final String host, final int port, final String name) {
        this.host = host;
        this.port = port;
        this.name = name;
        this.offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** 返回 [connectMs, loginAckMs, spawnMs]（自 connect 起累计毫秒）。 */
    public long[] joinAndDisconnect(final long postSpawnDwellMs) throws IOException {
        final long[] t = this.doJoin(postSpawnDwellMs);
        this.socket.close();
        return t;
    }

    /** tracing 版：包级时间戳写入 {@link #getPhaseTrace()}。 */
    public long[] joinAndDisconnect(final long postSpawnDwellMs, final boolean tracing) throws IOException {
        this.phaseTrace.clear();
        final long[] t = this.doJoin(postSpawnDwellMs);
        this.socket.close();
        return t;
    }

    /**
     * 批次90：join 后在 play 阶段行走 durationMs（每 50ms 发 MOVE_PLAYER_POS 0x1D，
     * 响应 KEEP_ALIVE 0x2B/回 PING 0x3B），然后断开。dx/dzPerTick 为每步位移。
     */
    public long[] joinWalkAndDisconnect(final long durationMs, final double dxPerTick, final double dzPerTick) throws IOException {
        final long[] t = this.doJoin(100);
        this.walk(durationMs, dxPerTick, dzPerTick);
        this.socket.close();
        return t;
    }

    // Papo batch 141 (R5 内存浸泡)：行走扩散后在原地驻留（持续回 keepalive 保持连接与
    // 区块票据），供 harness 在驻留窗口内做 jcmd 堆测量——bot 断开会丢票据/卸区块。
    public long[] joinWalkDwellAndDisconnect(final long spreadMs, final double dxPerTick, final double dzPerTick,
                                             final long dwellMs) throws IOException {
        final long[] t = this.doJoin(100);
        this.walk(spreadMs, dxPerTick, dzPerTick);
        if (dwellMs > 0) {
            this.walk(dwellMs, 0, 0); // 原地踏步：同坐标位置包 + keepalive 排水
        }
        this.socket.close();
        return t;
    }

    // Papo batch 152（R12 长时生长审计）：同一径向往返循环行走——出腿把出发区域
    // 甩出视距（卸载），回腿重访（盘载重载路径），制造 加载→卸载→重载 周期，
    // 供 LongRunGrowth 按腿采样检测随周期累积的慢增长簇。
    public long[] joinWalkCyclesAndDisconnect(final long totalMs, final double dxPerTick, final double dzPerTick,
                                              final long legMs) throws IOException {
        final long[] t = this.doJoin(100);
        final long deadline = System.currentTimeMillis() + totalMs;
        boolean outbound = true;
        while (System.currentTimeMillis() < deadline) {
            final long left = deadline - System.currentTimeMillis();
            this.walk(Math.min(legMs, left), outbound ? dxPerTick : -dxPerTick, outbound ? dzPerTick : -dzPerTick);
            outbound = !outbound;
        }
        this.socket.close();
        return t;
    }

    // 批次189（R48）：爬升+撒布+挖掘风暴。相位①出生列垂直爬升 0.5/tick 至 climbToY
    // （spawn 上方空气柱；穿树叶等稀有障碍经 0x46 采纳收敛）；相位②剩余时长水平撒布
    // ——y=200 空中带纯空气 = creative 无 clip 纠偏（批次141 spectator 判例的 creative
    // 等价形态：creative 无 noPhysics 豁免不能穿山，就以不遇山的高度飞）。fillDone 由
    // harness 在 /fill+/say 标记后 countDown；dig=false 时同节拍驻留（keepalive 排水）
    // = 配对腿对称控制。
    public long[] joinClimbSpreadDigAndDisconnect(final long spreadMs, final double dxPerTick, final double dzPerTick,
                                                  final double climbToY, final java.util.concurrent.CountDownLatch fillDone,
                                                  final long digMs, final boolean dig) throws IOException {
        // 旧行为原样保留（R47-R58 腿语义）：dig=true 时全 digMs 段挖掘（挖尽转驻留）
        final long[] t = this.doJoin(100);
        this.walkClimbThenWalk(spreadMs, dxPerTick, dzPerTick, climbToY);
        this.awaitLatchDraining(fillDone, 120_000);
        if (dig) {
            this.digReachSphere(digMs);
        } else {
            this.walk(digMs, 0, 0);
        }
        this.socket.close();
        return t;
    }

    // 批次200（R59 放置轴）：place=true 时 dig 段挖尽后把挖掘立方表面外贴格逐格放回
    // （USE_ITEM_ON 放置链=R48 挖掘风暴的对偶轴；A 腿同节拍纯挖=挖掘面同量相消）。
    public long[] joinClimbSpreadDigAndDisconnect(final long spreadMs, final double dxPerTick, final double dzPerTick,
                                                  final double climbToY, final java.util.concurrent.CountDownLatch fillDone,
                                                  final long digMs, final boolean dig, final boolean place) throws IOException {
        final long[] t = this.doJoin(100);
        this.walkClimbThenWalk(spreadMs, dxPerTick, dzPerTick, climbToY);
        this.awaitLatchDraining(fillDone, 120_000);
        if (dig) {
            this.digReachSphere(digMs / 2);
        } else {
            this.walk(digMs, 0, 0); // 配对控制腿：同驻留节拍，零挖掘包
        }
        if (place && dig) {
            // dig/place 各半段：放置风暴紧跟挖尽段（~80s 挖+~20s 放回+各自驻留尾）
            this.setHeldStone();
            this.placeFaceStorm(digMs / 2);
        } else if (dig && !place) {
            // A 腿对称控制：纯挖腿的后半段同节拍驻留（挖掘面同量、放置面零）
            this.walk(digMs / 2, 0, 0);
        }
        this.socket.close();
        return t;
    }

    private void walkClimbThenWalk(final long durationMs, final double dxPerTick, final double dzPerTick,
                                   final double climbToY) throws IOException {
        final long knowBy = System.currentTimeMillis() + 10_000;
        this.socket.setSoTimeout(20);
        while (!this.posKnown) {
            if (System.currentTimeMillis() > knowBy) {
                throw new IOException("no PLAYER_POSITION sync within 10s");
            }
            this.drainPlayInbound();
            if (!this.posKnown) {
                try {
                    Thread.sleep(20);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        final long deadline = System.currentTimeMillis() + durationMs;
        while (this.posY < climbToY && System.currentTimeMillis() < deadline) {
            this.posY = Math.min(this.posY + 0.5, climbToY);
            this.sendMoveAndDrain();
        }
        while (System.currentTimeMillis() < deadline) {
            this.posX += dxPerTick;
            this.posZ += dzPerTick;
            this.sendMoveAndDrain();
        }
        this.socket.setSoTimeout(0);
    }

    private void sendMoveAndDrain() throws IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream(25);
        b.write(longBytes(Double.doubleToLongBits(this.posX)));
        b.write(longBytes(Double.doubleToLongBits(this.posY)));
        b.write(longBytes(Double.doubleToLongBits(this.posZ)));
        b.write(0x01); // onGround=true, horizontalCollision=false
        this.sendPacket(0x1D, b.toByteArray()); // MOVE_PLAYER_POS
        this.drainPlayInbound();
        try {
            Thread.sleep(50);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 挖一格（creative START 即时破坏）；direction=EAST(5)、sequence=0——creative 路径不消费。 */
    public void digBlock(final int x, final int y, final int z) throws IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream(16);
        writeVarint(b, 0); // START_DESTROY_BLOCK
        b.write(longBytes(blockPosAsLong(x, y, z)));
        b.write(5); // direction 3D data value
        writeVarint(b, 0); // sequence
        this.sendPacket(0x28, b.toByteArray());
        this.digsSent++;
    }

    private static long blockPosAsLong(final int x, final int y, final int z) {
        // BlockPos.asLong 源码逐字：((x & 0x3FFFFFF) << 38) | (y & 0xFFF) | ((z & 0x3FFFFFF) << 12)
        return ((x & 67108863L) << 38) | ((y & 4095L)) | ((z & 67108863L) << 12);
    }

    public int getDigsSent() { return this.digsSent; }

    // ---------- 批次200（R59 放置轴）：creative 手持 + USE_ITEM_ON 放置风暴 ----------

    private int placesSent = 0;
    private int placeSeq = 0;
    private int[] firstPlaceTargets;

    /**
     * SET_CREATIVE_MODE_SLOT（play id 0x37，GameProtocols 注册序权威提取——批次200
     * 判例：+10 偏移外推失效，common/ping/cookie 包在特定位置穿插，0x35 实为
     * SET_COMMAND_BLOCK/0x3d 实为 TELEPORT_TO_ENTITY，服务器日志踢因实锚）。
     * slot 36=hotbar 首格（默认选中槽 0）。ItemStack 线格式（ItemStack.
     * createOptionalStreamCodec）：varint count + varint itemId（vanilla flat 注册序
     * AIR=0/STONE=1）+ component patch（varint 0 ×2=空）。
     */
    public void setHeldStone() throws IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream(16);
        b.write((36 >>> 8) & 0xFF); // big-endian short slot
        b.write(36 & 0xFF);
        writeVarint(b, 1); // count
        writeVarint(b, 1); // minecraft:stone（flat 注册序）
        writeVarint(b, 0); // patch added = 0
        writeVarint(b, 0); // patch removed = 0
        this.sendPacket(0x37, b.toByteArray());
    }

    /**
     * 批次205：通用 creative 槽位武装（slot+flat itemId 参数化——批次205 判例：
     * flat id 以运行时注册序为准，源码声明序 dye 块族有 +16 漂移，须回显定位）。
     */
    public void setCreativeSlotItem(final int slot, final int itemId, final int count) throws IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream(16);
        b.write((slot >>> 8) & 0xFF);
        b.write(slot & 0xFF);
        writeVarint(b, count);
        writeVarint(b, itemId);
        writeVarint(b, 0); // patch added = 0
        writeVarint(b, 0); // patch removed = 0
        this.sendPacket(0x37, b.toByteArray());
    }

    /**
     * 批次205（R64 农业生长轴）：骨粉手持——BONE_MEAL flat 注册序 **1082**
     * （批次205 判例：源码声明序≠运行时注册序——dye 块族程序化注册漂移 +16，
     * 源码序 1066 实为 white_dye；GrowVerify 四槽候选回显定位：1081=black_dye/
     * 1082=bone_meal/1083=bone/1084=sugar。AIR=0/STONE=1 锚点只保序不保偏移，
     * 声明序 id 必须回显验证后才可用）。
     */
    public void setHeldBonemeal() throws IOException {
        this.setCreativeSlotItem(36, 1082, 1);
    }

    /**
     * USE_ITEM_ON（play id 0x3f，同上权威提取）：varint hand(0=主手)+long 支撑格
     * BlockPos.asLong 大端+varint direction+float×3 cursor+bool isInside+bool
     * isWorldBorderHit（FriendlyByteBuf.writeBlockHitResult 尾两布尔——批次200 判例：
     * 1.21.11 比旧版多 worldBorderHit 位，漏写致帧内多一字节解码错）+varint
     * sequence（递增）。
     */
    public void placeOnFace(final int x, final int y, final int z, final int direction) throws IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream(32);
        writeVarint(b, 0); // hand = MAIN_HAND
        b.write(longBytes(blockPosAsLong(x, y, z)));
        writeVarint(b, direction);
        b.write(intBytes(Float.floatToIntBits(0.5f))); // cursorX
        b.write(intBytes(Float.floatToIntBits(1.0f))); // cursorY
        b.write(intBytes(Float.floatToIntBits(0.5f))); // cursorZ
        b.write(0); // isInside = false
        b.write(0); // isWorldBorderHit = false
        writeVarint(b, ++this.placeSeq);
        this.sendPacket(0x3f, b.toByteArray());
        this.placesSent++;
    }

    public int getPlacesSent() { return this.placesSent; }

    public int[] getFirstPlaceTargets() { return this.firstPlaceTargets; }

    /**
     * 表面外贴放置风暴：挖掘立方（11×15×11，bx±5/y[FILL_Y-5,FILL_Y+9]/bz±5）四侧面
     * 外一格的放置目标（支撑=立方表面格恒 solid），按（距离,y,x,z）确定序逐 100ms
     * 放置至耗尽或截时；耗尽后 keepalive 驻留。首 3 目标写 firstPlaceTargets
     * （窗后探针锚）。距离门对支撑格（R48 digReachSphere 同式保守 5.0 半径）。
     */
    private void placeFaceStorm(final long digMs) throws IOException {
        final double ex = this.posX, ey = this.posY + 1.62, ez = this.posZ;
        final int bx = (int) Math.floor(this.posX), by = (int) Math.floor(this.posY), bz = (int) Math.floor(this.posZ);
        record PCand(double d2, int tx, int ty, int tz, int sx, int sy, int sz, int dir) {}
        final java.util.List<PCand> cands = new java.util.ArrayList<>(512);
        final int[][] faces = {
            {0, 0, -1, 2},  // 北：支撑面 z=bz-5，目标 z-1 格，direction=NORTH(2)
            {0, 0, 1, 3},   // 南：支撑面 z=bz+5，direction=SOUTH(3)
            {-1, 0, 0, 4},  // 西：支撑面 x=bx-5，direction=WEST(4)
            {1, 0, 0, 5},   // 东：支撑面 x=bx+5，direction=EAST(5)
        };
        for (final int[] f : faces) {
            for (int dy = -5; dy <= 9; dy++) {
                for (int u = -5; u <= 5; u++) {
                    final int cx = bx + (f[0] != 0 ? f[0] * 5 : u);
                    final int cz = bz + (f[2] != 0 ? f[2] * 5 : u);
                    final int cy = by + dy;
                    final double ddx = Math.max(cx - ex, Math.max(0.0, ex - (cx + 1)));
                    final double ddy = Math.max(cy - ey, Math.max(0.0, ey - (cy + 1)));
                    final double ddz = Math.max(cz - ez, Math.max(0.0, ez - (cz + 1)));
                    final double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (d2 <= 25.0) {
                        cands.add(new PCand(d2, cx + f[0], cy, cz + f[2], cx, cy, cz, f[3]));
                    }
                }
            }
        }
        cands.sort((a, c) -> a.d2 != c.d2 ? Double.compare(a.d2, c.d2)
            : a.ty != c.ty ? Integer.compare(a.ty, c.ty)
            : a.tx != c.tx ? Integer.compare(a.tx, c.tx) : Integer.compare(a.tz, c.tz));
        final long deadline = System.currentTimeMillis() + digMs;
        int i = 0;
        this.socket.setSoTimeout(20);
        while (System.currentTimeMillis() < deadline) {
            if (i < cands.size()) {
                final PCand c = cands.get(i);
                if (i < 3) {
                    System.out.println("PLACETGT[" + this.name + "] #" + i + " x=" + c.tx + " y=" + c.ty + " z=" + c.tz);
                }
                this.placeOnFace(c.sx, c.sy, c.sz, c.dir);
                if (i == 2) {
                    // 发满 3 个即锚定（原 i==3 处锚定在早死时丢锚——批次200 腿 B 判例）。
                    // 18 元组=[target(tx,ty,tz)+support(sx,sy,sz)]×3：探针③查支撑格
                    // （replaceClicked 语义：挖空面格时石头落支撑格自身）。
                    final PCand c0 = cands.get(0), c1 = cands.get(1), c2 = cands.get(2);
                    this.firstPlaceTargets = new int[]{
                        c0.tx, c0.ty, c0.tz, c0.sx, c0.sy, c0.sz,
                        c1.tx, c1.ty, c1.tz, c1.sx, c1.sy, c1.sz,
                        c2.tx, c2.ty, c2.tz, c2.sx, c2.sy, c2.sz};
                }
                i++;
            }
            this.drainPlayInbound();
            try {
                Thread.sleep(i < cands.size() ? 100 : 50);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                this.socket.setSoTimeout(0);
                return;
            }
        }
        System.out.println("PLACEDONE[" + this.name + "] sent=" + i + " total=" + cands.size());
        this.socket.setSoTimeout(0);
    }

    private static byte[] intBytes(final int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /**
     * 挖掘风暴：以眼位为心的可达球候选（点-AABB 距 ≤5.0²，与服务端距离门同式但半径
     * 保守 0.5 边距=每个包必然过门），按（距离,y,x,z）确定序逐格 100ms 一挖，至耗尽
     * 或 digMs 截止；耗尽后 keepalive 驻留至 digMs（测量轴前 bot 保持在线=区块票据）。
     * 首 3 目标写 {@link #firstDigTargets}（harness 窗后 air 探针正证据锚）。
     */
    private void digReachSphere(final long digMs) throws IOException {
        final double ex = this.posX, ey = this.posY + 1.62, ez = this.posZ;
        final int bx = (int) Math.floor(this.posX), by = (int) Math.floor(this.posY), bz = (int) Math.floor(this.posZ);
        record Cand(double d2, int x, int y, int z) {}
        final java.util.List<Cand> cands = new java.util.ArrayList<>(729);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 6; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    final int cx = bx + dx, cy = by + dy, cz = bz + dz;
                    final double ddx = Math.max(cx - ex, Math.max(0.0, ex - (cx + 1)));
                    final double ddy = Math.max(cy - ey, Math.max(0.0, ey - (cy + 1)));
                    final double ddz = Math.max(cz - ez, Math.max(0.0, ez - (cz + 1)));
                    final double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (d2 <= 25.0) {
                        cands.add(new Cand(d2, cx, cy, cz));
                    }
                }
            }
        }
        cands.sort((a, c) -> a.d2 != c.d2 ? Double.compare(a.d2, c.d2)
            : a.y != c.y ? Integer.compare(a.y, c.y)
            : a.x != c.x ? Integer.compare(a.x, c.x) : Integer.compare(a.z, c.z));
        final long deadline = System.currentTimeMillis() + digMs;
        int i = 0;
        this.socket.setSoTimeout(20);
        while (System.currentTimeMillis() < deadline) {
            if (i < cands.size()) {
                final Cand c = cands.get(i);
                if (i < 3) {
                    System.out.println("DIGTGT[" + this.name + "] #" + i + " x=" + c.x + " y=" + c.y + " z=" + c.z);
                }
                this.digBlock(c.x, c.y, c.z);
                i++;
                if (i == 3) { // 第 3 挖完成即发布（探针② marker+90 早于挖掘耗尽）
                    final Cand c0 = cands.get(0), c1 = cands.get(1), c2 = cands.get(2);
                    this.firstDigTargets = new int[]{c0.x, c0.y, c0.z, c1.x, c1.y, c1.z, c2.x, c2.y, c2.z};
                }
            }
            this.drainPlayInbound();
            try {
                Thread.sleep(i < cands.size() ? 100 : 50); // 挖尽后转驻留节拍
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                this.socket.setSoTimeout(0);
                return;
            }
        }
        System.out.println("DIGDONE[" + this.name + "] sent=" + i + " total=" + cands.size());
        this.socket.setSoTimeout(0);
    }

    /** 等 harness fill 闩，期间 keepalive/纠偏排水维持连接。 */
    private void awaitLatchDraining(final java.util.concurrent.CountDownLatch latch, final long timeoutMs) throws IOException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        this.socket.setSoTimeout(20);
        while (latch.getCount() > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("fill latch timeout");
            }
            this.drainPlayInbound();
            try {
                latch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        this.socket.setSoTimeout(0);
    }

    private void walk(final long durationMs, final double dxPerTick, final double dzPerTick) throws IOException {
        // 批次141（R5）：等待首个位置同步（placeNewPlayer 后服务端必发 0x45）
        final long knowBy = System.currentTimeMillis() + 10_000;
        this.socket.setSoTimeout(20);
        while (!this.posKnown) {
            if (System.currentTimeMillis() > knowBy) {
                throw new IOException("no PLAYER_POSITION sync within 10s");
            }
            this.drainPlayInbound();
            if (!this.posKnown) {
                try {
                    Thread.sleep(20);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        final long deadline = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < deadline) {
            // 从（可能被服务端纠偏过的）当前坐标推进，碰撞/纠偏经 0x46 采纳后自然收敛；
            // gamemode=spectator：noPhysics 豁免 moved-wrongly/CLIPPED_INTO_BLOCK，y 恒定
            // 直穿地形（批次141 判例：survival 下 +0.5/tick 爬升撞山面必触发纠偏循环）
            this.posX += dxPerTick;
            this.posZ += dzPerTick;
            if (this.traceMoves < 6) { // 诊断探针（批次141）：前 6 个移动包坐标
                System.out.println("TRACE[" + this.name + "] move#" + this.traceMoves
                    + " x=" + this.posX + " y=" + this.posY + " z=" + this.posZ);
                this.traceMoves++;
            }
            final ByteArrayOutputStream b = new ByteArrayOutputStream(25);
            b.write(longBytes(Double.doubleToLongBits(this.posX)));
            b.write(longBytes(Double.doubleToLongBits(this.posY)));
            b.write(longBytes(Double.doubleToLongBits(this.posZ)));
            b.write(0x01); // onGround=true, horizontalCollision=false
            this.sendPacket(0x1D, b.toByteArray()); // MOVE_PLAYER_POS
            this.drainPlayInbound();
            try {
                Thread.sleep(50);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        this.socket.setSoTimeout(0);
    }

    /** 非阻塞处理入站 keepalive/ping/位置同步（其他包跳读）。返回是否为已识别包。 */
    private boolean handlePlayFrame(final Frame f) throws IOException {
        switch (f.packetId()) {
            case 0x2B -> this.sendPacket(0x1B, f.payload); // keepalive echo（long）
            case 0x3B -> this.sendPacket(0x2C, f.payload); // ping → pong（int；0x2C=权威注册序，0x2b 实为 PLAYER_LOADED）
            case 0x46 -> { // PLAYER_POSITION：采纳坐标 + 回传送确认（id=0x46 经验实证：
                // 注册序推算 0x45 差一，实测 join burst 中唯一 len=61 的包恰为 varint+48B+8B+4B 结构）
                if (this.traceHex < 8) { // 诊断探针（批次141）：前 8 个 0x46 原样十六进制转储
                    System.out.println("TRACE[" + this.name + "] 0x46 len=" + f.payload().length + " hex=" + hex(f.payload()));
                }
                this.traceHex++;
                final int[] p = {0};
                final int teleportId = readVarintAt(f.payload(), p);
                final double x = Double.longBitsToDouble(readLongAt(f.payload(), p));
                final double y = Double.longBitsToDouble(readLongAt(f.payload(), p));
                final double z = Double.longBitsToDouble(readLongAt(f.payload(), p));
                readLongAt(f.payload(), p); // deltaMovement x/y/z 不消费
                readLongAt(f.payload(), p);
                readLongAt(f.payload(), p);
                p[0] += 4; // yaw float
                p[0] += 4; // pitch float
                int mask = 0;
                for (int i = 3; i >= 0; i--) { // int 大端
                    mask = (mask << 8) | (f.payload()[p[0] + i] & 0xFF);
                }
                if ((mask & 0x01) != 0) { this.posX += x; } else { this.posX = x; }
                if ((mask & 0x02) != 0) { this.posY += y; } else { this.posY = y; }
                if ((mask & 0x04) != 0) { this.posZ += z; } else { this.posZ = z; }
                this.posKnown = true;
                final ByteArrayOutputStream acc = new ByteArrayOutputStream(6);
                writeVarint(acc, teleportId);
                this.sendPacket(0x00, acc.toByteArray()); // ACCEPT_TELEPORTATION
            }
            default -> { return false; }
        }
        return true;
    }

    private static String hex(final byte[] data) { // 诊断探针（批次141）
        final StringBuilder sb = new StringBuilder(data.length * 3);
        for (final byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private static long readLongAt(final byte[] data, final int[] pos) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[pos[0] + i] & 0xFFL);
        }
        pos[0] += 8;
        return v;
    }

    /** 非阻塞处理入站 keepalive/ping（其他包跳读）。 */
    private void drainPlayInbound() {
        try {
            while (true) {
                final Frame f = this.readFrame();
                this.handlePlayFrame(f);
            }
        } catch (final Exception expected) {
            // SocketTimeout / EOF（EOF=被断开，由调用方发现）
        }
    }

    private static byte[] longBytes(final long v) {
        // 大端（网络序）：Minecraft FriendlyByteBuf.writeLong/writeDouble 语义，
        // 批次141 判例：此处曾按 b[i]=v>>>(8*i) 产出小端，服务端按大端解码得次正规数
        // ≈0.0 —— 三个坐标恒 (0,0,0)，"moved too quickly" 每tick纠偏回出生点的根因
        final byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (v >>> (8 * (7 - i)));
        }
        return b;
    }

    private long[] doJoin(final long postSpawnDwellMs) throws IOException {
        final long t0 = System.nanoTime();
        this.socket = new Socket(this.host, this.port);
        this.socket.setTcpNoDelay(true);
        this.in = this.socket.getInputStream();
        this.out = this.socket.getOutputStream();

        // Handshake(protocol, host, port, nextState=LOGIN)
        final byte[] hostB = this.host.getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream hs = new ByteArrayOutputStream();
        writeVarint(hs, PROTOCOL_VERSION);
        writeVarint(hs, hostB.length);
        hs.writeBytes(hostB);
        hs.write((this.port >>> 8) & 0xFF);
        hs.write(this.port & 0xFF);
        writeVarint(hs, 2);
        sendRaw(0x00, hs.toByteArray());

        // Login Start
        final ByteArrayOutputStream ls = new ByteArrayOutputStream();
        final byte[] nameB = this.name.getBytes(StandardCharsets.UTF_8);
        writeVarint(ls, nameB.length);
        ls.writeBytes(nameB);
        writeUuid(ls, this.offlineUuid);
        sendRaw(0x00, ls.toByteArray());
        final long tLoginStart = System.nanoTime();

        // --- LOGIN 状态 ---
        boolean loggedIn = false;
        while (!loggedIn) {
            final Frame f = this.readFrame();
            this.trace("LOGIN", f.packetId(), t0);
            switch (f.packetId) {
                case 0x03 -> { // LoginCompression
                    this.compressionThreshold = readVarintBytes(f.payload, 0);
                }
                case 0x02 -> { // LoginFinished
                    sendPacket(0x03, new byte[0]); // LoginAcknowledged
                    loggedIn = true;
                }
                case 0x00 -> throw new IOException("login disconnect: " + readRestString(f));
                default -> { }
            }
        }
        final long tLoginAck = System.nanoTime();

        // --- CONFIGURATION 状态 ---
        boolean finished = false;
        while (!finished) {
            final Frame f = this.readFrame();
            this.trace("CONFIG", f.packetId(), t0);
            switch (f.packetId) {
                case 0x0E -> sendPacket(0x07, new byte[]{0x00}); // SelectKnownPacks: 空表（服务端发全量 NBT，bot 只跳读）
                case 0x04 -> sendPacket(0x04, f.payload); // KeepAlive 回显 long
                case 0x03 -> { // FinishConfiguration
                    sendPacket(0x03, new byte[0]);
                    finished = true;
                }
                case 0x02 -> throw new IOException("config disconnect: " + readRestString(f));
                default -> { }
            }
        }

        // --- PLAY 状态：首个 play 包 = placeNewPlayer 完成 ---
        final Frame firstPlay = this.readFrame();
        this.traceLen("PLAY", firstPlay.packetId(), firstPlay.payload().length, t0);
        final long tSpawn = System.nanoTime();
        if (firstPlay == null) {
            throw new EOFException("no play packet after configuration");
        }
        // 批次89：再追 12 个 play 包（5ms SO_TIMEOUT 短读非阻塞形状），看 join 突发的发送形状
        try {
            this.socket.setSoTimeout(5);
            for (int i = 0; i < 12; i++) {
                final Frame f = this.readFrame();
                this.traceLen("PLAY", f.packetId(), f.payload().length, t0);
                this.handlePlayFrame(f); // 批次141（R5）：首个位置同步在此窗口内即被采纳
            }
        } catch (final java.net.SocketTimeoutException expected) {
            // 突发读完（或 5ms 无更多）——正常
        } finally {
            this.socket.setSoTimeout(0);
        }

        // 停留（触发可能的 keepalive/区块发送），不回 play keepalive（停留远小于 30s 超时）
        try {
            Thread.sleep(postSpawnDwellMs);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 注意：不在此关闭 socket——由各入口（joinAndDisconnect/joinWalkAndDisconnect）关闭

        return new long[]{
            (tLoginStart - t0) / 1_000_000,
            (tLoginAck - t0) / 1_000_000,
            (tSpawn - t0) / 1_000_000,
        };
    }

    // ---------- framing ----------

    private record Frame(int packetId, byte[] payload) {}

    private Frame readFrame() throws IOException {
        final int len = readVarint(this.in);
        if (len < 0 || len > 64 * 1024 * 1024) {
            throw new IOException("bad frame length " + len);
        }
        byte[] data = this.in.readNBytes(len);
        if (data.length != len) {
            throw new EOFException("short frame: " + data.length + "/" + len);
        }
        if (this.compressionThreshold >= 0) {
            final int[] pos = {0};
            final int dataLen = readVarintAt(data, pos);
            if (dataLen != 0) {
                data = inflate(data, pos[0], dataLen);
            } else {
                data = java.util.Arrays.copyOfRange(data, pos[0], data.length);
            }
        }
        final int[] pos = {0};
        final int id = readVarintAt(data, pos);
        return new Frame(id, java.util.Arrays.copyOfRange(data, pos[0], data.length));
    }

    private void sendRaw(final int packetId, final byte[] payload) throws IOException {
        final byte[] frameBytes;
        synchronized (this.papoSendLock) {
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeVarint(body, packetId);
            body.writeBytes(payload);
            final byte[] bodyB = body.toByteArray();
            final ByteArrayOutputStream frame = new ByteArrayOutputStream();
            writeVarint(frame, bodyB.length);
            frame.writeBytes(bodyB);
            frameBytes = frame.toByteArray();
            this.out.write(frameBytes);
            this.out.flush();
        }
    }

    private void sendPacket(final int packetId, final byte[] payload) throws IOException {
        if (this.compressionThreshold < 0 || payload.length < this.compressionThreshold) {
            // 未压缩帧：data-length = 0
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeVarint(body, 0);
            writeVarint(body, packetId);
            body.writeBytes(payload);
            final byte[] bodyB = body.toByteArray();
            final ByteArrayOutputStream frame = new ByteArrayOutputStream();
            writeVarint(frame, bodyB.length);
            frame.writeBytes(bodyB);
            synchronized (this.papoSendLock) {
                this.out.write(frame.toByteArray());
                this.out.flush();
            }
        } else {
            throw new IOException("bot payload >= threshold not implemented");
        }
    }

    // ---------- primitives ----------

    private static void writeVarint(final ByteArrayOutputStream bos, final int value) {
        int v = value;
        while (true) {
            if ((v & ~0x7F) == 0) {
                bos.write(v);
                return;
            }
            bos.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }

    private static void writeUuid(final ByteArrayOutputStream bos, final UUID uuid) {
        final long msb = uuid.getMostSignificantBits();
        final long lsb = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            bos.write((int) (msb >>> (8 * i)) & 0xFF);
        }
        for (int i = 7; i >= 0; i--) {
            bos.write((int) (lsb >>> (8 * i)) & 0xFF);
        }
    }

    private static int readVarint(final InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        int current;
        while ((current = in.read()) != -1) {
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("varint too big");
            }
        }
        throw new EOFException("eof in varint");
    }

    private static int readVarintAt(final byte[] data, final int[] pos) {
        int value = 0;
        int position = 0;
        int current;
        do {
            current = data[pos[0]++];
            value |= (current & 0x7F) << position;
            position += 7;
        } while ((current & 0x80) != 0);
        return value;
    }

    private static int readVarintBytes(final byte[] data, final int offset) {
        final int[] pos = {offset};
        return readVarintAt(data, pos);
    }

    private static byte[] inflate(final byte[] data, final int offset, final int expected) throws IOException {
        final Inflater inflater = new Inflater(false);
        inflater.setInput(data, offset, data.length - offset);
        final byte[] out = new byte[expected];
        int outPos = 0;
        try {
            while (!inflater.finished() && outPos < expected) {
                final int n = inflater.inflate(out, outPos, expected - outPos);
                if (n == 0 && inflater.needsInput()) {
                    throw new EOFException("truncated compressed payload");
                }
                outPos += n;
            }
        } catch (final java.util.zip.DataFormatException e) {
            throw new IOException("bad compressed payload", e);
        } finally {
            inflater.end();
        }
        if (outPos != expected) {
            throw new IOException("decompressed size mismatch: " + outPos + " != " + expected);
        }
        return out;
    }

    /** 断连原因（Chat 组件）粗读：全 payload 当字符串供错误信息用。 */
    private static String readRestString(final Frame f) {
        try {
            final java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(f.payload);
            final int[] pos = {0};
            final int len = readVarintAt(f.payload, pos);
            bis.skip(pos[0]);
            return new String(f.payload, pos[0], Math.min(len, f.payload.length - pos[0]), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return "<unreadable>";
        }
    }
}
