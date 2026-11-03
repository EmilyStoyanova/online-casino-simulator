package com.casino.user.repository;

import java.util.List;

import com.casino.user.entity.Interest;
import org.springframework.data.jpa.repository.JpaRepository;

/** Database access to the interest catalogue ({@code interests}). Used only by the {@code user} services. */
public interface InterestRepository extends JpaRepository<Interest, String> {

    /** Active interests in display order; the code decides between equal orders, so the order is stable. */
    List<Interest> findByActiveTrueOrderBySortOrderAscCodeAsc();
}
