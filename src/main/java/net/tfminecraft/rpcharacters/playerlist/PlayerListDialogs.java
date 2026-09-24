package net.tfminecraft.rpcharacters.playerlist;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.destroystokyo.paper.profile.ProfileProperty;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.profile.ProfileManager;

/**
 * The online player list and character sheet dialogs. Names are the safe
 * character display; hovering shows the account, rank and ping; clicking opens
 * the character sheet through the normal profile view rules.
 */
public final class PlayerListDialogs {

	/** Custom click id sent by the Quick Actions menu button. */
	public static final Key OPEN_ACTION = Key.key("rpcharacters", "player_list");

	private static final int SCREEN_BUDGET = 460;
	private static final int CARD_WIDTH = 190;
	private static final int BODY_WIDTH = 420;
	private static final ClickCallback.Options CALLBACKS = ClickCallback.Options.builder()
			.uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(10)).build();

	private PlayerListDialogs() {}

	public static void openList(Player viewer) {
		PlayerListSettings settings = Cache.playerList;
		List<Player> online = visiblePlayers(viewer, settings);
		int widest = online.stream().mapToInt(p -> GuiText.width(label(p, settings)) + 10).max().orElse(100);
		int buttonWidth = Math.max(100, Math.min(220, widest + 10));
		int columns = Math.max(1, Math.min(4, SCREEN_BUDGET / (buttonWidth + 4)));
		boolean canViewProfiles = viewer.hasPermission(Cache.profilePermission);

		List<ActionButton> buttons = new ArrayList<>(online.size());
		for (Player player : online) {
			UUID subject = player.getUniqueId();
			buttons.add(ActionButton.builder(face(player).append(Component.text(" "))
							.append(GuiText.component(label(player, settings))))
					.tooltip(tooltip(player, settings, canViewProfiles))
					.width(buttonWidth)
					.action(DialogAction.customClick((response, audience) -> {
						Player target = Bukkit.getPlayer(subject);
						if (audience instanceof Player clicker && target != null && clicker.canSee(target)) {
							ProfileManager.showProfileSheet(clicker, target);
						}
					}, CALLBACKS))
					.build());
		}
		Component header = GuiText.component(format(settings.header().replace("{online}",
				Integer.toString(online.size()))));
		Dialog dialog = Dialog.create(builder -> builder.empty()
				.base(DialogBase.builder(GuiText.component(format(settings.title())))
						.externalTitle(Component.text("Online players"))
						.canCloseWithEscape(true)
						.body(List.of(DialogBody.plainMessage(header, BODY_WIDTH)))
						.build())
				.type(DialogType.multiAction(buttons,
						ActionButton.builder(Component.text("Close")).width(120).build(), columns)));
		viewer.showDialog(dialog);
	}

	/** Shows an allowed profile view as a character sheet: skin portrait beside the profile lines. */
	public static void openCharacterSheet(Player viewer, Player target, List<String> profileLines) {
		List<String> card = new ArrayList<>();
		for (String line : profileLines) {
			card.addAll(GuiText.wrap(line, CARD_WIDTH));
		}
		String tag = format(Cache.playerList.tag(rank(target, Cache.playerList)));
		int ping = target.getPing();
		card.add("");
		card.add("§7(" + target.getName() + ")" + (tag.isEmpty() ? "" : "  " + tag));
		card.add("§8Ping: " + pingColour(ping) + ping + "ms");

		SkinPortrait.SkinTexture texture = Cache.playerList.portrait() ? SkinPortrait.texture(target) : null;
		CompletableFuture<BufferedImage> skin = SkinPortrait.fetch(texture);
		UUID targetId = target.getUniqueId();
		skin.thenAccept(image -> Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
			Player current = Bukkit.getPlayer(targetId);
			if (!viewer.isOnline() || current == null || !viewer.canSee(current)) {
				return;
			}
			BufferedImage front = image == null ? null : SkinPortrait.front(image, texture.slim());
			viewer.showDialog(sheet(current, card, front));
		}));
	}

	private static Dialog sheet(Player target, List<String> card, BufferedImage front) {
		TextComponent.Builder body = Component.text();
		if (front == null) {
			for (int i = 0; i < card.size(); i++) {
				body.append(GuiText.component(card.get(i)));
				if (i < card.size() - 1) {
					body.append(Component.newline());
				}
			}
		} else {
			List<Component> portrait = SkinPortrait.rows(front);
			int rows = Math.max(portrait.size(), card.size() + 1);
			for (int y = 0; y < rows; y++) {
				body.append(y < portrait.size() ? portrait.get(y) : SkinPortrait.blankRow());
				body.append(Component.text(SkinPortrait.BLANK + SkinPortrait.BLANK));
				int line = y - 1;
				body.append(GuiText.padded(line >= 0 && line < card.size() ? card.get(line) : "", CARD_WIDTH));
				if (y < rows - 1) {
					body.append(Component.newline());
				}
			}
		}
		Component title = face(target).append(Component.text(" "))
				.append(GuiText.component(DisplayIdentityService.resolveDisplaySafe(target)));
		return Dialog.create(builder -> builder.empty()
				.base(DialogBase.builder(title)
						.externalTitle(Component.text("Character"))
						.canCloseWithEscape(true)
						.body(List.of(DialogBody.plainMessage(body.build(), BODY_WIDTH)))
						.build())
				.type(DialogType.notice(ActionButton.builder(Component.text("Back to player list"))
						.width(160)
						.action(DialogAction.customClick((response, audience) -> {
							if (audience instanceof Player clicker) {
								openList(clicker);
							}
						}, CALLBACKS))
						.build())));
	}

	static List<Player> visiblePlayers(Player viewer, PlayerListSettings settings) {
		List<Player> online = new ArrayList<>();
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player.equals(viewer) || viewer.canSee(player)) {
				online.add(player);
			}
		}
		online.sort(Comparator.<Player>comparingInt(p -> settings.rankIndex(rank(p, settings)))
				.thenComparing(p -> GuiText.plain(DisplayIdentityService.resolveDisplaySafe(p)),
						String.CASE_INSENSITIVE_ORDER));
		return online;
	}

	/** First configured LuckPerms group the player is in, via its {@code group.<name>} node. */
	static String rank(Player player, PlayerListSettings settings) {
		return settings.rank(group -> {
			String node = "group." + group;
			// isPermissionSet skips operators' blanket permissions.
			return player.isPermissionSet(node) && player.hasPermission(node);
		});
	}

	private static String label(Player player, PlayerListSettings settings) {
		String tag = format(settings.tag(rank(player, settings)));
		String name = DisplayIdentityService.resolveDisplaySafe(player);
		return tag.isEmpty() ? name : tag + "§r - " + name;
	}

	private static Component tooltip(Player player, PlayerListSettings settings, boolean canViewProfile) {
		String tag = format(settings.tag(rank(player, settings)));
		int ping = player.getPing();
		TextComponent.Builder tooltip = Component.text()
				.append(GuiText.component(DisplayIdentityService.resolveDisplaySafe(player)))
				.append(Component.newline())
				.append(Component.text("(" + player.getName() + ")", NamedTextColor.GRAY))
				.append(Component.newline())
				.append(GuiText.component((tag.isEmpty() ? "§7No rank" : tag)
						+ "§r§8  |  " + pingColour(ping) + ping + "ms"));
		if (canViewProfile) {
			tooltip.append(Component.newline())
					.append(Component.text("Click to view their character", NamedTextColor.DARK_GRAY));
		}
		return tooltip.build();
	}

	/** The player's face as an inline text sprite, from their profile textures. */
	private static Component face(Player player) {
		PlayerHeadObjectContents.Builder head = ObjectContents.playerHead()
				.id(player.getUniqueId()).name(player.getName()).hat(true);
		for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
			if ("textures".equals(property.getName())) {
				head.profileProperty(PlayerHeadObjectContents.property(
						property.getName(), property.getValue(), property.getSignature()));
			}
		}
		return Component.object(head.build());
	}

	static String pingColour(int ping) {
		if (ping < 80) {
			return "§a";
		}
		if (ping < 150) {
			return "§e";
		}
		if (ping < 300) {
			return "§6";
		}
		return "§c";
	}

	private static String format(String configText) {
		return StringFormatter.formatHex(configText == null ? "" : configText);
	}
}
