package net.tfminecraft.rpcharacters.managers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import net.Indyuce.mmocore.api.event.PlayerChangeClassEvent;
import net.Indyuce.mmocore.api.event.PlayerLevelUpEvent;
import net.Indyuce.mmocore.api.event.PlayerExperienceGainEvent;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.identity.TempAliasService;
import net.tfminecraft.rpcharacters.injuries.InjuryHealingService;
import net.tfminecraft.rpcharacters.injuries.OffhandBlockService;
import net.tfminecraft.rpcharacters.prosthetics.ProstheticFuelService;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.holder.RPCHolder;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.objects.experience.ExperienceModifier;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.PotionData;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitEffectResolver;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.utils.ClueProgressFormatter;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.Integrator;
import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.enums.ConfirmType;
import net.tfminecraft.rpcharacters.enums.FreezeReason;
import net.tfminecraft.rpcharacters.enums.Status;

public class PlayerManager implements Listener{
	private static final int TRAIT_POTION_DURATION_TICKS = 60;
	private static final int TRAIT_POTION_PULSE_TICKS = 40;
	
	private static List<PlayerData> data = new ArrayList<>();
	private HashMap<Player, Location> frozen = new HashMap<>();
	private HashMap<Player, ConfirmType> confirm = new HashMap<>();
	private HashMap<Player, RPCharacter> last = new HashMap<>();
	private HashMap<Player, Long> cooldown = new HashMap<>();
	/** In-memory Discord gate; TFMCWeb re-applies on join (not persisted). */
	private final Set<UUID> discordGateRequired = new HashSet<>();
	private Database db = new Database();
	
	public static boolean exists(Player p) {
		for(PlayerData pd : data) {
			if(pd.getPlayer() != null && pd.getPlayer().equals(p)) return true;
		}
		return false;
	}
	public static PlayerData get(Player p) {
		for(PlayerData pd : data) {
			if(pd.getPlayer() != null && pd.getPlayer().equals(p)) return pd;
		}
		return null;
	}
	public static PlayerData get(UUID uuid) {
		if (uuid == null) {
			return null;
		}
		for (PlayerData pd : data) {
			if (uuid.equals(pd.getUniqueId())) {
				return pd;
			}
		}
		return null;
	}
	public static List<PlayerData> getOnlineData() {
		return new ArrayList<>(data);
	}

	public boolean hasTrait(Player p, String trait) {
		PlayerData pd = get(p);
		if(pd == null) return false;
		RPCharacter active = pd.getActiveCharacter();
		if(active == null) return false;
		for(Trait t : active.getTraits()) {
			if(t.getId().equalsIgnoreCase(trait)) return true;
		}
		return false;
	}

	public boolean isAtFreezeLoc(Player p) {
		if(!p.getGameMode().equals(GameMode.SURVIVAL)) return true;
		Location loc = frozen.get(p);
		if(loc == null) return true;
		if(p.getLocation().getX() != loc.getX()) return false;
		if(p.getLocation().getY() != loc.getY()) return false;
		if(p.getLocation().getZ() != loc.getZ()) return false;
		return true;
	}

	public void toFreezeLoc(Player p) {
		if(frozen.get(p) == null) return;
		Location loc = frozen.get(p).clone();
		loc.setYaw(p.getLocation().getYaw());
		loc.setPitch(p.getLocation().getPitch());
		p.teleport(loc);
	}

	public void releaseFreeze(Player p) {
		frozen.remove(p);
	}

	/**
	 * External Discord gate (TFMCWeb). Does not touch characters.
	 * Survival freeze is applied via {@link #reevaluateFreeze(Player)}.
	 */
	public void setDiscordGate(UUID id, boolean required) {
		if (id == null) {
			return;
		}
		if (required) {
			discordGateRequired.add(id);
		} else {
			discordGateRequired.remove(id);
		}
		Player online = Bukkit.getPlayer(id);
		if (online != null && online.isOnline()) {
			reevaluateFreeze(online);
		}
	}

	public void setDiscordGate(Player p, boolean required) {
		if (p == null) {
			return;
		}
		setDiscordGate(p.getUniqueId(), required);
	}

