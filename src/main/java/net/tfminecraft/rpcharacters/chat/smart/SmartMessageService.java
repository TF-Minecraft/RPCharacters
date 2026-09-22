package net.tfminecraft.rpcharacters.chat.smart;

import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.loaders.SmartMessageLoader;
import net.tfminecraft.rpcharacters.loaders.SpeechBubbleLoader;
import net.tfminecraft.rpcharacters.chat.CharacterChatEvent;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.chat.ChatChannelPreferenceManager;
import net.tfminecraft.rpcharacters.chat.ChatFormatter;
import net.tfminecraft.rpcharacters.speechbubble.fake.FakeBubbleManager;
import net.tfminecraft.rpcharacters.speechbubble.fake.ProtocolLibBridge;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class SmartMessageService {

	private SmartMessageService() {}

	public static void deliver(CharacterChatEvent event, ChatChannel channel, Player sender) {
		SmartMessageSettings settings = SmartMessageLoader.getSettings();
		Set<Player> recipients = event.getRecipients();
		String message = event.getMessage();
		String displayName = event.getDisplayName();
		String channelId = event.getChannel();

		UUID utteranceId = UUID.randomUUID();
		long expiresAt = System.currentTimeMillis()
				+ (SpeechBubbleLoader.getSettings().getUtteranceTimeoutSeconds() * 1000L);

		if (SmartMessageDebug.isEnabled()) {
			SmartMessageDebug.log("deliver-start",
					"speaker=" + label(sender)
							+ ", channel=" + channelId
							+ ", utterance=" + utteranceId
							+ ", candidates=" + recipients.size()
							+ ", message=" + preview(message));
		}

		int heardCount = 0;
		for (Player recipient : recipients) {
			if (recipient == null || !recipient.isOnline()) {
				if (SmartMessageDebug.isEnabled()) {
					SmartMessageDebug.logSkip("recipient", "offline/null");
				}
				continue;
			}
			if (!ChatChannelPreferenceManager.get().isChannelVisible(recipient, channelId)) {
				if (SmartMessageDebug.isEnabled()) {
					SmartMessageDebug.logSkip("recipient",
							label(recipient) + " channel toggled off");
				}
				continue;
			}

			SpeechPowerSnapshot powerSnapshot = SpeechPowerCalculator.evaluate(sender, recipient, channel, settings);
			double rawPower = powerSnapshot.getIntelligibility();
			double power = rawPower;
			if (channel.getRange() > 0 && settings.isCharismaHearingEnabled()) {
				int charisma = CharismaHearingCalculator.getCharisma(recipient, settings);
				power = CharismaHearingCalculator.applyBoost(recipient, rawPower, settings);
				if (power != rawPower) {
					powerSnapshot = powerSnapshot.withCharismaBoost(power, charisma, power - rawPower);
				}
			}
			if (power < settings.getMinAudible()) {
				if (SmartMessageDebug.isEnabled()) {
					SmartMessageDebug.logSkip("recipient",
							label(recipient) + " below min-audible (" + settings.getMinAudible() + ")"
									+ " | " + powerSnapshot.formatBreakdown());
				}
				continue;
			}

			MuffleOutcome outcome = MuffleEngine.applyDetailed(message, power, utteranceId, recipient.getUniqueId(),
					settings);
			if (!outcome.isAudible()) {
				if (SmartMessageDebug.isEnabled()) {
					SmartMessageDebug.logSkip("recipient",
							label(recipient) + " muffle stage=" + outcome.getStage()
									+ " | " + powerSnapshot.formatBreakdown());
				}
				continue;
			}

			if (outcome.hidesSender() && PlaceholderSuppressionResolver.shouldSuppress(recipient, settings)) {
				if (SmartMessageDebug.isEnabled()) {
					SmartMessageDebug.logSkip("recipient",
							label(recipient) + " placeholder-suppressed-active-conversation"
									+ " sessions=" + PlaceholderSuppressionResolver.activeSessionCount(recipient)
									+ " | " + powerSnapshot.formatBreakdown());
				}
				continue;
			}

			String heard = outcome.getText();
			boolean anonymize = !outcome.hidesSender()
					&& AnonymousMuffledVoiceResolver.shouldAnonymize(sender, recipient, channel, power, settings);
			String nameForFormat = AnonymousMuffledVoiceResolver.resolveDisplayName(displayName, anonymize, settings);
			String formatted = outcome.hidesSender()
					? heard
					: ChatFormatter.format(channel, sender, nameForFormat, heard);
			if (formatted.isEmpty()) {
				if (SmartMessageDebug.isEnabled()) {
					SmartMessageDebug.logSkip("recipient", label(recipient) + " empty formatted message");
				}
				continue;
			}

			RPTexts.send(recipient, formatted);
			heardCount++;

			boolean bubble = channel.hasSpeechBubble() && ProtocolLibBridge.isReady();
			if (bubble) {
				FakeBubbleManager.get().show(recipient, sender, utteranceId, heard, channel, expiresAt);
			}

			if (SmartMessageDebug.isEnabled()) {
				SmartMessageDebug.log("recipient",
						label(recipient)
								+ " | " + powerSnapshot.formatBreakdown()
								+ " | muffle=" + outcome.getStage()
								+ " | in=" + preview(message)
								+ " | out=" + preview(heard)
								+ " | chat=" + (outcome.hidesSender() ? "anonymous" : anonymize ? "anonymous-muffled" : "formatted")
								+ " | bubble=" + bubble);
			}
		}

		if (heardCount == 0) {
			RPTexts.send(sender, RPTexts.ERROR + "No one can hear you in this channel.");
			if (SmartMessageDebug.isEnabled()) {
				SmartMessageDebug.log("deliver-end", "heardCount=0");
			}
		} else if (SmartMessageDebug.isEnabled()) {
			SmartMessageDebug.log("deliver-end", "heardCount=" + heardCount);
		}
	}

	private static String label(Player player) {
		if (player == null) {
			return "null";
		}
		String name = player.getName();
		if (name != null && !name.isBlank()) {
			return name;
		}
		return player.getUniqueId().toString().substring(0, 8);
	}

	private static String preview(String text) {
		if (text == null) {
			return "null";
		}
		String plain = text.replace('§', '&');
		if (plain.length() <= 48) {
			return "\"" + plain + "\"";
		}
		return "\"" + plain.substring(0, 45) + "...\"";
	}
}
