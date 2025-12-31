# Documentation Index - J'Toye OaaS 2026

**Last Updated:** December 30, 2025
**Project Status:** ✅ Phase 2.1 Complete (32/36 tests passing)

---

## 📁 Directory Structure

```
docs/
├── guides/              # User and testing guides
│   ├── TESTING_GUIDE.md
│   └── APPLICATION_VERIFICATION.md
├── planning/            # Project planning and roadmaps
│   ├── PHASE_1_PLAN.md
│   ├── PHASE_1_READINESS.md
│   └── NEXT_SESSION_CHECKLIST.md
├── status/              # Project status and summaries
│   ├── PROJECT_STATUS.md
│   ├── IMPLEMENTATION_SUMMARY.md
│   ├── SESSION_HANDOFF.md
│   └── README_SESSION_SUMMARY.md
├── setup/               # Setup and configuration files
└── AI_CONTEXT.md        # AI agent guidelines
```

---

## Quick Links

### Essential Reading (Root Directory)
1. **[README.md](../README.md)** - Start here! Quick start guide and overview
2. **[docs/DOCKER_QUICK_START.md](DOCKER_QUICK_START.md)** - ⭐ NEW! One-command Docker setup guide
3. **[docs/DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)** - Complete deployment guide (local, Docker, K8s)
4. **[docs/CHANGELOG.md](CHANGELOG.md)** - Version history and changes
5. **[docs/status/PROJECT_STATUS.md](status/PROJECT_STATUS.md)** - Current project status and health
6. **[docs/CREDENTIALS.md](CREDENTIALS.md)** - Development credentials and test users

### Testing & User Guides
7. **[docs/guides/USER_GUIDE.md](guides/USER_GUIDE.md)** - ⭐ Complete user guide for manual API testing
8. **[docs/guides/TESTING_GUIDE.md](guides/TESTING_GUIDE.md)** - Automated testing procedures
9. **[docs/guides/APPLICATION_VERIFICATION.md](guides/APPLICATION_VERIFICATION.md)** - Verification procedures

### Planning & Roadmap
10. **[docs/planning/PHASE_1_PLAN.md](planning/PHASE_1_PLAN.md)** - Phase 1 implementation plan and progress
11. **[docs/planning/PHASE_1_READINESS.md](planning/PHASE_1_READINESS.md)** - Phase 1 readiness assessment
12. **[docs/planning/NEXT_SESSION_CHECKLIST.md](planning/NEXT_SESSION_CHECKLIST.md)** - Checklist for next session

### Architecture & Implementation
13. **[docs/status/IMPLEMENTATION_SUMMARY.md](status/IMPLEMENTATION_SUMMARY.md)** - Architecture and technical details
14. **[docs/status/SESSION_HANDOFF.md](status/SESSION_HANDOFF.md)** - Session notes and handoff
15. **[docs/AI_CONTEXT.md](AI_CONTEXT.md)** - ⭐ UPDATED! AI agent guidelines with Docker networking details

### Development Guidelines
12. **[docs/AI_CONTEXT.md](AI_CONTEXT.md)** - Security & compliance directives for AI agents

---

## Document Summaries

### 📘 ../README.md
**Purpose:** Project overview and quick start guide
**Audience:** All developers, new team members
**Key Sections:**
- What's included (core-java, edge-go, infra)
- Quick start instructions
- Configuration details (ports, environment variables)
- Security/OIDC setup with Keycloak
- Multi-tenant JWT authentication examples
- Current status and roadmap

**When to read:** First document for anyone joining the project

---

### 📊 status/PROJECT_STATUS.md
**Purpose:** Real-time project health and status
**Audience:** Project managers, stakeholders, developers
**Key Sections:**
- Quick status summary (components, tests, status)
- Current capabilities (working features)
- Test results
- Infrastructure status
- Database migrations status
- Security status
- Known issues
- Roadmap (Phase 1-4)

**When to read:**
- Daily standup preparation
- Before deployments
- When checking project health

---

### 🗓️ planning/PHASE_1_PLAN.md
**Purpose:** Complete Phase 1 implementation plan with progress tracking
**Audience:** Developers, project managers
**Key Sections:**
- Implementation strategy and principles
- Phase 1 objectives (Envers, Orders, State Machine, Customer, Reports)
- Database migrations plan (V4-V7)
- Testing strategy and metrics (19/19 tests = 100%)
- Implementation order with step-by-step completion
- Progress tracking with commits
- Decision log and next steps

**Status:** ✅ Core Features Complete (Envers + Orders)

**When to read:**
- Before starting Phase 1 work
- When planning next features
- When reviewing implementation progress

---