	public boolean isDiscordGate(UUID id) {
		return id != null && discordGateRequired.contains(id);
	}

	public void reevaluateFreeze(Player p) {
		if (p.isDead() || net.tfminecraft.rpcharacters.permadeath.PermadeathService.isAwaitingPermakillRespawn(p)) {
			return;
		}
		if (CreationManager.activeCreators.containsKey(p) || !p.getGameMode().equals(GameMode.SURVIVAL)) {
			frozen.remove(p);
			return;
		}
		PlayerData pd = get(p);
		if (pd == null) return;

		FreezeReason reason = getFreezeReason(p, pd);
		if (reason != null) {
			if (!frozen.containsKey(p)) {
				frozen.put(p, p.getLocation());
			}
		} else {
			frozen.remove(p);
		}
	}

	private FreezeReason getFreezeReason(Player player, PlayerData pd) {
		if (player != null && isDiscordGate(player.getUniqueId())) {
			return FreezeReason.DISCORD_REQUIRED;
		}
		if (Cache.excessCharactersFreeze && player != null) {
			int max = CharacterSlotService.getMaxAliveCharacters(player);
			int alive = pd.getCharacters(Status.ALIVE).size();
			if (alive > max) {
				return FreezeReason.EXCESS_CHARACTERS;
			}
		}
		if (!pd.hasActiveCharacter() && Cache.noCharacterFreeze) {
			return FreezeReason.NO_CHARACTER;
		}
		if (pd.hasActiveCharacter()) {
			RPCharacter active = pd.getActiveCharacter();
			if (!active.hasEnoughClues() && Cache.lackingCluesFreeze) {
				return FreezeReason.LACKING_CLUES;
			}
		}
		return null;
	}

	private void notifyFrozen(Player p, FreezeReason reason, PlayerData pd) {
		if (CreationManager.activeCreators.containsKey(p)) return;
		if (cooldown.containsKey(p) && cooldown.get(p) > System.currentTimeMillis()) {
			return;
		}
		cooldown.put(p, System.currentTimeMillis() + 5000L);
		if (reason == FreezeReason.DISCORD_REQUIRED) {
			RPTexts.title(p, " ", RPTexts.ERROR + "Discord Required!", 5, 50, 5);
			RPTexts.send(p, RPTexts.ERROR + "You must be linked to Discord and in the TFMC server to play.");
			RPTexts.send(p, RPTexts.ERROR + "Link with " + RPTexts.COMMAND + "/linkdiscord"
					+ RPTexts.ERROR + " (then run " + RPTexts.COMMAND + "/linkdiscord <code>"
					+ RPTexts.ERROR + " in Discord).");
			RPTexts.send(p, RPTexts.MUTED + "If you left Discord, rejoin within 1 hour to keep your link.");
		} else if (reason == FreezeReason.NO_CHARACTER) {
			RPTexts.title(p, " ", RPTexts.ERROR + "No Character!", 5, 50, 5);
			RPTexts.send(p, RPTexts.ERROR + "You do not have an active character!");
			RPTexts.send(p, RPTexts.ERROR + "Create one with " + RPTexts.COMMAND + "/rpcharacter create");
			RPTexts.send(p, RPTexts.ERROR + "Or do it through " + RPTexts.COMMAND + "/rpcharacter menu");
		} else if (reason == FreezeReason.LACKING_CLUES) {
			RPCharacter c = pd.getActiveCharacter();
			RPTexts.title(p, " ", RPTexts.ERROR + "More Clues Needed!", 5, 50, 5);
			RPTexts.send(p, ClueProgressFormatter.lackingCluesMessage(c));
			RPTexts.send(p, RPTexts.ERROR + "Add clues with " + RPTexts.COMMAND + "/rpcharacter clues");
		} else if (reason == FreezeReason.EXCESS_CHARACTERS) {
			int alive = pd.getCharacters(Status.ALIVE).size();
			int max = CharacterSlotService.getMaxAliveCharacters(p);
			RPTexts.title(p, " ", RPTexts.ERROR + "Too Many Characters!", 5, 50, 5);
			RPTexts.send(p, RPTexts.ERROR + "You have " + RPTexts.WARN + alive + RPTexts.ERROR
					+ " alive characters but your rank allows " + RPTexts.WARN + max + RPTexts.ERROR + ".");
			RPTexts.send(p, RPTexts.COMMAND + "/rpcharacter menu");
		}
		p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
	}
	
