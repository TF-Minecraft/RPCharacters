package net.tfminecraft.rpcharacters.api;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.identity.MaskService;
import net.tfminecraft.rpcharacters.wardrobe.SkinTextures;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeCache;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeSlotData;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeSnapshot;

/**
 * Single place for RP character player heads (wardrobe textures, then owner / Steve).
 */
public final class CharacterSkull {

	private CharacterSkull() {}

	public static ItemStack steveHead() {
		return new ItemStack(Material.PLAYER_HEAD);
	}

	public static ItemStack ofOwner(Player player) {
		ItemStack head = steveHead();
		if (player == null) {
			return head;
		}
		if (!(head.getItemMeta() instanceof SkullMeta meta)) {
			return head;
		}
		meta.setOwningPlayer(player);
		head.setItemMeta(meta);
		return head;
	}

	public static ItemStack fromTextures(String textureValue, String textureSignature) {
		if (textureValue == null || textureValue.isBlank()) {
			return steveHead();
		}
		ItemStack head = steveHead();
		applyTextures(head, textureValue, textureSignature);
		return head;
	}

	public static ItemStack of(RPCharacter character) {
		if (character == null) {
			return steveHead();
		}
		Player owner = character.getOwner();
		WardrobeSnapshot snapshot = owner != null ? WardrobeCache.get(owner) : null;
		boolean snapshotForThis = snapshot != null
			&& character.getId() != null
			&& character.getId().equalsIgnoreCase(snapshot.getCharacterId());

		if (snapshotForThis && Boolean.TRUE.equals(character.isActive())
				&& owner != null && owner.isOnline()) {
			ItemStack live = fromLiveAppearance(owner, snapshot);
			if (live != null) {
				return live;
			}
		}

		if (snapshotForThis) {
			ItemStack base = fromSlot(snapshot.getSlot(WardrobeSnapshot.SLOT_BASE));
			if (base != null) {
				return base;
			}
		}

		if (Boolean.TRUE.equals(character.isActive()) && owner != null) {
			SkinTextures last = WardrobeCache.getLastApplied(owner);
			if (last != null && last.isValid()) {
				return fromTextures(last.getValue(), last.getSignature());
			}
		}

		return ofOwner(owner);
	}

	public static ItemStack ofActive(Player player) {
		if (player == null) {
			return steveHead();
		}
		RPCharacter active = RPCharacters.getActiveCharacter(player);
		if (active != null) {
			return of(active);
		}
		return ofOwner(player);
	}

	public static void applyTextures(ItemStack head, String textureValue, String textureSignature) {
		if (head == null || textureValue == null || textureValue.isEmpty()) {
			return;
		}
		if (!(head.getItemMeta() instanceof SkullMeta meta)) {
			return;
		}
		try {
			Class<?> profileClass = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
			Class<?> propertyClass = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
			Method createProfile = Bukkit.getServer().getClass().getMethod("createProfile", UUID.class);
			Object profile = createProfile.invoke(Bukkit.getServer(), UUID.randomUUID());
			Constructor<?> propertyCtor = propertyClass.getConstructor(String.class, String.class, String.class);
			String signature = textureSignature == null ? "" : textureSignature;
			Object property = propertyCtor.newInstance("textures", textureValue, signature);
			try {
				profile.getClass().getMethod("setProperty", propertyClass).invoke(profile, property);
			} catch (NoSuchMethodException missingSet) {
				profile.getClass().getMethod("setProperty", String.class, propertyClass)
					.invoke(profile, "textures", property);
			}
			Method setProfile = meta.getClass().getMethod("setPlayerProfile", profileClass);
			setProfile.invoke(meta, profile);
			head.setItemMeta(meta);
		} catch (Throwable error) {
			if (RPCharacters.plugin != null) {
				RPCharacters.plugin.getLogger().log(
					Level.FINE,
					"Could not apply skull texture (value length="
						+ textureValue.length()
						+ "): "
						+ error.getMessage());
			}
		}
	}

	private static ItemStack fromLiveAppearance(Player player, WardrobeSnapshot snapshot) {
		if (MaskService.isMasked(player)) {
			ItemStack masked = fromApplyableSlot(snapshot.getSlot(WardrobeSnapshot.SLOT_MASKED));
			if (masked != null) {
				return masked;
			}
		}
		String active = snapshot.getActiveSlot();
		if (active != null && !active.isBlank()) {
			ItemStack slot = fromApplyableSlot(snapshot.getSlot(active));
			if (slot != null) {
				return slot;
			}
		}
		SkinTextures last = WardrobeCache.getLastApplied(player);
		if (last != null && last.isValid()) {
			return fromTextures(last.getValue(), last.getSignature());
		}
		SkinTextures account = WardrobeCache.getAccountSkin(player);
		if (account != null && account.isValid()) {
			return fromTextures(account.getValue(), account.getSignature());
		}
		return null;
	}

	private static ItemStack fromApplyableSlot(WardrobeSlotData slot) {
		if (slot == null || !slot.canApply()) {
			return null;
		}
		return fromTextures(slot.getTextureValue(), slot.getTextureSignature());
	}

	private static ItemStack fromSlot(WardrobeSlotData slot) {
		if (slot == null || slot.getTextureValue() == null || slot.getTextureValue().isBlank()) {
			return null;
		}
		return fromTextures(slot.getTextureValue(), slot.getTextureSignature());
	}
}
