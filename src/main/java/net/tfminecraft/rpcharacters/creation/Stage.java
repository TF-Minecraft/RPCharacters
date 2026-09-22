package net.tfminecraft.rpcharacters.creation;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.rpcharacters.creation.stages.AttributesStage;
import net.tfminecraft.rpcharacters.creation.stages.ClueStage;
import net.tfminecraft.rpcharacters.creation.stages.InfoStage;
import net.tfminecraft.rpcharacters.creation.stages.QuestionStage;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.creation.stages.SetterStage;
import net.tfminecraft.rpcharacters.creation.stages.SummaryStage;
import net.tfminecraft.rpcharacters.creation.stages.WardrobeStage;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.utils.DurationParser;
import net.tfminecraft.rpcharacters.enums.StageType;

public class Stage {
	private String id;
	
	private boolean repeat;
	
	private boolean autoNext;
	
	private boolean cancelled;
	
	private Dependency dependency;

	private long lockTimeMs = -1L;

	/** Inclusive minimum account-age hours, or null if unset. */
	private Integer requireAccountAgeHoursMin;
	/** Exclusive maximum account-age hours, or null if unset. */
	private Integer requireAccountAgeHoursMax;

	/** Creation client: both (default), web-only, or game-only. */
	private String platform = "both";

	public long getLockTimeMs() {
		return lockTimeMs;
	}

	public void setLockTimeMs(long lockTimeMs) {
		this.lockTimeMs = lockTimeMs;
	}

	public Integer getRequireAccountAgeHoursMin() {
		return requireAccountAgeHoursMin;
	}

	public void setRequireAccountAgeHoursMin(Integer requireAccountAgeHoursMin) {
		this.requireAccountAgeHoursMin = requireAccountAgeHoursMin;
	}

	public Integer getRequireAccountAgeHoursMax() {
		return requireAccountAgeHoursMax;
	}

	public void setRequireAccountAgeHoursMax(Integer requireAccountAgeHoursMax) {
		this.requireAccountAgeHoursMax = requireAccountAgeHoursMax;
	}

	public String getPlatform() {
		return platform != null && !platform.isBlank() ? platform : "both";
	}

	public void setPlatform(String platform) {
		if (platform == null || platform.isBlank()) {
			this.platform = "both";
			return;
		}
		String p = platform.trim().toLowerCase();
		if (!p.equals("web") && !p.equals("game") && !p.equals("both")) {
			p = "both";
		}
		this.platform = p;
	}

	/** True if this stage should run during in-game creation. */
	public boolean runsInGame() {
		String p = getPlatform();
		return p.equals("both") || p.equals("game");
	}

	/** True if this stage should run on the website wizard. */
	public boolean runsOnWeb() {
		String p = getPlatform();
		return p.equals("both") || p.equals("web");
	}

	/**
	 * @return true if the player passes any configured account-age min/max gate.
	 */
	public boolean passesAccountAgeGate(PlayerData pd) {
		int ageSec = pd == null ? 0 : pd.getAgeSeconds();
		if (requireAccountAgeHoursMin != null
				&& ageSec < requireAccountAgeHoursMin * 3600L) {
			return false;
		}
		if (requireAccountAgeHoursMax != null
				&& ageSec >= requireAccountAgeHoursMax * 3600L) {
			return false;
		}
		return true;
	}

	protected void copyBaseFields(Stage source) {
		setId(source.getId());
		setRepeat(source.shouldRepeat());
		setAutoNext(source.autoNext());
		setCancelled(source.isCancelled());
		if (source.hasDependency()) {
			setDependency(source.getDependency());
		} else {
			setDependency(null);
		}
		setLockTimeMs(source.getLockTimeMs());
		setRequireAccountAgeHoursMin(source.getRequireAccountAgeHoursMin());
		setRequireAccountAgeHoursMax(source.getRequireAccountAgeHoursMax());
		setPlatform(source.getPlatform());
	}

