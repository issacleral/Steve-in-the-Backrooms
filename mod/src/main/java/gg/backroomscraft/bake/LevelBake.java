package gg.backroomscraft.bake;

import gg.backroomscraft.etb.LevelReader;
import gg.backroomscraft.etb.MaterialReader;
import gg.backroomscraft.etb.PakArchive;
import gg.backroomscraft.etb.StaticMeshReader;
import gg.backroomscraft.gen.Sheets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Turns a map of Escape the Backrooms into a {@link BakedLevel}: every mesh instance is moved to its place, converted to
 * Minecraft's axes, split into small triangles and lit from the map's own lights; the same triangles become collision
 * voxels.
 *
 * <p>Minecraft (x, y, z) = Unreal (x, z, y) / 100, with Unreal z = 0 at the level's floor_y.
 */
public final class LevelBake {
	public interface Progress {
		void report(String stage, float fraction);
	}

	/** What the bake could not use, by reason: shown in the log so nothing is dropped silently. */
	public final Map<String, Integer> problems = new TreeMap<>();
	public int instances, meshesRead, trianglesIn;

	private final PakArchive pak;
	private final Sheets.Level level;
	private final MaterialReader materials;
	private final Map<String, StaticMeshReader.Mesh> meshes = new HashMap<>();
	private final Map<String, Builder> builders = new LinkedHashMap<>();
	private final CollisionBake collision = new CollisionBake();
	private final double maxEdge = Sheets.Render.SUBDIVIDE_CM / 100.0;

	public LevelBake(PakArchive pak, Sheets.Level level) {
		this.pak = pak;
		this.level = level;
		this.materials = new MaterialReader(pak);
	}

	public BakedLevel bake(Progress progress) throws IOException {
		BakedLevel out = new BakedLevel();
		List<String> maps = new ArrayList<>();
		maps.add(level.map());
		maps.addAll(level.sublevels());
		List<LevelReader.Scene> scenes = new ArrayList<>();
		for (int i = 0; i < maps.size(); i++) {
			progress.report("map", i / (float) maps.size());
			LevelReader.Scene scene = LevelReader.read(pak, maps.get(i));
			scene.problems().forEach((what, n) -> problems.merge(maps.get(0).equals(level.map()) ? what : what, n, Integer::sum));
			scenes.add(scene);
		}

		int total = scenes.stream().mapToInt(s -> s.meshes().size()).sum(), done = 0;
		for (LevelReader.Scene scene : scenes) {
			for (LevelReader.MeshInstance instance : scene.meshes()) {
				if (done++ % 256 == 0) {
					progress.report("geometry", done / (float) total);
				}
				place(instance);
			}
		}

		for (LevelReader.Scene scene : scenes) {
			for (LevelReader.Light light : scene.lights()) {
				boolean white = light.colour()[0] > 0.98f && light.colour()[1] > 0.98f && light.colour()[2] > 0.98f;
				float[] tone = white ? Sheets.Render.LAMP_COLOUR : new float[] {1, 1, 1};
				float strength = light.intensity() * Sheets.Render.LIGHT_SCALE;
				boolean spot = light.type().equals("SpotLightComponent");
				float outer = Math.min(light.outerCone(), 89), inner = Math.min(light.innerCone(), outer - 1);
				float radius = (light.radius() > 0 ? light.radius() : Sheets.Render.LIGHT_RADIUS_CM) / 100f;
				out.lamps.add(new LightBake.Lamp(light.pos()[0] / 100f, light.pos()[2] / 100f + level.floorY(), light.pos()[1] / 100f,
						light.dir()[0], light.dir()[2], light.dir()[1],
						light.colour()[0] * tone[0] * strength, light.colour()[1] * tone[1] * strength, light.colour()[2] * tone[2] * strength,
						radius, spot ? (float) Math.cos(Math.toRadians(outer)) : -2, spot ? (float) Math.cos(Math.toRadians(Math.max(0, inner))) : 1));
			}
			for (LevelReader.Start start : scene.starts()) {
				// A PlayerStart is the middle of a 184 cm capsule: the feet are 92 cm lower.
				out.markers.add(new BakedLevel.Marker("PlayerStart", start.name(), start.tags().isEmpty() ? "" : start.tags().get(0),
						start.pos()[0] / 100f, (start.pos()[2] - 92) / 100f + level.floorY() + 0.05f, start.pos()[1] / 100f, start.yaw() - 90));
			}
			for (LevelReader.Actor actor : scene.actors()) {
				out.markers.add(new BakedLevel.Marker(actor.className(), actor.name(), "", actor.pos()[0] / 100f,
						actor.pos()[2] / 100f + level.floorY(), actor.pos()[1] / 100f, actor.yaw() - 90));
			}
		}

		out.collision.putAll(collision.blocks());
		LightBake lighting = new LightBake(out.lamps, collision);
		int batch = 0;
		for (Builder builder : builders.values()) {
			progress.report("light", batch++ / (float) builders.size());
			BakedLevel.Batch b = builder.finish();
			int n = b.vertexCount();
			b.light = new byte[n * 3];
			boolean glows = b.emissive > 0;
			IntStream.range(0, n).parallel().forEach(i -> {
				float[] c = {1, 1, 1};
				if (!glows) {
					lighting.shade(b.positions[i * 3], b.positions[i * 3 + 1], b.positions[i * 3 + 2], b.normals[i * 3] / 127f,
							b.normals[i * 3 + 1] / 127f, b.normals[i * 3 + 2] / 127f, b.twoSided, c);
				}
				for (int k = 0; k < 3; k++) {
					b.light[i * 3 + k] = (byte) Math.max(0, Math.min(255, Math.round(c[k] * 128)));
				}
			});
			for (int i = 0; i < n * 3; i++) {
				int axis = i % 3;
				out.min[axis] = Math.min(out.min[axis], (int) Math.floor(b.positions[i]));
				out.max[axis] = Math.max(out.max[axis], (int) Math.floor(b.positions[i]));
			}
			out.batches.add(b);
		}
		progress.report("done", 1);
		return out;
	}

