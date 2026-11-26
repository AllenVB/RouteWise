package com.routewise.optimization;

/**
 * Names match {@code ck_runs_algorithm} and the optimizer's own enum.
 *
 * <p>Only what the optimizer implements today is accepted on the API; the
 * exhaustive search arrives with the comparison module in phase 6.
 */
public enum Algorithm {

    NAIVE,
    GREEDY_NN,
    SAVINGS,
    ROUTEWISE,
    DFS_EXHAUSTIVE;

    /** Only RouteWise draws random numbers, so only its runs carry a seed. */
    public boolean isSeeded() {
        return this == ROUTEWISE;
    }

    public boolean isAvailable() {
        return this != DFS_EXHAUSTIVE;
    }
}
