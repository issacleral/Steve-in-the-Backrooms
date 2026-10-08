package gg.backroomscraft.etb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a cooked AnimSequence compressed with Unreal's per-track codec: for each animated bone, a list of evenly spaced
 * keys (or a single key) for its translation and rotation. Formats this reader does not know are refused, not guessed.
 */
public final class AnimReader {
	/**
	 * @param bones        the bone each track moves, by name
	 * @param translations per track: x y z per key, or null when the track leaves the translation at zero
	 * @param rotations    per track: x y z w per key, or null for no rotation
	 * @param scales       per track: x y z per key, or null to keep the bone's own scale
	 */
	public record Animation(float length, int frames, String[] bones, float[][] translations, float[][] rotations, float[][] scales) {
	}

	private static final int FLOAT96 = 1, FIXED48 = 2;

	private AnimReader() {
	}

	/** Bone names of a Skeleton asset, in the order animation tracks refer to them. */
	public static List<String> skeletonBones(PakArchive pak, String gamePath) throws IOException {
		UPackage k = new UPackage(pak, UPackage.pakPath(gamePath));
		for (UPackage.Export e : k.exports) {
			if (k.className(e).equals("Skeleton")) {
				int o = k.props(e).end + 4;
				int count = k.data.getInt(o);
				if (count < 1 || count > 4096) {
					break;
				}
				List<String> names = new ArrayList<>();
				for (int i = 0; i < count; i++) {
					names.add(k.fname(o + 4 + i * 12));
				}
				return names;
			}
		}
		throw new IOException("No Skeleton in " + gamePath);
	}

	public static Animation read(PakArchive pak, String gamePath) throws IOException {
		UPackage k = new UPackage(pak, UPackage.pakPath(gamePath));
		UPackage.Export e = null;
		for (UPackage.Export x : k.exports) {
			if (k.className(x).equals("AnimSequence")) {
				e = x;
				break;
			}
		}
		if (e == null) {
			throw new IOException("No AnimSequence in " + gamePath);
		}
		UPackage.Props props = k.props(e);
		UPackage.ObjectRef skeletonRef = props.ref("Skeleton");
		String skeletonPath = skeletonRef == null ? null : k.objectPackage(skeletonRef.index());
		if (skeletonPath == null) {
			throw new IOException("Animation without a skeleton: " + gamePath);
		}
		List<String> skeleton = skeletonBones(pak, skeletonPath);
		float length = props.num("SequenceLength", 0);

		ByteBuffer u = k.data;
		// Object guid flag, skeleton guid, strip flags, has-compressed-data flag, raw size.
		int o = props.end + 4 + 16 + 2;
		if (u.getInt(o) == 0) {
			throw new IOException("Animation has no compressed data: " + gamePath);
		}
		o += 8;
		int tracks = u.getInt(o);
		o += 4;
		if (tracks < 1 || tracks > 4096) {
			throw new IOException("Unexpected track table in " + gamePath);
		}
		String[] bones = new String[tracks];
		for (int t = 0; t < tracks; t++) {
			int bone = u.getInt(o + t * 4);
			if (bone < 0 || bone >= skeleton.size()) {
				throw new IOException("Track bone out of range in " + gamePath);
			}
			bones[t] = skeleton.get(bone);
		}
		o += tracks * 4;
		o += 4 + 8 * u.getInt(o);
		int streamBytes = u.getInt(o);
		if (u.getInt(o + 4) != 0) {
			throw new IOException("Animation data is not inline: " + gamePath);
		}
		int stream = o + 8;
		o = stream + streamBytes;
		String codec = k.string(o);
		o += 4 + Math.abs(u.getInt(o)) * (u.getInt(o) < 0 ? 2 : 1);
		o += 4 + Math.abs(u.getInt(o)) * (u.getInt(o) < 0 ? 2 : 1);
		o += 4 + u.getInt(o);
		int frames = u.getInt(o);
		int keyEncoding = u.get(o + 4);
		int trackOffsets = u.getInt(o + 12), scaleOffsets = u.getInt(o + 16);
		if (!codec.startsWith("AnimCompress_PerTrackCompression") || keyEncoding != 2 || trackOffsets != tracks * 2 || frames < 1) {
			throw new IOException("Unsupported animation compression (" + codec + ", " + keyEncoding + ") in " + gamePath);
		}
		int data = stream + (trackOffsets + scaleOffsets) * 4;
		int dataEnd = stream + streamBytes;

		float[][] translations = new float[tracks][], rotations = new float[tracks][], scales = new float[tracks][];
		for (int t = 0; t < tracks; t++) {
			int at = u.getInt(stream + t * 8);
			if (at >= 0) {
				translations[t] = vectorTrack(u, data + at, dataEnd, frames, gamePath);
			}
			at = u.getInt(stream + t * 8 + 4);
			if (at >= 0) {
				rotations[t] = rotationTrack(u, data + at, dataEnd, frames, gamePath);
			}
			at = scaleOffsets == tracks ? u.getInt(stream + trackOffsets * 4 + t * 4) : -1;
			if (at >= 0 && (u.getInt(data + at) >>> 28) == FLOAT96) {
				scales[t] = vectorTrack(u, data + at, dataEnd, frames, gamePath);
			}
		}
		return new Animation(length, frames, bones, translations, rotations, scales);
	}

