# Testing Patterns

**Analysis Date:** 2026-04-07

## Test Framework

**Frontend:**
- Runner: Jest 29.7.0
- Config: `frontend/jest.config.js`
- Testing Library: `@testing-library/react` 16.3.0
- User interaction: `@testing-library/user-event` 14.5.1
- Assertions: `@testing-library/jest-dom` 6.1.5
- E2E: Playwright 1.59.1 (installed but not heavily used in unit tests)

**Backend:**
- Framework: JUnit Jupiter (JUnit 5)
- Runner: Gradle test task with `useJUnitPlatform()`
- Mocking: Mockito 5.x (via `MockitoExtension`)
- Integration: TestContainers 1.21.3 for PostgreSQL
- In-memory DB: H2 for lightweight unit tests
- Configuration: `core-java/build.gradle.kts`

**Go:**
- Standard library: `testing` package
- HTTP testing: `net/http/httptest`
- No external mocking framework (manual mocks implemented)
- Example: `internal/middleware/jwt_test.go`

**Run Commands:**

Frontend:
```bash
npm test                  # Run all tests once
npm run test:watch       # Watch mode for continuous testing
npm run test:coverage    # Run with coverage report
```

Backend:
```bash
./gradlew test          # Run unit tests (excluding integration tests)
./gradlew test -PincludeIntegration  # Run all tests including TestContainers
./gradlew test --info   # Verbose output
```

## Test File Organization

**Frontend:**
- Location: Co-located in `__tests__` directories adjacent to tested code
- Examples:
  - `frontend/app/auth/signin/__tests__/page.test.tsx` (tests `page.tsx`)
  - `frontend/app/dashboard/__tests__/page.test.tsx` (tests `page.tsx`)
  - `frontend/lib/__tests__/api-client.test.ts` (tests `api-client.ts`)
- Naming: `*.test.tsx` or `*.test.ts` suffix

**Backend:**
- Location: Mirror source structure under `src/test/java/`
- Examples:
  - `core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java`
  - `core-java/src/test/java/uk/jtoye/core/order/OrderServiceTest.java`
  - `core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java`
- Naming: `<Class>Test.java` or `<Class>IntegrationTest.java`
- Tags: Tests tagged with `@Tag("testcontainers")` are excluded by default, run with `-PincludeIntegration`

**Go:**
- Location: Same package, `*_test.go` files
- Example: `internal/middleware/jwt_test.go` tests `jwt.go`
- Naming: `<module>_test.go`

## Test Structure

**Frontend (Jest + React Testing Library):**

```typescript
// Setup/teardown pattern
describe('SignIn Page', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  // Test structure
  it('should render the sign-in page', () => {
    render(<SignInPage />)
    expect(screen.getByText("J'Toye OaaS")).toBeInTheDocument()
  })

  it('should call signIn when button is clicked', () => {
    render(<SignInPage />)
    const signInButton = screen.getByRole('button', { name: /sign in with keycloak/i })
    fireEvent.click(signInButton)
    expect(signIn).toHaveBeenCalledWith('keycloak', { callbackUrl: '/dashboard' })
  })
})
```

**Backend (JUnit 5 + Mockito):**

```java
@ExtendWith(MockitoExtension.class)
class ShopServiceTest {
    
    @Mock
    private ShopRepository shopRepository;
    
    @InjectMocks
    private ShopService shopService;
    
    private UUID tenantId;
    private Shop testShop;
    
    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        
        // Create test fixtures
        testShop = new Shop();
        setField(testShop, "id", UUID.randomUUID());
        testShop.setTenantId(tenantId);
        testShop.setName("Test Shop");
    }
    
    @Test
    @DisplayName("Should create shop with valid request")
    void testCreateShop() {
        // Arrange
        CreateShopRequest request = new CreateShopRequest();
        request.setName("New Shop");
        
        // Act
        ShopDto result = shopService.createShop(request);
        
        // Assert
        assertNotNull(result);
        assertEquals("New Shop", result.getName());
    }
}
```

**Go (Standard Testing):**