	private StaticMeshReader.Mesh mesh(String path) {
		if (!meshes.containsKey(path)) {
			StaticMeshReader.Mesh mesh = null;
			try {
				mesh = StaticMeshReader.read(pak, path);
				meshesRead++;
			} catch (IOException | RuntimeException e) {
				problems.merge("mesh not read: " + path + " (" + e.getMessage() + ")", 1, Integer::sum);
			}
			meshes.put(path, mesh);
		}
		return meshes.get(path);
	}

	private void place(LevelReader.MeshInstance instance) {
		StaticMeshReader.Mesh mesh = mesh(instance.mesh());
		if (mesh == null) {
			return;
		}
		instances++;
		float[] m = instance.matrix();
		// Normals go through the cofactor matrix, which stays right under uneven and mirrored scale.
		double[] c0 = cross(m[4], m[5], m[6], m[8], m[9], m[10]);
		double[] c1 = cross(m[8], m[9], m[10], m[0], m[1], m[2]);
		double[] c2 = cross(m[0], m[1], m[2], m[4], m[5], m[6]);
		double det = m[0] * c0[0] + m[1] * c0[1] + m[2] * c0[2];
		double flip = det < 0 ? -1 : 1;
		int n = mesh.vertexCount();
		double[] pos = new double[n * 3];
		float[] nor = new float[n * 3];
		for (int i = 0; i < n; i++) {
			double x = mesh.positions()[i * 3], y = mesh.positions()[i * 3 + 1], z = mesh.positions()[i * 3 + 2];
			pos[i * 3] = (x * m[0] + y * m[4] + z * m[8] + m[12]) / 100.0;
			pos[i * 3 + 2] = (x * m[1] + y * m[5] + z * m[9] + m[13]) / 100.0;
			pos[i * 3 + 1] = (x * m[2] + y * m[6] + z * m[10] + m[14]) / 100.0 + level.floorY();
			double nx = mesh.normals()[i * 3], ny = mesh.normals()[i * 3 + 1], nz = mesh.normals()[i * 3 + 2];
			double wx = (nx * c0[0] + ny * c1[0] + nz * c2[0]) * flip, wy = (nx * c0[1] + ny * c1[1] + nz * c2[1]) * flip,
					wz = (nx * c0[2] + ny * c1[2] + nz * c2[2]) * flip;
			double len = Math.sqrt(wx * wx + wy * wy + wz * wz);
			if (len > 1e-12) {
				nor[i * 3] = (float) (wx / len);
				nor[i * 3 + 2] = (float) (wy / len);
				nor[i * 3 + 1] = (float) (wz / len);
			}
		}
		for (StaticMeshReader.Section section : mesh.sections()) {
			int slot = section.material();
			String path = slot >= 0 && slot < instance.materials().size() && !instance.materials().get(slot).isEmpty() ? instance.materials().get(slot)
					: slot >= 0 && slot < mesh.materials().size() ? mesh.materials().get(slot) : "";
			MaterialReader.Material material = materials.read(path);
			Builder builder = null;
			if (instance.visible() && !material.translucent()) {
				builder = builders.computeIfAbsent(path, k -> new Builder(material));
			} else if (instance.visible()) {
				problems.merge("translucent material not drawn: " + path, 1, Integer::sum);
			}
			for (int t = 0; t < section.triangles(); t++) {
				int ia = mesh.indices()[section.firstIndex() + t * 3], ib = mesh.indices()[section.firstIndex() + t * 3 + 1],
						ic = mesh.indices()[section.firstIndex() + t * 3 + 2];
				// Order the corners counter-clockwise as seen from the side the normals point to.
				double[] g = cross(pos[ib * 3] - pos[ia * 3], pos[ib * 3 + 1] - pos[ia * 3 + 1], pos[ib * 3 + 2] - pos[ia * 3 + 2],
						pos[ic * 3] - pos[ia * 3], pos[ic * 3 + 1] - pos[ia * 3 + 1], pos[ic * 3 + 2] - pos[ia * 3 + 2]);
				double facing = g[0] * (nor[ia * 3] + nor[ib * 3] + nor[ic * 3]) + g[1] * (nor[ia * 3 + 1] + nor[ib * 3 + 1] + nor[ic * 3 + 1])
						+ g[2] * (nor[ia * 3 + 2] + nor[ib * 3 + 2] + nor[ic * 3 + 2]);
				if (facing < 0) {
					int swap = ib;
					ib = ic;
					ic = swap;
				}
				trianglesIn++;
				Vertex a = vertex(mesh, pos, nor, ia, material.tiling()), b = vertex(mesh, pos, nor, ib, material.tiling()),
						c = vertex(mesh, pos, nor, ic, material.tiling());
				if (builder != null) {
					split(builder, a, b, c, 0);
				}
				if (instance.collides()) {
					collision.triangle(new double[] {a.x, a.y, a.z}, new double[] {b.x, b.y, b.z}, new double[] {c.x, c.y, c.z});
				}
			}
		}
	}

