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
    private int compressionThreshold = -1;

    // 批次87：分段计时——每个收到的包记录 (状态, packetId, 自 connect 起毫秒)
    private final java.util.List<String> phaseTrace = new java.util.ArrayList<>();

    public List<String> getPhaseTrace() {
        return this.phaseTrace;
    }

    private void trace(final String state, final int packetId, final long t0) {
        this.phaseTrace.add(state + ":0x" + Integer.toHexString(packetId) + ":" + (System.nanoTime() - t0) / 1_000_000 + "ms");
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

    private void walk(final long durationMs, final double dxPerTick, final double dzPerTick) throws IOException {
        double x = 0.5, y = 100.0, z = 0.5;
        final long deadline = System.currentTimeMillis() + durationMs;
        this.socket.setSoTimeout(20); // 短超时读入站（keepalive/ping）
        while (System.currentTimeMillis() < deadline) {
            x += dxPerTick;
            z += dzPerTick;
            final ByteArrayOutputStream b = new ByteArrayOutputStream(25);
            b.write(longBytes(Double.doubleToLongBits(x)));
            b.write(longBytes(Double.doubleToLongBits(y)));
            b.write(longBytes(Double.doubleToLongBits(z)));
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

    /** 非阻塞处理入站 keepalive/ping（其他包跳读）。 */
    private void drainPlayInbound() {
        try {
            while (true) {
                final Frame f = this.readFrame();
                if (frameLog != null && WATCHED_FRAMES.contains(f.packetId)) {
                    // 批次118：容器线序观测（ID+相对毫秒+载荷字节）——闪回机制的线级实证
                    frameLog.add(String.format(java.util.Locale.ROOT, "0x%02X,%d,%d",
                        f.packetId, (System.nanoTime() - frameLogT0) / 1_000_000, f.payload.length));
                }
                switch (f.packetId) {
                    case 0x2B -> this.sendPacket(0x1B, f.payload); // keepalive echo（long）
                    case 0x3B -> this.sendPacket(0x2B, f.payload); // ping → pong（int）
                    default -> { }
                }
            }
        } catch (final Exception expected) {
            // SocketTimeout / EOF（EOF=被断开，由调用方发现）
        }
    }

    // Papo start - batch 118: optional clientbound frame log (container wire-sequence observation)
    /** 观测帧集合：CLOSE=0x11 SET_CONTENT=0x12 SET_SLOT=0x14 OPEN_SCREEN=0x39（GameProtocols 注册序+1 偏移，keepalive 0x2B/ping 0x3B 双实证校验）。 */
    public static final java.util.Set<Integer> WATCHED_FRAMES = java.util.Set.of(0x11, 0x12, 0x14, 0x39);
    public volatile java.util.List<String> frameLog;
    public volatile long frameLogT0;

    /** 开启帧日志（容器相关 clientbound 包：ID/相对毫秒/字节数）。 */
    public void startFrameLog() {
        this.frameLog = java.util.Collections.synchronizedList(new java.util.ArrayList<>(4096));
        this.frameLogT0 = System.nanoTime();
    }
    // Papo end - batch 118

    private static byte[] longBytes(final long v) {
        final byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (v >>> (8 * i));
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
        this.trace("PLAY", firstPlay.packetId(), t0);
        final long tSpawn = System.nanoTime();
        if (firstPlay == null) {
            throw new EOFException("no play packet after configuration");
        }
        // 批次89：再追 12 个 play 包（5ms SO_TIMEOUT 短读非阻塞形状），看 join 突发的发送形状
        try {
            this.socket.setSoTimeout(5);
            for (int i = 0; i < 12; i++) {
                final Frame f = this.readFrame();
                this.trace("PLAY", f.packetId(), t0);
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
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarint(body, packetId);
        body.writeBytes(payload);
        final byte[] bodyB = body.toByteArray();
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarint(frame, bodyB.length);
        frame.writeBytes(bodyB);
        this.out.write(frame.toByteArray());
        this.out.flush();
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
            this.out.write(frame.toByteArray());
            this.out.flush();
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
