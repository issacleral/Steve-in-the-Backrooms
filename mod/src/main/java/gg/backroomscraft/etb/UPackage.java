package gg.backroomscraft.etb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Names, imports, exports and tagged properties of one cooked UE 4.27 package ({@code .uasset}/{@code .umap} + {@code .uexp}). */
public final class UPackage {
	public record Import(String className, int outer, String name) {
	}

	public record Export(int classIndex, int templateIndex, int outerIndex, String name, int offset, int size) {
	}

	/** A reference to another object: negative = import, positive = export, 0 = none. */
	public record ObjectRef(int index) {
	}

	/** A value this reader does not decode: where its bytes are in {@link #data}. */
	public record Raw(int offset, int size) {
	}

	/** The tagged properties of one object or struct, in file order. */
	public static final class Props {
		public final Map<String, Object> values = new LinkedHashMap<>();
		/** Offset in the export data just after the terminating None. */
		public int end;

		public boolean has(String name) {
			return values.containsKey(name);
		}

		public Object get(String name) {
			return values.get(name);
		}

		public float num(String name, float fallback) {
			return values.get(name) instanceof Number n ? n.floatValue() : fallback;
		}

		public boolean bool(String name, boolean fallback) {
			return values.get(name) instanceof Boolean b ? b : fallback;
		}

		public String str(String name, String fallback) {
			return values.get(name) instanceof String s ? s : fallback;
		}

		public float[] floats(String name) {
			return values.get(name) instanceof float[] f ? f : null;
		}

		public Props props(String name) {
			return values.get(name) instanceof Props p ? p : null;
		}

		public ObjectRef ref(String name) {
			return values.get(name) instanceof ObjectRef r ? r : null;
		}

		@SuppressWarnings("unchecked")
		public List<Object> list(String name) {
			return values.get(name) instanceof List<?> l ? (List<Object>) l : List.of();
		}
	}

	public final String path;
	public final List<String> names = new ArrayList<>();
	public final List<Import> imports = new ArrayList<>();
	public final List<Export> exports = new ArrayList<>();
	/** The export data ({@code .uexp}). */
	public final ByteBuffer data;

	/** @param path pak path without extension, e.g. EscapeTheBackrooms/Content/Maps/Level0 */
	public UPackage(PakArchive pak, String path) throws IOException {
		this.path = path;
		String head = pak.has(path + ".umap") ? path + ".umap" : path + ".uasset";
		ByteBuffer a = ByteBuffer.wrap(pak.read(head)).order(ByteOrder.LITTLE_ENDIAN);
		this.data = ByteBuffer.wrap(pak.read(path + ".uexp")).order(ByteOrder.LITTLE_ENDIAN);
		if (a.getInt(0) != 0x9E2A83C1) {
			throw new IOException("Not a UE package: " + path);
		}
		int o = 20;
		o += 4 + a.getInt(o) * 20;
		int headerSize = a.getInt(o);
		o += 4;
		o += 4 + a.getInt(o);
		o += 4;
		int nameCount = a.getInt(o), nameOffset = a.getInt(o + 4);
		int exportCount = a.getInt(o + 16), exportOffset = a.getInt(o + 20);
		int importCount = a.getInt(o + 24), importOffset = a.getInt(o + 28);
		int dependsOffset = a.getInt(o + 32);

		int q = nameOffset;
		for (int i = 0; i < nameCount; i++) {
			int len = a.getInt(q);
			q += 4;
			if (len >= 0) {
				names.add(new String(a.array(), q, Math.max(0, len - 1), StandardCharsets.ISO_8859_1));
				q += len;
			} else {
				names.add(new String(a.array(), q, -2 * len - 2, StandardCharsets.UTF_16LE));
				q += -2 * len;
			}
			q += 4;
		}
		int importSize = importCount > 0 ? (exportOffset - importOffset) / importCount : 0;
		for (int i = 0; i < importCount; i++) {
			q = importOffset + i * importSize;
			imports.add(new Import(names.get(a.getInt(q + 8)), a.getInt(q + 16), name(a, q + 20)));
		}
		int exportSize = exportCount > 0 ? (dependsOffset - exportOffset) / exportCount : 0;
		for (int i = 0; i < exportCount; i++) {
			q = exportOffset + i * exportSize;
			exports.add(new Export(a.getInt(q), a.getInt(q + 8), a.getInt(q + 12), name(a, q + 16),
					(int) (a.getLong(q + 36) - headerSize), (int) a.getLong(q + 28)));
		}
	}

