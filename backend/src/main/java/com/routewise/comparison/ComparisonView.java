package com.routewise.comparison;

import java.time.Instant;

/** A stored comparison as the F8 screen reads it; {@code level} tells which shape it is. */
public sealed interface ComparisonView permits PathComparisonService.PathComparison, TourComparisonService.TourComparison {

    Long id();

    Comparison.Level level();

    Instant createdAt();
}
