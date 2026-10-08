package gg.backroomscraft.etb;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out how a material looks without running its shader graph: the base colour texture, a tint, how often the
 * texture repeats and whether it glows. Parameters come from the material instance first, then from its parents.
 */
public final class MaterialReader {
	/**
	 * @param texture  game path of the base colour texture, or "" when none was found (then tint is the whole colour)
	 * @param tint     r, g, b multiplier
	 * @param tiling   how many times the texture repeats per UV unit
	 * @param emissive 0 for a lit surface, above 0 for one that glows on its own
	 */
	public record Material(String path, String texture, float[] tint, float tiling, float emissive, boolean masked, boolean translucent,
			boolean twoSided) {
	}

	private static final String[] NOT_COLOUR = {"normal", "rough", "metal", "orm", "arm", "mask", "height", "emissi", "dirt", "detail",
			"spec", "opacity", "occlusion", "displace", "bump", "noise", "grunge", "scatter"};

	private final PakArchive pak;
	private final Map<String, Material> cache = new HashMap<>();
	private final Map<String, UPackage> packages = new HashMap<>();

	public MaterialReader(PakArchive pak) {
		this.pak = pak;
	}

	public Material read(String gamePath) {
		return cache.computeIfAbsent(gamePath, this::load);
	}

	private Material load(String gamePath) {
		Map<String, String> textures = new LinkedHashMap<>();
		Map<String, Float> scalars = new HashMap<>();
		Map<String, float[]> vectors = new HashMap<>();
		Map<String, Boolean> switches = new HashMap<>();
		String blend = null;
		Boolean twoSided = null;
		String looseTexture = null;

		String at = gamePath;
		for (int depth = 0; depth < 8 && at != null && !at.isEmpty(); depth++) {
			UPackage k = pkg(at);
			UPackage.Export e = k == null ? null : mainExport(k, at);
			if (e == null) {
				break;
			}
			UPackage.Props p;
			try {
				p = k.props(e);
			} catch (RuntimeException ex) {
				break;
			}
			String next = null;
			if (k.className(e).equals("Material")) {
				UPackage.Props cached = p.props("CachedExpressionData");
				UPackage.Props params = cached == null ? null : cached.props("Parameters");
				if (params != null) {
					List<Object> names = entryNames(params, "RuntimeEntries"), values = params.list("ScalarValues");
					for (int i = 0; i < names.size() && i < values.size(); i++) {
						if (values.get(i) instanceof Float f) {
							scalars.putIfAbsent((String) names.get(i), f);
						}
					}
					names = entryNames(params, "RuntimeEntries[1]");
					values = params.list("VectorValues");
					for (int i = 0; i < names.size() && i < values.size(); i++) {
						if (values.get(i) instanceof float[] f) {
							vectors.putIfAbsent((String) names.get(i), f);
						}
					}
					names = entryNames(params, "RuntimeEntries[2]");
					values = params.list("TextureValues");
					for (int i = 0; i < names.size() && i < values.size(); i++) {
						if (values.get(i) instanceof UPackage.ObjectRef r && k.objectPackage(r.index()) != null) {
							textures.putIfAbsent((String) names.get(i), k.objectPackage(r.index()));
						}
					}
				}
				if (blend == null) {
					blend = p.str("BlendMode", "BLEND_Opaque");
				}
				if (twoSided == null) {
					twoSided = p.bool("TwoSided", false);
				}
				// A material without parameters uses its textures directly: take the one that looks like the colour.
				int best = 0;
				for (int i = 0; i < k.imports.size(); i++) {
					if (k.imports.get(i).className().equals("Texture2D")) {
						int score = colourScore(k.imports.get(i).name());
						if (score > best) {
							best = score;
							looseTexture = k.objectPackage(-i - 1);
						}
					}
				}
			} else {
				for (Object v : p.list("TextureParameterValues")) {
					if (v instanceof UPackage.Props row && row.ref("ParameterValue") != null) {
						String path = k.objectPackage(row.ref("ParameterValue").index());
						if (path != null) {
							textures.putIfAbsent(paramName(row), path);
						}
					}
				}
				for (Object v : p.list("ScalarParameterValues")) {
					if (v instanceof UPackage.Props row && row.has("ParameterValue")) {
						scalars.putIfAbsent(paramName(row), row.num("ParameterValue", 0));
					}
				}
				for (Object v : p.list("VectorParameterValues")) {
					if (v instanceof UPackage.Props row && row.floats("ParameterValue") != null) {
						vectors.putIfAbsent(paramName(row), row.floats("ParameterValue"));
					}
				}
				UPackage.Props statics = p.props("StaticParameters");
				if (statics != null) {
					for (Object v : statics.list("StaticSwitchParameters")) {
						if (v instanceof UPackage.Props row) {
							switches.putIfAbsent(paramName(row), row.bool("Value", false));
						}
					}
				}
				UPackage.Props overrides = p.props("BasePropertyOverrides");
				if (overrides != null) {
					if (blend == null && overrides.bool("bOverride_BlendMode", false)) {
						blend = overrides.str("BlendMode", "BLEND_Opaque");
					}
					if (twoSided == null && overrides.bool("bOverride_TwoSided", false)) {
						twoSided = overrides.bool("TwoSided", false);
					}
				}
				UPackage.ObjectRef parent = p.ref("Parent");
				next = parent == null ? null : k.objectPackage(parent.index());
			}
			at = next;
		}

		String texture = null;
		int best = 0;
		for (Map.Entry<String, String> t : textures.entrySet()) {
			int score = Math.max(paramScore(t.getKey()), colourScore(leaf(t.getValue())) - 1);
			if (score > best) {
				best = score;
				texture = t.getValue();
			}
		}
		if (texture == null) {
			texture = looseTexture;
		}

		float[] tint = {1, 1, 1};
		for (Map.Entry<String, float[]> v : vectors.entrySet()) {
			String name = flat(v.getKey());
			boolean multiplier = name.contains("multipl") || name.contains("tint") || name.contains("colorcorrection");
			boolean colour = name.equals("basecolor") || name.equals("color") || name.equals("diffuse") || name.equals("albedo");
			if (name.contains("emissi") || !(multiplier || (texture == null && colour))) {
				continue;
			}
			tint = new float[] {v.getValue()[0], v.getValue()[1], v.getValue()[2]};
			if (multiplier && name.contains("basecolor")) {
				break;
			}
		}
		if (texture == null && tint[0] == 1 && tint[1] == 1 && tint[2] == 1) {
			tint = new float[] {0.5f, 0.5f, 0.5f};
		}

		float tiling = 1;
		for (Map.Entry<String, Float> s : scalars.entrySet()) {
			String name = flat(s.getKey());
			if (name.equals("tiling") || name.equals("uvtiling") || name.equals("texturetiling") || name.equals("tile")) {
				tiling = s.getValue();
			}
		}
		float emissive = 0;
		if (switches.getOrDefault("UseEmissive", false)) {
			emissive = Math.max(1, scalars.getOrDefault("EmissiveMultiplier", 1f));
		}
		String mode = blend == null ? "" : blend;
		return new Material(gamePath, texture == null ? "" : texture, tint, tiling == 0 ? 1 : tiling, emissive, mode.endsWith("Masked"),
				mode.endsWith("Translucent") || mode.endsWith("Additive") || mode.endsWith("Modulate"), twoSided != null && twoSided);
	}