	private String name(ByteBuffer b, int at) {
		int number = b.getInt(at + 4);
		String base = names.get(b.getInt(at));
		return number == 0 ? base : base + "_" + (number - 1);
	}

	/** Reads an FName stored in the export data. */
	public String fname(int at) {
		return name(data, at);
	}

	/** The first export with this name, or null. */
	public Export export(String name) {
		for (Export e : exports) {
			if (e.name.equalsIgnoreCase(name)) {
				return e;
			}
		}
		return null;
	}

	public Export export(int index) {
		return index > 0 && index <= exports.size() ? exports.get(index - 1) : null;
	}

	public Import importAt(int index) {
		return index < 0 && -index <= imports.size() ? imports.get(-index - 1) : null;
	}

	/** Class name of an export, e.g. StaticMeshComponent or BP_Wall_C. */
	public String className(Export e) {
		return objectName(e.classIndex, "Class");
	}

	public String objectName(int index) {
		return objectName(index, null);
	}

	private String objectName(int index, String none) {
		if (index < 0) {
			return imports.get(-index - 1).name;
		}
		return index > 0 ? exports.get(index - 1).name : none;
	}

	/** Game path ("/Game/...") of the package an imported object lives in, or null for exports and none. */
	public String objectPackage(int index) {
		if (index >= 0) {
			return null;
		}
		Import im = imports.get(-index - 1);
		while (im.outer < 0) {
			im = imports.get(-im.outer - 1);
		}
		return im.name;
	}

	/** Turns "/Game/A/B" into the pak path "EscapeTheBackrooms/Content/A/B". */
	public static String pakPath(String gamePath) {
		if (gamePath.startsWith("/Game/")) {
			return "EscapeTheBackrooms/Content/" + gamePath.substring(6);
		}
		if (gamePath.startsWith("/Engine/")) {
			return "Engine/Content/" + gamePath.substring(8);
		}
		return gamePath;
	}

	public Props props(Export e) {
		return readProps(e.offset);
	}

	/** Reads tagged properties starting at an offset of the export data, up to and including the None that ends them. */
	public Props readProps(int o) {
		Props out = new Props();
		while (true) {
			String name = fname(o);
			o += 8;
			if (name.equals("None")) {
				out.end = o;
				return out;
			}
			String type = fname(o);
			o += 8;
			int size = data.getInt(o), arrayIndex = data.getInt(o + 4);
			o += 8;
			String struct = null, inner = null;
			boolean flag = false;
			switch (type) {
				case "StructProperty" -> {
					struct = fname(o);
					o += 24;
				}
				case "BoolProperty" -> {
					flag = data.get(o) != 0;
					o += 1;
				}
				case "ByteProperty", "EnumProperty" -> o += 8;
				case "ArrayProperty", "SetProperty" -> {
					inner = fname(o);
					o += 8;
				}
				case "MapProperty" -> o += 16;
				default -> {
				}
			}
			if (data.get(o) != 0) {
				o += 16;
			}
			o += 1;
			if (size < 0 || o + size > data.limit()) {
				throw new IllegalStateException("Bad property " + name + " in " + path);
			}
			Object value = type.equals("BoolProperty") ? (Object) flag : value(type, o, size, struct, inner);
			out.values.put(arrayIndex == 0 ? name : name + "[" + arrayIndex + "]", value);
			o += size;
		}
	}

