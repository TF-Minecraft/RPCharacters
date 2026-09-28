package net.tfminecraft.rpcharacters.paidchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Lets players pay to reopen a creation stage after its lock-time runs out. Each paid change
 * costs the next entry in {@code costs}; the last entry repeats once the list runs out.
 */
public final class PaidChangeRule {
	private final String id;
	private final String stageId;
	private final String label;
	private final List<BigDecimal> costs;

	public PaidChangeRule(String id, String stageId, String label, List<BigDecimal> costs) {
		this.id = id;
		this.stageId = stageId.toLowerCase(java.util.Locale.ROOT);
		this.label = label == null || label.isBlank() ? id : label;
		this.costs = costs.stream().map(cost -> cost.setScale(2, RoundingMode.HALF_UP)).toList();
	}

	public String getId() {
		return id;
	}

	/** Also the key for the per-character paid change count. */
	public String getStageId() {
		return stageId;
	}

	/** What players change, in lower case, e.g. "class". */
	public String getLabel() {
		return label;
	}

	public List<BigDecimal> getCosts() {
		return costs;
	}

	/** Price of a change after {@code paidSoFar} earlier paid changes. */
	public BigDecimal costAfter(int paidSoFar) {
		if (costs.isEmpty()) {
			return BigDecimal.ZERO.setScale(2);
		}
		return costs.get(Math.min(Math.max(0, paidSoFar), costs.size() - 1));
	}
}
