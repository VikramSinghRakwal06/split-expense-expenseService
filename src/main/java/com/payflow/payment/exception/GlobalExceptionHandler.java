package com.payflow.payment.exception;

import com.payflow.payment.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <h2>Status codes for transfer failures</h2>
 *
 * <ul>
 *   <li><strong>400</strong> — the request was wrong. Fix it and resend.</li>
 *   <li><strong>404</strong> — the caller has no wallet yet.</li>
 *   <li><strong>409</strong> — a request with this key is already in progress, or the
 *       transfer failed and was safely reversed. Resending is meaningful (same key to check
 *       progress; a fresh key to try again).</li>
 *   <li><strong>422</strong> — the transfer was refused outright, or the key was reused with a
 *       different request. Resending unchanged will not help.</li>
 *   <li><strong>500</strong> — {@link TransferUnresolvedException}: do not retry.</li>
 *   <li><strong>503</strong> — wallet-service is unavailable.</li>
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

    @ExceptionHandler(SameWalletTransferException.class)
    public ResponseEntity<ErrorResponse> handleSameWallet(
            SameWalletTransferException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(NoWalletException.class)
    public ResponseEntity<ErrorResponse> handleNoWallet(
            NoWalletException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            IdempotencyKeyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, null);
    }

    /** Debit refused, or {@link InsufficientFundsException} escaping unmapped. Logged at info:
     *  a declined transfer is a normal business outcome, not a fault. */
    @ExceptionHandler({TransferFailedException.class, InsufficientFundsException.class})
    public ResponseEntity<ErrorResponse> handleTransferFailed(
            RuntimeException ex, HttpServletRequest request) {
        log.info("Transfer declined on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, null);
    }

    @ExceptionHandler(TransferInProgressException.class)
    public ResponseEntity<ErrorResponse> handleInProgress(
            TransferInProgressException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    @ExceptionHandler(TransferReversedException.class)
    public ResponseEntity<ErrorResponse> handleReversed(
            TransferReversedException ex, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                "Transfer could not be completed; your funds were returned. " + ex.getMessage(),
                request,
                null);
    }

    /** The one response that must not imply a retry is safe. Already logged at error where
     *  it was raised, in {@code PaymentService}. */
    @ExceptionHandler(TransferUnresolvedException.class)
    public ResponseEntity<ErrorResponse> handleUnresolved(
            TransferUnresolvedException ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request, null);
    }

    /**
     * wallet-service reached a decision this service was not expecting to have to surface
     * directly (every path {@code PaymentService} anticipates translates this into a more
     * specific exception first). Passes wallet-service's own status code through unchanged.
     */
    @ExceptionHandler(WalletOperationException.class)
    public ResponseEntity<ErrorResponse> handleWalletOperation(
            WalletOperationException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatus());
        return build(
                status != null ? status : HttpStatus.BAD_GATEWAY,
                ex.getMessage(),
                request,
                null);
    }

    @ExceptionHandler(WalletServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleWalletUnavailable(
            WalletServiceUnavailableException ex, HttpServletRequest request) {
        log.warn("wallet-service unavailable on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                "wallet-service is currently unavailable. This request may be retried once it "
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
