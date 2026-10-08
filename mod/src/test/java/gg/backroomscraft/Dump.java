package gg.backroomscraft;

import gg.backroomscraft.bake.BakedLevel;
import gg.backroomscraft.bake.CollisionBake;
import gg.backroomscraft.bake.LevelBake;
import gg.backroomscraft.bake.LightBake;
import gg.backroomscraft.etb.AnimReader;
import gg.backroomscraft.etb.PakArchive;
import gg.backroomscraft.etb.SkeletalMeshReader;
import gg.backroomscraft.etb.Skinning;
import gg.backroomscraft.etb.SoundReader;
import gg.backroomscraft.etb.SteamLocator;
import gg.backroomscraft.etb.TextureReader;
import gg.backroomscraft.gen.Sheets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Developer check, run by hand: bakes Level 0 from the installed Escape the Backrooms outside Minecraft, prints what it
 * found and writes pictures to look at (a floor plan with the baked light, and the textures). Nothing it writes is part
 * of the mod.
 *
 * <pre>gradlew dump -PdumpArgs="&lt;output folder&gt;"</pre>
 */
public final class Dump {
	public static void main(String[] args) throws Exception {
		Path out = Path.of(args[0]);
		Files.createDirectories(out);
		Path game = SteamLocator.findGame();
		System.out.println("Escape the Backrooms: " + game);
		long t0 = System.nanoTime();
		PakArchive pak = PakArchive.open(SteamLocator.paks(game));
		System.out.println("files: " + pak.size() + " in " + ms(t0) + " ms");

		Sheets.Level level = Sheets.LEVELS.get(0);
		t0 = System.nanoTime();
		LevelBake bake = new LevelBake(pak, level);
		String[] last = {""};
		BakedLevel baked = bake.bake((stage, fraction) -> {
			if (!stage.equals(last[0])) {
				System.out.println("  stage " + stage);
				last[0] = stage;
			}
		});
		System.out.println("baked in " + ms(t0) + " ms: " + bake.instances + " instances of " + bake.meshesRead + " meshes, " + bake.trianglesIn
				+ " source triangles");
		long vertices = 0, triangles = 0;
		for (BakedLevel.Batch b : baked.batches) {
			vertices += b.vertexCount();
			triangles += b.indices.length / 3;
			System.out.printf("  %7d v %7d t  tint %.2f %.2f %.2f  tile x%s%s%s%s  %s  <- %s%n", b.vertexCount(), b.indices.length / 3, b.tint[0], b.tint[1],
					b.tint[2], "", b.emissive > 0 ? " EMISSIVE" : "", b.masked ? " MASKED" : "", b.twoSided ? " 2SIDED" : "", b.material,
					b.texture.isEmpty() ? "(no texture)" : b.texture);
		}
		System.out.println("total " + vertices + " vertices, " + triangles + " triangles in " + baked.batches.size() + " batches");
		System.out.println("collision blocks " + baked.collision.size() + ", lamps " + baked.lamps.size() + ", markers " + baked.markers.size());
		System.out.println("bounds " + baked.min[0] + " " + baked.min[1] + " " + baked.min[2] + " .. " + baked.max[0] + " " + baked.max[1] + " " + baked.max[2]);
		bake.problems.forEach((what, n) -> System.out.println("  PROBLEM x" + n + "  " + what));
		for (BakedLevel.Marker m : baked.markers) {
			if (m.className().equals("PlayerStart") || m.className().equals(level.exitClass()) || m.className().equals(level.entityClass())
					|| m.className().contains("DroppedItem")) {
				System.out.printf("  marker %s %s [%s] at %.1f %.1f %.1f yaw %.0f%n", m.className(), m.name(), m.tag(), m.x(), m.y(), m.z(), m.yaw());
			}
		}

		t0 = System.nanoTime();
		Path cache = out.resolve("level0.bin");
		baked.save(cache, 1, pak.stamp());
		BakedLevel again = BakedLevel.load(cache, 1, pak.stamp());
		System.out.println("cache " + Files.size(cache) / 1024 + " KB, saved and loaded in " + ms(t0) + " ms, same: "
				+ (again != null && again.batches.size() == baked.batches.size() && again.collision.size() == baked.collision.size()));

		floorPlan(baked, level, out.resolve("level0_plan.png"), level.floorY());
		reach(baked, level, out.resolve("level0_reach.png"));
		Set<String> textures = new LinkedHashSet<>();
		for (BakedLevel.Batch b : baked.batches) {
			if (!b.texture.isEmpty()) {
				textures.add(b.texture);
			}
		}
		int shown = 0;
		for (String texture : textures) {
			try {
				TextureReader.Texture t = TextureReader.read(pak, texture, 256);
				if (shown++ < 12) {
					BufferedImage image = new BufferedImage(t.width(), t.height(), BufferedImage.TYPE_INT_ARGB);
					image.setRGB(0, 0, t.width(), t.height(), t.mips().get(0), 0, t.width());
					ImageIO.write(image, "png", out.resolve("tex_" + texture.substring(texture.lastIndexOf('/') + 1) + ".png").toFile());
				}
			} catch (Exception e) {
				System.out.println("  PROBLEM texture " + texture + ": " + e.getMessage());
			}
		}
		System.out.println(textures.size() + " textures checked");
		for (Sheets.Sound sound : Sheets.SOUNDS) {
			for (String path : sound.paths()) {
				try {
					System.out.println("  sound " + sound.id() + ": " + SoundReader.readOgg(pak, path).length + " bytes of Ogg");
				} catch (Exception e) {
					System.out.println("  PROBLEM sound " + path + ": " + e.getMessage());
				}
			}
		}
		for (Sheets.Creature creature : Sheets.ENTITIES) {
			t0 = System.nanoTime();
			SkeletalMeshReader.SkeletalMesh mesh = SkeletalMeshReader.read(pak, creature.mesh());
			System.out.println("creature " + creature.id() + ": " + mesh.vertexCount() + " vertices, " + mesh.indices().length / 3 + " triangles, "
					+ mesh.boneNames().size() + " bones, materials " + mesh.materials() + " in " + ms(t0) + " ms");
			Skinning skinning = new Skinning(mesh);
			float[] skin = new float[mesh.boneNames().size() * 12], pos = new float[mesh.vertexCount() * 3], nor = new float[mesh.vertexCount() * 3];
			String[][] animations = {{"rest", null}, {"idle", creature.animIdle()}, {"run", creature.animRun()}, {"attack", creature.animAttack()}};
			for (String[] a : animations) {
				AnimReader.Animation animation = a[1] == null ? null : AnimReader.read(pak, a[1]);
				for (int step = 0; step < (animation == null ? 1 : 3); step++) {
					float time = animation == null ? 0 : animation.length() * step / 3f;
					t0 = System.nanoTime();
					skinning.pose(animation, time, skin);
					skinning.skin(skin, pos, nor);
					long took = ms(t0);
					float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
					for (int i = 0; i < pos.length; i++) {
						lo[i % 3] = Math.min(lo[i % 3], pos[i]);
						hi[i % 3] = Math.max(hi[i % 3], pos[i]);
					}
					System.out.printf("  %s t=%.2f (%s frames, %.2f s): x %.0f..%.0f y %.0f..%.0f z %.0f..%.0f cm, posed in %d ms%n", a[0], time,
							animation == null ? "-" : animation.frames(), animation == null ? 0 : animation.length(), lo[0], hi[0], lo[1], hi[1], lo[2], hi[2], took);
					picture(pos, mesh.indices(), lo, hi, out.resolve("creature_" + creature.id() + "_" + a[0] + "_" + step + ".png"));
				}
			}
		}
		pak.close();
	}