	/** A track header: key format in the top 4 bits, which of x y z are stored in the next 4 (8 = own key times), then the key count. */
	private static int keys(ByteBuffer u, int at, int frames, String name) throws IOException {
		int header = u.getInt(at);
		int count = header & 0xffffff, flags = (header >>> 24) & 0xf;
		if ((flags & 8) != 0 || (count != 1 && count != frames)) {
			throw new IOException("Unsupported key timing in " + name);
		}
		return count;
	}

	private static float[] vectorTrack(ByteBuffer u, int at, int end, int frames, String name) throws IOException {
		int header = u.getInt(at), format = header >>> 28, flags = (header >>> 24) & 0xf, count = keys(u, at, frames, name);
		if (format != FLOAT96) {
			throw new IOException("Unsupported translation format " + format + " in " + name);
		}
		float[] out = new float[count * 3];
		int q = at + 4;
		for (int key = 0; key < count; key++) {
			for (int c = 0; c < 3; c++) {
				if ((flags & (1 << c)) != 0) {
					if (q + 4 > end) {
						throw new IOException("Animation data out of range in " + name);
					}
					out[key * 3 + c] = u.getFloat(q);
					q += 4;
				}
			}
		}
		return out;
	}

	private static float[] rotationTrack(ByteBuffer u, int at, int end, int frames, String name) throws IOException {
		int header = u.getInt(at), format = header >>> 28, flags = (header >>> 24) & 0xf, count = keys(u, at, frames, name);
		if (format != FLOAT96 && format != FIXED48) {
			throw new IOException("Unsupported rotation format " + format + " in " + name);
		}
		float[] out = new float[count * 4];
		int q = at + 4;
		for (int key = 0; key < count; key++) {
			float sum = 0;
			for (int c = 0; c < 3; c++) {
				float v = 0;
				if ((flags & (1 << c)) != 0) {
					if (q + (format == FLOAT96 ? 4 : 2) > end) {
						throw new IOException("Animation data out of range in " + name);
					}
					if (format == FLOAT96) {
						v = u.getFloat(q);
						q += 4;
					} else {
						v = ((u.getShort(q) & 0xffff) - 32767) / 32767f;
						q += 2;
					}
				}
				out[key * 4 + c] = v;
				sum += v * v;
			}
			// The fourth component is not stored: the rotation has length 1 and w is never negative.
			out[key * 4 + 3] = sum < 1 ? (float) Math.sqrt(1 - sum) : 0;
		}
		return out;
	}
}
