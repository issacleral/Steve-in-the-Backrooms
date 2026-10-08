package gg.backroomscraft.game;

import com.mojang.serialization.Codec;
import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.ModItems;
import gg.backroomscraft.bake.BakedLevel;
import gg.backroomscraft.gen.Sheets;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.player.Player;

/**
 * Sanity, as in Escape the Backrooms: it drains slowly, faster in the dark without a flashlight and while the entity
 * is chasing nearby; almond water restores it; at zero it hurts.
 */
public final class Sanity {
	// hook: sanity_attachment
	public static final AttachmentType<Float> SANITY = AttachmentRegistry.create(BackroomsCraft.id("sanity"), builder -> builder
			.initializer(() -> Sheets.Rules.SANITY_MAX).persistent(Codec.FLOAT).syncWith(ByteBufCodecs.FLOAT, AttachmentSyncPredicate.targetOnly()));
	private static final ResourceKey<DamageType> INSANITY = ResourceKey.create(Registries.DAMAGE_TYPE, BackroomsCraft.id("insanity"));

	private Sanity() {
	}

	/** Makes sure the attachment type is registered before any player loads. */
	public static void register() {
	}

	public static float get(Player player) {
		return player.getAttachedOrCreate(SANITY);
	}

	public static void set(ServerPlayer player, float value) {
		float clamped = Math.max(0, Math.min(Sheets.Rules.SANITY_MAX, value));
		if (Math.abs(clamped - get(player)) > 0.001F) {
			player.setAttached(SANITY, clamped);
		}
	}

	public static void add(ServerPlayer player, float amount) {
		set(player, get(player) + amount);
	}

	/** One second of a run. */
	static void second(ServerPlayer player, ServerLevel level, BakedLevel baked, boolean chased) {
		if (player.isCreative() || player.isSpectator()) {
			return;
		}
		float drain = Sheets.Rules.SANITY_DRAIN_BASE;
		boolean dark = baked.brightness(player.getX(), player.getY() + 1, player.getZ()) < Sheets.Rules.SANITY_DARK_LIGHT;
		if (dark && !ModItems.flashlightOn(player)) {
			drain += Sheets.Rules.SANITY_DRAIN_DARK;
		}
		if (chased) {
			drain += Sheets.Rules.SANITY_DRAIN_ENTITY;
		}
		add(player, -drain);
		if (get(player) <= 0) {
			player.hurtServer(level, new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(INSANITY)),
					Sheets.Rules.SANITY_ZERO_DAMAGE);
		}
	}
}
