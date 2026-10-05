# 38-05 evidence: what Jackson 3's defaults do to the bytes this API publishes and the inputs it accepts

RESEARCH Open Question 2, answered by measurement on the interim Boot 4.1.1 tree.

| Item | Value |
|------|-------|
| PLAN_BASE | `9bd5ed8aa92f038625a4327eb95b2bc6dce823b7` (HEAD before this plan's first commit) |
| Branch | `phase-37-spring-boot-4-1` |
| JDK | OpenJDK 25.0.4.1 (Ubuntu build; no Temurin on this host, as in the spike) |
| Jackson 3 (Boot's mapper) | `tools.jackson.core:jackson-databind` 3.1.7 (testRuntimeClasspath, dependencyInsight) |
| Jackson 2 (interim bridge, removed by 38-12) | `com.fasterxml.jackson.core:jackson-databind` 2.22.3 |
| Oracle | `core-java/src/test/resources/jackson2-golden/{responses,outbox}`, written on Boot 3.5.16 / databind 2.21.7 at `e12177e1` (38-01). Not modified by this plan. |
| Results | `core-java/build-local/` (never `core-java/build/`) |

## 1. Which mapper writes the REST bodies on this tree

From `Jackson3AcceptanceProbeTest`, `build-local/boot4/jackson-mapper-facts.txt`:

```
probes_differing_bridge_vs_boot=8
probes_differing_bridge_vs_use_jackson2_defaults=0
runner_default_vs_injected_bean_byte_mismatches=[]
use_jackson2_defaults_raw_equal=15/15
use_jackson2_defaults_tree_equal=15/15
use_jackson2_defaults_raw_unequal=[]
jackson2_bridge_raw_equal=15/15
jackson2_bridge_raw_unequal=[]
mvc_jackson_converter_mapper_is_boot_jsonMapper_bean=true
mvc_converters=[org.springframework.data.web.ProjectingJacksonHttpMessageConverter, org.springframework.http.converter.ByteArrayHttpMessageConverter, org.springframework.http.converter.StringHttpMessageConverter, org.springframework.http.converter.ResourceHttpMessageConverter, org.springframework.http.converter.ResourceRegionHttpMessageConverter, org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter, org.springframework.http.converter.json.JacksonJsonHttpMessageConverter, org.springframework.http.converter.yaml.MappingJackson2YamlHttpMessageConverter, org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter]
```

The `JacksonJsonHttpMessageConverter` in the MVC adapter holds the SAME `JsonMapper` instance this
plan measures, so the wire diff below is the diff of every REST response body on this tree. No
Jackson-2 JSON converter is registered. (`ProjectingJacksonHttpMessageConverter` is Spring Data's
read-only converter for `@ProjectedPayload` interfaces; `MappingJackson2YamlHttpMessageConverter`
is YAML only.)

## 2. Serialization: the 15 fixtures, raw and tree

`Jackson3WireContractTest` serialises each `GoldenSamples` instance with Boot's injected
`JsonMapper` and compares it with the Jackson-2 bytes: raw byte equality, and tree equality (both
sides parsed by the same Jackson-3 mapper; objects order-insensitive, numbers by value; each
difference as a JSON pointer with both values). `build-local/boot4/jackson-wire-diff.tsv`:

```
fixture	raw_equal	tree_equal	differing_pointers
outbox/MediaProcessingEvent.json	true	true	-
outbox/OnboardingStateChangeEvent.json	true	true	-
outbox/OrderStateChangeEvent-offset.json	true	true	-
outbox/OrderStateChangeEvent.json	true	true	-
outbox/PaymentEvent.json	true	true	-
outbox/RefundEvent.json	true	true	-
responses/CustomerDto.json	true	true	-
responses/DsarIntakeAck.json	true	true	-
responses/MediaAcceptDto.json	true	true	-
responses/OrderDto.json	false	true	-
responses/ProblemDetail-401.json	false	true	-
responses/ProductDto.json	false	true	-
responses/ShopDto.json	false	true	-
responses/WebhookDeliveryView.json	true	true	-
responses/WebhookEventEnvelope.json	true	true	-
```

**15 rows, 15 tree-equal, 11 raw-equal, 4 raw-unequal. No JSON pointer differs anywhere.**

### 2a. RED: the first version asserted raw equality for every fixture

Command (run start 2026-10-05T01:35:40Z):

```
./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.boot4.Jackson3WireContractTest' --no-daemon
rc=1   (BUILD FAILED; 1 test completed, 1 failed; cleanTest executed)
TEST-uk.jtoye.core.boot4.Jackson3WireContractTest.xml: tests="1" failures="1" errors="0"
```

The failure, verbatim from the JUnit XML (it is the measurement, not a defect):

```
java.lang.AssertionError: [fixtures whose bytes Boot's JsonMapper does not reproduce] 
Expecting empty but was: ["responses/OrderDto.json	false	true	-",
    "responses/ProblemDetail-401.json	false	true	-",
    "responses/ProductDto.json	false	true	-",
    "responses/ShopDto.json	false	true	-"]
```

### 2b. What the 4 raw differences are

Byte length equal in every case, and the parsed trees are equal. The only change is property
ORDER. The top-level key sequence of each file (Jackson 2 fixture, then Boot's Jackson 3 output):

```
responses/OrderDto.json  bytes J2=691 J3=691  J3 order == sorted(J2 keys): True
  J2: id,tenantId,shopId,orderNumber,status,customerName,customerEmail,customerPhone,notes,subtotalPennies,vatRate,vatAmountPennies,totalAmountPennies,fulfilmentType,deliveryFeePennies,itemCount,unitCount,paymentStatus,paymentReference,paymentMethod,createdAt,updatedAt
  J3: createdAt,customerEmail,customerName,customerPhone,deliveryFeePennies,fulfilmentType,id,itemCount,notes,orderNumber,paymentMethod,paymentReference,paymentStatus,shopId,status,subtotalPennies,tenantId,totalAmountPennies,unitCount,updatedAt,vatAmountPennies,vatRate
responses/ProblemDetail-401.json  bytes J2=116 J3=116  J3 order == sorted(J2 keys): True
  J2: type,title,status,detail
  J3: detail,status,title,type
responses/ProductDto.json  bytes J2=1194 J3=1194  J3 order == sorted(J2 keys): True
  J2: id,sku,title,ingredientsText,allergenMask,pricePennies,vatRate,createdAt,description,imageUrl,category,displayOrder,available,featured,preparationTimeMinutes,dietaryTags,shopId,quantityInStock,additionalImageUrls,shelfLifeDays,durabilityType,allergenSpans,media
  J3: additionalImageUrls,allergenMask,allergenSpans,available,category,createdAt,description,dietaryTags,displayOrder,durabilityType,featured,id,imageUrl,ingredientsText,media,preparationTimeMinutes,pricePennies,quantityInStock,shelfLifeDays,shopId,sku,title,vatRate
responses/ShopDto.json  bytes J2=584 J3=584  J3 order == sorted(J2 keys): True
  J2: id,tenantId,name,address,createdAt,slug,description,logoUrl,bannerUrl,phone,email,latitude,longitude,openingHours,deliveryInfo,minimumOrderPennies,published,tags
  J3: address,bannerUrl,createdAt,deliveryInfo,description,email,id,latitude,logoUrl,longitude,minimumOrderPennies,name,openingHours,phone,published,slug,tags,tenantId
```

**Mechanism (measured, not assumed):** the 4 raw-unequal shapes are exactly the four built as
classes with setters/fields (Lombok `OrderDto`, `ProductDto`, `ShopDto`, and Spring's
`ProblemDetail`). Jackson 3's `SORT_PROPERTIES_ALPHABETICALLY` (default true) reorders them. Every
**record** keeps its declaration order (its properties are creator properties, which Jackson 3
still emits first in declaration order), including the records nested inside `ProductDto`
(`allergenSpans[*]`, `media[*]` are still `start,end` and `assetId,status,…`). So:

- all 6 outbox payloads (records) are byte-identical;
- the webhook envelope (record, and its `data` record) is byte-identical, so the bytes vendors
  verify the HMAC over do not change;
- `CustomerDto`, `DsarIntakeAck`, `MediaAcceptDto`, `WebhookDeliveryView` (records) are
  byte-identical;
- `OrderDto`, `ProductDto`, `ShopDto` and the 401 `ProblemDetail` change order only. On the real
  HTTP path a `ProblemDetail` with extension properties emits them after the sorted standard
  members (section 4: `…,"type":…,"errors":{…}`).

Independent byte check outside the test (cmp over the files the test wrote):

```
outbox/MediaProcessingEvent.json	golden==boot_default:True	golden==use_jackson2_defaults:True
outbox/OnboardingStateChangeEvent.json	golden==boot_default:True	golden==use_jackson2_defaults:True
outbox/OrderStateChangeEvent-offset.json	golden==boot_default:True	golden==use_jackson2_defaults:True
outbox/OrderStateChangeEvent.json	golden==boot_default:True	golden==use_jackson2_defaults:True
outbox/PaymentEvent.json	golden==boot_default:True	golden==use_jackson2_defaults:True
outbox/RefundEvent.json	golden==boot_default:True	golden==use_jackson2_defaults:True
responses/CustomerDto.json	golden==boot_default:True	golden==use_jackson2_defaults:True
responses/DsarIntakeAck.json	golden==boot_default:True	golden==use_jackson2_defaults:True
responses/MediaAcceptDto.json	golden==boot_default:True	golden==use_jackson2_defaults:True
responses/OrderDto.json	golden==boot_default:False	golden==use_jackson2_defaults:True
responses/ProblemDetail-401.json	golden==boot_default:False	golden==use_jackson2_defaults:True
responses/ProductDto.json	golden==boot_default:False	golden==use_jackson2_defaults:True
responses/ShopDto.json	golden==boot_default:False	golden==use_jackson2_defaults:True
responses/WebhookDeliveryView.json	golden==boot_default:True	golden==use_jackson2_defaults:True
responses/WebhookEventEnvelope.json	golden==boot_default:True	golden==use_jackson2_defaults:True
golden vs boot default: 4 byte-differing; golden vs use-jackson2-defaults: 0 byte-differing
```

## 3. Deserialization: the acceptance differential

`Jackson3AcceptanceProbeTest` (temporary) reads the same input with the interim bridge's Jackson-2
`ObjectMapper` and with Boot's Jackson-3 `JsonMapper`. A third column is the counterfactual for
option B: a Boot `JsonMapper` built by Boot's own `JacksonAutoConfiguration` (an
`ApplicationContextRunner`) with `spring.jackson.use-jackson2-defaults=true`. An outcome is
`ACCEPT` plus the resulting value, or `REJECT`; exception text is shown but not compared.
`build-local/boot4/jackson-acceptance-probe.tsv`:

```
probe	input	jackson2_bridge	jackson3_boot	jackson3_boot_use_jackson2_defaults	differs_bridge_vs_boot	differs_bridge_vs_use_jackson2_defaults
trailing-object	{"name":"a"} {"name":"b"}	ACCEPT Named[name=a]	REJECT MismatchedInputException: Trailing token (`JsonToken.START_OBJECT`) found after value (bound as `uk.jtoye.core.boot4.Jackson3AcceptanceProbeTest$Named`): not allowed as per `Deserializat	ACCEPT Named[name=a]	true	false
trailing-brace	{"name":"a"}}	ACCEPT Named[name=a]	REJECT StreamReadException: Unexpected close marker '}': no open Object to close	ACCEPT Named[name=a]	true	false
null-into-record-int-long	{"i":null,"l":null}	ACCEPT Prims[i=0, l=0]	REJECT MismatchedInputException: Cannot map `null` into type `int` (set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false' to allow)	ACCEPT Prims[i=0, l=0]	true	false
absent-record-int-long	{}	ACCEPT Prims[i=0, l=0]	REJECT MismatchedInputException: Cannot map `null` into type `int` (set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false' to allow)	ACCEPT Prims[i=0, l=0]	true	false
null-into-pojo-int-setter	{"quantity":null}	ACCEPT PojoPrim[quantity=0]	REJECT MismatchedInputException: Cannot map `null` into type `int` (set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false' to allow)	ACCEPT PojoPrim[quantity=0]	true	false
unknown-property	{"name":"a","bogus":1}	ACCEPT Named[name=a]	ACCEPT Named[name=a]	ACCEPT Named[name=a]	false	false
enum-by-name	{"tag":"ALPHA"}	ACCEPT Tagged[tag=alpha-label]	REJECT InvalidFormatException: Cannot deserialize value of type `uk.jtoye.core.boot4.Jackson3AcceptanceProbeTest$Labelled` from String "ALPHA": not one of the values accepted for Enum class: 	ACCEPT Tagged[tag=alpha-label]	true	false
enum-by-toString	{"tag":"alpha-label"}	REJECT InvalidFormatException: Cannot deserialize value of type `uk.jtoye.core.boot4.Jackson3AcceptanceProbeTest$Labelled` from String "alpha-label": not one of the values accepted for Enum c	ACCEPT Tagged[tag=alpha-label]	REJECT InvalidFormatException: Cannot deserialize value of type `uk.jtoye.core.boot4.Jackson3AcceptanceProbeTest$Labelled` from String "alpha-label": not one of the values accepted for Enum c	true	false
datetime-epoch-decimal	{"at":1791117296.123456789}	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	false	false
datetime-iso-plus-0100	{"at":"2026-10-04T13:34:56.123456789+01:00"}	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	false	false
single-value-for-list	{"items":"a"}	REJECT MismatchedInputException: Cannot construct instance of `java.util.ArrayList` (although at least one Creator exists): no String-argument constructor/factory method to deserialize from Str	REJECT MismatchedInputException: Cannot deserialize value of type `java.util.ArrayList<java.lang.String>` from String value (token `JsonToken.VALUE_STRING`)	REJECT MismatchedInputException: Cannot deserialize value of type `java.util.ArrayList<java.lang.String>` from String value (token `JsonToken.VALUE_STRING`)	false	false
float-into-int	{"count":1.5}	ACCEPT Count[count=1]	ACCEPT Count[count=1]	ACCEPT Count[count=1]	false	false
write-enum-with-toString	new Tagged(ALPHA)	ACCEPT {"tag":"ALPHA"}	ACCEPT {"tag":"alpha-label"}	ACCEPT {"tag":"ALPHA"}	true	false
# probes_differing_bridge_vs_boot=8 probes_differing_bridge_vs_use_jackson2_defaults=0 (an outcome is ACCEPT plus its value, or REJECT; the exception text is not compared)
```

Readings:

- **Trailing tokens** (`FAIL_ON_TRAILING_TOKENS` false -> true): Jackson 2 read the first value and
  ignored the rest; Jackson 3 rejects. Reaches every request body (section 4).
- **null / absent into a primitive** (`FAIL_ON_NULL_FOR_PRIMITIVES` false -> true): Jackson 3
  rejects a JSON `null`, and also an ABSENT record component, for `int`/`long`, on records and on
  setter POJOs alike. Exposure in this API: **0** (section 5).
- **Enum toString** (`READ_/WRITE_ENUMS_USING_TO_STRING` false -> true): for an enum whose
  `toString()` differs from `name()` the written value and the accepted spelling both flip.
  Exposure in this API: **0** of 40 main enums (section 5).
- **Unknown property**: accepted by all three (Boot 3 already disabled `FAIL_ON_UNKNOWN_PROPERTIES`;
  Jackson 3's default is false). The spring-boot#49951 side effect RESEARCH cites was NOT observed
  for this probe under `use-jackson2-defaults` on 4.1.1.
- **Dates**: an epoch decimal and an ISO `+01:00` string both read to the same instant, and BOTH
  mappers normalise the offset to `Z` (`ADJUST_DATES_TO_CONTEXT_TIME_ZONE` is on in both). No
  change.
- **Single value for a List**: rejected by both (`ACCEPT_SINGLE_VALUE_AS_ARRAY` off in both). No
  change. **Float into int**: both truncate `1.5` to `1`. No change.

## 4. The trailing-token change on the real HTTP path

`Jackson3AcceptanceProbeTest#trailingTokenOnTheRealHttpPath`, MockMvc against
`POST /public/shops/any-slug/orders` with 38-02's body (valid except `customerEmail` absent, so a
body that is READ is answered by validation). `build-local/boot4/jackson-trailing-token-http.txt`:

```
control-no-trailing-token	status=400	body={"detail":"Validation failed","instance":"/public/shops/any-slug/orders","status":400,"title":"Validation Error","type":"https://jtoye.uk/errors/validation","errors":{"customerEmail":"Email is required"}}
trailing-object	status=400	body={"detail":"Malformed or unreadable request body","instance":"/public/shops/any-slug/orders","status":400,"title":"Bad Request","type":"https://jtoye.uk/errors/unreadable-request"}
```

Same status (400), different problem: with a trailing value the body is no longer read at all and
the client gets `errors/unreadable-request` instead of the field-level `errors/validation`. On
Boot 3.5 (Jackson 2) the trailing value was silently ignored and the first object processed.

## 5. Request DTOs exposed to the flags that differ

Walked structurally, as `RequestBodyConstraintEnforcementTest` does: every `@RequestBody` of every
handler the live `RequestMappingHandlerMapping` serves under `uk.jtoye.core`, recursing into every
`uk.jtoye` property type (collection elements included). Every one of the 20 DTO names in
`38-02-request-body-constraints-boot35.tsv` is reached; the walk finds 26 types in total.
`build-local/boot4/request-dto-exposure.tsv`:

```
# request_body_types=26 in_38_02_inventory=20 (of 20 names) primitive_exposed=0 enum_exposed=11 enum_constants_with_toString_differing_types=0
dto	in_38_02_inventory	primitive_properties	enum_properties	temporal_properties	handlers
CreateCustomerRequest	true	-	-	-	CustomerController#create
UpdateCustomerRequest	true	-	-	-	CustomerController#update
CreateTransactionRequest	true	-	vatRate:VatRate(toString==name)	-	FinancialTransactionController#createTransaction
VerificationRequest	false	-	-	-	DsarVerificationController#verify
DsarIntakeRequest	true	-	requestType:RequestType(toString==name)	-	DsarIntakeController#lodge
UnsubscribeRequest	false	-	category:NotificationCategory(toString==name)	-	PublicUnsubscribeController#unsubscribe
CreateOnboardingRequest	true	-	model:OnboardingModel(toString==name)	-	OnboardingController#create
RejectOnboardingRequest	true	-	-	-	OnboardingAdminController#reject
ResolveGateRequest	true	-	decision:GateDecision(toString==name)	-	OnboardingAdminController#resolveGate
UpdateOnboardingRequest	false	-	-	-	OnboardingController#updateCompanyNumber
CreateOrderRequest	true	-	-	-	OrderController#createOrder
OrderItemRequest	true	-	-	-	OrderController#createOrder
UpdateOrderRequest	false	-	-	-	OrderController#updateOrder
CreateRefundRequest	true	-	reason:RefundReason(toString==name)	-	RefundController#createRefund
CreateProductRequest	true	-	vatRate:VatRate(toString==name)	-	ProductController#create,ProductController#update
CreateReviewRequest	true	-	-	-	PublicStorefrontController#createReview
GrantStaffRequest	true	-	role:ShopRole(toString==name)	-	StaffController#grant
CreateAnnouncementRequest	true	-	-	validFrom:OffsetDateTime,validUntil:OffsetDateTime	AnnouncementController#create,AnnouncementController#update
CreatePromotionRequest	true	-	discountType:DiscountType(toString==name)	validFrom:OffsetDateTime,validUntil:OffsetDateTime	PromotionController#create,PromotionController#update
CreateShopRequest	true	-	-	-	ShopController#create,ShopController#update
GuestOrderItemRequest	true	-	-	-	PublicStorefrontController#createGuestOrder
GuestOrderRequest	true	-	-	-	PublicStorefrontController#createGuestOrder
BatchSyncRequest	false	-	-	-	SyncController#batchSync
SyncItem	false	-	-	-	SyncController#batchSync
CreateTenantRequest	true	-	plan:TenantPlan(toString==name)	-	TenantAdminController#create
CreateWebhookSubscriptionRequest	true	-	eventTypes:WebhookEventType(toString==name)	-	WebhookSubscriptionController#create
# main_enums_scanned=40 main_enums_with_toString_differing_from_name=[] control_test_enums_with_toString_differing=[uk.jtoye.core.boot4.Jackson3AcceptanceProbeTest$Labelled]
```

- **Primitive properties: none.** 0 of 26 request-body types has a primitive property, so
  `FAIL_ON_NULL_FOR_PRIMITIVES` changes no accepted request on this API. (Corroborated by text:
  the only primitive in a `*Request` type under `src/main` is the private fingerprint record
  `MediaUploadController.MediaUploadRequest`, which is never a `@RequestBody`.)
- **Enum properties: 11 types, every enum `toString()==name()`.** The classpath scan over all 40
  main enums finds **none** whose `toString()` differs from `name()`, so neither enum flag changes
  any byte written or any spelling accepted, request side or response side.
- **Positive controls** (asserted in the test, so the "none" is not a blind walk): the property
  walk reports `int`/`long` on the probe records and `int` on the setter POJO; the enum scan, run
  over the test output too, reports this test's own `Labelled` enum.

## 6. Counterfactuals the owner can weigh (measured, nothing configured)

Fidelity control: an `ApplicationContextRunner` with only `JacksonAutoConfiguration` and NO
property writes all 15 fixtures byte-for-byte as the injected bean does
(`runner_default_vs_injected_bean_byte_mismatches=[]`), so a runner-built mapper stands for Boot's
real one. Fail direction of that byte check: the same comparison between the default bean and the
`use-jackson2-defaults` mapper differs on 4 files (section 2b cmp).

**Option B (`spring.jackson.use-jackson2-defaults: true`)**, `build-local/boot4/jackson2-defaults-wire-diff.tsv`:

```
fixture	raw_equal	tree_equal	differing_pointers
outbox/MediaProcessingEvent.json	true	true	-
outbox/OnboardingStateChangeEvent.json	true	true	-
outbox/OrderStateChangeEvent-offset.json	true	true	-
outbox/OrderStateChangeEvent.json	true	true	-
outbox/PaymentEvent.json	true	true	-
outbox/RefundEvent.json	true	true	-
responses/CustomerDto.json	true	true	-
responses/DsarIntakeAck.json	true	true	-
responses/MediaAcceptDto.json	true	true	-
responses/OrderDto.json	true	true	-
responses/ProblemDetail-401.json	true	true	-
responses/ProductDto.json	true	true	-
responses/ShopDto.json	true	true	-
responses/WebhookDeliveryView.json	true	true	-
responses/WebhookEventEnvelope.json	true	true	-
```

15/15 raw-equal, and 0 acceptance probes differ from the Jackson-2 bridge (section 3, last column).

**Option C candidate (only the two flags that reach this API)**:
`spring.jackson.mapper.sort-properties-alphabetically: false` and
`spring.jackson.deserialization.fail-on-trailing-tokens: false`.
`build-local/boot4/jackson-targeted-keys.tsv`:

```
# keys=[spring.jackson.mapper.sort-properties-alphabetically=false, spring.jackson.deserialization.fail-on-trailing-tokens=false]
# raw_equal=15/15 tree_equal=15/15
outbox/MediaProcessingEvent.json	true	true	-
outbox/OnboardingStateChangeEvent.json	true	true	-
outbox/OrderStateChangeEvent-offset.json	true	true	-
outbox/OrderStateChangeEvent.json	true	true	-
outbox/PaymentEvent.json	true	true	-
outbox/RefundEvent.json	true	true	-
responses/CustomerDto.json	true	true	-
responses/DsarIntakeAck.json	true	true	-
responses/MediaAcceptDto.json	true	true	-
responses/OrderDto.json	true	true	-
responses/ProblemDetail-401.json	true	true	-
responses/ProductDto.json	true	true	-
responses/ShopDto.json	true	true	-
responses/WebhookDeliveryView.json	true	true	-
responses/WebhookEventEnvelope.json	true	true	-
probe	jackson2_bridge	jackson3_targeted	differs
trailing-object	ACCEPT Named[name=a]	ACCEPT Named[name=a]	false
trailing-brace	ACCEPT Named[name=a]	ACCEPT Named[name=a]	false
null-into-record-int-long	ACCEPT Prims[i=0, l=0]	REJECT MismatchedInputException: Cannot map `null` into type `int` (set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false' to allow)	true
absent-record-int-long	ACCEPT Prims[i=0, l=0]	REJECT MismatchedInputException: Cannot map `null` into type `int` (set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false' to allow)	true
null-into-pojo-int-setter	ACCEPT PojoPrim[quantity=0]	REJECT MismatchedInputException: Cannot map `null` into type `int` (set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false' to allow)	true
unknown-property	ACCEPT Named[name=a]	ACCEPT Named[name=a]	false
enum-by-name	ACCEPT Tagged[tag=alpha-label]	REJECT InvalidFormatException: Cannot deserialize value of type `uk.jtoye.core.boot4.Jackson3AcceptanceProbeTest$Labelled` from String "ALPHA": not one of the values accepted for Enum class: 	true
enum-by-toString	REJECT InvalidFormatException: Cannot deserialize value of type `uk.jtoye.core.boot4.Jackson3AcceptanceProbeTest$Labelled` from String "alpha-label": not one of the values accepted for Enum c	ACCEPT Tagged[tag=alpha-label]	true
datetime-epoch-decimal	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	false
datetime-iso-plus-0100	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	ACCEPT When[at=2026-10-04T12:34:56.123456789Z] instant=2026-10-04T12:34:56.123456789Z offset=Z	false
single-value-for-list	REJECT MismatchedInputException: Cannot construct instance of `java.util.ArrayList` (although at least one Creator exists): no String-argument constructor/factory method to deserialize from Str	REJECT MismatchedInputException: Cannot deserialize value of type `java.util.ArrayList<java.lang.String>` from String value (token `JsonToken.VALUE_STRING`)	false
float-into-int	ACCEPT Count[count=1]	ACCEPT Count[count=1]	false
# probes_differing_bridge_vs_targeted=5
```

15/15 raw-equal and trailing tokens accepted again. The 5 probes that still differ are the
primitive and enum flags, which section 5 shows reach no DTO or enum of this API.

**Control: the Jackson-2 bridge** reproduces all 15 fixtures byte-for-byte
(`jackson2_bridge_raw_equal=15/15`), so the harness can report equality and the bridge is a
faithful stand-in for Boot 3.5's acceptance behaviour.

## 7. Verdict

The plan's definition: ORDER-ONLY when every `tree_equal` is true AND no acceptance probe differs;
VALUE-DIFF otherwise. Every `tree_equal` is true (section 2), but 8 acceptance probes differ
(section 3), so the verdict is:

VERDICT: VALUE-DIFF

Pointer-level differences behind it: **none on the wire** (15/15 tree-equal; 4 fixtures differ in
property order only, section 2b). The value difference is entirely on the input side, and of the
8 differing probes only ONE reaches this API: trailing content after a request body is now a 400
`errors/unreadable-request` (section 4). The other 7 (null/absent primitives, enum toString read
and write) have zero exposure (section 5).

## 8. Fail-direction record for this task's checks

| Check | Real tree | Fail direction |
|---|---|---|
| raw equality per fixture | 11/15 true | the RED run: failed naming the 4 (section 2a) |
| tree diff can report a difference | `treeDiffCanFail`: a changed value is reported as `/a/b/1 expected="x" actual="y"`, a missing key as `/b~1c expected=2 actual=<absent>` | the same method shows `1.50` vs `1.5` and reordered keys report nothing |
| fixture/factory pairing | 15 files = 15 factories | the assertion is `containsExactlyInAnyOrderElementsOf(files on disk)`; an extra or missing file fails it |
| runner fidelity (bytes) | 0 mismatches vs the injected bean | 4 mismatches between the default bean and the use-jackson2-defaults mapper (cmp, section 2b) |
| probe comparator | 8 `true` in the Boot column | 0 `true` in the use-jackson2-defaults column, same comparator |
| DTO walk sees primitives / enums | positive controls asserted (section 5) | n/a (controls are the fail direction of "none") |
| VERDICT line count | see section 9 | see section 9 |

## 9. Task 1 verify, as the plan states it

First verify (run start 2026-10-05T01:44:18Z, on the final Task 1 sources):

```
./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.boot4.Jackson3WireContractTest' \
          --tests 'uk.jtoye.core.boot4.Jackson3AcceptanceProbeTest' --no-daemon
rc=0   BUILD SUCCESSFUL; ':core-java:cleanTest' and ':core-java:test' both executed (0 UP-TO-DATE lines for them)
TEST-uk.jtoye.core.boot4.Jackson3AcceptanceProbeTest.xml  tests="4" failures="0" errors="0"  newer than run start: 1
TEST-uk.jtoye.core.boot4.Jackson3WireContractTest.xml     tests="2" failures="0" errors="0"  newer than run start: 1
core-java/build-local/boot4/jackson-wire-diff.tsv         691 bytes, 16 lines             newer than run start: 1
fail direction of the freshness reading: against a marker touched AFTER the run -> tsv 0, xml 0
```

Second verify:

```
awk '/^VERDICT: (ORDER-ONLY|VALUE-DIFF)$/{n++} END{print "verdicts="n+0}' <this file>   -> verdicts=1
fail direction: a copy with a second verdict line appended                     -> verdicts=2
                /dev/null                                                       -> verdicts=0
                a copy whose line reads "VERDICT: VALUE-DIFF." (malformed)      -> verdicts=0
```

## 10. Owner decision (Task 2)

Presented on 2026-10-05 from sections 2-7 (verdict VALUE-DIFF), with four options: jackson3-defaults
(recommended), jackson2-defaults, targeted `sort-properties-alphabetically=false`, and targeted
`sort-properties-alphabetically=false` plus `fail-on-trailing-tokens=false`. The deprecated
Jackson-2 converter switch was not offered (D-01).

The owner's answer, exact words, selected via AskUserQuestion on 2026-10-05:

> jackson3-defaults (Recommended)

**Decision: jackson3-defaults.** Boot's `JsonMapper` keeps Jackson 3's defaults.

**Applied keys: none.** No `spring.jackson.*` key is added to any `application*.yml`, and
`spring.http.converters.preferred-json-mapper` is not set anywhere (D-01).

Rationale, from the measurement: no published VALUE changes (15/15 tree-equal); the outbox payloads,
the webhook envelope (the HMAC input) and every record response keep their exact bytes; the 7
input-side differences other than trailing tokens reach no request DTO and no enum of this API
(section 5); and Boot 4's intended state carries nothing to remove later (a Jackson-2-defaults
switch or a targeted key would be a standing exception and a second contract change on removal).
The persisted-hash paths do not depend on this choice: idempotency (38-10) uses its own frozen
mapper, AMQP (38-08) and Redis (38-09) build their own.

### Contract changes this decision accepts (for ADR-0006 and the PR description)

1. **Alphabetical key order on class-based responses.** `OrderDto`, `ProductDto`, `ShopDto` and
   every Spring `ProblemDetail` body this mapper writes (measured: the 401 fixture, and a
   `GlobalExceptionHandler` problem on the real HTTP path in section 4) are written with their
   properties in alphabetical order instead of declaration order.
   Values, key sets and byte lengths are unchanged (section 2b); a `ProblemDetail`'s extension
   properties still follow its sorted standard members (section 4). Records (all 6 outbox payloads,
   the webhook envelope and `data`, `CustomerDto`, `DsarIntakeAck`, `MediaAcceptDto`,
   `WebhookDeliveryView`, and records nested in a class such as `ProductDto.allergenSpans[*]` and
   `media[*]`) keep declaration order and their exact bytes. A JSON consumer that relies on key
   order is the only one affected.
2. **Trailing content after a request body is rejected.** A body followed by any further token
   (`{...} {...}`, `{...}}`) is now a 400 `https://jtoye.uk/errors/unreadable-request`
   ("Malformed or unreadable request body") without the body being read; on Boot 3.5 the trailing
   content was ignored and the first value processed (section 4). Same status code, different
   problem type, and no field-level `errors` map.

Behaviour that changed in the mapper but reaches nothing on this API today (asserted on Boot's
mapper so a future DTO meets it knowingly): JSON `null` or an absent record component into an
`int`/`long` is rejected; an enum is read and written by `toString()` (all 40 enums here have
`toString()==name()`, so no byte or accepted spelling changes).

### Where the documentation obligation lands

- **ADR-0006** is written by plan **38-16** Task 2 (`docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md`;
  its must-have: "ADR-0006 records ... the 38-05 Jackson-defaults decision"). It must name the two
  contract changes above with this file as the evidence reference.
- **The PR description** is composed at ship time (38-18's ship checklist, then `/gsd-ship`). It
  must name the same two contract changes.

Both are carried obligations of 38-05, recorded in `38-05-SUMMARY.md`; neither is written here.

### What the permanent `Jackson3WireContractTest` now asserts (12 tests)

| Test | Decided relationship |
|---|---|
| `everyFixtureIsTreeEqualAndOnlyTheAcceptedOrderingDifferencesAreRawUnequal` | all 15 fixtures tree-equal; the raw-unequal set is EXACTLY `ACCEPTED_ORDERING_DIFFERENCES` (OrderDto, ProblemDetail-401, ProductDto, ShopDto); writes `build-local/boot4/jackson-wire-diff.tsv` |
| `recordsKeepTheirBytesAndClassBasedTypesAreAlphabetical` | every record sample byte-equal; every class-based sample's top-level keys == the fixture's keys sorted; the class-based set == the accepted set |
| `restBodiesUseThisMapper` | the MVC `JacksonJsonHttpMessageConverter` holds this `JsonMapper` bean |
| `trailingTokensAreRejected` | `{..} {..}` and `{..}}` rejected; control accepted |
| `trailingTokenAfterARequestBodyIsUnreadableRequest` | real HTTP path: control 400 `errors/validation`, trailing token 400 `errors/unreadable-request` |
| `nullOrAbsentIntoAPrimitiveIsRejected` | null/absent into record `int`/`long` and setter `int` rejected; control accepted |
| `unknownPropertiesAreIgnored` | unknown property accepted |
| `enumsUseToString` | read by toString accepted, by name rejected, written as toString |
| `datesReadToTheSameInstantInUtc` | epoch decimal and ISO `+01:00` read to the same instant, offset `Z` |
| `aSingleValueIsNotAList` | single value for a List rejected; control accepted |
| `aFloatIntoAnIntIsTruncated` | `1.5` into `int` reads as `1` |
| `treeDiffCanFail` | the diff reports a changed value and a missing key by pointer, and ignores order/number spelling |

`Jackson3AcceptanceProbeTest` is deleted (it needs the interim Jackson-2 bean that 38-12 removes).
The web environment moved from NONE to the default MOCK with `@AutoConfigureMockMvc`, because the
decided trailing-token outcome is asserted on the real HTTP path.

First green run on the decided sources (run start 2026-10-05T08:25Z):

```
./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.boot4.Jackson3WireContractTest' --no-daemon
rc=0   BUILD SUCCESSFUL; ':core-java:cleanTest' and ':core-java:test' both executed
TEST-uk.jtoye.core.boot4.Jackson3WireContractTest.xml  tests="12" skipped="0" failures="0" errors="0"  newer than run start: 1
```
