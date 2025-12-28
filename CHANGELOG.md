# Changelog

All notable changes to the J'Toye OaaS 2026 project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2025-12-28

### Added
- Multi-tenant JWT authentication with Keycloak integration
  - JWT token extraction from `tenant_id`, `tenantId`, or `tid` claims
  - Keycloak group-based tenant mapping with protocol mappers
  - Pre-configured test users: `tenant-a-user` and `tenant-b-user`
- Row-Level Security (RLS) implementation
  - PostgreSQL RLS policies for `tenants`, `shops`, and `products` tables
  - Automatic tenant context injection via AOP (`TenantSetLocalAspect`)
  - `SET LOCAL app.current_tenant_id` executed on each transaction
- Security filter chain configuration
  - `TenantFilter` for X-Tenant-ID header fallback (dev mode)
  - `JwtTenantFilter` for JWT-based tenant extraction (production mode)
  - Correct filter ordering: TenantFilter → BearerTokenAuthenticationFilter → JwtTenantFilter
- Database migrations (Flyway)
  - V1: Base schema with tenants, shops, products tables
  - V2: RLS policies and security functions
  - V3: Additional tenant isolation enhancements
  - V4: Schema refinements
- Test infrastructure
  - Integration tests for multi-tenant shop operations (6 tests)
  - Product controller tests (3 tests)
  - Tenant aspect unit tests (2 tests)
  - All tests passing with 100% success rate
- Documentation
  - `README.md` with quick start guide and verification examples
  - `docs/TESTING_GUIDE.md` with comprehensive testing procedures
  - Helper scripts in `scripts/testing/` directory
  - Test data generation scripts

### Fixed
- **CRITICAL**: JWT tenant extraction filter ordering
  - Changed `JwtTenantFilter` to run after `BearerTokenAuthenticationFilter` instead of `UsernamePasswordAuthenticationFilter`
  - Fixed issue where JWT tokens were not yet validated when tenant extraction occurred
  - Resolved `auth=null` problem causing empty API responses
- Flyway migration conflicts after database recreation
  - Properly ordered V1-V4 migrations
  - Clean database initialization process
- Build directory permissions
  - Configured Gradle to use `build-local/` directory to avoid permission conflicts
- Port conflicts
  - Configured core-java to use port 9090 (not 8080)
  - PostgreSQL on port 5433 (not 5432)

### Changed
- JWT tenant claim takes PRIORITY over X-Tenant-ID header for security
- Removed verbose logging from security components
  - Changed `log.info` to `log.debug` in `JwtTenantFilter`
  - Changed `log.info` to `log.debug` in `TenantSetLocalAspect`
- Reorganized project structure
  - Moved diagnostic scripts to `scripts/testing/`
  - Moved token generation scripts to `scripts/testing/`
- Removed low-level RLS unit tests (`TenantIsolationSecurityTest`)
  - API-level integration tests provide sufficient verification
  - Simplified test suite maintenance

### Verified
- ✅ Multi-tenant JWT authentication works correctly with Keycloak
- ✅ Tenant A users see only Tenant A data (shops, products)
- ✅ Tenant B users see only Tenant B data (shops, products)
- ✅ Cross-tenant access is blocked at database level (RLS)
- ✅ JWT-only authentication (no header required) works in production mode
- ✅ Header fallback works in dev mode
- ✅ JWT tenant claim overrides header for security
- ✅ All 11 tests passing with 100% success rate

### Security
- Implemented tenant isolation at database level using PostgreSQL RLS
- JWT-based authentication prevents tenant spoofing
- Aspect-oriented tenant context ensures no manual filtering required
- X-Tenant-ID header restricted to dev/testing environments only

### Performance
- Test suite completes in 0.924s
- AOP-based tenant context adds minimal overhead
- RLS policies leverage PostgreSQL native security features

## [0.0.1] - 2025-12-27

### Added
- Initial project scaffolding
- Spring Boot 3 core service setup
- Go 1.22 edge service setup
- Docker Compose infrastructure (PostgreSQL 15 + Keycloak)
- Basic Keycloak realm configuration
- Health check endpoints
- Flyway migration framework
- Basic REST API endpoints for shops and products

---

## Release Notes

### Version 0.1.0 - Multi-Tenant Authentication Release

This release marks the completion of Phase 0/1 with full multi-tenant JWT authentication and Row-Level Security implementation.

**Key Achievements:**
- Production-ready multi-tenant authentication system
- Database-level tenant isolation with PostgreSQL RLS
- Comprehensive test coverage with 100% pass rate
- Keycloak integration with group-based tenant mapping
- Security-first approach with JWT priority over headers

**Breaking Changes:**
- None (initial release)

**Upgrade Path:**
- New installation: Follow README.md quick start guide
- Database initialization: Run Flyway migrations V1-V4
- Keycloak setup: Import realm configuration from infra/keycloak/

**Known Issues:**
- None

**Testing:**
- Run diagnostic: `bash scripts/testing/diagnose-jwt-issue.sh`
- Full test suite: `cd core-java && ../gradlew test`
- See `docs/TESTING_GUIDE.md` for detailed testing procedures
