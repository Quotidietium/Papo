package net.minecraft.util;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 175 (R34): Multipoint.mapAll rebuilds its children via
// values().stream().map(...).toList() on every density-tree rebuild (each generated
// chunk rewraps the tree; 335 census trips for the stream machinery + 124 for
// create()'s enhanced-for iterator). It now fills a plain ArrayList in the same
// order. The oracle here is the legacy stream form, asserted at parityString /
// minValue / maxValue / apply level, including double composition - the second
// mapAll runs over a result whose values list is the new ArrayList, proving the
// list-implementation change is unobservable to every consumer.
public class PapoCubicSplineMapAllTest {

    private static final class CoordFunc implements BoundedFloatFunction<Float> {
        final float scale;
        final float offset;

        CoordFunc(final float scale, final float offset) {
            this.scale = scale;
            this.offset = offset;
        }

        @Override
        public float apply(final Float value) {
            return this.scale * value + this.offset;
        }

        @Override
        public float minValue() {
            return Float.NEGATIVE_INFINITY;
        }

        @Override
        public float maxValue() {
            return Float.POSITIVE_INFINITY;
        }

        @Override
        public String toString() {
            return "coord[scale=" + this.scale + ", offset=" + this.offset + "]";
        }
    }

    private static CubicSpline<Float, CoordFunc> constant(final float value) {
        return CubicSpline.constant(value);
    }

    // the legacy vanilla form, verbatim, as the identity oracle
    private static CubicSpline<Float, CoordFunc> legacyMapAll(
        final CubicSpline.Multipoint<Float, CoordFunc> multipoint, final CubicSpline.CoordinateVisitor<CoordFunc> visitor
    ) {
        return CubicSpline.Multipoint.create(
            visitor.visit(multipoint.coordinate()),
            multipoint.locations(),
            multipoint.values().stream().map(cubicSpline -> cubicSpline.mapAll(visitor)).toList(),
            multipoint.derivatives()
        );
    }

    private static CubicSpline.Multipoint<Float, CoordFunc> tree() {
        final CubicSpline<Float, CoordFunc> child = CubicSpline.Multipoint.create(
            new CoordFunc(1.0F, 0.0F),
            new float[] {0.0F, 10.0F},
            List.of(constant(0.5F), constant(1.5F)),
            new float[] {0.0F, 0.0F});
        return CubicSpline.Multipoint.create(
            new CoordFunc(-1.0F, 0.5F),
            new float[] {-5.0F, 5.0F, 20.0F},
            List.of(constant(1.0F), child, constant(-1.0F)),
            new float[] {0.0F, 1.0F, 0.0F});
    }

    private static final CubicSpline.CoordinateVisitor<CoordFunc> VISITOR = coordinate -> new CoordFunc(coordinate.scale * 2.0F, coordinate.offset + 1.0F);
    private static final CubicSpline.CoordinateVisitor<CoordFunc> VISITOR2 = coordinate -> new CoordFunc(coordinate.scale * -0.5F, coordinate.offset - 2.0F);

    @Test
    void mapAllMatchesLegacyStreamForm() {
        final CubicSpline.Multipoint<Float, CoordFunc> parent = tree();
        final CubicSpline<Float, CoordFunc> expected = legacyMapAll(parent, VISITOR);
        final CubicSpline<Float, CoordFunc> actual = parent.mapAll(VISITOR);

        Assertions.assertEquals(expected.parityString(), actual.parityString());
        Assertions.assertEquals(expected.minValue(), actual.minValue(), 0.0F);
        Assertions.assertEquals(expected.maxValue(), actual.maxValue(), 0.0F);

        Assertions.assertInstanceOf(CubicSpline.Multipoint.class, actual);
        Assertions.assertEquals(parent.values().size(), ((CubicSpline.Multipoint<Float, CoordFunc>)actual).values().size());

        // below-range / interval / above-range for the parent and the nested child
        for (final float x : new float[] {-10.0F, -5.0F, -1.0F, 0.0F, 2.5F, 5.0F, 7.7F, 12.0F, 20.0F, 40.0F}) {
            Assertions.assertEquals(expected.apply(x), actual.apply(x), 0.0F, "apply at " + x);
        }
    }

    @Test
    void doubleMapAllCompositionMatchesLegacy() {
        final CubicSpline.Multipoint<Float, CoordFunc> parent = tree();
        final CubicSpline<Float, CoordFunc> expected = legacyMapAll(
            (CubicSpline.Multipoint<Float, CoordFunc>)legacyMapAll(parent, VISITOR), VISITOR2);
        final CubicSpline<Float, CoordFunc> actual = parent.mapAll(VISITOR).mapAll(VISITOR2);

        Assertions.assertEquals(expected.parityString(), actual.parityString());
        Assertions.assertEquals(expected.minValue(), actual.minValue(), 0.0F);
        Assertions.assertEquals(expected.maxValue(), actual.maxValue(), 0.0F);
        for (final float x : new float[] {-10.0F, 0.0F, 3.3F, 12.0F, 40.0F}) {
            Assertions.assertEquals(expected.apply(x), actual.apply(x), 0.0F, "apply at " + x);
        }
    }

    @Test
    void originalTreeUnchangedAfterMapAll() {
        final CubicSpline.Multipoint<Float, CoordFunc> parent = tree();
        final String before = parent.parityString();
        parent.mapAll(VISITOR);
        parent.mapAll(VISITOR2);
        Assertions.assertEquals(before, parent.parityString());
    }
}
