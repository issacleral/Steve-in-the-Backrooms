package gg.backroomscraft.etb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Reads a cooked Texture2D: the mip chain from the largest mip that fits a size limit down to the smallest. */
public final class TextureReader {
	/** Each mip is ARGB, row by row from the top; mips.get(0) is width x height and each next one is half that. */
	public record Texture(int width, int height, List<int[]> mips, String format) {
	}

	private static final int BULK_AT_END = 0x1, BULK_INLINE = 0x40, BULK_SEPARATE_FILE = 0x100, BULK_OPTIONAL = 0x800, BULK_SIZE_64 = 0x2000;

	private TextureReader() {
	}

	public static Texture read(PakArchive pak, String gamePath, int maxSize) throws IOException {
		String path = UPackage.pakPath(gamePath);
		UPackage k = new UPackage(pak, path);
		UPackage.Export e = null;
		for (UPackage.Export x : k.exports) {
			if (k.className(x).equals("Texture2D")) {
				e = x;
				break;
			}
		}
		if (e == null) {
			throw new IOException("No Texture2D in " + gamePath);
		}
		ByteBuffer u = k.data;
		// Object guid flag, two strip flags, cooked flag.
		int o = k.props(e).end + 4 + 2 + 2;
		if (u.getInt(o) == 0) {
			throw new IOException("Texture is not cooked: " + gamePath);
		}
		o += 4;
		if (k.fname(o).equals("None")) {
			throw new IOException("Texture has no data: " + gamePath);
		}
		// Format name, offset of the next format, size, packed flags.
		o += 8 + 8 + 12;
		String format = k.string(o);
		o += 4 + Math.abs(u.getInt(o)) * (u.getInt(o) < 0 ? 2 : 1);
		int mipCount = u.getInt(o + 4);
		o += 8;

		byte[] bulk = null;
		List<int[]> mips = new ArrayList<>();
		int width = 0, height = 0, lastWidth = 0;
		for (int i = 0; i < mipCount; i++) {
			o += 4;
			int flags = u.getInt(o);
			long count;
			if ((flags & BULK_SIZE_64) != 0) {
				count = u.getLong(o + 4);
				o += 20;
			} else {
				count = u.getInt(o + 4);
				o += 12;
			}
			long offset = u.getLong(o);
			o += 8;
			byte[] src = null;
			int at = 0;
			if ((flags & BULK_SEPARATE_FILE) != 0) {
				if ((flags & BULK_OPTIONAL) == 0) {
					if (bulk == null) {
						bulk = pak.read(path + ".ubulk");
					}
					src = bulk;
					at = (int) offset;
				}
			} else if ((flags & BULK_INLINE) != 0 || (flags & BULK_AT_END) == 0) {
				src = u.array();
				at = o;
				o += (int) count;
			}
			int w = u.getInt(o), h = u.getInt(o + 4);
			o += 12;
			if (src == null || w < 1 || h < 1 || (Math.max(w, h) > maxSize && i < mipCount - 1)) {
				continue;
			}
			if (!mips.isEmpty() && w != Math.max(1, lastWidth / 2)) {
				break;
			}
			if (at < 0 || at + count > src.length) {
				throw new IOException("Texture data out of range in " + gamePath);
			}
			int[] pixels = decode(format, src, at, (int) count, w, h, gamePath);
			if (mips.isEmpty()) {
				width = w;
				height = h;
			}
			lastWidth = w;
			mips.add(pixels);
		}
		if (mips.isEmpty()) {
			throw new IOException("No readable mip in " + gamePath);
		}
		return new Texture(width, height, mips, format);
	}