	private static List<Object> entryNames(UPackage.Props params, String entry) {
		UPackage.Props e = params.props(entry);
		List<Object> out = new java.util.ArrayList<>();
		if (e != null) {
			for (Object info : e.list("ParameterInfos")) {
				out.add(info instanceof UPackage.Props p ? p.str("Name", "") : "");
			}
		}
		return out;
	}

	private static String paramName(UPackage.Props row) {
		UPackage.Props info = row.props("ParameterInfo");
		return info == null ? "" : info.str("Name", "");
	}

	private static String flat(String name) {
		return name.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("colour", "color");
	}

	/** How much a parameter name says "this is the surface colour": 0 = it is something else. */
	private static int paramScore(String param) {
		String n = flat(param);
		for (String no : NOT_COLOUR) {
			if (n.contains(no)) {
				return 0;
			}
		}
		if (n.equals("basecolor") || n.equals("diffuse") || n.equals("albedo") || n.equals("basecolortexture") || n.equals("basecolormap")) {
			return 6;
		}
		if (n.contains("basecolor") || n.contains("diffuse") || n.contains("albedo")) {
			return 5;
		}
		if (n.contains("color") || n.equals("texture") || n.equals("maintexture") || n.equals("bc") || n.equals("d")) {
			return 3;
		}
		return 0;
	}

	/** The same question asked of a texture's file name. */
	private static int colourScore(String asset) {
		String n = asset.toLowerCase(Locale.ROOT);
		for (String no : NOT_COLOUR) {
			if (n.contains(no)) {
				return 0;
			}
		}
		if (n.endsWith("_n") || n.endsWith("_nrm") || n.endsWith("_h") || n.endsWith("_e") || n.endsWith("_m") || n.endsWith("_r")
				|| n.endsWith("_ao") || n.endsWith("_s") || n.endsWith("_msk")) {
			return 0;
		}
		if (n.endsWith("_bc") || n.endsWith("_d") || n.endsWith("_alb") || n.contains("basecolor") || n.contains("base_color")
				|| n.contains("diffuse") || n.contains("albedo") || n.endsWith("_color") || n.endsWith("_col") || n.endsWith("_c")) {
			return 4;
		}
		return 1;
	}

	private static String leaf(String gamePath) {
		return gamePath.substring(gamePath.lastIndexOf('/') + 1);
	}

	private UPackage pkg(String gamePath) {
		if (!packages.containsKey(gamePath)) {
			UPackage k = null;
			try {
				String path = UPackage.pakPath(gamePath);
				if (pak.has(path + ".uexp")) {
					k = new UPackage(pak, path);
				}
			} catch (IOException | RuntimeException ignored) {
				// An unreadable material just falls back to a plain colour.
			}
			packages.put(gamePath, k);
		}
		return packages.get(gamePath);
	}

	private static UPackage.Export mainExport(UPackage k, String gamePath) {
		String name = leaf(gamePath);
		for (UPackage.Export e : k.exports) {
			String cls = k.className(e);
			if (e.name().equalsIgnoreCase(name) && (cls.equals("Material") || cls.startsWith("MaterialInstance"))) {
				return e;
			}
		}
		return null;
	}
}
