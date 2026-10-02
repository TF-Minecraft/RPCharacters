package net.tfminecraft.rpcharacters.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Consumer;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.display.TextDisplayHelper;
import net.tfminecraft.rpcharacters.managers.SpawnedClueManager;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

class ClueHologramTest {
    RuntimeTestState state;World world;SpawnedClueManager manager;
    MockedStatic<Bukkit> bukkit;MockedStatic<SpawnedClueManager> managers;
    final Map<UUID,Entity> entities=new LinkedHashMap<>();final List<TextDisplay> spawned=new ArrayList<>();
    @BeforeEach void setup() {
        MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class);RPCharacters.plugin=mock(RPCharacters.class);when(RPCharacters.plugin.getName()).thenReturn("RPCharacters");when(RPCharacters.plugin.namespace()).thenReturn("rpcharacters");
        world=mock(World.class);when(world.getName()).thenReturn("clues");when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getNearbyEntities(any(Location.class),anyDouble(),anyDouble(),anyDouble())).thenAnswer(c -> new ArrayList<>(entities.values()));
        when(world.spawn(any(Location.class),eq(TextDisplay.class),any(Consumer.class))).thenAnswer(c -> {var display=display();spawned.add(display);((Consumer<TextDisplay>)c.getArgument(2)).accept(display);return display;});
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);bukkit.when(() -> Bukkit.getWorld("clues")).thenReturn(world);bukkit.when(() -> Bukkit.getEntity(any(UUID.class))).thenAnswer(c -> entities.get(c.getArgument(0)));
        manager=mock(SpawnedClueManager.class);managers=mockStatic(SpawnedClueManager.class);managers.when(SpawnedClueManager::get).thenReturn(manager);
        Cache.spawnedClueVisualYOffset=1;Cache.spawnedClueFirstLineOffset=.5;Cache.spawnedClueLineSpacing=.25;Cache.spawnedClueScale=.75f;Cache.spawnedClueLineLength=100;
    }
    @AfterEach void restore() {managers.close();bukkit.close();state.close();MockBukkit.unmock();}
    TextDisplay display() {var d=mock(TextDisplay.class);var id=UUID.randomUUID();when(d.getUniqueId()).thenReturn(id);when(d.getPersistentDataContainer()).thenReturn(new ItemStack(Material.PAPER).getItemMeta().getPersistentDataContainer());entities.put(id,d);return d;}
    SpawnedClue clue(String text) {return new SpawnedClue(UUID.randomUUID(),"clues",0,65,0,text,Long.MAX_VALUE,UUID.randomUUID());}

    @Test void spawningTagsFormatsAndReusesDisplaysWhileRemovingOrphans() {
        var c=clue("A footprint");var orphan=display();TextDisplayHelper.setTag(orphan,TextDisplayHelper.key("spawned_clue_id"),c.getId().toString());
        var unrelated=display();var dead=display();when(dead.isDead()).thenReturn(true);var nonText=mock(Entity.class);entities.put(UUID.randomUUID(),nonText);
        ClueHologram.spawn(c);assertEquals(1,spawned.size());var active=spawned.getFirst();assertEquals(List.of(active.getUniqueId()),c.getDisplayEntityIds());assertTrue(c.isVisualsSpawned());
        verify(orphan).remove();verify(unrelated,never()).remove();verify(dead,never()).remove();verify(active).setText("§7A footprint");verify(active).setBillboard(Display.Billboard.CENTER);verify(active).setShadowed(true);verify(active).setGravity(false);verify(active).setPersistent(true);verify(active).setAlignment(TextDisplay.TextAlignment.CENTER);
        assertEquals(c.getId().toString(),TextDisplayHelper.getStringTag(active,TextDisplayHelper.key("spawned_clue_id")));assertEquals(0,active.getPersistentDataContainer().get(TextDisplayHelper.key("spawned_clue_line"),PersistentDataType.INTEGER));
        verify(manager).markDisplayDirty();ClueHologram.refresh(c);assertEquals(1,spawned.size());verify(active,times(2)).setText("§7A footprint");verify(manager,times(1)).markDisplayDirty();
        ClueHologram.remove(c);verify(active).remove();assertTrue(c.getDisplayEntityIds().isEmpty());assertFalse(c.isVisualsSpawned());
    }

    @Test void refreshReplacesMissingEntitiesAndRemovesSurplusLines() {
        var c=clue("One line");var extra=display();c.getDisplayEntityIds().add(UUID.randomUUID());c.getDisplayEntityIds().add(extra.getUniqueId());ClueHologram.refresh(c);
        assertEquals(1,c.getDisplayEntityIds().size());verify(extra).remove();assertEquals(spawned.getFirst().getUniqueId(),c.getDisplayEntityIds().getFirst());
        var old=spawned.getFirst();when(old.isDead()).thenReturn(true);ClueHologram.refresh(c);assertEquals(2,spawned.size());assertNotEquals(old.getUniqueId(),c.getDisplayEntityIds().getFirst());
        var missing=new SpawnedClue(UUID.randomUUID(),"missing",0,0,0,"text",Long.MAX_VALUE,UUID.randomUUID());ClueHologram.refresh(missing);ClueHologram.remove(missing);ClueHologram.spawnClueParticles(missing);assertFalse(missing.isVisualsSpawned());
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);ClueHologram.remove(c);assertTrue(c.getDisplayEntityIds().isEmpty());
    }

    @Test void refreshMovesExistingLinesWhenConfiguredVisualOffsetChanges() {
        var c=clue("text");ClueHologram.spawn(c);var display=spawned.getFirst();clearInvocations(display);Cache.spawnedClueVisualYOffset=3;
        ClueHologram.refresh(c);verify(display).teleport(new Location(world,0,68.5,0));assertEquals(1,spawned.size());
    }

    @Test void particleRaysRespectWorldsAndIncludeBothEndpoints() {
        var from=new Location(world,0,65,0);var to=new Location(world,2,65,0);
        ClueHologram.spawnParticle(null);ClueHologram.spawnParticle(new Location(null,0,0,0));ClueHologram.spawnParticleRay(null,to);ClueHologram.spawnParticleRay(from,null);ClueHologram.spawnParticleRay(new Location(null,0,0,0),to);ClueHologram.spawnParticleRay(from,new Location(mock(World.class),0,0,0));
        ClueHologram.spawnParticleRay(from,from);ClueHologram.spawnParticleRay(from,to);
        var rays=mockingDetails(world).getInvocations().stream().filter(i -> i.getMethod().getName().equals("spawnParticle")&&i.getArgument(0)==Particle.DUST).toList();assertEquals(9,rays.size());assertEquals(0D,(Double)rays.getFirst().getArgument(1));assertEquals(2D,(Double)rays.getLast().getArgument(1));
        ClueHologram.spawnClueParticles(clue("plain"));var linked=new SpawnedClue(UUID.randomUUID(),"clues",0,65,0,"linked",Long.MAX_VALUE,UUID.randomUUID(),1,65,1);ClueHologram.spawnClueParticles(linked);
    }

    @Test void displayHelpersInterpolateAndManageOptionalEntities() {
        var from=new Location(world,1,2,3,10,20);var to=new Location(world,3,6,9,30,40);
        assertNull(TextDisplayHelper.lerpLocation(from,null,.5));assertEquals(to,TextDisplayHelper.lerpLocation(null,to,.5));assertNotSame(to,TextDisplayHelper.lerpLocation(null,to,.5));
        assertEquals(to,TextDisplayHelper.lerpLocation(new Location(null,0,0,0),to,.5));assertEquals(new Location(null,0,0,0),TextDisplayHelper.lerpLocation(from,new Location(null,0,0,0),.5));
        assertEquals(to,TextDisplayHelper.lerpLocation(new Location(mock(World.class),0,0,0),to,.5));assertEquals(from,TextDisplayHelper.lerpLocation(from,to,-1));assertEquals(to,TextDisplayHelper.lerpLocation(from,to,2));
        assertEquals(new Location(world,2,4,6,20,30),TextDisplayHelper.lerpLocation(from,to,.5));assertEquals(.5,TextDisplayHelper.createScaleTransformation(.5f).getScale().x);
        assertNull(TextDisplayHelper.findDisplay(null));assertNull(TextDisplayHelper.findDisplay(UUID.randomUUID()));TextDisplayHelper.removeDisplay(null);TextDisplayHelper.removeDisplay(UUID.randomUUID());
        var nonText=mock(Entity.class);var id=UUID.randomUUID();entities.put(id,nonText);assertNull(TextDisplayHelper.findDisplay(id));TextDisplayHelper.removeDisplay(id);verify(nonText).remove();
        var dead=display();when(dead.isDead()).thenReturn(true);TextDisplayHelper.removeDisplay(dead.getUniqueId());verify(dead,never()).remove();
        var d=display();assertSame(d,TextDisplayHelper.findDisplay(d.getUniqueId()));TextDisplayHelper.applyDisplay(d,"text",TextDisplayHelper.createScaleTransformation(1),false);verify(d).setPersistent(false);
    }
}
