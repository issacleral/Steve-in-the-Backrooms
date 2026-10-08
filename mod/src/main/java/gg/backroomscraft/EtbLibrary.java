package gg.backroomscraft;

import gg.backroomscraft.bake.BakedLevel;
import gg.backroomscraft.bake.LevelBake;
import gg.backroomscraft.etb.PakArchive;
import gg.backroomscraft.etb.SteamLocator;
import gg.backroomscraft.gen.Sheets;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * The player's own copy of Escape the Backrooms, opened once at start-up. The level is built from it on a background
 * thread (a few seconds the first time, then read from a cache in the instance folder). Everything of Escape the
 * Backrooms that the mod shows or plays comes from here; none of it ships with the mod.
 */
public final class EtbLibrary {
	public enum State {
		LOADING, READY, MISSING, FAILED
	}

	private static volatile State state = State.LOADING;
	private static volatile String error = "";
	private static volatile String progress = "";
	private static volatile PakArchive pak;
	private static volatile BakedLevel level;
	private static final CountDownLatch DONE = new CountDownLatch(1);

	private EtbLibrary() {
	}

	static void start(Path workDir) {
		Thread thread = new Thread(() -> open(workDir), "BackroomsCraft library");
		thread.setDaemon(true);
		thread.start();
	}

	private static void open(Path workDir) {
		try {
			Path game = SteamLocator.findGame();
			if (game == null) {
				BackroomsCraft.LOGGER.warn("Escape the Backrooms is not installed (Steam app {}); there is no level to play", SteamLocator.APP_ID);
				state = State.MISSING;
				return;
			}
			long start = System.nanoTime();
			PakArchive archive = PakArchive.open(SteamLocator.paks(game));
			Sheets.Level row = BackroomsCraft.LEVEL_ROW;
			Path cache = workDir.resolve("cache").resolve(row.id() + ".bin");
			int version = Math.round(Sheets.Render.BAKE_VERSION);
			BakedLevel baked = BakedLevel.load(cache, version, archive.stamp());
			boolean cached = baked != null;
			if (!cached) {
				LevelBake bake = new LevelBake(archive, row);
				baked = bake.bake((stage, fraction) -> progress = stage + " " + Math.round(fraction * 100) + "%");
				bake.problems.forEach((what, n) -> BackroomsCraft.LOGGER.info("Level bake: {} x{}", what, n));
				baked.save(cache, version, archive.stamp());
			}
			pak = archive;
			level = baked;
			state = State.READY;
			BackroomsCraft.LOGGER.info("Escape the Backrooms found at {}: {} files; {} {} in {} ms ({} batches, {} collision blocks)", game, archive.size(),
					row.name(), cached ? "read from the cache" : "built", (System.nanoTime() - start) / 1_000_000, baked.batches.size(), baked.collision.size());
		} catch (Throwable e) {
			error = String.valueOf(e.getMessage());
			state = State.FAILED;
			BackroomsCraft.LOGGER.error("Could not read Escape the Backrooms' files", e);
		} finally {
			DONE.countDown();
		}
	}

	public static State state() {
		return state;
	}

	public static String error() {
		return error;
	}

	/** What the bake is doing, for the loading message. */
	public static String progress() {
		return progress;
	}

	/** Escape the Backrooms' files, or null unless {@link #state()} is READY. */
	public static PakArchive pak() {
		return pak;
	}

	/** The level, or null unless {@link #state()} is READY. */
	public static BakedLevel level() {
		return level;
	}

	/** Waits for start-up to finish, then returns the level or null when there is none. For world generation. */
	public static BakedLevel awaitLevel() {
		try {
			DONE.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return level;
	}
}