	private static int[] decode(String format, byte[] d, int at, int length, int w, int h, String name) throws IOException {
		int[] out = new int[w * h];
		switch (format) {
			case "PF_DXT1", "PF_DXT5", "PF_BC4", "PF_BC5" -> {
				int bw = (w + 3) / 4, bh = (h + 3) / 4;
				int blockBytes = format.equals("PF_DXT1") || format.equals("PF_BC4") ? 8 : 16;
				if (length < bw * bh * blockBytes) {
					throw new IOException("Short texture data in " + name);
				}
				int[] block = new int[16];
				for (int by = 0; by < bh; by++) {
					for (int bx = 0; bx < bw; bx++) {
						switch (format) {
							case "PF_DXT1" -> colourBlock(d, at, block, true);
							case "PF_DXT5" -> {
								colourBlock(d, at + 8, block, false);
								alphaBlock(d, at, block, 24);
							}
							case "PF_BC4" -> {
								java.util.Arrays.fill(block, 0xff000000);
								alphaBlock(d, at, block, 16);
								for (int i = 0; i < 16; i++) {
									int v = (block[i] >> 16) & 0xff;
									block[i] = 0xff000000 | v << 16 | v << 8 | v;
								}
							}
							default -> {
								java.util.Arrays.fill(block, 0xff0000ff);
								alphaBlock(d, at, block, 16);
								alphaBlock(d, at + 8, block, 8);
							}
						}
						at += blockBytes;
						for (int i = 0; i < 16; i++) {
							int x = bx * 4 + i % 4, y = by * 4 + i / 4;
							if (x < w && y < h) {
								out[y * w + x] = block[i];
							}
						}
					}
				}
			}
			case "PF_B8G8R8A8" -> {
				if (length < w * h * 4) {
					throw new IOException("Short texture data in " + name);
				}
				for (int i = 0; i < w * h; i++) {
					int p = at + i * 4;
					out[i] = (d[p + 3] & 0xff) << 24 | (d[p + 2] & 0xff) << 16 | (d[p + 1] & 0xff) << 8 | (d[p] & 0xff);
				}
			}
			case "PF_G8" -> {
				if (length < w * h) {
					throw new IOException("Short texture data in " + name);
				}
				for (int i = 0; i < w * h; i++) {
					int v = d[at + i] & 0xff;
					out[i] = 0xff000000 | v << 16 | v << 8 | v;
				}
			}
			default -> throw new IOException("Unsupported texture format " + format + " in " + name);
		}
		return out;
	}

	/** A DXT colour block: two 565 colours and sixteen 2-bit picks. */
	private static void colourBlock(byte[] d, int p, int[] out, boolean oneBitAlpha) {
		int c0 = (d[p] & 0xff) | (d[p + 1] & 0xff) << 8, c1 = (d[p + 2] & 0xff) | (d[p + 3] & 0xff) << 8;
		int[] c = new int[4];
		c[0] = rgb565(c0);
		c[1] = rgb565(c1);
		if (c0 > c1 || !oneBitAlpha) {
			c[2] = mix(c[0], c[1], 2, 1, 3);
			c[3] = mix(c[0], c[1], 1, 2, 3);
		} else {
			c[2] = mix(c[0], c[1], 1, 1, 2);
			c[3] = 0;
		}
		int bits = (d[p + 4] & 0xff) | (d[p + 5] & 0xff) << 8 | (d[p + 6] & 0xff) << 16 | (d[p + 7] & 0xff) << 24;
		for (int i = 0; i < 16; i++) {
			out[i] = c[(bits >>> (i * 2)) & 3];
		}
	}

	/** A DXT5-style 8-value block written into one byte of each pixel (shift 24 = alpha, 16 = red, 8 = green). */
	private static void alphaBlock(byte[] d, int p, int[] out, int shift) {
		int a0 = d[p] & 0xff, a1 = d[p + 1] & 0xff;
		int[] a = new int[8];
		a[0] = a0;
		a[1] = a1;
		if (a0 > a1) {
			for (int i = 1; i < 7; i++) {
				a[i + 1] = ((7 - i) * a0 + i * a1) / 7;
			}
		} else {
			for (int i = 1; i < 5; i++) {
				a[i + 1] = ((5 - i) * a0 + i * a1) / 5;
			}
			a[6] = 0;
			a[7] = 255;
		}
		long bits = 0;
		for (int i = 0; i < 6; i++) {
			bits |= (long) (d[p + 2 + i] & 0xff) << (8 * i);
		}
		int mask = ~(0xff << shift);
		for (int i = 0; i < 16; i++) {
			out[i] = (out[i] & mask) | a[(int) (bits >>> (i * 3)) & 7] << shift;
		}
	}

	private static int rgb565(int v) {
		int r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
		return 0xff000000 | (r * 255 / 31) << 16 | (g * 255 / 63) << 8 | (b * 255 / 31);
	}

	private static int mix(int a, int b, int wa, int wb, int div) {
		int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / div;
		int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / div;
		int bl = ((a & 255) * wa + (b & 255) * wb) / div;
		return 0xff000000 | r << 16 | g << 8 | bl;
	}
}
