package net.tfminecraft.rpcharacters.grave;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

/**
 * Binds an insurance ticket to the grave made when its holder died. Only the bound
 * ticket can recover that grave.
 */
final class GraveInsuranceTickets {

	/**
	 * @param bound whether a ticket was bound to the grave
	 * @param split the bound ticket when it was split off a stack and must be handed back separately
	 */
	record Binding(boolean bound, ItemStack split) {
		static final Binding NONE = new Binding(false, null);
	}

	private GraveInsuranceTickets() {}

	/** The stash slot that {@link #bind} would use, or null when no loose ticket can be bound. */
	static Integer bindableSlot(Map<Integer, ItemStack> stash) {
		if (stash == null || stash.isEmpty()) {
			return null;
		}
		List<Integer> slots = new ArrayList<>(stash.keySet());
		slots.sort(null);
		return pickSlot(slots, slot -> GraveLoader.isInsuranceItem(stash.get(slot)),
				slot -> boundToLiveGrave(stash.get(slot)));
	}

	/** Tags one already-separated ticket and returns the bound copy. */
	static ItemStack bindTicket(ItemStack ticket, Grave grave) {
		if (Grave.isBlank(ticket) || grave == null) {
			return null;
		}
		ItemStack single = ticket.clone();
		single.setAmount(1);
		return tag(single, grave);
	}

	/**
	 * Binds one ticket from the death stash to {@code grave}. A stacked ticket is split:
	 * the rest of the stack stays in its slot and the bound ticket is returned in
	 * {@link Binding#split()}.
	 */
	static Binding bind(Map<Integer, ItemStack> stash, Grave grave) {
		if (stash == null || stash.isEmpty() || grave == null) {
			return Binding.NONE;
		}
		Integer slot = bindableSlot(stash);
		if (slot == null) {
			return Binding.NONE;
		}
		ItemStack ticket = stash.get(slot);
		if (ticket.getAmount() <= 1) {
			stash.put(slot, tag(ticket, grave));
			return new Binding(true, null);
		}
		ItemStack rest = ticket.clone();
		rest.setAmount(ticket.getAmount() - 1);
		stash.put(slot, rest);
		ItemStack single = ticket.clone();
		single.setAmount(1);
		return new Binding(true, tag(single, grave));
	}

	/**
	 * Picks the ticket slot to bind: the first ticket that is not bound to a grave that
	 * still exists. Tickets bound to a live grave keep their binding.
	 */
	static Integer pickSlot(List<Integer> slots, Predicate<Integer> isTicket, Predicate<Integer> boundToLiveGrave) {
		for (Integer slot : slots) {
			if (isTicket.test(slot) && !boundToLiveGrave.test(slot)) {
				return slot;
			}
		}
		return null;
	}

	static UUID boundGrave(ItemStack item) {
		if (Grave.isBlank(item) || !item.hasItemMeta()) {
			return null;
		}
		String raw = item.getItemMeta().getPersistentDataContainer()
				.get(GraveKeys.insuranceGraveId(), GraveKeys.GRAVE_ID_TYPE);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Removes the binding and its lore line so the ticket stacks with unbound ones again. */
	static void unbind(ItemStack item) {
		if (Grave.isBlank(item) || !item.hasItemMeta()) {
			return;
		}
		ItemMeta meta = item.getItemMeta();
		PersistentDataContainer pdc = meta.getPersistentDataContainer();
		String loreLine = pdc.get(GraveKeys.insuranceLore(), GraveKeys.GRAVE_ID_TYPE);
		pdc.remove(GraveKeys.insuranceGraveId());
		pdc.remove(GraveKeys.insuranceLore());
		if (loreLine != null && meta.hasLore()) {
			List<String> lore = new ArrayList<>(meta.getLore());
			lore.remove(loreLine);
			meta.setLore(lore.isEmpty() ? null : lore);
		}
		item.setItemMeta(meta);
	}

	static boolean boundToLiveGrave(ItemStack item) {
		UUID id = boundGrave(item);
		return id != null && GraveManager.get().getById(id) != null;
	}

	private static ItemStack tag(ItemStack ticket, Grave grave) {
		ItemStack tagged = ticket.clone();
		unbind(tagged);
		ItemMeta meta = tagged.getItemMeta();
		if (meta == null) {
			return tagged;
		}
		PersistentDataContainer pdc = meta.getPersistentDataContainer();
		pdc.set(GraveKeys.insuranceGraveId(), GraveKeys.GRAVE_ID_TYPE, grave.getId().toString());
		String loreLine = boundLore(grave.getBlockLocation());
		if (loreLine != null) {
			List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
			lore.add(loreLine);
			meta.setLore(lore);
			pdc.set(GraveKeys.insuranceLore(), GraveKeys.GRAVE_ID_TYPE, loreLine);
		}
		tagged.setItemMeta(meta);
		return tagged;
	}

	private static String boundLore(Location location) {
		String template = GraveLoader.getInsuranceBoundLore();
		if (template == null || template.isBlank() || location == null) {
			return null;
		}
		String world = location.getWorld() != null ? location.getWorld().getName() : "unknown";
		String text = template
				.replace("{x}", Integer.toString(location.getBlockX()))
				.replace("{y}", Integer.toString(location.getBlockY()))
				.replace("{z}", Integer.toString(location.getBlockZ()))
				.replace("{world}", world);
		return StringFormatter.formatHex(text.replace('&', '§'));
	}
}
