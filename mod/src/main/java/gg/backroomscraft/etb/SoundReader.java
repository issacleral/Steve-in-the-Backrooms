package gg.backroomscraft.etb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Takes the Ogg Vorbis file out of a cooked SoundWave. */
public final class SoundReader {
	private SoundReader() {
	}

	public static byte[] readOgg(PakArchive pak, String gamePath) throws IOException {
		UPackage k = new UPackage(pak, UPackage.pakPath(gamePath));
		UPackage.Export e = null;
		for (UPackage.Export x : k.exports) {
			if (k.className(x).equals("SoundWave")) {
				e = x;
				break;
			}
		}
		if (e == null) {
			throw new IOException("No SoundWave in " + gamePath);
		}
		ByteBuffer u = k.data;
		// Object guid flag, cooked flag, format count, then per format: name and a bulk data header.
		int o = k.props(e).end + 4;
		if (u.getInt(o) == 0 || u.getInt(o + 4) < 1 || !k.fname(o + 8).startsWith("OGG")) {
			throw new IOException("Not an inline Ogg sound: " + gamePath);
		}
		int count = u.getInt(o + 20);
		int at = o + 36;
		if (count < 4 || at + count > u.limit() || u.get(at) != 'O' || u.get(at + 1) != 'g' || u.get(at + 2) != 'g' || u.get(at + 3) != 'S') {
			throw new IOException("Unexpected sound layout in " + gamePath);
		}
		return Arrays.copyOfRange(u.array(), at, at + count);
	}
}
