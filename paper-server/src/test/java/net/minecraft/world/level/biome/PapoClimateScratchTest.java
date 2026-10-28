package net.minecraft.world.level.biome;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Climate.Parameter;
import net.minecraft.world.level.biome.Climate.ParameterPoint;
import net.minecraft.world.level.biome.Climate.TargetPoint;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 174 (R33): ParameterList.search fills a shared thread-local long[7]
// scratch instead of allocating toParameterArray() per query. The oracle is the
// @VisibleForTesting findValueBruteForce - identical inputs must keep selecting the
// nearest parameter point across interleaved queries (the scratch is overwritten every
// call, so staleness would show up as wrong results on re-query), across multiple
// ParameterList instances (the scratch is static/shared), and across different metrics.
public class PapoClimateScratchTest {

    private static final Holder<Biome> A = Holder.direct(null);
    private static final Holder<Biome> B = Holder.direct(null);

    private static ParameterPoint point(final long t, final long h, final long c, final long e, final long d, final long w) {
        return new ParameterPoint(
            new Parameter(t, t), new Parameter(h, h), new Parameter(c, c), new Parameter(e, e), new Parameter(d, d), new Parameter(w, w), 0L
        );
    }

    private static Climate.ParameterList<Holder<Biome>> list() {
        return new Climate.ParameterList<>(List.of(
            new com.mojang.datafixers.util.Pair<>(point(0L, 0L, 0L, 0L, 0L, 0L), A),
            new com.mojang.datafixers.util.Pair<>(point(10_000L, 10_000L, 10_000L, 10_000L, 10_000L, 10_000L), B)
        ));
    }

    @Test
    void searchMatchesBruteForceAcrossInterleavedQueries() {
        final Climate.ParameterList<Holder<Biome>> index = list();
        final Climate.ParameterList<Holder<Biome>> other = list();

        final TargetPoint nearA = new TargetPoint(100L, 100L, 100L, 100L, 100L, 100L);
        final TargetPoint nearB = new TargetPoint(9_900L, 9_900L, 9_900L, 9_900L, 9_900L, 9_900L);
        // asymmetric so no query is equidistant to both entries - the tree search and
        // the brute-force oracle must otherwise break exact ties independently
        final TargetPoint middle = new TargetPoint(5_500L, 4_500L, 5_500L, 4_500L, 5_500L, 4_000L);

        // interleave across instances and points - the shared static scratch must never
        // leak a previous query's coordinates into a result
        for (int i = 0; i < 32; ++i) {
            for (final TargetPoint p : new TargetPoint[] {nearA, nearB, middle, nearA, nearB}) {
                Assertions.assertSame(index.findValue(p), index.findValueBruteForce(p), "round " + i);
                Assertions.assertSame(other.findValue(p), other.findValueBruteForce(p), "other round " + i);
            }
        }
        Assertions.assertSame(A, index.findValue(nearA));
        Assertions.assertSame(B, index.findValue(nearB));
    }
}