### 📖 guides/USER_GUIDE.md ⭐ NEW
**Purpose:** Complete user guide for manual API testing
**Audience:** Developers, QA engineers, API users
**Key Sections:**
- Quick start with authentication setup
- Complete API endpoint reference (Shops, Products, Orders)
- Manual testing with cURL (copy-paste ready examples)
- Manual testing with Postman (collection setup)
- Multi-tenant isolation testing
- Common workflows (order lifecycle, bulk operations)
- Comprehensive troubleshooting
- Complete test script in appendix

**When to read:**
- **PRIMARY GUIDE** for anyone testing the API manually
- When learning the API endpoints
- When setting up Postman collections
- When troubleshooting API issues

---

### 🧪 guides/TESTING_GUIDE.md
**Purpose:** Automated testing procedures and diagnostics
**Audience:** QA engineers, developers
**Key Sections:**
- Multi-tenant JWT authentication testing
- Prerequisites and setup
- Quick diagnostic test script
- Detailed testing procedures
- Test scenarios (JWT-only, header fallback, security)
- Troubleshooting guide
- Success criteria

**When to read:**
- Before running automated tests
- When verifying tenant isolation
- When troubleshooting authentication issues

---

### 🏗️ status/IMPLEMENTATION_SUMMARY.md
**Purpose:** Deep technical architecture and implementation details
**Audience:** Senior developers, architects, security engineers
**Key Sections:**
- Executive summary with metrics
- Architecture overview (authentication flow)
- Key components (JWT filter, tenant context, RLS)
- Critical fixes and resolutions
- Testing strategy
- Verification results
- Technical decisions
- Lessons learned
- Production readiness checklist

**When to read:**
- When understanding system architecture
- During code reviews
- When making architectural decisions
- Before production deployment

---

### 📝 docs/CHANGELOG.md
**Purpose:** Complete version history and release notes
**Audience:** All team members, stakeholders
**Key Sections:**
- Version 0.7.0 (Phase 2.1): Complete CRUD + Infrastructure
- Version 0.6.0 (Phase 1): Full CRUD implementation
- Version 0.2.0 (Phase 1): Envers + Orders
- Version 0.1.0 (Phase 0/1): Multi-tenant foundation
- Version 0.0.1: Initial scaffolding

**When to read:**
- Before releases
- When reviewing changes between versions
- During release planning

---

### 🔑 docs/CREDENTIALS.md
**Purpose:** Development credentials and test users
**Audience:** Developers, QA engineers
**Key Sections:**
- Database credentials
- Keycloak admin credentials
- Test users for different tenants
- JWT token acquisition examples

**When to read:**
- During initial setup
- When needing test user credentials
- When testing different tenant contexts

---

### 🔒 AI_CONTEXT.md
**Purpose:** Security and compliance directives for AI code assistants
**Audience:** AI agents, developers using AI assistants
**Key Sections:**
- Security principles (defensive security only)
- Prohibited actions
- Allowed activities
- Project guardrails

**When to read:**
- When working with AI assistants
- When setting up AI coding tools
- During security audits

---

### 📋 planning/PHASE_1_READINESS.md
**Purpose:** Assessment of Phase 1 readiness
**Audience:** Project managers, developers
**When to read:** Before beginning Phase 1 implementation

---

### ✅ planning/NEXT_SESSION_CHECKLIST.md
**Purpose:** Checklist for continuity between sessions
**Audience:** Developers
**When to read:** Start of each development session

---

### 📄 guides/APPLICATION_VERIFICATION.md
**Purpose:** Legacy verification procedures
**Audience:** QA engineers
**When to read:** For historical reference (superseded by TESTING_GUIDE.md)

---

### 📓 status/SESSION_HANDOFF.md
**Purpose:** Detailed session notes and handoff
**Audience:** Developers
**When to read:** When reviewing previous session work

---

### 📖 status/README_SESSION_SUMMARY.md
**Purpose:** Quick session summary
**Audience:** Developers, project managers
**When to read:** For historical reference

---

## Documentation Organization

### Root Directory
```
/
└── README.md                      # Project overview & quick start
```

### docs/ Directory
```
docs/
├── guides/
│   ├── USER_GUIDE.md              # Manual API testing guide ⭐
│   ├── TESTING_GUIDE.md           # Automated testing procedures
│   └── APPLICATION_VERIFICATION.md # Legacy verification
├── planning/
│   ├── PHASE_1_PLAN.md            # Phase 1 implementation plan ⭐
│   ├── PHASE_1_READINESS.md       # Phase 1 readiness assessment
│   └── NEXT_SESSION_CHECKLIST.md  # Session checklist
├── status/
│   ├── PROJECT_STATUS.md          # Current status & health ⭐
│   ├── IMPLEMENTATION_SUMMARY.md  # Technical architecture ⭐
│   ├── SESSION_HANDOFF.md         # Session notes
│   └── README_SESSION_SUMMARY.md  # Session summary
├── setup/                          # Setup files
├── AI_CONTEXT.md                  # AI agent guidelines
├── CHANGELOG.md                   # Version history
├── CREDENTIALS.md                 # Dev credentials
└── DOCUMENTATION_INDEX.md         # This file
```

