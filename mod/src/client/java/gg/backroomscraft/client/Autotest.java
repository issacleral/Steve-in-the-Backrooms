package gg.backroomscraft.client;

import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.ModItems;
import gg.backroomscraft.bake.BakedLevel;
import gg.backroomscraft.entity.BacteriaEntity;
import gg.backroomscraft.game.Sanity;
import gg.backroomscraft.gen.Sheets;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * A scripted check of the real game, run only with -Dbackroomscraft.autotest=true: makes a survival world, waits to be
 * put in the level, looks around, walks, uses the flashlight and the almond water, meets the entity, and saves a
 * screenshot of each step. It logs one AUTOTEST line with the result and quits.
 */
public final class Autotest {
	public static final boolean ACTIVE = System.getProperty("backroomscraft.autotest") != null;
	/** -Dbackroomscraft.autotest=demo plays a short captioned tour and saves every frame, to cut a clip from. */
	public static final boolean DEMO = "demo".equals(System.getProperty("backroomscraft.autotest"));
	/** Text the HUD shows at the top of the picture while the tour runs. */
	public static String caption;
	private static int exitAt = 480;

	private static boolean worldRequested;
	private static int waited;
	private static int tick = -1;
	private static float yaw, pitch;
	private static double startX, startY, startZ, walked, entityFar = -1, entityNear = -1;
	private static boolean grounded, kit, lit, drank, relocated, escaped, entitySeen, entityChased, facingEntity;
	private static int framesAtStart, entityDrawn;
	private static float sanityBefore, healthLow = 20;

	private Autotest() {
	}

	static void register() {
		if (ACTIVE) {
			// hook: client_tick
			ClientTickEvents.END_CLIENT_TICK.register(Autotest::tick);
		}
	}

