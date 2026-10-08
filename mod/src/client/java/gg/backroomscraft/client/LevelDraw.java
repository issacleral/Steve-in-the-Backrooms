package gg.backroomscraft.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.bake.BakedLevel;
import gg.backroomscraft.gen.Sheets;
import gg.backroomscraft.world.CollisionBlock;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldTerrainRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Draws the level: the baked meshes go to the GPU once, and every frame they are drawn into the main target before
 * Minecraft's own terrain, with the level's own shader (baked lamp light, the flashlight beam, fog).
 */
public final class LevelDraw {
	private static final int VERTEX_BYTES = 28;
	private static final int PARAMS_BYTES = 48;

	// hook: level_pipeline
	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
			.withVertexShader(BackroomsCraft.id("core/level"))
			.withFragmentShader(BackroomsCraft.id("core/level"))
			.withSampler("Sampler0")
			.withUniform("LevelParams", UniformType.UNIFORM_BUFFER)
			.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, VertexFormat.Mode.TRIANGLES)
			.buildSnippet();
	private static final RenderPipeline SOLID = RenderPipeline.builder(SNIPPET).withLocation(BackroomsCraft.id("pipeline/level_solid")).build();
	private static final RenderPipeline SOLID_TWO_SIDED = RenderPipeline.builder(SNIPPET).withLocation(BackroomsCraft.id("pipeline/level_solid_two_sided"))
			.withCull(false).build();
	private static final RenderPipeline CUTOUT = RenderPipeline.builder(SNIPPET).withLocation(BackroomsCraft.id("pipeline/level_cutout"))
			.withShaderDefine("ALPHA_CUTOUT", 0.33F).build();
	private static final RenderPipeline CUTOUT_TWO_SIDED = RenderPipeline.builder(SNIPPET).withLocation(BackroomsCraft.id("pipeline/level_cutout_two_sided"))
			.withShaderDefine("ALPHA_CUTOUT", 0.33F).withCull(false).build();

	/** A run of a batch's triangles that lie in one square of the map, so squares out of sight are not drawn. */
	private record Range(float centreX, float centreZ, int firstIndex, int indexCount) {
	}

	private record Uploaded(BakedLevel.Batch batch, GpuBuffer vertices, GpuBuffer indices, List<Range> ranges, RenderPipeline pipeline) {
	}

	/** Side of those squares, in blocks. */
	private static final int CELL = 32;

	private static final List<Uploaded> BATCHES = new ArrayList<>();
	private static BakedLevel uploaded;
	private static GpuBuffer params;
	/** Whether the flashlight beam is on. */
	public static boolean flashlight;
	/** Frames the level has been drawn in, and triangles drawn in the last one, for the autotest. */
	public static int framesDrawn, trianglesDrawn;

	private LevelDraw() {
	}

	static void register() {
		// hook: level_draw
		WorldRenderEvents.START_MAIN.register(LevelDraw::draw);
		// hook: block_outline
		WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> {
			Minecraft mc = Minecraft.getInstance();
			return !(mc.hitResult instanceof BlockHitResult hit && mc.level != null && mc.level.getBlockState(hit.getBlockPos()).is(CollisionBlock.BLOCK));
		});
	}

	/** Whether the level and all its textures are on the GPU. */
	public static boolean ready() {
		return uploaded != null && EtbTextures.done();
	}

	private static void draw(WorldTerrainRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		BakedLevel level = EtbLibrary.level();
		if (mc.level == null || level == null || !mc.level.dimension().equals(BackroomsCraft.LEVEL)) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		if (uploaded != level) {
			upload(device, level);
		}
		EtbTextures.upload(6);

		Vec3 camera = context.worldState().cameraRenderState.pos;
		List<CreatureDraw.Draw> creatures = CreatureDraw.prepare(device, mc, camera, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).translate((float) -camera.x, (float) -camera.y, (float) -camera.z);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, PARAMS_BYTES)
					.putVec4(Sheets.Render.FOG_COLOUR[0], Sheets.Render.FOG_COLOUR[1], Sheets.Render.FOG_COLOUR[2], 1)
					.putVec4(Sheets.Render.FOG_START, Sheets.Render.FOG_END, Sheets.Render.EXPOSURE, Sheets.Render.EMISSIVE_BOOST)
					.putVec4(flashlight ? Sheets.Render.FLASHLIGHT_POWER : 0, (float) Math.cos(Math.toRadians(Sheets.Render.FLASHLIGHT_CONE_DEG)),
							Sheets.Render.FLASHLIGHT_RANGE, 0)
					.get();
			device.createCommandEncoder().writeToBuffer(params.slice(), data);
		}
		DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[BATCHES.size() + creatures.size()];
		Vector3f noOffset = new Vector3f();
		Matrix4f noTexture = new Matrix4f();
		for (int i = 0; i < creatures.size(); i++) {
			CreatureDraw.Draw c = creatures.get(i);
			transforms[BATCHES.size() + i] = new DynamicUniforms.Transform(new Matrix4f(modelView).mul(c.model()),
					new Vector4f(c.tint()[0], c.tint()[1], c.tint()[2], 0), noOffset, noTexture);
		}
		for (int i = 0; i < BATCHES.size(); i++) {
			BakedLevel.Batch b = BATCHES.get(i).batch();
			transforms[i] = new DynamicUniforms.Transform(modelView, new Vector4f(b.tint[0], b.tint[1], b.tint[2], b.emissive > 0 ? 1 : 0), noOffset, noTexture);
		}
		GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);

		float reach = Sheets.Render.FOG_END + CELL * 0.75F;
		Vector3f forward = context.worldState().cameraRenderState.orientation.transform(new Vector3f(0, 0, -1));
		trianglesDrawn = 0;
		RenderTarget target = mc.getMainRenderTarget();
		// Textures and the sampler may be created here, which cannot happen inside a render pass.
		GpuSampler sampler = EtbTextures.sampler();
		GpuTextureView[] views = new GpuTextureView[BATCHES.size()];
		for (int i = 0; i < views.length; i++) {
			views[i] = EtbTextures.view(BATCHES.get(i).batch().texture);
		}
		try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "BackroomsCraft level", target.getColorTextureView(),
				OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("LevelParams", params);
			for (int i = 0; i < BATCHES.size(); i++) {
				Uploaded u = BATCHES.get(i);
				pass.setPipeline(u.pipeline());
				pass.setUniform("DynamicTransforms", slices[i]);
				pass.bindTexture("Sampler0", views[i], sampler);
				pass.setVertexBuffer(0, u.vertices());
				pass.setIndexBuffer(u.indices(), VertexFormat.IndexType.INT);
				for (Range range : u.ranges()) {
					float dx = range.centreX() - (float) camera.x, dz = range.centreZ() - (float) camera.z;
					// Skip squares hidden by the fog or wholly behind the camera.
					if (dx * dx + dz * dz < reach * reach && dx * forward.x + dz * forward.z > -CELL) {
						pass.drawIndexed(0, range.firstIndex(), range.indexCount(), 1);
						trianglesDrawn += range.indexCount() / 3;
					}
				}
			}
			for (int i = 0; i < creatures.size(); i++) {
				CreatureDraw.Draw c = creatures.get(i);
				pass.setPipeline(SOLID_TWO_SIDED);
				pass.setUniform("DynamicTransforms", slices[BATCHES.size() + i]);
				pass.bindTexture("Sampler0", c.texture(), sampler);
				pass.setVertexBuffer(0, c.vertices());
				pass.setIndexBuffer(c.indices(), VertexFormat.IndexType.INT);
				pass.drawIndexed(0, 0, c.indexCount(), 1);
			}
		}
		framesDrawn++;
	}

	private static void upload(GpuDevice device, BakedLevel level) {
		for (Uploaded u : BATCHES) {
			u.vertices().close();
			u.indices().close();
		}
		BATCHES.clear();
		Set<String> textures = new LinkedHashSet<>();
		long vertices = 0, triangles = 0;
		for (BakedLevel.Batch b : level.batches) {
			int n = b.vertexCount();
			if (n == 0 || b.indices.length == 0) {
				continue;
			}
			ByteBuffer v = MemoryUtil.memAlloc(n * VERTEX_BYTES);
			ByteBuffer idx = MemoryUtil.memAlloc(b.indices.length * 4);
			try {
				for (int i = 0; i < n; i++) {
					v.putFloat(b.positions[i * 3]).putFloat(b.positions[i * 3 + 1]).putFloat(b.positions[i * 3 + 2]);
					v.putFloat(b.uvs[i * 2]).putFloat(b.uvs[i * 2 + 1]);
					v.put(b.light[i * 3]).put(b.light[i * 3 + 1]).put(b.light[i * 3 + 2]).put((byte) 255);
					v.put(b.normals[i * 3]).put(b.normals[i * 3 + 1]).put(b.normals[i * 3 + 2]).put((byte) 0);
				}
				v.flip();
				// Group the triangles by the square of the map their middle is in.
				Map<Long, IntArrayList> cells = new LinkedHashMap<>();
				for (int t = 0; t + 2 < b.indices.length; t += 3) {
					int i0 = b.indices[t], i1 = b.indices[t + 1], i2 = b.indices[t + 2];
					int cx = Math.floorDiv((int) Math.floor((b.positions[i0 * 3] + b.positions[i1 * 3] + b.positions[i2 * 3]) / 3), CELL);
					int cz = Math.floorDiv((int) Math.floor((b.positions[i0 * 3 + 2] + b.positions[i1 * 3 + 2] + b.positions[i2 * 3 + 2]) / 3), CELL);
					IntArrayList list = cells.computeIfAbsent((long) cx << 32 | (cz & 0xffffffffL), k -> new IntArrayList());
					list.add(i0);
					list.add(i1);
					list.add(i2);
				}
				List<Range> ranges = new ArrayList<>();
				IntBuffer sorted = idx.asIntBuffer();
				int first = 0;
				for (Map.Entry<Long, IntArrayList> cell : cells.entrySet()) {
					int cx = (int) (cell.getKey() >> 32), cz = (int) (long) cell.getKey();
					sorted.put(cell.getValue().elements(), 0, cell.getValue().size());
					ranges.add(new Range(cx * CELL + CELL / 2F, cz * CELL + CELL / 2F, first, cell.getValue().size()));
					first += cell.getValue().size();
				}
				String name = b.material;
				GpuBuffer vertexBuffer = device.createBuffer(() -> "BackroomsCraft vertices " + name, GpuBuffer.USAGE_VERTEX, v);
				GpuBuffer indexBuffer = device.createBuffer(() -> "BackroomsCraft indices " + name, GpuBuffer.USAGE_INDEX, idx);
				RenderPipeline pipeline = b.masked ? (b.twoSided ? CUTOUT_TWO_SIDED : CUTOUT) : (b.twoSided ? SOLID_TWO_SIDED : SOLID);
				BATCHES.add(new Uploaded(b, vertexBuffer, indexBuffer, ranges, pipeline));
			} finally {
				MemoryUtil.memFree(v);
				MemoryUtil.memFree(idx);
			}
			textures.add(b.texture);
			vertices += n;
			triangles += b.indices.length / 3;
		}
		if (params == null) {
			params = device.createBuffer(() -> "BackroomsCraft level params", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, PARAMS_BYTES);
		}
		EtbTextures.request(textures);
		uploaded = level;
		BackroomsCraft.LOGGER.info("Level on the GPU: {} batches, {} vertices, {} triangles, {} textures to load", BATCHES.size(), vertices, triangles,
				textures.size());
	}
}
