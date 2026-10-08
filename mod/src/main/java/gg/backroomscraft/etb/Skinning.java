package gg.backroomscraft.etb;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Poses a skeletal mesh with an animation and moves its vertices, in the mesh's own space (Unreal centimetres).
 * Matrices are 3x4, twelve floats per bone: three rows of (x y z translation), applied to column vectors.
 */
public final class Skinning {
	private final SkeletalMeshReader.SkeletalMesh mesh;
	private final int boneCount;
	/** Inverse of each bone's rest matrix in mesh space. */
	private final float[] inverseRest;
	private final float[] local, global;
	private final Map<AnimReader.Animation, int[]> trackOfBone = new IdentityHashMap<>();

	public Skinning(SkeletalMeshReader.SkeletalMesh mesh) {
		this.mesh = mesh;
		this.boneCount = mesh.parents().length;
		this.local = new float[boneCount * 12];
		this.global = new float[boneCount * 12];
		this.inverseRest = new float[boneCount * 12];
		float[] rest = mesh.restPose();
		for (int b = 0; b < boneCount; b++) {
			compose(rest, b * 10, rest, b * 10 + 4, rest, b * 10 + 7, local, b * 12);
		}
		chain();
		for (int b = 0; b < boneCount; b++) {
			invert(global, b * 12, inverseRest, b * 12);
		}
	}

	/**
	 * Fills skin (12 floats per bone) with the matrices that move rest-pose vertices to the pose of an animation at a
	 * time in seconds. The animation loops. A null animation gives the rest pose.
	 */
	public void pose(AnimReader.Animation animation, float time, float[] skin) {
		float[] rest = mesh.restPose();
		int[] tracks = animation == null ? null : trackOfBone.computeIfAbsent(animation, this::mapTracks);
		float[] t = new float[3], r = new float[4], s = new float[3];
		for (int b = 0; b < boneCount; b++) {
			System.arraycopy(rest, b * 10, r, 0, 4);
			System.arraycopy(rest, b * 10 + 4, t, 0, 3);
			System.arraycopy(rest, b * 10 + 7, s, 0, 3);
			int track = tracks == null ? -1 : tracks[b];
			if (track >= 0) {
				float position = animation.length() <= 0 ? 0 : (time % animation.length()) / animation.length() * (animation.frames() - 1);
				if (position < 0) {
					position += animation.frames() - 1;
				}
				sampleVector(animation.translations()[track], position, t, 0);
				sampleRotation(animation.rotations()[track], position, r);
				if (animation.scales()[track] != null) {
					sampleVector(animation.scales()[track], position, s, 1);
				}
			}
			compose(r, 0, t, 0, s, 0, local, b * 12);
		}
		chain();
		for (int b = 0; b < boneCount; b++) {
			multiply(global, b * 12, inverseRest, b * 12, skin, b * 12);
		}
	}