	private static void tick(Minecraft mc) {
		mc.options.pauseOnLostFocus = false;
		if (!worldRequested) {
			if (mc.screen instanceof AccessibilityOnboardingScreen) {
				// A fresh game folder opens on this screen and would wait for a click.
				mc.options.onboardAccessibility = false;
				mc.setScreen(new TitleScreen());
			}
			if (mc.screen instanceof TitleScreen && (EtbLibrary.state() != EtbLibrary.State.LOADING || ++waited > 2400)) {
				worldRequested = true;
				waited = 0;
				String name = "backroomscraft_autotest_" + System.currentTimeMillis();
				LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.NORMAL, true,
						new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
				mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(8675309L, false, false),
						WorldPresets::createFlatWorldDimensions, mc.screen);
			}
			return;
		}
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (tick < 0) {
			boolean inLevel = mc.level.dimension().equals(BackroomsCraft.LEVEL);
			boolean loaded = LevelDraw.ready() && EtbSounds.ready() && EtbItemRenderer.ready() && CreatureAssets.get(BacteriaEntity.ROW.id()) != null;
			if (!inLevel || !loaded || mc.screen != null) {
				if (++waited == 2400) {
					BackroomsCraft.LOGGER.info("AUTOTEST FAIL: never got into the level (library={}, dimension={}, level={}, sounds={}, items={}, creature={})",
							EtbLibrary.state(), mc.level.dimension().identifier(), LevelDraw.ready(), EtbSounds.ready(), EtbItemRenderer.ready(),
							CreatureAssets.get(BacteriaEntity.ROW.id()) != null);
					mc.stop();
				}
				return;
			}
			tick = 0;
			yaw = mc.player.getYRot();
			framesAtStart = LevelDraw.framesDrawn;
		}
		tick++;
		BacteriaEntity bacteria = null;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof BacteriaEntity found && found.isAlive()) {
				bacteria = found;
			}
		}
		if (DEMO) {
			demo(mc, bacteria);
			return;
		}
		if (tick > 410 && tick < 819 && bacteria != null) {
			BakedLevel.Marker first = EtbLibrary.level().starts(BackroomsCraft.LEVEL_ROW.startTag()).get(0);
			if (bacteria.distanceToSqr(first.x(), first.y(), first.z()) < 16 || bacteria.isChasing()) {
				relocated = true;
				tick = 819;
			}
		}
		if (facingEntity && bacteria != null) {
			yaw = (float) Math.toDegrees(Math.atan2(-(bacteria.getX() - mc.player.getX()), bacteria.getZ() - mc.player.getZ()));
			healthLow = Math.min(healthLow, mc.player.getHealth());
			entityChased |= bacteria.isChasing();
			entityDrawn = Math.max(entityDrawn, CreatureDraw.drawn);
		}
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		mc.options.keyUp.setDown(tick >= 200 && tick < 260);
		if (tick > 20) {
			mc.gui.getChat().clearMessages(false);
		}
		switch (tick) {
			case 40 -> {
				startX = mc.player.getX();
				startY = mc.player.getY();
				startZ = mc.player.getZ();
				grounded = mc.player.onGround();
				ItemStack first = mc.player.getInventory().getItem(0), fifth = mc.player.getInventory().getItem(4);
				kit = first.getItem() instanceof ModItems.FlashlightItem && fifth.getItem() instanceof ModItems.AlmondWaterItem && fifth.getCount() == 2;
				shot(mc, "01_start");
			}
			case 60, 100, 140, 180 -> yaw += 90;
			case 80 -> shot(mc, "02_right");
			case 120 -> shot(mc, "03_back");
			case 160 -> shot(mc, "04_left");
			case 190 -> pitch = -35;
			case 198 -> shot(mc, "05_ceiling");
			case 199 -> pitch = 0;
			case 270 -> {
				walked = Math.hypot(mc.player.getX() - startX, mc.player.getZ() - startZ);
				shot(mc, "06_walked");
			}
			case 280 -> mc.player.getInventory().setSelectedSlot(0);
			case 290 -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			case 305 -> {
				lit = LevelDraw.flashlight;
				shot(mc, "07_flashlight");
			}
			case 310 -> {
				sanityBefore = Sanity.get(mc.player);
				mc.player.getInventory().setSelectedSlot(4);
			}
			case 320 -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			case 332 -> {
				drank = mc.player.getInventory().getItem(4).getCount() == 1 && Sanity.get(mc.player) >= sanityBefore;
				shot(mc, "08_almond_water");
			}
			case 335 -> mc.player.getInventory().setSelectedSlot(0);
			case 340 -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			case 355 -> shot(mc, "09_third_person");
			case 357 -> mc.options.setCameraType(CameraType.FIRST_PERSON);
			case 360 -> {
				// Leave a trail between two start points, then wait for the idle entity to be moved onto it.
				BakedLevel.Marker first = EtbLibrary.level().starts(BackroomsCraft.LEVEL_ROW.startTag()).get(0);
				command(mc, "tp @s %.2f %.2f %.2f", first.x(), first.y(), first.z());
			}
			case 410 -> {
				BakedLevel.Marker other = EtbLibrary.level().starts(BackroomsCraft.LEVEL_ROW.startTag()).get(3);
				command(mc, "tp @s %.2f %.2f %.2f", other.x(), other.y(), other.z());
			}
			case 820 -> {
				// The map's own exit: stand at one of the start points beside it and look at it.
				BakedLevel.Marker exit = EtbLibrary.level().markers(BackroomsCraft.LEVEL_ROW.exitClass()).get(0);
				BakedLevel.Marker beside = EtbLibrary.level().starts(BackroomsCraft.LEVEL_ROW.exitTag()).get(0);
				yaw = (float) Math.toDegrees(Math.atan2(-(exit.x() - beside.x()), exit.z() - beside.z()));
				command(mc, "tp @s %.2f %.2f %.2f", beside.x(), beside.y(), beside.z());
			}
			case 845 -> shot(mc, "10_exit");
			case 850 -> {
				BakedLevel.Marker exit = EtbLibrary.level().markers(BackroomsCraft.LEVEL_ROW.exitClass()).get(0);
				command(mc, "tp @s %.2f %.2f %.2f", exit.x() + 1.2, exit.y(), exit.z());
			}
			case 865 -> {
				// Reaching the exit ends the run: the player is back at a start point with a fresh kit.
				BakedLevel.Marker exit = EtbLibrary.level().markers(BackroomsCraft.LEVEL_ROW.exitClass()).get(0);
				escaped = mc.player.distanceToSqr(exit.x(), exit.y(), exit.z()) > 400 && mc.player.getInventory().getItem(4).getCount() == 2;
				// Face the way the start point faces: that is the direction known to be open.
				for (BakedLevel.Marker start : EtbLibrary.level().starts(BackroomsCraft.LEVEL_ROW.startTag())) {
					if (mc.player.distanceToSqr(start.x(), start.y(), start.z()) < 4) {
						yaw = start.yaw();
					}
				}
				shot(mc, "11_escaped");
			}
			case 880 -> {
				// Bring the entity ten blocks ahead: every start point has that much open floor in front of it.
				entitySeen = bacteria != null;
				double ahead = 10;
				command(mc, "tp @e[type=backroomscraft:bacteria,limit=1] %.2f %.2f %.2f", mc.player.getX() - Math.sin(Math.toRadians(yaw)) * ahead,
						mc.player.getY(), mc.player.getZ() + Math.cos(Math.toRadians(yaw)) * ahead);
				facingEntity = true;
			}
			case 892 -> entityFar = bacteria == null ? -1 : bacteria.distanceTo(mc.player);
			case 900 -> shot(mc, "12_entity");
			case 912 -> shot(mc, "13_entity_closer");
			case 916 -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			case 924 -> shot(mc, "14_entity_third_person");
			case 926 -> mc.options.setCameraType(CameraType.FIRST_PERSON);
			case 940 -> {
				entityNear = bacteria == null ? -1 : bacteria.distanceTo(mc.player);
				shot(mc, "15_entity_near");
			}
			case 980 -> shot(mc, "16_entity_hit");
			case 1020 -> {
				double drop = Math.abs(mc.player.getY() - startY);
				int frames = LevelDraw.framesDrawn - framesAtStart;
				boolean world = grounded && walked > 1.0 && frames > 100 && Math.abs(startY - BackroomsCraft.LEVEL_ROW.floorY()) < 0.6;
				boolean entity = entitySeen && entityDrawn > 0 && entityChased && entityFar > 0 && entityNear < entityFar - 2 && healthLow < 20;
				boolean pass = world && kit && lit && drank && relocated && escaped && entity && EtbSounds.played > 0;
				BackroomsCraft.LOGGER.info(
						"AUTOTEST {}: library={} grounded={} startY={} drop={} walked={} levelFrames={} triangles={} fps={} kit={} flashlight={} drank={} relocated={} escaped={} sanity={} "
								+ "entitySeen={} entityDrawn={} entityChased={} entityDistance={}->{} healthLow={} soundsPlayed={} start={} entityAt={}",
						pass ? "PASS" : "FAIL", EtbLibrary.state(), grounded, String.format("%.2f", startY), String.format("%.2f", drop),
						String.format("%.2f", walked), frames, LevelDraw.trianglesDrawn, mc.getFps(), kit, lit, drank, relocated, escaped, String.format("%.1f", Sanity.get(mc.player)), entitySeen, entityDrawn,
						entityChased, String.format("%.1f", entityFar), String.format("%.1f", entityNear), healthLow, EtbSounds.played,
						String.format("%.1f %.1f %.1f", startX, startY, startZ), bacteria == null ? "-" : bacteria.position().toString());
			}
			case 1040 -> mc.stop();
			default -> {
			}
		}
	}

	/** The tour: look around, walk, flashlight, almond water, the entity, the exit. One frame is saved per tick (20 a second). */
	private static void demo(Minecraft mc, BacteriaEntity bacteria) {
		int t = tick;
		// Minecraft's own pop-ups (new recipes...) would cover the captions.
		mc.getToastManager().clear();
		BakedLevel level = EtbLibrary.level();
		BakedLevel.Marker exit = level.markers(BackroomsCraft.LEVEL_ROW.exitClass()).get(0);
		if (t == 1) {
			startX = mc.player.getX();
			startY = mc.player.getY();
			startZ = mc.player.getZ();
		}
		if (t >= 40 && t < 160) {
			yaw += 3;
		}
		if (facingEntity && bacteria != null) {
			yaw = (float) Math.toDegrees(Math.atan2(-(bacteria.getX() - mc.player.getX()), bacteria.getZ() - mc.player.getZ()));
			if (mc.player.getHealth() <= 7 && t < exitAt) {
				exitAt = t + 1;
			}
		}
		mc.options.keyUp.setDown(t >= 180 && t < 240);
		mc.player.setYRot(yaw);
		mc.player.setXRot(0);
		if (t > 20) {
			mc.gui.getChat().clearMessages(false);
		}
		switch (t) {
			case 30 -> caption = "Minecraft, inside the real Level 0 of Escape the Backrooms.";
			case 170 -> {
				caption = "No blocks: the whole map is read from your own copy of the game.";
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			}
			case 245 -> mc.options.setCameraType(CameraType.FIRST_PERSON);
			case 250 -> {
				caption = "The real flashlight. Right click switches it on.";
				mc.player.getInventory().setSelectedSlot(0);
			}
			case 262, 315 -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			case 300 -> {
				caption = "Almond water restores your sanity.";
				mc.player.getInventory().setSelectedSlot(4);
			}
			case 345 -> {
				caption = "The Bacteria follows your trail and chases you on sight.";
				mc.player.getInventory().setSelectedSlot(0);
				command(mc, "tp @e[type=backroomscraft:bacteria,limit=1] %.2f %.2f %.2f", startX, startY, startZ);
				facingEntity = true;
			}
			default -> {
			}
		}
		if (t == exitAt) {
			facingEntity = false;
			caption = "Find the exit of the level to escape.";
			BakedLevel.Marker beside = level.starts(BackroomsCraft.LEVEL_ROW.exitTag()).get(0);
			yaw = (float) Math.toDegrees(Math.atan2(-(exit.x() - beside.x()), exit.z() - beside.z()));
			command(mc, "tp @s %.2f %.2f %.2f", beside.x(), beside.y(), beside.z());
		} else if (t == exitAt + 55) {
			command(mc, "tp @s %.2f %.2f %.2f", exit.x() + 1.2, exit.y(), exit.z());
		} else if (t == exitAt + 62) {
			caption = "Steve in the Backrooms. Needs Minecraft: Java Edition and Escape the Backrooms on Steam.";
			for (BakedLevel.Marker start : level.starts(BackroomsCraft.LEVEL_ROW.startTag())) {
				if (mc.player.distanceToSqr(start.x(), start.y(), start.z()) < 4) {
					yaw = start.yaw();
				}
			}
		}
		if (t >= 30 && t < exitAt + 140) {
			Screenshot.grab(mc.gameDirectory, String.format("demo_%04d.png", t - 30), mc.getMainRenderTarget(), 1, message -> {
			});
		}
		if (t == exitAt + 140) {
			BackroomsCraft.LOGGER.info("AUTOTEST demo: {} frames saved as demo_NNNN.png; sounds played {}", t - 30, EtbSounds.played);
		}
		if (t == exitAt + 160) {
			mc.stop();
		}
	}

	private static void command(Minecraft mc, String format, Object... values) {
		mc.player.connection.sendCommand(String.format(java.util.Locale.ROOT, format, values));
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "autotest_" + name + ".png", mc.getMainRenderTarget(), 1,
				message -> BackroomsCraft.LOGGER.info("AUTOTEST {}", message.getString()));
	}
}
