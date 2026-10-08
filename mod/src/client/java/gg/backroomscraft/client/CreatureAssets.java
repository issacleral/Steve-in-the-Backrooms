package gg.backroomscraft.client;

import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.etb.AnimReader;
import gg.backroomscraft.etb.MaterialReader;
import gg.backroomscraft.etb.PakArchive;
import gg.backroomscraft.etb.SkeletalMeshReader;
import gg.backroomscraft.etb.Skinning;
import gg.backroomscraft.gen.Sheets;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The entities' meshes, animations and textures, read from the player's Escape the Backrooms on a background thread. */
public final class CreatureAssets {
	public static final class Creature {
		public final Sheets.Creature row;
		public final SkeletalMeshReader.SkeletalMesh mesh;
		public final Skinning skinning;
		public final AnimReader.Animation idle, run, attack;
		/** Game path of the base colour texture ("" for none) and the material's tint. */
		public final String texture;
		public final float[] tint;

		Creature(Sheets.Creature row, SkeletalMeshReader.SkeletalMesh mesh, AnimReader.Animation idle, AnimReader.Animation run,
				AnimReader.Animation attack, MaterialReader.Material material) {
			this.row = row;
			this.mesh = mesh;
			this.skinning = new Skinning(mesh);
			this.idle = idle;
			this.run = run;
			this.attack = attack;
			this.texture = material.texture();
			this.tint = new float[] {material.tint()[0] * row.shade(), material.tint()[1] * row.shade(), material.tint()[2] * row.shade()};
		}
	}

	private static final Map<String, Creature> CREATURES = new ConcurrentHashMap<>();

	private CreatureAssets() {
	}

	/** The creature of a row of the entities sheet, or null while loading or when the game is not installed. */
	public static Creature get(String id) {
		return CREATURES.get(id);
	}

	static void start() {
		Thread thread = new Thread(CreatureAssets::load, "BackroomsCraft creatures");
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
			MaterialReader materials = new MaterialReader(pak);
			for (Sheets.Creature row : Sheets.ENTITIES) {
				long start = System.nanoTime();
				try {
					Creature creature = new Creature(row, SkeletalMeshReader.read(pak, row.mesh()), AnimReader.read(pak, row.animIdle()),
							AnimReader.read(pak, row.animRun()), AnimReader.read(pak, row.animAttack()), materials.read(row.material()));
					CREATURES.put(row.id(), creature);
					BackroomsCraft.LOGGER.info("Loaded {} from Escape the Backrooms in {} ms: {} vertices, {} bones, texture {}", row.name(),
							(System.nanoTime() - start) / 1_000_000, creature.mesh.vertexCount(), creature.mesh.boneNames().size(), creature.texture);
				} catch (Exception e) {
					BackroomsCraft.LOGGER.error("Could not read {} from Escape the Backrooms; it will not be drawn", row.name(), e);
				}
			}
		} catch (Throwable e) {
			BackroomsCraft.LOGGER.error("Could not load the entities from Escape the Backrooms", e);
		}
	}
}
