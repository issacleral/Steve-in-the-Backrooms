package gg.backroomscraft.bake;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The map's solid space as voxels of 1/8 block (12.5 cm). Each block that holds any geometry has eight longs, one per
 * layer from the bottom; in a layer, bit z * 8 + x is set where the voxel is solid.
 */
public final class CollisionBake {
	/** Voxels along one block edge. */
	public static final int N = 8;
	/** How far behind its surface a triangle is voxelised, so a floor at a block boundary fills the voxel under it. */
	private static final double INSET = 0.25;

	private final Long2ObjectOpenHashMap<long[]> blocks = new Long2ObjectOpenHashMap<>();

	/** The same packing as Minecraft's BlockPos.asLong. */
	public static long key(int x, int y, int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
	}

	public Long2ObjectOpenHashMap<long[]> blocks() {
		return blocks;
	}

	public void fill(int vx, int vy, int vz) {
		int bx = Math.floorDiv(vx, N), by = Math.floorDiv(vy, N), bz = Math.floorDiv(vz, N);
		long key = key(bx, by, bz);
		long[] bits = blocks.get(key);
		if (bits == null) {
			bits = new long[N];
			blocks.put(key, bits);
		}
		bits[vy - by * N] |= 1L << ((vz - bz * N) * N + (vx - bx * N));
	}

	public boolean solid(int vx, int vy, int vz) {
		int bx = Math.floorDiv(vx, N), by = Math.floorDiv(vy, N), bz = Math.floorDiv(vz, N);
		long[] bits = blocks.get(key(bx, by, bz));
		return bits != null && (bits[vy - by * N] >>> ((vz - bz * N) * N + (vx - bx * N)) & 1) != 0;
	}

	/** Marks the voxels a triangle passes through. Corners are in block coordinates, counter-clockwise seen from outside. */
	public void triangle(double[] a, double[] b, double[] c) {
		double[][] p = {{a[0] * N, a[1] * N, a[2] * N}, {b[0] * N, b[1] * N, b[2] * N}, {c[0] * N, c[1] * N, c[2] * N}};
		double[] e1 = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]};
		double[] e2 = {p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]};
		double[] n = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
		double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
		if (len < 1e-9) {
			return;
		}
		for (int k = 0; k < 3; k++) {
			n[k] /= len;
			for (int i = 0; i < 3; i++) {
				p[i][k] -= n[k] * INSET;
			}
		}
		// Walk the cells of the plane the triangle faces most, and fill the depth range the triangle covers in each.
		int d = Math.abs(n[0]) >= Math.abs(n[1]) && Math.abs(n[0]) >= Math.abs(n[2]) ? 0 : Math.abs(n[1]) >= Math.abs(n[2]) ? 1 : 2;
		int u = (d + 1) % 3, v = (d + 2) % 3;
		double minU = Math.min(p[0][u], Math.min(p[1][u], p[2][u])), maxU = Math.max(p[0][u], Math.max(p[1][u], p[2][u]));
		double minV = Math.min(p[0][v], Math.min(p[1][v], p[2][v])), maxV = Math.max(p[0][v], Math.max(p[1][v], p[2][v]));
		double minD = Math.min(p[0][d], Math.min(p[1][d], p[2][d])), maxD = Math.max(p[0][d], Math.max(p[1][d], p[2][d]));
		double area = (p[1][u] - p[0][u]) * (p[2][v] - p[0][v]) - (p[1][v] - p[0][v]) * (p[2][u] - p[0][u]);
		double sign = area >= 0 ? 1 : -1;
		double[] ea = new double[3], eb = new double[3], ec = new double[3];
		for (int i = 0; i < 3; i++) {
			double[] s = p[i], t = p[(i + 1) % 3];
			ea[i] = -sign * (t[v] - s[v]);
			eb[i] = sign * (t[u] - s[u]);
			ec[i] = -(ea[i] * s[u] + eb[i] * s[v]);
		}
		double planeD = n[0] * p[0][0] + n[1] * p[0][1] + n[2] * p[0][2];
		int[] cell = new int[3];
		for (int iu = (int) Math.floor(minU); iu <= (int) Math.floor(maxU); iu++) {
			for (int iv = (int) Math.floor(minV); iv <= (int) Math.floor(maxV); iv++) {
				boolean inside = true;
				for (int i = 0; i < 3 && inside; i++) {
					inside = ea[i] * (iu + (ea[i] > 0 ? 1 : 0)) + eb[i] * (iv + (eb[i] > 0 ? 1 : 0)) + ec[i] >= -1e-9;
				}
				if (!inside) {
					continue;
				}
				double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
				for (int corner = 0; corner < 4; corner++) {
					double depth = (planeD - n[u] * (iu + (corner & 1)) - n[v] * (iv + (corner >> 1))) / n[d];
					lo = Math.min(lo, depth);
					hi = Math.max(hi, depth);
				}
				lo = Math.max(lo, minD);
				hi = Math.min(hi, maxD);
				int from = (int) Math.floor(lo), to = Math.max(from, (int) Math.floor(hi - 1e-6));
				cell[u] = iu;
				cell[v] = iv;
				for (int id = from; id <= to; id++) {
					cell[d] = id;
					fill(cell[0], cell[1], cell[2]);
				}
			}
		}
	}

	/** Whether anything solid lies on the straight line between two points given in block coordinates. */
	public boolean blocked(double x0, double y0, double z0, double x1, double y1, double z1) {
		double px = x0 * N, py = y0 * N, pz = z0 * N;
		double dx = x1 * N - px, dy = y1 * N - py, dz = z1 * N - pz;
		int vx = (int) Math.floor(px), vy = (int) Math.floor(py), vz = (int) Math.floor(pz);
		int ex = (int) Math.floor(x1 * N), ey = (int) Math.floor(y1 * N), ez = (int) Math.floor(z1 * N);
		int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
		double tx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? vx + 1 : vx) - px) / dx;
		double ty = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? vy + 1 : vy) - py) / dy;
		double tz = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? vz + 1 : vz) - pz) / dz;
		double ddx = dx == 0 ? Double.MAX_VALUE : sx / dx, ddy = dy == 0 ? Double.MAX_VALUE : sy / dy, ddz = dz == 0 ? Double.MAX_VALUE : sz / dz;
		long lastKey = Long.MIN_VALUE;
		long[] bits = null;
		for (int step = 0; step < 4096; step++) {
			int bx = vx >> 3, by = vy >> 3, bz = vz >> 3;
			long key = key(bx, by, bz);
			if (key != lastKey) {
				bits = blocks.get(key);
				lastKey = key;
			}
			if (bits != null && (bits[vy & 7] >>> ((vz & 7) * N + (vx & 7)) & 1) != 0) {
				return true;
			}
			if (vx == ex && vy == ey && vz == ez) {
				return false;
			}
			if (tx <= ty && tx <= tz) {
				if (tx > 1) {
					return false;
				}
				vx += sx;
				tx += ddx;
			} else if (ty <= tz) {
				if (ty > 1) {
					return false;
				}
				vy += sy;
				ty += ddy;
			} else {
				if (tz > 1) {
					return false;
				}
				vz += sz;
				tz += ddz;
			}
		}
		return false;
	}
}
