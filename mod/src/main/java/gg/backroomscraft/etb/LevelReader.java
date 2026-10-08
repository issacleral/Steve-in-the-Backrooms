package gg.backroomscraft.etb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rebuilds where everything is in a map: every mesh instance with its world matrix, the lights, the player starts and
 * the other actors. A component's values come from the level first, then from the blueprint it was made from.
 *
 * <p>Matrices are Unreal's: 16 floats, row vectors (world = v * M), centimetres, z up.
 */
public final class LevelReader {
	public record MeshInstance(String mesh, List<String> materials, float[] matrix, boolean visible, boolean collides, String owner,
			String ownerClass) {
	}

	/** @param type SpotLightComponent, PointLightComponent or RectLightComponent; intensity is in Unreal's unitless units */
	public record Light(String type, float[] pos, float[] dir, float intensity, float[] colour, float radius, float outerCone,
			float innerCone) {
	}

	public record Start(String name, float[] pos, float yaw, List<String> tags) {
	}

	public record Actor(String className, String name, float[] pos, float yaw) {
	}

	public record Scene(List<MeshInstance> meshes, List<Light> lights, List<Start> starts, List<Actor> actors, Map<String, Integer> problems) {
	}

	/** An object reference that still makes sense after properties of several packages are merged. */
	private record Ref(UPackage pkg, int index, String path, String name) {
	}

	private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

	private final PakArchive pak;
	private final Map<String, UPackage> packages = new HashMap<>();
	private UPackage level;
	private final Map<Integer, Map<String, Object>> merged = new HashMap<>();
	private final Map<Integer, float[]> world = new HashMap<>();

	private LevelReader(PakArchive pak) {
		this.pak = pak;
	}

	/** Reads one map, e.g. /Game/Maps/Level0. Sublevels are separate maps: read each and join the scenes. */
	public static Scene read(PakArchive pak, String gamePath) throws IOException {
		LevelReader reader = new LevelReader(pak);
		reader.level = new UPackage(pak, UPackage.pakPath(gamePath));
		return reader.scene();
	}

	private Scene scene() {
		UPackage k = level;
		List<MeshInstance> meshes = new ArrayList<>();
		List<Light> lights = new ArrayList<>();
		List<Start> starts = new ArrayList<>();
		List<Actor> actors = new ArrayList<>();
		Map<String, Integer> problems = new TreeMap<>();
		for (int i = 1; i <= k.exports.size(); i++) {
			UPackage.Export e = k.exports.get(i - 1);
			String cls = k.className(e);
			UPackage.Export outer = k.export(e.outerIndex());
			String outerClass = outer == null ? "" : k.className(outer);
			try {
				switch (cls) {
					case "StaticMeshComponent", "InstancedStaticMeshComponent", "HierarchicalInstancedStaticMeshComponent" -> {
						Map<String, Object> d = props(i);
						if (!(d.get("StaticMesh") instanceof Ref mesh) || mesh.path == null) {
							count(problems, "mesh component without a mesh (" + outerClass + ")");
							continue;
						}
						boolean visible = !Boolean.FALSE.equals(d.get("bVisible")) && !Boolean.TRUE.equals(d.get("bHiddenInGame"));
						if (outer != null && Boolean.TRUE.equals(props(e.outerIndex()).get("bHidden"))) {
							visible = false;
						}
						List<String> materials = new ArrayList<>();
						if (d.get("OverrideMaterials") instanceof List<?> list) {
							for (Object m : list) {
								materials.add(m instanceof Ref r && r.path != null ? r.path : "");
							}
						}
						boolean collides = true;
						if (d.get("BodyInstance") instanceof UPackage.Props body) {
							collides = !body.str("CollisionEnabled", "").endsWith("NoCollision")
									&& !body.str("CollisionProfileName", "").equals("NoCollision");
						}
						float[] w = worldMatrix(i);
						if (cls.equals("StaticMeshComponent")) {
							meshes.add(new MeshInstance(mesh.path, materials, w, visible, collides, outer == null ? "" : outer.name(), outerClass));
						} else {
							for (float[] instance : instances(i)) {
								meshes.add(new MeshInstance(mesh.path, materials, mul(instance, w), visible, collides,
										outer == null ? "" : outer.name(), outerClass));
							}
						}
					}
					case "SpotLightComponent", "PointLightComponent", "RectLightComponent" -> {
						Map<String, Object> d = props(i);
						if (Boolean.FALSE.equals(d.get("bVisible"))) {
							continue;
						}
						float[] w = worldMatrix(i);
						float[] colour = d.get("LightColor") instanceof float[] c ? new float[] {c[0] / 255f, c[1] / 255f, c[2] / 255f}
								: new float[] {1, 1, 1};
						float intensity = number(d, "Intensity", cls.equals("RectLightComponent") ? 8 : 5000);
						String units = d.get("IntensityUnits") instanceof String s ? s : "";
						if (units.endsWith("Candelas")) {
							intensity /= 16;
						} else if (units.endsWith("Lumens")) {
							intensity /= 16 * 4 * (float) Math.PI;
						}
						lights.add(new Light(cls, new float[] {w[12], w[13], w[14]}, normalise(w[0], w[1], w[2]), intensity, colour,
								number(d, "AttenuationRadius", 1000), number(d, "OuterConeAngle", 44), number(d, "InnerConeAngle", 0)));
					}
					default -> {
						if (!outerClass.equals("Level") || cls.equals("Model") || cls.equals("StaticMeshActor")) {
							continue;
						}
						Map<String, Object> d = props(i);
						float[] pos = null;
						float yaw = 0;
						if (d.get("RootComponent") instanceof Ref root && root.pkg == k && root.index > 0) {
							float[] w = worldMatrix(root.index);
							pos = new float[] {w[12], w[13], w[14]};
							yaw = (float) Math.toDegrees(Math.atan2(w[1], w[0]));
						}
						if (cls.equals("PlayerStart")) {
							List<String> tags = new ArrayList<>();
							if (d.get("Tags") instanceof List<?> list) {
								for (Object t : list) {
									tags.add(String.valueOf(t));
								}
							}
							if (pos != null) {
								starts.add(new Start(e.name(), pos, yaw, tags));
							}
						} else if (pos != null) {
							actors.add(new Actor(cls, e.name(), pos, yaw));
						}
					}
				}
			} catch (RuntimeException | IOException ex) {
				count(problems, cls + ": " + ex.getMessage());
			}
		}
		return new Scene(meshes, lights, starts, actors, problems);
	}

