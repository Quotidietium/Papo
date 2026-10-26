package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - compat regression test: the shared wire-compression memo must be CONTENT-verified.
// The memo keys on packet-instance identity; if anything mutates the packet between the fill and
// a later replay (packet-editing plugin rewriting a shared instance per viewer), the current
// connection's bytes differ and the replay must miss - that connection compresses its own bytes.
// This pins the (threshold, length, content-hash) stamp contract.
public class PapoSharedWireMemoContentTest {

    private static ByteBuf bytes(final String s) {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeBytes(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return buf;
    }

    @Test
    void replayMatchesOnlyForIdenticalContent() {
        final PapoSharedWireMemo memo = PapoSharedWireMemo.papoCreate();
        final ByteBuf original = bytes("chunk payload");
        final long originalHash = PapoSharedWireMemo.papoHashBytes(original);

        memo.papoStore(256, original.readableBytes(), originalHash, new byte[] {1, 2, 3});

        Assertions.assertNotNull(memo.papoSegmentFor(256, original.readableBytes(), PapoSharedWireMemo.papoHashBytes(bytes("chunk payload"))),
            "identical content must hit");
        Assertions.assertNull(memo.papoSegmentFor(256, original.readableBytes(), PapoSharedWireMemo.papoHashBytes(bytes("chunk payloax"))),
            "mutated content (same length) must miss - this is the plugin-rewrite guard");
        Assertions.assertNull(memo.papoSegmentFor(256, original.readableBytes() + 1, PapoSharedWireMemo.papoHashBytes(bytes("chunk payload!"))),
            "different length must miss");
        Assertions.assertNull(memo.papoSegmentFor(512, original.readableBytes(), originalHash),
            "different threshold must miss");
        original.release();
    }

    @Test
    void hashIsIndexNeutral() {
        final ByteBuf buf = bytes("abcdefgh");
        final long before = PapoSharedWireMemo.papoHashBytes(buf);
        Assertions.assertEquals(0, buf.readerIndex(), "hash must not move readerIndex");
        Assertions.assertEquals(buf.writerIndex(), buf.capacity(), "hash must not move writerIndex");
        Assertions.assertEquals(before, PapoSharedWireMemo.papoHashBytes(buf), "hash must be stable");
        buf.release();
    }

    @Test
    void refillAfterMismatchOverwritesStaleSegment() {
        final PapoSharedWireMemo memo = PapoSharedWireMemo.papoCreate();
        memo.papoStore(256, 4, PapoSharedWireMemo.papoHashBytes(bytes("aaaa")), new byte[] {1});
        // a viewer with different content misses...
        Assertions.assertNull(memo.papoSegmentFor(256, 4, PapoSharedWireMemo.papoHashBytes(bytes("bbbb"))));
        // ...and its fill (same stamp family, different identity) replaces the stale segment
        memo.papoStore(256, 4, PapoSharedWireMemo.papoHashBytes(bytes("bbbb")), new byte[] {2});
        Assertions.assertNotNull(memo.papoSegmentFor(256, 4, PapoSharedWireMemo.papoHashBytes(bytes("bbbb"))));
        Assertions.assertNull(memo.papoSegmentFor(256, 4, PapoSharedWireMemo.papoHashBytes(bytes("aaaa"))));
    }
}
