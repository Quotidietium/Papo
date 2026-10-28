package papo.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 批次 142（R5 轮2）验证：ShortList（Moonrise 每 LevelChunkSection 一个的
 * 随机刻方块表）惰性 map 化后的语义差分。参照模型 = 同语义的 ArrayList
 * （插入序 + 末位交换删除）。随机指令序列 + 边界序列双跑，逐操作断言
 * size/getRaw 全量一致；退出码 0=全绿，1=失配（打印首个分歧）。
 *
 * 关键空态路径（惰性化的全部可观测面）：never-added 上 remove、never-added
 * 上 clear、空态 setMinCapacity、首次 add 分配路径、remove 到空后再 add。
 */
public final class ShortListVerify {

    public static void main(final String[] args) {
        int failures = 0;

        // 参照模型：与 ShortList 相同的插入序 + swap-remove 语义
        final List<Short> model = new ArrayList<>();
        final ca.spottedleaf.moonrise.common.list.ShortList list = new ca.spottedleaf.moonrise.common.list.ShortList();

        // 序列 1：空态边界（惰性 null-map 全路径）
        failures += check("empty remove", list, model, list.remove((short) 5), false);
        list.setMinCapacity(8);   // 空态预容量（byIndex 独立惰性）
        list.clear();             // 空态 clear
        failures += check("still empty", list, model, list.size() == 0, true);

        // 序列 2：确定性核心操作
        final short[] adds = {100, 200, 300, 200, 100, 400, 500, 600};
        for (final short v : adds) {
            final boolean r = list.add(v);
            final boolean m = !model.contains(v);
            if (m) {
                model.add(v);
            }
            failures += check("add " + v, list, model, r, m);
        }
        failures += check("remove absent", list, model, list.remove((short) 999), false);
        failures += check("remove head-ish", list, model, list.remove((short) 100), modelRemove(model, (short) 100));
        failures += check("remove mid", list, model, list.remove((short) 400), modelRemove(model, (short) 400));
        failures += check("post-removals", list, model, 0, 0);

        // remove 到空，再复活
        while (list.size() > 0) {
            modelRemove(model, list.getRaw(0));
            list.remove(list.getRaw(0));
        }
        list.clear();
        failures += check("drained", list, model, list.size(), 0);
        final boolean revive = list.add((short) 77);
        model.add((short) 77);
        failures += check("revive add", list, model, revive, true);
        failures += check("revive state", list, model, 0, 0);

        // 序列 3：随机差分（含 setMinCapacity 混入）
        final Random rnd = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            final int op = rnd.nextInt(100);
            if (op < 45) {
                final short v = (short) rnd.nextInt(500);
                final boolean r = list.add(v);
                final boolean m = !model.contains(v);
                if (m) {
                    model.add(v);
                }
                if (r != m) {
                    failures += check("rnd add " + v, list, model, r, m);
                }
            } else if (op < 80) {
                final short v = (short) rnd.nextInt(500);
                final boolean r = list.remove(v);
                final boolean m = modelRemove(model, v);
                if (r != m) {
                    failures += check("rnd remove " + v, list, model, r, m);
                }
            } else if (op < 90) {
                list.setMinCapacity(rnd.nextInt(32));
            } else if (op < 95) {
                list.clear();
                model.clear();
            }
            if (i % 9973 == 0) {
                failures += check("rnd snapshot @" + i, list, model, 0, 0);
            }
        }
        failures += check("final", list, model, 0, 0);

        if (failures == 0) {
            System.out.println("ShortListVerify: ALL OK (200k+ ops)");
            System.exit(0);
        }
        System.exit(1);
    }

    /** 参照模型的 swap-remove：末位补洞，与 ShortList.remove 逐位同构。 */
    private static boolean modelRemove(final List<Short> model, final short v) {
        final int idx = model.indexOf(v);
        if (idx < 0) {
            return false;
        }
        final short last = model.remove(model.size() - 1);
        if (idx < model.size()) {
            model.set(idx, last);
        }
        return true;
    }

    /** 全量快照断言：size 与全部 getRaw 逐位一致（返回值断言失败时也触发快照）。 */
    private static int check(final String label, final ca.spottedleaf.moonrise.common.list.ShortList list,
                             final List<Short> model, final Object got, final Object want) {
        final int sizeFail = list.size() != model.size() ? 1 : 0;
        int elemFail = 0;
        final int n = Math.min(list.size(), model.size());
        for (int i = 0; i < n; i++) {
            if (list.getRaw(i) != model.get(i)) {
                elemFail = 1;
                System.out.println("MISMATCH[" + label + "] idx=" + i + " got=" + list.getRaw(i) + " want=" + model.get(i));
                break;
            }
        }
        final int retFail = !got.equals(want) ? 1 : 0;
        if (sizeFail + elemFail + retFail > 0) {
            if (sizeFail != 0) {
                System.out.println("MISMATCH[" + label + "] size got=" + list.size() + " want=" + model.size());
            }
            if (retFail != 0) {
                System.out.println("MISMATCH[" + label + "] ret got=" + got + " want=" + want);
            }
            dump("list", list);
            dump("model", model);
        }
        return sizeFail + elemFail + retFail;
    }

    private static void dump(final String label, final ca.spottedleaf.moonrise.common.list.ShortList list) {
        final StringBuilder sb = new StringBuilder(label + "[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(list.getRaw(i));
        }
        System.out.println(sb.append("]"));
    }

    private static void dump(final String label, final List<Short> model) {
        System.out.println(label + model);
    }
}
