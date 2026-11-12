# SDK integration guide (Java)

The `io.hlthstack` library is a client for the [hlthstack](https://www.hlthstack.io/) partner
REST API: health checkups and lab tests, in-clinic and online consultations, ePharmacy orders,
benefit wallets and member pre-registration. To call the API over plain HTTP without it, see
the [plain REST API guide](api-integration.md).

- Java 11+. One dependency: [Jackson databind](https://github.com/FasterXML/jackson-databind).
  Requests go through the JDK's `java.net.http.HttpClient`.
- Access tokens are fetched, cached and refreshed for you, including the separate
  pre-registration token. The client is thread-safe.
- One method per API operation. Query parameters and JSON bodies are `Map`s with the API's own
  field names; responses are Jackson `JsonNode` trees.
- Every request is checked against the API's input rules before it is sent, with all problems
  reported at once.
- One exception class per HTTP status, with the messages pulled out of every error shape.
- Webhook signature verification.

> **Server-side only.** Your API credentials, access tokens, entity secret key and webhook
> secret must never be shipped to a browser or mobile app. Call hlthstack from your backend.

REST API reference and guides: https://github.com/hlthstack/integration-guide

## Install

Install straight from this GitHub repository through [JitPack](https://jitpack.io), which
builds the library from a tag or commit the first time someone asks for it. Pin a release tag
or a commit hash so your builds are reproducible.

**Maven** (`pom.xml`):

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.github.hlthstack</groupId>
    <artifactId>java</artifactId>
    <version>TAG-OR-COMMIT</version>
  </dependency>
</dependencies>
```

**Gradle** (`settings.gradle` or `build.gradle`):

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.hlthstack:java:TAG-OR-COMMIT'
}
```

Gradle Kotlin DSL: `maven("https://jitpack.io")` and
`implementation("com.github.hlthstack:java:TAG-OR-COMMIT")`.

**From source**, into your local Maven repository (needs only a JDK 11+; the Maven Wrapper
downloads Maven):

```bash
git clone https://github.com/hlthstack/java.git hlthstack-java
cd hlthstack-java
./mvnw install          # add -DskipTests to skip the tests
```

then depend on `io.hlthstack:hlthstack:0.1.0` (in Gradle, add `mavenLocal()` to the
repositories).

The package is `io.hlthstack` (automatic module name `io.hlthstack`). Publishing to Maven
Central is planned; the GitHub install keeps working when it lands.

## Quickstart

```java
import com.fasterxml.jackson.databind.JsonNode;
import io.hlthstack.Environment;
import io.hlthstack.Hlthstack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

Hlthstack client = Hlthstack.builder()
    .username(System.getenv("HLTHSTACK_USERNAME"))
    .password(System.getenv("HLTHSTACK_PASSWORD"))
    .environment(Environment.STAGING)
    .build();

// 1. Find providers. The access token is requested on the first call and reused.
JsonNode providers = client.appointments().listProviders(
    Map.of("service_name", "ahc", "package_ids", "1001", "pincode", "500081"));
long providerId = providers.at("/providers/0/provider_id").asLong();

// 2. Pick a slot.
JsonNode slots = client.appointments().listSlots(providerId, "ahc", Map.of("package_ids", "1001", "pincode", "500081"));
String slotTime = null;
for (JsonNode day : slots.path("booking_slots")) {
  for (JsonNode slot : day.path("slots")) {
    if (slotTime == null && slot.path("available").asBoolean()) slotTime = slot.get("utc_slot_time").asText();
  }
}

// 3. Book.
Map<String, Object> customer = new LinkedHashMap<>();
customer.put("name", "Jane Doe");
customer.put("dob", "1990-08-24");
customer.put("mobile_number", "9999999999");
customer.put("api_customer_id", "CUST1001");
customer.put("gender", "female");
customer.put("package_ids", List.of(1001));
customer.put("booking_customer", true);
Map<String, Object> address = new LinkedHashMap<>();
address.put("address_line_one", "1 Example Road");
address.put("address_line_two", "Madhapur");
address.put("city", "Hyderabad");
address.put("state", "Telangana");
address.put("country", "India");
address.put("pincode", "500081");
address.put("latitude", 17.4437);
address.put("longitude", 78.386);
Map<String, Object> booking = new LinkedHashMap<>();
booking.put("provider_id", providerId);
booking.put("appointment_date_time", slotTime);
booking.put("appointment_type", "home_collection");
booking.put("service_name", "ahc");
booking.put("customers", List.of(customer));
booking.put("address", address);
JsonNode created = client.appointments().create(booking);

if (created.path("status").asText().equals("payment_pending")) {
  // send created.path("payment_link").asText() to the member
}

// 4. Read it back.
JsonNode details = client.appointments().get(Map.of("booking_id", created.get("booking_id").asText()));
for (JsonNode appointment : details.path("appointments")) {
  System.out.println(appointment.path("appointment_id").asText() + " " + appointment.path("status").asText());
}
```

The same code is in [`examples/sdk/Quickstart.java`](../examples/sdk/Quickstart.java).

## Configuration

```java
Hlthstack client = Hlthstack.builder()
    .username("example-user")             // issued by hlthstack; not needed for pre-registration only
    .password("example-password")
    .preRegistrationUsername(null)        // pre-registration login (an email), for client.preRegistration()
    .preRegistrationPassword(null)
    .environment(Environment.PRODUCTION)  // the default; or Environment.STAGING
    .baseUrl(null)                        // optional override, e.g. a proxy
    .entityId(null)                       // multi-entity partners: entity to act for
    .secretKey(null)                      // required with entityId
    .timeout(Duration.ofSeconds(60))      // per request (the default)
    .validate(true)                       // check inputs before sending (see Validation)
    .httpClient(null)                     // your own java.net.http.HttpClient (proxy, SSL, executor)
    .clock(null)                          // token expiry and date rules; for tests
    .build();
```

`build()` throws `IllegalArgumentException` when neither the API credentials nor the
pre-registration credentials are complete, or when `entityId` is set without `secretKey`.

Create one client per set of credentials and share it: it is thread-safe and holds the access
tokens. Credentials, the entity secret key and webhook secrets are server-side secrets.

### Environments

| `Environment` | Base URL |
|---|---|
| `PRODUCTION` (default) | `https://api.hlthstack.io` |
| `STAGING` | `https://staging-api.hlthstack.io` |

Credentials, package ids, plan ids, entity ids and webhook secrets are different per
environment.

### Authentication

You never call the token endpoint yourself. The client:

- requests a token (`GET /api/get-access-token`, HTTP Basic) on the first API call;
- caches it and requests a new one 5 minutes before `expires_at` (tokens last 1 hour);
- shares one token request between concurrent threads;
- on a `401`, fetches a new token and retries the call once (uploads are re-sent).

`client.auth().getAccessToken()` returns the cached token if you need it, and
`getAccessToken(true)` forces a new one.

`client.preRegistration()` uses separate credentials (`preRegistrationUsername` /
`preRegistrationPassword`) and its own token: the client requests it from
`GET /api/pre_registration/access-token` (its `expires_at` looks like
`2026-01-15 07:16:21 UTC`), sends it raw in `X-EKINCARE-KEY`, and caches, shares and refreshes
it the same way. `client.preRegistration().getAccessToken()` returns it.

### Multi-entity partners

Set `entityId` and `secretKey` to send `API-ENTITY-ID` and `API-SECRET-KEY` on every call
(never on the token requests). Without them, calls act for the entity tied to your
credentials. Override per call:

```java
client.appointments().create(params, RequestOptions.builder().entityId("1002").build());     // uses the client's secretKey
client.appointments().create(params, RequestOptions.builder().entityId("1003").secretKey("...").build());
client.appointments().create(params, RequestOptions.builder().withoutEntity().build());      // the credentials' own entity
```

An entity id without a secret key (on the client or the call) throws
`IllegalArgumentException` before anything is sent. Pre-registration calls never send entity
headers: each row names its `company_id`.

### Per-request options

Every method has an overload that takes a `RequestOptions` as its last argument:

```java
RequestOptions options = RequestOptions.builder()
    .entityId("1002")                 // or .withoutEntity()
    .secretKey("...")
    .timeout(Duration.ofSeconds(90))  // this call's HTTP timeout
    .validate(false)                  // override the client's validate setting
    .header("X-Trace-Id", "trace-1")  // extra headers
    .idempotencyKey("rx-upload-0001") // Idempotency-Key (prescription digitization)
    .build();
```

## API

Method names follow the spec's `operationId`s, grouped by area.

| Area | Method | operationId | Endpoint |
|---|---|---|---|
| `auth()` | `getAccessToken()` | getAccessToken | `GET /api/get-access-token` |
| `appointments()` | `listProviders(query)` | listProviders | `GET /api/providers` |
| | `listSlots(providerId, benefitType, query)` | listSlots | `GET /api/providers/{provider_id}/{benefit_type}/slots` |
| | `create(body)` | createAppointment | `POST /api/appointments` |
| | `get(query)` | getAppointment | `GET /api/appointments` |
| | `rescheduleBooking(bookingId, body)` | rescheduleBooking | `PATCH /api/bookings/{booking_id}/reschedule` |
| | `cancelBooking(bookingId)` | cancelBooking | `PATCH /api/bookings/{booking_id}/cancel` |
| | `reschedule(appointmentId, body)` | rescheduleAppointment | `PATCH /api/appointments/{appointment_id}/reschedule` |
| | `cancel(appointmentId)` | cancelAppointment | `PATCH /api/appointments/{appointment_id}/cancel` |
| | `getVoucher(appointmentId)` → `byte[]` | getAppointmentVoucher | `GET /api/appointments/{appointment_id}/voucher` |
| | `getReports(appointmentId)` | getAppointmentReports | `GET /api/appointments/{appointment_id}/reports` |
| | `getLabResults(appointmentId)` | getAppointmentLabResults | `GET /api/appointments/{appointment_id}/lab_results` |
| `consultations()` | `listInclinicProviders(query)` | listInclinicConsultationProviders | `GET /api/consultations/inclinic_consultation/providers` |
| | `listSpecialistProviders(query)` | listSpecialistConsultationProviders | `GET /api/consultations/specialist_consultation/providers` |
| | `listSlots(providerId, benefitType, query)` | listSlots | same as `appointments().listSlots` |
| | `createInclinic(body)` | createInclinicConsultation | `POST /api/consultations/inclinic_consultation` |
| | `createSpecialist(body)` | createSpecialistConsultation | `POST /api/consultations/specialist_consultation` |
| | `getInclinic(appointmentId)` | getInclinicConsultation | `GET /api/consultations/inclinic_consultation/{appointment_id}` |
| | `getSpecialist(appointmentId)` | getSpecialistConsultation | `GET /api/consultations/specialist_consultation/{appointment_id}` |
| | `reschedule(appointmentId, body)` | rescheduleConsultation | `PATCH /api/consultations/{appointment_id}/reschedule` |
| | `cancel(appointmentId)` | cancelConsultation | `PATCH /api/consultations/{appointment_id}/cancel` |
| | `getSummaryAndPrescription(appointmentId)` | getConsultationSummaryAndPrescription | `GET /api/consultations/{appointment_id}/summary_and_prescription` |
| | `getVoucher(appointmentId)` → `byte[]` | getConsultationVoucher | `GET /api/consultations/{appointment_id}/voucher` |
| | `uploadDocuments(appointmentId, documents)` | uploadConsultationDocuments | `POST /api/consultations/{appointment_id}/upload_documents` (multipart) |
| `pharmacy()` | `searchMedicines(query)` | searchMedicines | `GET /api/epharmacy/medicines/search` |
| | `digitizePrescription(form)` | digitizePrescription | `POST /api/prescriptions/digitize` (multipart) |
| | `getPrescriptionDigitization(requestId)` | getPrescriptionDigitization | `GET /api/prescriptions/digitize/{request_id}` |
| | `getCart(query)` | getCart | `GET /api/epharmacy/cart` |
| | `updateCart(params)` | updateCart | `PATCH /api/epharmacy/cart` (JSON, or multipart with `prescription_files`) |
| | `getFulfillmentOptions(query)` | getFulfillmentOptions | `GET /api/epharmacy/fulfillment_options` |
| | `getOrderPaymentDetails(body)` | getOrderPaymentDetails | `POST /api/epharmacy/orders/payment_details` |
| | `createOrder(body)` | createOrder | `POST /api/epharmacy/orders` |
| | `listOrders(query)` | listOrders | `GET /api/epharmacy/orders` |
| | `getOrder(orderId, query)` | getOrder | `GET /api/epharmacy/orders/{order_id}` |
| | `cancelOrder(orderId, body)` | cancelOrder | `PATCH /api/epharmacy/orders/{order_id}/cancel` |
| | `confirmOrder(orderId, body)` | confirmOrder | `POST /api/epharmacy/orders/{order_id}/confirm` |
| `wallet()` | `getCustomerWallet(companySlug, query)` | getCustomerWallet | `GET /api/{company_slug}/customers/wallet` |
| | `listCustomerPlans(companySlug, query)` | listCustomerPlans | `GET /api/{company_slug}/customers/plans` |
| `preRegistration()` | `getAccessToken()` | getPreRegistrationAccessToken | `GET /api/pre_registration/access-token` |
| | `create(body)` | createPreRegistrations | `POST /v1/corporate/pre_registrations` |
| | `update(body)` | updatePreRegistrations | `POST /v1/corporate/pre_registrations/update` |
| | `deactivate(body)` | deactivatePreRegistrations | `POST /v1/corporate/pre_registrations/deactivate` |
| | `getStatus(requestId)` | getPreRegistrationStatus | `GET /v1/corporate/pre_registrations/{request_id}/status` |
| `testing()` (staging only) | `simulateAppointmentStatus(appointmentId, body)` | simulateAppointmentStatus | `POST /api/appointments/{appointment_id}/update_status` |
| | `simulateConsultationStatus(appointmentId, body)` | simulateConsultationStatus | `PATCH /api/consultations/{appointment_id}/update_status` |
| | `simulateOrderStatus(orderId, body)` | simulateOrderStatus | `PATCH /api/epharmacy/orders/{order_id}/update_status` |

Path parameters are positional (`providerId` is a `long`, the others `String`s, URL-encoded
for you). Query parameters and JSON bodies are a `Map<String, ?>` with the API's own field
names; values may be `String`, numbers, `Boolean`, `List`, nested `Map`, `null` or a Jackson
`JsonNode`. In queries:

- a `List` is sent comma-separated: `"package_ids", List.of(1001, 1002)` is
  `package_ids=1001,1002`;
- a nested `Map` uses brackets: on the ePharmacy GET endpoints pass the member as
  `"customer_details", Map.of("api_customer_id", "CUST1001")`, sent as
  `customer_details[api_customer_id]=CUST1001`;
- `null` values are left out.

Responses are the parsed JSON as a `JsonNode` (an empty object for an empty body). Many fields
are present only in some states, so read them with `path(...)` / `at(...)`, which never return
null. The full request and response shapes are in the
[OpenAPI spec](https://github.com/hlthstack/integration-guide/blob/main/openapi/openapi.yaml).

### File uploads

Uploads take `FileInput`s, read into memory so a request can be re-sent after a token
refresh. The API accepts PDF, JPEG and PNG; the content type comes from the filename's
extension unless you give one:

```java
FileInput.of(Paths.get("reports/blood.pdf"));                  // reads the file
FileInput.of(bytes, "scan.jpg");                                // image/jpeg from the extension
FileInput.of(bytes, "scan", "image/png");                       // explicit content type
```

Consultation documents are sent as `files[][name]` + `files[][value]` pairs:

```java
client.consultations().uploadDocuments("APT1A2B3C4D5", List.of(
    ConsultationDocument.of("Blood report", FileInput.of(Paths.get("blood.pdf"))),
    ConsultationDocument.of("X-ray", FileInput.of(Paths.get("xray.png")))));
```

Prescription digitization takes exactly one customer and one or more files (each up to
10 MB); the client sends them as `customers[][name]`, ..., `plan_id` and `files[]`. Give each
upload its own `Idempotency-Key` so a retry returns the original request:

```java
Map<String, Object> customer = new LinkedHashMap<>();
customer.put("name", "Jane Doe");
customer.put("api_customer_id", "CUST1001");
customer.put("mobile_number", "9999999999");
customer.put("gender", "female");
customer.put("dob", "1990-08-24");

JsonNode accepted = client.pharmacy().digitizePrescription(
    Map.of("customers", List.of(customer), "files", List.of(FileInput.of(Paths.get("rx.jpg")))),
    RequestOptions.builder().idempotencyKey("rx-upload-0001").build());
String requestId = accepted.get("request_id").asText(); // poll getPrescriptionDigitization, or wait for the webhook
```

`updateCart` with `prescription_files` (each under 7 MB) is sent as `multipart/form-data`
with `customer_details[api_customer_id]`, `plan_id` and `prescription_files[]`, the only
multipart fields the API defines. Send other cart changes in a separate call; combining them
throws `IllegalArgumentException`.

```java
client.pharmacy().updateCart(Map.of(
    "customer_details", Map.of("api_customer_id", "CUST1001"),
    "prescription_files", List.of(FileInput.of(Paths.get("rx.pdf")))));
```

### Binary responses

Vouchers come back as PDF bytes:

```java
byte[] pdf = client.appointments().getVoucher("APT1A2B3C4D5");
Files.write(Paths.get("voucher.pdf"), pdf);
```

### Pre-registration

Register, update and deactivate members and their dependants in bulk (at most 250 rows per
call, `employees` and `dependants` together). Each write returns a `request_id`; poll
`getStatus` until `status` is `completed`, then read `success_records` and `failure_records`:

```java
Hlthstack client = Hlthstack.builder()
    .preRegistrationUsername(System.getenv("HLTHSTACK_PREREG_USERNAME"))
    .preRegistrationPassword(System.getenv("HLTHSTACK_PREREG_PASSWORD"))
    .environment(Environment.STAGING)
    .build();

Map<String, Object> employee = new LinkedHashMap<>();
employee.put("employee_code", "EMP1001");
employee.put("company_id", "1001");
employee.put("first_name", "Jane");
employee.put("last_name", "Doe");
employee.put("email", "jane.doe@example.com");
employee.put("date_of_birth", "24-08-1990");
employee.put("gender", "Female");
employee.put("mobile_number", "9999999999");
employee.put("vip", "No");
employee.put("entity_name", "");
employee.put("employee_office_location", "Hyderabad");
employee.put("department", "Engineering");
employee.put("grade", "B");
employee.put("date_of_joining", "01-01-2026");

String requestId = client.preRegistration().create(Map.of("employees", List.of(employee))).get("request_id").asText();
JsonNode status = client.preRegistration().getStatus(requestId);
while (!status.path("status").asText().equals("completed")) {
  Thread.sleep(5_000);
  status = client.preRegistration().getStatus(requestId);
}
for (JsonNode record : status.path("failure_records")) {
  System.out.println(record.path("email").asText() + " " + record.path("errors"));
}

client.preRegistration().update(Map.of("employees", List.of(Map.of("email", "jane.doe@example.com", "grade", "C"))));
client.preRegistration().deactivate(Map.of("employees", List.of("jane.doe@example.com")));
```

On `create`, every key the spec marks required must be present in each row, even when empty:
the API silently drops rows that miss one. `update` takes the identifier and only the fields to
change; `deactivate` takes employee emails and dependant mobile numbers. See the
[pre-registration guide](https://github.com/hlthstack/integration-guide/blob/main/docs/pre-registration.md).

### Staging simulators

`client.testing()` moves bookings and orders through their lifecycle on **staging** and fires
the same webhooks real updates would. These routes don't exist in production, and the client
throws `IllegalStateException` instead of calling them when its environment is `PRODUCTION`.

```java
Hlthstack staging = Hlthstack.builder().username(username).password(password).environment(Environment.STAGING).build();
staging.testing().simulateAppointmentStatus("APT1A2B3C4D5", Map.of("status", "confirm"));
staging.testing().simulateConsultationStatus("APT1A2B3C4D5", Map.of("status", "reschedule", "reschedule_time", "2030-01-16T04:30:00Z"));
staging.testing().simulateOrderStatus("EPO000000000001",
    Map.of("customer_details", Map.of("api_customer_id", "CUST1001"), "status", "in_progress"));
```

## Validation

Before any network call (and before fetching a token), every method checks its input against
the rules in the OpenAPI spec and throws `ValidationException` listing **every** problem it
found:

- required fields, types (including nullable fields), enums;
- patterns, exactly as the server applies them (some are deliberately unanchored), lengths,
  numeric ranges, list sizes, and the `date`, `date-time`, `uri` and `uuid` formats;
- path parameters, such as the shape of `booking_id` and `appointment_id`;
- cross-field rules: fields required together or only in some cases (`address` for home
  collection, `latitude`/`longitude` without `pincode`, `selected_chemist_id` for store
  pickup...), "at least one of" and "only one of" groups, unique `api_customer_id`s and product
  ids, at most 250 pre-registration rows, a positive `partner_collected_amount`;
- dates relative to today: members at least one year old, `appointment_date_time` later than
  now for consultations (a date-time without an offset is read as IST, as the API does), dates
  of birth not in the future. "Today" is the API's calendar day in India Standard Time;
- pincodes: an Indian address must not mention a 6-digit pincode other than its own;
- uploads: file count, size and type.

Query, path and multipart values travel as text, so `"pincode", 500081` and
`"latitude", "17.44"` are both accepted and checked as the server will read them.

```java
try {
  client.appointments().create(booking);
} catch (ValidationException e) {
  e.operationId(); // "createAppointment"
  for (ValidationIssue issue : e.errors()) {
    System.out.println(issue.path() + " | " + issue.rule() + " | " + issue.message());
  }
}
```

```text
customers[0].mobile_number | pattern | must match ^[0-9]{10}$
customers[0].gender | enum | must be one of "male", "female", "non-binary"
address | required_if | is required when appointment_type is home_collection
customers[1].api_customer_id | unique_by | duplicates customers[0].api_customer_id ("CUST1001")
customers[1].dob | relative_date | must be on or before 2025-11-12 (at least 1 year(s) ago)
```

`e.getMessage()` lists the same problems on one line:
`Invalid createAppointment request (5 problems): customers[0].mobile_number: must match ^[0-9]{10}$; ...`.

`path()` uses the method's own field names (`customers[0].api_customer_id`,
`documents[0].file`); it is empty when a problem concerns the request as a whole (for example
"send at least one of api_customer_id, customer_id"). `rule()` is the JSON Schema keyword
(`required`, `pattern`, ...) or the spec's rule kind (`required_if`, `unique_by`, ...) that
failed. `ValidationException` extends `HlthstackException`; its `statusCode()` is 0.

Checks that need data stored on the server, such as plan benefits, package ids, slot
availability, whether an order or appointment exists, or whether an amount matches the order,
are still made by the API and come back as HTTP errors.

**Escape hatch.** If the API accepts something this version of the library rejects, turn
validation off for one call or for the client, and please open an issue:

```java
client.appointments().create(booking, RequestOptions.builder().validate(false).build());
Hlthstack unchecked = Hlthstack.builder().username(username).password(password).validate(false).build();
```

## Errors

Every exception extends `HlthstackException` (a `RuntimeException`). A non-2xx response throws
one of these (invalid input is rejected earlier with a `ValidationException`):

| Exception | Status |
|---|---|
| `BadRequestException` | 400 |
| `AuthenticationException` | 401 (after one token refresh), or wrong credentials |
| `PermissionDeniedException` | 403 |
| `NotFoundException` | 404 |
| `ConflictException` | 409 |
| `UnprocessableEntityException` | 422 |
| `ServerException` | 5xx |
| `HlthstackException` | any other non-2xx status |
| `ApiConnectionException` | no response: network error, timeout (`timedOut()`), or interrupted |
| `ValidationException` | the request breaks the API's input rules; nothing was sent |

```java
try {
  client.appointments().reschedule("APT1A2B3C4D5", Map.of("appointment_date_time", "2030-01-16T02:30:00Z"));
} catch (BadRequestException e) {
  e.statusCode(); // 400
  e.body();       // parsed JSON, or a text node (e.g. "HTTP Token: Access denied.")
  e.messages();   // ["This appointment cannot be rescheduled"]
  e.requestId();  // x-request-id response header, when present
  e.headers();    // all response headers
} catch (ApiConnectionException e) {
  e.timedOut();
} catch (HlthstackException e) {
  // any other API error
}
```

The API uses several error body shapes (`{errors: {field: [...]}}`, `{errors: {messages:
[...]}}`, `{errors: [...]}`, `{message}`, `{success: false, message}`, `{msg, recall}`,
`{error}`, plain text, or an empty body). `body()` keeps the original; `messages()` flattens
all of them into strings.

Some ePharmacy responses are HTTP 200 with a nested `success: false` (for example
`getFulfillmentOptions`); those are returned, not thrown, so check the flags.

The client does not retry automatically, except once after a `401`. Retry 5xx, 409 and
connection errors with backoff yourself, but don't blindly retry `appointments().create` or
consultation creation: check with `appointments().get` or your webhooks first. Prescription
digitization (with the same idempotency key) and `confirmOrder` are safe to retry. See the
[errors guide](https://github.com/hlthstack/integration-guide/blob/main/docs/errors.md).

## Webhooks

hlthstack signs every webhook with an HS256 JWT in the `Signature` header. The JWT does not
cover the body, so `Webhooks.verifySignature` checks the signature, that the ids in the claims
match the body (`order_id` and `webhook_type` for pharmacy events, `request_id` for
prescription digitization, otherwise `appointment_id` and `booking_id`), and that the
`timestamp` claim is within 5 minutes of now:

```java
import io.hlthstack.VerifiedWebhook;
import io.hlthstack.WebhookVerificationException;
import io.hlthstack.Webhooks;

// In your HTTP handler for POST /hlthstack/webhooks:
try {
  VerifiedWebhook event = Webhooks.verifySignature(rawBody, signatureHeader, System.getenv("HLTHSTACK_WEBHOOK_SECRET"));
  // respond 200 fast, then process asynchronously
  enqueue(event.payload());
} catch (WebhookVerificationException e) {
  // respond 401; e.reason() says why (STALE_TIMESTAMP, ID_MISMATCH, ...)
}
```

`verifySignature(body, signature, secret)` takes the raw body (`String` or `byte[]`) or the
parsed JSON (`JsonNode` or `Map`), and returns the claims (`claims()`), the parsed body
(`payload()`) and its `type()`. Overloads take a different tolerance (`Duration`) and the
current time (`Instant`).

| `type` | Sent for |
|---|---|
| `status_update` / `reports_upload` | Health checkup and consultation status changes (`appointment_id`, `booking_id`) |
| `lab_results` | Digitized lab results (registered separately, on request) |
| `status_update` | Pharmacy order status changes (`order_id`, `service_type: "pharmacy"`) |
| `additional_payment_required` / `post_billing` | Partner-collected pharmacy payments |
| `digitization_results` | Prescription digitization finished (`request_id`) |

Appointment and pharmacy events share a URL: appointment payloads have `appointment_id`,
pharmacy payloads have `order_id`. Delivery is at-least-once and unordered: deduplicate, use
`claims().get("timestamp")` to drop older events, and treat `appointments().get` /
`pharmacy().getOrder` as the source of truth. A complete receiver on the JDK's built-in HTTP
server is in [`examples/api/WebhookReceiver.java`](../examples/api/WebhookReceiver.java). See
the [webhooks guide](https://github.com/hlthstack/integration-guide/blob/main/docs/webhooks.md).

## Development

```bash
./mvnw verify    # compiles for Java 11 (--release 11) and runs every test
```

Only a JDK 11+ is needed; the Maven Wrapper downloads Maven. CI runs on JDK 11, 17 and 21.

[`openapi/openapi.yaml`](../openapi/openapi.yaml) is a copy of the spec from
[hlthstack/integration-guide](https://github.com/hlthstack/integration-guide), which is the
source of truth. The request schemas and `x-hlthstack-rules` the validator applies are
generated from it into `src/main/resources/io/hlthstack/validation.json`. After updating the
spec, regenerate it:

```bash
./mvnw -q test-compile exec:java@generate-validation
```

The generator (`src/test/java/io/hlthstack/codegen/BuildValidation.java`) stops with an error
when the spec uses a schema keyword, format, parameter location or rule kind the validator
(`src/main/java/io/hlthstack/Validator.java`) does not implement: extend them together. Rules
are described in prose in the spec, so each one is restated as parameters in
`src/test/resources/io/hlthstack/codegen/rules.yaml`; the generator fails until a changed or
new rule is restated there. `GeneratedValidationTest` fails when `validation.json` is out of
date, and `EndpointsTest` fails if an operation has no method.

`src/test/java/PlainApiExamplesTest.java` runs the plain REST API examples in
[`examples/api/`](../examples/api/) against a local mock server, so the
[plain REST API guide](api-integration.md) stays correct. Run an example against staging with
your credentials in the environment:

```bash
./mvnw -q test-compile exec:java -Dexec.mainClass=BookHealthCheckup
```