	private static void count(Map<String, Integer> problems, String what) {
		problems.merge(what, 1, Integer::sum);
	}

	private static float number(Map<String, Object> d, String name, float fallback) {
		return d.get(name) instanceof Number n ? n.floatValue() : fallback;
	}

	/** Properties of export i (1-based) of the level, merged over its template chain. */
	private Map<String, Object> props(int i) {
		Map<String, Object> d = merged.get(i);
		if (d == null) {
			d = templateProps(level, level.exports.get(i - 1), 0);
			merged.put(i, d);
		}
		return d;
	}

	private Map<String, Object> templateProps(UPackage k, UPackage.Export e, int depth) {
		Map<String, Object> out = new LinkedHashMap<>();
		int t = e.templateIndex();
		if (depth < 6) {
			if (t > 0) {
				out.putAll(templateProps(k, k.exports.get(t - 1), depth + 1));
			} else if (t < 0) {
				UPackage.Import im = k.imports.get(-t - 1);
				UPackage home = pkg(k.objectPackage(t));
				if (home != null) {
					String outerName = im.outer() < 0 ? k.imports.get(-im.outer() - 1).name() : null;
					for (UPackage.Export te : home.exports) {
						if (te.name().equals(im.name()) && (outerName == null || te.outerIndex() == 0
								|| outerName.equals(home.objectName(te.outerIndex())))) {
							out.putAll(templateProps(home, te, depth + 1));
							break;
						}
					}
				}
			}
		}
		UPackage.Props own;
		try {
			own = k.props(e);
		} catch (RuntimeException ex) {
			return out;
		}
		for (Map.Entry<String, Object> v : own.values.entrySet()) {
			out.put(v.getKey(), portable(k, v.getValue()));
		}
		if (k == level) {
			out.put("__end", own.end);
		}
		return out;
	}

	private Object portable(UPackage k, Object v) {
		if (v instanceof UPackage.ObjectRef r) {
			return new Ref(k, r.index(), k.objectPackage(r.index()), k.objectName(r.index()));
		}
		if (v instanceof List<?> list) {
			List<Object> out = new ArrayList<>(list.size());
			for (Object x : list) {
				out.add(portable(k, x));
			}
			return out;
		}
		return v;
	}

