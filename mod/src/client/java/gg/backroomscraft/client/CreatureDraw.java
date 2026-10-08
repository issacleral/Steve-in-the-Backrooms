package gg.backroomscraft.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTextureView;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.bake.BakedLevel;
import gg.backroomscraft.entity.BacteriaEntity;
import gg.backroomscraft.etb.AnimReader;
import gg.backroomscraft.gen.Sheets;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the entities with their Escape the Backrooms meshes: each frame the mesh is posed with the animation that fits
 * what the entity is doing, its vertices are moved on the CPU and sent to the GPU, and LevelDraw draws them in the
 * level's own render pass so they get the same light, flashlight beam and fog as the walls.
 */
public final class CreatureDraw {
	/** One entity ready to be drawn: its vertices are relative to its feet, model turns and places them. */
	public record Draw(GpuBuffer vertices, GpuBuffer indices, int indexCount, Matrix4f model, GpuTextureView texture, float[] tint) {
	}

	private static final int VERTEX_BYTES = 28;
	/** Beyond this many blocks an entity is hidden by the fog anyway. */
	private static final double MAX_DISTANCE = Sheets.Render.FOG_END + 8;
	/** How long the attack animation shows after a hit starts, in seconds, and how much faster than recorded it plays. */
	private static final float ATTACK_SECONDS = 1.1F, ATTACK_SPEED = 1.6F;

	private static final class Slot {
		GpuBuffer vertices;
		float attackStarted = -100;
		boolean wasSwinging;
	}

	private static final Map<Integer, Slot> SLOTS = new HashMap<>();
	private static final Map<String, GpuBuffer> INDICES = new HashMap<>();
	private static float[] positions = new float[0], normals = new float[0], skin = new float[0];
	private static ByteBuffer scratch;
	/** Entities drawn in the last frame, for the autotest. */
	public static int drawn;

	private CreatureDraw() {
	}

	static void register() {
		// hook: entity_renderer
		EntityRendererRegistry.register(BacteriaEntity.TYPE, NoopRenderer::new);
	}

	/** Poses and uploads every entity near the camera. Called by LevelDraw before it opens its render pass. */
	static List<Draw> prepare(GpuDevice device, Minecraft mc, Vec3 camera, float partialTick) {
		List<Draw> draws = new ArrayList<>();
		BakedLevel level = EtbLibrary.level();
		if (mc.level == null || level == null) {
			drawn = 0;
			return draws;
		}
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof BacteriaEntity bacteria) || !bacteria.isAlive()) {
				continue;
			}
			CreatureAssets.Creature creature = CreatureAssets.get(BacteriaEntity.ROW.id());
			Vec3 at = bacteria.getPosition(partialTick);
			if (creature == null || at.distanceTo(camera) > MAX_DISTANCE) {
				continue;
			}
			Slot slot = SLOTS.computeIfAbsent(bacteria.getId(), id -> new Slot());
			float seconds = (bacteria.tickCount + partialTick) / 20F;
			if (bacteria.swinging && !slot.wasSwinging) {
				slot.attackStarted = seconds;
			}
			slot.wasSwinging = bacteria.swinging;
			AnimReader.Animation animation;
			float time;
			if (seconds - slot.attackStarted < ATTACK_SECONDS) {
				animation = creature.attack;
				time = (seconds - slot.attackStarted) * ATTACK_SPEED;
			} else if (bacteria.isChasing() || bacteria.getDeltaMovement().horizontalDistanceSqr() > 0.0004) {
				animation = creature.run;
				time = seconds * (bacteria.isChasing() ? 1.25F : 0.6F);
			} else {
				animation = creature.idle;
				time = seconds;
			}
			int n = creature.mesh.vertexCount();
			if (positions.length < n * 3) {
				positions = new float[n * 3];
				normals = new float[n * 3];
			}
			if (skin.length < creature.mesh.boneNames().size() * 12) {
				skin = new float[creature.mesh.boneNames().size() * 12];
			}
			creature.skinning.pose(animation, time, skin);
			creature.skinning.skin(skin, positions, normals);

			if (scratch == null || scratch.capacity() < n * VERTEX_BYTES) {
				if (scratch != null) {
					MemoryUtil.memFree(scratch);
				}
				scratch = MemoryUtil.memAlloc(n * VERTEX_BYTES);
			}
			scratch.clear();
			// The mesh is turned inside its actor (yaw about Unreal's z), then Unreal (x, y, z) cm becomes Minecraft (x, z, y) blocks.
			double turn = Math.toRadians(creature.row.meshYaw());
			float cos = (float) Math.cos(turn), sin = (float) Math.sin(turn), scale = creature.row.meshScale() / 100F;
			float light = Math.min(1.99F, level.brightness(at.x, at.y + creature.row.height() * 0.5, at.z));
			byte lit = (byte) Math.round(light * 128);
			float[] uvs = creature.mesh.uvs();
			for (int i = 0; i < n; i++) {
				float x = positions[i * 3], y = positions[i * 3 + 1], z = positions[i * 3 + 2];
				scratch.putFloat((x * cos - y * sin) * scale).putFloat(z * scale).putFloat((x * sin + y * cos) * scale);
				scratch.putFloat(uvs[i * 2]).putFloat(uvs[i * 2 + 1]);
				scratch.put(lit).put(lit).put(lit).put((byte) 255);
				x = normals[i * 3];
				y = normals[i * 3 + 1];
				z = normals[i * 3 + 2];
				scratch.put((byte) Math.round((x * cos - y * sin) * 127)).put((byte) Math.round(z * 127)).put((byte) Math.round((x * sin + y * cos) * 127))
						.put((byte) 0);
			}
			scratch.flip();
			if (slot.vertices == null || slot.vertices.size() < (long) n * VERTEX_BYTES) {
				if (slot.vertices != null) {
					slot.vertices.close();
				}
				slot.vertices = device.createBuffer(() -> "BackroomsCraft creature vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
						(long) n * VERTEX_BYTES);
			}
			device.createCommandEncoder().writeToBuffer(slot.vertices.slice(0, (long) n * VERTEX_BYTES), scratch);
			GpuBuffer indexBuffer = INDICES.computeIfAbsent(creature.row.id(), id -> {
				ByteBuffer idx = MemoryUtil.memAlloc(creature.mesh.indices().length * 4);
				try {
					idx.asIntBuffer().put(creature.mesh.indices());
					return device.createBuffer(() -> "BackroomsCraft creature indices", GpuBuffer.USAGE_INDEX, idx);
				} finally {
					MemoryUtil.memFree(idx);
				}
			});
			if (!creature.texture.isEmpty()) {
				EtbTextures.request(List.of(creature.texture));
			}
			// In actor space the creature faces +x; Minecraft's yaw 0 faces +z and grows clockwise seen from above.
			float yaw = Mth.rotLerp(partialTick, bacteria.yBodyRotO, bacteria.yBodyRot);
			Matrix4f model = new Matrix4f().translate((float) at.x, (float) at.y, (float) at.z).rotateY((float) Math.toRadians(-(yaw + 90)));
			draws.add(new Draw(slot.vertices, indexBuffer, creature.mesh.indices().length, model, EtbTextures.view(creature.texture), creature.tint));
		}
		drawn = draws.size();
		return draws;
	}
}