	/** A front view of a posed mesh (Unreal x across, z up, y as depth), shaded by how much each triangle faces the viewer. */
	private static void picture(float[] pos, int[] indices, float[] lo, float[] hi, Path file) throws Exception {
		int h = 480;
		float scale = (h - 20) / Math.max(1e-3f, Math.max(hi[2] - lo[2], hi[0] - lo[0]));
		int w = Math.max(64, Math.round((hi[0] - lo[0]) * scale) + 20);
		float[] depth = new float[w * h];
		java.util.Arrays.fill(depth, Float.MAX_VALUE);
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int t = 0; t + 2 < indices.length; t += 3) {
			float[] sx = new float[3], sy = new float[3], sz = new float[3];
			for (int c = 0; c < 3; c++) {
				int v = indices[t + c] * 3;
				sx[c] = (pos[v] - lo[0]) * scale + 10;
				sy[c] = h - 10 - (pos[v + 2] - lo[2]) * scale;
				sz[c] = pos[v + 1];
			}
			float area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0]);
			if (Math.abs(area) < 1e-6f) {
				continue;
			}
			float ux = pos[indices[t + 1] * 3] - pos[indices[t] * 3], uy = pos[indices[t + 1] * 3 + 1] - pos[indices[t] * 3 + 1],
					uz = pos[indices[t + 1] * 3 + 2] - pos[indices[t] * 3 + 2];
			float vx = pos[indices[t + 2] * 3] - pos[indices[t] * 3], vy = pos[indices[t + 2] * 3 + 1] - pos[indices[t] * 3 + 1],
					vz = pos[indices[t + 2] * 3 + 2] - pos[indices[t] * 3 + 2];
			float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			float shade = Math.abs(ny) / Math.max(1e-9f, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
			int grey = Math.round(40 + 200 * shade);
			int x0 = Math.max(0, (int) Math.min(sx[0], Math.min(sx[1], sx[2]))), x1 = Math.min(w - 1, (int) Math.max(sx[0], Math.max(sx[1], sx[2])) + 1);
			int y0 = Math.max(0, (int) Math.min(sy[0], Math.min(sy[1], sy[2]))), y1 = Math.min(h - 1, (int) Math.max(sy[0], Math.max(sy[1], sy[2])) + 1);
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					float b0 = ((sx[1] - x) * (sy[2] - y) - (sx[2] - x) * (sy[1] - y)) / area;
					float b1 = ((sx[2] - x) * (sy[0] - y) - (sx[0] - x) * (sy[2] - y)) / area;
					float b2 = 1 - b0 - b1;
					if (b0 < -0.01f || b1 < -0.01f || b2 < -0.01f) {
						continue;
					}
					float z = b0 * sz[0] + b1 * sz[1] + b2 * sz[2];
					if (z < depth[y * w + x]) {
						depth[y * w + x] = z;
						image.setRGB(x, y, grey << 16 | grey << 8 | grey);
					}
				}
			}
		}
		ImageIO.write(image, "png", file.toFile());
	}

	/**
	 * Where a player can walk to from the start points: a flood fill over half-block cells, standing wherever a
	 * 0.6 x 1.8 block body fits on something solid, stepping up at most 1.25 blocks (a jump) and dropping any height.
	 */
	private static void reach(BakedLevel baked, Sheets.Level level, Path file) throws Exception {
		CollisionBake voxels = new CollisionBake();
		voxels.blocks().putAll(baked.collision);
		int n = CollisionBake.N, half = n / 2;
		int minX = baked.min[0] * 2, minZ = baked.min[2] * 2, w = (baked.max[0] - baked.min[0] + 1) * 2, h = (baked.max[2] - baked.min[2] + 1) * 2;
		// For every cell, the heights (in voxels) a player can stand at.
		java.util.Map<Long, int[]> stands = new java.util.HashMap<>();
		java.util.function.BiFunction<Integer, Integer, int[]> standing = (cx, cz) -> stands.computeIfAbsent((long) cx << 32 | (cz & 0xffffffffL), k -> {
			java.util.List<Integer> ys = new java.util.ArrayList<>();
			int vx0 = cx * half - 1, vz0 = cz * half - 1;
			for (int vy = baked.min[1] * n; vy <= (baked.max[1] + 1) * n; vy++) {
				boolean ground = false, clear = true;
				for (int dx = 0; dx < 5 && clear; dx++) {
					for (int dz = 0; dz < 5 && clear; dz++) {
						ground |= voxels.solid(vx0 + dx, vy - 1, vz0 + dz);
						for (int dy = 0; dy < 15 && clear; dy++) {
							clear = !voxels.solid(vx0 + dx, vy + dy, vz0 + dz);
						}
					}
				}
				if (ground && clear) {
					ys.add(vy);
				}
			}
			return ys.stream().mapToInt(Integer::intValue).toArray();
		});
		java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (BakedLevel.Marker m : baked.starts(level.startTag())) {
			int cx = (int) Math.floor(m.x() * 2), cz = (int) Math.floor(m.z() * 2);
			for (int y : standing.apply(cx, cz)) {
				if (Math.abs(y - m.y() * n) < 4) {
					queue.add(new int[] {cx, cz, y});
				}
			}
		}
		int count = 0;
		while (!queue.isEmpty()) {
			int[] at = queue.poll();
			long key = ((long) at[0] & 0xfffff) << 44 | ((long) at[1] & 0xfffff) << 24 | (at[2] & 0xffffff);
			if (at[0] < minX || at[1] < minZ || at[0] >= minX + w || at[1] >= minZ + h || !seen.add(key)) {
				continue;
			}
			count++;
			image.setRGB(at[0] - minX, at[1] - minZ, at[2] < level.floorY() * n - 8 ? 0x3060ff : 0x40c040);
			int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
			for (int[] step : steps) {
				for (int y : standing.apply(at[0] + step[0], at[1] + step[1])) {
					if (y - at[2] <= 10) {
						queue.add(new int[] {at[0] + step[0], at[1] + step[1], y});
					}
				}
			}
		}
		System.out.println("reachable from the start points: " + count + " half-block cells");
		for (BakedLevel.Marker m : baked.markers) {
			boolean exit = m.className().equals(level.exitClass());
			if (!exit && !m.className().contains("DroppedItem") && !m.className().equals(level.entityClass()) && !m.className().equals("PlayerStart")) {
				continue;
			}
			boolean reached = false;
			double best = Double.MAX_VALUE;
			for (long key : seen) {
				int cx = (int) (key >> 44 & 0xfffff), cz = (int) (key >> 24 & 0xfffff);
				if (cx >= 0x80000) {
					cx -= 0x100000;
				}
				if (cz >= 0x80000) {
					cz -= 0x100000;
				}
				double d = Math.hypot(cx / 2.0 + 0.25 - m.x(), cz / 2.0 + 0.25 - m.z());
				best = Math.min(best, d);
			}
			reached = best < Sheets.Rules.EXIT_RADIUS;
			System.out.printf("  %s %s [%s] at %.1f %.1f %.1f: %s (nearest reachable cell %.1f blocks away)%n", m.className(), m.name(), m.tag(), m.x(), m.y(), m.z(),
					reached ? "REACHABLE" : "NOT REACHABLE", best);
			int px = (int) Math.floor(m.x() * 2) - minX, pz = (int) Math.floor(m.z() * 2) - minZ;
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (px + dx >= 0 && px + dx < w && pz + dz >= 0 && pz + dz < h) {
						image.setRGB(px + dx, pz + dz, exit ? 0xff2020 : m.className().equals("PlayerStart") ? 0xffffff : 0xffc000);
					}
				}
			}
		}
		ImageIO.write(image, "png", file.toFile());
	}

	/** One pixel per quarter block: walls dark, floor shaded by the light a player standing there gets. */
	private static void floorPlan(BakedLevel baked, Sheets.Level level, Path file, int floorY) throws Exception {
		int scale = 4, w = (baked.max[0] - baked.min[0] + 1) * scale, h = (baked.max[2] - baked.min[2] + 1) * scale;
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		CollisionBake voxels = new CollisionBake();
		voxels.blocks().putAll(baked.collision);
		int step = CollisionBake.N / scale;
		double sum = 0;
		int lit = 0;
		java.util.List<Float> values = new java.util.ArrayList<>();
		for (int px = 0; px < w; px++) {
			for (int pz = 0; pz < h; pz++) {
				int vx = baked.min[0] * CollisionBake.N + px * step, vz = baked.min[2] * CollisionBake.N + pz * step;
				boolean floor = false, wall = false;
				for (int dx = 0; dx < step; dx++) {
					for (int dz = 0; dz < step; dz++) {
						floor |= voxels.solid(vx + dx, floorY * CollisionBake.N - 1, vz + dz);
						for (int vy = floorY * CollisionBake.N + 6; vy < floorY * CollisionBake.N + 14; vy++) {
							wall |= voxels.solid(vx + dx, vy, vz + dz);
						}
					}
				}
				int rgb = 0x101018;
				if (wall) {
					rgb = 0x5a3c1e;
				} else if (floor) {
					float light = baked.brightness((vx + 0.5) / CollisionBake.N, floorY + 0.05, (vz + 0.5) / CollisionBake.N);
					sum += light;
					lit++;
					values.add(light);
					int v = Math.min(255, Math.round(light * 200));
					rgb = v << 16 | v << 8 | (v * 3 / 4);
				}
				image.setRGB(px, pz, rgb);
			}
		}
		for (BakedLevel.Marker m : baked.markers) {
			int colour = m.className().equals("PlayerStart") ? 0x00ff40 : m.className().equals(level.exitClass()) ? 0xff2020
					: m.className().equals(level.entityClass()) ? 0xff00ff : m.className().contains("DroppedItem") ? 0x30c0ff : 0;
			if (colour == 0) {
				continue;
			}
			int cx = Math.round((m.x() - baked.min[0]) * scale), cz = Math.round((m.z() - baked.min[2]) * scale);
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (cx + dx >= 0 && cx + dx < w && cz + dz >= 0 && cz + dz < h) {
						image.setRGB(cx + dx, cz + dz, colour);
					}
				}
			}
		}
		ImageIO.write(image, "png", file.toFile());
		java.util.Collections.sort(values);
		System.out.println("floor plan " + w + "x" + h + " -> " + file + "; mean floor light " + (lit == 0 ? 0 : sum / lit));
		if (!values.isEmpty()) {
			System.out.println("floor light percentiles: 10% " + values.get(values.size() / 10) + ", 50% " + values.get(values.size() / 2) + ", 75% "
					+ values.get(values.size() * 3 / 4) + ", 90% " + values.get(values.size() * 9 / 10) + ", max " + values.get(values.size() - 1));
		}
		for (BakedLevel.Marker m : baked.starts(level.startTag())) {
			StringBuilder column = new StringBuilder();
			for (int vy = (floorY - 2) * CollisionBake.N; vy < (floorY + 6) * CollisionBake.N; vy++) {
				if (voxels.solid((int) Math.floor(m.x() * CollisionBake.N), vy, (int) Math.floor(m.z() * CollisionBake.N))) {
					column.append(String.format(" %.3f", vy / (double) CollisionBake.N));
				}
			}
			LightBake.Lamp nearest = null;
			double best = Double.MAX_VALUE;
			for (LightBake.Lamp l : baked.lamps) {
				double d = Math.hypot(l.x() - m.x(), l.z() - m.z()) + Math.abs(l.y() - m.y()) * 0.01;
				if (d < best) {
					best = d;
					nearest = l;
				}
			}
			System.out.println("start " + m.name() + " solid voxels at y:" + column + "; light " + baked.brightness(m.x(), m.y(), m.z()) + "; nearest lamp " + nearest);
			if (nearest != null) {
				System.out.println("   ray up to lamp blocked: " + voxels.blocked(nearest.x(), m.y() + 0.3, nearest.z(), nearest.x(), nearest.y() - 0.45, nearest.z())
						+ ", under lamp light " + baked.brightness(nearest.x(), m.y(), nearest.z()));
				StringBuilder under = new StringBuilder();
				for (int vy = (floorY - 1) * CollisionBake.N; vy < (floorY + 6) * CollisionBake.N; vy++) {
					if (voxels.solid((int) Math.floor(nearest.x() * CollisionBake.N), vy, (int) Math.floor(nearest.z() * CollisionBake.N))) {
						under.append(String.format(" %.3f", vy / (double) CollisionBake.N));
					}
				}
				System.out.println("   solid under lamp at y:" + under);
			}
		}
	}

	private static long ms(long since) {
		return (System.nanoTime() - since) / 1_000_000;
	}
}
