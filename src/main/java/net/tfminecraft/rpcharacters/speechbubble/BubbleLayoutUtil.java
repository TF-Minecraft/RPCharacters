package net.tfminecraft.rpcharacters.speechbubble;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class BubbleLayoutUtil {

	private BubbleLayoutUtil() {}

	public static Location desiredLineLocation(Player player, int stackIndex, SpeechBubbleSettings settings,
			long tickCounter) {
		double bob = settings.getBobAmplitude()
				* Math.sin((tickCounter + stackIndex * 8.0) / Math.max(1, settings.getBobPeriodTicks()));
		double y = settings.getHeightAboveHead()
				+ settings.getFirstLineOffset()
				+ (stackIndex * settings.getLineSpacing())
				+ bob;
		Location loc = player.getLocation().clone();
		loc.setY(loc.getY() + y);
		return loc;
	}
}
