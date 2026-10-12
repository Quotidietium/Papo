package org.bukkit.craftbukkit.persistence;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.Tag;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 186 (R45): the lazy backing-map contract of CraftPersistentDataContainer.
// Oracle = a materialized-empty container (one that went through put + clear, i.e. the old
// eager state with an empty HashMap). The lazy state must be observably bit-identical on
// every read (isEmpty/size/keys/hashCode/equals both ways/toTagCompound/serialize), writes
// must materialize transparently, and clear() must return to the empty state.
public class PapoPersistentDataContainerLazyTest {

    private static CraftPersistentDataContainer fresh() {
        return new CraftPersistentDataContainer(new CraftPersistentDataTypeRegistry());
    }

    private static CraftPersistentDataContainer materializedEmpty() {
        final CraftPersistentDataContainer c = fresh();
        c.set(key("warm"), PersistentDataType.BYTE, (byte) 1);
        c.clear();
        return c;
    }

    private static NamespacedKey key(final String k) {
        return new NamespacedKey("papo", k);
    }

    @Test
    void lazyEmptyMatchesMaterializedEmpty() {
        final CraftPersistentDataContainer lazy = fresh();
        final CraftPersistentDataContainer oracle = materializedEmpty();
        Assertions.assertTrue(lazy.isEmpty());
        Assertions.assertEquals(0, lazy.getSize());
        Assertions.assertTrue(lazy.getKeys().isEmpty());
        Assertions.assertNull(lazy.getTag("anything"));
        Assertions.assertFalse(lazy.has(key("anything")));
        Assertions.assertEquals(oracle.hashCode(), lazy.hashCode(), "hashCode(empty map) == 0 both states");
        Assertions.assertEquals(oracle, lazy, "lazy equals oracle");
        Assertions.assertEquals(lazy, oracle, "oracle equals lazy");
        Assertions.assertEquals(oracle.toTagCompound(), lazy.toTagCompound());
        Assertions.assertEquals(0, lazy.toTagCompound().size());
        Assertions.assertEquals(oracle.serialize(), lazy.serialize());
    }

    @Test
    void removeAndEmptyPutAllKeepEmptyState() {
        final CraftPersistentDataContainer c = fresh();
        c.remove(key("absent")); // 空态 remove 无操作
        Assertions.assertTrue(c.isEmpty());
        c.putAll(new CompoundTag()); // 空 CompoundTag 无条目
        Assertions.assertTrue(c.isEmpty());
        c.putAll(new HashMap<String, Tag>()); // 空 Map 无条目
        Assertions.assertTrue(c.isEmpty());
        Assertions.assertEquals(materializedEmpty(), c);
    }

    @Test
    void writeMaterializesAndRoundTrips() {
        final CraftPersistentDataContainer c = fresh();
        c.set(key("a"), PersistentDataType.BYTE, (byte) 7);
        c.set(key("b"), PersistentDataType.STRING, "v");
        Assertions.assertFalse(c.isEmpty());
        Assertions.assertEquals(2, c.getSize());
        Assertions.assertTrue(c.getKeys().contains(key("a")));
        Assertions.assertEquals((byte) 7, c.get(key("a"), PersistentDataType.BYTE));
        Assertions.assertEquals("v", c.get(key("b"), PersistentDataType.STRING));

        // NBT 往返：toTagCompound -> 新容器 putAll -> 逐位等价
        final CompoundTag tag = c.toTagCompound();
        final CraftPersistentDataContainer copy = fresh();
        copy.putAll(tag);
        Assertions.assertEquals(c, copy);
        Assertions.assertEquals(2, copy.getSize());

        // clear 回到空态（与物化空 oracle 等价）
        c.clear();
        Assertions.assertTrue(c.isEmpty());
        Assertions.assertEquals(materializedEmpty(), c);
        // clear 后可再次写入
        c.set(key("c"), PersistentDataType.BYTE, (byte) 9);
        Assertions.assertEquals((byte) 9, c.get(key("c"), PersistentDataType.BYTE));
    }

    @Test
    void copyToSemanticsPreserved() {
        final CraftPersistentDataContainer source = fresh();
        source.set(key("x"), PersistentDataType.BYTE, (byte) 1);
        final CraftPersistentDataContainer target = fresh();
        source.copyTo(target, false);
        Assertions.assertEquals((byte) 1, target.get(key("x"), PersistentDataType.BYTE));
        // 非替换模式不覆盖既有键
        source.set(key("x"), PersistentDataType.BYTE, (byte) 2);
        source.copyTo(target, false);
        Assertions.assertEquals((byte) 1, target.get(key("x"), PersistentDataType.BYTE));
        // 替换模式覆盖
        source.copyTo(target, true);
        Assertions.assertEquals((byte) 2, target.get(key("x"), PersistentDataType.BYTE));
        // 空源 copyTo 不污染目标
        final CraftPersistentDataContainer emptySource = fresh();
        final CraftPersistentDataContainer target2 = fresh();
        emptySource.copyTo(target2, true);
        Assertions.assertTrue(target2.isEmpty());
    }

    @Test
    void rawMapContractIsLive() {
        final CraftPersistentDataContainer c = fresh();
        final Map<String, Tag> raw = c.getRaw();
        raw.put("raw-key", IntTag.valueOf(5));
        Assertions.assertNotNull(c.getTag("raw-key"), "raw map writes are visible through the container");
        Assertions.assertFalse(c.isEmpty());
        Assertions.assertEquals(IntTag.valueOf(5), c.getTag("raw-key"));
    }

    @Test
    void putAllCompoundPopulates() {
        final CraftPersistentDataContainer c = fresh();
        final CompoundTag tag = new CompoundTag();
        tag.put("n", IntTag.valueOf(3));
        c.putAll(tag);
        Assertions.assertEquals(IntTag.valueOf(3), c.getTag("n"));
        Assertions.assertEquals(1, c.getSize());
    }
}
// Papo end - batch 186 (R45)
