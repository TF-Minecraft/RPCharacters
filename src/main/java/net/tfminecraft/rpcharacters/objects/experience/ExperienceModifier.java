package net.tfminecraft.rpcharacters.objects.experience;

import org.bukkit.configuration.ConfigurationSection;

import io.lumine.mythic.bukkit.utils.lib.lang3.text.WordUtils;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

public class ExperienceModifier {
    private String profession;
    private String alias;
    private int modifier;

    public ExperienceModifier(String key, ConfigurationSection config) {
        profession = key;
        alias = StringFormatter.formatHex(config.getString("alias", WordUtils.capitalize(new String(key))));
        modifier = config.getInt("modifier", 10);
    }

    public ExperienceModifier(ExperienceModifier another, int change) {
        profession = another.getProfession();
        alias = another.getAlias();
        modifier = another.getModifier()+change;
    }

    public ExperienceModifier(String p, String a, int m) {
        profession = p;
        alias = StringFormatter.formatHex(a);
        modifier = m;
    }

    public String getProfession() {
        return profession;
    }

    public String getAlias() {
        return alias;
    }

    public void modify(int change) {
        modifier += change;
    }

    public int getModifier() {
        return modifier;
    }

    public double getFactor() {
        double factor = 1 + (modifier/100.0);
        if(factor < 0) factor = 0;
        return factor;
    }
}
