package com.splitexpense.expense.exception;

import com.splitexpense.expense.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates every exception escaping a controller into the one {@link ErrorResponse} shape.
 *
 * <p>Covers the controller side only. Authentication and authorisation failures raised inside
 * the security filter chain never reach a controller, and are handled by {@code
 * JwtAuthenticationEntryPoint} and {@code RestAccessDeniedHandler}, which produce an identical
 * body.
 *
 * <h2>Status codes for expense and settlement failures</h2>
 *
 * <ul>
 *   <li><strong>400</strong> — the request was wrong. Fix it and resend.</li>
 *   <li><strong>403</strong> — the caller is a group member but has no standing over this
 *       specific expense or settlement.</li>
 *   <li><strong>404</strong> — no such expense/settlement, <em>or</em> the caller is not a
 *       member of its group. The two are deliberately indistinguishable.</li>
 *   <li><strong>409</strong> — a request with this key is already in progress, or the
 *       resource's own state forbids the transition (voiding a non-COMPLETED expense).</li>
 *   <li><strong>422</strong> — the operation was refused outright, or the key was reused with
 *       a different request. Resending unchanged will not help.</li>
 *   <li><strong>503</strong> — group-service is unavailable.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean validation failure on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.merge(
                    error.getField(),
                    error.getDefaultMessage(),
                    (existing, ignored) -> existing);
        }

        return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, fieldErrors);
    }

    /** Body that could not be parsed at all — malformed JSON, or a value of the wrong type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", request, null);
    }

    /** A path variable that could not be converted. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "'%s' is not a valid value for %s".formatted(ex.getValue(), ex.getName()),
                request,
                null);
    }

    /** {@code Idempotency-Key} or {@code Authorization} missing from the request. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "Missing required header: " + ex.getHeaderName(),
                request,
                null);
    }

    /** A split's participants or their inputs do not describe a valid division of the amount. */
    @ExceptionHandler(InvalidSplitException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSplit(
            InvalidSplitException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(SameUserSettlementException.class)
    public ResponseEntity<ErrorResponse> handleSameUserSettlement(
            SameUserSettlementException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    /** group-service could not find the group, or the caller is not a member of it. */
    @ExceptionHandler(NoSuchGroupException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchGroup(
            NoSuchGroupException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    @ExceptionHandler({NotExpenseParticipantException.class, NotSettlementPartyException.class})
    public ResponseEntity<ErrorResponse> handleNoStanding(
            RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request, null);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            IdempotencyKeyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, null);
    }

    /** A participant, payer, or settlement party is not a member of the named group. Logged
     *  at info: expense-service already validated this once against the group view it read,
     *  so this is a plain business rejection, not a fault. */
    @ExceptionHandler(NotAGroupMemberException.class)
    public ResponseEntity<ErrorResponse> handleNotAMember(
            NotAGroupMemberException ex, HttpServletRequest request) {
        log.info("Rejected on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, null);
    }

    /** A settlement asked to move more than the payer currently owes the recipient — or
     *  anything at all, when they owe nothing. Logged at info: a plain business rejection,
     *  not a fault. */
    @ExceptionHandler(InsufficientDebtException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientDebt(
            InsufficientDebtException ex, HttpServletRequest request) {
        log.info("Rejected on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, null);
    }

    /** group-service refused the deltas. Logged at info: a declined expense or settlement is
     *  a normal business outcome, not a fault. */
    @ExceptionHandler({ExpenseFailedException.class, SettlementFailedException.class})
    public ResponseEntity<ErrorResponse> handleOperationFailed(
            RuntimeException ex, HttpServletRequest request) {
        log.info("Declined on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, null);
    }

    @ExceptionHandler(OperationInProgressException.class)
    public ResponseEntity<ErrorResponse> handleInProgress(
            OperationInProgressException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    @ExceptionHandler(ExpenseNotVoidableException.class)
    public ResponseEntity<ErrorResponse> handleNotVoidable(
            ExpenseNotVoidableException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    /**
     * An optimistic-lock failure that reached the handler unretried — most plausibly two
     * different Idempotency-Keys voiding the same expense at the same moment. Still a 409,
     * and still safe to retry, because the transaction rolled back.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Unretried optimistic-lock failure on {} {}",
                request.getMethod(), request.getRequestURI());
        return build(
                HttpStatus.CONFLICT,
                "This resource was updated concurrently. No change was applied; please retry.",
                request,
                null);
    }

    /**
     * group-service reached a decision this service was not expecting to have to surface
     * directly (every path {@code ExpenseService} and {@code SettlementService} anticipate
     * translates this into a more specific exception first). Passes group-service's own
     * status code through unchanged.
     */
    @ExceptionHandler(GroupOperationException.class)
    public ResponseEntity<ErrorResponse> handleGroupOperation(
            GroupOperationException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatus());
        return build(
                status != null ? status : HttpStatus.BAD_GATEWAY,
                ex.getMessage(),
                request,
                null);
    }

    @ExceptionHandler(GroupServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleGroupUnavailable(
            GroupServiceUnavailableException ex, HttpServletRequest request) {
        log.warn("group-service unavailable on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                "group-service is currently unavailable. This request may be retried once it "
                        + "recovers.",
                request,
                null);
    }

    /** Authorisation failure raised by method security, after a controller was selected. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return build(
                HttpStatus.FORBIDDEN,
                "You do not have permission to access this resource.",
                request,
                null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "No endpoint for this path", request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return build(
                HttpStatus.METHOD_NOT_ALLOWED,
                "%s is not supported for this endpoint".formatted(ex.getMethod()),
                request,
                null);
    }

    /** Last resort. The stack trace goes to the log; the caller gets a fixed sentence. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}",
                request.getMethod(), request.getRequestURI(), ex);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.",
                request,
                null);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> validationErrors) {

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .validationErrors(validationErrors)
                .build();

        return ResponseEntity.status(status).body(body);
    }
}
