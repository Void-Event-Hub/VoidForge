package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortCollection;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.goldenforge.GoldenForge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class StarLightInterface {

    public static final TicketType<ChunkPos> CHUNK_WORK_TICKET = TicketType.create("starlight_chunk_work_ticket", (p1, p2) -> Long.compare(p1.toLong(), p2.toLong()));

    /**
     * Can be {@code null}, indicating the light is all empty.
     */
    protected final Level world;
    protected final LightChunkGetter lightAccess;

    protected final ArrayDeque<SkyStarLightEngine> cachedSkyPropagators;
    protected final ArrayDeque<BlockStarLightEngine> cachedBlockPropagators;

    public final io.papermc.paper.chunk.system.light.LightQueue lightQueue; // Paper - replace light queue

    protected final LayerLightEventListener skyReader;
    protected final LayerLightEventListener blockReader;
    protected final boolean isClientSide;

    public final int minSection; // Paper - public
    public final int maxSection; // Paper - public
    protected final int minLightSection;
    protected final int maxLightSection;

    public final LevelLightEngine lightEngine;

    private final boolean hasBlockLight;
    private final boolean hasSkyLight;

    public StarLightInterface(final LightChunkGetter lightAccess, final boolean hasSkyLight, final boolean hasBlockLight, final LevelLightEngine lightEngine) {
        this.lightAccess = lightAccess;
        this.world = lightAccess == null ? null : (Level)lightAccess.getLevel();
        this.cachedSkyPropagators = hasSkyLight && lightAccess != null ? new ArrayDeque<>() : null;
        this.cachedBlockPropagators = hasBlockLight && lightAccess != null ? new ArrayDeque<>() : null;
        this.isClientSide = !(this.world instanceof ServerLevel);
        if (this.world == null) {
            this.minSection = -4;
            this.maxSection = 19;
            this.minLightSection = -5;
            this.maxLightSection = 20;
        } else {
            this.minSection = WorldUtil.getMinSection(this.world);
            this.maxSection = WorldUtil.getMaxSection(this.world);
            this.minLightSection = WorldUtil.getMinLightSection(this.world);
            this.maxLightSection = WorldUtil.getMaxLightSection(this.world);
        }
        this.lightEngine = lightEngine;
        this.hasBlockLight = hasBlockLight;
        this.hasSkyLight = hasSkyLight;
        this.skyReader = !hasSkyLight ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : new LayerLightEventListener() {
            @Override
            public void checkBlock(final BlockPos blockPos) {
                StarLightInterface.this.lightEngine.checkBlock(blockPos.immutable());
            }

            @Override
            public void onBlockEmissionIncrease(final BlockPos blockPos, final int i) {
                // skylight doesn't care
            }

            @Override
            public boolean hasLightWork() {
                // not really correct...
                return StarLightInterface.this.hasUpdates();
            }

            @Override
            public int runUpdates(final int i, final boolean bl, final boolean bl2) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void enableLightSources(final ChunkPos chunkPos, final boolean bl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DataLayer getDataLayerData(final SectionPos pos) {
                final ChunkAccess chunk = StarLightInterface.this.getAnyChunkNow(pos.getX(), pos.getZ());
                if (chunk == null || (!StarLightInterface.this.isClientSide && !chunk.isLightCorrect()) || !chunk.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
                    return null;
                }

                final int sectionY = pos.getY();

                if (sectionY > StarLightInterface.this.maxLightSection || sectionY < StarLightInterface.this.minLightSection) {
                    return null;
                }

                if (chunk.getSkyEmptinessMap() == null) {
                    return null;
                }

                return chunk.getSkyNibbles()[sectionY - StarLightInterface.this.minLightSection].toVanillaNibble();
            }

            @Override
            public int getLightValue(final BlockPos blockPos) {
                return StarLightInterface.this.getSkyLightValue(blockPos, StarLightInterface.this.getAnyChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4));
            }

            @Override
            public void updateSectionStatus(final SectionPos pos, final boolean notReady) {
                StarLightInterface.this.sectionChange(pos, notReady);
            }
        };
        this.blockReader = !hasBlockLight ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : new LayerLightEventListener() {
            @Override
            public void checkBlock(final BlockPos blockPos) {
                StarLightInterface.this.lightEngine.checkBlock(blockPos.immutable());
            }

            @Override
            public void onBlockEmissionIncrease(final BlockPos blockPos, final int i) {
                this.checkBlock(blockPos);
            }

            @Override
            public boolean hasLightWork() {
                // not really correct...
                return StarLightInterface.this.hasUpdates();
            }

            @Override
            public int runUpdates(final int i, final boolean bl, final boolean bl2) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void enableLightSources(final ChunkPos chunkPos, final boolean bl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DataLayer getDataLayerData(final SectionPos pos) {
                final ChunkAccess chunk = StarLightInterface.this.getAnyChunkNow(pos.getX(), pos.getZ());

                if (chunk == null || pos.getY() < StarLightInterface.this.minLightSection || pos.getY() > StarLightInterface.this.maxLightSection) {
                    return null;
                }

                return chunk.getBlockNibbles()[pos.getY() - StarLightInterface.this.minLightSection].toVanillaNibble();
            }

            @Override
            public int getLightValue(final BlockPos blockPos) {
                return StarLightInterface.this.getBlockLightValue(blockPos, StarLightInterface.this.getAnyChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4));
            }

            @Override
            public void updateSectionStatus(final SectionPos pos, final boolean notReady) {
                StarLightInterface.this.sectionChange(pos, notReady);
            }
        };
        this.lightQueue = new io.papermc.paper.chunk.system.light.LightQueue(this); // Paper - replace light queue
    }

    protected int getSkyLightValue(final BlockPos blockPos, final ChunkAccess chunk) {
        if (!this.hasSkyLight) {
            return 0;
        }
        final int x = blockPos.getX();
        int y = blockPos.getY();
        final int z = blockPos.getZ();

        final int minSection = this.minSection;
        final int maxSection = this.maxSection;
        final int minLightSection = this.minLightSection;
        final int maxLightSection = this.maxLightSection;

        if (chunk == null || (!this.isClientSide && !chunk.isLightCorrect()) || !chunk.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
            return 15;
        }

        int sectionY = y >> 4;

        if (sectionY > maxLightSection) {
            return 15;
        }

        if (sectionY < minLightSection) {
            sectionY = minLightSection;
            y = sectionY << 4;
        }

        final SWMRNibbleArray[] nibbles = chunk.getSkyNibbles();
        final SWMRNibbleArray immediate = nibbles[sectionY - minLightSection];

        if (!immediate.isNullNibbleVisible()) {
            return immediate.getVisible(x, y, z);
        }

        final boolean[] emptinessMap = chunk.getSkyEmptinessMap();

        if (emptinessMap == null) {
            return 15;
        }

        // are we above this chunk's lowest empty section?
        int lowestY = minLightSection - 1;
        for (int currY = maxSection; currY >= minSection; --currY) {
            if (emptinessMap[currY - minSection]) {
                continue;
            }

            // should always be full lit here
            lowestY = currY;
            break;
        }

        if (sectionY > lowestY) {
            return 15;
        }

        // this nibble is going to depend solely on the skylight data above it
        // find first non-null data above (there does exist one, as we just found it above)
        for (int currY = sectionY + 1; currY <= maxLightSection; ++currY) {
            final SWMRNibbleArray nibble = nibbles[currY - minLightSection];
            if (!nibble.isNullNibbleVisible()) {
                return nibble.getVisible(x, 0, z);
            }
        }

        // should never reach here
        return 15;
    }

    protected int getBlockLightValue(final BlockPos blockPos, final ChunkAccess chunk) {
        if (!this.hasBlockLight) {
            return 0;
        }
        final int y = blockPos.getY();
        final int cy = y >> 4;

        final int minLightSection = this.minLightSection;
        final int maxLightSection = this.maxLightSection;

        if (cy < minLightSection || cy > maxLightSection) {
            return 0;
        }

        if (chunk == null) {
            return 0;
        }

        final SWMRNibbleArray nibble = chunk.getBlockNibbles()[cy - minLightSection];
        return nibble.getVisible(blockPos.getX(), y, blockPos.getZ());
    }

    public int getRawBrightness(final BlockPos pos, final int ambientDarkness) {
        final ChunkAccess chunk = this.getAnyChunkNow(pos.getX() >> 4, pos.getZ() >> 4);

        final int sky = this.getSkyLightValue(pos, chunk) - ambientDarkness;
        // Don't fetch the block light level if the skylight level is 15, since the value will never be higher.
        if (sky == 15) return 15;
        final int block = this.getBlockLightValue(pos, chunk);
        return Math.max(sky, block);
    }

    public LayerLightEventListener getSkyReader() {
        return this.skyReader;
    }

    public LayerLightEventListener getBlockReader() {
        return this.blockReader;
    }

    public boolean isClientSide() {
        return this.isClientSide;
    }

    public ChunkAccess getAnyChunkNow(final int chunkX, final int chunkZ) {
        if (this.world == null) {
            // empty world
            return null;
        }

        final ServerChunkCache chunkProvider =  ((ServerLevel)this.world).getChunkSource();
        final LevelChunk fullLoaded = chunkProvider.getChunkAtIfLoadedImmediately(chunkX, chunkZ);
        if (fullLoaded != null) {
            return fullLoaded;
        }

        return chunkProvider.getChunkAtImmediately(chunkX, chunkZ);
    }

    public boolean hasUpdates() {
        return !this.lightQueue.isEmpty();
    }

    public Level getWorld() {
        return this.world;
    }

    public LightChunkGetter getLightAccess() {
        return this.lightAccess;
    }

    public final SkyStarLightEngine getSkyLightEngine() { // Paper - public
        if (this.cachedSkyPropagators == null) {
            return null;
        }
        final SkyStarLightEngine ret;
        synchronized (this.cachedSkyPropagators) {
            ret = this.cachedSkyPropagators.pollFirst();
        }

        if (ret == null) {
            return new SkyStarLightEngine(this.world);
        }
        return ret;
    }

    public final void releaseSkyLightEngine(final SkyStarLightEngine engine) { // Paper - public
        if (this.cachedSkyPropagators == null) {
            return;
        }
        synchronized (this.cachedSkyPropagators) {
            this.cachedSkyPropagators.addFirst(engine);
        }
    }

    public final BlockStarLightEngine getBlockLightEngine() { // Paper - public
        if (this.cachedBlockPropagators == null) {
            return null;
        }
        final BlockStarLightEngine ret;
        synchronized (this.cachedBlockPropagators) {
            ret = this.cachedBlockPropagators.pollFirst();
        }

        if (ret == null) {
            return new BlockStarLightEngine(this.world);
        }
        return ret;
    }

    public final void releaseBlockLightEngine(final BlockStarLightEngine engine) { // Paper - public
        if (this.cachedBlockPropagators == null) {
            return;
        }
        synchronized (this.cachedBlockPropagators) {
            this.cachedBlockPropagators.addFirst(engine);
        }
    }

    public CompletableFuture<Void> blockChange(final BlockPos pos) {
        if (this.world == null || pos.getY() < WorldUtil.getMinBlockY(this.world) || pos.getY() > WorldUtil.getMaxBlockY(this.world)) { // empty world
            return null;
        }

        return this.lightQueue.queueBlockChange(pos);
    }

    public CompletableFuture<Void> sectionChange(final SectionPos pos, final boolean newEmptyValue) {
        if (this.world == null) { // empty world
            return null;
        }

        return this.lightQueue.queueSectionChange(pos, newEmptyValue);
    }

    public void forceLoadInChunk(final ChunkAccess chunk, final Boolean[] emptySections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.forceHandleEmptySectionChanges(this.lightAccess, chunk, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.forceHandleEmptySectionChanges(this.lightAccess, chunk, emptySections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void loadInChunk(final int chunkX, final int chunkZ, final Boolean[] emptySections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.handleEmptySectionChanges(this.lightAccess, chunkX, chunkZ, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.handleEmptySectionChanges(this.lightAccess, chunkX, chunkZ, emptySections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void lightChunk(final ChunkAccess chunk, final Boolean[] emptySections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.light(this.lightAccess, chunk, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.light(this.lightAccess, chunk, emptySections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void relightChunks(final Set<ChunkPos> chunks, final Consumer<ChunkPos> chunkLightCallback,
                              final IntConsumer onComplete) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.relightChunks(this.lightAccess, chunks, blockEngine == null ? chunkLightCallback : null,
                        blockEngine == null ? onComplete : null);
            }
            if (blockEngine != null) {
                blockEngine.relightChunks(this.lightAccess, chunks, chunkLightCallback, onComplete);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void checkChunkEdges(final int chunkX, final int chunkZ) {
        this.checkSkyEdges(chunkX, chunkZ);
        this.checkBlockEdges(chunkX, chunkZ);
    }

    public void checkSkyEdges(final int chunkX, final int chunkZ) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
        }
    }

    public void checkBlockEdges(final int chunkX, final int chunkZ) {
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        try {
            if (blockEngine != null) {
                blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ);
            }
        } finally {
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void checkSkyEdges(final int chunkX, final int chunkZ, final ShortCollection sections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, sections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
        }
    }

    public void checkBlockEdges(final int chunkX, final int chunkZ, final ShortCollection sections) {
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        try {
            if (blockEngine != null) {
                blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, sections);
            }
        } finally {
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void scheduleChunkLight(final ChunkPos pos, final Runnable run) {
        GoldenForge.LOGGER.error("scheduleChunkLight No longer implemented, use the new lightQueue field to queue tasks");
    }

    public void removeChunkTasks(final ChunkPos pos) {
        throw new UnsupportedOperationException("No longer implemented, use the new lightQueue field to queue tasks"); // Paper - replace light queue
    }

    public void propagateChanges() {
        throw new UnsupportedOperationException("No longer implemented, task draining is now performed by the light thread"); // Paper - replace light queue
    }

    protected static final class LightQueue {

        protected final Long2ObjectLinkedOpenHashMap<ChunkTasks> chunkTasks = new Long2ObjectLinkedOpenHashMap<>();
        protected final StarLightInterface manager;

        public LightQueue(final StarLightInterface manager) {
            this.manager = manager;
        }

        public synchronized boolean isEmpty() {
            return this.chunkTasks.isEmpty();
        }

        public synchronized CompletableFuture<Void> queueBlockChange(final BlockPos pos) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);
            tasks.changedPositions.add(pos.immutable());
            return tasks.onComplete;
        }

        public synchronized CompletableFuture<Void> queueSectionChange(final SectionPos pos, final boolean newEmptyValue) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);

            if (tasks.changedSectionSet == null) {
                tasks.changedSectionSet = new Boolean[this.manager.maxSection - this.manager.minSection + 1];
            }
            tasks.changedSectionSet[pos.getY() - this.manager.minSection] = Boolean.valueOf(newEmptyValue);

            return tasks.onComplete;
        }

        public synchronized CompletableFuture<Void> queueChunkLighting(final ChunkPos pos, final Runnable lightTask) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);
            if (tasks.lightTasks == null) {
                tasks.lightTasks = new ArrayList<>();
            }
            tasks.lightTasks.add(lightTask);

            return tasks.onComplete;
        }

        public synchronized CompletableFuture<Void> queueChunkSkylightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);

            ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksSky;
            if (queuedEdges == null) {
                queuedEdges = tasks.queuedEdgeChecksSky = new ShortOpenHashSet();
            }
            queuedEdges.addAll(sections);

            return tasks.onComplete;
        }

        public synchronized CompletableFuture<Void> queueChunkBlocklightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);

            ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksBlock;
            if (queuedEdges == null) {
                queuedEdges = tasks.queuedEdgeChecksBlock = new ShortOpenHashSet();
            }
            queuedEdges.addAll(sections);

            return tasks.onComplete;
        }

        public void removeChunk(final ChunkPos pos) {
            final ChunkTasks tasks;
            synchronized (this) {
                tasks = this.chunkTasks.remove(CoordinateUtils.getChunkKey(pos));
            }
            if (tasks != null) {
                tasks.onComplete.complete(null);
            }
        }

        public synchronized ChunkTasks removeFirstTask() {
            if (this.chunkTasks.isEmpty()) {
                return null;
            }
            return this.chunkTasks.removeFirst();
        }

        protected static final class ChunkTasks {

            public final Set<BlockPos> changedPositions = new ObjectOpenHashSet<>();
            public Boolean[] changedSectionSet;
            public ShortOpenHashSet queuedEdgeChecksSky;
            public ShortOpenHashSet queuedEdgeChecksBlock;
            public List<Runnable> lightTasks;

            public final CompletableFuture<Void> onComplete = new CompletableFuture<>();

            public final long chunkCoordinate;

            public ChunkTasks(final long chunkCoordinate) {
                this.chunkCoordinate = chunkCoordinate;
            }
        }
    }
}
