package net.tfminecraft.rpcharacters.will;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.estate.EstateHooks;
import net.tfminecraft.rpcharacters.estate.EstateLink;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.will.WillScreens.Button;
import net.tfminecraft.rpcharacters.will.WillScreens.Input;
import net.tfminecraft.rpcharacters.will.WillScreens.Screen;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;

/**
 * {@code /will} and the Character Info button (design W3): the current character's heir, what its estate
 * holds today, and naming or revoking an heir. The session is captured when the menu opens and re-checked
 * on every click; the heir is never told.
 */
public final class WillMenu {
    static final double NEARBY_BLOCKS = 16;
    static BiConsumer<Player, Screen> screens = WillScreens::show;

    private record Context(Player player, CharacterSession session, PlayerData data, RPCharacter character) {}

    private WillMenu() {}

    public static void open(Player player) {
        PlayerData data = PlayerManager.get(player);
        Optional<CharacterSession> session = Characters.currentSession(player);
        RPCharacter character = data == null || session.isEmpty() ? null
                : data.getCharacterById(session.get().ref().characterId().toString());
        if (character == null) {
            player.sendMessage("Play a character to make its will.");
            return;
        }
        main(new Context(player, session.get(), data, character));
    }

    private static void main(Context ctx) {
        CharacterWill will = ctx.character().getWill();
        List<String> lines = new ArrayList<>();
        String heir = WillService.heirName(will);
        lines.add(will.named() ? "Heir: " + (heir == null ? "a character who can't be found now" : heir) : "No heir is named.");
        lines.add("Without a living heir, money goes to your guild and property to its leader; without a guild, staff hold it.");
        EstateHooks estates = EstateLink.current();
        List<String> held = estates == null ? List.of() : estates.holdings(UUID.fromString(ctx.character().getId()));
        lines.add(held.isEmpty() ? "Your estate holds nothing recorded today." : "Your estate today:");
        for (String line : held) lines.add("• " + line);
        lines.add("Pets and animals are not included yet.");
        List<Button> buttons = new ArrayList<>();
        buttons.add(button(ctx, "Name an heir", values -> ask(ctx)));
        if (will.named()) buttons.add(button(ctx, "Revoke", values -> confirm(ctx, null, "Revoke your will? Nobody will be named.")));
        buttons.add(button(ctx, "Close", values -> ctx.player().closeDialog()));
        screens.accept(ctx.player(), new Screen("Will of " + plain(ctx.character().getName()), lines, null, buttons));
    }

    private static void ask(Context ctx) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(button(ctx, "Find", values -> find(ctx, values.apply("heir"))));
        for (CharacterInfo near : nearby(ctx)) buttons.add(button(ctx, "Name " + near.name(), values -> chosen(ctx, near)));
        buttons.add(button(ctx, "Back", values -> main(ctx)));
        screens.accept(ctx.player(), new Screen("Name an heir", List.of("Type the heir's character name, or pick someone nearby."),
                new Input("heir", "Character name", List.of()), buttons));
    }

    private static void find(Context ctx, String name) {
        List<CharacterInfo> found = new ArrayList<>();
        String wanted = name == null ? "" : name.trim();
        if (!wanted.isEmpty()) {
            for (CharacterInfo info : Characters.findByName(wanted)) {
                if (!info.ref().characterId().toString().equalsIgnoreCase(ctx.character().getId())) found.add(info);
            }
        }
        if (found.isEmpty()) {
            screens.accept(ctx.player(), new Screen("No match", List.of("No other living character is called \"" + wanted + "\"."),
                    null, List.of(button(ctx, "Back", values -> ask(ctx)))));
        } else if (found.size() == 1) {
            chosen(ctx, found.getFirst());
        } else {
            List<String[]> options = new ArrayList<>();
            for (CharacterInfo info : found) options.add(new String[] {info.ref().characterId().toString(), describe(ctx, info)});
            screens.accept(ctx.player(), new Screen("Several characters", List.of("Several living characters share that name."),
                    new Input("pick", "Character", options), List.of(
                            button(ctx, "Choose", values -> found.stream()
                                    .filter(info -> info.ref().characterId().toString().equals(values.apply("pick")))
                                    .findFirst().ifPresent(info -> chosen(ctx, info))),
                            button(ctx, "Back", values -> ask(ctx)))));
        }
    }

    private static void chosen(Context ctx, CharacterInfo heir) {
        confirm(ctx, heir.ref().characterId(), "Name " + describe(ctx, heir) + " as your heir?");
    }

    private static void confirm(Context ctx, UUID heir, String question) {
        screens.accept(ctx.player(), new Screen("Your will", List.of(question), null, List.of(
                button(ctx, "Confirm", values -> apply(ctx, heir)), button(ctx, "Back", values -> main(ctx)))));
    }

    private static void apply(Context ctx, UUID heir) {
        WillService.Change change = WillService.change(ctx.data(), ctx.character(), ctx.player().getUniqueId(), heir, null);
        ctx.player().closeDialog();
        if (change.applied()) {
            ctx.player().sendMessage(heir == null ? "Your will is revoked." : "Your heir is named.");
            return;
        }
        ctx.player().sendMessage(switch (change.verdict()) {
            case OK -> "Your will could not be saved. Try again or contact staff.";
            case HEIR_UNREADABLE -> "That character can't be checked right now. Try again later.";
            default -> "That character can't be your heir.";
        });
    }

    private static Button button(Context ctx, String label, java.util.function.Consumer<java.util.function.Function<String, String>> action) {
        return new Button(label, values -> {
            if (!Characters.isCurrent(ctx.session())) {
                ctx.player().closeDialog();
                ctx.player().sendMessage("Your character changed. Open /will again.");
                return;
            }
            action.accept(values);
        });
    }

    /** Characters played by players within 16 blocks, excluding this one. */
    private static List<CharacterInfo> nearby(Context ctx) {
        List<CharacterInfo> found = new ArrayList<>();
        Player self = ctx.player();
        for (Player other : self.getWorld().getPlayers()) {
            if (other == self || other.getLocation().distanceSquared(self.getLocation()) > NEARBY_BLOCKS * NEARBY_BLOCKS) continue;
            Characters.currentSession(other).flatMap(session -> Characters.info(session.ref().characterId()))
                    .filter(CharacterInfo::alive).ifPresent(found::add);
        }
        return found;
    }

    private static String describe(Context ctx, CharacterInfo info) {
        Race race = info.raceId() == null ? null : RaceLoader.getByString(info.raceId());
        String raceName = race == null ? info.raceId() : plain(race.getName());
        return info.name() + " (" + raceName + ", " + info.ref().characterId().toString().substring(0, 8)
                + (info.ref().ownerId().equals(ctx.player().getUniqueId()) ? ", on your account)" : ")");
    }

    private static String plain(String text) {
        return ClueFormatter.stripColor(text).trim();
    }
}
