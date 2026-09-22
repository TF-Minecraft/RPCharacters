package net.tfminecraft.rpcharacters.creation.stages;



import java.util.ArrayList;

import java.util.List;



import org.bukkit.configuration.ConfigurationSection;

import org.bukkit.entity.Player;

import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;



import net.tfminecraft.rpcharacters.RPCharacters;

import net.tfminecraft.rpcharacters.creation.CharacterCreation;

import net.tfminecraft.rpcharacters.creation.Stage;

import net.tfminecraft.rpcharacters.utils.RPTexts;



public class InfoStage extends Stage{

	

	private int interval;
	private BukkitTask messageTask;

	

	private List<String> messages = new ArrayList<>();

	/** Optional website copy; in-game always uses {@link #messages}. */
	private List<String> webMessages = new ArrayList<>();

	

	public InfoStage(Stage s, ConfigurationSection config) {

		copyBaseFields(s);

		this.interval = config.getInt("interval");

		this.messages = config.getStringList("messages");

		if (config.contains("web-messages")) {

			this.webMessages = config.getStringList("web-messages");

		}

	}

	public InfoStage(InfoStage another) {

		copyBaseFields(another);

		this.interval = another.getInterval();

		this.messages = another.getMessages();

		this.webMessages = new ArrayList<>(another.getWebMessages());

	}

	public int getInterval() {

		return interval;

	}



	public List<String> getMessages() {

		return messages;

	}



	public List<String> getWebMessages() {

		return webMessages;

	}



	public boolean hasWebMessages() {

		return webMessages != null && !webMessages.isEmpty();

	}



	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	public void runMessage(Player p, String message) {

		String substituted = substitutePlaceholders(message);

		String type = substituted.split("\\(")[0];

		String info = RPTexts.formatGui(substituted.split("\\(")[1].replace(")", ""));

		if(type.equalsIgnoreCase("title")) {

			p.sendTitle(info, " ", 5, interval-10, 5);
			p.sendMessage(info);

		} else if(type.equalsIgnoreCase("subtitle")) {

			p.sendTitle(" ", info, 5, interval-10, 5);
			p.sendMessage(info);

		} else if(type.equalsIgnoreCase("chat")) {

			p.sendMessage(info);

		}

	}

	public static String substitutePlaceholders(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.replace("{hours}", String.valueOf(
			net.tfminecraft.rpcharacters.Cache.evilMinAccountAgeHours));
	}

	public void execute(Player p, CharacterCreation cc) {
		stopMessages();
		if (cc.isCancelled()) return;
		cc.setCanNext(true);

		messageTask = new BukkitRunnable()

		{

			int i = 0;

			public void run()

			{

				if(InfoStage.this.isCancelled() || cc.isCancelled() || cc.getActiveStage() != InfoStage.this) {

					this.cancel();
					return;

				}

				if(i >= messages.size()) {

					this.cancel();

					if(autoNext()) {

						cc.runStage();

					} else {

						cc.setCanNext(true);

					}

				} else {

					runMessage(p, messages.get(i));

					i++;

				}

			}

		}.runTaskTimer(RPCharacters.plugin, 0L, interval*1L);

	}

	public void stopMessages() {
		if (messageTask != null) {
			messageTask.cancel();
			messageTask = null;
		}
	}

}
