package net.minecraft.nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 171 (R30): the papoReadUtf rewrite - thread-local read scratch plus
// a bounded direct-mapped dedup cache for short ASCII strings. The JDK modified-UTF-8
// codec (DataOutputStream.writeUTF / DataInputStream.readUTF) is the oracle: every
// string class the function can see must round-trip to an equal value, including the
// non-ASCII/NUL fallback path, length boundaries around the scratch size and the dedup
// cutoff, and strings that repeat (dedup must return an identical-equal instance) or
// collide in the bounded cache (correct value, latest entry wins per slot).
public class PapoReadUtfIdentityTest {

    private static byte[] utfBytes(final String value) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.writeUTF(value);
        }
        return out.toByteArray();
    }

    private static String read(final String value) throws Exception {
        final byte[] bytes = utfBytes(value);
        return CompoundTag.papoReadUtf(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static String reference(final String value) throws Exception {
        return new DataInputStream(new ByteArrayInputStream(utfBytes(value))).readUTF();
    }

    @Test
    void asciiRoundTripsAgainstJdkCodec() throws Exception {
        final Random random = new Random(0xFEED);
        // lengths across the dedup cutoff (48), the initial scratch (8192) and one past it
        for (final int length : new int[] {0, 1, 2, 47, 48, 49, 100, 8190, 8191, 8192, 8193, 12000}) {
            final StringBuilder builder = new StringBuilder();
            for (int i = 0; i < length; ++i) {
                builder.append((char) ('!' + random.nextInt(90))); // printable ASCII, no NUL
            }
            final String value = builder.toString();
            Assertions.assertEquals(reference(value), read(value), "ASCII len=" + length);
        }
    }

    @Test
    void modifiedUtf8FallbackMatchesJdkCodec() throws Exception {
        for (final String value : new String[] {
            "é", "常用方块", "ß𝄞 mixed ascii", "a\u0000b", "\u0000", "emoji😀end", "xé".repeat(24)
        }) {
            Assertions.assertEquals(reference(value), read(value), "fallback: " + value);
        }
        // fallback immediately after a long ASCII string: the scratch is grown and the
        // in-place prefix shift must not corrupt the read
        Assertions.assertEquals("suffixé", read("suffixé"));
    }

    @Test
    void repeatedShortStringsShareOneInstance() throws Exception {
        final String key = "aVeryRepeatedNbtKey";
        final String first = read(key);
        final String second = read(key);
        Assertions.assertEquals(key, first);
        Assertions.assertSame(first, second, "second read of the same short ASCII string must dedup");
    }

    @Test
    void longStringsBypassDedupButStayEqual() throws Exception {
        final String longKey = "L".repeat(60); // > 48: bypasses the cache
        final String first = read(longKey);
        final String second = read(longKey);
        Assertions.assertEquals(longKey, first);
        Assertions.assertNotSame(first, second, "strings past the dedup cutoff must not be pooled");
    }

    @Test
    void boundedCacheReturnsCorrectValueUnderReplacement() throws Exception {
        // fill the cache with many distinct short strings so slots get replaced, then
        // re-read every one: each must still decode to its exact value (a miss after
        // replacement allocates fresh - never a wrong shared instance)
        final String[] values = new String[8192];
        final Random random = new Random(0xBEEF);
        for (int i = 0; i < values.length; ++i) {
            values[i] = "k" + random.nextInt(1_000_000_000);
            Assertions.assertEquals(values[i], read(values[i]), "first pass " + i);
        }
        for (int i = 0; i < values.length; ++i) {
            Assertions.assertEquals(values[i], read(values[i]), "second pass " + i);
        }
    }

    @Test
    void emptyStringIsConstant() throws Exception {
        Assertions.assertSame("", read(""));
        Assertions.assertSame("", read(""));
    }
}
