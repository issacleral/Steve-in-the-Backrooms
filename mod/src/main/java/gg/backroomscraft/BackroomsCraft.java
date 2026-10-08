package gg.backroomscraft;

import gg.backroomscraft.entity.BacteriaEntity;
import gg.backroomscraft.game.Run;
import gg.backroomscraft.game.Sanity;
import gg.backroomscraft.gen.Sheets;
import gg.backroomscraft.world.CollisionBlock;
import gg.backroomscraft.world.LevelChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackroomsCraft implements ModInitializer {
	public static final String MOD_ID = "backroomscraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** The level the game is played in: the first row of the levels sheet. */
	public static final Sheets.Level LEVEL_ROW = Sheets.LEVELS.get(0);
	/** Its dimension (data/backroomscraft/dimension/&lt;id&gt;.json). */
	public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION, id(LEVEL_ROW.id()));

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		EtbLibrary.start(FabricLoader.getInstance().getGameDir().resolve(MOD_ID));
		CollisionBlock.register();
		LevelChunkGenerator.register();
		ModItems.register();
		Sanity.register();
		BacteriaEntity.register();
		Run.register();
	}
}
