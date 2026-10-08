package gg.backroomscraft.bake;

import gg.backroomscraft.gen.Sheets;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.List;

/**
 * Lights a point of the map from the map's own lights: inverse-square falloff inside each light's radius, the spot
 * cone, the surface direction and a shadow traced through the collision voxels, plus a flat share for bounced light.
 */
public final class LightBake {
	/** A light in block coordinates. colour already includes its strength. cosOuter = -2 for a light without a cone. */
	public record Lamp(float x, float y, float z, float dx, float dy, float dz, float r, float g, float b, float radius, float cosOuter,
			float cosInner) {
	}

	/** Side of the lookup cells, in blocks. */
	private static final int CELL = 8;
	/** Shadow rays start this far off the surface and stop this far short of the light, clear of the lamp's own mesh. */
	private static final double SURFACE_GAP = 0.2, LAMP_GAP = 0.45;
	/** How far below a ceiling lamp its bounced light seems to come from, in blocks. */
	private static final double BOUNCE_DROP = 2.0;

	private final List<Lamp> lamps;
	private final CollisionBake collision;
	private final Long2ObjectOpenHashMap<IntArrayList> cells = new Long2ObjectOpenHashMap<>();

	public LightBake(List<Lamp> lamps, CollisionBake collision) {
		this.lamps = lamps;
		this.collision = collision;
		for (int i = 0; i < lamps.size(); i++) {
			Lamp l = lamps.get(i);
			for (int cx = Math.floorDiv((int) Math.floor(l.x - l.radius), CELL); cx <= Math.floorDiv((int) Math.floor(l.x + l.radius), CELL); cx++) {
				for (int cz = Math.floorDiv((int) Math.floor(l.z - l.radius), CELL); cz <= Math.floorDiv((int) Math.floor(l.z + l.radius), CELL); cz++) {
					cells.computeIfAbsent(cell(cx, cz), k -> new IntArrayList()).add(i);
				}
			}
		}
	}

	private static long cell(int cx, int cz) {
		return (long) cx << 32 | (cz & 0xffffffffL);
	}

	/** Light reaching a surface point with the given normal: r, g, b into out, 1 = fully lit. */
	public void shade(double x, double y, double z, float nx, float ny, float nz, boolean twoSided, float[] out) {
		float r = Sheets.Render.LIGHT_AMBIENT[0], g = Sheets.Render.LIGHT_AMBIENT[1], b = Sheets.Render.LIGHT_AMBIENT[2];
		IntArrayList near = cells.get(cell(Math.floorDiv((int) Math.floor(x), CELL), Math.floorDiv((int) Math.floor(z), CELL)));
		if (near != null) {
			double sx = x + nx * SURFACE_GAP, sy = y + ny * SURFACE_GAP, sz = z + nz * SURFACE_GAP;
			for (int k = 0; k < near.size(); k++) {
				Lamp l = lamps.get(near.getInt(k));
				double lx = l.x - x, ly = l.y - y, lz = l.z - z;
				double d2 = lx * lx + ly * ly + lz * lz;
				if (d2 >= l.radius * l.radius) {
					continue;
				}
				double d = Math.sqrt(d2);
				double falloff = d / l.radius;
				falloff = 1 - falloff * falloff * falloff * falloff;
				double strength = falloff * falloff / (d2 + 1);
				// Bounced light: as if a soft lamp hung in the room below the real one. No cone, no shadow, any direction,
				// so ceilings and the backs of pillars are not black.
				double drop = l.cosOuter > -1.5f ? BOUNCE_DROP : 0;
				double bx = lx + l.dx * drop, by = ly + l.dy * drop, bz = lz + l.dz * drop;
				double amount = Sheets.Render.LIGHT_BOUNCE * falloff * falloff / (bx * bx + by * by + bz * bz + 4);
				double cone = 1;
				if (l.cosOuter > -1.5f && d > 1e-4) {
					double cos = -(lx * l.dx + ly * l.dy + lz * l.dz) / d;
					cone = Math.max(0, Math.min(1, (cos - l.cosOuter) / Math.max(1e-4, l.cosInner - l.cosOuter)));
				}
				double facing = d < 1e-4 ? 1 : (lx * nx + ly * ny + lz * nz) / d;
				if (twoSided) {
					facing = Math.abs(facing);
				}
				double direct = Math.max(0, facing) * cone * strength;
				if (direct > 0) {
					double t = d < 1e-4 ? 0 : Math.max(0, 1 - LAMP_GAP / d);
					if (!collision.blocked(sx, sy, sz, x + lx * t, y + ly * t, z + lz * t)) {
						amount += direct;
					}
				}
				r += l.r * amount;
				g += l.g * amount;
				b += l.b * amount;
			}
		}
		out[0] = r;
		out[1] = g;
		out[2] = b;
	}

	/** How bright it is for someone standing at a point: 0 = dark, around 1 = under a lamp. */
	public float brightness(double x, double y, double z) {
		float[] c = new float[3];
		shade(x, y, z, 0, 1, 0, true, c);
		return Math.max(c[0], Math.max(c[1], c[2]));
	}
}
