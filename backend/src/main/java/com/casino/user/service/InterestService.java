package com.casino.user.service;

import java.util.List;

import com.casino.user.dto.InterestResponse;
import com.casino.user.repository.InterestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The catalogue of educational interests. */
@Service
public class InterestService {

    private final InterestRepository repository;

    public InterestService(InterestRepository repository) {
        this.repository = repository;
    }

    /** Active interests in display order ({@code sort_order}, then code). */
    @Transactional(readOnly = true)
    public List<InterestResponse> listActive() {
        return repository.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .map(interest -> new InterestResponse(interest.getCode(), interest.getName(), interest.getDescription()))
                .toList();
    }
}
