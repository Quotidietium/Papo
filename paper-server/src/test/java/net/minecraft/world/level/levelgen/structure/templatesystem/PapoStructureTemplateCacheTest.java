package net.minecraft.world.level.levelgen.structure.templatesystem;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 150 (R10): the bounded residency contract of the structure
// template cache. tryLoad-backed entries (the worldgen hot path) are evictable
// after a 120s idle TTL and reload on the next miss; every entry that entered
// through put() (vanilla getOrCreate's create-path for unloadable ids, and the
// Bukkit register/load-with-register bridge) is protected forever because it may
// be unsaved programmatic work; every read path (get, containsKey,
// computeIfAbsent, entrySet iteration) stamps so plugins polling the raw map
// keep entries hot; a hard cap bounds the evictable population for pathological
// worlds. Pure data-structure test over an injected clock - no bootstrap needed.
public class PapoStructureTemplateCacheTest {

    private static final class FakeClock implements LongSupplier {
        long nanos;

        @Override
        public long getAsLong() {
            return this.nanos;
        }

        void advanceSeconds(long seconds) {
            this.nanos += TimeUnit.SECONDS.toNanos(seconds);
        }

        void advanceMillis(long millis) {
            this.nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    private static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    private static Optional<StructureTemplate> template() {
        return Optional.of(new StructureTemplate());
    }

    @Test
    public void tryLoadBackedEntryEvictsAfterIdleTtl() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);
        cache.computeIfAbsent(id("evictable"), key -> template());

        clock.advanceSeconds(121L);
        cache.papoSweepIfDue();
        Assertions.assertFalse(cache.containsKey(id("evictable")), "idle tryLoad-backed entry must be swept");
    }

    @Test
    public void protectedPutEntryNeverEvicts() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);
        cache.put(id("protected"), template());

        clock.advanceSeconds(10_000L);
        cache.papoSweepIfDue();
        Assertions.assertTrue(cache.containsKey(id("protected")), "put-backed (possibly unsaved) entry must never be swept");
    }

    @Test
    public void accessStampsKeepEntryHot() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);
        cache.computeIfAbsent(id("polled"), key -> template());

        clock.advanceSeconds(60L);
        Assertions.assertTrue(cache.containsKey(id("polled")), "entry must still be present before TTL");

        clock.advanceSeconds(61L);
        cache.papoSweepIfDue();
        Assertions.assertTrue(cache.containsKey(id("polled")), "read at t=60s must keep the entry hot past the insert-time TTL");

        clock.advanceSeconds(122L);
        cache.papoSweepIfDue();
        Assertions.assertFalse(cache.containsKey(id("polled")), "entry untouched for >TTL after its last read must be swept");
    }

    @Test
    public void clearResetsProtection() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);
        cache.put(id("was_protected"), template());
        cache.clear();
        cache.computeIfAbsent(id("was_protected"), key -> template());

        clock.advanceSeconds(121L);
        cache.papoSweepIfDue();
        Assertions.assertFalse(
            cache.containsKey(id("was_protected")),
            "after clear() + re-insert through the load path the entry must behave as evictable again (vanilla reload semantics)"
        );
    }

    @Test
    public void entrySetIterationStampsEntries() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);
        cache.computeIfAbsent(id("browsed"), key -> template());

        clock.advanceSeconds(130L);
        int seen = 0;
        for (java.util.Map.Entry<Identifier, Optional<StructureTemplate>> entry : cache.entrySet()) {
            seen++;
        }
        Assertions.assertEquals(1, seen, "entrySet must expose the single cached entry");

        clock.advanceSeconds(1L);
        cache.papoSweepIfDue();
        Assertions.assertTrue(cache.containsKey(id("browsed")), "entrySet iteration (Bukkit getStructures) must keep entries hot");
    }

    @Test
    public void rawGetStampsEntries() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);
        cache.computeIfAbsent(id("got"), key -> template());

        clock.advanceSeconds(130L);
        Assertions.assertNotNull(cache.get(id("got")), "get must return the cached value");

        clock.advanceSeconds(1L);
        cache.papoSweepIfDue();
        Assertions.assertTrue(cache.containsKey(id("got")), "raw-map get (Bukkit getStructure) must keep entries hot");
    }

    @Test
    public void sweepRespectsEvictableCap() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);

        for (int i = 0; i < 300; i++) {
            cache.computeIfAbsent(id("cap_" + i), key -> template());
            clock.advanceMillis(1L);
        }

        cache.put(id("cap_protected"), template());
        clock.advanceSeconds(26L);
        cache.papoSweepIfDue();

        Assertions.assertEquals(
            PapoStructureCache.PAPO_MAX_EVICTABLE + 1,
            cache.size(),
            "cap path must evict the oldest evictable entries down to the cap while keeping the protected entry"
        );
        Assertions.assertFalse(cache.containsKey(id("cap_0")), "oldest-stamped entry must be evicted first");
        Assertions.assertTrue(cache.containsKey(id("cap_299")), "newest-stamped entry must survive the cap path");
        Assertions.assertTrue(cache.containsKey(id("cap_protected")), "protected entry must survive the cap path");
    }

    @Test
    public void evictedEntryReloadsOnNextAccess() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);
        int[] loads = {0};

        cache.computeIfAbsent(id("reload"), key -> {
            loads[0]++;
            return template();
        });
        clock.advanceSeconds(121L);
        cache.papoSweepIfDue();
        Assertions.assertFalse(cache.containsKey(id("reload")));

        cache.computeIfAbsent(id("reload"), key -> {
            loads[0]++;
            return template();
        });
        Assertions.assertEquals(2, loads[0], "eviction must be transparent: the next access reloads from the source");
    }

    @Test
    public void mapContractBasicsHold() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);

        Optional<StructureTemplate> first = template();
        Assertions.assertNull(cache.put(id("contract"), first));
        Assertions.assertSame(first, cache.get(id("contract")), "get must return the exact stored Optional instance");
        Optional<StructureTemplate> second = template();
        Assertions.assertSame(first, cache.put(id("contract"), second), "put must return the previous value");
        int[] loads = {0};
        Optional<StructureTemplate> loaded = cache.computeIfAbsent(id("contract"), key -> {
            loads[0]++;
            return template();
        });
        Assertions.assertSame(second, loaded, "computeIfAbsent must not re-run the loader while present");
        Assertions.assertEquals(0, loads[0], "loader must not run for a present mapping");
        Assertions.assertSame(second, cache.remove(id("contract")), "remove must return the removed value");
        Assertions.assertTrue(cache.isEmpty());
    }

    @Test
    public void backToBackSweepsAreHarmless() {
        FakeClock clock = new FakeClock();
        PapoStructureCache cache = new PapoStructureCache(clock);
        cache.computeIfAbsent(id("idempotent"), key -> template());

        clock.advanceSeconds(121L);
        cache.papoSweepIfDue();
        cache.papoSweepIfDue();
        Assertions.assertFalse(cache.containsKey(id("idempotent")));
        Assertions.assertTrue(cache.values().isEmpty());
    }
}
