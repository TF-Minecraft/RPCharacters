package net.tfminecraft.rpcharacters.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class GraveInsuranceTicketsTest {

	@Test
	void picksFirstTicketSlot() {
		Set<Integer> tickets = Set.of(4, 7);
		assertEquals(4, GraveInsuranceTickets.pickSlot(List.of(1, 4, 7), tickets::contains, slot -> false));
	}

	@Test
	void skipsTicketsBoundToLiveGraves() {
		Set<Integer> tickets = Set.of(4, 7);
		assertEquals(7, GraveInsuranceTickets.pickSlot(List.of(1, 4, 7), tickets::contains, slot -> slot == 4));
	}

	@Test
	void noTicketMeansNoBinding() {
		assertNull(GraveInsuranceTickets.pickSlot(List.of(1, 2), slot -> false, slot -> false));
	}

	@Test
	void allTicketsBoundMeansNoBinding() {
		assertNull(GraveInsuranceTickets.pickSlot(List.of(3), slot -> true, slot -> true));
	}
}