	/** Moves every vertex by its bones. outPositions and outNormals are x y z per vertex. */
	public void skin(float[] skin, float[] outPositions, float[] outNormals) {
		float[] positions = mesh.positions(), normals = mesh.normals(), weights = mesh.weights();
		int[] bones = mesh.bones();
		int n = mesh.vertexCount();
		float[] m = new float[12];
		for (int v = 0; v < n; v++) {
			for (int i = 0; i < 12; i++) {
				m[i] = 0;
			}
			for (int k = 0; k < 4; k++) {
				float w = weights[v * 4 + k];
				if (w > 0) {
					int at = bones[v * 4 + k] * 12;
					for (int i = 0; i < 12; i++) {
						m[i] += skin[at + i] * w;
					}
				}
			}
			float x = positions[v * 3], y = positions[v * 3 + 1], z = positions[v * 3 + 2];
			outPositions[v * 3] = m[0] * x + m[1] * y + m[2] * z + m[3];
			outPositions[v * 3 + 1] = m[4] * x + m[5] * y + m[6] * z + m[7];
			outPositions[v * 3 + 2] = m[8] * x + m[9] * y + m[10] * z + m[11];
			x = normals[v * 3];
			y = normals[v * 3 + 1];
			z = normals[v * 3 + 2];
			float nx = m[0] * x + m[1] * y + m[2] * z, ny = m[4] * x + m[5] * y + m[6] * z, nz = m[8] * x + m[9] * y + m[10] * z;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1e-9f) {
				len = 1;
			}
			outNormals[v * 3] = nx / len;
			outNormals[v * 3 + 1] = ny / len;
			outNormals[v * 3 + 2] = nz / len;
		}
	}

	private int[] mapTracks(AnimReader.Animation animation) {
		int[] tracks = new int[boneCount];
		for (int b = 0; b < boneCount; b++) {
			tracks[b] = -1;
			for (int t = 0; t < animation.bones().length; t++) {
				if (animation.bones()[t].equals(mesh.boneNames().get(b))) {
					tracks[b] = t;
					break;
				}
			}
		}
		return tracks;
	}

	private static void sampleVector(float[] keys, float position, float[] out, float missing) {
		if (keys == null) {
			out[0] = out[1] = out[2] = missing;
			return;
		}
		int count = keys.length / 3;
		int a = Math.min(count - 1, (int) position), b = Math.min(count - 1, a + 1);
		float f = count == 1 ? 0 : position - (int) position;
		for (int c = 0; c < 3; c++) {
			out[c] = keys[a * 3 + c] + (keys[b * 3 + c] - keys[a * 3 + c]) * f;
		}
	}

	private static void sampleRotation(float[] keys, float position, float[] out) {
		if (keys == null) {
			out[0] = out[1] = out[2] = 0;
			out[3] = 1;
			return;
		}
		int count = keys.length / 4;
		int a = Math.min(count - 1, (int) position), b = Math.min(count - 1, a + 1);
		float f = count == 1 ? 0 : position - (int) position;
		float dot = 0;
		for (int c = 0; c < 4; c++) {
			dot += keys[a * 4 + c] * keys[b * 4 + c];
		}
		float sign = dot < 0 ? -1 : 1, len = 0;
		for (int c = 0; c < 4; c++) {
			out[c] = keys[a * 4 + c] * (1 - f) + keys[b * 4 + c] * sign * f;
			len += out[c] * out[c];
		}
		len = (float) Math.sqrt(len);
		for (int c = 0; c < 4; c++) {
			out[c] = len < 1e-9f ? (c == 3 ? 1 : 0) : out[c] / len;
		}
	}

	/** global = parent's global x local, for every bone in order. */
	private void chain() {
		int[] parents = mesh.parents();
		for (int b = 0; b < boneCount; b++) {
			if (parents[b] < 0 || parents[b] >= b) {
				System.arraycopy(local, b * 12, global, b * 12, 12);
			} else {
				multiply(global, parents[b] * 12, local, b * 12, global, b * 12);
			}
		}
	}

	/** A bone's matrix from its rotation (x y z w), translation and scale: scale first, then rotate, then move. */
	private static void compose(float[] q, int qi, float[] t, int ti, float[] s, int si, float[] out, int oi) {
		float x = q[qi], y = q[qi + 1], z = q[qi + 2], w = q[qi + 3];
		float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
		out[oi] = (1 - 2 * (yy + zz)) * s[si];
		out[oi + 1] = 2 * (xy - wz) * s[si + 1];
		out[oi + 2] = 2 * (xz + wy) * s[si + 2];
		out[oi + 3] = t[ti];
		out[oi + 4] = 2 * (xy + wz) * s[si];
		out[oi + 5] = (1 - 2 * (xx + zz)) * s[si + 1];
		out[oi + 6] = 2 * (yz - wx) * s[si + 2];
		out[oi + 7] = t[ti + 1];
		out[oi + 8] = 2 * (xz - wy) * s[si];
		out[oi + 9] = 2 * (yz + wx) * s[si + 1];
		out[oi + 10] = (1 - 2 * (xx + yy)) * s[si + 2];
		out[oi + 11] = t[ti + 2];
	}

	private static void multiply(float[] a, int ai, float[] b, int bi, float[] out, int oi) {
		float[] r = new float[12];
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 4; col++) {
				float v = a[ai + row * 4] * b[bi + col] + a[ai + row * 4 + 1] * b[bi + 4 + col] + a[ai + row * 4 + 2] * b[bi + 8 + col];
				r[row * 4 + col] = col == 3 ? v + a[ai + row * 4 + 3] : v;
			}
		}
		System.arraycopy(r, 0, out, oi, 12);
	}

	private static void invert(float[] m, int mi, float[] out, int oi) {
		float a = m[mi], b = m[mi + 1], c = m[mi + 2], d = m[mi + 4], e = m[mi + 5], f = m[mi + 6], g = m[mi + 8], h = m[mi + 9], i = m[mi + 10];
		float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
		float inv = Math.abs(det) < 1e-20f ? 0 : 1 / det;
		float[] r = {(e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv, 0, (f * g - d * i) * inv, (a * i - c * g) * inv,
				(c * d - a * f) * inv, 0, (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv, 0};
		for (int row = 0; row < 3; row++) {
			r[row * 4 + 3] = -(r[row * 4] * m[mi + 3] + r[row * 4 + 1] * m[mi + 7] + r[row * 4 + 2] * m[mi + 11]);
		}
		System.arraycopy(r, 0, out, oi, 12);
	}
}
