package net.tfminecraft.rpcharacters.objects;

import java.util.ArrayList;
import java.util.List;

import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Dependency;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;

public class SelectableItem {
	private boolean selected;
	private String name;
	private String type;
	private String id;
	private int cost;
	private AttributeData data;
	private Dependency dependency;
	
	private List<String> exclusive = new ArrayList<String>();
	
	public SelectableItem(Race r) {
		this.selected = false;
		this.name = r.getName();
		this.type = "race";
		this.id = r.getId();
		this.cost = 0;
		this.data = r.getRaceData().getAttributeData();
	}
	
	public SelectableItem(Trait t) {
		this.selected = false;
		this.name = t.getName();
		this.type = "trait";
		this.id = t.getId();
		this.cost = t.getTraitData().getCost();
		this.data = t.getTraitData().getAttributeData();
		if(t.getTraitData().hasDependency()) {
			this.dependency = t.getTraitData().getDependency();
		}
		if(t.getTraitData().hasExclusives()) {
			exclusive = t.getTraitData().getExclusive();
		}
	}

	public SelectableItem(PlayerClass playerClass) {
		this.selected = false;
		this.name = playerClass.getName();
		this.type = "class";
		this.id = playerClass.getId();
		this.cost = 0;
		this.data = new AttributeData();
	}
	
	public SelectableItem(SelectableItem another) {
		this.selected = another.isSelected();
		this.name = another.getName();
		this.type = another.getType();
		this.id = another.getId();
		this.cost = another.getCost();
		this.data = another.getAttributeData();
		if(another.hasDependency()) {
			this.dependency = another.getDependency();
		}
		if(another.hasExclusives()) {
			this.exclusive = another.getExclusives();
		}
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
	public boolean hasExclusives() {
		if(exclusive.size() > 0) return true;
		return false;
	}
	public boolean isExclusive(SelectableItem i) {
		for(String s : exclusive) {
			if(s.equalsIgnoreCase(i.getId())) return true;
		}
		return false;
	}
	public boolean isExclusive(String s) {
		if(exclusive.contains(s)) return true;
		return false;
	}
	public List<String> getExclusives(){
		return exclusive;
	}
	public int getCost() {
		return cost;
	}
	public void setSelected(boolean b) {
		selected = b;
	}
	public boolean isSelected() {
		return selected;
	}
	public void click(CharacterCreation cc) {
		if ("class".equalsIgnoreCase(type)) {
			this.selected = !this.selected;
			return;
		}
		this.selected = !this.selected;
		if(this.selected) {
			cc.getTempData().mergeFrom(data);
		} else {
			cc.getTempData().mergeFromReverse(data);
		}
	}
	public void click(RPCharacter c) {
		this.selected = !this.selected;
		if(this.selected) {
			c.addTrait(TraitLoader.getByString(id));
			c.getAttributeData().mergeFrom(data);
		} else {
			c.removeTrait(TraitLoader.getByString(id));
			c.getAttributeData().mergeFromReverse(data);
		}
	}
	
	public AttributeData getAttributeData() {
		return data;
	}
	public String getName() {
		return name;
	}

	public String getType() {
		return type;
	}

	public String getId() {
		return id;
	}
	
	
}
