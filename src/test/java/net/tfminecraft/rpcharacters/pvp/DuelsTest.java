package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.rpcharacters.loaders.DuelLoader;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class DuelsTest extends PvpRuntimeFixture {
    DuelCommand handler;Command command;MockedStatic<net.Indyuce.mmocore.api.player.PlayerData> mmo;
    final Map<Player,Double> health=new HashMap<>();final Map<Player,Set<PotionEffect>> effects=new HashMap<>();

    @BeforeEach void setupDuels() throws Exception {
        Duels.clear();handler=new DuelCommand();command=mock(Command.class);when(command.getName()).thenReturn("duel");mmo=boundary(net.Indyuce.mmocore.api.player.PlayerData.class);
        Files.writeString(folder.resolve("duel.yml"),"announce-radius: 24\n");new DuelLoader().load(folder.resolve("duel.yml").toFile());
        for(Player p:List.of(victim,killer,other)){fighter(p);}
        bukkit.when(()->org.bukkit.Bukkit.getPlayerExact(anyString())).thenAnswer(c->online.values().stream().filter(p->p.getName().equalsIgnoreCase(c.getArgument(0))).findFirst().orElse(null));
        Duels.start(p->false);
    }
    @AfterEach void cleanupDuels(){Duels.shutdown();Duels.clear();}

    /** A player with real health, game mode, food and effects so restoring can be checked. */
    void fighter(Player p){
        health.put(p,20.0);effects.put(p,new HashSet<>());
        if(!loaded.containsKey(p)){var pd=mock(PlayerData.class);when(pd.hasActiveCharacter()).thenReturn(true);loaded.put(p,pd);}
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(p.getHealth()).thenAnswer(c->health.get(p));doAnswer(c->{health.put(p,c.getArgument(0));return null;}).when(p).setHealth(anyDouble());
        var attribute=mock(AttributeInstance.class);when(attribute.getValue()).thenReturn(20.0);when(p.getAttribute(Attribute.MAX_HEALTH)).thenReturn(attribute);
        when(p.getActivePotionEffects()).thenAnswer(c->new ArrayList<>(effects.get(p)));
        when(p.hasPotionEffect(any())).thenAnswer(c->effects.get(p).stream().anyMatch(e->e.getType()==c.getArgument(0)));
        doAnswer(c->effects.get(p).removeIf(e->e.getType()==c.getArgument(0))).when(p).removePotionEffect(any());
        doAnswer(c->{Collection<PotionEffect> added=c.getArgument(0);effects.get(p).addAll(added);return true;}).when(p).addPotionEffects(anyCollection());
    }
    void command(Player p,String...args){assertTrue(handler.onCommand(p,command,"duel",args));}
    void lookAt(Player from,Entity at){when(from.getTargetEntity(anyInt())).thenReturn(at);}
    /** Victim challenges the killer, who accepts, and the countdown runs out. */
    void duel(){command(victim,"Killer");command(killer,"accept");while(!delayed.isEmpty())runNext();}
    EntityDamageByEntityEvent hit(Player target,Entity from,double damage){var e=mock(EntityDamageByEntityEvent.class);when(e.getEntity()).thenReturn(target);when(e.getDamager()).thenReturn(from);when(e.getFinalDamage()).thenReturn(damage);when(e.getCause()).thenReturn(DamageCause.ENTITY_ATTACK);return e;}
    EntityDamageEvent hurt(Player target,DamageCause cause,double damage){var e=mock(EntityDamageEvent.class);when(e.getEntity()).thenReturn(target);when(e.getFinalDamage()).thenReturn(damage);when(e.getCause()).thenReturn(cause);return e;}
    /** The hit as the server would run it: screened, then (if still on) recorded and applied. */
    void land(EntityDamageEvent e){handler.onDamage(e);if(e.isCancelled())return;Player p=(Player)e.getEntity();if(health.get(p)-e.getFinalDamage()<=0&&Duels.settleLethalBlow(e,p))return;handler.onDamageTaken(e);health.put(p,Math.max(0,health.get(p)-e.getFinalDamage()));}
    void cancelTracking(EntityDamageEvent e){doAnswer(c->{when(e.isCancelled()).thenReturn(c.getArgument(0));return null;}).when(e).setCancelled(anyBoolean());}

    @Test void challengeAcceptCountdownAndFightStart() {
        command(victim,"Killer");assertTrue(text(victim).contains("You challenged Killer"));var prompt=PlainTextComponentSerializer.plainText().serialize(components.get(killer).getFirst());assertTrue(prompt.contains("Victim challenges you")&&prompt.contains("[Accept]")&&prompt.contains("[Decline]"));
        assertEquals(Set.of("/duel accept "+victim.getUniqueId(),"/duel decline "+victim.getUniqueId()),PvpStrikeServiceTest.commands(components.get(killer).getFirst()));
        command(killer,"accept",victim.getUniqueId().toString());assertTrue(Duels.isDuelling(victim.getUniqueId()));assertEquals(List.of(0L,20L,40L,60L),delayed.stream().map(PendingTask::delay).toList());
        var early=hit(killer,victim,4);cancelTracking(early);land(early);assertTrue(early.isCancelled(),"No hits count during the countdown");
        while(!delayed.isEmpty())runNext();verify(victim).sendTitle(contains("3"),eq(" "),eq(0),eq(25),eq(5));verify(killer).sendTitle(contains("DUEL"),eq(" "),eq(0),eq(25),eq(5));assertTrue(text(victim).contains("Your duel with Killer has begun"));assertTrue(text(other).isEmpty(),"Other is 100 blocks away, past the announce radius");
        var nearby=player("Nearby",5);fighter(nearby);Duels.endFor(List.of(victim.getUniqueId()),Duels.Ending.FIGHT);command(victim,"Killer");command(killer,"accept");while(!delayed.isEmpty())runNext();assertTrue(text(nearby).contains("Victim and Killer begin a duel"));
    }

    @Test void aLethalBlowEndsTheDuelAndMendsBothWithoutKillingAnyone() {
        duel();land(hit(killer,victim,6));land(hit(victim,killer,15));assertEquals(14.0,health.get(killer));assertEquals(5.0,health.get(victim));
        var lethal=hit(victim,killer,9);cancelTracking(lethal);var knockouts=new PvpKnockoutManager();knockouts.onLethalDamage(lethal);assertTrue(lethal.isCancelled());assertFalse(knockouts.isDown(victim));
        assertFalse(Duels.isDuelling(victim.getUniqueId()));assertEquals(20.0,health.get(victim));assertEquals(20.0,health.get(killer));assertTrue(text(killer).contains("You won the duel against Victim"));assertTrue(text(victim).contains("Killer won the duel"));
        assertFalse(PvpStrikeService.hasPendingDecision(victim),"A duel loss never offers a strike");
    }

    @Test void onlyTheOpponentsDamageIsGivenBackSoADuelCannotHeal() {
        health.put(victim,12.0);duel();land(hit(victim,killer,5));land(hurt(victim,DamageCause.FALL,3));land(hurt(victim,DamageCause.POISON,1));assertEquals(3.0,health.get(victim));
        command(victim,"yield");assertEquals(9.0,health.get(victim),"Fall damage stays; the opponent's hit and their poison come back");assertTrue(text(victim).contains("You yielded the duel to Killer"));assertTrue(text(killer).contains("Victim yielded. You won the duel."));
        land(hurt(victim,DamageCause.POISON,1));assertEquals(8.0,health.get(victim));command(victim,"yield");assertTrue(text(victim).contains("You aren't in a duel"));
    }

    @Test void anOutsiderInterruptsAndDuellistsCannotHurtBystanders() {
        var zombie=mock(Zombie.class);when(zombie.getUniqueId()).thenReturn(UUID.randomUUID());duel();
        var stray=hit(other,killer,4);cancelTracking(stray);land(stray);assertTrue(stray.isCancelled(),"Duellists can't hurt bystanders");
        land(hit(killer,victim,6));land(hit(killer,zombie,2));assertFalse(Duels.isDuelling(killer.getUniqueId()));assertEquals(18.0,health.get(killer),"The duel's 6 come back, the zombie's 2 stay");
        assertTrue(text(victim).contains("interrupted: Killer got into another fight"));var after=hit(other,killer,4);cancelTracking(after);land(after);assertFalse(after.isCancelled());
        duel();var swing=mock(EntityDamageByEntityEvent.class);when(swing.getEntity()).thenReturn(zombie);when(swing.getDamager()).thenReturn(victim);handler.onDamage(swing);assertFalse(Duels.isDuelling(victim.getUniqueId()),"Fighting a mob interrupts, so its mana isn't handed back");
        duel();var stand=mock(EntityDamageByEntityEvent.class);when(stand.getEntity()).thenReturn(mock(org.bukkit.entity.Item.class));when(stand.getDamager()).thenReturn(victim);handler.onDamage(stand);assertTrue(Duels.isDuelling(victim.getUniqueId()),"Breaking a dropped item is no fight");when(stand.getEntity()).thenReturn(mock(ArmorStand.class));handler.onDamage(stand);assertTrue(Duels.isDuelling(victim.getUniqueId()),"Nor is knocking furniture");
    }

    @Test void damageSourcesAndProjectilesResolveToTheirShooter() {
        duel();var arrow=mock(Arrow.class);when(arrow.getShooter()).thenReturn(killer);land(hit(victim,arrow,4));assertEquals(16.0,health.get(victim));
        var dispenser=mock(Arrow.class);when(dispenser.getShooter()).thenReturn(mock(org.bukkit.projectiles.BlockProjectileSource.class));assertNull(Duels.causingEntity(hit(victim,dispenser,1)));
        var spell=hurt(victim,DamageCause.ENTITY_ATTACK,3);var source=mock(DamageSource.class);when(source.getCausingEntity()).thenReturn(killer);when(spell.getDamageSource()).thenReturn(source);land(spell);assertEquals(13.0,health.get(victim));
        var unattributed=hurt(victim,DamageCause.LAVA,2);when(unattributed.getDamageSource()).thenReturn(mock(DamageSource.class));land(unattributed);assertEquals(11.0,health.get(victim));
        var self=hit(victim,victim,1);land(self);assertTrue(Duels.isDuelling(victim.getUniqueId()),"Hurting yourself doesn't interrupt");
        handler.onDamage(hurt(victim,DamageCause.FALL,1));handler.onDamageTaken(hurt(victim,DamageCause.FALL,1));var notPlayer=mock(EntityDamageEvent.class);when(notPlayer.getEntity()).thenReturn(mock(Zombie.class));handler.onDamage(notPlayer);handler.onDamageTaken(notPlayer);assertFalse(Duels.settleLethalBlow(hurt(other,DamageCause.FALL,30),other));
        Duels.shutdown();assertEquals(17.0,health.get(victim),"Shutdown gives back the 7 the opponent took; lava and the self-hit stay");assertTrue(text(victim).contains("the server is restarting"));
    }

    static PotionEffectType type(org.bukkit.potion.PotionEffectTypeCategory category){var t=mock(PotionEffectType.class);when(t.getCategory()).thenReturn(category);return t;}

    @Test void hungerManaAndEffectsComeBackButOldDebuffsStay() {
        var good=org.bukkit.potion.PotionEffectTypeCategory.BENEFICIAL;var bad=org.bukkit.potion.PotionEffectTypeCategory.HARMFUL;
        var SPEED=type(good);var HASTE=type(good);var STRENGTH=type(good);var SLOWNESS=type(bad);var POISON=type(bad);var WEAKNESS=type(bad);
        var speed=new PotionEffect(SPEED,2_000,1);var slow=new PotionEffect(SLOWNESS,PotionEffect.INFINITE_DURATION,0);var oldPoison=new PotionEffect(POISON,40,0);var gone=new PotionEffect(HASTE,0,0);effects.get(victim).addAll(List.of(speed,slow,oldPoison,gone));
        when(victim.getFoodLevel()).thenReturn(18);when(victim.getSaturation()).thenReturn(5f);when(victim.getExhaustion()).thenReturn(1f);when(victim.getFireTicks()).thenReturn(0);
        var resources=mock(net.Indyuce.mmocore.api.player.PlayerData.class);when(resources.getMana()).thenReturn(40.0);when(resources.getStamina()).thenReturn(10.0);mmo.when(()->net.Indyuce.mmocore.api.player.PlayerData.has(victim.getUniqueId())).thenReturn(true);mmo.when(()->net.Indyuce.mmocore.api.player.PlayerData.get(victim.getUniqueId())).thenReturn(resources);
        duel();
        effects.get(victim).removeIf(e->e.getType()==SPEED||e.getType()==HASTE);effects.get(victim).add(new PotionEffect(WEAKNESS,200,1));effects.get(victim).add(new PotionEffect(STRENGTH,200,0));
        when(victim.getFoodLevel()).thenReturn(12);when(victim.getSaturation()).thenReturn(0f);when(victim.getExhaustion()).thenReturn(3f);when(victim.getFireTicks()).thenReturn(80);when(resources.getMana()).thenReturn(5.0);when(resources.getStamina()).thenReturn(2.0);
        command(killer,"yield");
        verify(victim).setFoodLevel(18);verify(victim).setExhaustion(1f);verify(victim).setFireTicks(0);verify(resources).setMana(40.0);verify(resources).setStamina(10.0);
        var types=new HashMap<PotionEffectType,PotionEffect>();for(var e:effects.get(victim))types.put(e.getType(),e);
        assertFalse(types.containsKey(WEAKNESS),"Debuffs from the duel come off");assertTrue(types.containsKey(STRENGTH),"Buffs drunk during the duel stay");assertTrue(types.containsKey(SPEED),"A buff the duel took comes back");assertFalse(types.containsKey(HASTE),"A buff that would have run out stays gone");
        assertTrue(types.get(SLOWNESS).isInfinite(),"An old debuff stays");assertTrue(types.get(POISON).getDuration()>20&&types.get(POISON).getDuration()<=40,"Old poison keeps its time left");assertEquals(1,types.get(SPEED).getAmplifier());
        mmo.when(()->net.Indyuce.mmocore.api.player.PlayerData.has(killer.getUniqueId())).thenReturn(true);var full=mock(net.Indyuce.mmocore.api.player.PlayerData.class);when(full.getMana()).thenReturn(1.0);mmo.when(()->net.Indyuce.mmocore.api.player.PlayerData.get(killer.getUniqueId())).thenReturn(full);duel();when(full.getMana()).thenReturn(9.0);when(full.getStamina()).thenReturn(9.0);command(victim,"yield");verify(full,never()).setMana(anyDouble());verify(full,never()).setStamina(anyDouble());
        when(killer.getFoodLevel()).thenReturn(20);duel();mmo.when(()->net.Indyuce.mmocore.api.player.PlayerData.has(killer.getUniqueId())).thenReturn(false);command(victim,"yield");verify(killer,never()).setFoodLevel(anyInt());
    }

    @Test void challengesExpireCanBeDeclinedWithdrawnReplacedAndCrossAccepted() {
        command(victim,"Killer");command(killer,"decline");assertTrue(text(victim).contains("Killer declined your duel"));assertTrue(text(killer).contains("You declined the duel with Victim"));command(killer,"decline");assertTrue(text(killer).contains("no duel challenge to answer"));
        command(victim,"Killer");command(victim,"cancel");assertTrue(text(killer).contains("Victim withdrew"));assertTrue(text(victim).contains("You withdrew your duel challenge to Killer"));command(victim,"cancel");assertTrue(text(victim).contains("no open duel challenge"));
        var near=player("Near",3);fighter(near);command(victim,"Killer");command(victim,"Near");assertTrue(text(killer).contains("Victim withdrew their duel challenge"));command(killer,"accept","Victim");assertTrue(text(killer).contains("no duel challenge"));
        command(near,"Victim");assertTrue(Duels.isDuelling(near.getUniqueId()),"Challenging someone who challenged you accepts it");Duels.endFor(List.of(near.getUniqueId()),Duels.Ending.FIGHT);
        command(victim,"Killer");command(victim,"Killer");command(killer,"accept","victim");assertTrue(Duels.isDuelling(killer.getUniqueId()),"Names match without case");Duels.endFor(List.of(killer.getUniqueId()),Duels.Ending.FIGHT);
        command(victim,"Killer");online.remove(victim.getUniqueId());command(killer,"accept");assertTrue(text(killer).contains("no duel challenge"));command(killer,"decline");online.put(victim.getUniqueId(),victim);
        command(victim,"Killer");command(killer,"accept","Nobody");assertTrue(text(killer).contains("no duel challenge"));
        command(near,"Killer");command(victim,"Killer");command(killer,"accept");assertTrue(Duels.isDuelling(victim.getUniqueId()),"Without a name the newest challenge is accepted");Duels.endFor(List.of(victim.getUniqueId()),Duels.Ending.FIGHT);
        command(victim,"Killer");online.remove(killer.getUniqueId());command(victim,"cancel");online.put(killer.getUniqueId(),killer);command(victim,"Killer");command(near,"Killer");timer.run();assertFalse(text(victim).contains("didn't answer"));command(near,"cancel");
        var shortLived=folder.resolve("duel.yml");try{Files.writeString(shortLived,"challenge-seconds: 0\n");}catch(Exception e){fail(e);}new DuelLoader().load(shortLived.toFile());assertEquals(1,DuelLoader.getChallengeSeconds());
        command(victim,"Killer");sleep(1_010);timer.run();assertTrue(text(victim).contains("Killer didn't answer your duel challenge"));assertTrue(text(killer).contains("The duel challenge from Victim has expired"));
        command(victim,"Killer");online.remove(killer.getUniqueId());sleep(1_010);timer.run();online.put(killer.getUniqueId(),killer);command(killer,"accept");assertTrue(text(killer).contains("no duel challenge"));
    }

    @Test void whoCanDuelAndFromHowFar() {
        lookAt(victim,null);command(victim);assertTrue(text(victim).contains("Look at the player"));lookAt(victim,mock(Zombie.class));command(victim);
        lookAt(victim,killer);command(victim);assertTrue(text(victim).contains("You challenged Killer"));command(victim,"victim");assertTrue(text(victim).contains("can't duel yourself"));
        command(victim,"Ghost");assertTrue(text(victim).contains("Nobody called Ghost is online"));
        command(victim,"Other");assertTrue(text(victim).contains("Other is too far away. Stand within 16 blocks"));
        var elsewhere=mock(World.class);when(other.getLocation()).thenReturn(new Location(elsewhere,0,64,0));command(victim,"Other");assertTrue(text(victim).contains("Other is too far away"));online.remove(other.getUniqueId());
        loaded.remove(victim);command(victim,"Killer");assertTrue(text(victim).contains("you're without an active character"));var empty=mock(PlayerData.class);loaded.put(victim,empty);command(victim,"Killer");loaded.put(victim,data);
        loaded.remove(killer);command(victim,"Killer");assertTrue(text(victim).contains("Killer can't duel right now: they're without an active character"));fighter(killer);
        when(victim.getGameMode()).thenReturn(GameMode.CREATIVE);command(victim,"Killer");assertTrue(text(victim).contains("not in survival"));when(victim.getGameMode()).thenReturn(GameMode.ADVENTURE);
        when(victim.isDead()).thenReturn(true);command(victim,"Killer");assertTrue(text(victim).contains("knocked out"));when(victim.isDead()).thenReturn(false);
        Duels.start(p->p==killer);command(victim,"Killer");assertTrue(text(victim).contains("they're knocked out"));Duels.start(p->false);
        var fight=new PvpSituation(other.getUniqueId(),List.of(victim.getUniqueId()));PvpSituations.track(fight);command(victim,"Killer");assertTrue(text(victim).contains("in a PvP fight"));fight.markDeath(victim.getUniqueId());command(victim,"Killer");assertTrue(text(victim).contains("You challenged Killer"),"Dying in a fight frees you to duel");fight.close();PvpSituations.untrack(fight);
        PvpStartSessions.begin(List.of(victim.getUniqueId()),System.currentTimeMillis(),60_000);command(victim,"Killer");PvpStartSessions.end(victim.getUniqueId());
        battles.when(()->PermadeathBattleExemption.isInStartedBattle(victim)).thenReturn(true);command(victim,"Killer");assertTrue(text(victim).contains("in a battle"));battles.when(()->PermadeathBattleExemption.isInStartedBattle(victim)).thenReturn(false);
        when(killer.getGameMode()).thenReturn(GameMode.SPECTATOR);command(killer,"accept");assertTrue(text(killer).contains("You can't duel right now: you're not in survival"));when(killer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        command(victim,"Killer");command(killer,"accept");Duels.endFor(List.of(killer.getUniqueId()),Duels.Ending.FIGHT);while(!delayed.isEmpty())runNext();assertFalse(text(victim).contains("has begun"),"A countdown that was called off never starts the fight");
        command(victim,"Killer");command(killer,"accept");online.remove(killer.getUniqueId());while(!delayed.isEmpty())runNext();online.put(killer.getUniqueId(),killer);assertFalse(text(victim).contains("has begun"));Duels.endFor(List.of(victim.getUniqueId()),Duels.Ending.FIGHT);
        duel();var third=player("Third",4);fighter(third);command(third,"Killer");assertTrue(text(third).contains("Killer can't duel right now: they're already duelling"));
    }

    @Test void theDuelEndsAsADrawWhenTheFightCannotGoOn() {
        duel();land(hit(victim,killer,4));when(killer.getLocation()).thenReturn(new Location(world,40,64,0));timer.run();assertTrue(text(victim).contains("Your duel with Killer is over: you moved too far apart"));assertEquals(20.0,health.get(victim));
        when(killer.getLocation()).thenReturn(new Location(world,2,64,0));duel();when(killer.getGameMode()).thenReturn(GameMode.CREATIVE);timer.run();assertTrue(text(victim).contains("Killer is no longer in survival"));when(killer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        duel();battles.when(()->PermadeathBattleExemption.isInStartedBattle(killer)).thenReturn(true);timer.run();assertTrue(text(victim).contains("a battle started"));battles.when(()->PermadeathBattleExemption.isInStartedBattle(killer)).thenReturn(false);
        duel();timer.run();assertTrue(Duels.isDuelling(victim.getUniqueId()));online.remove(killer.getUniqueId());timer.run();assertFalse(Duels.isDuelling(victim.getUniqueId()),"A vanished opponent ends it");online.put(killer.getUniqueId(),killer);
        duel();online.remove(victim.getUniqueId());timer.run();online.put(victim.getUniqueId(),victim);assertFalse(Duels.isDuelling(killer.getUniqueId()));
        try{Files.writeString(folder.resolve("duel.yml"),"max-minutes: 0\ncountdown-seconds: 0\nannounce-radius: 0\n");}catch(Exception e){fail(e);}new DuelLoader().load(folder.resolve("duel.yml").toFile());
        command(victim,"Killer");command(killer,"accept");assertTrue(delayed.isEmpty(),"No countdown");land(hit(victim,killer,1));assertEquals(19.0,health.get(victim));timer.run();assertTrue(Duels.isDuelling(victim.getUniqueId()));Duels.shutdown();
        Duels.start(p->false);try{Files.writeString(folder.resolve("duel.yml"),"max-minutes: 1\ncountdown-seconds: 0\n");}catch(Exception e){fail(e);}new DuelLoader().load(folder.resolve("duel.yml").toFile());assertEquals(60_000L,DuelLoader.getMaxMs());
        command(victim,"Killer");command(killer,"accept");var running=new ArrayList<Duel>();try{var f=Duels.class.getDeclaredField("duelsByPlayer");f.setAccessible(true);running.add(((Map<UUID,Duel>)f.get(null)).get(victim.getUniqueId()));}catch(ReflectiveOperationException e){fail(e);}running.getFirst().startFight(System.currentTimeMillis()-61_000);timer.run();assertTrue(text(victim).contains("time ran out"));
    }

    @Test void quittingDyingAndRealFightsEndDuels() {
        duel();land(hit(killer,victim,5));var quit=mock(PlayerQuitEvent.class);when(quit.getPlayer()).thenReturn(killer);handler.onQuit(quit);assertEquals(20.0,health.get(killer),"The quitter is mended before they are saved");assertTrue(text(victim).contains("Killer left"));assertFalse(text(killer).contains("Killer left"));
        command(victim,"Killer");handler.onQuit(quit);command(killer,"accept");assertTrue(text(killer).contains("no duel challenge"),"Leaving drops challenges both ways");
        duel();land(hit(killer,victim,5));health.put(killer,0.0);var death=mock(PlayerDeathEvent.class);when(death.getEntity()).thenReturn(killer);handler.onDeath(death);assertEquals(0.0,health.get(killer));assertTrue(text(victim).contains("Killer died"));
        handler.onDeath(death);handler.onQuit(quit);
        duel();var pvp=new PvpCommand();var pvpCommand=mock(Command.class);when(pvpCommand.getName()).thenReturn("pvp");pvp.onCommand(victim,pvpCommand,"pvp",new String[]{"start"});assertTrue(text(killer).contains("a real fight was called"));pvp.shutdown();
        duel();online.remove(killer.getUniqueId());Duels.settleLethalBlow(hit(victim,killer,50),victim);online.put(killer.getUniqueId(),killer);assertFalse(Duels.isDuelling(victim.getUniqueId()),"The loser's side still ends when the winner is gone");
        duel();when(killer.isDead()).thenReturn(true);Duels.shutdown();assertTrue(text(killer).contains("the server is restarting"));when(killer.isDead()).thenReturn(false);
        Duels.start(p->false);Duels.start(p->false);Duels.shutdown();Duels.shutdown();
    }

    @Test void commandRoutingAndCompletion() {
        when(command.getName()).thenReturn("other");assertFalse(handler.onCommand(victim,command,"duel",new String[0]));when(command.getName()).thenReturn("DUEL");
        var console=mock(CommandSender.class);assertTrue(handler.onCommand(console,command,"duel",new String[0]));verify(console).sendMessage(contains("Players only"));
        command(victim,"help");assertTrue(text(victim).contains("Usage: /duel"));
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals(List.of("accept","decline","cancel","yield","help"),handler.onTabComplete(victim,command,"duel",new String[]{""}));assertEquals(List.of("yield"),handler.onTabComplete(victim,command,"duel",new String[]{"Y"}));assertTrue(handler.onTabComplete(victim,command,"duel",new String[]{"Kil"}).isEmpty(),"Never completes player names");assertTrue(handler.onTabComplete(victim,command,"duel",new String[]{"accept",""}).isEmpty());
        assertEquals("unknown-key",DuelLoader.message("unknown-key"));new DuelLoader().load(folder.resolve("missing.yml").toFile());assertEquals(16,DuelLoader.getChallengeRange());assertEquals(32,DuelLoader.getLeashRange());assertEquals(3,DuelLoader.getCountdownSeconds());assertEquals(30_000L,DuelLoader.getChallengeMs());
    }

    static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