```go
func TestJWTMiddleware_Validate_MissingAuthHeader(t *testing.T) {
    // Arrange
    logger, _ := zap.NewProduction()
    middleware := NewJWTMiddleware("http://example.com/jwks", "http://example.com", logger)
    
    gin.SetMode(gin.TestMode)
    w := httptest.NewRecorder()
    c, _ := gin.CreateTestContext(w)
    c.Request = httptest.NewRequest("GET", "/test", nil)
    
    // Act
    middleware.Validate()(c)
    
    // Assert
    if w.Code != http.StatusUnauthorized {
        t.Errorf("Expected status 401, got %d", w.Code)
    }
}
```

## Test Structure Patterns

**Suite Organization:**
- Group related tests with `describe()` block (frontend)
- Group related tests with test class annotated `@ExtendWith` (backend)
- Use `@DisplayName` for human-readable test names (backend)
- Use `it('should...')` for behavior-driven test names (frontend)

**Setup/Teardown:**
- Frontend: `beforeEach()` clears mocks, renders component fresh for each test
- Backend: `@BeforeEach` sets up mocks, creates test fixtures (using reflection for auto-generated fields)
- Go: Typically inline in each test function

**Arrange-Act-Assert Pattern:**
- Comments in tests clearly mark these three phases
- Consistent structure across all test files
- Mock setup occurs in arrange phase

## Mocking

**Frontend (Jest):**
- Framework: Jest built-in `jest.mock()`
- Location: `jest.setup.js` for global mocks (next-auth, next/navigation, framer-motion)
- Patterns:
```typescript
// Global mocks in jest.setup.js
jest.mock('next-auth/react', () => ({
  useSession: jest.fn(() => ({
    data: { user: { name: 'Test User', email: 'test@example.com' }, ... },
    status: 'authenticated',
  })),
  signIn: jest.fn(),
  signOut: jest.fn(),
}))

// Local mocks in test files
jest.mock('@/lib/api-client')
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

mockedApiClient.get.mockImplementation((url: string) => {
  if (url === '/financial-transactions/summary') {
    return Promise.resolve({ data: { ... } })
  }
  return Promise.resolve({ data: { content: [], totalElements: 0 } })
})
```

**Backend (Mockito):**
- Framework: Mockito 5.x via `@Mock` and `@InjectMocks` annotations
- Return type inference with `lenient().when().thenAnswer()`
- Argument captor for verification: `ArgumentCaptor<T> captor = ArgumentCaptor.forClass(T.class)`
- Mocking behavior:
```java
@Mock
private ShopRepository shopRepository;

@InjectMocks
private ShopService shopService;

// Mock setup in @BeforeEach
lenient().when(shopMapper.toDto(any(Shop.class))).thenAnswer(invocation -> {
    Shop shop = invocation.getArgument(0);
    ShopDto dto = new ShopDto();
    // ... populate dto
    return dto;
});

// Verification in tests
verify(shopRepository, times(1)).saveAndFlush(any(Shop.class));
verify(shopMapper).toDto(any(Shop.class));
```

**Go:**
- No external mocking framework
- Manual mock implementations or interfaces
- HTTP test server using `httptest.NewRecorder()` and `httptest.NewRequest()`

**What to Mock:**
- External service calls (API clients, database)
- Time-dependent operations (use mocks that return fixed time)
- File system operations
- Random number generation

**What NOT to Mock:**
- Business logic you're testing
- Domain entities (create real instances)
- Utility functions (test with real implementations)
- Pure functions

## Fixtures and Factories

**Frontend:**
- Minimal fixtures (usually mocked API responses)
- Mock data defined inline in test or in defaultMock function:
```typescript
const defaultMock = (url: string) => {
  if (url === '/financial-transactions/summary') {
    return Promise.resolve({ 
      data: { totalRevenuePennies: 0, totalExpensesPennies: 0, ... } 
    })
  }
  return Promise.resolve({ data: { content: [], totalElements: 0 } })
}
```

**Backend:**
- Test fixtures created in `@BeforeEach` using reflection for auto-generated fields
- Helper method pattern:
```java
private void setField(Object target, String fieldName, Object value) {
    try {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    } catch (Exception e) {
        throw new RuntimeException("Failed to set field " + fieldName, e);
    }
}

// Usage in setup
testShop = new Shop();
setField(testShop, "id", shopId);
testShop.setTenantId(tenantId);
testShop.setName("Test Shop");
```

**Location:**
- Fixtures created inline in test methods
- Reusable fixtures in `@BeforeEach` setup methods
- Factory classes not currently used; consider adding if many tests share complex fixtures

