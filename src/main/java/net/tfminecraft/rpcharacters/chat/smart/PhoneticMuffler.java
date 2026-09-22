package net.tfminecraft.rpcharacters.chat.smart;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phonetic-ish muffling: similar sound swaps, nasal (m/n) smearing, and {@code ...} breaks.
 */
final class PhoneticMuffler {

	private static final String ELLIPSIS = "...";
	private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+|[^A-Za-z]+");

	private PhoneticMuffler() {}

	static String muffle(String message, double intelligibility, Random random) {
		double distortion = 1.0 - intelligibility;
		if (distortion < 0.05 || message == null || message.isEmpty()) {
			return message;
		}

		Matcher matcher = WORD_PATTERN.matcher(message);
		StringBuilder output = new StringBuilder(message.length() + 16);
		boolean changed = false;

		while (matcher.find()) {
			String token = matcher.group();
			if (!isWord(token)) {
				output.append(token);
				continue;
			}
			String muffled = muffleWord(token, distortion, random);
			if (!muffled.equals(token)) {
				changed = true;
			}
			output.append(muffled);
		}
		return changed ? output.toString() : message;
	}

	private static boolean isWord(String token) {
		if (token == null || token.isEmpty()) {
			return false;
		}
		for (int i = 0; i < token.length(); i++) {
			if (!Character.isLetter(token.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static String muffleWord(String word, double distortion, Random random) {
		if (word.length() <= 1) {
			return word;
		}

		String working = word.toLowerCase(Locale.ROOT);
		working = applySoundClusters(working, distortion, random, false);

		if (working.length() >= 5) {
			working = insertPhoneticBreaks(working, distortion, random);
		}

		String[] parts = working.split(Pattern.quote(ELLIPSIS), -1);
		StringBuilder rebuilt = new StringBuilder(working.length() + 8);
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) {
				rebuilt.append(ELLIPSIS);
			}
			rebuilt.append(processSegment(parts[i], distortion, random));
		}
		working = rebuilt.toString();

		return slurLeadingSyllable(working, distortion, random);
	}

	private static String processSegment(String segment, double distortion, Random random) {
		if (segment.isEmpty()) {
			return segment;
		}

		String working = applySoundClusters(segment, distortion, random, true);
		if (!isSuffixTail(working)) {
			working = muffleStemFinalConsonant(working, distortion, random);
		}
		working = smearInternalConsonants(working, distortion, random);
		return working;
	}

	private static boolean isSuffixTail(String segment) {
		if (segment.length() <= 3) {
			return true;
		}
		return segment.equals("ing") || segment.equals("led") || segment.endsWith("ing") && segment.length() <= 5;
	}

	private static String applySoundClusters(String word, double distortion, Random random, boolean ffOnly) {
		if (ffOnly) {
			return replaceCluster(word, "ff", "f", distortion, random, 0.78, false);
		}

		boolean forceCore = distortion >= 0.45;
		String working = word;
		working = replaceCluster(working, "st", "sn", distortion, random, 0.88, forceCore);
		working = replaceCluster(working, "ss", "sm", distortion, random, 0.88, forceCore);
		working = replaceCluster(working, "th", "n", distortion, random, 0.76, false);
		working = replaceCluster(working, "sh", "m", distortion, random, 0.72, false);
		working = replaceCluster(working, "ch", "m", distortion, random, 0.68, false);
		working = replaceCluster(working, "mp", "m", distortion, random, 0.66, false);
		working = replaceCluster(working, "mb", "m", distortion, random, 0.66, false);
		working = replaceCluster(working, "ck", "k", distortion, random, 0.62, false);
		working = replaceCluster(working, "ng", "n", distortion, random, 0.58, false);
		working = replaceCluster(working, "tt", "tn", distortion, random, 0.74, false);
		working = replaceCluster(working, "dd", "n", distortion, random, 0.74, false);
		working = replaceCluster(working, "ll", "l", distortion, random, 0.58, false);
		working = replaceCluster(working, "ght", "nt", distortion, random, 0.7, false);
		working = replaceCluster(working, "tion", "n", distortion, random, 0.55, false);
		return working;
	}

	private static String replaceCluster(String word, String from, String to, double distortion, Random random,
			double weight, boolean force) {
		if (!word.contains(from)) {
			return word;
		}
		if (!force && random.nextDouble() >= distortion * weight) {
			return word;
		}
		return word.replace(from, to);
	}

	private static String muffleStemFinalConsonant(String word, double distortion, Random random) {
		String stem = word;
		String suffix = "";

		if (word.endsWith("ing") && word.length() > 4) {
			stem = word.substring(0, word.length() - 3);
			suffix = "ing";
		} else if (word.endsWith("ed") && word.length() > 3) {
			stem = word.substring(0, word.length() - 2);
			suffix = "ed";
		} else if (word.endsWith("le") && word.length() > 3) {
			stem = word.substring(0, word.length() - 2);
			suffix = "le";
		}

		if (stem.isEmpty()) {
			return word;
		}

		char last = stem.charAt(stem.length() - 1);
		if (!Character.isLetter(last) || isVowel(last)) {
			return word;
		}
		if (random.nextDouble() >= distortion * 0.86) {
			return word;
		}

		char nasal = toNasalMuffle(last, random);
		if (nasal == last) {
			return word;
		}
		return stem.substring(0, stem.length() - 1) + nasal + suffix;
	}

	private static String smearInternalConsonants(String word, double distortion, Random random) {
		if (word.length() < 3) {
			return word;
		}

		StringBuilder output = new StringBuilder(word.length());
		for (int i = 0; i < word.length(); i++) {
			char character = word.charAt(i);
			if (!Character.isLetter(character) || isVowel(character) || i == 0 || i == word.length() - 1) {
				output.append(character);
				continue;
			}

			if (random.nextDouble() >= distortion * 0.42) {
				output.append(character);
				continue;
			}

			char smeared = toNasalMuffle(character, random);
			output.append(smeared == character ? pickNasal(random) : smeared);
		}
		return output.toString();
	}

	private static String insertPhoneticBreaks(String word, double distortion, Random random) {
		if (word.length() < 5) {
			return word;
		}

		int breakAt = findDoubleConsonantBreak(word);
		if (breakAt < 0 || distortion < 0.32) {
			if (random.nextDouble() >= distortion * 0.68) {
				return word;
			}
			breakAt = findBreakIndex(word, random);
		}

		if (breakAt <= 0 || breakAt >= word.length()) {
			return word;
		}

		String left = word.substring(0, breakAt);
		String right = trimBridgingConsonant(left, word.substring(breakAt));
		String result = left + ELLIPSIS + right;

		if (word.length() >= 8 && distortion > 0.42 && random.nextDouble() < distortion * 0.45) {
			result = insertSecondBreak(result, distortion, random);
		}
		return result;
	}

	private static int findDoubleConsonantBreak(String word) {
		for (int i = 2; i < word.length() - 1; i++) {
			char previous = word.charAt(i - 1);
			char current = word.charAt(i);
			if (previous == current && !isVowel(current)) {
				return i;
			}
		}
		return -1;
	}

	private static String insertSecondBreak(String word, double distortion, Random random) {
		int ellipsis = word.indexOf(ELLIPSIS);
		if (ellipsis < 0) {
			return word;
		}

		String right = word.substring(ellipsis + ELLIPSIS.length());
		if (right.length() < 4 || random.nextDouble() >= distortion * 0.5) {
			return word;
		}

		int localBreak = findBreakIndex(right, random);
		if (localBreak <= 0 || localBreak >= right.length()) {
			return word;
		}

		String leftPart = right.substring(0, localBreak);
		String rightPart = trimBridgingConsonant(leftPart, right.substring(localBreak));
		return word.substring(0, ellipsis + ELLIPSIS.length()) + leftPart + ELLIPSIS + rightPart;
	}

	private static int findBreakIndex(String word, Random random) {
		int mid = word.length() / 2;
		int best = -1;
		int bestScore = Integer.MIN_VALUE;

		for (int i = 2; i < word.length() - 1; i++) {
			char prev = word.charAt(i - 1);
			char current = word.charAt(i);
			int score = 0;
			if (isVowel(prev) && !isVowel(current)) {
				score += 3;
			}
			if (!isVowel(prev) && isVowel(current)) {
				score += 2;
			}
			if (i >= word.length() / 2 && !isVowel(prev) && isVowel(current)) {
				score += 2;
			}
			if (prev == current && !isVowel(current)) {
				score += 5;
			}
			score -= Math.abs(i - mid);

			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}

		if (best > 0) {
			return best;
		}
		return Math.max(2, mid + (random.nextBoolean() ? 0 : 1));
	}

	private static String trimBridgingConsonant(String left, String right) {
		if (left.isEmpty() || right.isEmpty()) {
			return right;
		}
		char leftEnd = left.charAt(left.length() - 1);
		char rightStart = right.charAt(0);
		if (leftEnd == rightStart && !isVowel(leftEnd)) {
			return right.substring(1);
		}
		return right;
	}

	private static String slurLeadingSyllable(String word, double distortion, Random random) {
		if (word.length() < 5 || distortion < 0.38 || random.nextDouble() >= distortion * 0.48) {
			return word;
		}
		if (word.startsWith(ELLIPSIS)) {
			return word;
		}

		int ellipsis = word.indexOf(ELLIPSIS);
		String lead = ellipsis >= 0 ? word.substring(0, ellipsis) : word;
		String tail = ellipsis >= 0 ? word.substring(ellipsis) : "";

		if (lead.length() < 4) {
			return word;
		}

		int drop = 1;
		if (lead.length() >= 6 && random.nextDouble() < distortion * 0.25) {
			drop = 2;
		}
		drop = Math.min(drop, lead.length() - 2);
		return ELLIPSIS + lead.substring(drop) + tail;
	}

	private static char toNasalMuffle(char consonant, Random random) {
		char lower = Character.toLowerCase(consonant);
		return switch (lower) {
			case 't', 'd', 's', 'z', 'c', 'x' -> 'n';
			case 'p', 'b', 'f', 'v', 'm' -> 'm';
			case 'k', 'g', 'q' -> random.nextBoolean() ? 'm' : 'n';
			case 'l', 'r' -> random.nextBoolean() ? 'm' : 'n';
			case 'h', 'j', 'w', 'y' -> pickNasal(random);
			default -> consonant;
		};
	}

	private static char pickNasal(Random random) {
		return random.nextBoolean() ? 'm' : 'n';
	}

	private static boolean isVowel(char character) {
		char lower = Character.toLowerCase(character);
		return lower == 'a' || lower == 'e' || lower == 'i' || lower == 'o' || lower == 'u';
	}
}
