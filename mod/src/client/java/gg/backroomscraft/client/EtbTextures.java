package gg.backroomscraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.etb.PakArchive;
import gg.backroomscraft.etb.TextureReader;
import gg.backroomscraft.gen.Sheets;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Escape the Backrooms textures as GPU textures with their own mip chains, sampled with repeat. They are decoded from
 * the player's game files on background threads and uploaded a few per frame.
 */
public final class EtbTextures {
	private record Decoded(String path, TextureReader.Texture texture) {
	}

	private static final Map<String, GpuTextureView> VIEWS = new HashMap<>();
	private static final Set<String> REQUESTED = new HashSet<>();
	private static final ConcurrentLinkedQueue<Decoded> DECODED = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger PENDING = new AtomicInteger();
	private static ExecutorService workers;
	private static GpuTextureView white;
	private static GpuSampler sampler;

	private EtbTextures() {
	}

	/** Starts loading textures that are not loaded yet. Render thread. */
	public static void request(Collection<String> paths) {
		PakArchive pak = EtbLibrary.pak();
		if (pak == null) {
			return;
		}
		for (String path : paths) {
			if (path.isEmpty() || !REQUESTED.add(path)) {
				continue;
			}
			if (workers == null) {
				workers = Executors.newFixedThreadPool(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2)), runnable -> {
					Thread thread = new Thread(runnable, "BackroomsCraft textures");
					thread.setDaemon(true);
					return thread;
				});
			}
			PENDING.incrementAndGet();
			int limit = Math.round(path.startsWith("/Game/Backrooms/") ? Sheets.Render.TEXTURE_MAX : Sheets.Render.TEXTURE_MAX_PROPS);
			workers.execute(() -> {
				try {
					DECODED.add(new Decoded(path, TextureReader.read(pak, path, limit)));
				} catch (Throwable e) {
					BackroomsCraft.LOGGER.warn("Could not read texture {} from Escape the Backrooms: {}", path, e.getMessage());
					PENDING.decrementAndGet();
				}
			});
		}
	}

	/** Whether every requested texture is on the GPU (or failed). */
	public static boolean done() {
		return PENDING.get() == 0;
	}

	/** Uploads decoded textures, at most this many per call. Render thread. */
	// hook: gpu_texture
	public static void upload(int budget) {
		GpuDevice device = RenderSystem.getDevice();
		Decoded next;
		while (budget-- > 0 && (next = DECODED.poll()) != null) {
			TextureReader.Texture t = next.texture();
			String path = next.path();
			GpuTexture texture = device.createTexture(() -> "BackroomsCraft " + path, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
					TextureFormat.RGBA8, t.width(), t.height(), 1, t.mips().size());
			for (int mip = 0; mip < t.mips().size(); mip++) {
				write(device, texture, mip, Math.max(1, t.width() >> mip), Math.max(1, t.height() >> mip), t.mips().get(mip));
			}
			VIEWS.put(path, device.createTextureView(texture));
			PENDING.decrementAndGet();
		}
	}

	private static void write(GpuDevice device, GpuTexture texture, int mip, int width, int height, int[] argb) {
		ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
		try {
			for (int i = 0; i < width * height; i++) {
				int c = argb[i];
				pixels.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
			}
			pixels.flip();
			device.createCommandEncoder().writeToTexture(texture, pixels, NativeImage.Format.RGBA, mip, 0, 0, 0, width, height);
		} finally {
			MemoryUtil.memFree(pixels);
		}
	}

	/** The texture for a game path, or plain white while it is loading or when the path is "". */
	public static GpuTextureView view(String path) {
		GpuTextureView view = VIEWS.get(path);
		if (view != null) {
			return view;
		}
		if (white == null) {
			GpuDevice device = RenderSystem.getDevice();
			GpuTexture texture = device.createTexture(() -> "BackroomsCraft white", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
					TextureFormat.RGBA8, 1, 1, 1, 1);
			write(device, texture, 0, 1, 1, new int[] {-1});
			white = device.createTextureView(texture);
		}
		return white;
	}

	public static GpuSampler sampler() {
		if (sampler == null) {
			GpuDevice device = RenderSystem.getDevice();
			sampler = device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR,
					Math.max(1, Math.min(8, device.getMaxSupportedAnisotropy())), OptionalDouble.empty());
		}
		return sampler;
	}
}
