package net.tfminecraft.rpcharacters.grave;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Pulls one insurance ticket out of a death snapshot, including tickets stored in a bundle.
 * The bundle stays where it was, with that one ticket removed.
 */
final class GraveInsuranceExtract {

	private static final int MAX_DEPTH = 16;

	private GraveInsuranceExtract() {}

	/**
	 * Searches storage, armor, offhand, then any extra stacks (kept items). The first
	 * ticket that is not bound to a grave still in the world is removed and returned
	 * with amount 1. {@code offhand} is a one-slot array so the caller sees a cleared slot.
	 */
	static ItemStack pull(ItemStack[] storage, ItemStack[] armor, ItemStack[] offhand,
			Iterable<ItemStack> extra) {
		if (!GraveLoader.isInsuranceEnabled()) {
			return null;
		}
		Items items = new Items();
		ItemStack taken = pullSlots(storage, items);
		if (taken != null) {
			return taken;
		}
		taken = pullSlots(armor, items);
		if (taken != null) {
			return taken;
		}
		taken = pullSlots(offhand, items);
		if (taken != null) {
			return taken;
		}
		if (extra == null) {
			return null;
		}
		for (ItemStack stack : extra) {
			taken = pullOne(stack, items, ignored -> { });
			if (taken != null) {
				return taken;
			}
		}
		return null;
	}

	static <T> T pullSlots(T[] slots, Stacks<T> stacks) {
		if (slots == null) {
			return null;
		}
		for (int i = 0; i < slots.length; i++) {
			int slot = i;
			T taken = pullOne(slots[i], stacks, remainder -> slots[slot] = remainder);
			if (taken != null) {
				return taken;
			}
		}
		return null;
	}

	private static <T> T pullOne(T item, Stacks<T> stacks, Consumer<T> replace) {
		Take<T> taken = take(item, stacks, 0);
		if (taken == null) {
			return null;
		}
		replace.accept(taken.remainder);
		return taken.ticket;
	}

	private static <T> Take<T> take(T item, Stacks<T> stacks, int depth) {
		if (item == null || stacks.blank(item) || depth > MAX_DEPTH) {
			return null;
		}
		if (stacks.insurance(item)) {
			if (stacks.keepBinding(item)) {
				return null;
			}
			T ticket = stacks.one(item);
			if (stacks.amount(item) <= 1) {
				return new Take<>(ticket, null);
			}
			stacks.setAmount(item, stacks.amount(item) - 1);
			return new Take<>(ticket, item);
		}
		if (!stacks.bundle(item)) {
			return null;
		}
		List<T> contents = new ArrayList<>(stacks.contents(item));
		for (int i = 0; i < contents.size(); i++) {
			Take<T> inner = take(contents.get(i), stacks, depth + 1);
			if (inner == null) {
				continue;
			}
			if (inner.remainder == null) {
				contents.remove(i);
			} else {
				contents.set(i, inner.remainder);
			}
			stacks.setContents(item, contents);
			return new Take<>(inner.ticket, item);
		}
		return null;
	}

	private record Take<T>(T ticket, T remainder) {}

	interface Stacks<T> {
		boolean blank(T item);

		int amount(T item);

		void setAmount(T item, int amount);

		boolean insurance(T item);

		/** True when this ticket is already bound to a grave that still exists. */
		boolean keepBinding(T item);

		boolean bundle(T item);

		List<T> contents(T bundle);

		void setContents(T bundle, List<T> contents);

		T one(T item);
	}

	private static final class Items implements Stacks<ItemStack> {
		@Override
		public boolean blank(ItemStack item) {
			return Grave.isBlank(item);
		}

		@Override
		public int amount(ItemStack item) {
			return item.getAmount();
		}

		@Override
		public void setAmount(ItemStack item, int amount) {
			item.setAmount(amount);
		}

		@Override
		public boolean insurance(ItemStack item) {
			return GraveLoader.isInsuranceItem(item);
		}

		@Override
		public boolean keepBinding(ItemStack item) {
			return GraveInsuranceTickets.boundToLiveGrave(item);
		}

		@Override
		public boolean bundle(ItemStack item) {
			return item.getItemMeta() instanceof BundleMeta;
		}

		@Override
		public List<ItemStack> contents(ItemStack bundle) {
			ItemMeta meta = bundle.getItemMeta();
			if (!(meta instanceof BundleMeta bundleMeta) || !bundleMeta.hasItems()) {
				return List.of();
			}
			return bundleMeta.getItems();
		}

		@Override
		public void setContents(ItemStack bundle, List<ItemStack> contents) {
			BundleMeta bundleMeta = (BundleMeta) bundle.getItemMeta();
			List<ItemStack> kept = new ArrayList<>();
			if (contents != null) {
				for (ItemStack inner : contents) {
					if (!Grave.isBlank(inner)) {
						kept.add(inner);
					}
				}
			}
			bundleMeta.setItems(kept.isEmpty() ? null : kept);
			bundle.setItemMeta(bundleMeta);
		}

		@Override
		public ItemStack one(ItemStack item) {
			ItemStack single = item.clone();
			single.setAmount(1);
			return single;
		}
	}
}