	public boolean autoNext() {
		return autoNext;
	}
	
	public boolean shouldRepeat() {
		return repeat;
	}
	
	public void setRepeat(boolean b) {
		this.repeat = b;
	}
	
	public void setAutoNext(boolean b) {
		this.autoNext = b;
	}
	
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	
	public boolean hasDependency() {
		if(dependency != null) return true;
		return false;
	}
	
	public Dependency getDependency() {
		return dependency;
	}

	public void setDependency(Dependency dependency) {
		this.dependency = dependency;
	}

	public static Stage create(String id, ConfigurationSection config) {
		Stage s = new Stage();
		s.setId(id);
		if(config.contains("repeat")) {
			s.setRepeat(config.getBoolean("repeat"));
		} else {
			s.setRepeat(true);
		}
		if(config.contains("auto-next")) {
			s.setAutoNext(config.getBoolean("auto-next"));
		} else {
			s.setAutoNext(true);
		}
		if(config.contains("dependency")) {
			s.setDependency(new Dependency(config.getConfigurationSection("dependency")));
		}
		s.setLockTimeMs(DurationParser.parseLockTimeMs(config.getString("lock-time", "-1")));
		if (config.contains("require-account-age-hours-min")) {
			s.setRequireAccountAgeHoursMin(config.getInt("require-account-age-hours-min"));
		}
		if (config.contains("require-account-age-hours-max")) {
			s.setRequireAccountAgeHoursMax(config.getInt("require-account-age-hours-max"));
		}
		if (config.contains("platform")) {
			s.setPlatform(config.getString("platform"));
		}
		StageType type = StageType.valueOf(config.getString("type").toUpperCase());
		s.setCancelled(false);
		if(type.equals(StageType.INFO)) {
			return new InfoStage(s, config);
		} else if(type.equals(StageType.QUESTIONS)) {
			return new QuestionStage(s, config);
		} else if(type.equals(StageType.SETTER)) {
			return new SetterStage(s, config);
		} else if(type.equals(StageType.SELECTION)) {
			return new SelectionStage(s, config);
		} else if(type.equals(StageType.ATTRIBUTES)) {
			return new AttributesStage(s, config);
		} else if(type.equals(StageType.CLUE)) {
			return new ClueStage(s, config);
		} else if(type.equals(StageType.SUMMARY)) {
			return new SummaryStage(s, config);
		} else if(type.equals(StageType.WARDROBE)) {
			return new WardrobeStage(s, config);
		}
		return s;
	}
	
	public static Stage another(Stage another) {
		if(another instanceof InfoStage) {
			InfoStage s = (InfoStage) another;
			return new InfoStage(s);
		} else if(another instanceof QuestionStage) {
			QuestionStage s = (QuestionStage) another;
			return new QuestionStage(s);
		} else if(another instanceof SetterStage) {
			SetterStage s = (SetterStage) another;
			return new SetterStage(s);
		} else if(another instanceof SelectionStage) {
			SelectionStage s = (SelectionStage) another;
			return new SelectionStage(s);
		} else if(another instanceof AttributesStage) {
			AttributesStage s = (AttributesStage) another;
			return new AttributesStage(s);
		} else if(another instanceof ClueStage) {
			ClueStage s = (ClueStage) another;
			return new ClueStage(s);
		} else if(another instanceof SummaryStage) {
			SummaryStage s = (SummaryStage) another;
			return new SummaryStage(s);
		} else if(another instanceof WardrobeStage) {
			WardrobeStage s = (WardrobeStage) another;
			return new WardrobeStage(s);
		}
		return null;
	}

	public void cancel() {
		cancelled = true;
	}
	public void update(PlayerData pd) {

	}
	public void setCancelled(boolean b) {
		this.cancelled = b;
	}
	
	public boolean isCancelled() {
		return cancelled;
	}
}
