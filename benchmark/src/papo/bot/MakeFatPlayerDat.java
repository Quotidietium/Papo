package papo.bot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * 批次84：生成"老玩家"大 .dat——1.20.1 格式（DataVersion 3700）结构化玩家 NBT，
 * 触发真实 datafix 全链（3700→4600+ 数百个 schema 步进，含 1.20.5 物品组件重写：
 * Count/tag 拆解、附魔/名称/lore 迁移），gzip 后落盘。
 *
 * 服务器读路径（NbtIo.readCompressed → MCDataConverter.convertTag）对结构宽容：
 * 字段缺失用默认、未知字段原样穿透；本工具只产 vanilla 已知字段。
 * 目标形态：41 槽背包 + 27 槽末影箱（每项带 ench/display/custom 数据）+ 30 条属性，
 * 未压缩 ~2MB——模拟重度老玩家。
 */
public final class MakeFatPlayerDat {

    public static final int DATA_VERSION = 3700; // 1.20.1
    // 归因实验：PAPO_FAT_DV=<current> 时生成现代版本文件（datafix 无步进，读成本不变）
    private static final int EFFECTIVE_DV = Integer.parseInt(System.getenv().getOrDefault("PAPO_FAT_DV", String.valueOf(DATA_VERSION)));

    public static void main(final String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: MakeFatPlayerDat <playerName> <outfile.dat>");
            System.exit(2);
        }
        final byte[] dat = build(args[0]);
        Files.write(Path.of(args[1]), dat);
        System.out.println("wrote " + args[1] + " (" + dat.length + "B gz, player=" + args[0]
            + " uuid=" + offlineUuid(args[0]) + " DataVersion=" + DATA_VERSION + ")");
    }

    public static UUID offlineUuid(final String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] build(final String playerName) throws IOException {
        final CompoundWriter root = new CompoundWriter();
        root.int_("DataVersion", EFFECTIVE_DV);

        // 41 槽背包（1.20.1 格式：Slot/id/Count/tag）
        root.list("Inventory", NbtWriter.COMPOUND, w -> {
            for (int slot = 0; slot < 41; slot++) {
                final CompoundWriter item = new CompoundWriter();
                item.byte_("Slot", (byte) slot);
                item.string("id", switch (slot % 7) {
                    case 0 -> "minecraft:diamond_pickaxe";
                    case 1 -> "minecraft:netherite_chestplate";
                    case 2 -> "minecraft:elytra";
                    case 3 -> "minecraft:golden_apple";
                    case 4 -> "minecraft:shulker_box";
                    case 5 -> "minecraft:enchanted_book";
                    default -> "minecraft:diamond_sword";
                });
                item.byte_("Count", (byte) (1 + slot % 16));
                item.compound("tag", tag -> {
                    tag.list("Enchantments", NbtWriter.COMPOUND, e -> {
                        for (int e1 = 0; e1 < 5; e1++) {
                            final CompoundWriter ench = new CompoundWriter();
                            ench.string("id", e1 == 0 ? "minecraft:sharpness" : e1 == 1 ? "minecraft:protection"
                                : e1 == 2 ? "minecraft:efficiency" : e1 == 3 ? "minecraft:unbreaking" : "minecraft:mending");
                            ench.short_("lvl", (short) (e1 + 1));
                            e.item(ench);
                        }
                    });
                    tag.compound("display", d -> {
                        d.string("Name", "{\"text\":\"" + playerName + " item\",\"color\":\"gold\",\"italic\":false}");
                        d.list("Lore", NbtWriter.STRING, l -> {
                            for (int line = 0; line < 10; line++) {
                                l.stringItem("{\"text\":\"line " + line + " of " + playerName + "\",\"color\":\"aqua\"}");
                            }
                        });
                    });
                    tag.list("AttributeModifiers", NbtWriter.COMPOUND, m -> {
                        for (int m1 = 0; m1 < 4; m1++) {
                            final CompoundWriter mod = new CompoundWriter();
                            mod.string("AttributeName", "generic.attack_damage");
                            mod.string("Name", playerName + "_mod_" + m1);
                            mod.double_("Amount", 1.5 + m1);
                            mod.int_("Operation", m1 % 2);
                            mod.long_("UUIDMost", 0x12345678L + m1);
                            mod.long_("UUIDLeast", 0x9ABCDEF0L + m1);
                            m.item(mod);
                        }
                    });
                    tag.int_("RepairCost", 3);
                    tag.int_("HideFlags", 6);
                });
                w.item(item);
            }
        });

        // 27 槽末影箱
        root.list("EnderItems", NbtWriter.COMPOUND, w -> {
            for (int slot = 0; slot < 27; slot++) {
                final CompoundWriter item = new CompoundWriter();
                item.byte_("Slot", (byte) slot);
                item.string("id", slot % 3 == 0 ? "minecraft:diamond" : slot % 3 == 1 ? "minecraft:iron_block" : "minecraft:shulker_box");
                item.byte_("Count", (byte) 64);
                w.item(item);
            }
        });

        // 30 条属性（旧命名，触发后续属性命名空间/名重写）
        root.list("Attributes", NbtWriter.COMPOUND, w -> {
            for (int i0 = 0; i0 < 30; i0++) {
                final int i = i0;
                final CompoundWriter attr = new CompoundWriter();
                attr.string("Name", switch (i % 6) {
                    case 0 -> "generic.max_health";
                    case 1 -> "generic.movement_speed";
                    case 2 -> "generic.attack_damage";
                    case 3 -> "generic.armor";
                    case 4 -> "generic.armor_toughness";
                    default -> "generic.luck";
                });
                attr.double_("Base", 1.0 + (i % 5) * 0.5);
                attr.list("Modifiers", NbtWriter.COMPOUND, m -> {
                    for (int m1 = 0; m1 < 3; m1++) {
                        final CompoundWriter mod = new CompoundWriter();
                        mod.string("Name", playerName + "_am_" + i + "_" + m1);
                        mod.double_("Amount", 0.1 * m1);
                        mod.int_("Operation", 0);
                        mod.long_("UUIDMost", 0x1000L + i);
                        mod.long_("UUIDLeast", 0x2000L + m1);
                        m.item(mod);
                    }
                });
                w.item(attr);
            }
        });

        // 位置/运动/基础状态
        root.list("Pos", NbtWriter.DOUBLE, w -> { w.doubleItem(0.5); w.doubleItem(80.0); w.doubleItem(0.5); });
        root.list("Motion", NbtWriter.DOUBLE, w -> { w.doubleItem(0.0); w.doubleItem(0.0); w.doubleItem(0.0); });
        root.list("Rotation", NbtWriter.FLOAT, w -> { w.floatItem(0.0f); w.floatItem(0.0f); });
        root.string("Dimension", "minecraft:overworld");
        root.float_("Health", 20.0f);
        root.short_("Air", (short) 300);
        root.short_("Fire", (short) -1);
        root.int_("XpLevel", 50);
        root.int_("XpTotal", 12345);
        root.float_("XpP", 0.5f);
        root.int_("XpSeed", 1234567);
        root.int_("foodLevel", 20);
        root.float_("foodSaturationLevel", 5.0f);
        root.int_("foodTickTimer", 0);
        root.float_("foodExhaustionLevel", 0.0f);
        root.int_("playerGameType", 0);
        root.int_("previousPlayerGameType", -1);
        root.int_("Score", 999);
        root.int_("SpawnX", 0);
        root.int_("SpawnY", 80);
        root.int_("SpawnZ", 0);
        root.float_("SpawnAngle", 0.0f);
        root.boolean_("SpawnForced", false);
        root.int_("sleepTimer", 0);
        root.int_("InsomniaTicks", 0);
        root.short_("DeathTime", (short) 0);
        root.short_("HurtTime", (short) 0);
        root.boolean_("seenCredits", true);
        root.compound("abilities", a -> {
            a.boolean_("invulnerable", false);
            a.boolean_("flying", false);
            a.boolean_("mayfly", false);
            a.boolean_("instabuild", false);
            a.boolean_("mayBuild", true);
            a.float_("flySpeed", 0.05f);
            a.float_("walkSpeed", 0.1f);
        });
        root.compound("recipeBook", rb -> {
            // 真实配方 id（伪造 id 会触发每条 "unrecognized recipe" ERROR，污染零异常门）
            rb.list("recipes", NbtWriter.STRING, r -> {
                r.stringItem("minecraft:bread");
                r.stringItem("minecraft:torch");
                r.stringItem("minecraft:iron_pickaxe");
                r.stringItem("minecraft:furnace");
            });
            rb.list("toBeDisplayed", NbtWriter.STRING, r -> r.stringItem("minecraft:bread"));
            rb.boolean_("filteringCraftable", false);
            rb.boolean_("furnaceFilteringCraftable", false);
            rb.boolean_("blastFurnaceFilteringCraftable", false);
            rb.boolean_("smokerFilteringCraftable", false);
        });
        root.compound("bukkit", b -> {
            b.string("lastKnownName", playerName);
            b.long_("firstPlayed", 1600000000000L);
            b.long_("lastPlayed", 1700000000000L);
            b.int_("bukkitLevel", 0);
        });

        return gzip(root.toByteArray());
    }

    static byte[] gzip(final byte[] nbt) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(nbt.length / 4);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos, 8192)) {
            gz.write(nbt);
        }
        return bos.toByteArray();
    }

    // ---------- 极小 NBT writer（未压缩 Big-Endian，root 无名 compound） ----------

    static final class NbtWriter {
        static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

        int count; // list 元素计数（回填 list 长度用）
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(16 * 1024);
        final DataOutputStream out = new DataOutputStream(this.buf);

        interface ListBody { void accept(NbtWriter w) throws IOException; }

        void stringItem(final String s) throws IOException {
            this.count++;
            final byte[] b = s.getBytes(StandardCharsets.UTF_8);
            this.out.writeShort(b.length);
            this.out.write(b);
        }

        void doubleItem(final double v) throws IOException {
            this.count++;
            this.out.writeDouble(v);
        }

        void floatItem(final float v) throws IOException {
            this.count++;
            this.out.writeFloat(v);
        }

        void item(final CompoundWriter compound) throws IOException {
            this.count++;
            compound.writePayloadTo(this.out);
        }
    }

    static final class CompoundWriter {
        interface Body { void accept(CompoundWriter w) throws IOException; }

        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(16 * 1024);
        private final DataOutputStream out = new DataOutputStream(this.buf);

        void byte_(final String name, final byte v) throws IOException {
            this.head(NbtWriter.BYTE, name);
            this.out.writeByte(v);
        }

        void short_(final String name, final short v) throws IOException {
            this.head(NbtWriter.SHORT, name);
            this.out.writeShort(v);
        }

        void int_(final String name, final int v) throws IOException {
            this.head(NbtWriter.INT, name);
            this.out.writeInt(v);
        }

        void long_(final String name, final long v) throws IOException {
            this.head(NbtWriter.LONG, name);
            this.out.writeLong(v);
        }

        void float_(final String name, final float v) throws IOException {
            this.head(NbtWriter.FLOAT, name);
            this.out.writeFloat(v);
        }

        void double_(final String name, final double v) throws IOException {
            this.head(NbtWriter.DOUBLE, name);
            this.out.writeDouble(v);
        }

        void boolean_(final String name, final boolean v) throws IOException {
            this.byte_(name, (byte) (v ? 1 : 0));
        }

        void string(final String name, final String v) throws IOException {
            this.head(NbtWriter.STRING, name);
            final byte[] b = v.getBytes(StandardCharsets.UTF_8);
            this.out.writeShort(b.length);
            this.out.write(b);
        }

        void compound(final String name, final Body body) throws IOException {
            this.head(NbtWriter.COMPOUND, name);
            final CompoundWriter inner = new CompoundWriter();
            body.accept(inner);
            inner.writePayloadTo(this.out);
        }

        void list(final String name, final byte elementType, final NbtWriter.ListBody body) throws IOException {
            this.head(NbtWriter.LIST, name);
            final NbtWriter listWriter = new NbtWriter();
            body.accept(listWriter);
            this.out.writeByte(elementType);
            this.out.writeInt(listWriter.count);
            listWriter.buf.writeTo(this.out);
        }

        void head(final byte type, final String name) throws IOException {
            this.out.writeByte(type);
            final byte[] b = name.getBytes(StandardCharsets.UTF_8);
            this.out.writeShort(b.length);
            this.out.write(b);
        }

        void writePayloadTo(final DataOutputStream target) throws IOException {
            this.out.flush();
            this.buf.writeTo(target);
            target.writeByte(NbtWriter.END);
        }

        byte[] toByteArray() throws IOException {
            this.out.flush();
            final ByteArrayOutputStream root = new ByteArrayOutputStream(this.buf.size() + 4);
            root.write(NbtWriter.COMPOUND);
            root.write(0);
            root.write(0);
            this.buf.writeTo(root);
            root.write(NbtWriter.END);
            return root.toByteArray();
        }
    }
}
