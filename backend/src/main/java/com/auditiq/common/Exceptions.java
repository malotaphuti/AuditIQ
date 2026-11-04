package com.auditiq.common;

/** Domain exceptions translated to HTTP responses by {@link GlobalExceptionHandler}. */
public final class Exceptions {

    private Exceptions() {
    }

    /** 404 – the requested entity does not exist. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 409 – the request conflicts with existing state (e.g. duplicate username). */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** 422 – well-formed request that violates a business or governance rule. */
    public static class BusinessRuleException extends RuntimeException {
        public BusinessRuleException(String message) {
            super(message);
        }
    }

    /** 403 – a feature is disabled by configuration. */
    public static class FeatureDisabledException extends RuntimeException {
        public FeatureDisabledException(String message) {
            super(message);
        }
    }
}
