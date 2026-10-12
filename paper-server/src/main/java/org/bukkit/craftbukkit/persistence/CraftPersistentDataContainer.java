package org.bukkit.craftbukkit.persistence;

import com.google.common.base.Preconditions;
import com.mojang.serialization.Codec;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.util.CraftNBTTagConfigSerializer;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

public class CraftPersistentDataContainer extends io.papermc.paper.persistence.PaperPersistentDataContainerView implements PersistentDataContainer { // Paper - split up view and mutable

    // Papo start - batch 186 (R45): lazy backing map. Empty containers are the overwhelmingly
    // common state (every block entity, every item meta, most entities/worlds), and the map
    // is only ever populated by plugin writes or a PublicBukkitValues load. Until the first
    // mutation every read is answered by an immutable empty view with bit-identical
    // observable semantics (hashCode 0 == empty HashMap, elementwise equals, empty iteration,
    // empty saved NBT). getRaw()/copyTo-target materialize because their contract hands out
    // a live writable map; the three external getRaw() callers (view copyTo, guarded
    // CraftMetaItem serialization, DeprecatedContainerTagType wrapping) all use the
    // reference immediately and never retain it across a clear(), which is the only path
    // where a retained stale alias could diverge - audited none.
    private @Nullable Map<String, Tag> customDataTags;

    private Map<String, Tag> papoTags() {
        Map<String, Tag> map = this.customDataTags;
        if (map == null) {
            this.customDataTags = map = new HashMap<>();
        }
        return map;
    }

    private Map<String, Tag> papoTagsView() {
        final Map<String, Tag> map = this.customDataTags;
        return map != null ? map : Collections.emptyMap();
    }
    // Papo end

    public CraftPersistentDataContainer(Map<String, Tag> customTags, CraftPersistentDataTypeRegistry registry) {
        this(registry);
        // Papo start - batch 186 (R45): empty source keeps the container lazy
        if (!customTags.isEmpty()) {
            this.papoTags().putAll(customTags);
        }
        // Papo end
    }

    public CraftPersistentDataContainer(CraftPersistentDataTypeRegistry registry) {
        super(registry);
    }

    @Override
    public Tag getTag(final String key) {
        return this.papoTagsView().get(key); // Papo - batch 186 lazy map
    }

    @Override
    public <T, Z> void set(@NotNull NamespacedKey key, @NotNull PersistentDataType<T, Z> type, @NotNull Z value) {
        Preconditions.checkArgument(key != null, "The NamespacedKey key cannot be null");
        Preconditions.checkArgument(type != null, "The provided PersistentDataType type cannot be null");
        Preconditions.checkArgument(value != null, "The provided value cannot be null");

        this.papoTags().put(key.toString(), this.registry.wrap(type, type.toPrimitive(value, this.papoAdapterContext()))); // Papo - batch 186 lazy map + lazy adapter
    }

    @NotNull
    @Override
    public Set<NamespacedKey> getKeys() {
        Set<NamespacedKey> keys = new HashSet<>();

        this.papoTagsView().keySet().forEach(key -> { // Papo - batch 186 lazy map
            String[] keyData = key.split(":", 2);
            if (keyData.length == 2) {
                keys.add(new NamespacedKey(keyData[0], keyData[1]));
            }
        });

        return keys;
    }

    @Override
    public void remove(@NotNull NamespacedKey key) {
        Preconditions.checkArgument(key != null, "The NamespacedKey key cannot be null");

        final Map<String, Tag> map = this.customDataTags; // Papo - batch 186 lazy map: removing from the empty state is a no-op, no materialization
        if (map != null) {
            map.remove(key.toString());
        }
    }

    @Override
    public boolean isEmpty() {
        return this.papoTagsView().isEmpty(); // Papo - batch 186 lazy map
    }

