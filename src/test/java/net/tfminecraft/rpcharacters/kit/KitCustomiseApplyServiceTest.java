package net.tfminecraft.rpcharacters.kit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.logging.Logger;
import dev.lone.itemsadder.api.CustomStack;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.loaders.KitLoader;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.util.LegacyModelData;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

class KitCustomiseApplyServiceTest {
    RuntimeTestState state; RPCharacters plugin; Logger logger; ItemCreator creator; ArmorMerger merger;
    MockedStatic<TLibs> tlibs; MockedStatic<CustomStack> skins;
    @BeforeEach void setup() {
        MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,KitLoader.class);
        Cache.attributes=new ArrayList<>();Cache.professions=new ArrayList<>();plugin=mock(RPCharacters.class);when(plugin.getName()).thenReturn("RPCharacters");when(plugin.namespace()).thenReturn("rpcharacters");logger=mock(Logger.class);when(plugin.getLogger()).thenReturn(logger);RPCharacters.plugin=plugin;
        tlibs=mockStatic(TLibs.class);var api=mock(ItemAPI.class);creator=mock(ItemCreator.class);merger=mock(ArmorMerger.class);when(api.getCreator()).thenReturn(creator);when(api.getArmorMerger()).thenReturn(merger);tlibs.when(TLibs::getItemAPI).thenReturn(api);skins=mockStatic(CustomStack.class);
    }
    @AfterEach void cleanup() throws Exception {skins.close();tlibs.close();MockBukkit.unmock();state.close();}
    KitCustomiseData data(String name,List<String> lore,String skin,String path) {return new KitCustomiseData("paper",name,lore,skin,path);}
    ItemStack paper(String name,List<String> lore) {var i=new ItemStack(Material.PAPER);var m=i.getItemMeta();if(name!=null)m.setDisplayName(name);if(lore!=null)m.setLore(lore);i.setItemMeta(m);return i;}

    @Test void dataSnapshotsInputsAndAllConvenienceConstructorsKeepDefaults() {
        var lore=new ArrayList<>(List.of("Lore"));var colours=new ArrayList<>(List.of("red"));var styles=new ArrayList<>(List.of("bold"));
        var full=new KitCustomiseData(" paper ","Name",lore," skin "," v.PAPER "," custom ",colours,styles);lore.clear();colours.clear();styles.clear();
        assertEquals("paper",full.getKitKey());assertEquals("Name",full.getDisplayName());assertEquals(List.of("Lore"),full.getLore());assertEquals("skin",full.getSkinSlug());assertEquals("v.PAPER",full.getPath());assertEquals("custom",full.getIaNamespace());assertEquals(List.of("red"),full.getNameColours());assertEquals(List.of("bold"),full.getNameStyles());assertThrows(UnsupportedOperationException.class,()->full.getLore().add("changed"));
        var defaults=new KitCustomiseData(null,null,null," ",null," ");assertEquals("",defaults.getKitKey());assertEquals("",defaults.getDisplayName());assertEquals("",defaults.getPath());assertNull(defaults.getSkinSlug());assertEquals("tfmc_submissions",defaults.getIaNamespace());assertTrue(defaults.getLore().isEmpty());assertTrue(defaults.getNameStyles().isEmpty());
        var coloured=new KitCustomiseData("key","N",null,null,"v.PAPER",null,List.of("red"));assertEquals(List.of("red"),coloured.getNameColours());assertTrue(coloured.getNameStyles().isEmpty());
    }

    @Test void invalidBasesReturnNullAndMissingPathUsesLegacyKnife() {
        assertNull(KitCustomiseApplyService.buildStack(null));var air=new ItemStack(Material.AIR);when(creator.getItemFromPath("air")).thenReturn(air);when(creator.getItemFromPath("throws")).thenThrow(new IllegalStateException("broken"));
        for(String path:List.of("missing","air","throws"))assertNull(KitCustomiseApplyService.buildStack(data("N",null,null,path)));
        when(creator.getItemFromPath("m.tools.IRON_HUNTING_KNIFE")).thenReturn(paper(null,null));assertNotNull(KitCustomiseApplyService.buildStack(data("",null,null," ")));verify(logger).warning(contains("broken"));
    }

    @Test void customNameLoreAndMarkerAreAppliedWithoutMutatingTheBase() {
        var base=paper("Original",List.of("§aBase statistic"));var key=new NamespacedKey("test","identity");var meta=base.getItemMeta();meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,"retained");base.setItemMeta(meta);when(creator.getItemFromPath("v.PAPER")).thenReturn(base);
        var built=KitCustomiseApplyService.buildStack(data(" &aCustom ",Arrays.asList(null," ","New lore","&bBlue"),null,"v.PAPER"));
        assertNotSame(base,built);assertEquals("§aCustom",built.getItemMeta().getDisplayName());assertEquals(List.of("§aBase statistic"," ","§7New lore","§bBlue"),built.getItemMeta().getLore());assertEquals("retained",built.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING));assertEquals("paper",built.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin,"kit_customise"),PersistentDataType.STRING));assertEquals("Original",base.getItemMeta().getDisplayName());assertEquals(List.of("§aBase statistic"),base.getItemMeta().getLore());
    }

    @Test void skinMergeKeepsBaseStatsAndUsesConfiguredNamespace() {
        when(creator.getItemFromPath("v.PAPER")).thenReturn(paper("Base",List.of("Base statistic")));var skinned=new ItemStack(Material.WRITTEN_BOOK);when(merger.merge(any(ItemStack.class),eq(Optional.empty()),eq("ia.staff:book"))).thenReturn(skinned);
        var result=KitCustomiseApplyService.buildStack(new KitCustomiseData("paper","Named book",List.of("Story"),"book","v.PAPER","staff"));assertSame(skinned,result);assertEquals("Named book",result.getItemMeta().getDisplayName());assertEquals(List.of("Base statistic"," ","§7Story"),result.getItemMeta().getLore());
        when(merger.merge(any(ItemStack.class),eq(Optional.empty()),eq("ia.tfmc_submissions:broken"))).thenThrow(new IllegalStateException("skin unavailable"));assertEquals(Material.PAPER,KitCustomiseApplyService.buildStack(data("Name",null,"broken","v.PAPER")).getType());verify(logger).warning(contains("skin unavailable"));
    }

    @Test void metadataFailuresAreLoggedAndLoreCopiesAreIndependent() {
        var noMeta=mock(ItemStack.class);when(noMeta.getType()).thenReturn(Material.PAPER);when(noMeta.clone()).thenReturn(noMeta);when(creator.getItemFromPath("no-meta")).thenReturn(noMeta);assertSame(noMeta,KitCustomiseApplyService.buildStack(data("N",null,null,"no-meta")));verify(logger).warning(contains("null ItemMeta"));
        var rejected=mock(ItemStack.class);when(rejected.getType()).thenReturn(Material.PAPER);when(rejected.clone()).thenReturn(rejected);when(rejected.getItemMeta()).thenReturn(paper(null,null).getItemMeta());when(creator.getItemFromPath("rejected")).thenReturn(rejected);assertSame(rejected,KitCustomiseApplyService.buildStack(data("N",List.of(),null,"rejected")));verify(logger).warning(contains("setItemMeta failed"));
        assertTrue(KitCustomiseApplyService.copyLore(null).isEmpty());assertTrue(KitCustomiseApplyService.copyLore(noMeta).isEmpty());var withLore=paper(null,List.of("Original"));var copy=KitCustomiseApplyService.copyLore(withLore);copy.clear();assertEquals(List.of("Original"),withLore.getItemMeta().getLore());
    }

    @Test void loreMergingAndColourDetectionPreserveIntentionalColourAndStyle() {
        assertEquals(List.of("custom"),KitCustomiseApplyService.mergeLore(null,List.of("custom")));assertEquals(List.of("base"),KitCustomiseApplyService.mergeLore(List.of("base"),null));assertEquals(List.of("base"," ","custom"),KitCustomiseApplyService.mergeLore(List.of("base"),List.of("custom")));
        assertEquals("",KitCustomiseApplyService.ensureLoreGray(null));assertEquals("",KitCustomiseApplyService.ensureLoreGray("  "));assertEquals("",KitCustomiseApplyService.formatLoreLine(null));assertFalse(KitCustomiseApplyService.hasLeadingLoreColour(null));assertFalse(KitCustomiseApplyService.hasLeadingLoreColour(""));
        for(String colour:List.of("§aGreen","&BBlue","#12abEFHex")){assertTrue(KitCustomiseApplyService.hasLeadingLoreColour(colour));assertEquals(colour,KitCustomiseApplyService.ensureLoreGray(" "+colour+" "));}
        for(String other:List.of("§lBold","&lBold","#12ZZEFInvalid","#123Short","plain","&")){assertFalse(KitCustomiseApplyService.hasLeadingLoreColour(other));assertEquals("§7"+other,KitCustomiseApplyService.ensureLoreGray(other));}
    }

    @Test void displayFormattingFallsBackSafelyWhenFormatterRejectsTokens() {
        assertNull(KitCustomiseApplyService.formatDisplayName(null));assertNull(KitCustomiseApplyService.formatDisplayName(data(" ",null,null,null)));
        try(var formatter=mockStatic(StringFormatter.class)) {
            var styled=new KitCustomiseData("paper"," Name ",null,null,null,null,List.of("red"),List.of("bold"));formatter.when(()->StringFormatter.formatDisplayName(eq("Name"),eq(List.of("red")),eq(List.of("bold")))).thenReturn("styled");assertEquals("styled",KitCustomiseApplyService.formatDisplayName(styled));
            formatter.when(()->StringFormatter.formatDisplayName(anyString(),anyList(),anyList())).thenThrow(new IllegalArgumentException("invalid"));formatter.when(()->StringFormatter.formatHex("Name")).thenReturn("fallback");assertEquals("fallback",KitCustomiseApplyService.formatDisplayName(styled));formatter.when(()->StringFormatter.formatHex("Name")).thenThrow(new IllegalStateException());assertEquals("Name",KitCustomiseApplyService.formatDisplayName(styled));
            formatter.when(()->StringFormatter.formatHex("§7Lore")).thenThrow(new IllegalStateException());assertEquals("§7Lore",KitCustomiseApplyService.formatLoreLine("Lore"));
        }
    }

    @Test void skinAvailabilityRejectsMissingNullAirAndApiFailures() {
        assertTrue(KitCustomiseApplyService.isSkinPresent(null));assertTrue(KitCustomiseApplyService.isSkinPresent(data("N",null,null,null)));var custom=new KitCustomiseData("paper","N",null,"book",null,"staff");assertFalse(KitCustomiseApplyService.isSkinPresent(custom));var skin=mock(CustomStack.class);skins.when(()->CustomStack.getInstance("staff:book")).thenReturn(skin);assertFalse(KitCustomiseApplyService.isSkinPresent(custom));var air=new ItemStack(Material.AIR);when(skin.getItemStack()).thenReturn(air);assertFalse(KitCustomiseApplyService.isSkinPresent(custom));when(skin.getItemStack()).thenReturn(new ItemStack(Material.PAPER));assertTrue(KitCustomiseApplyService.isSkinPresent(custom));skins.when(()->CustomStack.getInstance("staff:book")).thenThrow(new LinkageError("plugin not enabled"));assertFalse(KitCustomiseApplyService.isSkinPresent(custom));
        assertFalse(KitCustomiseApplyService.isSkinPresent(data("N",null,"missing",null)));skins.verify(()->CustomStack.getInstance("tfmc_submissions:missing"));
    }

    @Test void skinReadinessOnlyChecksTheSelectedEditableKeys() {
        var character=new RPCharacter(null);character.getKitCustomisations().put("null",null);character.putKitCustomise(data("N",null,"missing",null));character.getKitCustomisations().put("blank",new KitCustomiseData(" ",null,null,"missing",null));
        assertTrue(KitCustomiseApplyService.requiredSkinsReady(null,List.of("paper")));assertTrue(KitCustomiseApplyService.requiredSkinsReady(character,null));assertTrue(KitCustomiseApplyService.requiredSkinsReady(character,List.of()));assertTrue(KitCustomiseApplyService.requiredSkinsReady(character,List.of("other")));assertFalse(KitCustomiseApplyService.requiredSkinsReady(character,List.of("paper")));character.putKitCustomise(data("N",null,null,null));assertTrue(KitCustomiseApplyService.requiredSkinsReady(character,List.of("paper")));
    }

    @Test void previewRowsExposeConfigurationAndRealItemAppearance() {
        var base=paper("§aNamed paper",List.of("§bLore"));var edit=new KitEditableSpec("skin.png","signed.png","base","two","three");
        try(var loader=mockStatic(KitLoader.class);var models=mockStatic(LegacyModelData.class)) {
            var definitions=Arrays.asList(null,new KitItemDefinition("ignored",1,null),new KitItemDefinition(" ",1,edit),new KitItemDefinition(" v.PAPER ",3,edit),new KitItemDefinition("missing",1,edit),new KitItemDefinition("air",1,edit),new KitItemDefinition("throws",1,edit));var kit=new KitDefinition("starter","Starter",0,true,definitions);var kits=new LinkedHashMap<String,KitDefinition>();kits.put("null",null);kits.put("starter",kit);loader.when(KitLoader::getKits).thenReturn(kits);
            var air=new ItemStack(Material.AIR);when(creator.getItemFromPath("v.PAPER")).thenReturn(base);when(creator.getItemFromPath("air")).thenReturn(air);when(creator.getItemFromPath("throws")).thenThrow(new IllegalArgumentException("bad preview"));models.when(()->LegacyModelData.has(any(ItemMeta.class))).thenReturn(true);models.when(()->LegacyModelData.get(any(ItemMeta.class))).thenReturn(812);
            var rows=EditableKitPreviewBuilder.build();assertEquals(4,rows.size());var row=rows.getFirst();assertEquals("starter",row.getGrantKitId());assertEquals("paper",row.getKitKey());assertEquals("v.PAPER",row.getPath());assertEquals(3,row.getAmount());assertEquals("skin.png",row.getSkinPng());assertEquals("signed.png",row.getSkinPngSigned());assertEquals("base",row.getBaseSet());assertEquals("two",row.get2dTemplate());assertEquals("three",row.get3dTemplate());var preview=row.getPreview();assertEquals("Named paper",preview.getDisplayName());assertEquals(List.of("Lore"),preview.getLore());assertEquals("PAPER",preview.getMaterial());assertEquals(812,preview.getCustomModelData());assertNull(rows.get(1).getPreview());assertNull(rows.get(2).getPreview());assertNull(rows.get(3).getPreview());
            var empty=new EditableKitPreviewBuilder.Preview(null,null,null,null);assertEquals("",empty.getDisplayName());assertTrue(empty.getLore().isEmpty());assertEquals("",empty.getMaterial());assertNull(empty.getCustomModelData());var minimal=new EditableKitPreviewBuilder.Row(null,"k","p",1,null,null,null,null," ",null);assertEquals("",minimal.getGrantKitId());assertEquals("",minimal.getSkinPng());assertEquals("",minimal.getSkinPngSigned());assertEquals("",minimal.getBaseSet());assertEquals("",minimal.get2dTemplate());assertNull(minimal.get3dTemplate());assertEquals("paper",EditableKitPreviewBuilder.kitKeyFromPath("PAPER"));
        }
    }

    @Test void kitStatusStorageIsLocaleIndependentAndRejectsUnknownValues() {
        assertNull(KitStatus.fromStorage(null));assertNull(KitStatus.fromStorage(" "));assertNull(KitStatus.fromStorage("unknown"));assertEquals(KitStatus.GRANTED,KitStatus.fromStorage(" granted "));Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals(KitStatus.ELIGIBLE,KitStatus.fromStorage("eligible"));assertEquals("ineligible",KitStatus.INELIGIBLE.toStorage());
    }

    @Test void externalItemPreviewPreservesSparseLoreAsEmptyLines() {
        // ItemMeta's legacy list contract does not require non-null elements.
        var meta=mock(ItemMeta.class);when(meta.hasLore()).thenReturn(true);when(meta.getLore()).thenReturn(Arrays.asList(null,"§aVisible"));
        var stack=mock(ItemStack.class);when(stack.getType()).thenReturn(Material.PAPER);when(stack.getItemMeta()).thenReturn(meta);when(creator.getItemFromPath("external.paper")).thenReturn(stack);
        try(var loader=mockStatic(KitLoader.class);var models=mockStatic(LegacyModelData.class)) {
            var editable=new KitEditableSpec("paper","base","two",null);var kit=new KitDefinition("starter","Starter",0,true,List.of(new KitItemDefinition("external.paper",1,editable)));loader.when(KitLoader::getKits).thenReturn(Map.of("starter",kit));
            var preview=EditableKitPreviewBuilder.build().getFirst().getPreview();assertEquals("Paper",preview.getDisplayName());assertEquals(List.of("","Visible"),preview.getLore());assertEquals("PAPER",preview.getMaterial());assertNull(preview.getCustomModelData());
        }
    }
}
