package com.splitexpense.expense.controller;

import com.splitexpense.expense.dto.request.CreateExpenseRequest;
import com.splitexpense.expense.dto.response.ErrorResponse;
import com.splitexpense.expense.dto.response.ExpenseResponse;
import com.splitexpense.expense.security.AuthenticatedUser;
import com.splitexpense.expense.service.ExpenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for expenses.
 *
 * <p>Holds no business logic: each method resolves the caller, delegates to
 * {@link ExpenseService}, and returns a DTO.
 *
 * <p>Every endpoint that names a group or an expense authorises through group-service, using
 * the caller's own forwarded bearer token — never a role check here. That is what makes
 * {@code SecurityConfig} for this service so simple: there is no parameter through which a
 * client could name a group it does not belong to and have this service trust it.
 *
 * <p>Mapped under {@code /api/v1/expenses}, not nested beneath {@code /api/v1/groups/**}: the
 * gateway routes that whole prefix to group-service, so an expense-owned resource needs its
 * own top-level path.
 */
@RestController
@RequestMapping("/api/v1/expenses")
@Tag(name = "Expenses", description = "Expenses, splits and voids")
@SecurityRequirement(name = "bearerAuth")
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    /**
     * Records a new expense and splits it between its participants.
     *
     * @param caller         the verified caller
     * @param idempotencyKey client-generated key identifying this specific attempt; a retry
     *                       must resend the same key with the same body
     * @param authorization  the caller's own bearer token, forwarded to authorise against the
     *                       group
     * @param request        group, payer, amount, split type and participants
     * @return 200 with the recorded expense
     */
    @PostMapping
    @Operation(summary = "Record a new expense and split it between participants",
            description = "Requires an Idempotency-Key header. Retrying the same key with the "
                    + "same body is always safe and returns the original outcome.")
    @ApiResponse(responseCode = "200", description = "Expense recorded")
    @ApiResponse(responseCode = "400", description = "Validation failed, or the split does "
            + "not add up",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such group, or the caller is not a "
            + "member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "A request with this key is already in "
            + "progress",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "The expense was refused, or the "
            + "Idempotency-Key was reused with a different request",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "group-service is unavailable",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<ExpenseResponse> createExpense(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Parameter(description = "Client-generated key identifying this specific attempt")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody CreateExpenseRequest request) {

        ExpenseResponse response =
                expenseService.createExpense(caller, idempotencyKey, request, authorization);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Reads one expense, provided the caller belongs to its group.
     *
     * @param expenseId     expense to read
     * @param authorization the caller's own bearer token, forwarded to authorise
     * @return 200 with the expense and its splits
     */
    @GetMapping("/{expenseId}")
    @Operation(summary = "Read one expense and its splits")
    @ApiResponse(responseCode = "200", description = "The expense")
    @ApiResponse(responseCode = "404", description = "No such expense, or the caller is not "
            + "a member of its group",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<ExpenseResponse> getExpense(
            @PathVariable UUID expenseId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {

        return ResponseEntity.ok(expenseService.getExpense(expenseId, authorization));
    }

    /**
     * Returns one page of the caller's own expenses (as payer), newest first.
     *
     * @param caller   the verified caller
     * @param pageable paging and sort, defaulted to 20 per page by {@code createdAt} desc
     * @return 200 with a page of expenses
     */
    @GetMapping("/me")
    @Operation(summary = "Page through the expenses the authenticated user paid")
    @ApiResponse(responseCode = "200", description = "A page of expenses")
    public ResponseEntity<Page<ExpenseResponse>> myExpenses(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ResponseEntity.ok(expenseService.getExpensesForPayer(caller.id(), pageable));
    }

    /**
     * Returns one page of a group's expenses, newest first.
     *
     * @param groupId       group whose expenses are being read
     * @param authorization the caller's own bearer token, forwarded to authorise
     * @param pageable      paging and sort, defaulted to 20 per page by {@code createdAt} desc
     * @return 200 with a page of expenses
     */
    @GetMapping("/groups/{groupId}")
    @Operation(summary = "Page through a group's expenses")
    @ApiResponse(responseCode = "200", description = "A page of expenses")
    @ApiResponse(responseCode = "404", description = "No such group, or the caller is not a "
            + "member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Page<ExpenseResponse>> groupExpenses(
            @PathVariable UUID groupId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ResponseEntity.ok(expenseService.getGroupExpenses(groupId, authorization, pageable));
    }

    /**
     * Voids a completed expense by applying the exact inverse of its original deltas.
     *
     * @param caller         the verified caller
     * @param expenseId      expense to void
     * @param idempotencyKey client-generated key identifying this specific attempt
     * @param authorization  the caller's own bearer token, forwarded to authorise
     * @return 200 with the voided expense
     */
    @PostMapping("/{expenseId}/void")
    @Operation(summary = "Void a completed expense",
            description = "Requires an Idempotency-Key header. Only the payer or a "
                    + "participant may void an expense, and only while it is COMPLETED.")
    @ApiResponse(responseCode = "200", description = "Expense voided")
    @ApiResponse(responseCode = "403", description = "Caller is neither the payer nor a "
            + "participant",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such expense, or the caller is not "
            + "a member of its group",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "A request with this key is already in "
            + "progress, or the expense is not COMPLETED",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<ExpenseResponse> voidExpense(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID expenseId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {

        return ResponseEntity.ok(
                expenseService.voidExpense(expenseId, caller.id(), idempotencyKey, authorization));
    }
}