	private UPackage pkg(String gamePath) {
		if (gamePath == null || !gamePath.startsWith("/Game/")) {
			return null;
		}
		if (!packages.containsKey(gamePath)) {
			UPackage k = null;
			try {
				String path = UPackage.pakPath(gamePath);
				if (pak.has(path + ".uexp")) {
					k = new UPackage(pak, path);
				}
			} catch (IOException | RuntimeException ignored) {
				// A blueprint that cannot be read leaves its components with the level's own values only.
			}
			packages.put(gamePath, k);
		}
		return packages.get(gamePath);
	}

	private float[] worldMatrix(int i) {
		float[] m = world.get(i);
		if (m != null) {
			return m;
		}
		world.put(i, IDENTITY);
		Map<String, Object> d = props(i);
		float[] loc = d.get("RelativeLocation") instanceof float[] f ? f : new float[] {0, 0, 0};
		float[] rot = d.get("RelativeRotation") instanceof float[] f ? f : new float[] {0, 0, 0};
		float[] scale = d.get("RelativeScale3D") instanceof float[] f ? f : new float[] {1, 1, 1};
		m = trs(loc, rot, scale);
		if (d.get("AttachParent") instanceof Ref parent && parent.pkg == level && parent.index > 0) {
			m = mul(m, worldMatrix(parent.index));
		}
		world.put(i, m);
		return m;
	}

	/** Per-instance matrices of an instanced mesh component, stored after its properties. */
	private List<float[]> instances(int i) throws IOException {
		UPackage.Export e = level.exports.get(i - 1);
		if (!(props(i).get("__end") instanceof Integer end)) {
			return List.of();
		}
		ByteBuffer u = level.data;
		int o = end + 4;
		int lods = u.getInt(o);
		o += 4;
		for (int l = 0; l < lods; l++) {
			int classFlags = u.get(o + 1);
			o += 2 + 16;
			if ((classFlags & 1) == 0) {
				boolean colours = u.get(o) != 0;
				o += 1;
				if (colours) {
					o += 2;
					int vertices = u.getInt(o + 4);
					o += 8;
					if (vertices > 0) {
						o += 8 + u.getInt(o) * u.getInt(o + 4);
					}
				}
			}
		}
		o += 4;
		int size = u.getInt(o), n = u.getInt(o + 4);
		o += 8;
		if (size != 64 || n < 0 || o + (long) n * 64 > e.offset() + e.size()) {
			throw new IOException("instance data not found");
		}
		List<float[]> out = new ArrayList<>(n);
		for (int j = 0; j < n; j++) {
			float[] m = new float[16];
			for (int c = 0; c < 16; c++) {
				m[c] = u.getFloat(o + j * 64 + c * 4);
			}
			out.add(m);
		}
		return out;
	}

	/** Unreal's scale, then rotation (pitch, yaw, roll in degrees), then translation. */
	static float[] trs(float[] loc, float[] rot, float[] scale) {
		double p = Math.toRadians(rot[0]), y = Math.toRadians(rot[1]), r = Math.toRadians(rot[2]);
		float sp = (float) Math.sin(p), cp = (float) Math.cos(p);
		float sy = (float) Math.sin(y), cy = (float) Math.cos(y);
		float sr = (float) Math.sin(r), cr = (float) Math.cos(r);
		return new float[] {
				cp * cy * scale[0], cp * sy * scale[0], sp * scale[0], 0,
				(sr * sp * cy - cr * sy) * scale[1], (sr * sp * sy + cr * cy) * scale[1], -sr * cp * scale[1], 0,
				-(cr * sp * cy + sr * sy) * scale[2], (cy * sr - cr * sp * sy) * scale[2], cr * cp * scale[2], 0,
				loc[0], loc[1], loc[2], 1};
	}

	public static float[] mul(float[] a, float[] b) {
		float[] out = new float[16];
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				float s = 0;
				for (int k = 0; k < 4; k++) {
					s += a[i * 4 + k] * b[k * 4 + j];
				}
				out[i * 4 + j] = s;
			}
		}
		return out;
	}

	private static float[] normalise(float x, float y, float z) {
		float len = (float) Math.sqrt(x * x + y * y + z * z);
		return len < 1e-6f ? new float[] {1, 0, 0} : new float[] {x / len, y / len, z / len};
	}
}
