package com.routewise.common.error;

import org.springframework.http.HttpStatus;

/**
 * Catalogue of error codes, each bound to the status it is returned with.
 *
 * <p>409 means a business rule was violated: the client fixes it by changing the
 * flow, not the payload. 422 means the geographic validation rejected a point.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),

    ADDRESS_NOT_VERIFIED(HttpStatus.CONFLICT),
    ADDRESS_LOCKED(HttpStatus.CONFLICT),
    CHANGE_LIMIT_REACHED(HttpStatus.CONFLICT),
    PLAN_ALREADY_DISPATCHED(HttpStatus.CONFLICT),
    NO_FEASIBLE_SOLUTION(HttpStatus.CONFLICT),
    PLAN_NOT_OPTIMIZED(HttpStatus.CONFLICT),
    NOT_ENOUGH_COURIERS(HttpStatus.CONFLICT),
    SHIPMENT_IN_RUN_HISTORY(HttpStatus.CONFLICT),
    STOP_ALREADY_CLOSED(HttpStatus.CONFLICT),

    OUTSIDE_SERVICE_AREA(HttpStatus.UNPROCESSABLE_ENTITY),
    TOO_FAR_FROM_ROAD(HttpStatus.UNPROCESSABLE_ENTITY),

    OPTIMIZER_UNAVAILABLE(HttpStatus.BAD_GATEWAY),
    OPTIMIZER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
