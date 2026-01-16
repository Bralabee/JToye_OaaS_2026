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
import uk.jtoye.core.finance.dto.FinancialTransactionDto;
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
        assertEquals(2000L, result.vatAmountPennies()); // 20% of 10000
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
        assertEquals(2000L, result.vatAmountPennies()); // 20% of 10000 = 2000
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
        assertEquals(500L, result.vatAmountPennies()); // 5% of 10000 = 500
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
        assertEquals(2000L, result.get().vatAmountPennies()); // 20% VAT
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
        assertEquals(-1000L, result.vatAmountPennies()); // 20% of -5000 = -1000
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
        assertEquals(20000000L, result.vatAmountPennies()); // £200,000.00 (20% VAT)
        verify(financialTransactionRepository).save(any(FinancialTransaction.class));
    }
}