	private static Vertex vertex(StaticMeshReader.Mesh mesh, double[] pos, float[] nor, int i, float tiling) {
		return new Vertex(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], mesh.uvs()[i * 2] * tiling, mesh.uvs()[i * 2 + 1] * tiling, nor[i * 3],
				nor[i * 3 + 1], nor[i * 3 + 2]);
	}

	/** Halves the longest edge until no edge is longer than the limit, so light baked at the corners is smooth. */
	private void split(Builder builder, Vertex a, Vertex b, Vertex c, int depth) {
		double ab = a.distance2(b), bc = b.distance2(c), ca = c.distance2(a);
		double longest = Math.max(ab, Math.max(bc, ca));
		if (depth >= 14 || longest <= maxEdge * maxEdge) {
			builder.triangle(a, b, c);
		} else if (longest == ab) {
			Vertex m = a.middle(b);
			split(builder, a, m, c, depth + 1);
			split(builder, m, b, c, depth + 1);
		} else if (longest == bc) {
			Vertex m = b.middle(c);
			split(builder, a, b, m, depth + 1);
			split(builder, a, m, c, depth + 1);
		} else {
			Vertex m = c.middle(a);
			split(builder, a, b, m, depth + 1);
			split(builder, m, b, c, depth + 1);
		}
	}

	private static double[] cross(double ax, double ay, double az, double bx, double by, double bz) {
		return new double[] {ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
	}

	private record Vertex(double x, double y, double z, float u, float v, float nx, float ny, float nz) {
		double distance2(Vertex o) {
			return (x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) + (z - o.z) * (z - o.z);
		}

		Vertex middle(Vertex o) {
			float mx = nx + o.nx, my = ny + o.ny, mz = nz + o.nz;
			float len = (float) Math.sqrt(mx * mx + my * my + mz * mz);
			if (len < 1e-6f) {
				len = 1;
			}
			return new Vertex((x + o.x) / 2, (y + o.y) / 2, (z + o.z) / 2, (u + o.u) / 2, (v + o.v) / 2, mx / len, my / len, mz / len);
		}
	}

	private record Key(int x, int y, int z, int u, int v, int n) {
	}

	/** Collects the triangles of one material, sharing vertices that are the same. */
	private static final class Builder {
		private final MaterialReader.Material material;
		private final Map<Key, Integer> index = new HashMap<>();
		private float[] positions = new float[3 * 1024], uvs = new float[2 * 1024];
		private byte[] normals = new byte[3 * 1024];
		private int[] indices = new int[3 * 1024];
		private int vertexCount, indexCount;

		Builder(MaterialReader.Material material) {
			this.material = material;
		}

		void triangle(Vertex a, Vertex b, Vertex c) {
			if (indexCount + 3 > indices.length) {
				indices = java.util.Arrays.copyOf(indices, indices.length * 2);
			}
			indices[indexCount++] = add(a);
			indices[indexCount++] = add(b);
			indices[indexCount++] = add(c);
		}

		private int add(Vertex v) {
			byte nx = (byte) Math.round(v.nx * 127), ny = (byte) Math.round(v.ny * 127), nz = (byte) Math.round(v.nz * 127);
			Key key = new Key((int) Math.round(v.x * 1024), (int) Math.round(v.y * 1024), (int) Math.round(v.z * 1024), Math.round(v.u * 2048),
					Math.round(v.v * 2048), (nx & 0xff) << 16 | (ny & 0xff) << 8 | (nz & 0xff));
			Integer at = index.get(key);
			if (at != null) {
				return at;
			}
			if (vertexCount * 3 + 3 > positions.length) {
				positions = java.util.Arrays.copyOf(positions, positions.length * 2);
				uvs = java.util.Arrays.copyOf(uvs, uvs.length * 2);
				normals = java.util.Arrays.copyOf(normals, normals.length * 2);
			}
			positions[vertexCount * 3] = (float) v.x;
			positions[vertexCount * 3 + 1] = (float) v.y;
			positions[vertexCount * 3 + 2] = (float) v.z;
			uvs[vertexCount * 2] = v.u;
			uvs[vertexCount * 2 + 1] = v.v;
			normals[vertexCount * 3] = nx;
			normals[vertexCount * 3 + 1] = ny;
			normals[vertexCount * 3 + 2] = nz;
			index.put(key, vertexCount);
			return vertexCount++;
		}

		BakedLevel.Batch finish() {
			BakedLevel.Batch b = new BakedLevel.Batch();
			b.material = material.path();
			b.texture = material.texture();
			b.tint = material.tint();
			b.emissive = material.emissive();
			b.masked = material.masked();
			b.twoSided = material.twoSided();
			b.positions = java.util.Arrays.copyOf(positions, vertexCount * 3);
			b.uvs = java.util.Arrays.copyOf(uvs, vertexCount * 2);
			b.normals = java.util.Arrays.copyOf(normals, vertexCount * 3);
			b.indices = java.util.Arrays.copyOf(indices, indexCount);
			index.clear();
			return b;
		}
	}
}
