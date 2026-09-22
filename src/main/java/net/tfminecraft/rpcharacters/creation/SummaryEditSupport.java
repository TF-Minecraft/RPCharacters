package net.tfminecraft.rpcharacters.creation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.tfminecraft.rpcharacters.creation.stages.SummaryStage;
import net.tfminecraft.rpcharacters.loaders.StageLoader;

public final class SummaryEditSupport {

	private static final String SUMMARY_STAGE_ID = "creation_summary_stage";

	private SummaryEditSupport() {}

	public static SummaryStage getSummaryStage() {
		Stage template = StageLoader.getById(SUMMARY_STAGE_ID);
		if (template instanceof SummaryStage summary) {
			return summary;
		}
		return null;
	}

	public static Map<String, String> getEditEntries() {
		SummaryStage summary = getSummaryStage();
		if (summary == null) {
			return Collections.emptyMap();
		}
		return new LinkedHashMap<>(summary.getEntries());
	}

	public static List<String> getEditEntryKeys() {
		return new ArrayList<>(getEditEntries().keySet());
	}

	public static String resolveStageId(String entryKey) {
		if (entryKey == null || entryKey.isBlank()) {
			return null;
		}
		Map<String, String> entries = getEditEntries();
		if (entries.containsKey(entryKey)) {
			return entries.get(entryKey);
		}
		for (var entry : entries.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(entryKey)) {
				return entry.getValue();
			}
		}
		return null;
	}

	public static Stage resolveStageForEntry(String entryKey) {
		String stageId = resolveStageId(entryKey);
		if (stageId == null || stageId.equalsIgnoreCase("clues")) {
			return null;
		}
		return StageLoader.getById(stageId);
	}
}
