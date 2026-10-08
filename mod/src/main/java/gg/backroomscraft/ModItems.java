package gg.backroomscraft;

import com.mojang.serialization.Codec;
import gg.backroomscraft.game.Sanity;
import gg.backroomscraft.gen.Sheets;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** The items of the items sheet, and the component that stores whether a flashlight is on. */
public final class ModItems {
	// hook: lit_component
	public static final DataComponentType<Boolean> LIT = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, BackroomsCraft.id("lit"),
			DataComponentType.<Boolean>builder().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());

	/** Registered items by their id in the items sheet. */
	public static final Map<String, Item> ITEMS = new LinkedHashMap<>();

	private ModItems() {
	}

	// hook: items
	static void register() {
		for (Sheets.Item row : Sheets.ITEMS) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, BackroomsCraft.id(row.id()));
			Item.Properties properties = new Item.Properties().stacksTo(row.stack());
			Item item = row.kind().equals("flashlight") ? Items.registerItem(key, FlashlightItem::new, properties.component(LIT, false))
					: Items.registerItem(key, AlmondWaterItem::new, properties);
			ITEMS.put(row.id(), item);
		}
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> ITEMS.values().forEach(entries::accept));
	}

	/** Whether the player holds a flashlight that is switched on, in either hand. */
	public static boolean flashlightOn(Player player) {
		return lit(player.getMainHandItem()) || lit(player.getOffhandItem());
	}

	private static boolean lit(ItemStack stack) {
		return stack.getItem() instanceof FlashlightItem && stack.getOrDefault(LIT, false);
	}

	public static final class FlashlightItem extends Item {
		FlashlightItem(Properties properties) {
			super(properties);
		}

		@Override
		public InteractionResult use(Level level, Player player, InteractionHand hand) {
			ItemStack stack = player.getItemInHand(hand);
			if (!level.isClientSide()) {
				boolean on = !stack.getOrDefault(LIT, false);
				stack.set(LIT, on);
				level.playSound(null, player.blockPosition(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.4F, on ? 1.5F : 1.2F);
				player.displayClientMessage(Component.translatable(on ? "backroomscraft.flashlight.on" : "backroomscraft.flashlight.off"), true);
			}
			return InteractionResult.SUCCESS;
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
			lines.accept(Component.translatable("backroomscraft.tooltip.flashlight"));
		}

		/** Switching it on changes the stack; that must not replay the equip animation. */
		@Override
		public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
			return false;
		}
	}

	public static final class AlmondWaterItem extends Item {
		AlmondWaterItem(Properties properties) {
			super(properties);
		}

		@Override
		public InteractionResult use(Level level, Player player, InteractionHand hand) {
			ItemStack stack = player.getItemInHand(hand);
			if (player instanceof ServerPlayer server) {
				Sanity.add(server, Sheets.Rules.ALMOND_SANITY);
				server.heal(Sheets.Rules.ALMOND_HEAL);
				server.getFoodData().eat(Math.round(Sheets.Rules.ALMOND_FOOD), 0.3F);
				level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_DRINK.value(), SoundSource.PLAYERS, 0.8F, 1.0F);
				if (!player.isCreative()) {
					stack.shrink(1);
				}
			}
			return InteractionResult.SUCCESS;
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
			lines.accept(Component.translatable("backroomscraft.tooltip.almond_water"));
		}
	}
}
