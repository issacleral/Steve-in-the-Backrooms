package gg.backroomscraft.world;

import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.bake.BakedLevel;
import gg.backroomscraft.bake.CollisionBake;
import net.fabricmc.fabric.api.registry.LandPathNodeTypesRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The level's solid space inside Minecraft: an invisible, unbreakable block whose shape is the map's 12.5 cm voxels at
 * that position. Players, mobs, arrows and block placing all meet the real geometry through it.
 */
public class CollisionBlock extends Block {
	public static final ResourceKey<Block> KEY = ResourceKey.create(Registries.BLOCK, BackroomsCraft.id("collision"));
	public static Block BLOCK;

	/** Eight layers of voxels, compared by value so equal blocks share one shape. */
	private record Pattern(long[] layers) {
		@Override
		public boolean equals(Object o) {
			return o instanceof Pattern p && Arrays.equals(layers, p.layers);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(layers);
		}
	}

	private static final Map<Pattern, VoxelShape> SHAPES = new ConcurrentHashMap<>();

	public CollisionBlock(Properties properties) {
		super(properties);
	}

	// hook: collision_block
	public static void register() {
		BLOCK = Blocks.register(KEY, CollisionBlock::new, BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noLootTable().noOcclusion()
				.sound(SoundType.EMPTY).dynamicShape().pushReaction(PushReaction.BLOCK).isValidSpawn((state, level, pos, type) -> false)
				.isSuffocating((state, level, pos) -> false).isViewBlocking((state, level, pos) -> false));
		// Mobs plan their paths around any cell with geometry above knee height (walls, and the floor they stand on);
		// cells with only low geometry stay open and are stepped over.
		LandPathNodeTypesRegistry.registerDynamic(BLOCK, (state, level, pos, neighbor) -> !neighbor && tall(pos) ? PathType.BLOCKED : null);
	}

	private static boolean tall(BlockPos pos) {
		BakedLevel level = EtbLibrary.level();
		long[] layers = level == null ? null : level.voxels(pos.asLong());
		if (layers != null) {
			for (int y = CollisionBake.N / 2; y < CollisionBake.N; y++) {
				if (layers[y] != 0) {
					return true;
				}
			}
		}
		return false;
	}

	/** The solid part of the block at a position, from the level's voxels. */
	public static VoxelShape shapeAt(BlockPos pos) {
		BakedLevel level = EtbLibrary.level();
		long[] layers = level == null ? null : level.voxels(pos.asLong());
		if (layers == null) {
			return Shapes.empty();
		}
		return SHAPES.computeIfAbsent(new Pattern(layers), CollisionBlock::build);
	}

	private static VoxelShape build(Pattern pattern) {
		int n = CollisionBake.N;
		BitSetDiscreteVoxelShape voxels = new BitSetDiscreteVoxelShape(n, n, n);
		boolean any = false;
		for (int y = 0; y < n; y++) {
			long layer = pattern.layers[y];
			for (int i = 0; i < n * n; i++) {
				if ((layer >>> i & 1) != 0) {
					voxels.fill(i % n, y, i / n);
					any = true;
				}
			}
		}
		return any ? new CubeVoxelShape(voxels) : Shapes.empty();
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeAt(pos);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapeAt(pos);
	}

	@Override
	protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return true;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0F;
	}
}
