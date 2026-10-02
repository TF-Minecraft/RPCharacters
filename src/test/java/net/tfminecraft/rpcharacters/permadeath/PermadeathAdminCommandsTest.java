package net.tfminecraft.rpcharacters.permadeath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class PermadeathAdminCommandsTest extends PermadeathRuntimeFixture {
    CommandSender sender;final List<String> replies=new ArrayList<>();
    @BeforeEach void setupSender(){sender=mock(CommandSender.class);doAnswer(call->{replies.add(call.getArgument(0));return null;}).when(sender).sendMessage(org.mockito.ArgumentMatchers.anyString());}
    String reply(){return ChatColor.stripColor(String.join("\n",replies));}
    void injure(String...args){assertTrue(PermadeathAdminCommands.handleInjure(sender,args));}
    void kill(String...args){assertTrue(PermadeathAdminCommands.handlePermakill(sender,args));}

    @Test void missingArgumentsShowTheDefaultOrCustomUsage(){
        injure("injure");assertTrue(reply().contains("/rpcharacter injure <player> [character] [permanent]"));replies.clear();assertTrue(PermadeathAdminCommands.handleInjure(sender,new String[]{"admin","injure"},2,"custom injury usage"));assertTrue(reply().contains("custom injury usage"));
        replies.clear();kill("permakill");assertTrue(reply().contains("/rpcharacter admin permakill <player> [character]"));replies.clear();assertTrue(PermadeathAdminCommands.handlePermakill(sender,new String[]{"admin","kill"},2,"kill"));assertTrue(reply().contains("/rpcharacter admin kill <player> [character]"));
    }
    @Test void unknownPlayersAreRejectedByBothCommands(){injure("injure","Nobody");kill("permakill","Nobody");assertEquals(2,replies.size());assertTrue(replies.stream().allMatch(s->s.contains("No player found")));assertTrue(events.isEmpty());}
    @Test void playersWhoseDataIsNotLoadedAreRejectedByBothCommands(){loaded.clear();injure("injure","Aria");kill("permakill","Aria");assertEquals(2,replies.size());assertTrue(replies.stream().allMatch(s->s.contains("Player data not loaded")));assertTrue(character.getTraits().isEmpty());}
    @Test void aMissingActiveCharacterDoesNotChooseAnInactiveOneImplicitly(){loaded.put(player,new PlayerData(player));injure("injure","Aria");kill("permakill","Aria");assertEquals(2,replies.size());assertTrue(replies.stream().allMatch(s->s.contains("no active character")));}
    @Test void unmatchedExplicitCharacterReportsTheQuery(){var nameless=character(null,false);data.addCharacter(nameless);injure("injure","Aria","Missing");kill("permakill","Aria","Missing");assertEquals(2,replies.size());assertTrue(replies.stream().allMatch(s->s.contains("No character found matching")&&s.contains("Missing")));}
    @Test void deadCharactersCannotReceiveInjuriesOrBeKilledAgain(){character.setStatus(Status.DEAD);injure("injure","Aria");assertTrue(reply().contains("not alive"));replies.clear();kill("permakill","Aria");assertTrue(reply().contains("already dead"));assertTrue(events.isEmpty());}
    @Test void randomHealingInjuryMutatesTheActiveCharacterAndReportsExhaustion() throws Exception {pool(healing);injure("injure","Aria");assertEquals(List.of(healing),character.getTraits());assertTrue(reply().contains("Applied a random injury to Aria (Aria)"));replies.clear();injure("injure","Aria");assertTrue(reply().contains("no injuries left to receive"));}
    @Test void permanentSuffixIsCaseInsensitiveAndCanApplyToTheActiveCharacter() throws Exception {progression(healing,permanent);injure("injure","Aria","PeRmAnEnT");assertEquals(List.of(permanent),character.getTraits());assertTrue(reply().contains("random permanent injury"));}
    @Test void explicitCharacterSlugUsesTheChosenInactiveCharacter() throws Exception {pool(healing);RPCharacter other=character("Bryn Stone",false);data.addCharacter(other);injure("injure","Aria",other.getSlug());assertEquals(List.of(healing),other.getTraits());assertTrue(character.getTraits().isEmpty());assertTrue(reply().contains("Bryn Stone"));}
    @Test void explicitCharacterIdAndNameFallbackResolveTheCorrectTarget() throws Exception {progression(healing,permanent);RPCharacter other=character("Bryn Stone",false);data.addCharacter(other);injure("injure","Aria",other.getId(),"permanent");assertEquals(List.of(permanent),other.getTraits());pool(healing);injure("injure","Aria","bRyN sToNe");assertEquals(List.of(permanent,healing),other.getTraits());}
    @Test void customArgumentOffsetsWorkForAnInjuryAndPermakill() throws Exception {pool(healing);assertTrue(PermadeathAdminCommands.handleInjure(sender,new String[]{"admin","injure","Aria"},2,"custom"));assertEquals(List.of(healing),character.getTraits());assertTrue(PermadeathAdminCommands.handlePermakill(sender,new String[]{"admin","kill","Aria"},2,"kill"));assertEquals(Status.DEAD,character.getStatus());assertTrue(reply().contains("Permanently killed Aria (Aria)"));assertEquals(PermakillCause.COMMAND,events.getFirst().getCause());}
    @Test void explicitPermakillUsesTheRequestedInactiveCharacter(){RPCharacter other=character("Bryn",false);data.addCharacter(other);kill("permakill","Aria",other.getId());assertEquals(Status.DEAD,other.getStatus());assertEquals(Status.ALIVE,character.getStatus());assertTrue(reply().contains("Permanently killed Bryn (Aria)"));assertTrue(tasks.isEmpty());}
    @Test void eventCancellationIsReportedAndLeavesTheCharacterAlive(){cancelEvents=true;kill("permakill","Aria");assertTrue(reply().contains("Permakill was cancelled"));assertEquals(Status.ALIVE,character.getStatus());assertTrue(character.isActive());}
}
