package net.tfminecraft.rpcharacters.progress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.Indyuce.mmocore.MMOCore;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.*;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.professions.*;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;

class CharacterProgressServiceTest extends ProgressFixture {
    @Test void preflightRejectsRemovedStoredClassWithoutTouchingProgressOrMmo() throws Exception {
        var character=character(); enable(); service.prepare(account);
        when(classes.get("warrior")).thenReturn(null);
        assertPreflightUnchanged(character,()->assertThrows(IOException.class,()->service.preflight(account,character)));
        assertTrue(store.readBaseline(account.getUniqueId()).isPresent(),"Stored baselines remain readable without configured classes");
        assertTrue(store.read(account.getUniqueId(),character.getId()).isPresent());
    }

    @Test void preflightRejectsRemovedSavedSlotWithoutTouchingProgressOrMmo() throws Exception {
        var mage=mock(net.Indyuce.mmocore.api.player.profess.PlayerClass.class);
        when(classes.get("mage")).thenReturn(mage);
        when(mmo.getSavedClasses()).thenReturn(Set.of("mage"));
        var saved=new net.Indyuce.mmocore.api.player.profess.SavedClassInformation(
                MMOCore.plugin.playerDataManager.getDefaultData());
        when(mmo.getClassInfo("mage")).thenReturn(saved);
        var character=character(); enable(); service.prepare(account);
        assertPreflightUnchanged(character,()->assertDoesNotThrow(()->service.preflight(account,character)));
        when(classes.get("mage")).thenReturn(null);
        assertPreflightUnchanged(character,()->assertThrows(IOException.class,()->service.preflight(account,character)));
        assertTrue(store.readBaseline(account.getUniqueId()).isPresent());
    }

    @Test void preflightChecksConfiguredClassButAllowsNullLegacyClassInSelectionRoom() throws Exception {
        var character=character(); enable(); service.prepare(account);
        assertNull(account.getActiveCharacter());
        assertPreflightUnchanged(character,()->assertDoesNotThrow(()->service.preflight(account,character)));
        when(character.getMMOClass()).thenReturn("warrior");
        assertPreflightUnchanged(character,()->assertDoesNotThrow(()->service.preflight(account,character)));
        when(character.getMMOClass()).thenReturn("removed");
        assertPreflightUnchanged(character,()->assertThrows(IOException.class,()->service.preflight(account,character)));
        assertFalse(character.isActive()); assertNull(service.token(account));
    }

