package gg.backroomscraft.bake;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A map of Escape the Backrooms turned into what Minecraft needs: meshes to draw, voxels to collide with, the lights
 * and the places things start. Built from the player's own game files and kept in a cache file in the instance folder;
 * it is never part of the mod.
 *
 * <p>Everything is in Minecraft block coordinates (1 block = 1 m, y up).
 */
public final class BakedLevel {
	private static final int MAGIC = 0x4252434C;

	/** The triangles drawn with one material. */
	public static final class Batch {
		public String material = "";
		/** Game path of the base colour texture, or "" for a plain colour. */
		public String texture = "";
		public float[] tint = {1, 1, 1};
		public float emissive;
		public boolean masked;
		public boolean twoSided;
		/** x, y, z per vertex. */
		public float[] positions;
		/** u, v per vertex, already multiplied by the material's tiling. */
		public float[] uvs;
		/** x, y, z per vertex, -127..127. */
		public byte[] normals;
		/** Baked light r, g, b per vertex; 128 = fully lit, 255 = twice that. */
		public byte[] light;
		public int[] indices;

		public int vertexCount() {
			return positions.length / 3;
		}
	}

	/** A place a player or thing starts: a PlayerStart or an actor of the map. yaw is Minecraft's. */
	public record Marker(String className, String name, String tag, float x, float y, float z, float yaw) {
	}

	public final List<Batch> batches = new ArrayList<>();
	/** Collision voxels by block (CollisionBake.key): eight longs per block, one per layer. */
	public final Long2ObjectOpenHashMap<long[]> collision = new Long2ObjectOpenHashMap<>();
	public final List<LightBake.Lamp> lamps = new ArrayList<>();
	public final List<Marker> markers = new ArrayList<>();
	public final int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
	public final int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
	private LightBake lighting;

	public long[] voxels(long blockKey) {
		return collision.get(blockKey);
	}

	/** Light at a point, from the map's own lamps; see LightBake.brightness. */
	public synchronized float brightness(double x, double y, double z) {
		if (lighting == null) {
			CollisionBake voxels = new CollisionBake();
			voxels.blocks().putAll(collision);
			lighting = new LightBake(lamps, voxels);
		}
		return lighting.brightness(x, y, z);
	}

	public List<Marker> markers(String className) {
		List<Marker> out = new ArrayList<>();
		for (Marker m : markers) {
			if (m.className().equals(className)) {
				out.add(m);
			}
		}
		return out;
	}

	public List<Marker> starts(String tag) {
		List<Marker> out = new ArrayList<>();
		for (Marker m : markers) {
			if (m.className().equals("PlayerStart") && m.tag().equals(tag)) {
				out.add(m);
			}
		}
		return out;
	}

	public void save(Path file, int version, String stamp) throws IOException {
		Files.createDirectories(file.getParent());
		Path part = file.resolveSibling(file.getFileName() + ".part");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(part), 1 << 20))) {
			out.writeInt(MAGIC);
			out.writeInt(version);
			out.writeUTF(stamp);
			out.writeInt(batches.size());
			for (Batch b : batches) {
				out.writeUTF(b.material);
				out.writeUTF(b.texture);
				out.writeFloat(b.tint[0]);
				out.writeFloat(b.tint[1]);
				out.writeFloat(b.tint[2]);
				out.writeFloat(b.emissive);
				out.writeBoolean(b.masked);
				out.writeBoolean(b.twoSided);
				writeFloats(out, b.positions);
				writeFloats(out, b.uvs);
				out.writeInt(b.normals.length);
				out.write(b.normals);
				out.writeInt(b.light.length);
				out.write(b.light);
				writeInts(out, b.indices);
			}
			out.writeInt(collision.size());
			for (var e : collision.long2ObjectEntrySet()) {
				out.writeLong(e.getLongKey());
				for (long layer : e.getValue()) {
					out.writeLong(layer);
				}
			}
			out.writeInt(lamps.size());
			for (LightBake.Lamp l : lamps) {
				for (float f : new float[] {l.x(), l.y(), l.z(), l.dx(), l.dy(), l.dz(), l.r(), l.g(), l.b(), l.radius(), l.cosOuter(), l.cosInner()}) {
					out.writeFloat(f);
				}
			}
			out.writeInt(markers.size());
			for (Marker m : markers) {
				out.writeUTF(m.className());
				out.writeUTF(m.name());
				out.writeUTF(m.tag());
				out.writeFloat(m.x());
				out.writeFloat(m.y());
				out.writeFloat(m.z());
				out.writeFloat(m.yaw());
			}
			for (int i = 0; i < 3; i++) {
				out.writeInt(min[i]);
				out.writeInt(max[i]);
			}
		}
		Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
	}

	/** The cached level, or null when there is none for this version of the bake and this copy of the game. */
	public static BakedLevel load(Path file, int version, String stamp) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
			if (in.readInt() != MAGIC || in.readInt() != version || !in.readUTF().equals(stamp)) {
				return null;
			}
			BakedLevel level = new BakedLevel();
			int batches = in.readInt();
			for (int i = 0; i < batches; i++) {
				Batch b = new Batch();
				b.material = in.readUTF();
				b.texture = in.readUTF();
				b.tint = new float[] {in.readFloat(), in.readFloat(), in.readFloat()};
				b.emissive = in.readFloat();
				b.masked = in.readBoolean();
				b.twoSided = in.readBoolean();
				b.positions = readFloats(in);
				b.uvs = readFloats(in);
				b.normals = new byte[in.readInt()];
				in.readFully(b.normals);
				b.light = new byte[in.readInt()];
				in.readFully(b.light);
				b.indices = readInts(in);
				level.batches.add(b);
			}
			int blocks = in.readInt();
			for (int i = 0; i < blocks; i++) {
				long key = in.readLong();
				long[] layers = new long[CollisionBake.N];
				for (int k = 0; k < layers.length; k++) {
					layers[k] = in.readLong();
				}
				level.collision.put(key, layers);
			}
			int lamps = in.readInt();
			for (int i = 0; i < lamps; i++) {
				level.lamps.add(new LightBake.Lamp(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
						in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()));
			}
			int markers = in.readInt();
			for (int i = 0; i < markers; i++) {
				level.markers.add(new Marker(in.readUTF(), in.readUTF(), in.readUTF(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()));
			}
			for (int i = 0; i < 3; i++) {
				level.min[i] = in.readInt();
				level.max[i] = in.readInt();
			}
			return level;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void writeFloats(DataOutputStream out, float[] values) throws IOException {
		out.writeInt(values.length);
		ByteBuffer buffer = ByteBuffer.allocate(values.length * 4);
		buffer.asFloatBuffer().put(values);
		out.write(buffer.array());
	}

	private static void writeInts(DataOutputStream out, int[] values) throws IOException {
		out.writeInt(values.length);
		ByteBuffer buffer = ByteBuffer.allocate(values.length * 4);
		buffer.asIntBuffer().put(values);
		out.write(buffer.array());
	}

	private static float[] readFloats(DataInputStream in) throws IOException {
		float[] values = new float[in.readInt()];
		byte[] raw = new byte[values.length * 4];
		in.readFully(raw);
		ByteBuffer.wrap(raw).asFloatBuffer().get(values);
		return values;
	}

	private static int[] readInts(DataInputStream in) throws IOException {
		int[] values = new int[in.readInt()];
		byte[] raw = new byte[values.length * 4];
		in.readFully(raw);
		ByteBuffer.wrap(raw).asIntBuffer().get(values);
		return values;
	}
}
