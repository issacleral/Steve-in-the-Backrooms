package gg.backroomscraft.client;

import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.ModItems;
import gg.backroomscraft.entity.BacteriaEntity;
import gg.backroomscraft.game.Sanity;
import gg.backroomscraft.gen.Sheets;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.world.entity.Entity;

/** The client's side of a run: the level's ambience, footsteps on its carpet, the chase and hit sounds, the heartbeat, the flashlight beam. */
public final class ClientGame {
	private static double lastX, lastZ, strideLeft;
	private static boolean wasChased, wasInLevel;
	private static int lastHitSound = -1000;

	private ClientGame() {
	}

	static void register() {
		// hook: game_tick
		ClientTickEvents.END_CLIENT_TICK.register(ClientGame::tick);
	}

	/** Whether the local player is in the level's dimension. */
	public static boolean inLevel(Minecraft mc) {
		return mc.player != null && mc.level != null && mc.level.dimension().equals(BackroomsCraft.LEVEL);
	}

	private static void tick(Minecraft mc) {
		boolean inLevel = inLevel(mc);
		LevelDraw.flashlight = inLevel && ModItems.flashlightOn(mc.player);
		if (!inLevel) {
			if (wasInLevel) {
				EtbSounds.stopLoops();
				wasInLevel = false;
			}
			return;
		}
		if (!wasInLevel) {
			// Minecraft's first-steps hints (punch a tree...) have nothing to do in here.
			mc.getTutorial().setStep(TutorialSteps.NONE);
			lastX = mc.player.getX();
			lastZ = mc.player.getZ();
			strideLeft = Sheets.Rules.FOOTSTEP_STRIDE;
			wasInLevel = true;
		}
		if (mc.isPaused() || !EtbSounds.ready()) {
			return;
		}
		Sheets.Level row = BackroomsCraft.LEVEL_ROW;
		EtbSounds.loop(row.ambience(), true);

		boolean chased = false, struck = false;
		double range = Sheets.Rules.CHASE_SOUND_RANGE;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof BacteriaEntity bacteria && bacteria.isAlive()) {
				double distance = bacteria.distanceToSqr(mc.player);
				chased |= bacteria.isChasing() && distance < range * range;
				struck |= bacteria.swinging && bacteria.swingTime <= 1 && distance < 36;
			}
		}
		if (chased && !wasChased) {
			EtbSounds.play("bacteria_chase_start");
		}
		wasChased = chased;
		EtbSounds.loop(BacteriaEntity.ROW.soundChase(), chased);
		if (struck && mc.player.tickCount - lastHitSound > 50) {
			EtbSounds.play(BacteriaEntity.ROW.soundAttack());
			lastHitSound = mc.player.tickCount;
		}
		EtbSounds.loop("heartbeat", Sanity.get(mc.player) < Sheets.Rules.SANITY_LOW);

		double moved = Math.hypot(mc.player.getX() - lastX, mc.player.getZ() - lastZ);
		lastX = mc.player.getX();
		lastZ = mc.player.getZ();
		if (mc.player.onGround() && moved < 2 && !mc.player.isShiftKeyDown()) {
			strideLeft -= moved;
			if (strideLeft <= 0) {
				strideLeft = Sheets.Rules.FOOTSTEP_STRIDE;
				EtbSounds.play("footstep_carpet");
			}
		}
	}
}
