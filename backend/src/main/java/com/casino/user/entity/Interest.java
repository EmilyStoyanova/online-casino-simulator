package com.casino.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * An educational interest from the catalogue ({@code interests}, migration V5).
 *
 * <p>Reference data, maintained only by migrations, so read-only here. {@code code} is the stable
 * key clients use; {@code sortOrder} is the display order; inactive interests are no longer offered.
 *
 * <p>Never returned by controllers; services map it to DTOs.
 */
@Entity
@Immutable
@Table(name = "interests")
public class Interest {

    @Id
    @Column(length = 40)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false)
    private short sortOrder;

    @Column(nullable = false)
    private boolean active;

    /** For JPA. */
    protected Interest() {
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public short getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    /** Equal by {@code code}, the assigned and never changing key, so sets of interests work across loads. */
    @Override
    public boolean equals(Object other) {
        // The getter, not the field: `other` may be a Hibernate proxy.
        return this == other || other instanceof Interest interest && code.equals(interest.getCode());
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }
}