    @NotNull
    @Override
    public void copyTo(PersistentDataContainer other, boolean replace) {
        Preconditions.checkArgument(other != null, "The target container cannot be null");

        CraftPersistentDataContainer target = (CraftPersistentDataContainer) other;
        if (replace) {
            // Papo - batch 186 lazy map: empty source adds nothing; keep an all-empty target lazy
            if (!this.papoTagsView().isEmpty()) {
                target.papoTags().putAll(this.papoTagsView());
            }
        } else {
            this.papoTagsView().forEach(target.papoTags()::putIfAbsent); // Papo - batch 186 lazy map
        }
    }

    @Override
    public PersistentDataAdapterContext getAdapterContext() {
        return this.papoAdapterContext(); // Papo - batch 186 lazy adapter
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CraftPersistentDataContainer)) {
            return false;
        }

        // Papo - batch 186 lazy map: read-view comparison (elementwise, same result as the
        // vanilla map-vs-map equals) so CraftMetaItem.equals on two empty containers does
        // not materialize either side
        Map<String, Tag> myRawMap = this.papoTagsView();
        Map<String, Tag> theirRawMap = ((CraftPersistentDataContainer) obj).papoTagsView();

        return Objects.equals(myRawMap, theirRawMap);
    }

    public CompoundTag toTagCompound() {
        CompoundTag tag = new CompoundTag();
        for (Entry<String, Tag> entry : this.papoTagsView().entrySet()) { // Papo - batch 186 lazy map
            tag.put(entry.getKey(), entry.getValue());
        }
        return tag;
    }

    public void put(String key, Tag base) {
        this.papoTags().put(key, base); // Papo - batch 186 lazy map
    }

    public void putAll(Map<String, Tag> map) {
        if (!map.isEmpty()) { // Papo - batch 186 lazy map: empty source is a no-op, no materialization
            this.papoTags().putAll(map);
        }
    }

    public void putAll(CompoundTag compound) {
        for (String key : compound.keySet()) {
            this.papoTags().put(key, compound.get(key)); // Papo - batch 186 lazy map
        }
    }

    public Map<String, Tag> getRaw() {
        return this.papoTags(); // Papo - batch 186 lazy map: live-map contract, materializes
    }

    public CraftPersistentDataTypeRegistry getDataTagTypeRegistry() {
        return this.registry;
    }

    @Override
    public int hashCode() {
        int hashCode = 3;
        hashCode += this.papoTagsView().hashCode(); // We will simply add the maps hashcode // Papo - batch 186 lazy map: empty view hashes 0 == empty HashMap
        return hashCode;
    }

    public String serialize() {
        return CraftNBTTagConfigSerializer.serialize(this.toTagCompound());
    }

    public void clear() {
        this.customDataTags = null; // Papo - batch 186 lazy map: bit-identical observable empty state (see class comment: no retained aliases audited)
    }

    @Override
    public void readFromBytes(final byte[] bytes, final boolean clear) throws java.io.IOException {
        if (clear) {
            this.clear();
        }
        try (final java.io.DataInputStream dataInput = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            final net.minecraft.nbt.CompoundTag compound = net.minecraft.nbt.NbtIo.read(dataInput);
            this.putAll(compound);
        }
    }

    public Map<String, Tag> getTagsCloned() {
        final Map<String, Tag> tags = new HashMap<>();
        this.papoTagsView().forEach((key, tag) -> tags.put(key, tag.copy())); // Papo - batch 186 lazy map
        return tags;
    }

    public static Codec<CraftPersistentDataContainer> createCodec(final CraftPersistentDataTypeRegistry registry) {
        return CompoundTag.CODEC.xmap(tag -> {
            final CraftPersistentDataContainer container = new CraftPersistentDataContainer(registry);
            container.putAll(tag);
            return container;
        }, CraftPersistentDataContainer::toTagCompound);
    }

    @Override
    public int getSize() {
        return this.papoTagsView().size(); // Papo - batch 186 lazy map
    }
}
