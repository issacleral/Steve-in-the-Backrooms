package gg.backroomscraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.etb.MaterialReader;
import gg.backroomscraft.etb.PakArchive;
import gg.backroomscraft.etb.StaticMeshReader;
import gg.backroomscraft.etb.TextureReader;
import gg.backroomscraft.gen.Sheets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Draws the carried items with their Escape the Backrooms meshes wherever Minecraft draws an item: in the hand, in
 * inventory slots, lying on the floor. The meshes and textures are read from the player's game files at start-up.
 */
public final class EtbItemRenderer implements SpecialModelRenderer<Boolean> {
	/** An item's mesh in metres, centred, with its long side along z (a flashlight) or y (a can). */
	private static final class Model {
		final Identifier texture;
		float[] positions, normals, uvs;
		int[] indices;
		float[] size = new float[3];
		float handYaw, handSize;
		TextureReader.Texture image;
		volatile boolean ready;

		Model(String id) {
			this.texture = BackroomsCraft.id("dynamic/item_" + id);
		}
	}

	private static final Map<String, Model> MODELS = new ConcurrentHashMap<>();
	private final String item;

	private EtbItemRenderer(String item) {
		this.item = item;
	}

	// hook: special_model
	static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BackroomsCraft.id("etb_mesh"), Unbaked.MAP_CODEC);
		Thread thread = new Thread(EtbItemRenderer::load, "BackroomsCraft items");
		thread.setDaemon(true);
		thread.start();
	}

	private static void load() {
		try {
			while (EtbLibrary.state() == EtbLibrary.State.LOADING) {
				Thread.sleep(50);
			}
			PakArchive pak = EtbLibrary.pak();
			if (EtbLibrary.state() != EtbLibrary.State.READY || pak == null) {
				return;
			}
			MaterialReader materials = new MaterialReader(pak);
			for (Sheets.Item row : Sheets.ITEMS) {
				try {
					Model model = build(pak, materials, row);
					MODELS.put(row.id(), model);
					Minecraft.getInstance().execute(() -> upload(model));
				} catch (Exception e) {
					BackroomsCraft.LOGGER.error("Could not read the {} from Escape the Backrooms; it will be invisible", row.name(), e);
				}
			}
		} catch (Throwable e) {
			BackroomsCraft.LOGGER.error("Could not load the items from Escape the Backrooms", e);
		}
	}

	private static Model build(PakArchive pak, MaterialReader materials, Sheets.Item row) throws Exception {
		StaticMeshReader.Mesh mesh = StaticMeshReader.read(pak, row.mesh());
		Model model = new Model(row.id());
		model.handYaw = row.handYaw();
		model.handSize = row.handSize();
		// The texture of the first material that has one; the triangles of see-through materials (a lens) are left out.
		String texture = "";
		int kept = 0;
		int[] indices = new int[mesh.indices().length];
		for (StaticMeshReader.Section section : mesh.sections()) {
			MaterialReader.Material material = materials.read(section.material() < mesh.materials().size() ? mesh.materials().get(section.material()) : "");
			if (material.translucent()) {
				continue;
			}
			if (texture.isEmpty()) {
				texture = material.texture();
			}
			System.arraycopy(mesh.indices(), section.firstIndex(), indices, kept, section.triangles() * 3);
			kept += section.triangles() * 3;
		}
		model.indices = java.util.Arrays.copyOf(indices, kept);
		if (texture.isEmpty()) {
			throw new java.io.IOException("no texture for " + row.mesh());
		}
		model.image = TextureReader.read(pak, texture, 256);

		int n = mesh.vertexCount();
		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		float[] p = new float[n * 3], nor = new float[n * 3];
		for (int i = 0; i < n; i++) {
			// Unreal (x, y, z) cm to Minecraft (x, z, y) m.
			p[i * 3] = mesh.positions()[i * 3] / 100F;
			p[i * 3 + 1] = mesh.positions()[i * 3 + 2] / 100F;
			p[i * 3 + 2] = mesh.positions()[i * 3 + 1] / 100F;
			nor[i * 3] = mesh.normals()[i * 3];
			nor[i * 3 + 1] = mesh.normals()[i * 3 + 2];
			nor[i * 3 + 2] = mesh.normals()[i * 3 + 1];
			for (int c = 0; c < 3; c++) {
				lo[c] = Math.min(lo[c], p[i * 3 + c]);
				hi[c] = Math.max(hi[c], p[i * 3 + c]);
			}
		}
		int longest = hi[0] - lo[0] >= hi[1] - lo[1] && hi[0] - lo[0] >= hi[2] - lo[2] ? 0 : hi[1] - lo[1] >= hi[2] - lo[2] ? 1 : 2;
		int wanted = row.kind().equals("flashlight") ? 2 : 1;
		for (int i = 0; i < n; i++) {
			for (int c = 0; c < 3; c++) {
				p[i * 3 + c] -= (lo[c] + hi[c]) / 2;
			}
			if (longest != wanted) {
				swap(p, i * 3 + longest, i * 3 + wanted);
				swap(nor, i * 3 + longest, i * 3 + wanted);
			}
		}
		for (int c = 0; c < 3; c++) {
			model.size[c] = hi[c] - lo[c];
		}
		if (longest != wanted) {
			swap(model.size, longest, wanted);
		}
		model.positions = p;
		model.normals = nor;
		model.uvs = mesh.uvs();
		return model;
	}

	private static void swap(float[] values, int a, int b) {
		float t = values[a];
		values[a] = values[b];
		values[b] = t;
	}

	private static void upload(Model model) {
		TextureReader.Texture image = model.image;
		NativeImage pixels = new NativeImage(image.width(), image.height(), false);
		int[] argb = image.mips().get(0);
		for (int y = 0; y < image.height(); y++) {
			for (int x = 0; x < image.width(); x++) {
				pixels.setPixel(x, y, argb[y * image.width() + x] | 0xff000000);
			}
		}
		Minecraft.getInstance().getTextureManager().register(model.texture, new DynamicTexture(model.texture::toString, pixels));
		model.image = null;
		model.ready = true;
	}

	/** Whether every item's mesh and texture are loaded, for the autotest. */
	public static boolean ready() {
		for (Sheets.Item row : Sheets.ITEMS) {
			Model model = MODELS.get(row.id());
			if (model == null || !model.ready) {
				return false;
			}
		}
		return true;
	}

	@Override
	public Boolean extractArgument(ItemStack stack) {
		return Boolean.TRUE;
	}

	@Override
	public void submit(Boolean argument, ItemDisplayContext context, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil,
			int outline) {
		Model model = MODELS.get(item);
		if (model == null || !model.ready) {
			return;
		}
		float longest = Math.max(model.size[0], Math.max(model.size[1], model.size[2]));
		pose.pushPose();
		pose.translate(0.5F, 0.5F, 0.5F);
		switch (context) {
			case GUI, FIXED, ON_SHELF -> {
				// Seen from the side and a little from above, filling the slot.
				pose.mulPose(Axis.XP.rotationDegrees(20));
				pose.mulPose(Axis.YP.rotationDegrees(context == ItemDisplayContext.FIXED ? 60 : -60));
				float fit = 0.9F / longest;
				pose.scale(fit, fit, fit);
			}
			case GROUND -> {
				float fit = model.handSize * 1.2F / longest;
				pose.scale(fit, fit, fit);
			}
			default -> {
				// In a hand: larger than life, as Minecraft's own held items are, turned to point where the hand does.
				pose.mulPose(Axis.YP.rotationDegrees(model.handYaw));
				float fit = model.handSize / longest;
				pose.scale(fit, fit, fit);
			}
		}
		collector.submitCustomGeometry(pose, RenderTypes.entityCutoutNoCull(model.texture), (at, buffer) -> draw(at, buffer, model, light));
		pose.popPose();
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer buffer, Model model, int light) {
		int[] idx = model.indices;
		// The entity render types draw quads: each triangle goes in as a quad with its last corner doubled.
		for (int i = 0; i + 2 < idx.length; i += 3) {
			vertex(pose, buffer, model, idx[i], light);
			vertex(pose, buffer, model, idx[i + 1], light);
			vertex(pose, buffer, model, idx[i + 2], light);
			vertex(pose, buffer, model, idx[i + 2], light);
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Model model, int v, int light) {
		buffer.addVertex(pose, model.positions[v * 3], model.positions[v * 3 + 1], model.positions[v * 3 + 2]).setColor(-1)
				.setUv(model.uvs[v * 2], model.uvs[v * 2 + 1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, model.normals[v * 3], model.normals[v * 3 + 1], model.normals[v * 3 + 2]);
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0, 0, 0));
		output.accept(new Vector3f(1, 1, 1));
	}

	public record Unbaked(String item) implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder
				.mapCodec(instance -> instance.group(Codec.STRING.fieldOf("item").forGetter(Unbaked::item)).apply(instance, Unbaked::new));

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			return new EtbItemRenderer(item);
		}
	}
}
