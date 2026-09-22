package net.tfminecraft.rpcharacters.speechbubble;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

final class SpeechBubbleStack {

	private final UUID playerId;
	private final Deque<SpeechBubbleUtterance> utterances = new ArrayDeque<>();
	private final List<UUID> displayEntityIds = new ArrayList<>();

	SpeechBubbleStack(UUID playerId) {
		this.playerId = playerId;
	}

	UUID getPlayerId() {
		return playerId;
	}

	Deque<SpeechBubbleUtterance> getUtterances() {
		return utterances;
	}

	List<UUID> getDisplayEntityIds() {
		return displayEntityIds;
	}

	SpeechBubbleUtterance findUtterance(UUID utteranceId) {
		if (utteranceId == null) {
			return null;
		}
		for (SpeechBubbleUtterance utterance : utterances) {
			if (utterance.getId().equals(utteranceId)) {
				return utterance;
			}
		}
		return null;
	}

	void clear() {
		utterances.clear();
		displayEntityIds.clear();
	}

	List<LayoutLine> buildLayoutLines() {
		List<LayoutLine> layout = new ArrayList<>();
		for (SpeechBubbleUtterance utterance : utterances) {
			for (int i = 0; i < utterance.getLines().size(); i++) {
				layout.add(new LayoutLine(utterance, utterance.getLines().get(i), i));
			}
		}
		return layout;
	}

	static final class LayoutLine {
		private final SpeechBubbleUtterance utterance;
		private final String text;
		private final int lineIndex;

		LayoutLine(SpeechBubbleUtterance utterance, String text, int lineIndex) {
			this.utterance = utterance;
			this.text = text;
			this.lineIndex = lineIndex;
		}

		SpeechBubbleUtterance getUtterance() {
			return utterance;
		}

		UUID getUtteranceId() {
			return utterance.getId();
		}

		String getText() {
			return text;
		}

		int getLineIndex() {
			return lineIndex;
		}
	}
}
