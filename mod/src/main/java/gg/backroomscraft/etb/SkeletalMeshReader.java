package gg.backroomscraft.etb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Reads LOD0 of a cooked SkeletalMesh: the mesh, which bones move each vertex, and the bones' rest pose. */
public final class SkeletalMeshReader {
	/**
	 * @param bones     four bone indices per vertex (into boneNames)
	 * @param weights   their four weights, summing to 1
	 * @param parents   parent of each bone, -1 for the root; a parent always comes before its children
	 * @param restPose  ten floats per bone, relative to its parent: rotation x y z w, translation x y z, scale x y z
	 * @param materials game path per material slot
	 */
	public record SkeletalMesh(float[] positions, float[] normals, float[] uvs, int[] indices, int[] bones, float[] weights, List<String> boneNames,
			int[] parents, float[] restPose, List<String> materials) {
		public int vertexCount() {
			return positions.length / 3;
		}
	}

	private record Section(int baseVertex, int vertices, int[] boneMap) {
	}

	private SkeletalMeshReader() {
	}

	public static SkeletalMesh read(PakArchive pak, String gamePath) throws IOException {
		UPackage k = new UPackage(pak, UPackage.pakPath(gamePath));
		UPackage.Export e = null;
		for (UPackage.Export x : k.exports) {
			if (k.className(x).equals("SkeletalMesh")) {
				e = x;
				break;
			}
		}
		if (e == null) {
			throw new IOException("No SkeletalMesh in " + gamePath);
		}
		ByteBuffer u = k.data;
		int end = e.offset() + e.size();
		// Object guid flag, strip flags, bounds.
		int o = k.props(e).end + 4 + 2 + 28;
		int materialCount = u.getInt(o);
		o += 4;
		List<String> materials = new ArrayList<>();
		for (int i = 0; i < materialCount; i++) {
			String path = k.objectPackage(u.getInt(o));
			materials.add(path == null ? "" : path);
			boolean importedName = u.getInt(o + 12) != 0;
			o += 16 + (importedName ? 8 : 0) + 24;
		}
		int boneCount = u.getInt(o);
		o += 4;
		if (boneCount < 1 || boneCount > 1024) {
			throw new IOException("Unexpected skeleton in " + gamePath);
		}
		List<String> names = new ArrayList<>();
		int[] parents = new int[boneCount];
		for (int i = 0; i < boneCount; i++) {
			names.add(k.fname(o));
			parents[i] = u.getInt(o + 8);
			o += 12;
		}
		if (u.getInt(o) != boneCount) {
			throw new IOException("Unexpected rest pose in " + gamePath);
		}
		o += 4;
		float[] rest = new float[boneCount * 10];
		for (int i = 0; i < rest.length; i++) {
			rest[i] = u.getFloat(o + i * 4);
		}
		o += boneCount * 40;
		o += 4 + u.getInt(o) * 12;
		// Cooked flag, LOD count; then LOD0: strip flags, cooked-out flag, inlined flag, required bones.
		if (u.getInt(o) == 0 || u.getInt(o + 4) < 1 || u.getInt(o + 4 + 4 + 2) != 0) {
			throw new IOException("No render data in " + gamePath);
		}
		o += 8 + 2 + 8;
		o += 4 + 2 * u.getInt(o);
		int sectionCount = u.getInt(o);
		o += 4;
		if (sectionCount < 1 || sectionCount > 256) {
			throw new IOException("Unexpected sections in " + gamePath);
		}
		List<Section> sections = new ArrayList<>();
		int sectionsStart = o;
		for (int s = 0; s < sectionCount; s++) {
			// Strip flags, material, first index, triangles, recompute tangents, its mask channel, cast shadow.
			o += 2 + 2 + 4 + 4 + 4 + 1 + 4;
			int baseVertex = u.getInt(o);
			o += 4;
			o += 4 + 64 * u.getInt(o);
			int mapSize = u.getInt(o);
			o += 4;
			if (mapSize < 1 || mapSize > boneCount) {
				throw new IOException("Unexpected bone map in " + gamePath);
			}
			int[] boneMap = new int[mapSize];
			for (int i = 0; i < mapSize; i++) {
				boneMap[i] = u.getShort(o + i * 2) & 0xffff;
				if (boneMap[i] >= boneCount) {
					throw new IOException("Bone map out of range in " + gamePath);
				}
			}
			o += 2 * mapSize;
			sections.add(new Section(baseVertex, u.getInt(o), boneMap));
			// Vertex count, influences, cloth asset index, clothing data, duplicated vertices, disabled flag.
			o += 4 + 4 + 2 + 20;
			if (s < sectionCount - 1) {
				o += 4 + 4 * u.getInt(o);
				o += 4 + 8 * u.getInt(o);
				o += 4;
			}
		}

		int total = 0;
		for (Section s : sections) {
			total += s.vertices();
		}
		int p = findPositions(u, sectionsStart, end, total);
		int n = total;
		// The index buffer ends exactly where the position buffer starts: element size byte, size, count, data.
		int[] indices = null;
		for (int at = p - 9 - 6; at >= sectionsStart; at--) {
			int size = u.get(at);
			if ((size == 2 || size == 4) && u.getInt(at + 1) == size) {
				int count = u.getInt(at + 5);
				if (count > 0 && at + 9 + (long) size * count == p) {
					indices = new int[count];
					for (int i = 0; i < count; i++) {
						indices[i] = size == 4 ? u.getInt(at + 9 + i * 4) : u.getShort(at + 9 + i * 2) & 0xffff;
						if (indices[i] < 0 || indices[i] >= n) {
							throw new IOException("Index out of range in " + gamePath);
						}
					}
					break;
				}
			}
		}
		if (indices == null) {
			throw new IOException("No index buffer in " + gamePath);
		}

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
		int tangentSize = u.getInt(q);
		q += 8;
		float[] normals = new float[n * 3];
		for (int i = 0; i < n; i++) {
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
		q += tangentSize * n;
		int uvSize = u.getInt(q);
		q += 8;
		float[] uvs = new float[n * 2];
		for (int i = 0; i < n; i++) {
			int at = q + i * texCoords * uvSize;
			uvs[i * 2] = fullUvs ? u.getFloat(at) : Float.float16ToFloat(u.getShort(at));
			uvs[i * 2 + 1] = fullUvs ? u.getFloat(at + 4) : Float.float16ToFloat(u.getShort(at + 2));
		}
		q += uvSize * n * texCoords;

		// Skin weights: strip flags, variable flag, influences per vertex, total influences, vertex count, 16-bit flag.
		q += 2;
		boolean variable = u.getInt(q) != 0, wide = u.getInt(q + 16) != 0;
		int influences = u.getInt(q + 4);
		if (variable || influences < 1 || influences > 12 || u.getInt(q + 12) != n) {
			throw new IOException("Unsupported skin weights in " + gamePath);
		}
		q += 20;
		int stride = influences * (wide ? 3 : 2);
		if ((long) u.getInt(q) * u.getInt(q + 4) != (long) stride * n) {
			throw new IOException("Unexpected skin weight data in " + gamePath);
		}
		q += 8;
		int[] bones = new int[n * 4];
		float[] weights = new float[n * 4];
		int[] rawBone = new int[influences], rawWeight = new int[influences];
		for (Section section : sections) {
			for (int v = section.baseVertex(); v < section.baseVertex() + section.vertices() && v < n; v++) {
				int at = q + v * stride;
				for (int i = 0; i < influences; i++) {
					rawBone[i] = wide ? u.getShort(at + i * 2) & 0xffff : u.get(at + i) & 0xff;
					rawWeight[i] = u.get(at + influences * (wide ? 2 : 1) + i) & 0xff;
				}
				// Keep the four strongest.
				float sum = 0;
				for (int slot = 0; slot < 4; slot++) {
					int best = -1;
					for (int i = 0; i < influences; i++) {
						if (rawWeight[i] > 0 && (best < 0 || rawWeight[i] > rawWeight[best])) {
							best = i;
						}
					}
					if (best < 0) {
						break;
					}
					int bone = rawBone[best];
					if (bone >= section.boneMap().length) {
						throw new IOException("Skin bone out of range in " + gamePath);
					}
					bones[v * 4 + slot] = section.boneMap()[bone];
					weights[v * 4 + slot] = rawWeight[best];
					sum += rawWeight[best];
					rawWeight[best] = 0;
				}
				if (sum <= 0) {
					weights[v * 4] = 1;
					sum = 1;
				}
				for (int slot = 0; slot < 4; slot++) {
					weights[v * 4 + slot] /= sum;
				}
			}
		}
		return new SkeletalMesh(positions, normals, uvs, indices, bones, weights, names, parents, rest, materials);
	}

	private static int findPositions(ByteBuffer u, int from, int to, int vertices) throws IOException {
		for (int o = from; o + 16 <= to; o++) {
			if (u.getInt(o) == 12 && u.getInt(o + 4) == vertices && u.getInt(o + 8) == 12 && u.getInt(o + 12) == vertices
					&& o + 16 + (long) vertices * 12 <= to) {
				return o;
			}
		}
		throw new IOException("No inline vertex data");
	}
}
