package gg.backroomscraft.etb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Reads LOD0 of a cooked StaticMesh: positions, normals, the first UV channel, triangles, and which material each part uses. */
public final class StaticMeshReader {
	/** A run of triangles drawn with one material slot. */
	public record Section(int material, int firstIndex, int triangles) {
	}

	/**
	 * Positions are Unreal centimetres (x, y, z); uvs are (u, v) of channel 0; materials are game paths per slot
	 * ("" when the slot has none).
	 */
	public record Mesh(float[] positions, float[] normals, float[] uvs, int[] indices, List<Section> sections, List<String> materials) {
		public int vertexCount() {
			return positions.length / 3;
		}
	}

	private StaticMeshReader() {
	}

	public static Mesh read(PakArchive pak, String gamePath) throws IOException {
		UPackage k = new UPackage(pak, UPackage.pakPath(gamePath));
		UPackage.Export e = null;
		for (UPackage.Export x : k.exports) {
			if (k.className(x).equals("StaticMesh")) {
				e = x;
				break;
			}
		}
		if (e == null) {
			throw new IOException("No StaticMesh in " + gamePath);
		}
		UPackage.Props props = k.props(e);
		List<String> materials = new ArrayList<>();
		for (Object m : props.list("StaticMaterials")) {
			UPackage.ObjectRef ref = m instanceof UPackage.Props p ? p.ref("MaterialInterface") : null;
			String path = ref == null ? null : k.objectPackage(ref.index());
			materials.add(path == null ? "" : path);
		}

		ByteBuffer u = k.data;
		int end = e.offset() + e.size();
		// Object guid flag, strip flags, cooked flag, body setup, nav collision, lighting guid.
		int o = props.end + 4 + 2 + 4 + 4 + 4 + 16;
		int sockets = u.getInt(o);
		o += 4 + 4 * sockets;
		int lods = u.getInt(o);
		o += 4;
		if (lods < 1) {
			throw new IOException("No render data in " + gamePath);
		}
		o += 2;
		int sectionCount = u.getInt(o);
		o += 4;
		int p = findPositions(u, o, end);
		// Between the sections and the position buffer: max deviation, cooked-out flag, inlined flag, strip flags.
		int sectionBytes = p - 14 - o;
		if (sectionCount <= 0 || sectionBytes <= 0 || sectionBytes % sectionCount != 0 || sectionBytes / sectionCount < 20) {
			throw new IOException("Unexpected mesh layout in " + gamePath);
		}
		int sectionSize = sectionBytes / sectionCount;
		List<Section> sections = new ArrayList<>();
		for (int i = 0; i < sectionCount; i++) {
			int q = o + i * sectionSize;
			sections.add(new Section(u.getInt(q), u.getInt(q + 4), u.getInt(q + 8)));
		}

		int n = u.getInt(p + 4);
		int q = p + 16;
		float[] positions = new float[n * 3];
		for (int i = 0; i < n * 3; i++) {
			positions[i] = u.getFloat(q + i * 4);
		}
		q += n * 12;

		q += 2;
		int texCoords = u.getInt(q);
		boolean fullUvs = u.getInt(q + 8) != 0, highTangents = u.getInt(q + 12) != 0;
		if (u.getInt(q + 4) != n || texCoords < 1 || texCoords > 8) {
			throw new IOException("Unexpected vertex buffer in " + gamePath);
		}
		q += 16;
		int tangentSize = u.getInt(q), tangentCount = u.getInt(q + 4);
		q += 8;
		float[] normals = new float[n * 3];
		for (int i = 0; i < n && tangentCount >= n; i++) {
			int at = q + i * tangentSize;
			if (highTangents) {
				normals[i * 3] = u.getShort(at + 8) / 32767f;
				normals[i * 3 + 1] = u.getShort(at + 10) / 32767f;
				normals[i * 3 + 2] = u.getShort(at + 12) / 32767f;
			} else {
				normals[i * 3] = u.get(at + 4) / 127f;
				normals[i * 3 + 1] = u.get(at + 5) / 127f;
				normals[i * 3 + 2] = u.get(at + 6) / 127f;
			}
		}
		q += tangentSize * tangentCount;
		int uvSize = u.getInt(q), uvCount = u.getInt(q + 4);
		q += 8;
		float[] uvs = new float[n * 2];
		for (int i = 0; i < n && uvCount >= n * texCoords; i++) {
			int at = q + i * texCoords * uvSize;
			if (fullUvs) {
				uvs[i * 2] = u.getFloat(at);
				uvs[i * 2 + 1] = u.getFloat(at + 4);
			} else {
				uvs[i * 2] = Float.float16ToFloat(u.getShort(at));
				uvs[i * 2 + 1] = Float.float16ToFloat(u.getShort(at + 2));
			}
		}
		q += uvSize * uvCount;

		q += 2;
		int colours = u.getInt(q + 4);
		q += 8;
		if (colours > 0) {
			q += 8 + u.getInt(q) * u.getInt(q + 4);
		}

		boolean wide = u.getInt(q) != 0;
		int bytes = u.getInt(q + 4) * u.getInt(q + 8);
		q += 12;
		if (bytes <= 0 || q + bytes > end) {
			throw new IOException("Unexpected index buffer in " + gamePath);
		}
		int[] indices = new int[bytes / (wide ? 4 : 2)];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = wide ? u.getInt(q + i * 4) : u.getShort(q + i * 2) & 0xffff;
			if (indices[i] >= n) {
				throw new IOException("Index out of range in " + gamePath);
			}
		}
		for (Section s : sections) {
			if (s.firstIndex() < 0 || s.triangles() < 0 || s.firstIndex() + s.triangles() * 3 > indices.length) {
				throw new IOException("Section out of range in " + gamePath);
			}
		}
		return new Mesh(positions, normals, uvs, indices, sections, materials);
	}

	/** The position buffer starts with: stride 12, vertex count, element size 12, element count (= vertex count). */
	private static int findPositions(ByteBuffer u, int from, int to) throws IOException {
		for (int o = from; o + 16 <= to; o++) {
			if (u.getInt(o) == 12 && u.getInt(o + 8) == 12) {
				int n = u.getInt(o + 4);
				if (n >= 3 && n == u.getInt(o + 12) && o + 16 + (long) n * 12 <= to && o - 6 >= from && u.getInt(o - 6) == 1) {
					return o;
				}
			}
		}
		throw new IOException("No inline vertex data (" + u.limit() + " bytes)");
	}
}
