package gg.backroomscraft.client;

import gg.backroomscraft.BackroomsCraft;
import gg.backroomscraft.EtbLibrary;
import gg.backroomscraft.game.Sanity;
import gg.backroomscraft.gen.Sheets;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** The sanity bar in the bottom left corner, the darkening edges when sanity is low, and the notices about the game files. */
public final class SanityHud {
	private static final int BAR_WIDTH = 81, BAR_HEIGHT = 5;

	private SanityHud() {
	}

	// hook: hud
	static void register() {
		HudElementRegistry.addLast(BackroomsCraft.id("sanity"), SanityHud::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) {
			return;
		}
		Font font = mc.font;
		int width = graphics.guiWidth(), height = graphics.guiHeight();
		if (!ClientGame.inLevel(mc)) {
			Component notice = switch (EtbLibrary.state()) {
				case MISSING -> Component.translatable("backroomscraft.missing");
				case FAILED -> Component.translatable("backroomscraft.failed", EtbLibrary.error());
				default -> EtbLibrary.progress().isEmpty() ? Component.translatable("backroomscraft.loading")
						: Component.translatable("backroomscraft.baking", EtbLibrary.progress());
			};
			int w = font.width(notice);
			graphics.fill(width / 2 - w / 2 - 6, 30, width / 2 + w / 2 + 6, 46, 0xB0000000);
			graphics.drawCenteredString(font, notice, width / 2, 34, 0xFFFFD060);
			return;
		}
		if (Autotest.caption != null) {
			int w = font.width(Autotest.caption);
			graphics.fill(width / 2 - w / 2 - 6, 14, width / 2 + w / 2 + 6, 30, 0xB0000000);
			graphics.drawCenteredString(font, Autotest.caption, width / 2, 18, 0xFFFFFFFF);
		}
		float sanity = Sanity.get(mc.player);
		float share = Math.max(0, Math.min(1, sanity / Sheets.Rules.SANITY_MAX));
		if (sanity < Sheets.Rules.SANITY_LOW) {
			// Dark bands closing in from the edges, stronger as sanity runs out.
			float lost = 1 - sanity / Sheets.Rules.SANITY_LOW;
			for (int band = 0; band < 6; band++) {
				int alpha = Math.round(lost * 150 * (6 - band) / 6F);
				int inset = band * Math.max(4, height / 40), next = (band + 1) * Math.max(4, height / 40);
				int colour = alpha << 24 | 0x100000;
				graphics.fill(inset, inset, width - inset, next, colour);
				graphics.fill(inset, height - next, width - inset, height - inset, colour);
				graphics.fill(inset, next, next, height - next, colour);
				graphics.fill(width - next, next, width - inset, height - next, colour);
			}
		}
		if (mc.player.isCreative() || mc.player.isSpectator()) {
			return;
		}
		// Bottom left, clear of the hotbar, the held item's name and the action bar.
		int x = 8, y = height - 14;
		int fill = Math.round(BAR_WIDTH * share);
		int colour = sanity < Sheets.Rules.SANITY_LOW ? 0xFFD03030 : share < 0.6F ? 0xFFE0B040 : 0xFF58C0D8;
		graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, 0xC0000000);
		graphics.fill(x, y, x + fill, y + BAR_HEIGHT, colour);
		String label = Component.translatable("backroomscraft.sanity").getString() + " " + Math.round(sanity);
		graphics.drawString(font, label, x, y - 11, 0xE0FFFFFF, true);
	}
}
