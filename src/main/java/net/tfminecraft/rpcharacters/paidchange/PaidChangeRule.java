package net.tfminecraft.rpcharacters.paidchange;

import java.util.List;

/**
 * Lets players pay to reopen a creation stage after its lock-time runs out. Each paid change
 * costs the next entry in {@code costs}; the last entry repeats once the list runs out.
 */
public final class PaidChangeRule {
	private final String id;
	private final String stageId;
	private final String label;
	private final List<Double> costs;

	public PaidChangeRule(String id, String stageId, String label, List<Double> costs) {
		this.id = id;
		this.stageId = stageId;
		this.label = label == null || label.isBlank() ? id : label;
		this.costs = List.copyOf(costs);
	}

	/** Key for the per-character paid change count. */
	public String getId() {
		return id;
	}

	public String getStageId() {
		return stageId;
	}

	/** What players change, in lower case, e.g. "class". */
	public String getLabel() {
		return label;
	}

	public List<Double> getCosts() {
		return costs;
	}

	/** Price of a change after {@code paidSoFar} earlier paid changes. */
	public double costAfter(int paidSoFar) {
		if (costs.isEmpty()) {
			return 0.0;
		}
		return costs.get(Math.min(Math.max(0, paidSoFar), costs.size() - 1));
	}
}
