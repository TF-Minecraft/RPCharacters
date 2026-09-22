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
import net.tfminecraft.rpcharacters.creation.Stage;

public class StageLoader implements LoaderInterface{
	
	public static List<Stage> oList = new ArrayList<>();
	
	public static List<Stage> getNew(){
		List<Stage> newList = new ArrayList<>();
		for(Stage s : oList) {
			newList.add(Stage.another(s));
		}
		return newList;
	}

	public static Stage getById(String id) {
		for (Stage stage : oList) {
			if (stage.getId().equals(id)) {
				return stage;
			}
		}
		return null;
	}
	
	@Override
	public void load(File configFile) {
		oList.clear();
		
		FileConfiguration config = new YamlConfiguration();
        try {
        	config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }
        Set<String> set = config.getKeys(false);

		List<String> list = new ArrayList<String>(set);
		
		for(String key : list) {
			Stage o = Stage.create(key, config.getConfigurationSection(key));
			oList.add(o);
		}
	}
}
