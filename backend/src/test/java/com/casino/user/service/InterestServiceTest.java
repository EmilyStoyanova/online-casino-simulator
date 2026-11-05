package com.casino.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.casino.user.dto.InterestResponse;
import com.casino.user.entity.Interest;
import com.casino.user.repository.InterestRepository;
import org.junit.jupiter.api.Test;

class InterestServiceTest {

    private final InterestRepository repository = mock(InterestRepository.class);
    private final InterestService service = new InterestService(repository);

    @Test
    void listsTheActiveInterestsAsDtosInTheOrderOfTheQuery() {
        List<Interest> interests = List.of(interest("PROBABILITY", "Probability & odds", "How likely outcomes are"),
                interest("MATHEMATICS", "Mathematics of games", "Expected value"));
        when(repository.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(interests);

        assertThat(service.listActive()).containsExactly(
                new InterestResponse("PROBABILITY", "Probability & odds", "How likely outcomes are"),
                new InterestResponse("MATHEMATICS", "Mathematics of games", "Expected value"));
        // Only the query for active interests in display order is used.
        verify(repository).findByActiveTrueOrderBySortOrderAscCodeAsc();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void anEmptyCatalogueIsAnEmptyList() {
        when(repository.findByActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of());

        assertThat(service.listActive()).isEmpty();
    }

    private static Interest interest(String code, String name, String description) {
        Interest interest = mock(Interest.class);
        when(interest.getCode()).thenReturn(code);
        when(interest.getName()).thenReturn(name);
        when(interest.getDescription()).thenReturn(description);
        return interest;
    }
}