    @Test void preflightRequiresOwnedReadableProgressAndReadyOnlinePlayer() throws Exception {
        var character=character(); enable(); service.prepare(account);
        var original=character.getProgress();
        assertPreflightUnchanged(character,()-> {
            assertThrows(IOException.class,()->service.preflight(null,character));
            assertThrows(IOException.class,()->service.preflight(account,null));
            var stranger=mock(net.tfminecraft.rpcharacters.objects.RPCharacter.class);
            assertThrows(IOException.class,()->service.preflight(account,stranger));
            when(player.isOnline()).thenReturn(false);
            assertThrows(IOException.class,()->service.preflight(account,character));
            when(player.isOnline()).thenReturn(true);
            var offline=new PlayerData(account.getUniqueId()); offline.getCharacters().add(character);
            assertThrows(IOException.class,()->service.preflight(offline,character));
            ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(false);
            assertThrows(IOException.class,()->service.preflight(account,character));
            ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(true);
            character.setProgress(null);
            assertThrows(IOException.class,()->service.preflight(account,character));
            character.setProgress(progress(UUID.randomUUID().toString()));
            assertThrows(IOException.class,()->service.preflight(account,character));
            character.setProgress(new CharacterProgress(UUID.randomUUID(),character.getId(),CharacterProgress.FRESH,
                    MmoProgressCodec.fresh()));
            assertThrows(IOException.class,()->service.preflight(account,character));
            character.setProgress(original);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class,()->service.preflight(account,character));
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        });
    }

    @Test void preflightWrapsClassRegistryFailureWithoutWritingOrApplying() throws Exception {
        var character=character(); enable(); service.prepare(account);
        when(classes.get("warrior")).thenThrow(new IllegalStateException("reloading"));
        assertPreflightUnchanged(character,()-> {
            var failure=assertThrows(IOException.class,()->service.preflight(account,character));
            assertInstanceOf(IllegalStateException.class,failure.getCause());
        });
    }

    private void assertPreflightUnchanged(net.tfminecraft.rpcharacters.objects.RPCharacter character,
            org.junit.jupiter.api.function.Executable action) throws Exception {
        var before=new org.bukkit.configuration.file.YamlConfiguration();
        character.getProgress().writeTo(before);
        long revision=character.getProgress().getRevision();
        Map<Path,byte[]> files=new HashMap<>();
        try (var paths=Files.walk(root.resolve("progress"))) {
            for (Path file:paths.filter(Files::isRegularFile).toList()) files.put(file,Files.readAllBytes(file));
        }
        clearInvocations(mmo,items); mmoPlayers.clearInvocations();
        assertDoesNotThrow(action);
        verifyNoInteractions(mmo,items); mmoPlayers.verifyNoInteractions();
        var after=new org.bukkit.configuration.file.YamlConfiguration();
        character.getProgress().writeTo(after);
        assertEquals(before.saveToString(),after.saveToString());
        assertEquals(revision,character.getProgress().getRevision());
        try (var paths=Files.walk(root.resolve("progress"))) {
            assertEquals(files.keySet(),new HashSet<>(paths.filter(Files::isRegularFile).toList()));
        }
        for (var file:files.entrySet()) assertArrayEquals(file.getValue(),Files.readAllBytes(file.getKey()));
    }

    @Test void characterCreatedBeforeMmoReadyCannotPublishProgressWithoutItsBaseline() throws Exception {
        var original=character(); enable(); service.recordLegacyActive(account,null);
        ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(false);
        var added=character();
        assertThrows(IOException.class,()->service.ensureFresh(account,added));
        assertFalse(Files.exists(store.baselinePath(account.getUniqueId())));
        assertFalse(Files.exists(store.characterPath(account.getUniqueId(),added.getId()).getParent()));
        assertNull(added.getProgress());
        ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(true);
        service.prepare(account);
        assertEquals(CharacterProgress.BASELINE,original.getProgress().getSource());
        assertEquals(CharacterProgress.FRESH,added.getProgress().getSource());
        assertEquals(0,added.getProgress().getSkillPointsTotal());
    }
    @Test void firstBaselineUsesFrozenJoinRosterAndNewArrivalStaysFreshAcrossRestart() throws Exception {
        var original=character(); enable(); service.recordLegacyActive(account,null);
        ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(false);
        var added=character(); service.recordLegacyActive(account,null);
        ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(true);
        service.prepare(account);
        assertEquals(List.of(original.getId()),store.readBaseline(account.getUniqueId()).orElseThrow().characterIds());
        assertEquals(12,original.getProgress().getSkillPointsTotal());
        assertEquals(CharacterProgress.FRESH,added.getProgress().getSource());
        assertEquals(1,added.getProgress().getClassLevel());
        assertEquals(0,added.getProgress().getSkillPointsTotal());
        assertEquals(0,added.getProgress().getProfessionPoints("mining"));
        String baseline=Files.readString(store.baselinePath(account.getUniqueId()));
        service.close(); original.setProgress(null); added.setProgress(null);
        service=new CharacterProgressService(root.resolve("progress"));
        account.setAccountSkillPointsTotal(99); when(mmo.getLevel()).thenReturn(99);
        service.prepare(account);
        assertEquals(12,original.getProgress().getSkillPointsTotal());
        assertEquals(1,added.getProgress().getClassLevel());
        assertEquals(0,added.getProgress().getSkillPointsTotal());
        assertEquals(baseline,Files.readString(store.baselinePath(account.getUniqueId())));
    }
    @Test void emptyJoinRosterCannotCloneLegacyProgressIntoAnArrivalAndMissingSnapshotFailsClosed() throws Exception {
        enable(); service.recordLegacyActive(account,null); var added=character(); service.prepare(account);
        assertTrue(store.readBaseline(account.getUniqueId()).orElseThrow().characterIds().isEmpty());
        assertEquals(CharacterProgress.FRESH,added.getProgress().getSource());
        service.close();service=new CharacterProgressService(root.resolve("unrecorded"));added.setProgress(null);
        assertThrows(IOException.class,()->service.prepare(account));
        assertFalse(Files.exists(root.resolve("unrecorded").resolve(account.getUniqueId().toString())));
    }
    @Test void bodyAdmittedFreshAccountStartsItsExistingWebRosterFreshWithoutChangingLegacyPools() throws Exception {
        var fromWeb=character();enable();service.recordFreshAccount(account);service.prepare(account);
        assertTrue(store.readBaseline(account.getUniqueId()).orElseThrow().characterIds().isEmpty());
        assertEquals(CharacterProgress.FRESH,fromWeb.getProgress().getSource());
        assertEquals(1,fromWeb.getProgress().getClassLevel());assertEquals(0,fromWeb.getProgress().getSkillPointsTotal());
        assertEquals(0,fromWeb.getProgress().getAttributePointsTotal());assertEquals(0,fromWeb.getProgress().getProfessionPoints("mining"));
        assertEquals(12,account.getAccountSkillPointsTotal());assertEquals(7,account.getAccountAttributePointsTotal());assertEquals(8,account.getAccountProfessionPoints("mining"));
    }
    @Test void freshAdmissionCannotReplaceAnAlreadyPersistedLegacyBaseline() throws Exception {
        var original=character();enable();service.prepare(account);
        String baseline=Files.readString(store.baselinePath(account.getUniqueId()));
        original.setProgress(null);service.recordFreshAccount(account);service.prepare(account);
        assertEquals(CharacterProgress.BASELINE,original.getProgress().getSource());
        assertEquals(12,original.getProgress().getSkillPointsTotal());
        assertEquals(baseline,Files.readString(store.baselinePath(account.getUniqueId())));
    }
    @Test void resourceRestoreUsesPreActivationValuesAfterProgressCheckpointsAndRejectsNonLiveCharacters() throws Exception {
        var a=character(); var b=character(); enable();
        assertThrows(IOException.class,()->service.resources(account,a));
        when(mmo.getMana()).thenReturn(150d); when(mmo.getStamina()).thenReturn(140d); when(mmo.getStellium()).thenReturn(130d);
        service.prepare(account); var resources=service.resources(account,a);
        a.setActiveState(true); assertThrows(IOException.class,()->service.restoreResources(account,a,resources));
        play(a); when(mmo.getMana()).thenReturn(20d); when(mmo.getStamina()).thenReturn(20d); when(mmo.getStellium()).thenReturn(20d); service.save(account,a);
        clearInvocations(mmo); service.restoreResources(account,a,resources);
        verify(mmo).setMana(150d,io.lumine.mythic.lib.player.resource.ResourceUpdateReason.CHOOSE_CLASS);
        verify(mmo).setStamina(140d,io.lumine.mythic.lib.player.resource.ResourceUpdateReason.CHOOSE_CLASS);
        verify(mmo).setStellium(130d,io.lumine.mythic.lib.player.resource.ResourceUpdateReason.CHOOSE_CLASS);
        play(b); assertThrows(IOException.class,()->service.restoreResources(account,a,resources));
    }
    @Test void cutoverCopiesOnceLaterCharactersStartFreshAndRestartsNeverReadAnotherCharactersMmo() throws Exception {
        var a = character(); var b = character(); enable(); service.prepare(account);
        assertEquals(12,a.getProgress().getSkillPointsTotal()); assertEquals(8,b.getProgress().getClassLevel());
        assertEquals("baseline", b.getProgress().getSource()); assertNotSame(a.getProgress(),b.getProgress());
        play(a); a.getProgress().addSkillPoints(4); service.save(account,a); String baseline = Files.readString(store.baselinePath(account.getUniqueId()));
        var c = character(); service.ensureFresh(account,c); assertEquals("fresh",c.getProgress().getSource()); assertEquals(0,c.getProgress().getSkillPointsTotal()); assertEquals(1,c.getProgress().getClassLevel());
        service.ensureFresh(account,c); service.prepare(account); assertEquals(16,a.getProgress().getSkillPointsTotal());
        a.setProgress(null); b.setProgress(null); c.setProgress(null); when(mmo.getLevel()).thenReturn(99); account.setAccountSkillPointsTotal(50);
        service.prepare(account); assertEquals(16,a.getProgress().getSkillPointsTotal()); assertEquals(12,b.getProgress().getSkillPointsTotal()); assertEquals(1,c.getProgress().getClassLevel());
        assertEquals(baseline,Files.readString(store.baselinePath(account.getUniqueId())));
        b.setProgress(null); service.ensureFresh(account,b); assertEquals(12,b.getProgress().getSkillPointsTotal());
    }
    @Test void initialBootstrapCanResumeRemainingCharactersFromTheFrozenBaseline() throws Exception {
        var a = character(); var b = character(); var template = progress(a.getId()); template.setSkillPointsTotal(23);
        store.createBaseline(new ProgressBaseline(account.getUniqueId(), List.of(a.getId(), b.getId()), template)); enable();
        service.ensureFresh(account,a); assertEquals(23,a.getProgress().getSkillPointsTotal());
        service.prepare(account); assertEquals(23,b.getProgress().getSkillPointsTotal());
    }
    @Test void routingAndDelayedWorkRequireTheExactActivationAndNeverTouchLegacyPools() throws Exception {
        var a = character(); var b = character(); assertFalse(ProgressRouting.enabled());
        assertNull(ProgressRouting.activeProgress(null)); assertNull(ProgressRouting.activeProgress(player)); assertNull(ProgressRouting.pools(null));
        var legacy = ProgressRouting.pools(account); assertEquals("account",legacy.holder()); legacy.setSkillPointsTotal(12); legacy.setRewardSkillPoints(3); legacy.setAttributePointsTotal(7); legacy.addProfessionPoints("mining",2);
        assertEquals(10,legacy.getProfessionPoints("mining")); assertEquals(3,legacy.getRewardSkillPoints());
        var online = ProgressRouting.sessionGuard(player); assertTrue(online.getAsBoolean()); when(player.isOnline()).thenReturn(false); assertFalse(online.getAsBoolean()); when(player.isOnline()).thenReturn(true);
        enable(); service.prepare(account); assertNull(ProgressRouting.pools(account)); assertNull(ProgressRouting.pools(null,a)); assertFalse(ProgressRouting.sessionGuard(player).getAsBoolean());
        play(a); var guard = ProgressRouting.sessionGuard(player); assertTrue(guard.getAsBoolean()); assertSame(a.getProgress(),ProgressRouting.pools(account,a)); assertNull(ProgressRouting.pools(account,b));
        a.setActiveState(false); assertNull(ProgressRouting.pools(account)); assertFalse(guard.getAsBoolean());
        play(a); assertFalse(guard.getAsBoolean(),"Reactivating the same character invalidates old callbacks");
        assertTrue(ClassService.tryGrantSkillPoints(player,2)); assertTrue(AttributePointService.tryGrantAttributePoints(player,2)); assertTrue(ProfessionPointService.tryGrantPoints(player,"mining",2));
        assertEquals(12,account.getAccountSkillPointsTotal()); assertEquals(7,account.getAccountAttributePointsTotal()); assertEquals(10,account.getAccountProfessionPoints("mining"));
        assertEquals(14,a.getProgress().getSkillPointsTotal()); assertEquals(9,AttributePointService.lifetimePoints(player)); assertEquals(9,AttributePointService.freePoints(player));
        play(b); assertEquals(12,b.getProgress().getSkillPointsTotal()); var bGuard=ProgressRouting.sessionGuard(player);
        var replacement = new CharacterProgressService(root.resolve("replacement")); service.close(); assertSame(replacement,CharacterProgressService.active()); assertFalse(bGuard.getAsBoolean()); replacement.close();
    }
    @Test void unreadableProgressIsPreservedAndBlockedRatherThanReplaced() throws Exception {
        var a=character(); enable(); service.prepare(account); Path file=store.characterPath(account.getUniqueId(),a.getId()); Files.writeString(file,"broken: ["); a.setProgress(null);
        service.prepare(account); assertNull(a.getProgress()); a.setActiveState(true); assertThrows(IOException.class,()->service.activate(account,a)); assertThrows(IOException.class,()->service.ensureFresh(account,a));
        assertEquals("broken: [",Files.readString(file)); assertThrows(IOException.class,()->service.save(account,a));
        Files.delete(file); assertThrows(IOException.class,()->service.ensureFresh(account,a));
    }
    @Test void liveOwnershipReadinessAndThreadChecksPrecedeAnyMmoMutation() throws Exception {
        var a=character(); enable(); bukkit.when(Bukkit::isPrimaryThread).thenReturn(false); assertThrows(IllegalStateException.class,()->service.prepare(account)); bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        when(player.isOnline()).thenReturn(false); assertThrows(IOException.class,()->service.prepare(account)); when(player.isOnline()).thenReturn(true);
        ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(false); assertThrows(IOException.class,()->service.prepare(account)); ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(true);
        service.prepare(account); assertThrows(IOException.class,()->service.activate(account,a));
        a.setActiveState(true); ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(false); assertThrows(IOException.class,()->service.activate(account,a)); ready.when(()->MmoCorePlayerReady.isReady(player)).thenReturn(true);
        a.setProgress(progress(UUID.randomUUID().toString())); assertThrows(IOException.class,()->service.activate(account,a)); a.setProgress(null); service.ensureFresh(account,a);
        var outsider=mock(net.tfminecraft.rpcharacters.objects.RPCharacter.class); assertThrows(IOException.class,()->service.ensureFresh(account,outsider)); assertThrows(IOException.class,()->service.activate(account,outsider));
        clearInvocations(mmo); when(player.isOnline()).thenReturn(false); assertThrows(IOException.class,()->service.activate(account,a)); verifyNoInteractions(mmo); when(player.isOnline()).thenReturn(true);
        doThrow(new IllegalStateException("apply failed")).when(mmo).resetSkills(); assertThrows(IOException.class,()->service.activate(account,a)); assertFalse(service.holds(account,a));
        doNothing().when(mmo).resetSkills(); service.activate(account,a); assertTrue(service.holds(account,a));
        a.setActiveState(false); assertThrows(IOException.class,()->service.save(account,a));
    }
    @Test void checkpointsCoalesceXpAndRejectStaleCallbacksOrApplyingState() throws Exception {
        CharacterProgressService.checkpoint(player); CharacterProgressService.scheduleCheckpoint(player);
        var a=character(); enable(); service.prepare(account); CharacterProgressService.checkpoint(null); CharacterProgressService.checkpoint(player); CharacterProgressService.scheduleCheckpoint(null); CharacterProgressService.scheduleCheckpoint(player);
        play(a); long revision=a.getProgress().getRevision(); ClassService.runApplying(player,()->{assertTrue(ClassService.isApplying(player.getUniqueId()));ClassService.runApplying(player,()->CharacterProgressService.checkpoint(player));assertTrue(ClassService.isApplying(player.getUniqueId()));}); assertFalse(ClassService.isApplying(player.getUniqueId()));
        assertEquals(revision,a.getProgress().getRevision());
        CharacterProgressService.scheduleCheckpoint(player); CharacterProgressService.scheduleCheckpoint(player); assertEquals(1,later.size()); later.removeFirst().run(); assertEquals(revision+1,a.getProgress().getRevision());
        CharacterProgressService.scheduleCheckpoint(player); play(a); later.removeFirst().run(); assertEquals(revision+1,a.getProgress().getRevision());
        Files.writeString(store.characterPath(account.getUniqueId(),a.getId()),"bad: ["); assertDoesNotThrow(()->CharacterProgressService.checkpoint(player));
        assertThrows(IllegalArgumentException.class,()->ClassService.runApplying(player,()->{throw new IllegalArgumentException("test");})); assertFalse(ClassService.isApplying(player.getUniqueId()));
    }
    @Test void baselineMigrationUsesLegacyWatermarksAndPreservesCappedLevelsWithoutWritingAccount() throws Exception {
        var a=character(); account=new PlayerData(player); account.getCharacters().add(a); account.setAccountClassProgress(30,6); when(mmo.hasReachedMaxLevel()).thenReturn(true); when(mmo.getSkillPoints()).thenReturn(7); when(mmo.countSkillPointsSpent()).thenReturn(4); when(mmo.getAttributePoints()).thenReturn(3);
        enable(); service.recordLegacyActive(account,null); service.prepare(account); assertEquals(11,a.getProgress().getSkillPointsTotal()); assertEquals(3,a.getProgress().getAttributePointsTotal()); assertEquals(30,a.getProgress().getClassLevel()); assertEquals(4,a.getProgress().getProfessionPoints("mining")); assertTrue(account.needsSkillPointsMigration()); assertFalse(account.isProfessionPointsInitialized());
        ClassService.migrateSkillPointsIfNeeded(player); AttributePointService.migrateAttributePointsIfNeeded(player,account); ProfessionPointService.bootstrapLifetimeFromMmoCore(player); assertTrue(account.needsSkillPointsMigration());
        play(a); ClassService.trackFromPlayer(player); assertEquals(30,a.getProgress().getClassLevel()); when(mmo.hasReachedMaxLevel()).thenReturn(false); ClassService.trackFromPlayer(player); assertEquals(8,a.getProgress().getClassLevel());
        a.getProgress().setProfessionPoints("mining",99); ProfessionPointService.resetLifetimePoints(player); assertEquals(4,a.getProgress().getProfessionPoints("mining")); assertEquals(0,account.getAccountProfessionPoints("mining"));
    }
    @Test void classChangesUseOnlyTheLiveCharactersLevelAndDeferredMmoUnloadFailsSafely() throws Exception {
        var a=character();enable();service.prepare(account);
        var mage=mock(net.Indyuce.mmocore.api.player.profess.PlayerClass.class);when(mage.getId()).thenReturn("mage");when(classes.get("mage")).thenReturn(mage);
        var slot=mock(net.Indyuce.mmocore.api.player.profess.SavedClassInformation.class);when(slot.mapAttributeLevels()).thenReturn(new HashMap<>());when(mmo.hasSavedClass(mage)).thenReturn(true);when(mmo.getClassInfo(mage)).thenReturn(slot);
        assertTrue(ClassService.applyClass(player,"mage"));play(a);a.getProgress().setClassProgress(20,42);assertTrue(ClassService.applyClass(player,"mage"));assertEquals(20,a.getProgress().getClassLevel());assertEquals(42,a.getProgress().getClassExperience());assertFalse(account.hasAccountClassLevel());
        mmoPlayers.when(()->net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenThrow(new IllegalStateException("unloaded"));assertThrows(IOException.class,()->service.save(account,a));
    }
    @Test void leaderboardUsesReadyCharacterNamesAndProfessionEventsCannotCreditTheRoom() throws Exception {
        var a=character();enable();service.prepare(account);bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        try(var texts=mockStatic(net.tfminecraft.rpcharacters.utils.RPTexts.class)) {
            ProfessionTopService.showTop(player,"mining");texts.verify(()->net.tfminecraft.rpcharacters.utils.RPTexts.send(eq(player),contains("characters")));texts.verify(()->net.tfminecraft.rpcharacters.utils.RPTexts.send(eq(player),contains("Aria")),never());
            var event=mock(net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getReason()).thenReturn(net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent.Reason.LEVEL_UP);when(event.getProfession()).thenReturn(profession);when(event.getOldLevel()).thenReturn(1);when(event.getNewLevel()).thenReturn(2);ProfessionPointService.onProfessionLevelUp(event);assertEquals(8,a.getProgress().getProfessionPoints("mining"));
            play(a);ProfessionTopService.showTop(player,"mining");texts.verify(()->net.tfminecraft.rpcharacters.utils.RPTexts.send(eq(player),contains("Aria")));
        }
        var missing=mock(ProfessionDefinition.class);when(missing.getId()).thenReturn("removed");ProfessionRegistry.setProfessions(List.of(missing));ProfessionPointService.resetLifetimePoints(player);assertTrue(a.getProgress().getProfessionPointsMap().isEmpty());
    }
    @Test void legacyAttributeBootstrapUsesOriginalCharacterRatherThanAnItemHeirAndRequiresItsIdentity() throws Exception {
        var original=character();var heir=character();account=new PlayerData(player);account.getCharacters().addAll(List.of(original,heir));service=new CharacterProgressService(root.resolve("progress"));
        assertThrows(IOException.class,()->service.prepare(account));assertFalse(Files.exists(store.baselinePath(account.getUniqueId())));
        var attribute=mock(net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.AttributeInstance.class);when(attribute.getId()).thenReturn("strength");when(attribute.getBase()).thenReturn(9);when(mmo.getAttributes().getInstances()).thenReturn(List.of(attribute));when(original.getCreationBaseAmount("strength")).thenReturn(4);when(heir.getCreationBaseAmount("strength")).thenReturn(8);when(mmo.getAttributePoints()).thenReturn(2);
        service.recordLegacyActive(account,original);service.prepare(account);assertEquals(7,original.getProgress().getAttributePointsTotal());assertEquals(7,heir.getProgress().getAttributePointsTotal());verify(original).setExtraAttributeAllocation(Map.of("strength",5));verify(heir,never()).setExtraAttributeAllocation(anyMap());assertTrue(account.needsAttributePointsMigration());
    }
    @Test void roomPointGrantsAndScheduledRewardsDoNothingAndLegacyRefundStillWorks() throws Exception {
        ProfessionPointService.resetLifetimePoints(null); ProfessionPointService.resetLifetimePoints(player); assertEquals(4,account.getAccountProfessionPoints("mining")); assertTrue(account.isProfessionPointsInitialized());
        var a=character(); enable(); service.prepare(account); ProfessionPointService.resetLifetimePoints(player); assertFalse(ClassService.tryGrantSkillPoints(player,1)); assertFalse(AttributePointService.tryGrantAttributePoints(player,1)); assertFalse(ProfessionPointService.tryGrantPoints(player,"mining",1));
        assertEquals(0,AttributePointService.lifetimePoints(null)); assertEquals(0,AttributePointService.freePoints(null)); assertEquals(0,AttributePointService.freePoints(player));
        ClassService.trackFromPlayer(player); ClassService.scheduleCreditLevelRewardSkillPoints(null); ClassService.scheduleCreditLevelRewardSkillPoints(player); later.removeFirst().run();
        play(a); ClassService.scheduleCreditLevelRewardSkillPoints(player); later.removeFirst().run();
        players.when(()->PlayerManager.get(player)).thenReturn(null); assertNull(ProgressRouting.activeProgress(player)); assertFalse(ProgressRouting.sessionGuard(player).getAsBoolean()); service.close(); ProfessionPointService.resetLifetimePoints(player);
    }
}
