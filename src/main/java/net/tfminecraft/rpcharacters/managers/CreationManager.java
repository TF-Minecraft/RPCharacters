package net.tfminecraft.rpcharacters.managers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Dependency;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.SummaryEditSupport;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.creation.stages.AttributesStage;
import net.tfminecraft.rpcharacters.creation.stages.ClueStage;
import net.tfminecraft.rpcharacters.creation.stages.InfoStage;
import net.tfminecraft.rpcharacters.creation.stages.QuestionStage;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.creation.stages.SetterStage;
import net.tfminecraft.rpcharacters.creation.stages.SummaryStage;
import net.tfminecraft.rpcharacters.holder.RPCHolder;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.SelectableItem;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.PlaytimeGate;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.enums.CreationGuiContext;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.enums.Status;

public class CreationManager implements Listener{
	public static HashMap<Player, CharacterCreation> activeCreators = new HashMap<>();
	private static final String SUMMARY_ACTION_KEY = "summary_action";
	
	public static void initiateCreation(Player p) {
		PlayerData pd = PlayerManager.get(p);
		if (!CharacterSlotService.hasFreeSlot(p, pd)) {
			RPTexts.send(p, RPTexts.ERROR + "You don't have a free character slot!");
			p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		// Prefer landing any waiting web creates before opening in-game creator.
		net.tfminecraft.rpcharacters.ingest.CharacterIngestService.tryPullForPlayerAsync(
			net.tfminecraft.rpcharacters.RPCharacters.plugin, p.getUniqueId()
		);
		if(PermissionGroupService.hasCharacterSwitchCooldown(p, pd) && pd.getCharacters(Status.ALIVE).size() > 0 && !p.hasPermission("rpcharacters.no_cooldown")) {
			RPTexts.send(p, RPTexts.ERROR + "You are on cooldown from switching characters");
			p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		CharacterCreation cc = new CharacterCreation(p);
		cc.setCanNext(false);
		activeCreators.put(p, cc);
	}

	public static void initiateStagePreview(Player p, String stageId) {
		if (p == null) {
			return;
		}
		if (activeCreators.containsKey(p)) {
			RPTexts.send(p, RPTexts.ERROR + "You already have an active character session.");
			p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		CharacterCreation.forStagePreview(p, stageId);
	}

	public static void initiateEdit(Player p) {
		if (activeCreators.containsKey(p)) {
			CharacterCreation existing = activeCreators.get(p);
			if (existing != null && existing.isPreview()) {
				RPTexts.send(p, RPTexts.ERROR + "You are busy previewing a stage.");
			} else {
				RPTexts.send(p, RPTexts.ERROR + "You already have an active character session.");
			}
			p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		PlayerData pd = PlayerManager.get(p);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(p, RPTexts.ERROR + "You have no active character to edit.");
			p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		CharacterCreation cc = CharacterCreation.forEdit(p, pd.getActiveCharacter());
		activeCreators.put(p, cc);
		cc.openSummary();
	}

	public static void initiateEditEntry(Player p, String entryKey) {
		if (entryKey == null || entryKey.isBlank()) {
			initiateEdit(p);
			return;
		}
		String stageId = SummaryEditSupport.resolveStageId(entryKey);
		if ("clues".equalsIgnoreCase(stageId)) {
			if (!activeCreators.containsKey(p)) {
				initiateEdit(p);
			}
			CharacterCreation cc = activeCreators.get(p);
			if (cc == null) {
				return;
			}
			InventoryManager inv = new InventoryManager();
			CreationGuiContext context = cc.isEditing() ? CreationGuiContext.EDIT_SUMMARY : CreationGuiContext.CREATION_SUMMARY;
			inv.cluesView(p, cc.getCharacter(), context, cc);
			return;
		}
		if (stageId == null) {
			RPTexts.send(p, RPTexts.ERROR + "Unknown edit option.");
			return;
		}
		Stage stage = StageLoader.getById(stageId);
		PlayerData pd = PlayerManager.get(p);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(p, RPTexts.ERROR + "You have no active character to edit.");
			return;
		}
		if (stage != null && !StageEditLock.canEdit(p, stage, pd.getActiveCharacter())) {
			RPTexts.send(p, RPTexts.ERROR + "That choice is locked and can no longer be edited.");
			return;
		}
		if (!activeCreators.containsKey(p)) {
			CharacterCreation cc = CharacterCreation.forEdit(p, pd.getActiveCharacter());
			activeCreators.put(p, cc);
			cc.jumpToStageForEdit(stageId);
			return;
		}
		CharacterCreation existing = activeCreators.get(p);
		if (existing.isPreview()) {
			RPTexts.send(p, RPTexts.ERROR + "You are busy previewing a stage.");
			return;
		}
		if (!existing.isEditing()) {
			RPTexts.send(p, RPTexts.ERROR + "You are busy creating a character.");
			return;
		}
		existing.jumpToStageForEdit(stageId);
	}

	public static RPCharacter resolveCharacter(Player player, String characterId) {
		CharacterCreation cc = activeCreators.get(player);
		if (cc != null && cc.getCharacter().getId().equals(characterId)) {
			return cc.getCharacter();
		}
		PlayerData pd = PlayerManager.get(player);
		return pd != null ? pd.getCharacterById(characterId) : null;
	}

	public static boolean isDraftCharacter(Player player, String characterId) {
		CharacterCreation cc = activeCreators.get(player);
		return cc != null && !cc.isEditing() && !cc.isPreview()
				&& cc.getCharacter().getId().equals(characterId);
	}

	public static void sendChatBlockedDuringCreationHint(Player player) {
		RPTexts.send(player, RPTexts.ERROR + "Chat is blocked during character creation.");
		RPTexts.send(player, RPTexts.MUTED + "Type " + RPTexts.COMMAND + "/rpcharacter help "
				+ RPTexts.MUTED + "for guidance on this stage.");
	}

	public static boolean isChatInputStage(Player player) {
		CharacterCreation cc = activeCreators.get(player);
		if (cc == null) {
			return false;
		}
		Stage stage = cc.getActiveStage();
		return stage instanceof QuestionStage
				|| stage instanceof SetterStage
				|| stage instanceof ClueStage;
	}
	
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void chatEvent(AsyncPlayerChatEvent event) {
		Player player = event.getPlayer();
		CharacterCreation cc = activeCreators.get(player);
		if (cc == null) {
			return;
		}
		if (ClueInputManager.isPending(player)) {
			return;
		}
		event.setCancelled(true);
		Stage activeStage = cc.getActiveStage();
		String message = event.getMessage();
		new BukkitRunnable() {
			@Override
			public void run() {
				// Serialize submissions and never apply queued input to a different stage/session.
				if (activeCreators.get(player) != cc || cc.isCancelled()
						|| cc.getActiveStage() != activeStage) return;
				if (activeStage instanceof QuestionStage) {
					cc.answerQuestion(message);
				} else if (activeStage instanceof SetterStage setter) {
					setter.finish(message, player, cc);
				} else if (activeStage instanceof ClueStage clue) {
					clue.finish(message, player, cc);
				} else {
					sendChatBlockedDuringCreationHint(player);
				}
			}
		}.runTask(RPCharacters.plugin);
	}

	public static void next(Player p) {
		if(activeCreators.containsKey(p)) {
			CharacterCreation cc = activeCreators.get(p);
			if (cc.getActiveStage() instanceof InfoStage info) {
				info.stopMessages();
				p.resetTitle();
			}
			if (cc.isEditingFromSummary()) {
				cc.returnToSummary();
				return;
			}
			activeCreators.get(p).runStage();
		}
	}

	public static void back(Player p) {
		if (!activeCreators.containsKey(p)) {
			RPTexts.send(p, RPTexts.ERROR + "You dont have an active creator");
			return;
		}
		if (ClueInputManager.isPending(p)) {
			CharacterCreation cc = activeCreators.get(p);
			String characterId = ClueInputManager.getPendingCharacterId(p);
			ClueInputManager.cancel(p);
			if (cc != null && characterId != null) {
				RPCharacter character = resolveCharacter(p, characterId);
				if (character != null
						&& (isDraftCharacter(p, characterId) || cc.isEditing())) {
					InventoryManager inv = new InventoryManager();
					CreationGuiContext context = cc.isEditing()
							? CreationGuiContext.EDIT_SUMMARY
							: CreationGuiContext.CREATION_SUMMARY;
					inv.cluesView(p, character, context, cc);
					RPTexts.send(p, RPTexts.MUTED + "Clue input cancelled.");
					return;
				}
			}
			if (cc != null) {
				cc.openSummary();
				RPTexts.send(p, RPTexts.MUTED + "Clue input cancelled.");
			}
			return;
		}
		activeCreators.get(p).goBack();
	}

	public void click(Player p, Stage stage, CharacterCreation cc, InventoryClickEvent e) {
		if(stage == null) return;
		RPCharacter c = null;
		if(cc != null) {
			c = cc.getCharacter();
		} else {
			c = PlayerManager.get(p).getActiveCharacter();
		}
		if(c == null) return;
		Inventory inventory = e.getClickedInventory();
		if(inventory == null) return;
		if(!(inventory.getHolder() instanceof RPCHolder)) return;
		RPCHolder h = (RPCHolder) inventory.getHolder();
		SelectionStage s = (SelectionStage) stage;
		for(int i = 0; i<s.getSlots().size(); i++) {
			if(s.getSlots().get(i) == e.getSlot()) {
				SelectableItem item = s.getOptions().get(i);
				Set<String> traitIds = conflictTraitIds(c, s, cc);
				if(!item.isSelected()) {
					for(SelectableItem stored : s.getSelection()) {
						if(stored.isExclusive(item)) {
							RPTexts.send(p, RPTexts.ERROR + "You have one or more incompatible traits");
							p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
							return;
						}
					}
					for(String traitId : traitIds) {
						Trait t = TraitLoader.getByString(traitId);
						if(item.isExclusive(traitId) || (t != null && t.getTraitData() != null && t.getTraitData().isExclusive(item.getId()))) {
							RPTexts.send(p, RPTexts.ERROR + "You have one or more incompatible traits");
							p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
							return;
						}
					}
					if(s.getMaxSelections() <= s.getSelections()) {
						if (item.getType().equalsIgnoreCase("class") && s.getMaxSelections() == 1) {
							for (SelectableItem chosen : new ArrayList<>(s.getSelection())) {
								chosen.setSelected(false);
								s.unSelect(chosen);
							}
						} else {
							RPTexts.send(p, RPTexts.ERROR + "Cannot make any more selections");
							p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
							return;
						}
					}
					if(item.getCost() > s.getPoints()) {
						RPTexts.send(p, RPTexts.ERROR + "Cannot afford this trait");
						p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
						return;
					}
					if(item.hasDependency()) {
						if(!dependencyMet(item.getDependency(), c, traitIds, cc, s)) {
							RPTexts.send(p, RPTexts.ERROR + "Lacking requirements");
							p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
							return;
						}
					}
					if (item.getType().equalsIgnoreCase("trait")) {
						Trait trait = TraitLoader.getByString(item.getId());
						if (trait != null && !PlaytimeGate.canSelectTrait(p, trait)) {
							RPTexts.send(p, PlaytimeGate.denialMessage(p, trait));
							p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
							return;
						}
					}
					s.select(item);
				} else {
					for(String traitId : traitIds) {
						Trait t = TraitLoader.getByString(traitId);
						if(t == null || t.getTraitData() == null || !t.getTraitData().hasDependency()) {
							continue;
						}
						Dependency dependency = t.getTraitData().getDependency();
						if(dependency == null || dependency.getDependencies() == null || !dependency.getDependencies().contains(item.getId())) {
							continue;
						}
						if(!dependencyStillMet(dependency, c, traitIds, item.getId(), cc, s)) {
							RPTexts.send(p, dependency.toString());
							RPTexts.send(p, RPTexts.ERROR + "Your trait " + t.getName() + RPTexts.ERROR + " is dependent on this trait, remove that first!");
							p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
							return;
						}
					}
					s.unSelect(item);
				}
				p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
				if(cc != null) item.click(cc);
				else {
					item.click(c);
					ProstheticTraitRules.stripReplacedInjuries(c);
					c.update();
					RPCharacters.getPlayerManager().savePlayer(p);
					RPCharacters.getPlayerManager().reevaluateFreeze(p);
				}
				InventoryManager inv = new InventoryManager();
				inv.selectionUpdate(e.getView().getTopInventory(), p, s, cc);
			}
		}
		if(e.getSlot() == s.getSize()-9) {
			if(cc != null) {
				if (cc.isEditingFromSummary()) {
					h.override();
					p.closeInventory();
					cc.returnToSummary();
					return;
				}
				if (cc.isEditing()) {
					h.override();
					p.closeInventory();
					cc.returnToSummary();
					return;
				}
				cc.cancel();
			}
			h.override();
			p.closeInventory();
			return;
		}
		if(e.getSlot() == s.getSize()-1) {
			h.override();
			s.confirm(p, cc);
		}
	}

	private static boolean usesDraftTraits(CharacterCreation cc, SelectionStage stage) {
		return cc != null && stage.getTarget() != null && stage.getTarget().equalsIgnoreCase("trait");
	}

	/**
	 * During a creation or edit session, this stage's picks are the draft.
	 * Saved traits of the same key are stale until confirm. Other keys stay live.
	 * Outside a session, clicks write the character immediately, so the saved list is current.
	 */
	private static Set<String> conflictTraitIds(RPCharacter character, SelectionStage stage, CharacterCreation cc) {
		Set<String> ids = new LinkedHashSet<>();
		boolean draft = usesDraftTraits(cc, stage);
		if (character.getTraits() != null) {
			for (Trait trait : character.getTraits()) {
				if (draft && sameStageKey(trait, stage.getKey())) {
					continue;
				}
				if (trait.getId() != null) {
					ids.add(trait.getId());
				}
			}
		}
		if (draft) {
			for (SelectableItem selected : stage.getSelection()) {
				if (selected.getId() != null) {
					ids.add(selected.getId());
				}
			}
		}
		return ids;
	}

	private static boolean sameStageKey(Trait trait, String stageKey) {
		if (stageKey == null || trait.getTraitData() == null || trait.getTraitData().getKey() == null) {
			return false;
		}
		return trait.getTraitData().getKey().equalsIgnoreCase(stageKey);
	}

	private static boolean dependencyMet(Dependency dependency, RPCharacter character, Set<String> traitIds,
			CharacterCreation cc, SelectionStage stage) {
		if (usesDraftTraits(cc, stage) && dependency.getType() != null && dependency.getType().equalsIgnoreCase("trait")) {
			return dependency.satisfiedBy(traitIds);
		}
		return dependency.check(character);
	}

	private static boolean dependencyStillMet(Dependency dependency, RPCharacter character, Set<String> traitIds,
			String removedId, CharacterCreation cc, SelectionStage stage) {
		if (usesDraftTraits(cc, stage) && dependency.getType() != null && dependency.getType().equalsIgnoreCase("trait")) {
			return dependency.satisfiedByExcluding(traitIds, removedId);
		}
		return dependency.checkExclude(character, removedId);
	}

	private void handleSummaryClick(Player p, InventoryClickEvent e) {
		if (!activeCreators.containsKey(p)) {
			return;
		}
		CharacterCreation cc = activeCreators.get(p);
		ItemStack clicked = e.getCurrentItem();
		if (clicked == null || clicked.getItemMeta() == null) {
			return;
		}
		NamespacedKey actionKey = new NamespacedKey(RPCharacters.plugin, SUMMARY_ACTION_KEY);
		String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
		if (action == null) {
			return;
		}
		if (e.getInventory().getHolder() instanceof RPCHolder holder) {
			holder.override();
		}
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
		if ("confirm".equals(action)) {
			if (cc.isPreview()) {
				cc.endPreview();
			} else if (cc.isEditing()) {
				cc.closeEditSession();
			} else {
				cc.finish();
			}
			return;
		}
		if ("cancel".equals(action)) {
			p.closeInventory();
			cc.cancel();
			return;
		}
		if ("clues".equals(action)) {
			if (cc.isPreview()) {
				RPTexts.send(p, RPTexts.ERROR + "Preview is one stage only. Confirm or cancel to exit.");
				return;
			}
			InventoryManager inv = new InventoryManager();
			CreationGuiContext context = cc.isEditing() ? CreationGuiContext.EDIT_SUMMARY : CreationGuiContext.CREATION_SUMMARY;
			inv.cluesView(p, cc.getCharacter(), context, cc);
			return;
		}
		if (action.startsWith("edit:")) {
			if (cc.isPreview()) {
				RPTexts.send(p, RPTexts.ERROR + "Preview is one stage only. Confirm or cancel to exit.");
				return;
			}
			String stageId = action.substring("edit:".length());
			Stage stage = StageLoader.getById(stageId);
			if (stage != null && !StageEditLock.canEdit(p, stage, cc.getCharacter())) {
				RPTexts.send(p, RPTexts.ERROR + "That choice is locked and can no longer be edited.");
				return;
			}
			cc.jumpToStageForEdit(stageId);
		}
	}

	public void nonCreationClick(Player p, InventoryClickEvent e) {
		Inventory inv = e.getClickedInventory();
		if(!e.getClickedInventory().equals(e.getView().getTopInventory())) return;
		if(!(inv.getHolder() instanceof RPCHolder)) return;
		RPCHolder h = (RPCHolder) inv.getHolder();
		e.setCancelled(true);
		click(p, h.getStage(), null, e);
	}
	
	@EventHandler
	public void selectionClick(InventoryClickEvent e) {
		Player p = (Player) e.getWhoClicked();
		if(e.getClickedInventory() == null) return;
		if(!e.getClickedInventory().equals(e.getView().getTopInventory())) return;
		if (e.getView().getTitle().equals("§7Creation Summary") || e.getView().getTitle().equals("§7Edit Character")) {
			e.setCancelled(true);
			handleSummaryClick(p, e);
			return;
		}
		if(!activeCreators.containsKey(p)) {
			nonCreationClick(p, e);
			return;
		}
		e.setCancelled(true);
		CharacterCreation cc = activeCreators.get(p);
		Stage activeStage = cc.getActiveStage();
		if (activeStage instanceof SelectionStage) {
			click(p, activeStage, cc, e);
		} else if (activeStage instanceof AttributesStage) {
			attributesClick(p, (AttributesStage) activeStage, cc, e);
		}
	}

	private void attributesClick(Player p, AttributesStage s, CharacterCreation cc, InventoryClickEvent e) {
		Inventory inventory = e.getClickedInventory();
		if (inventory == null) {
			return;
		}
		if (!(inventory.getHolder() instanceof RPCHolder)) {
			return;
		}
		RPCHolder h = (RPCHolder) inventory.getHolder();
		int slot = e.getSlot();
		if (slot == s.getSize() - 9) {
			if (cc != null) {
				if (cc.isEditingFromSummary() || cc.isEditing()) {
					h.override();
					p.closeInventory();
					cc.returnToSummary();
					return;
				}
				cc.cancel();
			}
			h.override();
			p.closeInventory();
			return;
		}
		if (slot == s.getSize() - 1) {
			h.override();
			s.confirm(p, cc);
			return;
		}
		ItemStack clicked = e.getCurrentItem();
		if (clicked == null || clicked.getItemMeta() == null) {
			return;
		}
		NamespacedKey attrKey = new NamespacedKey(RPCharacters.plugin, "attr_id");
		NamespacedKey actionKey = new NamespacedKey(RPCharacters.plugin, "attr_action");
		String attr = clicked.getItemMeta().getPersistentDataContainer()
			.get(attrKey, PersistentDataType.STRING);
		String action = clicked.getItemMeta().getPersistentDataContainer()
			.get(actionKey, PersistentDataType.STRING);
		if (attr == null || action == null) {
			return;
		}
		boolean changed = false;
		if ("plus".equals(action)) {
			changed = s.tryIncrease(attr);
			if (!changed) {
				RPTexts.send(p, RPTexts.ERROR + "Cannot increase " + attr + ".");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
		} else if ("minus".equals(action)) {
			changed = s.tryDecrease(attr);
			if (!changed) {
				RPTexts.send(p, RPTexts.ERROR + "Cannot decrease " + attr + ".");
				p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
		} else {
			return;
		}
		p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
		InventoryManager inv = new InventoryManager();
		inv.attributesUpdate(e.getView().getTopInventory(), p, s, cc);
	}
	
	@EventHandler
	public void stopClose(InventoryCloseEvent e) {
		Player p = (Player) e.getPlayer();
		if(!(e.getInventory().getHolder() instanceof RPCHolder)) return;
		RPCHolder h = (RPCHolder) e.getInventory().getHolder();
		if(!activeCreators.containsKey(p)) {
			if(h.isOverridden()) return;
			if(h.getStage() == null) return;
			Stage stage = h.getStage();
			if(stage instanceof SelectionStage) {
			new BukkitRunnable()
			{
				public void run()
				{
					InventoryManager inv = new InventoryManager();
					inv.selectionView(p, (SelectionStage) stage, null);
				}
			}.runTaskLater(RPCharacters.plugin, 3L);
			} else if (stage instanceof AttributesStage) {
				new BukkitRunnable() {
					public void run() {
						InventoryManager inv = new InventoryManager();
						inv.attributesView(p, (AttributesStage) stage, null);
					}
				}.runTaskLater(RPCharacters.plugin, 3L);
			}
			return;
		}
		CharacterCreation cc = activeCreators.get(p);
		if (cc.isPreview()) {
			if (h.isOverridden()) {
				return;
			}
			cc.endPreview();
			return;
		}
		if (h.getStage() instanceof SummaryStage) {
			if (h.isOverridden()) {
				return;
			}
			new BukkitRunnable() {
				public void run() {
					if (activeCreators.containsKey(p)) {
						activeCreators.get(p).openSummary();
					}
				}
			}.runTaskLater(RPCharacters.plugin, 3L);
			return;
		}
		Stage activeStage = cc.getActiveStage();
		if(activeStage instanceof SelectionStage) {
			SelectionStage s = (SelectionStage) activeStage;
			if(!s.isActive()) return;
			if(h.isOverridden()) return;
			new BukkitRunnable()
			{
				public void run()
				{
					InventoryManager inv = new InventoryManager();
					inv.selectionView(p, s, cc);
				}
			}.runTaskLater(RPCharacters.plugin, 3L);
		} else if (activeStage instanceof AttributesStage) {
			AttributesStage s = (AttributesStage) activeStage;
			if (!s.isActive()) return;
			if (h.isOverridden()) return;
			new BukkitRunnable() {
				public void run() {
					InventoryManager inv = new InventoryManager();
					inv.attributesView(p, s, cc);
				}
			}.runTaskLater(RPCharacters.plugin, 3L);
		}
	}
}