⭐ = Most frequently updated documents

---

## Documentation Maintenance

### When to Update Each Document

| Document | Update Trigger | Frequency |
|----------|---------------|-----------|
| README.md | Major features, configuration changes | As needed |
| CHANGELOG.md | docs/CHANGELOG.md | Every release |
| planning/PHASE_1_PLAN.md | Phase 1 progress, commits | Per session |
| status/PROJECT_STATUS.md | Test results, deployments | Daily/Weekly |
| guides/TESTING_GUIDE.md | New test procedures, API changes | As needed |
| status/IMPLEMENTATION_SUMMARY.md | Architecture changes | Major releases |
| AI_CONTEXT.md | Security policy changes | Rarely |

### Documentation Review Schedule

- **Weekly:** PROJECT_STATUS.md, PHASE_1_PLAN.md - Verify all status indicators are current
- **Before Each Release:** All documents - Ensure consistency and accuracy
- **Monthly:** Documentation index - Check for outdated or missing docs
- **Quarterly:** Full documentation audit - Review structure and organization

---

## Quick Reference: Which Doc Should I Read?

| Question | Document | Section |
|----------|----------|---------|
| How do I test the API manually? | **guides/USER_GUIDE.md** ⭐ | Complete guide |
| How do I start the application? | README.md | Quick start |
| How do I create an order? | guides/USER_GUIDE.md | Create Order (cURL/Postman) |
| What are all the API endpoints? | guides/USER_GUIDE.md | API Endpoints |
| How do I get a JWT token? | guides/USER_GUIDE.md | Authentication Setup |
| Are all tests passing? | status/PROJECT_STATUS.md | Test Results |
| What's Phase 1 status? | planning/PHASE_1_PLAN.md | Progress Tracking |
| How do I test JWT authentication? | guides/TESTING_GUIDE.md | Quick Test |
| How does the filter chain work? | status/IMPLEMENTATION_SUMMARY.md | Key Components |
| What changed in version 0.7.0? | docs/CHANGELOG.md | [0.7.0] |
| What features are complete? | status/PROJECT_STATUS.md | Current Capabilities |
| How do I verify RLS? | guides/TESTING_GUIDE.md | Verify RLS |
| What's the architecture? | status/IMPLEMENTATION_SUMMARY.md | Architecture Overview |
| What's planned next? | planning/PHASE_1_PLAN.md | Next Steps |
| How do orders work? | planning/PHASE_1_PLAN.md | Order Entity & CRUD |
| How do I troubleshoot API errors? | guides/USER_GUIDE.md | Troubleshooting |
| What are common workflows? | guides/USER_GUIDE.md | Common Workflows |

---

## Documentation Standards

### Writing Guidelines

1. **Clear Headings:** Use descriptive headers with emojis for visual scanning
2. **Code Examples:** Always include runnable code examples
3. **Expected Output:** Show what success looks like
4. **Troubleshooting:** Include common issues and solutions
5. **Update Dates:** Include "Last Updated" at top of each doc
6. **Cross-References:** Link to related documents
7. **Audience:** Identify intended audience at start of each doc

### Formatting Conventions

- **Commands:** Use bash code blocks with `# comments`
- **Paths:** Use backticks for file paths
- **URLs:** Use angle brackets for URLs
- **Status:** Use ✅ ❌ 🔄 for status indicators
- **Important:** Use **bold** for critical information
- **Code:** Use triple backticks with language specification

### Version Control

- All documentation tracked in git
- Update CHANGELOG.md with documentation changes
- Commit docs with related code changes
- Review docs in pull requests

---

## External Documentation

### Frameworks & Tools
- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/index.html/)
- [Hibernate Envers Documentation](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#envers)
- [Keycloak Documentation](https://www.keycloak.org/documentation)
- [PostgreSQL Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [Flyway Migration Guide](https://flywaydb.org/documentation/)

### Standards & Best Practices
- [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)
- [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
- [REST API Guidelines](https://restfulapi.net/)
- [OWASP Top 10](https://owasp.org/www-project-top-ten/)

---

## Getting Help

### Documentation Issues
If you find issues with documentation:
1. Check if information is outdated
2. Verify examples actually work
3. Create issue with specific problem
4. Suggest improvements

### Missing Documentation
If you need documentation that doesn't exist:
1. Check this index first
2. Review related documents
3. Request new documentation
4. Contribute draft if possible

### Documentation Contributions
1. Follow writing guidelines above
2. Update this index when adding new docs
3. Cross-reference from related documents
4. Update CHANGELOG.md

---

**Documentation Status:** ✅ **COMPLETE & ORGANIZED**
**Last Audit:** 2025-12-28
**Next Review:** 2026-01-28
**Organization:** Subdirectories created for guides, planning, status
