package gg.backroomscraft.game;

import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.ModItems;
import gg.backroomscraft.bake.BakedLevel;
import gg.backroomscraft.entity.BacteriaEntity;
import gg.backroomscraft.gen.Sheets;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A run: the player is put at one of the map's own start points with the starting kit, the map's own pickups and
 * entity are placed where the map has them, and the run starts again after reaching the exit, dying or falling out.
 */
public final class Run {
	/** Marks the item entities this class placed, so a new run can clear them. */
	private static final String PLACED_TAG = "backroomscraft_placed";
	/** Server tick each player's current run began at. */
	private static final Map<UUID, Integer> STARTED = new HashMap<>();
	/** Map actors already turned into an item or the entity in the current run, by name. */
	private static final Set<String> PLACED = new HashSet<>();
	/** Server tick the entity was first found missing, or -1. */
	private static int entityGoneSince = -1;
	/** Where the player has stood during the run, oldest first: places known to be open and joined to each other. */
	private static final ArrayDeque<Vec3> TRAIL = new ArrayDeque<>();

	private Run() {
	}

	public static void register() {
		// hook: server_tick
		ServerTickEvents.END_SERVER_TICK.register(Run::tick);
		// hook: player_join
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> STARTED.remove(handler.player.getUUID()));
		// hook: player_respawn
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> STARTED.remove(newPlayer.getUUID()));
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 5 != 0) {
			return;
		}
		EtbLibrary.State state = EtbLibrary.state();
		BakedLevel baked = EtbLibrary.level();
		ServerLevel level = server.getLevel(BackroomsCraft.LEVEL);
		Sheets.Level row = BackroomsCraft.LEVEL_ROW;
		boolean second = server.getTickCount() % 20 == 0;
		boolean anyone = false;
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			if (state != EtbLibrary.State.READY || baked == null || level == null) {
				if (server.getTickCount() % 40 == 0) {
					player.displayClientMessage(switch (state) {
						case MISSING -> Component.translatable("backroomscraft.missing");
						case FAILED -> Component.translatable("backroomscraft.failed", EtbLibrary.error());
						default -> EtbLibrary.progress().isEmpty() ? Component.translatable("backroomscraft.loading")
								: Component.translatable("backroomscraft.baking", EtbLibrary.progress());
					}, true);
				}
				continue;
			}
			anyone = true;
			if (player.level() != level || !STARTED.containsKey(player.getUUID()) || player.getY() < row.floorY() + row.killZCm() / 100) {
				begin(server, player, level, baked);
				continue;
			}
			if (second) {
				Sanity.second(player, level, baked, chased(player, level));
				if (player.onGround() && (TRAIL.isEmpty() || TRAIL.getLast().distanceToSqr(player.position()) > 9)) {
					TRAIL.addLast(player.position());
					if (TRAIL.size() > 200) {
						TRAIL.removeFirst();
					}
				}
				if (server.getTickCount() % Math.round(Sheets.Rules.ENTITY_RELOCATE_S * 20) == 0) {
					relocate(player, level);
				}
			}
			for (BakedLevel.Marker exit : baked.markers(row.exitClass())) {
				if (player.distanceToSqr(exit.x(), exit.y(), exit.z()) < Sheets.Rules.EXIT_RADIUS * Sheets.Rules.EXIT_RADIUS) {
					int ticks = server.getTickCount() - STARTED.get(player.getUUID());
					String time = String.format("%d:%02d", ticks / 1200, ticks / 20 % 60);
					BackroomsCraft.LOGGER.info("{} escaped {} in {}", player.getName().getString(), row.name(), time);
					player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("backroomscraft.escaped.title")));
					player.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("backroomscraft.escaped.subtitle", time)));
					begin(server, player, level, baked);
					break;
				}
			}
		}
		if (anyone && level != null && baked != null) {
			populate(server, level, baked);
		}
	}

	private static boolean chased(ServerPlayer player, ServerLevel level) {
		double range = Sheets.Rules.SANITY_ENTITY_RANGE;
		return !level.getEntities(BacteriaEntity.TYPE, e -> e.isAlive() && e.getTarget() == player && e.distanceToSqr(player) < range * range).isEmpty();
	}

	/**
	 * Moves an entity that is idle and far from the player onto the trail the player has walked, out of sight. The map
	 * keeps the entity in a closed room until a scripted event; this is what brings it into the hunt here.
	 */
	private static void relocate(ServerPlayer player, ServerLevel level) {
		double far = Sheets.Rules.ENTITY_FAR, min = Sheets.Rules.ENTITY_RELOCATE_MIN, max = Sheets.Rules.ENTITY_RELOCATE_MAX;
		for (BacteriaEntity bacteria : level.getEntities(BacteriaEntity.TYPE, e -> e.isAlive() && e.getTarget() == null)) {
			if (bacteria.distanceToSqr(player) < far * far) {
				continue;
			}
			Vec3 best = null;
			for (Vec3 spot : TRAIL) {
				double distance = spot.distanceTo(player.position());
				if (distance >= min && distance <= max && (best == null || distance < best.distanceTo(player.position()))) {
					best = spot;
				}
			}
			if (best != null) {
				bacteria.teleportTo(best.x, best.y, best.z);
				bacteria.getNavigation().stop();
			}
		}
	}

	/** Puts a player at one of the map's start points with a fresh kit, and resets what the run placed. */
	private static void begin(MinecraftServer server, ServerPlayer player, ServerLevel level, BakedLevel baked) {
		List<BakedLevel.Marker> starts = baked.starts(BackroomsCraft.LEVEL_ROW.startTag());
		if (starts.isEmpty()) {
			return;
		}
		List<Entity> old = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof BacteriaEntity || entity.getTags().contains(PLACED_TAG)) {
				old.add(entity);
			}
		}
		old.forEach(Entity::discard);
		PLACED.clear();
		TRAIL.clear();
		entityGoneSince = -1;

		// -Dbackroomscraft.start=N fixes the start point, for testing each one.
		int fixed = Integer.getInteger("backroomscraft.start", -1);
		BakedLevel.Marker start = starts.get(fixed >= 0 ? fixed % starts.size() : player.getRandom().nextInt(starts.size()));
		player.teleportTo(level, start.x(), start.y(), start.z(), Set.of(), start.yaw(), 0, false);
		player.setDeltaMovement(0, 0, 0);
		player.fallDistance = 0;
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		Sanity.set(player, Sheets.Rules.SANITY_MAX);
		if (!player.isCreative()) {
			Inventory inventory = player.getInventory();
			inventory.clearContent();
			for (Sheets.Kit kit : Sheets.KIT) {
				Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.item()));
				inventory.setItem(kit.slot(), new ItemStack(item, kit.count()));
			}
		}
		STARTED.put(player.getUUID(), server.getTickCount());
	}

	/** Turns the map's own pickup actors into items and its entity actor into the entity, once their chunk is loaded. */
	private static void populate(MinecraftServer server, ServerLevel level, BakedLevel baked) {
		Sheets.Level row = BackroomsCraft.LEVEL_ROW;
		String entityMarker = null;
		for (BakedLevel.Marker marker : baked.markers) {
			boolean entity = marker.className().equals(row.entityClass());
			Item item = null;
			for (Sheets.Item candidate : Sheets.ITEMS) {
				if (candidate.pickupClass().equals(marker.className())) {
					item = ModItems.ITEMS.get(candidate.id());
				}
			}
			if (entity) {
				entityMarker = marker.name();
			}
			if ((!entity && item == null) || PLACED.contains(marker.name()) || !level.isLoaded(BlockPos.containing(marker.x(), marker.y(), marker.z()))) {
				continue;
			}
			if (entity) {
				BacteriaEntity bacteria = BacteriaEntity.TYPE.create(level, EntitySpawnReason.EVENT);
				if (bacteria != null) {
					// The actor's position is the middle of its capsule; the feet are half its height lower.
					bacteria.setPos(marker.x(), marker.y() - BacteriaEntity.ROW.height() / 2, marker.z());
					bacteria.setYRot(marker.yaw());
					level.addFreshEntity(bacteria);
				}
			} else {
				ItemEntity drop = new ItemEntity(level, marker.x(), marker.y() + 0.25, marker.z(), new ItemStack(item), 0, 0, 0);
				drop.setUnlimitedLifetime();
				drop.addTag(PLACED_TAG);
				level.addFreshEntity(drop);
			}
			PLACED.add(marker.name());
		}
		// A dead entity comes back at its start after a while.
		if (entityMarker != null && PLACED.contains(entityMarker) && level.getEntities(BacteriaEntity.TYPE, Entity::isAlive).isEmpty()) {
			if (entityGoneSince < 0) {
				entityGoneSince = server.getTickCount();
			} else if (server.getTickCount() - entityGoneSince > BacteriaEntity.ROW.respawnS() * 20) {
				PLACED.remove(entityMarker);
				entityGoneSince = -1;
			}
		} else {
			entityGoneSince = -1;
		}
	}
}
