package net.tfminecraft.rpcharacters.focus;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;

public final class FocusService {

    private final JavaPlugin plugin;
    private final FocusStore store;
    private final Map<UUID, FocusData> loaded = new ConcurrentHashMap<>();
    private BukkitTask regenTask;

    public FocusService(FocusStore store) {
        this(RPCharacters.plugin, store);
    }

    public FocusService(JavaPlugin plugin, FocusStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void restartRegen() {
        stopRegen();
        long interval = Math.max(1L, FocusConfig.regenIntervalTicks);
        regenTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickRegen();
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    public void start() {
        restartRegen();
        for (Player player : Bukkit.getOnlinePlayers()) {
            RPCharacter character = RPCharacters.getActiveCharacter(player);
            if (character != null) {
                activate(player, character);
            }
        }
    }

    public void shutdown() {
        stopRegen();
        saveAllOnline();
        loaded.clear();
    }

    public int getPoints(Player player) {
        FocusData data = dataFor(player);
        return data != null ? data.getPoints() : 0;
    }

    public boolean trySpend(Player player, int amount) {
        FocusData data = dataFor(player);
        return data != null && data.trySpend(amount);
    }

    public void grant(Player player, int amount) {
        FocusData data = dataFor(player);
        if (data != null) {
            data.grant(amount);
        }
    }

    public int getMax() {
        return FocusConfig.max;
    }

    public boolean restore(Player player) {
        FocusData data = dataFor(player);
        if (data == null) {
            return false;
        }
        data.setPoints(FocusConfig.max);
        return saveSafely(data);
    }

    public void activate(Player player, RPCharacter character) {
        if (player == null || character == null || character.getId() == null || character.getId().isBlank()) {
            return;
        }
        String characterId = character.getId();
        String owner = player.getUniqueId().toString();
        loaded.remove(player.getUniqueId());
        try {
            FocusData data = store.load(characterId);
            if (data == null) {
                data = store.migrateFromResearch(characterId, owner);
                if (data == null) data = FocusData.createNew(characterId, owner);
                store.save(data);
            } else {
                data.setOwnerUuid(owner);
            }
            applyOfflineRegen(player, data);
            loaded.put(player.getUniqueId(), data);
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Focus unavailable for character " + characterId + ": " + ex.getMessage());
        }
    }

    public void savePrevious(Player player, RPCharacter previous) {
        if (player == null || previous == null) {
            return;
        }
        FocusData data = loaded.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        if (!java.util.Objects.equals(previous.getId(), data.getCharacterId())) return;
        saveSafely(data);
    }

    public void deactivate(Player player) {
        if (player == null) {
            return;
        }
        FocusData data = loaded.remove(player.getUniqueId());
        if (data != null) {
            saveSafely(data);
        }
    }

    public void saveAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            FocusData data = loaded.get(player.getUniqueId());
            if (data != null) {
                saveSafely(data);
            }
        }
    }

    private boolean saveSafely(FocusData data) {
        try {
            store.save(data);
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().severe(ex.getMessage());
            return false;
        }
    }

    private FocusData dataFor(Player player) {
        return player == null ? null : loaded.get(player.getUniqueId());
    }

    private void applyOfflineRegen(Player player, FocusData data) {
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1L, FocusConfig.regenIntervalTicks) * 50L;
        if (FocusConfig.offlineRegen) {
            data.applyRegenForElapsed(hourlyRate(player), intervalMs, now);
        } else {
            data.setLastRegenMs(now);
        }
    }

    private void tickRegen() {
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1L, FocusConfig.regenIntervalTicks) * 50L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            FocusData data = loaded.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            data.applyRegenForElapsed(hourlyRate(player), intervalMs, now);
        }
    }

    private static double hourlyRate(Player player) {
        double hourly = FocusConfig.basePerHour;
        for (FocusConfig.RegenBonus bonus : FocusConfig.regenBonuses) {
            hourly += FocusAttributes.getTotal(player, bonus.mmocoreId) * bonus.extraPerHourPerPoint;
        }
        return hourly;
    }

    private void stopRegen() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
    }
}
