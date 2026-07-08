package uk.jtoye.core.finance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.finance.dto.FinancialAggregateRow;
import uk.jtoye.core.finance.dto.FinancialSummaryDto;
import uk.jtoye.core.finance.dto.FinancialTransactionDto;
import uk.jtoye.core.finance.dto.FinancialVatRow;
import uk.jtoye.core.security.TenantContext;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FinancialTransactionService.
 * Tests service layer business logic with mocked dependencies.
 *
 * Focus areas:
 * - Tenant context validation
 * - VAT calculation logic
 * - Immutability enforcement (no update/delete)
 * - Mapper integration
 */
@ExtendWith(MockitoExtension.class)
class FinancialTransactionServiceTest {

    @Mock
    private FinancialTransactionRepository financialTransactionRepository;

    @Mock
    private FinancialTransactionMapper financialTransactionMapper;

    @InjectMocks
    private FinancialTransactionService financialTransactionService;

    private UUID tenantId;
    private UUID transactionId;
    private FinancialTransaction testTransaction;
    private CreateTransactionRequest validRequest;

    /**
     * Helper method to set private fields using reflection.
     * Needed for auto-generated fields like id and createdAt.
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        transactionId = UUID.randomUUID();

        // Set up tenant context
        TenantContext.set(tenantId);

        // Create test transaction (using reflection to set auto-generated fields)
        testTransaction = new FinancialTransaction();
        setField(testTransaction, "id", transactionId);
        testTransaction.setTenantId(tenantId);
        testTransaction.setAmountPennies(10000L); // £100.00
        testTransaction.setVatRate(VatRate.STANDARD);
        testTransaction.setReference("ORDER-12345");
        setField(testTransaction, "createdAt", OffsetDateTime.now());

        // Create valid request
        validRequest = new CreateTransactionRequest(10000L, VatRate.STANDARD, "ORDER-12345");

        // Mock FinancialTransactionMapper behavior to mimic actual MapStruct implementation
        // Use lenient() to avoid UnnecessaryStubbingException in tests that don't use the mapper
        lenient().when(financialTransactionMapper.toDto(any(FinancialTransaction.class))).thenAnswer(invocation -> {
            FinancialTransaction transaction = invocation.getArgument(0);
            return new FinancialTransactionDto(
                    transaction.getId(),
                    transaction.getTenantId(),
                    transaction.getAmountPennies(),
                    transaction.getVatRate(),
                    transaction.calculateVatAmount(),
                    transaction.getReference(),
                    transaction.getCreatedAt()
            );
        });

        lenient().when(financialTransactionMapper.toEntity(any(CreateTransactionRequest.class))).thenAnswer(invocation -> {
            CreateTransactionRequest request = invocation.getArgument(0);
            FinancialTransaction transaction = new FinancialTransaction();
            transaction.setAmountPennies(request.amountPennies());
            transaction.setVatRate(request.vatRate());
            transaction.setReference(request.description());
            return transaction;
        });
    }

    @Test
    @DisplayName("createTransaction - Success with valid request")
    void testCreateTransaction_Success() {
        // Given
        when(financialTransactionRepository.save(any(FinancialTransaction.class))).thenAnswer(invocation -> {
            FinancialTransaction transaction = invocation.getArgument(0);
            setField(transaction, "id", transactionId);
            setField(transaction, "createdAt", OffsetDateTime.now());
            return transaction;
        });

        // When
        FinancialTransactionDto result = financialTransactionService.createTransaction(validRequest);

        // Then
        assertNotNull(result);
        assertEquals(transactionId, result.id());
        assertEquals(10000L, result.amountPennies());
        assertEquals(VatRate.STANDARD, result.vatRate());
        assertEquals(1666L, result.vatAmountPennies()); // fraction method: 10000*20/120 = 1666 (round down)
        assertEquals("ORDER-12345", result.description());

        ArgumentCaptor<FinancialTransaction> transactionCaptor = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(financialTransactionRepository).save(transactionCaptor.capture());

        FinancialTransaction savedTransaction = transactionCaptor.getValue();
        assertEquals(tenantId, savedTransaction.getTenantId());
        assertEquals(10000L, savedTransaction.getAmountPennies());
        assertEquals(VatRate.STANDARD, savedTransaction.getVatRate());
        assertEquals("ORDER-12345", savedTransaction.getReference());
    }

    @Test
    @DisplayName("createTransaction - Fails when tenant context not set")
    void testCreateTransaction_MissingTenant() {
        // Given
        TenantContext.clear();

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            financialTransactionService.createTransaction(validRequest);
        });

        assertEquals("Tenant context not set", exception.getMessage());
        verify(financialTransactionRepository, never()).save(any(FinancialTransaction.class));
    }

    @Test
    @DisplayName("createTransaction - Sets tenant ID correctly")
    void testCreateTransaction_SetsTenantId() {
        // Given
        ArgumentCaptor<FinancialTransaction> transactionCaptor = ArgumentCaptor.forClass(FinancialTransaction.class);
        when(financialTransactionRepository.save(transactionCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        financialTransactionService.createTransaction(validRequest);

        // Then
        FinancialTransaction savedTransaction = transactionCaptor.getValue();
        assertEquals(tenantId, savedTransaction.getTenantId());
    }

    @Test
    @DisplayName("createTransaction - Calculates STANDARD VAT correctly (20%)")
    void testCreateTransaction_StandardVatCalculation() {
        // Given
        CreateTransactionRequest request = new CreateTransactionRequest(10000L, VatRate.STANDARD, "TEST");
        when(financialTransactionRepository.save(any(FinancialTransaction.class))).thenAnswer(invocation -> {
            FinancialTransaction transaction = invocation.getArgument(0);
            setField(transaction, "id", transactionId);
            setField(transaction, "createdAt", OffsetDateTime.now());
            return transaction;
        });

        // When
        FinancialTransactionDto result = financialTransactionService.createTransaction(request);

        // Then
        assertEquals(1666L, result.vatAmountPennies()); // fraction method: 10000*20/120 = 1666
        verify(financialTransactionRepository).save(any(FinancialTransaction.class));
    }

    @Test
    @DisplayName("createTransaction - Calculates REDUCED VAT correctly (5%)")
    void testCreateTransaction_ReducedVatCalculation() {
        // Given
        CreateTransactionRequest request = new CreateTransactionRequest(10000L, VatRate.REDUCED, "TEST");
        when(financialTransactionRepository.save(any(FinancialTransaction.class))).thenAnswer(invocation -> {
            FinancialTransaction transaction = invocation.getArgument(0);
            setField(transaction, "id", transactionId);
            setField(transaction, "createdAt", OffsetDateTime.now());
            return transaction;
        });

        // When
        FinancialTransactionDto result = financialTransactionService.createTransaction(request);

        // Then
        assertEquals(476L, result.vatAmountPennies()); // fraction method: 10000*5/105 = 476 (round down)
        verify(financialTransactionRepository).save(any(FinancialTransaction.class));
    }

    @Test
    @DisplayName("createTransaction - Calculates ZERO VAT correctly (0%)")
    void testCreateTransaction_ZeroVatCalculation() {
        // Given
        CreateTransactionRequest request = new CreateTransactionRequest(10000L, VatRate.ZERO, "TEST");
        when(financialTransactionRepository.save(any(FinancialTransaction.class))).thenAnswer(invocation -> {
            FinancialTransaction transaction = invocation.getArgument(0);
            setField(transaction, "id", transactionId);
            setField(transaction, "createdAt", OffsetDateTime.now());
            return transaction;
        });

        // When
        FinancialTransactionDto result = financialTransactionService.createTransaction(request);

        // Then
        assertEquals(0L, result.vatAmountPennies()); // 0% of 10000 = 0
        verify(financialTransactionRepository).save(any(FinancialTransaction.class));
    }

    @Test
    @DisplayName("createTransaction - Calculates EXEMPT VAT correctly (0%)")
    void testCreateTransaction_ExemptVatCalculation() {
        // Given
        CreateTransactionRequest request = new CreateTransactionRequest(10000L, VatRate.EXEMPT, "TEST");
        when(financialTransactionRepository.save(any(FinancialTransaction.class))).thenAnswer(invocation -> {
            FinancialTransaction transaction = invocation.getArgument(0);
            setField(transaction, "id", transactionId);
            setField(transaction, "createdAt", OffsetDateTime.now());
            return transaction;
        });

        // When
        FinancialTransactionDto result = financialTransactionService.createTransaction(request);

        // Then
        assertEquals(0L, result.vatAmountPennies()); // EXEMPT = 0
        verify(financialTransactionRepository).save(any(FinancialTransaction.class));
    }

    @Test
    @DisplayName("getTransactionById - Success when transaction exists")
    void testGetTransactionById_Success() {
        // Given
        when(financialTransactionRepository.findById(transactionId)).thenReturn(Optional.of(testTransaction));

        // When
        Optional<FinancialTransactionDto> result = financialTransactionService.getTransactionById(transactionId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(transactionId, result.get().id());
        assertEquals(10000L, result.get().amountPennies());
        assertEquals(VatRate.STANDARD, result.get().vatRate());
        assertEquals(1666L, result.get().vatAmountPennies()); // fraction method: 10000*20/120 = 1666
        verify(financialTransactionRepository).findById(transactionId);
    }

    @Test
    @DisplayName("getTransactionById - Returns empty when transaction not found")
    void testGetTransactionById_NotFound() {
        // Given
        when(financialTransactionRepository.findById(transactionId)).thenReturn(Optional.empty());

        // When
        Optional<FinancialTransactionDto> result = financialTransactionService.getTransactionById(transactionId);

        // Then
        assertFalse(result.isPresent());
        verify(financialTransactionRepository).findById(transactionId);
    }

    @Test
    @DisplayName("getAllTransactions - Returns paginated results")
    void testGetAllTransactions_Paginated() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Page<FinancialTransaction> transactionPage = new PageImpl<>(List.of(testTransaction), pageable, 1);
        when(financialTransactionRepository.findAll(pageable)).thenReturn(transactionPage);

        // When
        Page<FinancialTransactionDto> result = financialTransactionService.getAllTransactions(pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(transactionId, result.getContent().get(0).id());
        verify(financialTransactionRepository).findAll(pageable);
    }

    @Test
    @DisplayName("findByReference - Success when transaction exists")
    void testFindByReference_Success() {
        // Given
        String reference = "ORDER-12345";
        when(financialTransactionRepository.findOneByReference(reference)).thenReturn(Optional.of(testTransaction));

        // When
        Optional<FinancialTransactionDto> result = financialTransactionService.findByReference(reference);

        // Then
        assertTrue(result.isPresent());
        assertEquals(transactionId, result.get().id());
        assertEquals("ORDER-12345", result.get().description());
        verify(financialTransactionRepository).findOneByReference(reference);
    }

    @Test
    @DisplayName("findByReference - Returns empty when transaction not found")
    void testFindByReference_NotFound() {
        // Given
        String reference = "NONEXISTENT";
        when(financialTransactionRepository.findOneByReference(reference)).thenReturn(Optional.empty());

        // When
        Optional<FinancialTransactionDto> result = financialTransactionService.findByReference(reference);

        // Then
        assertFalse(result.isPresent());
        verify(financialTransactionRepository).findOneByReference(reference);
    }

    @Test
    @DisplayName("DTO mapping - Converts FinancialTransaction entity to DTO correctly")
    void testDtoMapping_CorrectFieldMapping() {
        // Given
        when(financialTransactionRepository.findById(transactionId)).thenReturn(Optional.of(testTransaction));

        // When
        Optional<FinancialTransactionDto> result = financialTransactionService.getTransactionById(transactionId);

        // Then
        assertTrue(result.isPresent());
        FinancialTransactionDto dto = result.get();
        assertEquals(testTransaction.getId(), dto.id());
        assertEquals(testTransaction.getTenantId(), dto.tenantId());
        assertEquals(testTransaction.getAmountPennies(), dto.amountPennies());
        assertEquals(testTransaction.getVatRate(), dto.vatRate());
        assertEquals(testTransaction.calculateVatAmount(), dto.vatAmountPennies());
        assertEquals(testTransaction.getReference(), dto.description());
        assertEquals(testTransaction.getCreatedAt(), dto.createdAt());
    }

    @Test
    @DisplayName("createTransaction - Handles negative amount correctly (refund)")
    void testCreateTransaction_NegativeAmount() {
        // Given
        CreateTransactionRequest refundRequest = new CreateTransactionRequest(-5000L, VatRate.STANDARD, "REFUND-123");
        when(financialTransactionRepository.save(any(FinancialTransaction.class))).thenAnswer(invocation -> {
            FinancialTransaction transaction = invocation.getArgument(0);
            setField(transaction, "id", transactionId);
            setField(transaction, "createdAt", OffsetDateTime.now());
            return transaction;
        });

        // When
        FinancialTransactionDto result = financialTransactionService.createTransaction(refundRequest);

        // Then
        assertEquals(-5000L, result.amountPennies());
        assertEquals(-833L, result.vatAmountPennies()); // fraction method: -5000*20/120 = -833 (truncate toward zero)
        verify(financialTransactionRepository).save(any(FinancialTransaction.class));
    }

    @Test
    @DisplayName("createTransaction - Handles null description correctly")
    void testCreateTransaction_NullDescription() {
        // Given
        CreateTransactionRequest requestWithoutDescription = new CreateTransactionRequest(10000L, VatRate.STANDARD, null);
        when(financialTransactionRepository.save(any(FinancialTransaction.class))).thenAnswer(invocation -> {
            FinancialTransaction transaction = invocation.getArgument(0);
            setField(transaction, "id", transactionId);
            setField(transaction, "createdAt", OffsetDateTime.now());
            return transaction;
        });

        // When
        FinancialTransactionDto result = financialTransactionService.createTransaction(requestWithoutDescription);

        // Then
        assertNull(result.description());
        verify(financialTransactionRepository).save(any(FinancialTransaction.class));
    }

    @Test
    @DisplayName("getSummary - Returns correct aggregation of transactions")
    void testGetSummary() {
        // Given: mix of revenue and expense transactions with different VAT rates.
        // Post-CQ-02, getSummary() calls two JPQL aggregate queries instead of
        // findAll()+reduce — the stubs now return the pre-aggregated result rows
        // directly. The expected totals below mirror what a real DB run would
        // produce against: (STANDARD +20000), (ZERO +5000), (STANDARD -3000).
        //
        //   totalRevenue  = 20000 + 5000      = 25000
        //   totalExpenses = abs(-3000)        = 3000
        //   totalVat      = (20000*20/100)    = 4000
        //                 + (  5000* 0/100)   = 0
        //                 + (-3000*20/100)    = -600
        //                                       -----
        //                                       3400
        //   count         = 3
        FinancialAggregateRow aggregate = new FinancialAggregateRow(
                25000L,   // totalRevenuePennies
                3000L,    // totalExpensesPennies
                3400L,    // totalVatPennies
                3L);      // transactionCount
        List<FinancialVatRow> vatRows = List.of(
                // STANDARD: +20000 + -3000 = 17000; vat (20000*20/100) + (-3000*20/100) = 3400
                new FinancialVatRow(VatRate.STANDARD, 17000L, 3400L, 2L),
                // ZERO: +5000; vat = 0
                new FinancialVatRow(VatRate.ZERO, 5000L, 0L, 1L));
        when(financialTransactionRepository.aggregateForCurrentTenant()).thenReturn(aggregate);
        when(financialTransactionRepository.aggregateByVatRate()).thenReturn(vatRows);

        // When
        FinancialSummaryDto summary = financialTransactionService.getSummary();

        // Then
        assertEquals(25000L, summary.totalRevenuePennies());   // 20000 + 5000
        assertEquals(3000L, summary.totalExpensesPennies());    // abs(-3000)
        assertEquals(22000L, summary.netAmountPennies());       // 25000 - 3000
        assertEquals(3400L, summary.totalVatPennies());         // (20000*0.2) + 0 + (-3000*0.2) = 4000 + 0 + (-600) = 3400
        assertEquals(3, summary.transactionCount());
        assertEquals(2, summary.vatBreakdown().size());         // STANDARD + ZERO
    }

    @Test
    @DisplayName("getSummary - Returns zeros when no transactions exist")
    void testGetSummary_Empty() {
        // COALESCE(SUM(...), 0L) in the JPQL guarantees zero rows → 0L (not NULL).
        when(financialTransactionRepository.aggregateForCurrentTenant())
                .thenReturn(new FinancialAggregateRow(0L, 0L, 0L, 0L));
        when(financialTransactionRepository.aggregateByVatRate()).thenReturn(List.of());

        FinancialSummaryDto summary = financialTransactionService.getSummary();

        assertEquals(0L, summary.totalRevenuePennies());
        assertEquals(0L, summary.totalExpensesPennies());
        assertEquals(0L, summary.netAmountPennies());
        assertEquals(0L, summary.totalVatPennies());
        assertEquals(0, summary.transactionCount());
        assertTrue(summary.vatBreakdown().isEmpty());
    }

    @Test
    @DisplayName("createTransaction - Handles large amount correctly")
    void testCreateTransaction_LargeAmount() {
        // Given
        CreateTransactionRequest largeAmountRequest = new CreateTransactionRequest(100000000L, VatRate.STANDARD, "LARGE-PAYMENT");
        when(financialTransactionRepository.save(any(FinancialTransaction.class))).thenAnswer(invocation -> {
            FinancialTransaction transaction = invocation.getArgument(0);
            setField(transaction, "id", transactionId);
            setField(transaction, "createdAt", OffsetDateTime.now());
            return transaction;
        });

        // When
        FinancialTransactionDto result = financialTransactionService.createTransaction(largeAmountRequest);

        // Then
        assertEquals(100000000L, result.amountPennies()); // £1,000,000.00
        assertEquals(16666666L, result.vatAmountPennies()); // fraction method: 100000000*20/120 = 16666666 (round down)
        verify(financialTransactionRepository).save(any(FinancialTransaction.class));
    }
}
