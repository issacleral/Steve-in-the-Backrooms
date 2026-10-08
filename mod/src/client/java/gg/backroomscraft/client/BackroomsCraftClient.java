package gg.backroomscraft.client;

import gg.backroomscraft.BackroomsCraft;
import net.fabricmc.api.ClientModInitializer;

public class BackroomsCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LevelDraw.register();
		CreatureDraw.register();
		CreatureAssets.start();
		EtbItemRenderer.register();
		EtbSounds.start();
		ClientGame.register();
		SanityHud.register();
		Autotest.register();
		BackroomsCraft.LOGGER.info("BackroomsCraft loaded");
	}
}
