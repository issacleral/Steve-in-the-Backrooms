package gg.backroomscraft.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.bake.BakedLevel;
import gg.backroomscraft.bake.LightBake;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Generates the level's dimension: a collision block wherever the map has geometry, an invisible light where the map
 * has a lamp (so Minecraft's own lighting of mobs, the player and placed blocks follows the lamps), and nothing else.
 */
public class LevelChunkGenerator extends ChunkGenerator {
	public static final MapCodec<LevelChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance
			.group(BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource))
			.apply(instance, instance.stable(LevelChunkGenerator::new)));

	public LevelChunkGenerator(BiomeSource biomeSource) {
		super(biomeSource);
	}

	// hook: chunk_generator
	public static void register() {
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, BackroomsCraft.id("level"), CODEC);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
		BakedLevel level = EtbLibrary.awaitLevel();
		ChunkPos at = chunk.getPos();
		if (level == null || at.getMaxBlockX() < level.min[0] - 1 || at.getMinBlockX() > level.max[0] + 1 || at.getMaxBlockZ() < level.min[2] - 1
				|| at.getMinBlockZ() > level.max[2] + 1) {
			return CompletableFuture.completedFuture(chunk);
		}
		BlockState solid = CollisionBlock.BLOCK.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minY = Math.max(chunk.getMinY(), level.min[1] - 1), maxY = Math.min(chunk.getMaxY(), level.max[1] + 1);
		for (int x = at.getMinBlockX(); x <= at.getMaxBlockX(); x++) {
			for (int z = at.getMinBlockZ(); z <= at.getMaxBlockZ(); z++) {
				for (int y = minY; y <= maxY; y++) {
					if (level.voxels(BlockPos.asLong(x, y, z)) != null) {
						chunk.setBlockState(pos.set(x, y, z), solid);
					}
				}
			}
		}
		BlockState light = Blocks.LIGHT.defaultBlockState();
		for (LightBake.Lamp lamp : level.lamps) {
			// Half a block along the lamp's own direction, clear of the ceiling it hangs from.
			int x = (int) Math.floor(lamp.x() + lamp.dx() * 0.5), y = (int) Math.floor(lamp.y() + lamp.dy() * 0.5), z = (int) Math.floor(lamp.z() + lamp.dz() * 0.5);
			if (x >= at.getMinBlockX() && x <= at.getMaxBlockX() && z >= at.getMinBlockZ() && z <= at.getMaxBlockZ() && y >= chunk.getMinY()
					&& y <= chunk.getMaxY() && level.voxels(BlockPos.asLong(x, y, z)) == null) {
				chunk.setBlockState(pos.set(x, y, z), light);
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk) {
	}

	@Override
	public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
	}

	@Override
	public int getGenDepth() {
		return 128;
	}

	@Override
	public int getSeaLevel() {
		return -63;
	}

	@Override
	public int getMinY() {
		return 0;
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor height, RandomState random) {
		return height.getMinY();
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
		return new NoiseColumn(height.getMinY(), new BlockState[0]);
	}

	@Override
	public void addDebugScreenInfo(List<String> lines, RandomState random, BlockPos pos) {
	}
}