## Coverage

**Frontend:**
- Config: `jest.config.js` specifies `collectCoverageFrom`
- Coverage paths: `app/**/*.{js,jsx,ts,tsx}`, `components/**/*`, `lib/**/*`, `types/**/*`
- Excluded: `.d.ts` files, `node_modules`, `.next`
- Target: Not enforced but encouraged
- View coverage: `npm run test:coverage` generates coverage report in `coverage/` directory

**Backend:**
- No strict coverage requirement
- Can be added via Gradle plugin if needed
- Focus: Service layer (business logic), repository layer (data access), controller layer (integration points)
- Excluded: Entity models, configuration classes

## Test Types

**Unit Tests:**
- Frontend: Test individual components in isolation with mocked children/props
- Backend: Test service methods with mocked dependencies (repositories, external services)
- Go: Test individual functions with mocked HTTP calls
- Approach: Fast, isolated, no external dependencies

**Integration Tests:**
- Backend: Tests annotated with `@Tag("testcontainers")` using TestContainers PostgreSQL
- Run: `./gradlew test -PincludeIntegration` (default excluded to save CI time)
- Scope: Tests database queries, state machines, transactional behavior with real database
- Examples: `ShopServiceTest` (services), `MultiTenantIsolationIntegrationTest` (security), `AuditIntegrationTest` (audit log)

**E2E Tests:**
- Framework: Playwright 1.59.1 (installed in dependencies but not extensively used)
- Not currently prominent in unit test suite
- Intended for full user flow testing (login → dashboard → action → verification)

## Common Patterns

**Async Testing (Frontend):**
```typescript
it('should render dashboard heading after loading', async () => {
  render(<DashboardPage />)
  
  await waitFor(() => {
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
  })
})
```

**Error Testing (Backend):**
```java
@Test
@DisplayName("Should throw ResourceNotFoundException when shop not found")
void testGetNonExistentShop() {
    when(shopRepository.findById(any(UUID.class)))
        .thenReturn(Optional.empty());
    
    assertThrows(ResourceNotFoundException.class, () -> {
        shopService.getShopById(UUID.randomUUID());
    });
}
```

**Verifying Function Calls (Frontend):**
```typescript
it('should make API calls to fetch dashboard data', async () => {
  render(<DashboardPage />)
  
  await waitFor(() => {
    expect(mockedApiClient.get).toHaveBeenCalledWith('/shops?size=1')
    expect(mockedApiClient.get).toHaveBeenCalledWith('/products?size=1')
  })
})
```

**Mocking Chart Libraries (Frontend):**
```typescript
jest.mock('recharts', () => ({
  ResponsiveContainer: ({ children }) => <div>{children}</div>,
  PieChart: ({ children }) => <div>{children}</div>,
  Bar: () => <div />,
  // ... other chart components
}))
```

**ResizeObserver Global (Frontend):**
```typescript
global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver
```

## Test Execution Environment

**Frontend:**
- Environment: `jest-environment-jsdom` (browser-like environment)
- Setup: `jest.setup.js` runs before tests
- Environment variables: Set in setup file
  - `NEXT_PUBLIC_API_URL = 'http://localhost:8080/api'`
  - `NEXTAUTH_URL = 'http://localhost:3000'`

**Backend:**
- JUnit Platform execution
- Docker API version: `DOCKER_API_VERSION=1.45` for TestContainers compatibility
- Tenant Context: Manually set in `@BeforeEach` with `TenantContext.set(tenantId)`
- Database: In-memory H2 for unit tests, real PostgreSQL via TestContainers for integration tests

## Known Testing Considerations

**Frontend:**
- Chart components (recharts) require mocking for jsdom environment (no SVG support)
- ResizeObserver must be mocked globally for chart library compatibility
- Framer Motion animations mocked to prevent animation delays in tests
- Next.js navigation hooks (useRouter, usePathname) mocked globally

**Backend:**
- Auto-generated fields (id, createdAt) cannot be set via setters; use reflection helper
- Tenant context must be set before service methods are called
- Lenient mocking used for mocks not verified in every test to avoid `UnnecessaryStubbingException`
- MapStruct mapper behavior mocked with argument matchers and thenAnswer

---

*Testing analysis: 2026-04-07*
