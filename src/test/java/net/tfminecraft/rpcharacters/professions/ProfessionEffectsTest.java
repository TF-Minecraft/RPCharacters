package net.tfminecraft.rpcharacters.professions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import io.lumine.mythic.lib.MythicLib;
import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.*;
import net.Indyuce.mmoitems.api.crafting.condition.*;
import net.Indyuce.mmoitems.api.crafting.recipe.Recipe;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent.StationAction;
import net.Indyuce.mmoitems.api.item.build.ItemStackBuilder;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.stat.data.*;
import net.Indyuce.mmoitems.stat.data.type.StatData;
import net.Indyuce.mmoitems.stat.type.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.races.Race;

class ProfessionEffectsTest extends ProfessionRuntimeFixture {
    ProfessionEffectService effects;boolean typed=true,emptyEnchants=true,historyPresent=true;ItemStat damage,speed,critical;final Map<NBTItem,ItemStack> nbtStacks=new IdentityHashMap<>();MockedConstruction<LiveMMOItem> models;
    @BeforeEach void setupItems(){
        effects=new ProfessionEffectService();MMOItems.plugin=mock(MMOItems.class,RETURNS_DEEP_STUBS);MythicLib.plugin=mock(MythicLib.class,RETURNS_DEEP_STUBS);when(MMOItems.plugin.getName()).thenReturn("MMOItems");when(MMOItems.plugin.namespace()).thenReturn("mmoitems");when(MythicLib.plugin.getName()).thenReturn("MythicLib");when(MythicLib.plugin.namespace()).thenReturn("mythiclib");when(MythicLib.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());when(MMOItems.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        damage=stat("ATTACK_DAMAGE");speed=stat("ATTACK_SPEED");critical=stat("CRITICAL_STRIKE_CHANCE");when(MMOItems.plugin.getStats().get(anyString())).thenAnswer(call->switch((String)call.getArgument(0)){case "ATTACK_DAMAGE"->damage;case "ATTACK_SPEED"->speed;case "CRITICAL_STRIKE_CHANCE"->critical;default->null;});
        Cache.professionItemTypes=List.of(new ProfessionItemType("unrelated",List.of("SWORD")),new ProfessionItemType("weapons",List.of("BOW","SWORD")));
        var nbt=boundary(NBTItem.class);nbt.when(()->NBTItem.get(any(ItemStack.class))).thenAnswer(call->{ItemStack stack=call.getArgument(0);var wrapper=mock(NBTItem.class);when(wrapper.hasType()).thenReturn(typed&&!stack.getType().isAir());when(wrapper.getType()).thenReturn("SWORD");nbtStacks.put(wrapper,stack);return wrapper;});
        models=mockConstruction(LiveMMOItem.class,(model,context)->{
            ItemStack stack=nbtStacks.get(context.arguments().getFirst());Map<ItemStat,StatData> values=decode(stack);Map<ItemStat,StatHistory> histories=new HashMap<>();
            when(model.getData(any(ItemStat.class))).thenAnswer(call->values.get(call.getArgument(0)));
            doAnswer(call->{values.put(call.getArgument(0),call.getArgument(1));return null;}).when(model).setData(any(ItemStat.class),any(StatData.class));
            doAnswer(call->{values.put(call.getArgument(0),call.getArgument(1));return null;}).when(model).replaceData(any(ItemStat.class),any(StatData.class));
            when(model.getStatHistory(any())).thenAnswer(call->history(histories,values,call.getArgument(0)));
            when(model.computeStatHistory(any())).thenAnswer(call->historyPresent?history(histories,values,call.getArgument(0)):null);
            var builder=mock(ItemStackBuilder.class);when(model.newBuilder()).thenReturn(builder);when(builder.build()).thenAnswer(call->encode(values));
        });mocks.add(models);
    }
    ItemStat stat(String id){var stat=mock(ItemStat.class);when(stat.getId()).thenReturn(id);return stat;}
    StatHistory history(Map<ItemStat,StatHistory> histories,Map<ItemStat,StatData> values,ItemStat stat){return histories.computeIfAbsent(stat,key->{var history=mock(StatHistory.class);StatData original=values.get(key);when(history.getOriginalData()).thenReturn(original);return history;});}
    NamespacedKey key(String id){return new NamespacedKey("test",id.toLowerCase(Locale.ROOT));}
    Map<ItemStat,StatData> decode(ItemStack item){var values=new LinkedHashMap<ItemStat,StatData>();var meta=item.getItemMeta();for(var stat:List.of(damage,speed,critical)){Double value=meta.getPersistentDataContainer().get(key(stat.getId()),PersistentDataType.DOUBLE);if(value!=null)values.put(stat,new DoubleData(value));}var enchants=new EnchantListData();meta.getEnchants().forEach(enchants::addEnchant);if(emptyEnchants||!enchants.isEmpty())values.put(ItemStats.ENCHANTS,enchants);return values;}
    ItemStack encode(Map<ItemStat,StatData> values){var item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();for(var entry:values.entrySet()){if(entry.getValue() instanceof DoubleData number)meta.getPersistentDataContainer().set(key(entry.getKey().getId()),PersistentDataType.DOUBLE,number.getValue());if(entry.getValue() instanceof EnchantListData enchants)for(var enchant:enchants.getEnchants())meta.addEnchant(enchant,enchants.getLevel(enchant),true);}item.setItemMeta(meta);return item;}
    PlayerUseCraftingStationEvent event(int amount){var result=new ItemStack(Material.PAPER,amount);var event=mock(PlayerUseCraftingStationEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getInteraction()).thenReturn(StationAction.CRAFTING_QUEUE);when(event.getResult()).thenReturn(result);when(event.hasResult()).thenReturn(true);return event;}
    List<ItemStack> outputs(PlayerUseCraftingStationEvent event){var out=new ArrayList<ItemStack>();for(var item:player.getInventory().getContents())if(item!=null&&item.getType()==Material.PAPER)out.add(item);for(var entity:world.getEntities())if(entity instanceof Item item&&item.getItemStack().getType()==Material.PAPER)out.add(item.getItemStack());if(event.getResult().getType()==Material.PAPER)out.add(event.getResult());return out;}
    ItemStack onlyOutput(PlayerUseCraftingStationEvent event,int amount){var out=outputs(event);assertEquals(amount,out.stream().mapToInt(ItemStack::getAmount).sum(),"Crafting must preserve every output item without duplicate delivery");assertEquals(1,out.size());return out.getFirst();}
    double value(ItemStack item,ItemStat stat){Double result=item.getItemMeta().getPersistentDataContainer().get(key(stat.getId()),PersistentDataType.DOUBLE);return result==null?0:result;}
    void fullInventory(){for(int i=0;i<player.getInventory().getSize();i++)player.getInventory().setItem(i,new ItemStack(Material.STONE,64));}

    @Test void nonQueueActionsAndUnloadedOrInactiveAccountsDoNotChangeTheResult(){var event=event(2);when(event.getInteraction()).thenReturn(StationAction.INTERACT_WITH_RECIPE);effects.stationAddedStats(event);effects.stationEnchantTypeEvent(event);when(event.getInteraction()).thenReturn(StationAction.CRAFTING_QUEUE);loaded.clear();effects.stationAddedStats(event);effects.stationEnchantTypeEvent(event);loaded.put(player,new PlayerData(player));effects.stationAddedStats(event);effects.stationEnchantTypeEvent(event);assertEquals(2,event.getResult().getAmount());assertTrue(models.constructed().isEmpty());}
    @Test void unrelatedUpgradeTypesAndNonMmoItemsAreUnchanged(){upgrade("permission","craft.smith");var event=event(2);effects.stationAddedStats(event);effects.stationEnchantTypeEvent(event);upgrade("add_stats","weapons.attack_damage.2");upgrade("station_enchant","weapons.sharpness.1");typed=false;effects.stationAddedStats(event);effects.stationEnchantTypeEvent(event);assertEquals(Material.PAPER,event.getResult().getType());assertTrue(models.constructed().isEmpty());}
    @Test void enchantmentsStackOnExistingLevelsAndPreserveOutputQuantity(){upgrade("station_enchant","weapons.sharpness.2");var event=event(3);event.getResult().addUnsafeEnchantment(Enchantment.SHARPNESS,1);effects.stationEnchantTypeEvent(event);assertEquals(3,onlyOutput(event,3).getEnchantmentLevel(Enchantment.SHARPNESS));}
    @Test void anItemWithoutEnchantDataCanGainItsFirstEnchantment(){emptyEnchants=false;upgrade("station_enchant","weapons.sharpness.1");var event=event(2);assertDoesNotThrow(()->effects.stationEnchantTypeEvent(event));assertEquals(1,onlyOutput(event,2).getEnchantmentLevel(Enchantment.SHARPNESS));}
    @Test void enchantmentOverflowIsDeliveredWithoutDestroyingCraftedItems(){upgrade("station_enchant","weapons.sharpness.1");var event=event(4);fullInventory();effects.stationEnchantTypeEvent(event);assertEquals(1,onlyOutput(event,4).getEnchantmentLevel(Enchantment.SHARPNESS));}
    @Test void unmatchedItemGroupsAndMmoTypesLeaveTheResultAlone(){upgrade("station_enchant","missing.sharpness.1");upgrade("add_stats","missing.attack_damage.1");Cache.professionItemTypes=List.of(new ProfessionItemType("missing",List.of("BOW")));var event=event(2);effects.stationEnchantTypeEvent(event);effects.stationAddedStats(event);assertEquals(Material.PAPER,event.getResult().getType());assertEquals(2,event.getResult().getAmount());}
    @Test void invalidEnchantmentUnlocksCannotBreakAnOtherwiseValidCraft(){upgrade("station_enchant","weapons","weapons.sharpness.bad","weapons.unknown_enchant.1","weapons.bad key.1","weapons.sharpness.0","weapons.sharpness.-2","weapons.sharpness.1");var event=event(2);assertDoesNotThrow(()->effects.stationEnchantTypeEvent(event));assertEquals(1,onlyOutput(event,2).getEnchantmentLevel(Enchantment.SHARPNESS));}
    @Test void addedStatsAccumulateOnExistingDataAndSupportDecimalCommas(){upgrade("add_stats","weapons.attack_damage.1,5");var event=event(3);var meta=event.getResult().getItemMeta();meta.getPersistentDataContainer().set(key(damage.getId()),PersistentDataType.DOUBLE,2d);event.getResult().setItemMeta(meta);effects.stationAddedStats(event);assertEquals(3.5,value(onlyOutput(event,3),damage));}
    @Test void everyNewStatInOneUpgradeIsPersisted(){upgrade("add_stats","weapons.attack_damage.2","weapons.attack_speed.3");var event=event(2);effects.stationAddedStats(event);var output=onlyOutput(event,2);assertEquals(2,value(output,damage));assertEquals(3,value(output,speed));}
    @Test void multipleStatUpgradesAndEnchantmentsSurviveBothEventHandlers(){upgrade("station_enchant","weapons.sharpness.1");upgrade("add_stats","weapons.attack_damage.2");upgrade("add_stats","weapons.attack_speed.3");var event=event(3);effects.stationEnchantTypeEvent(event);effects.stationAddedStats(event);var output=onlyOutput(event,3);assertEquals(1,output.getEnchantmentLevel(Enchantment.SHARPNESS));assertEquals(2,value(output,damage));assertEquals(3,value(output,speed));}
    @Test void addedStatsDropOverflowAndAllowAbsentHistory(){upgrade("add_stats","weapons.attack_damage.2");historyPresent=false;var event=event(3);fullInventory();effects.stationAddedStats(event);assertEquals(2,value(onlyOutput(event,3),damage));}
    @Test void malformedUnknownAndNonfiniteStatUnlocksAreSkippedSafely(){upgrade("add_stats","weapons","weapons.attack_damage.bad","weapons.unknown.2","weapons.attack_damage.NaN","weapons.attack_damage.Infinity","weapons.attack_damage.2");var event=event(2);assertDoesNotThrow(()->effects.stationAddedStats(event));assertEquals(2,value(onlyOutput(event,2),damage));}
    @Test void numericPerksDoNotOverwriteStructuredMmoStats(){var enchantStat=ItemStats.ENCHANTS;when(MMOItems.plugin.getStats().get("ENCHANTS")).thenReturn(enchantStat);upgrade("add_stats","weapons.enchants.2","weapons.attack_damage.2");var event=event(2);event.getResult().addUnsafeEnchantment(Enchantment.SHARPNESS,1);effects.stationAddedStats(event);var result=onlyOutput(event,2);assertEquals(1,result.getEnchantmentLevel(Enchantment.SHARPNESS));assertEquals(2,value(result,damage));}
    @Test void overflowingNumericPerksPreserveTheExistingFiniteValue(){upgrade("add_stats","weapons.attack_damage.1.7976931348623157E308","weapons.attack_speed.2");var event=event(2);var meta=event.getResult().getItemMeta();meta.getPersistentDataContainer().set(key(damage.getId()),PersistentDataType.DOUBLE,Double.MAX_VALUE);event.getResult().setItemMeta(meta);effects.stationAddedStats(event);var result=onlyOutput(event,2);assertEquals(Double.MAX_VALUE,value(result,damage));assertEquals(2,value(result,speed));}
    @Test void statIdentifiersUseRootLocale(){Locale.setDefault(Locale.forLanguageTag("tr-TR"));upgrade("add_stats","weapons.critical_strike_chance.2");var event=event(2);effects.stationAddedStats(event);assertEquals(2,value(onlyOutput(event,2),critical));}
    @Test void unrelatedAndDifferentAnimalUpgradesCannotUnlockLockedBreeding(){upgrade("permission","craft.smith");upgrade("breeding","sheep","pig");Cache.professionLockedBreeding=List.of("cow");var experience=mock(BreedingExperienceService.class);Cache.professionBreedingExperience=experience;var event=mock(org.bukkit.event.entity.EntityBreedEvent.class);when(event.getBreeder()).thenReturn(player);when(event.getEntityType()).thenReturn(EntityType.COW);var mother=mock(Animals.class);var father=mock(Animals.class);when(event.getMother()).thenReturn(mother);when(event.getFather()).thenReturn(father);effects.breedEvent(event);verify(event).setCancelled(true);verify(mother).setLoveModeTicks(0);verify(father).setLoveModeTicks(0);assertTrue(text(player).contains("not yet learned"));effects.awardBreedingExperience(event);verifyNoInteractions(experience);upgrade("breeding","COW");effects.awardBreedingExperience(event);verify(experience).award(player,"cow");}
    @Test void deniedRecipePermissionsCancelTheEventAndExplainTheRequirement(){var event=event(1);effects.permissionCheck(event);when(event.getInteraction()).thenReturn(StationAction.INTERACT_WITH_RECIPE);var recipe=mock(Recipe.class);var condition=mock(Condition.class);var denied=mock(PermissionCondition.class,RETURNS_DEEP_STUBS);var allowed=mock(PermissionCondition.class);when(recipe.getConditions()).thenReturn(List.of(condition,denied,allowed));when(event.getRecipe()).thenReturn(recipe);var itemPlayer=mock(net.Indyuce.mmoitems.api.player.PlayerData.class);var players=boundary(net.Indyuce.mmoitems.api.player.PlayerData.class);players.when(()->net.Indyuce.mmoitems.api.player.PlayerData.get(player.getUniqueId())).thenReturn(itemPlayer);when(denied.isMet(itemPlayer)).thenReturn(false);when(denied.getDisplay().format(false)).thenReturn("Requires smith permission");when(allowed.isMet(itemPlayer)).thenReturn(true);effects.permissionCheck(event);verify(event).setCancelled(true);assertTrue(text(player).contains("Requires smith permission"));}

    @Test void externalItemLoreKeepsItsDescriptionAndReplacesLegacyDynamicLines() {
        // Paper normalizes sparse legacy lore into empty components; use real Bukkit metadata
        // at the external creator boundary, not a fabricated getLore() result with null entries.
        var externalLore = List.of("", "§aForged by hand", " ", "§7Cost: 99");
        var external = new ItemStack(Material.IRON_PICKAXE);
        var meta = external.getItemMeta();
        meta.setLore(externalLore);
        external.setItemMeta(meta);
        var api = mock(net.tfminecraft.tlibs.objects.api.ItemAPI.class);
        var creator = mock(net.tfminecraft.tlibs.objects.api.subapi.ItemCreator.class);
        when(api.getCreator()).thenReturn(creator);
        when(creator.getItemFromPath("external.forged")).thenReturn(external);
        try (var tlibs = mockStatic(net.tfminecraft.tlibs.TLibs.class)) {
            tlibs.when(net.tfminecraft.tlibs.TLibs::getItemAPI).thenReturn(api);
            var spec = new ProfessionItemSpec("external.forged", null, null, null,
                    List.of(), false, List.of());
            var upgrade = new ProfessionUpgradeDefinition("forged", "smith", spec, 2,
                    "perk", List.of(), List.of());
            var lore = ProfessionLoreBuilder.buildUpgradeLore(upgrade, character, false);
            assertEquals("§aForged by hand", lore.getFirst());
            assertFalse(lore.contains(null));
            assertFalse(lore.stream().anyMatch(line -> line.contains("99")));
            assertTrue(lore.stream().anyMatch(line -> ChatColor.stripColor(line).equals("Cost: 2 Points")));
            assertEquals(externalLore, external.getItemMeta().getLore(),
                    "Rendering must not replace the external template's lore");
        }
    }
}

abstract class ProfessionRuntimeFixture {
    ServerMock server;World world;PlayerMock player;RPCharacter character;PlayerData data;PlayerManager manager;RPCharacters plugin;RuntimeTestState state;final List<AutoCloseable> mocks=new ArrayList<>();final Map<Player,PlayerData> loaded=new HashMap<>();final List<ProfessionUpgradeDefinition> upgrades=new ArrayList<>();
    @BeforeEach void setupProfession(){server=MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,ProfessionRegistry.class,MMOItems.class,MythicLib.class,net.Indyuce.mmocore.MMOCore.class);Cache.attributes=new ArrayList<>();Cache.professions=new ArrayList<>();Cache.backgroundTraitTypes=new ArrayList<>();ProfessionRegistry.clear();world=server.addSimpleWorld("professions");player=server.addPlayer("Smith");player.teleport(new Location(world,0,64,0));plugin=mock(RPCharacters.class);when(plugin.getServer()).thenReturn(server);when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());when(plugin.isEnabled()).thenReturn(true);RPCharacters.plugin=plugin;manager=mock(PlayerManager.class);var root=boundary(RPCharacters.class);root.when(RPCharacters::getPlayerManager).thenReturn(manager);var players=boundary(PlayerManager.class);players.when(()->PlayerManager.get(any(Player.class))).thenAnswer(call->loaded.get(call.getArgument(0)));boundary(Database.class);var config=new YamlConfiguration();config.set("name","Human");character=new RPCharacter(player,UUID.randomUUID().toString(),"Smith",true,Status.ALIVE,new Race("human",config),new ArrayList<>(),"HUMAN");data=new PlayerData(player);data.addCharacter(character);loaded.put(player,data);profession("smith");}
    <T> MockedStatic<T> boundary(Class<T> type){var mock=mockStatic(type);mocks.add(mock);return mock;}
    ProfessionDefinition profession(String id){var profession=new ProfessionDefinition(id,id,null,List.of());var all=new ArrayList<>(ProfessionRegistry.getProfessions());all.add(profession);ProfessionRegistry.setProfessions(all);return profession;}
    ProfessionUpgradeDefinition upgrade(String type,String...unlocks){var upgrade=new ProfessionUpgradeDefinition("u"+upgrades.size(),"smith",null,1,type,List.of(),List.of(unlocks));upgrades.add(upgrade);ProfessionRegistry.setUpgrades(upgrades);character.addProfessionUpgrade(upgrade.getId());return upgrade;}
    String text(PlayerMock player){var out=new ArrayList<String>();String message;while((message=player.nextMessage())!=null)out.add(message);return ChatColor.stripColor(String.join("\n",out));}
    @AfterEach void cleanupProfession() throws Exception {try{for(int i=mocks.size()-1;i>=0;i--)mocks.get(i).close();if(state!=null)state.close();}finally{MockBukkit.unmock();}}
}
