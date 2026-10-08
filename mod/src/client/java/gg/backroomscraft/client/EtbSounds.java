package gg.backroomscraft.client;

import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.etb.PakArchive;
import gg.backroomscraft.etb.SoundReader;
import gg.backroomscraft.gen.Sheets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays Escape the Backrooms' own sounds. They are Ogg Vorbis inside the game's files, the format Minecraft plays, so
 * each is handed to Minecraft's sound engine as a stream straight from memory.
 */
public final class EtbSounds {
	private static final Identifier EVENT = BackroomsCraft.id("etb");
	private static final RandomSource RANDOM = RandomSource.create();
	private static final Map<String, List<byte[]>> OGGS = new ConcurrentHashMap<>();
	private static final Map<String, SoundInstance> LOOPS = new HashMap<>();
	private static volatile boolean ready;

	private EtbSounds() {
	}

	static void start() {
		Thread thread = new Thread(EtbSounds::load, "BackroomsCraft sounds");
		thread.setDaemon(true);
		thread.start();
	}

	private static void load() {
		try {
			while (EtbLibrary.state() == EtbLibrary.State.LOADING) {
				Thread.sleep(50);
			}
			PakArchive pak = EtbLibrary.pak();
			if (EtbLibrary.state() != EtbLibrary.State.READY || pak == null) {
				return;
			}
			int read = 0;
			for (Sheets.Sound sound : Sheets.SOUNDS) {
				List<byte[]> variants = new ArrayList<>();
				for (String path : sound.paths()) {
					try {
						variants.add(SoundReader.readOgg(pak, path));
						read++;
					} catch (Exception e) {
						BackroomsCraft.LOGGER.warn("Could not read sound {} from Escape the Backrooms: {}", path, e.getMessage());
					}
				}
				OGGS.put(sound.id(), variants);
			}
			ready = true;
			BackroomsCraft.LOGGER.info("Read {} sounds from Escape the Backrooms", read);
		} catch (Throwable e) {
			BackroomsCraft.LOGGER.error("Could not load the sounds from Escape the Backrooms", e);
		}
	}

	public static boolean ready() {
		return ready;
	}

	/** How many times a sound has been started, for the autotest. */
	public static int played;

	/** Plays a sound of the sounds sheet once, at the listener. */
	// hook: sound_play
	public static void play(String id) {
		SoundInstance instance = instance(id, false);
		if (instance != null) {
			Minecraft.getInstance().getSoundManager().play(instance);
			played++;
		}
	}

	/** Keeps a looping sound of the sounds sheet playing while wanted is true, and stops it otherwise. */
	public static void loop(String id, boolean wanted) {
		Minecraft mc = Minecraft.getInstance();
		SoundInstance current = LOOPS.get(id);
		if (wanted) {
			if (current == null || !mc.getSoundManager().isActive(current)) {
				SoundInstance instance = instance(id, true);
				if (instance != null) {
					mc.getSoundManager().play(instance);
					LOOPS.put(id, instance);
					played++;
				}
			}
		} else if (current != null) {
			mc.getSoundManager().stop(current);
			LOOPS.remove(id);
		}
	}

	public static void stopLoops() {
		for (String id : List.copyOf(LOOPS.keySet())) {
			loop(id, false);
		}
	}

	private static SoundInstance instance(String id, boolean loop) {
		Sheets.Sound row = Sheets.sound(id);
		List<byte[]> variants = OGGS.get(id);
		if (row == null || variants == null || variants.isEmpty()) {
			return null;
		}
		byte[] ogg = variants.get(RANDOM.nextInt(variants.size()));
		float pitch = 1 + (RANDOM.nextFloat() * 2 - 1) * row.pitchJitter();
		SoundSource source = switch (row.category()) {
			case "ambient" -> SoundSource.AMBIENT;
			case "hostile" -> SoundSource.HOSTILE;
			default -> SoundSource.PLAYERS;
		};
		return new OggSound(ogg, source, row.volume(), pitch, loop);
	}

	/** A sound that stays with the listener. */
	private static final class OggSound extends AbstractSoundInstance {
		private final byte[] ogg;
		private final boolean loop;

		OggSound(byte[] ogg, SoundSource source, float volume, float pitch, boolean loop) {
			super(EVENT, source, SoundInstance.createUnseededRandom());
			this.ogg = ogg;
			this.loop = loop;
			this.volume = volume;
			this.pitch = pitch;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
		}

		@Override
		public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly) {
			try {
				return CompletableFuture.completedFuture(loop ? new LoopingAudioStream(JOrbisAudioStream::new, new ByteArrayInputStream(ogg))
						: new JOrbisAudioStream(new ByteArrayInputStream(ogg)));
			} catch (Exception e) {
				return CompletableFuture.failedFuture(e);
			}
		}
	}
}
