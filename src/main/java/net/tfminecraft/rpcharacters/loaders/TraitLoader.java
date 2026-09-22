package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.objects.trait.Trait;

public class TraitLoader implements LoaderInterface{
	
	public static List<Trait> oList = new ArrayList<>();
	
	public static List<Trait> get(){
		return oList;
	}
	
	@Override
	public void load(File configFile) {
		
		FileConfiguration config = new YamlConfiguration();
        try {
        	config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }
        Set<String> set = config.getKeys(false);

		List<String> list = new ArrayList<String>(set);
		
		for(String key : list) {
			Trait o = new Trait(key, config.getConfigurationSection(key));
			oList.add(o);
		}
	}

	public static Trait getByString(String id) {
		for(Trait t : oList) {
			if(t.getId().equalsIgnoreCase(id)) return t;
		}
		return null;
	}

}