	public void start() {
		Bukkit.getLogger().info("[RPCharacters] Starting Player Manager");
		traitPotionPulse();
		InjuryHealingService.start();
		OffhandBlockService.start();
		ProstheticFuelService.start();
		new BukkitRunnable()
		{
			public void run()
			{
				for(Player p : Bukkit.getOnlinePlayers()) {
					if (p.isDead()
							|| net.tfminecraft.rpcharacters.permadeath.PermadeathService.isAwaitingPermakillRespawn(p)) {
						continue;
					}
					if (CreationManager.activeCreators.containsKey(p) || !p.getGameMode().equals(GameMode.SURVIVAL)) {
						frozen.remove(p);
						continue;
					}
					PlayerData pd = get(p);
					if (pd == null) continue;

					FreezeReason reason = getFreezeReason(p, pd);
					if (reason != null) {
						if (!frozen.containsKey(p)) {
							frozen.put(p, p.getLocation());
						}
						if (!isAtFreezeLoc(p)) {
							toFreezeLoc(p);
							notifyFrozen(p, reason, pd);
						}
					} else {
						frozen.remove(p);
					}
				}
			}
		}.runTaskTimer(RPCharacters.plugin, 0L, 5L);
	}

	public void traitPotionPulse() {
		Bukkit.getLogger().info("[RPCharacters] Starting Trait Potion Pulse");
		new BukkitRunnable()
		{
			public void run()
			{
				for(Player p : Bukkit.getOnlinePlayers()) {
					PlayerData pd = get(p);
					if(pd == null) continue;
					if(!pd.hasActiveCharacter()) continue;

					RPCharacter c = pd.getActiveCharacter();
					Map<PotionEffectType, Integer> effects = new HashMap<>();

					for(Trait trait : c.getTraits()) {
						List<PotionData> potions = TraitEffectResolver.resolvePotionEffects(c, trait);
						if(potions.isEmpty()) continue;
						for(PotionData potion : potions) {
							PotionEffectType type = potion.getType();
							if(type == null) continue;
							int current = effects.getOrDefault(type, -1);
							if(potion.getAmplifier() > current) {
								effects.put(type, potion.getAmplifier());
							}
						}
					}

					for(Map.Entry<PotionEffectType, Integer> entry : effects.entrySet()) {
						PotionEffect effect = new PotionEffect(entry.getKey(), TRAIT_POTION_DURATION_TICKS, entry.getValue(), false, false, false);
						p.addPotionEffect(effect, true);
					}
				}
			}
		}.runTaskTimer(RPCharacters.plugin, 0L, TRAIT_POTION_PULSE_TICKS);
	}
	
	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		initiatePlayer(p);
		net.tfminecraft.rpcharacters.ingest.CharacterIngestService.tryPullForPlayerAsync(
			RPCharacters.plugin, p.getUniqueId()
		);
	}
	@EventHandler
	public void onLeave(PlayerQuitEvent e) {
		Player p = e.getPlayer();
		net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager.get().clearViewer(p.getUniqueId());
		net.tfminecraft.rpcharacters.grave.GraveVisualManager.get().clearViewer(p.getUniqueId());
		net.tfminecraft.rpcharacters.clues.discovery.ClueAdminModeService.clear(p);
		TempAliasService.clear(p);
		MmoCorePlayerReady.cancel(p.getUniqueId());
		PlayerData pd = get(p);
		if (pd != null && pd.hasActiveCharacter()) {
			// Prevent MMOCore from persisting stacked attribute bases for the next login.
			AttributePointService.clearMmoAttributeBases(p);
		}
		stampActiveCharacterLocation(p);
		savePlayer(p);
		data.remove(pd);
	}
	public void savePlayer(Player p) {
		if(exists(p)) {
			db.savePlayer(get(p));
		}
	}

	public static void stampActiveCharacterLocation(Player p) {
		PlayerData pd = get(p);
		if (pd == null || p == null) {
			return;
		}
		RPCharacter active = pd.getActiveCharacter();
		if (active == null) {
			return;
		}
		active.stampLastLocation(p.getLocation());
		net.tfminecraft.rpcharacters.mail.MailRecipientDirectory.upsert(pd.getUniqueId(), active);
	}
	
	public void initiatePlayer(Player p) {
		if(!exists(p)) {
			PlayerData pd = db.loadPlayer(p);
			if(pd == null) {
				pd = new PlayerData(p);
			}
			final PlayerData loaded = pd;
			data.add(loaded);
			if (ProstheticTraitRules.sanitize(loaded)) {
				savePlayer(p);
			}
			if(!loaded.hasActiveCharacter() && loaded.getCharacters(Status.ALIVE).size() > 0) {
				loaded.setActiveCharacter(loaded.getCharacters(Status.ALIVE).get(0));
			}
			PermissionGroupService.enforceNameColourOnLogin(p, loaded);
			net.tfminecraft.rpcharacters.clues.discovery.InvestigationPointService.bootstrap(p);
			reevaluateFreeze(p);
			if (loaded.hasActiveCharacter()) {
				net.tfminecraft.rpcharacters.wardrobe.WardrobeService.refreshActiveAsync(p);
			}
			MmoCorePlayerReady.runWhenLoaded(p, () -> applyMmoOnJoin(p, loaded));
		}
	}

	private void applyMmoOnJoin(Player p, PlayerData pd) {
		if (p == null || !p.isOnline() || pd == null) {
			return;
		}
		new Integrator().applyPendingRemoves(p, pd.takePendingMmoAttributeRemoves());
		AttributePointService.migrateAttributePointsIfNeeded(p, pd);
		if(pd.hasActiveCharacter()) {
			RPCharacter active = pd.getActiveCharacter();
			AttributePointService.syncOnActivate(active);
			net.tfminecraft.rpcharacters.professions.ProfessionIntegrator.apply(p, active);
			net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle.fireActivated(
					p, pd.getUniqueId(), active, null);
		} else {
			net.Indyuce.mmocore.api.player.PlayerData.get(p).setAttributePoints(0);
		}
		net.Indyuce.mmocore.api.player.PlayerData.get(p).setAttributeReallocationPoints(0);
		ClassService.migrateSkillPointsIfNeeded(p);
		ClassService.trackFromPlayer(p);
		ClassService.sanitizeForeignSkillLevels(p);
		net.tfminecraft.rpcharacters.professions.ProfessionPointService.bootstrapLifetimeFromMmoCore(p);
		ClassService.applyFreeSkillPoints(p);
	}
	public void confirmClick(Player p, RPCharacter c, ConfirmType t) {
		if(t.equals(ConfirmType.KILL)) {
			Player owner = c.getOwner();
			net.tfminecraft.rpcharacters.permadeath.PermadeathService.killCharacter(owner, c,
					net.tfminecraft.rpcharacters.permadeath.PermakillCause.CHARACTER_MENU);
			InventoryManager inv = new InventoryManager();
			inv.characterView(p, c);
		} else if(t.equals(ConfirmType.SWITCH)) {
			PlayerData pd = get(p);
			pd.setActiveCharacter(c);
			net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager.get().refreshViewer(p);
			reevaluateFreeze(p);
			net.tfminecraft.rpcharacters.wardrobe.WardrobeService.refreshActiveAsync(p);
			InventoryManager inv = new InventoryManager();
			inv.characterView(p, c);
		} else if (t.equals(ConfirmType.REVIVE)) {
			Player owner = c.getOwner();
			PlayerData ownerData = get(owner);
			if (ownerData == null) {
				RPTexts.send(p, RPTexts.ERROR + "Could not find that player's data.");
				return;
			}
			if (!CharacterSlotService.hasFreeSlot(owner, ownerData)) {
				int alive = ownerData.getCharacters(Status.ALIVE).size();
				int max = CharacterSlotService.getMaxAliveCharacters(owner);
				RPTexts.send(p, RPTexts.ERROR + owner.getName() + " has no free character slots ("
						+ RPTexts.WARN + alive + RPTexts.ERROR + "/" + RPTexts.WARN + max + RPTexts.ERROR + ").");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
			c.setStatus(Status.ALIVE);
			if (!ownerData.hasActiveCharacter()) {
				ownerData.setActiveCharacter(c);
				if (owner.isOnline()) {
					net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager.get()
							.refreshViewer(owner);
					net.tfminecraft.rpcharacters.wardrobe.WardrobeService.refreshActiveAsync(owner);
				}
			}
			savePlayer(owner);
			reevaluateFreeze(owner);
			RPTexts.send(p, RPTexts.SUCCESS + "Revived character " + RPTexts.MUTED + c.getName()
					+ RPTexts.SUCCESS + " for " + RPTexts.MUTED + owner.getName() + RPTexts.SUCCESS + ".");
			RPTexts.send(owner, RPTexts.SUCCESS + "Your character " + RPTexts.MUTED + c.getName()
					+ " " + RPTexts.SUCCESS + "was revived by staff.");
			InventoryManager inv = new InventoryManager();
			inv.characterView(p, c);
		}
	}

	public void traitEdit(Player p, String key) {
		for(Stage s : StageLoader.getNew()) {
			if(!(s instanceof SelectionStage)) continue;
			SelectionStage stage = new SelectionStage((SelectionStage) s);
			if(!stage.getKey().equals(key)) continue;
			if(stage.hasDependency()) {
				if(!stage.getDependency().check(get(p).getActiveCharacter())) {
					RPTexts.send(p, RPTexts.ERROR + "You do not fulfill the prerequisites to view those traits (" + key + ")");
					return;
				}
			}
			InventoryManager inv = new InventoryManager();
			stage.update(get(p));
			inv.selectionView(p, stage, null);
		}
	}

	@EventHandler
	public void selectionClick(InventoryClickEvent e) {
		Player p = (Player) e.getWhoClicked();
		if(!(e.getView().getTopInventory().getHolder() instanceof RPCHolder)) return;
		if(e.getClickedInventory() == null) return;
		if(!e.getClickedInventory().equals(e.getView().getTopInventory())) return;
		RPCHolder h = (RPCHolder) e.getView().getTopInventory().getHolder();
		Player o = h.getOwner();
		if(e.getView().getTitle().equalsIgnoreCase("§7Character Menu")) {
			e.setCancelled(true);
			if(e.getSlot() == Cache.deadSlot) {
				PlayerData pd = get(o);
				if(pd.getCharacters(Status.DEAD).size() > 0) {
					InventoryManager inv = new InventoryManager();
					inv.deadView(p, o);
					p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
				}
			} else if(Cache.characterSlots.contains(e.getSlot())) {
				ItemStack i = e.getCurrentItem();
				if (i == null) return;
				if (i.getType().equals(Material.GRAY_STAINED_GLASS_PANE)) {
					return;
				}
				PlayerData pd = get(o);
				int slotIndex = Cache.characterSlots.indexOf(e.getSlot());
				if (i.getType().equals(Material.BARRIER)) {
					RPTexts.send(p, RPTexts.ERROR + "This character slot is locked.");
					p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
					return;
				}
				if(i.getType().equals(Material.YELLOW_CONCRETE)) {
					if(!p.equals(o)) return;
					if (!CharacterSlotService.isSlotUnlocked(o, slotIndex)) {
						RPTexts.send(p, RPTexts.ERROR + "This character slot is locked.");
						p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
						return;
					}
					if (!CharacterSlotService.hasFreeSlot(o, pd)) {
						RPTexts.send(p, RPTexts.ERROR + "You don't have a free character slot!");
						p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
						return;
					}
					p.closeInventory();
					CreationManager.initiateCreation(p);
					p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
				} else if(i.getType().equals(Material.ENDER_PEARL)) {
					if(e.getSlot() == 0) return;
					NamespacedKey key = new NamespacedKey(RPCharacters.plugin, "character_id");
					String id = i.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
					if(o == null) {
						RPTexts.send(p, RPTexts.ERROR + "Cant find player, maybe they are offline?");
						return;
					}
					RPCharacter c = pd.getCharacterById(id);
					if(c == null) {
						RPTexts.send(p, RPTexts.ERROR + "Cant find character");
						return;
					}
					InventoryManager inv = new InventoryManager();
					inv.characterView(p, c);
					p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
				}
			}
		} else if(e.getView().getTitle().equalsIgnoreCase("§7Character Info")) {
			e.setCancelled(true);
			if(e.getSlot() == 26) {
				if(o == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find player, maybe they are offline?");
					return;
				}
				InventoryManager inv = new InventoryManager();
				inv.profileView(p, o);
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
			} else if(e.getSlot() == 8) {
				ItemStack i = e.getInventory().getItem(10);
				if(!e.getCurrentItem().getType().equals(Material.IRON_AXE)) return;
				if(o == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find player, maybe they are offline?");
					return;
				}
				NamespacedKey key = new NamespacedKey(RPCharacters.plugin, "character_id");
				String id = i.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
				PlayerData pd = get(o);
				RPCharacter c = pd.getCharacterById(id);
				if(c == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find character");
					return;
				}
				confirm.put(p, ConfirmType.KILL);
				last.put(p, c);
				InventoryManager inv = new InventoryManager();
				inv.confirmView(p);
			} else if (e.getSlot() == 4 && Permissions.isAdmin(p)) {
				ItemStack i = e.getInventory().getItem(10);
				if (i == null || i.getItemMeta() == null) return;
				if (!e.getCurrentItem().getType().equals(Material.TOTEM_OF_UNDYING)) return;
				if (o == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find player, maybe they are offline?");
					return;
				}
				NamespacedKey key = new NamespacedKey(RPCharacters.plugin, "character_id");
				String id = i.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
				PlayerData pd = get(o);
				RPCharacter c = pd.getCharacterById(id);
				if (c == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find character");
					return;
				}
				if (!c.getStatus().equals(Status.DEAD)) {
					return;
				}
				if (!CharacterSlotService.hasFreeSlot(o, pd)) {
					int alive = pd.getCharacters(Status.ALIVE).size();
					int max = CharacterSlotService.getMaxAliveCharacters(o);
					RPTexts.send(p, RPTexts.ERROR + o.getName() + " has no free character slots ("
							+ RPTexts.WARN + alive + RPTexts.ERROR + "/" + RPTexts.WARN + max + RPTexts.ERROR + ").");
					p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
					return;
				}
				confirm.put(p, ConfirmType.REVIVE);
				last.put(p, c);
				InventoryManager inv = new InventoryManager();
				inv.confirmView(p);
			} else if(e.getSlot() == 6) {
				ItemStack i = e.getInventory().getItem(10);
				if(!e.getCurrentItem().getType().equals(Material.EMERALD)) return;
				if(o == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find player, maybe they are offline?");
					return;
				}
				NamespacedKey key = new NamespacedKey(RPCharacters.plugin, "character_id");
				String id = i.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
				PlayerData pd = get(o);
				RPCharacter c = pd.getCharacterById(id);
				if(c == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find character");
					return;
				}
				confirm.put(p, ConfirmType.SWITCH);
				last.put(p, c);
				InventoryManager inv = new InventoryManager();
				inv.confirmView(p);
			} else if(e.getSlot() == 14) {
				ItemStack i = e.getInventory().getItem(10);
				if(i == null || i.getItemMeta() == null) return;
				if(o == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find player, maybe they are offline?");
					return;
				}
				NamespacedKey key = new NamespacedKey(RPCharacters.plugin, "character_id");
				String id = i.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
				PlayerData pd = get(o);
				RPCharacter c = pd.getCharacterById(id);
				if(c == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find character");
					return;
				}
				InventoryManager inv = new InventoryManager();
				inv.traitsView(p, c);
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
			} else if(e.getSlot() == 16) {
				ItemStack clicked = e.getCurrentItem();
				if (clicked == null || clicked.getItemMeta() == null) return;
				if (!clicked.getType().equals(Material.BOOK)) return;
				if (!p.equals(o)) return;
				NamespacedKey key = new NamespacedKey(RPCharacters.plugin, "character_id");
				String id = clicked.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
				if (id == null) return;
				PlayerData pd = get(o);
				RPCharacter c = pd.getCharacterById(id);
				if (c == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find character");
					return;
				}
				InventoryManager inv = new InventoryManager();
				inv.cluesView(p, c);
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
			}
		} else if(e.getView().getTitle().startsWith("§7Clues (")) {
			e.setCancelled(true);
			if (o == null) return;
			PlayerData pd = get(o);
			if (pd == null) return;

			ItemStack clicked = e.getCurrentItem();
			if (clicked == null || clicked.getItemMeta() == null) return;
			NamespacedKey characterKey = new NamespacedKey(RPCharacters.plugin, "character_id");
			String characterId = clicked.getItemMeta().getPersistentDataContainer().get(characterKey, PersistentDataType.STRING);

			boolean fromSummary = false;
			CharacterCreation creation = null;
			if (e.getInventory().getHolder() instanceof RPCHolder holder) {
				fromSummary = holder.getContext() == net.tfminecraft.rpcharacters.enums.CreationGuiContext.CREATION_SUMMARY
						|| holder.getContext() == net.tfminecraft.rpcharacters.enums.CreationGuiContext.EDIT_SUMMARY;
				creation = holder.getCreation();
			}

			if (e.getSlot() == e.getInventory().getSize() - 1) {
				if (characterId == null) return;
				if (fromSummary && creation != null) {
					creation.returnToSummary();
					p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
					return;
				}
				RPCharacter c = pd.getCharacterById(characterId);
				if (c == null) return;
				InventoryManager inv = new InventoryManager();
				inv.characterView(p, c);
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
				return;
			}

			if (!p.equals(o)) return;

			if (e.getSlot() == 8 && clicked.getType().equals(Material.LIME_DYE)) {
				if (characterId == null) return;
				ClueInputManager.beginInput(p, characterId, fromSummary);
				return;
			}

			if (clicked.getType().equals(Material.PAPER)) {
				if (characterId == null) return;
				RPCharacter c = CreationManager.resolveCharacter(p, characterId);
				if (c == null) {
					c = pd.getCharacterById(characterId);
				}
				if (c == null) return;
				NamespacedKey clueIndexKey = new NamespacedKey(RPCharacters.plugin, "clue_index");
				Integer index = clicked.getItemMeta().getPersistentDataContainer().get(clueIndexKey, PersistentDataType.INTEGER);
				if (index == null) return;
				if (c.removePlayerClue(index)) {
					CharacterCreation activeSession = CreationManager.activeCreators.get(p);
					boolean editing = activeSession != null && activeSession.isEditing();
					if (!CreationManager.isDraftCharacter(p, characterId)) {
						savePlayer(p);
						reevaluateFreeze(p);
					} else if (editing) {
						activeSession.persistEdits();
					}
					InventoryManager inv = new InventoryManager();
					if (fromSummary && creation != null) {
						net.tfminecraft.rpcharacters.enums.CreationGuiContext context = creation.isEditing()
								? net.tfminecraft.rpcharacters.enums.CreationGuiContext.EDIT_SUMMARY
								: net.tfminecraft.rpcharacters.enums.CreationGuiContext.CREATION_SUMMARY;
						inv.cluesView(p, c, context, creation);
					} else {
						inv.cluesView(p, c);
					}
					p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
				}
			}
		} else if(e.getView().getTitle().equalsIgnoreCase("§7Confirm Action")) {
			e.setCancelled(true);
			if(!confirm.containsKey(p)) return;
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
			if(e.getSlot() == 11) {
				confirmClick(p, last.get(p), confirm.get(p));
				confirm.remove(p);
				last.remove(p);
			} else if(e.getSlot() == 15) {
				InventoryManager inv = new InventoryManager();
				inv.characterView(p, last.get(p));
				confirm.remove(p);
				last.remove(p);
			}
		} else if(e.getView().getTitle().equalsIgnoreCase("§7Trait List")) {
			e.setCancelled(true);
			if(e.getSlot() != e.getInventory().getSize() - 1) return;
			ItemStack item = e.getCurrentItem();
			if(item == null || item.getItemMeta() == null) return;
			NamespacedKey key = new NamespacedKey(RPCharacters.plugin, "character_id");
			String id = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
			if(o == null) {
				RPTexts.send(p, RPTexts.ERROR + "Cant find player, maybe they are offline?");
				return;
			}
			PlayerData pd = get(o);
			RPCharacter c = pd.getCharacterById(id);
			if(c == null) {
				RPTexts.send(p, RPTexts.ERROR + "Cant find character");
				return;
			}
			InventoryManager inv = new InventoryManager();
			inv.characterView(p, c);
			p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
		} else if(e.getView().getTitle().equalsIgnoreCase("§7Dead Characters")) {
			e.setCancelled(true);
			if(e.getCurrentItem().getType().equals(Material.ENDER_PEARL)) {
				NamespacedKey key = new NamespacedKey(RPCharacters.plugin, "character_id");
				String id = e.getCurrentItem().getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
				if(o == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find player, maybe they are offline?");
					return;
				}
				PlayerData pd = get(o);
				RPCharacter c = pd.getCharacterById(id);
				if(c == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find character");
					return;
				}
				InventoryManager inv = new InventoryManager();
				inv.characterView(p, c);
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
			} else if(e.getCurrentItem() != null) {
				if(o == null) {
					RPTexts.send(p, RPTexts.ERROR + "Cant find player, maybe they are offline?");
					return;
				}
				InventoryManager inv = new InventoryManager();
				inv.profileView(p, o);
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
			}
		}
	}

	@EventHandler
	public void xpGain(PlayerExperienceGainEvent e) {
		Player p = e.getPlayer();
		PlayerData pd = get(p);
		if(!pd.hasActiveCharacter()) return;
		if(e.getProfession() == null) {
			ClassService.trackFromPlayer(p);
			return;
		}
		RPCharacter c = pd.getActiveCharacter();
		if(c.getAttributeData().getExperienceModifiers().size() == 0) return;
		String profession = e.getProfession().getId();
		for(ExperienceModifier m : c.getAttributeData().getExperienceModifiers()) {
			if(m.getProfession().equalsIgnoreCase(profession)) {
				double amount = e.getExperience();
				amount *= m.getFactor();
				e.setExperience(amount);
			}
		}
		ClassService.trackFromPlayer(p);
	}

	@EventHandler
	public void levelUp(PlayerLevelUpEvent e) {
		ClassService.trackFromPlayer(e.getPlayer());
	}

	@EventHandler
	public void classChange(PlayerChangeClassEvent e) {
		Player player = e.getPlayer();
		PlayerData pd = get(player);
		if (pd == null) {
			return;
		}

		CharacterCreation cc = CreationManager.activeCreators.get(player);
		if (cc != null) {
			String newClassId = e.getData().getProfess().getId();
			if (cc.isEditing()) {
				RPCharacter character = cc.getCharacter();
				String oldClassId = character.getMMOClass();
				character.setMMOClass(newClassId);
				net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle.notifyClassChange(
						player, pd.getUniqueId(), character, oldClassId, character.getMMOClass());
			} else {
				cc.getCharacter().setMMOClass(newClassId);
			}
			if (!ClassService.isApplying(player.getUniqueId())) {
				ClassService.restoreAccountProgression(player);
			}
			if (cc.isEditing()) {
				RPCharacter character = cc.getCharacter();
				if (character.isActive()) {
					scheduleAttributeReapply(player, character);
				}
			}
			return;
		}

		if (CreationManager.activeCreators.containsKey(player)) {
			return;
		}

		if (pd.hasActiveCharacter()) {
			RPCharacter c = pd.getActiveCharacter();
			String oldClassId = c.getMMOClass();
			String newClassId = e.getData().getProfess().getId();
			c.setMMOClass(newClassId);
			net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle.notifyClassChange(
					player, pd.getUniqueId(), c, oldClassId, c.getMMOClass());
			if (!ClassService.isApplying(player.getUniqueId())) {
				ClassService.restoreAccountProgression(player);
			}
			scheduleAttributeReapply(player, c);
		}
	}

	private static void scheduleAttributeReapply(Player player, RPCharacter character) {
		new BukkitRunnable() {
			@Override
			public void run() {
				if (!player.isOnline() || character == null) {
					return;
				}
				AttributePointService.applyCharacterAttributes(player, character);
			}
		}.runTaskLater(RPCharacters.plugin, 1L);
	}
}
