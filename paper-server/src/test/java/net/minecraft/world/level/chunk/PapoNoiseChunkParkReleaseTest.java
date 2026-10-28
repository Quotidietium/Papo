package net.minecraft.world.level.chunk;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 160 (R20): the parked pre-carver NoiseChunk release contract.
// ServerChunkCache periodically nulls ChunkAccess.noiseChunk on ProtoChunks parked
// below the CARVERS step (batch 143 already releases at the carvers exit, which those
// chunks never reach). Behaviour-neutrality rests on getOrCreateNoiseChunk's lazy
// recreation: a chunk that resumes generation rebuilds through its own step's closure,
// and the cross-step reads (preliminarySurfaceLevel, aquifer) are pure memoised
// functions of deterministic inputs - see the identity proof on
// ChunkAccess#papoReleaseParkedNoiseChunk. These tests pin the field-level contract:
// release nulls exactly once, recreation routes through the calling step's creator,
// the cache is stable while parked, and repeated park cycles release again. Sentinels
// are mocks: the assertions only compare identity, and a real NoiseChunk needs a full
// worldgen registry set (RandomState eagerly builds a SurfaceSystem that queries the
// noise-parameter registry). Construction follows the batch 147/149 synthetic-registry
// test pattern.
public class PapoNoiseChunkParkReleaseTest {

    private static final Holder<Biome> PLAINS = Holder.direct(null);
    private static final Holder<Biome> DESERT = Holder.direct(null);

    private static Strategy<BlockState> statesStrategy;
    private static Strategy<Holder<Biome>> biomesStrategy;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
        statesStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        biomesStrategy = Strategy.createForBiomes(new IdMap<Holder<Biome>>() {
            @Override
            public int getId(final Holder<Biome> value) {
                if (value == PLAINS) {
                    return 0;
                }
                return value == DESERT ? 1 : -1;
            }

            @Override
            public Holder<Biome> byId(final int id) {
                return id == 0 ? PLAINS : DESERT;
            }

            @Override
            public int size() {
                return 2;
            }

            @Override
            public java.util.Iterator<Holder<Biome>> iterator() {
                return List.of(PLAINS, DESERT).iterator();
            }
        });
    }

    private static ProtoChunk protoChunk() {
        final LevelHeightAccessor height = LevelHeightAccessor.create(-64, 384);
        final PalettedContainerFactory factory = new PalettedContainerFactory(
            statesStrategy, Blocks.AIR.defaultBlockState(), null,
            biomesStrategy, PLAINS, null, null
        );
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, height, factory, null);
    }

    @Test
    void releaseClearsFieldExactlyOnce() {
        final ProtoChunk chunk = protoChunk();
        Assertions.assertFalse(chunk.papoReleaseParkedNoiseChunk(), "empty chunk must report nothing to release");
        chunk.getOrCreateNoiseChunk(c -> org.mockito.Mockito.mock(NoiseChunk.class));
        Assertions.assertTrue(chunk.papoReleaseParkedNoiseChunk(), "first release must report success");
        Assertions.assertFalse(chunk.papoReleaseParkedNoiseChunk(), "second release must be a no-op");
    }

    @Test
    void recreationRoutesThroughTheCallingStepsClosure() {
        final ProtoChunk chunk = protoChunk();
        final NoiseChunk biomesStep = chunk.getOrCreateNoiseChunk(c -> org.mockito.Mockito.mock(NoiseChunk.class));
        Assertions.assertSame(biomesStep, chunk.getOrCreateNoiseChunk(c -> {
            throw new AssertionError("cache must be served while parked");
        }), "parked cache must be served to later readers without recreation");
        Assertions.assertTrue(chunk.papoReleaseParkedNoiseChunk());
        final NoiseChunk carversStep = chunk.getOrCreateNoiseChunk(c -> org.mockito.Mockito.mock(NoiseChunk.class));
        Assertions.assertNotSame(biomesStep, carversStep, "post-release reader must rebuild through its own closure");
        Assertions.assertSame(carversStep, chunk.getOrCreateNoiseChunk(c -> {
            throw new AssertionError("rebuilt cache must be served");
        }), "rebuilt cache must be stable for subsequent readers of the resumed generation");
    }

    @Test
    void releaseAndReparkCycles() {
        final ProtoChunk chunk = protoChunk();
        for (int cycle = 0; cycle < 4; cycle++) {
            chunk.getOrCreateNoiseChunk(c -> org.mockito.Mockito.mock(NoiseChunk.class));
            Assertions.assertTrue(chunk.papoReleaseParkedNoiseChunk(), "cycle " + cycle + " must release");
            Assertions.assertFalse(chunk.papoReleaseParkedNoiseChunk());
        }
    }

    @Test
    void batch143HookStillClearsUnconditionally() {
        final ProtoChunk chunk = protoChunk();
        chunk.getOrCreateNoiseChunk(c -> org.mockito.Mockito.mock(NoiseChunk.class));
        chunk.papoReleaseNoiseChunk();
        Assertions.assertFalse(chunk.papoReleaseParkedNoiseChunk(), "both hooks share the field; carvers exit wins for progressing chunks");
    }
}
