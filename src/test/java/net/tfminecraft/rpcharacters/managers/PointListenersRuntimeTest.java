package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.mmocore.*;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.races.Race;

class PointListenersRuntimeTest {
    ServerMock server; PlayerMock player; RuntimeTestState state; PlayerData data; RPCharacter character;
    MockedStatic<PlayerManager> players; MockedStatic<AttributePointService> attributes;
    MockedStatic<ClassService> skills; MockedStatic<AttributePointTomeLoader> attributeTomes;
    MockedStatic<SkillPointTomeLoader> skillTomes;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState();
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>(); player = server.addPlayer("Learner");
        data = new PlayerData(player); var cfg = new YamlConfiguration(); cfg.set("name", "Human");
        character = new RPCharacter(player, UUID.randomUUID().toString(), "Learner", true, Status.ALIVE, new Race("human", cfg), new ArrayList<>(), null);
        data.getCharacters().add(character);
        players = mockStatic(PlayerManager.class); players.when(() -> PlayerManager.get(player)).thenReturn(data);
        attributes = mockStatic(AttributePointService.class); skills = mockStatic(ClassService.class);
        attributeTomes = mockStatic(AttributePointTomeLoader.class); skillTomes = mockStatic(SkillPointTomeLoader.class);
    }
    @AfterEach void cleanup() { skillTomes.close(); attributeTomes.close(); skills.close(); attributes.close(); players.close(); state.close(); MockBukkit.unmock(); }
    Player resolve(boolean attribute, String command) { return attribute ? AttributePointCommandListener.resolveAttributePointCommandTarget(command) : SkillPointCommandListener.resolveSkillPointCommandTarget(command); }
    void invoke(boolean attribute, PlayerInteractEvent event) { if (attribute) new AttributePointTomeListener().onPlayerInteract(event); else new SkillPointTomeListener().onPlayerInteract(event); }
    PlayerInteractEvent event(Action action, EquipmentSlot hand) { return new PlayerInteractEvent(player, action, player.getInventory().getItemInMainHand(), null, org.bukkit.block.BlockFace.UP, hand); }
    void tome(int points) {
        var config = new YamlConfiguration(); config.set("item", "minecraft:book"); config.set("skill-points", points); config.set("attribute-points", points);
        var attribute = new AttributePointTomeDefinition("test", config); var skill = new SkillPointTomeDefinition("test", config);
        attributeTomes.when(() -> AttributePointTomeLoader.resolve(any())).thenReturn(attribute);
        skillTomes.when(() -> SkillPointTomeLoader.resolve(any())).thenReturn(skill);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void commandParsingIgnoresUnrelatedCommandsAndSynchronizesBothSenders(boolean attribute) {
        String points = attribute ? "attribute-points" : "skill-points";
        for (String invalid : new String[]{null, " ", "/mmocore", "other admin " + points + " give Learner 2", "mmocore user " + points + " give Learner 2", "mmocore admin wrong give Learner 2", "mmocore admin " + points + " remove Learner 2", "mmocore admin " + points + " give Offline 2"}) assertNull(resolve(attribute, invalid));
        String command = "mmocore admin " + points + " give Learner 2";
        assertSame(player, resolve(attribute, " /" + command));
        assertSame(player, resolve(attribute, command.replace("give", "SET")));
        var console = server.getConsoleSender();
        if (attribute) {
            var listener = new AttributePointCommandListener(); listener.onServerCommand(new ServerCommandEvent(console, command)); listener.onPlayerCommand(new PlayerCommandPreprocessEvent(player, "/" + command)); listener.onPlayerCommand(new PlayerCommandPreprocessEvent(player, "/other"));
            attributes.verify(() -> AttributePointService.scheduleSyncAttributePoints(player, console)); attributes.verify(() -> AttributePointService.scheduleSyncAttributePoints(player, player));
        } else {
            var listener = new SkillPointCommandListener(); listener.onServerCommand(new ServerCommandEvent(console, command)); listener.onPlayerCommand(new PlayerCommandPreprocessEvent(player, "/" + command)); listener.onPlayerCommand(new PlayerCommandPreprocessEvent(player, "/other"));
            skills.verify(() -> ClassService.scheduleSyncSkillPoints(player, console)); skills.verify(() -> ClassService.scheduleSyncSkillPoints(player, player));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void namespacedMmoCoreCommandsStillSynchronizeTheCharacter(boolean attribute) {
        String points = attribute ? "attribute-points" : "skill-points";
        assertSame(player, resolve(attribute, "/mmocore:mmocore admin " + points + " give Learner 2"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void tomesIgnoreOtherActionsHandsAndItemsAndRequireAnActiveCharacter(boolean attribute) {
        player.getInventory().setItemInMainHand(new ItemStack(Material.BOOK, 2));
        invoke(attribute, event(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND));
        invoke(attribute, event(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND));
        invoke(attribute, event(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
        tome(2); players.when(() -> PlayerManager.get(player)).thenReturn(null);
        invoke(attribute, event(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));
        players.when(() -> PlayerManager.get(player)).thenReturn(data); data.getCharacters().clear();
        invoke(attribute, event(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND));
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(player.nextMessage().contains("active character")); attributes.verifyNoInteractions(); skills.verifyNoInteractions();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void eachTomeGrantsItsConfiguredPointsAndConsumesExactlyOneItem(boolean attribute) {
        for (int amount : new int[]{1, 3}) {
            tome(amount); player.getInventory().setItemInMainHand(new ItemStack(Material.BOOK, amount));
            var event = event(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND); invoke(attribute, event);
            assertTrue(event.isCancelled());
            if (amount == 1) assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
            else assertEquals(amount - 1, player.getInventory().getItemInMainHand().getAmount());
            assertTrue(player.nextMessage().contains("+" + amount));
            if (attribute) attributes.verify(() -> AttributePointService.grantAttributePoints(player, amount));
            else skills.verify(() -> ClassService.grantSkillPoints(player, amount));
        }
    }

    @Test void attributeSpendCapturesAllocationThenReappliesRemainingPoints() {
        var event = mock(net.Indyuce.mmocore.api.event.PlayerAttributeUseEvent.class); when(event.getPlayer()).thenReturn(player);
        var listener = new AttributePointSpendListener();
        players.when(() -> PlayerManager.get(player)).thenReturn(null); listener.onAttributeUse(event);
        players.when(() -> PlayerManager.get(player)).thenReturn(data); data.getCharacters().clear(); listener.onAttributeUse(event);
        attributes.verifyNoInteractions(); data.getCharacters().add(character); listener.onAttributeUse(event);
        attributes.verify(() -> AttributePointService.captureAllocationFromMmo(player, character));
        attributes.verify(() -> AttributePointService.applyFreeAttributePoints(player, character));
    }
}
