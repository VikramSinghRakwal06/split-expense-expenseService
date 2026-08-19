package com.splitexpense.expense.controller;

import com.splitexpense.expense.dto.request.CreateSettlementRequest;
import com.splitexpense.expense.dto.response.ErrorResponse;
import com.splitexpense.expense.dto.response.SettlementResponse;
import com.splitexpense.expense.security.AuthenticatedUser;
import com.splitexpense.expense.service.SettlementService;
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
 * HTTP entry point for settlements — real-world payments recorded to cancel part of a debt.
 *
 * <p>Runs the same idempotent, group-service-backed saga as {@link ExpenseController}, with a
 * single delta instead of a computed split.
 */
@RestController
@RequestMapping("/api/v1/settlements")
@Tag(name = "Settlements", description = "Payments recorded to cancel a debt")
@SecurityRequirement(name = "bearerAuth")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * Records a settlement between two group members.
     *
     * @param caller         the verified caller; must be either party named in the request
     * @param idempotencyKey client-generated key identifying this specific attempt
     * @param authorization  the caller's own bearer token, forwarded to authorise
     * @param request        group, both parties, amount and optional note
     * @return 200 with the recorded settlement
     */
    @PostMapping
    @Operation(summary = "Record a settlement between two group members",
            description = "Requires an Idempotency-Key header. Either party to the debt may "
                    + "record it.")
    @ApiResponse(responseCode = "200", description = "Settlement recorded")
    @ApiResponse(responseCode = "400", description = "Validation failed, or the two parties "
            + "are the same user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Caller is neither party to the "
            + "settlement",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such group, or the caller is not a "
            + "member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "A request with this key is already in "
            + "progress",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "The settlement was refused, or the "
            + "Idempotency-Key was reused with a different request",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "group-service is unavailable",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<SettlementResponse> recordSettlement(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Parameter(description = "Client-generated key identifying this specific attempt")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody CreateSettlementRequest request) {

        SettlementResponse response = settlementService.recordSettlement(
                caller.id(), idempotencyKey, request, authorization);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Reads one settlement, provided the caller belongs to its group.
     *
     * @param settlementId  settlement to read
     * @param authorization the caller's own bearer token, forwarded to authorise
     * @return 200 with the settlement
     */
    @GetMapping("/{settlementId}")
    @Operation(summary = "Read one settlement")
    @ApiResponse(responseCode = "200", description = "The settlement")
    @ApiResponse(responseCode = "404", description = "No such settlement, or the caller is "
            + "not a member of its group",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<SettlementResponse> getSettlement(
            @PathVariable UUID settlementId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {

        return ResponseEntity.ok(settlementService.getSettlement(settlementId, authorization));
    }

    /**
     * Returns one page of a group's settlements, newest first.
     *
     * @param groupId       group whose settlements are being read
     * @param authorization the caller's own bearer token, forwarded to authorise
     * @param pageable      paging and sort, defaulted to 20 per page by {@code createdAt} desc
     * @return 200 with a page of settlements
     */
    @GetMapping("/groups/{groupId}")
    @Operation(summary = "Page through a group's settlements")
    @ApiResponse(responseCode = "200", description = "A page of settlements")
    @ApiResponse(responseCode = "404", description = "No such group, or the caller is not a "
            + "member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Page<SettlementResponse>> groupSettlements(
            @PathVariable UUID groupId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ResponseEntity.ok(
                settlementService.getGroupSettlements(groupId, authorization, pageable));
    }
}
