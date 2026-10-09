package ca.spottedleaf.moonrise.common.list;

import it.unimi.dsi.fastutil.shorts.Short2ShortOpenHashMap;
import java.util.Arrays;

public final class ShortList {

    // Papo start - batch 142 (R5): lazy map allocation. Every LevelChunkSection owns one
    // ShortList for its randomly-ticking blocks, and in natural terrain the overwhelming
    // majority of sections hold zero such blocks (stone/deepslate/water/air never randomly
    // tick) - yet the old eager constructor gave every section a capacity-16 open-hash map
    // (map header + short[16] keys + short[16] values, ~250B). Measured at 12 players /
    // 4.4k chunks: ~83MB of pure empty-list residency. The map is now allocated on the
    // first add(); every read on the null state is observably identical to the empty-map
    // state (size() reads the count field, remove() returns "absent", clear() resets the
    // count, getRaw() reads byIndex which is independently lazy). setMinCapacity only
    // pre-sizes an existing map - its only callers immediately follow with adds, so the
    // deferred allocation lands on the same first add either way.
    private Short2ShortOpenHashMap map;

    private Short2ShortOpenHashMap map() {
        Short2ShortOpenHashMap map = this.map;
        if (map == null) {
            map = this.map = new Short2ShortOpenHashMap();
            map.defaultReturnValue(Short.MIN_VALUE);
        }
        return map;
    }
    // Papo end - batch 142 (R5)

    private static final short[] EMPTY_LIST = new short[0];

    private short[] byIndex = EMPTY_LIST;
    private short count;

    public int size() {
        return (int)this.count;
    }

    public short getRaw(final int index) {
        return this.byIndex[index];
    }

    public void setMinCapacity(final int len) {
        if (len <= 0) {
            return;
        }
        final short[] byIndex = this.byIndex;
        if (byIndex.length < len) {
            this.byIndex = Arrays.copyOf(byIndex, len);
        }
        final Short2ShortOpenHashMap map = this.map; // Papo - batch 142 (R5): pre-size only when allocated
        if (map != null) {
            map.ensureCapacity(len);
        }
    }

    public boolean add(final short value) {
        final int count = (int)this.count;
        final short currIndex = this.map().putIfAbsent(value, (short)count); // Papo - batch 142 (R5): lazy alloc

        if (currIndex != Short.MIN_VALUE) {
            return false; // already in this list
        }

        short[] list = this.byIndex;

        if (list.length == count) {
            // resize required
            list = this.byIndex = Arrays.copyOf(list, (int)Math.max(4L, count * 2L)); // overflow results in negative
        }

        list[count] = value;
        this.count = (short)(count + 1);

        return true;
    }

    public boolean remove(final short value) {
        final Short2ShortOpenHashMap map = this.map; // Papo - batch 142 (R5): null == empty == absent
        final short index;
        if (map == null) {
            index = Short.MIN_VALUE;
        } else {
            index = map.remove(value);
        }
        if (index == Short.MIN_VALUE) {
            return false;
        }

        // move the entry at the end to this index
        final short endIndex = --this.count;
        final short end = this.byIndex[endIndex];
        if (index != endIndex) {
            // not empty after this call
            map.put(end, index);
        }
        this.byIndex[(int)index] = end;
        this.byIndex[(int)endIndex] = (short)0;

        return true;
    }

    public void clear() {
        this.count = (short)0;
        final Short2ShortOpenHashMap map = this.map; // Papo - batch 142 (R5): clear only when allocated
        if (map != null) {
            map.clear();
        }
    }
}
