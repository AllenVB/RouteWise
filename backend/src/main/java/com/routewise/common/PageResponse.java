package com.routewise.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * The listing envelope every paged endpoint returns
 * ({@code docs/api-contract.md} §3).
 *
 * <p>Spring's own {@code Page} is not exposed: its JSON shape is an internal
 * detail that has changed between versions, and the contract must not drift
 * with the framework.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
