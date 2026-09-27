package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class GraveInsuranceExtractTest {

	@Test
	void pullsOneTicketOutOfABundle() {
		Node ticket = ticket("ticket", 1);
		Node bread = item("bread");
		Node bundle = bundle("bundle", ticket, bread);
		Node[] slots = { bundle };

		Node pulled = GraveInsuranceExtract.pullSlots(slots, new Nodes());

		assertEquals("ticket", pulled.id);
		assertEquals(1, pulled.amount);
		assertEquals(1, bundle.contents.size());
		assertEquals("bread", bundle.contents.get(0).id);
		assertTrue(slots[0] == bundle);
	}

	@Test
	void leavesTheRestOfAStackInTheBundle() {
		Node stack = ticket("ticket", 3);
		Node bundle = bundle("bundle", stack);
		Node[] slots = { bundle };

		Node pulled = GraveInsuranceExtract.pullSlots(slots, new Nodes());

		assertEquals(1, pulled.amount);
		assertEquals(1, bundle.contents.size());
		assertEquals(2, bundle.contents.get(0).amount);
	}

	@Test
	void pullsFromANestedBundle() {
		Node ticket = ticket("ticket", 1);
		Node inner = bundle("inner", ticket);
		Node outer = bundle("outer", item("bread"), inner);
		Node[] slots = { outer };

		Node pulled = GraveInsuranceExtract.pullSlots(slots, new Nodes());

		assertEquals("ticket", pulled.id);
		assertTrue(inner.contents.isEmpty());
		assertEquals(2, outer.contents.size());
		assertEquals("bread", outer.contents.get(0).id);
	}

	@Test
	void skipsATicketBoundToALiveGrave() {
		Node bound = ticket("bound", 1);
		bound.liveBound = true;
		Node free = ticket("free", 1);
		Node bundle = bundle("bundle", bound, free);
		Node[] slots = { bundle };

		Node pulled = GraveInsuranceExtract.pullSlots(slots, new Nodes());

		assertEquals("free", pulled.id);
		assertEquals(1, bundle.contents.size());
		assertEquals("bound", bundle.contents.get(0).id);
	}

	@Test
	void returnsNullWhenTheBundleHasNoTicket() {
		Node[] slots = { bundle("bundle", item("bread")) };

		assertNull(GraveInsuranceExtract.pullSlots(slots, new Nodes()));
		assertEquals(1, slots[0].contents.size());
	}

	@Test
	void searchesLaterSlotsAfterAnEmptyBundle() {
		Node ticket = ticket("ticket", 1);
		Node[] slots = { bundle("empty"), null, bundle("later", ticket) };

		Node pulled = GraveInsuranceExtract.pullSlots(slots, new Nodes());

		assertEquals("ticket", pulled.id);
		assertTrue(slots[2].contents.isEmpty());
	}

	private static Node item(String id) {
		return new Node(id, 1, false, null);
	}

	private static Node ticket(String id, int amount) {
		return new Node(id, amount, true, null);
	}

	private static Node bundle(String id, Node... contents) {
		List<Node> inner = new ArrayList<>();
		for (Node content : contents) {
			inner.add(content);
		}
		return new Node(id, 1, false, inner);
	}

	private static final class Node {
		private final String id;
		private int amount;
		private final boolean insurance;
		private boolean liveBound;
		private final List<Node> contents;

		private Node(String id, int amount, boolean insurance, List<Node> contents) {
			this.id = id;
			this.amount = amount;
			this.insurance = insurance;
			this.contents = contents;
		}
	}

	private static final class Nodes implements GraveInsuranceExtract.Stacks<Node> {
		@Override
		public boolean blank(Node item) {
			return item == null || item.amount <= 0;
		}

		@Override
		public int amount(Node item) {
			return item.amount;
		}

		@Override
		public void setAmount(Node item, int amount) {
			item.amount = amount;
		}

		@Override
		public boolean insurance(Node item) {
			return item.insurance;
		}

		@Override
		public boolean keepBinding(Node item) {
			return item.liveBound;
		}

		@Override
		public boolean bundle(Node item) {
			return item.contents != null;
		}

		@Override
		public List<Node> contents(Node bundle) {
			return bundle.contents;
		}

		@Override
		public void setContents(Node bundle, List<Node> contents) {
			bundle.contents.clear();
			bundle.contents.addAll(contents);
		}

		@Override
		public Node one(Node item) {
			return new Node(item.id, 1, item.insurance, null);
		}
	}
}