	private Object value(String type, int o, int size, String struct, String inner) {
		switch (type) {
			case "ObjectProperty":
				return new ObjectRef(data.getInt(o));
			case "FloatProperty":
				return data.getFloat(o);
			case "IntProperty", "UInt32Property":
				return data.getInt(o);
			case "NameProperty", "EnumProperty", "SoftObjectProperty":
				return fname(o);
			case "ByteProperty":
				return size == 8 ? fname(o) : (Object) (data.get(o) & 0xff);
			case "StrProperty":
				return string(o);
			case "StructProperty":
				return struct(struct, o, size);
			case "ArrayProperty":
				return array(inner, o, size);
			default:
				return new Raw(o, size);
		}
	}

	private static int fixedFloats(String struct) {
		return switch (struct) {
			case "Vector", "Rotator" -> 3;
			case "Vector2D" -> 2;
			case "Quat", "Vector4", "LinearColor" -> 4;
			default -> 0;
		};
	}

	private Object struct(String name, int o, int size) {
		int n = fixedFloats(name);
		if (n > 0 && size >= n * 4) {
			float[] f = new float[n];
			for (int i = 0; i < n; i++) {
				f[i] = data.getFloat(o + i * 4);
			}
			return f;
		}
		if (name.equals("Color") && size >= 4) {
			// Stored B, G, R, A.
			return new float[] {data.get(o + 2) & 0xff, data.get(o + 1) & 0xff, data.get(o) & 0xff, data.get(o + 3) & 0xff};
		}
		if (name.equals("Guid") || name.equals("IntPoint") || name.equals("Box") || name.equals("SoftObjectPath")) {
			return new Raw(o, size);
		}
		try {
			Props p = readProps(o);
			if (p.end == o + size) {
				return p;
			}
		} catch (RuntimeException ignored) {
			// Not a tagged struct: leave it undecoded.
		}
		return new Raw(o, size);
	}

	private Object array(String inner, int o, int size) {
		int n = data.getInt(o);
		int q = o + 4;
		List<Object> out = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
		try {
			switch (inner) {
				case "ObjectProperty" -> {
					for (int i = 0; i < n; i++) {
						out.add(new ObjectRef(data.getInt(q + i * 4)));
					}
					return out;
				}
				case "FloatProperty" -> {
					for (int i = 0; i < n; i++) {
						out.add(data.getFloat(q + i * 4));
					}
					return out;
				}
				case "IntProperty" -> {
					for (int i = 0; i < n; i++) {
						out.add(data.getInt(q + i * 4));
					}
					return out;
				}
				case "NameProperty" -> {
					for (int i = 0; i < n; i++) {
						out.add(fname(q + i * 8));
					}
					return out;
				}
				case "StructProperty" -> {
					// One inner tag (name, type, size, index, struct name, guid, has-guid byte), then n structs.
					q += 24;
					String struct = fname(q);
					q += 25;
					int floats = fixedFloats(struct);
					for (int i = 0; i < n; i++) {
						if (floats > 0) {
							float[] f = new float[floats];
							for (int k = 0; k < floats; k++) {
								f[k] = data.getFloat(q + k * 4);
							}
							out.add(f);
							q += floats * 4;
						} else {
							Props p = readProps(q);
							out.add(p);
							q = p.end;
						}
					}
					if (q == o + size) {
						return out;
					}
				}
				default -> {
				}
			}
		} catch (RuntimeException ignored) {
			// Fall through: an array this reader cannot decode.
		}
		return new Raw(o, size);
	}

	/** Reads an FString stored in the export data. */
	public String string(int at) {
		int n = data.getInt(at);
		if (n == 0) {
			return "";
		}
		if (n > 0) {
			return new String(data.array(), at + 4, n - 1, StandardCharsets.ISO_8859_1);
		}
		return new String(data.array(), at + 4, -2 * n - 2, StandardCharsets.UTF_16LE);
	}
}
