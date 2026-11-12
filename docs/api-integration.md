# Plain REST API integration guide (Java)

Call the hlthstack partner REST API directly from Java 11+ with the JDK's
`java.net.http.HttpClient`, without the `io.hlthstack` SDK. JSON is read and written with
[Jackson](https://github.com/FasterXML/jackson-databind) (any JSON library works the same
way); the webhook receiver uses the JDK's built-in `com.sun.net.httpserver`. You handle token
refresh, input checks and error parsing yourself; this guide shows small helpers for each. To
let a library do that for you, see the [SDK integration guide](sdk-integration.md).

Every snippet here comes from the runnable classes in [`examples/api/`](../examples/api/),
which are tested against a mock server in
[`src/test/java/PlainApiExamplesTest.java`](../src/test/java/PlainApiExamplesTest.java):

| File | What it does |
|---|---|
| [`ApiClient.java`](../examples/api/ApiClient.java) | Token cache, request helper with the 401 retry, errors, multipart bodies |
| [`BookHealthCheckup.java`](../examples/api/BookHealthCheckup.java) | Providers, slots, book, details, reschedule, voucher, cancel |
| [`BookConsultation.java`](../examples/api/BookConsultation.java) | In-clinic doctor consultation |
| [`PharmacyOrder.java`](../examples/api/PharmacyOrder.java) | ePharmacy cart and order |
| [`UploadPrescription.java`](../examples/api/UploadPrescription.java) | Multipart upload for prescription digitization |
| [`PreRegistration.java`](../examples/api/PreRegistration.java) | Register, update and deactivate members |
| [`WebhookReceiver.java`](../examples/api/WebhookReceiver.java) | Webhook endpoint with signature verification |

The full reference is the
[OpenAPI spec](https://github.com/hlthstack/integration-guide/blob/main/openapi/openapi.yaml)
and the [integration guide](https://github.com/hlthstack/integration-guide/tree/main/docs).

> **Server-side only.** Your API credentials, access tokens, entity secret key and webhook
> secret must never be shipped to a browser or mobile app. Call hlthstack from your backend.

Contents:

1. [Environments and base URLs](#environments-and-base-urls)
2. [Authentication](#authentication)
3. [Multi-entity partners](#multi-entity-partners)
4. [Book a health checkup end to end](#book-a-health-checkup-end-to-end)
5. [Consultations and ePharmacy](#consultations-and-epharmacy)
6. [File uploads](#file-uploads)
7. [Pre-registration](#pre-registration)
8. [Errors](#errors)
9. [Validating inputs yourself](#validating-inputs-yourself)
10. [Webhooks](#webhooks)
11. [Moving to the SDK](#moving-to-the-sdk)

## Environments and base URLs

| Environment | Base URL |
|---|---|
| Production | `https://api.hlthstack.io` |
| Staging | `https://staging-api.hlthstack.io` |

- Credentials, package ids, plan ids, entity ids and webhook secrets are different per
  environment.
- Staging has extra endpoints that move bookings and orders through their lifecycle and fire
  real webhooks ([testing](https://github.com/hlthstack/integration-guide/blob/main/docs/testing.md)).
  They don't exist in production.
- Set a client timeout of 30-60 seconds: create, reschedule and cancel calls can take a few
  seconds, and ePharmacy fulfillment options typically take 3-8 seconds.

The examples read their configuration from environment variables and default to staging:

```bash
export HLTHSTACK_USERNAME=example-user HLTHSTACK_PASSWORD=example-password   # staging credentials
export HLTHSTACK_BASE_URL=https://staging-api.hlthstack.io                   # optional
./mvnw -q test-compile exec:java -Dexec.mainClass=BookHealthCheckup
```

(`exec:java` runs a class with the project's classpath, which includes Jackson. In your own
project, copy `ApiClient.java` and add `com.fasterxml.jackson.core:jackson-databind`.)

```java
static final String DEFAULT_BASE_URL = "https://staging-api.hlthstack.io";
static final Duration TIMEOUT = Duration.ofSeconds(60); // create, reschedule and cancel can take a few seconds
static final Duration REFRESH_MARGIN = Duration.ofMinutes(5); // renew 5 minutes before expires_at
static final ObjectMapper JSON = new ObjectMapper();

final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

public ApiClient(Map<String, String> env) {
  baseUrl = env.getOrDefault("HLTHSTACK_BASE_URL", DEFAULT_BASE_URL);
  // ...
}
```

`ApiClient.fromEnvironment()` passes `System.getenv()`.

## Authentication

1. `GET /api/get-access-token` with HTTP Basic auth (the username and password hlthstack
   issued to you) returns a token valid for one hour:

   ```json
   { "access_token": "YOUR_ACCESS_TOKEN", "expires_at": "2026-01-15T07:16:21Z" }
   ```

   Wrong credentials return `401 {"message": "Wrong credentials"}`.
2. Send the token on every other call as `Authorization: Bearer <access_token>`.
3. There is no refresh token: request a new one before `expires_at`. A missing, invalid or
   expired token returns `401` with the plain-text body `HTTP Token: Access denied.`

### Token helper

Cache the token and fetch a new one five minutes before it expires. `synchronized` makes one
thread fetch the token while the others wait for it. The same class serves the
pre-registration token ([below](#pre-registration)), whose `expires_at` has a different
format:

```java
/** Fetches a token with HTTP Basic auth and caches it until shortly before it expires. */
final class AccessToken {
  final String path;
  final String username;
  final String password;
  private String value;
  private Instant refreshAt = Instant.MIN;

  AccessToken(String path, String username, String password) {
    this.path = path;
    this.username = username;
    this.password = password;
  }

  /** One refresh at a time, shared by all threads. */
  synchronized String get(boolean forceRefresh) throws IOException, InterruptedException {
    if (forceRefresh || value == null || !Instant.now().isBefore(refreshAt)) {
      String basic = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
      HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(TIMEOUT)
          .header("Authorization", "Basic " + basic)
          .GET()
          .build();
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) throw new ApiException(response.statusCode(), parseBody(response.body()));
      JsonNode data = JSON.readTree(response.body());
      value = data.get("access_token").asText();
      refreshAt = parseExpiresAt(data.get("expires_at").asText()).minus(REFRESH_MARGIN);
    }
    return value;
  }
}

static Instant parseExpiresAt(String value) {
  if (value.endsWith(" UTC")) { // pre-registration: "2026-01-15 07:16:21 UTC"
    return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")).toInstant(ZoneOffset.UTC);
  }
  return OffsetDateTime.parse(value).toInstant(); // ISO 8601: "2026-01-15T07:16:21Z"
}
```

`AccessToken` is an inner class of `ApiClient`, which creates one per credential set:

```java
token = new AccessToken(
    "/api/get-access-token", env.getOrDefault("HLTHSTACK_USERNAME", ""), env.getOrDefault("HLTHSTACK_PASSWORD", ""));
```

### Request helper

`api.get(path)`, `api.post(path)` and `api.patch(path)` start a request with the bearer token
and the [entity headers](#multi-entity-partners). Add `.query(name, value)`, a `.json(body)` or
`.multipart(form)` body and `.header(name, value)`, then `.send()` for the parsed JSON or
`.sendForBytes()` for the raw body (vouchers are PDFs). On a `401` it fetches a new token and
retries **once**; any other non-2xx response throws an [`ApiException`](#errors) with the
parsed body:

```java
/** Sends the request and returns the parsed JSON (an empty object for an empty body). */
public JsonNode send() throws IOException, InterruptedException {
  JsonNode parsed = parseBody(sendForBytes());
  return parsed == null ? JSON.createObjectNode() : parsed;
}

public byte[] sendForBytes() throws IOException, InterruptedException {
  String url = baseUrl + path + (query.isEmpty() ? "" : "?" + String.join("&", query));
  for (int attempt = 1; ; attempt++) {
    String value = accessToken.get(attempt == 2);
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
    request.header(preRegistration ? "X-EKINCARE-KEY" : "Authorization", preRegistration ? value : "Bearer " + value);
    headers.forEach(request::header);
    if (contentType != null) request.header("Content-Type", contentType);
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
    HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    int status = response.statusCode();
    if (status == 401 && attempt == 1) continue; // token expired or revoked: get a new one and retry once
    if (status < 200 || status > 299) throw new ApiException(status, parseBody(response.body()));
    return response.body();
  }
}

static JsonNode parseBody(byte[] bytes) {
  String text = new String(bytes, StandardCharsets.UTF_8);
  if (text.isBlank()) return null;
  try {
    return JSON.readTree(text);
  } catch (JsonProcessingException e) {
    return JSON.getNodeFactory().textNode(text); // e.g. "HTTP Token: Access denied."
  }
}
```

The body is kept as bytes, so the retry after a 401 can send it again (including uploads).

```java
ApiClient api = ApiClient.fromEnvironment();
JsonNode providers = api.get("/api/providers")
    .query("service_name", "ahc")
    .query("package_ids", "1001")
    .query("pincode", "500081")
    .send();
```

The full class is [`examples/api/ApiClient.java`](../examples/api/ApiClient.java).

## Multi-entity partners

If your company has several entities, your credentials are issued for the first one. To act
for another entity, add two headers to every call except `get-access-token`:

| Header | Value |
|---|---|
| `API-ENTITY-ID` | The id of the entity to act for (shared at onboarding) |
| `API-SECRET-KEY` | The secret key shared at onboarding. Required whenever `API-ENTITY-ID` is sent |

Without `API-ENTITY-ID`, calls act for the entity tied to the credentials. `ApiClient` sends
the headers on every booking-API call when `HLTHSTACK_ENTITY_ID` is set:

```java
private Map<String, String> entityHeaders() {
  Map<String, String> headers = new LinkedHashMap<>();
  if (entityId == null || entityId.isEmpty()) return headers; // act for the entity tied to the credentials
  headers.put("API-ENTITY-ID", entityId);
  headers.put("API-SECRET-KEY", secretKey);
  return headers;
}
```

To act for a different entity on one call, set the headers on that request:

```java
api.get("/api/appointments")
    .query("booking_id", "EKI0000000001700000000")
    .header("API-ENTITY-ID", "1002")
    .header("API-SECRET-KEY", System.getenv("ENTITY_1002_SECRET_KEY"))
    .send();
```

Header problems return `400 {"message": "..."}`: `API-ENTITY-ID header cannot be blank`,
`API-SECRET-KEY header cannot be blank`, `Invalid Entity`,
`API integration not enabled for this entity` or `API-SECRET-KEY is invalid`. An entity whose
contract is not enabled for API booking gets
`400 {"message": "The entity is not enabled for API booking."}` on every call. See
[authentication](https://github.com/hlthstack/integration-guide/blob/main/docs/authentication.md).

## Book a health checkup end to end

A booking covers one or more members at one provider and time (`booking_id`); each member gets
an appointment (`appointment_id`). From
[`BookHealthCheckup.java`](../examples/api/BookHealthCheckup.java), with `PACKAGE_ID = 1001`
(use the package ids shared with you) and `PINCODE = "500081"`. The full flow is in the
[health checkups guide](https://github.com/hlthstack/integration-guide/blob/main/docs/health-checkups.md).

### 1. List providers

`GET /api/providers` with `service_name`, `package_ids` (comma-separated) and `pincode`, or
`latitude` + `longitude` instead of the pincode. Optional: `radius`, `page`, `per_page` (at
most 50), `plan_id`, `auto_allocate_provider`. Results are sorted by distance.

```java
static List<JsonNode> listProviders(ApiClient api) throws IOException, InterruptedException {
  JsonNode response = api.get("/api/providers")
      .query("service_name", "ahc")
      .query("package_ids", PACKAGE_ID) // comma-separated for several: "1001,1002"
      .query("pincode", PINCODE)
      .send();
  List<JsonNode> providers = new ArrayList<>();
  response.path("providers").forEach(providers::add);
  return providers;
}
```

### 2. Get slots

`GET /api/providers/{provider_id}/{benefit_type}/slots` with `package_ids` and `pincode`. Pass
`booking_date` (`YYYY-MM-DD`) for one day, or omit it for up to `count_days` days (default
14). Read `booking_slots[].slots[]` and book with a slot's `utc_slot_time`. A `booking_date`
before the provider's first bookable date (from its `slot_lead_time` and `timezone`) returns
400.

```java
static List<String> availableSlots(ApiClient api, long providerId) throws IOException, InterruptedException {
  // No booking_date: up to 14 days of slots, from the provider's first bookable date.
  JsonNode response = api.get("/api/providers/" + providerId + "/ahc/slots")
      .query("package_ids", PACKAGE_ID)
      .query("pincode", PINCODE)
      .send();
  List<String> slots = new ArrayList<>();
  for (JsonNode day : response.path("booking_slots")) {
    for (JsonNode slot : day.path("slots")) {
      if (slot.path("available").asBoolean()) slots.add(slot.get("utc_slot_time").asText());
    }
  }
  return slots;
}
```

### 3. Create the booking

`POST /api/appointments`. `appointment_type` is `inclinic` or `home_collection`; home
collection also needs `address` (`address_line_one`, `address_line_two`, `city`, `state`,
`country`, `pincode`, `latitude`, `longitude`). Optional: `plan_id`, `customer_plan_id` or
`subscription_id`, `api_partner_order_id`.

```java
static JsonNode createBooking(ApiClient api, long providerId, String slotTime) throws IOException, InterruptedException {
  Map<String, Object> customer = new LinkedHashMap<>();
  customer.put("name", "Jane Doe");
  customer.put("dob", "1990-08-24");
  customer.put("gender", "female");
  customer.put("mobile_number", "9999999999");
  customer.put("api_customer_id", "CUST1001");
  customer.put("package_ids", List.of(PACKAGE_ID));
  Map<String, Object> body = new LinkedHashMap<>();
  body.put("service_name", "ahc");
  body.put("appointment_type", "inclinic");
  body.put("provider_id", providerId);
  body.put("appointment_date_time", slotTime); // a slot's utc_slot_time
  body.put("customers", List.of(customer));
  return api.post("/api/appointments").json(body).send();
}
```

The response has `booking_id`, `status` and `customers[].appointment_id`:

- `status: "confirmed"`: paid from the member's wallet. The appointment itself starts at
  `new` and becomes `confirmed` when the provider confirms it (you get a webhook).
- `status: "payment_pending"` with `payment_link`: the member must pay the rest.

Don't blindly retry a failed or timed-out create: check with step 4 or your webhooks whether
the booking exists first (see [Errors](#errors)).

### 4. Get the booking

`GET /api/appointments?booking_id=...` returns every appointment in the booking; add
`appointment_id` for one. Each appointment has the same shape as the webhook payload.

```java
static JsonNode getBooking(ApiClient api, String bookingId) throws IOException, InterruptedException {
  return api.get("/api/appointments").query("booking_id", bookingId).send();
}

for (JsonNode appointment : getBooking(api, booking.get("booking_id").asText()).path("appointments")) {
  System.out.println(appointment.get("appointment_id").asText() + " " + appointment.get("status").asText());
}
```

### 5. Reschedule

`PATCH /api/appointments/{appointment_id}/reschedule` (or
`PATCH /api/bookings/{booking_id}/reschedule` for the whole booking) with a new
`appointment_date_time`. Add `provider_id` to move to another provider (not allowed once the
appointment is confirmed at its provider), and for home collection `address` together with
`provider_id` to change the address.

```java
static void reschedule(ApiClient api, String appointmentId, String slotTime) throws IOException, InterruptedException {
  api.patch("/api/appointments/" + appointmentId + "/reschedule").json(Map.of("appointment_date_time", slotTime)).send();
}
```

### 6. Cancel

`PATCH /api/appointments/{appointment_id}/cancel` (or `PATCH /api/bookings/{booking_id}/cancel`),
with no body. Rescheduling and cancelling are allowed until 30 minutes before the slot (home
collection: or until a phlebotomist is assigned, whichever is first). Booking-level calls can
succeed partially: `success` and `failed` in the response list appointment ids.

```java
static void cancel(ApiClient api, String appointmentId) throws IOException, InterruptedException {
  api.patch("/api/appointments/" + appointmentId + "/cancel").send();
}
```

### 7. Download the voucher

`GET /api/appointments/{appointment_id}/voucher` returns a PDF once the appointment is
`confirmed` (before that,
`400 voucher only available for download when appointment is confirmed`):

```java
static void downloadVoucher(ApiClient api, String appointmentId, Path path) throws IOException, InterruptedException {
  byte[] pdf = api.get("/api/appointments/" + appointmentId + "/voucher").sendForBytes();
  Files.write(path, pdf);
}
```

On staging, confirm an appointment yourself with the staging-only status endpoint, as the
example does before downloading the voucher:

```java
/** Staging only: confirm the appointment as the provider would. */
static void simulateConfirmation(ApiClient api, String appointmentId) throws IOException, InterruptedException {
  api.post("/api/appointments/" + appointmentId + "/update_status").json(Map.of("status", "confirm")).send();
}
```

After the visit, `GET /api/appointments/{appointment_id}/reports` and
`GET /api/appointments/{appointment_id}/lab_results` return the reports and structured
results.

## Consultations and ePharmacy

### In-clinic consultation

Find a doctor, pick a slot, book. From
[`BookConsultation.java`](../examples/api/BookConsultation.java):

```java
static JsonNode bookInclinicConsultation(ApiClient api) throws IOException, InterruptedException {
  JsonNode doctors = api.get("/api/consultations/inclinic_consultation/providers")
      .query("package_ids", PACKAGE_ID)
      .query("pincode", "500084")
      .query("sort_by", "distance")
      .send()
      .path("providers");
  long providerId = doctors.get(0).get("provider_id").asLong();
  JsonNode slots = api.get("/api/providers/" + providerId + "/inclinic_consultation/slots")
      .query("package_ids", PACKAGE_ID)
      .send();
  String slotTime = null;
  for (JsonNode day : slots.path("booking_slots")) {
    for (JsonNode slot : day.path("slots")) {
      if (slotTime == null && slot.path("available").asBoolean()) slotTime = slot.get("utc_slot_time").asText();
    }
  }
  if (slotTime == null) throw new IllegalStateException("No free slot at this doctor.");

  Map<String, Object> customer = new LinkedHashMap<>();
  customer.put("name", "Jane Doe");
  customer.put("dob", "1990-08-24");
  customer.put("mobile_number", "9999999999");
  customer.put("api_customer_id", "CUST1001");
  customer.put("gender", "female");
  customer.put("package_ids", List.of(PACKAGE_ID));
  Map<String, Object> address = new LinkedHashMap<>(); // the member's address; pincode is required here
  address.put("address_line_one", "1 Example Road");
  address.put("address_line_two", "Kondapur");
  address.put("city", "Hyderabad");
  address.put("state", "Telangana");
  address.put("country", "India");
  address.put("pincode", "500084");
  address.put("latitude", 17.47);
  address.put("longitude", 78.35);
  Map<String, Object> body = new LinkedHashMap<>();
  body.put("appointment_date_time", slotTime); // must be in the future
  body.put("service_mode", "at_provider_location");
  body.put("provider_id", providerId);
  body.put("customers", List.of(customer));
  body.put("address", address);
  return api.post("/api/consultations/inclinic_consultation").json(body).send();
}
```

Track it with `GET /api/consultations/inclinic_consultation/{appointment_id}`, reschedule with
`PATCH /api/consultations/{appointment_id}/reschedule`, cancel with
`PATCH /api/consultations/{appointment_id}/cancel`. Audio and video specialist consultations
(scheduled or priority) use `/api/consultations/specialist_consultation`. See the
[consultations guide](https://github.com/hlthstack/integration-guide/blob/main/docs/consultations.md).

### ePharmacy: cart to order

Fill the member's cart, read the delivery options, place the order. From
[`PharmacyOrder.java`](../examples/api/PharmacyOrder.java), with
`CUSTOMER = Map.of("api_customer_id", "CUST1001")` and a medicine id from
`GET /api/epharmacy/medicines/search?name=...`:

```java
static JsonNode placeOrder(ApiClient api, long medicineId) throws IOException, InterruptedException {
  Map<String, Object> address = new LinkedHashMap<>();
  address.put("line1", "1 Example Road");
  address.put("line2", "Motera");
  address.put("city", "Ahmedabad");
  address.put("state", "Gujarat");
  address.put("pincode", "380005");
  Map<String, Object> cartUpdate = new LinkedHashMap<>();
  cartUpdate.put("customer_details", CUSTOMER);
  cartUpdate.put("products", List.of(Map.of("id", medicineId, "quantity", 1)));
  cartUpdate.put("address", address);
  JsonNode cart = api.patch("/api/epharmacy/cart").json(cartUpdate).send().get("cart");

  JsonNode options = api.get("/api/epharmacy/fulfillment_options") // slow: typically 3-8 seconds
      .query("customer_details[api_customer_id]", CUSTOMER.get("api_customer_id"))
      .query("cart_id", cart.get("id").asLong())
      .send();
  JsonNode delivery = options.path("delivery_options");
  if (!delivery.path("success").asBoolean() || !delivery.has("standard")) { // HTTP 200 can still fail
    throw new IllegalStateException("Standard delivery unavailable: " + delivery.path("message").asText());
  }

  Map<String, Object> order = new LinkedHashMap<>();
  order.put("customer_details", CUSTOMER);
  order.put("cart_id", cart.get("id").asLong());
  order.put("delivery_option", "standard");
  order.put("location_token", delivery.get("standard").get("location_token").asText());
  return api.post("/api/epharmacy/orders").json(order).send(); // status: confirmed | payment_pending | partner_payment_pending
}
```

Query parameters on ePharmacy endpoints use bracketed names such as
`customer_details[api_customer_id]`. Prescription-only medicines (`rx_required: true`) need a
prescription on the cart (`prescription_urls`, or `prescription_files[]` uploaded as
multipart). `POST /api/epharmacy/orders/payment_details` quotes the amounts before you order
(note: `selected_delivery_option` there, `delivery_option` when ordering). In the
partner-collected payment flow, confirm the payment with
`POST /api/epharmacy/orders/{order_id}/confirm`. See the
[ePharmacy guide](https://github.com/hlthstack/integration-guide/blob/main/docs/epharmacy.md).

## File uploads

Uploads are `multipart/form-data` with **bracketed form field names** exactly as the spec
defines them. The JDK has no multipart encoder, so `ApiClient.Multipart` builds the body in
memory (which also lets the retry after a 401 send it again):

```java
/** A multipart/form-data body, built in memory so a retry can send it again. */
public static final class Multipart {
  private final String boundary = "hlthstack-" + UUID.randomUUID();
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  /** A text field, with the bracketed name the API expects, such as {@code customers[][dob]}. */
  public Multipart field(String name, String value) {
    write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    return this;
  }

  /** A file field, such as {@code files[]}. Repeat the name to send several files. */
  public Multipart file(String name, String filename, String contentType, byte[] content) {
    write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
        + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
    out.writeBytes(content);
    write("\r\n");
    return this;
  }

  String contentType() {
    return "multipart/form-data; boundary=" + boundary;
  }

  byte[] toByteArray() {
    ByteArrayOutputStream copy = new ByteArrayOutputStream();
    copy.writeBytes(out.toByteArray());
    copy.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return copy.toByteArray();
  }

  private void write(String text) {
    out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
  }
}
```

Prescription digitization (`POST /api/prescriptions/digitize`) takes exactly one customer as
`customers[][name]`, `customers[][api_customer_id]`, `customers[][mobile_number]`,
`customers[][gender]` and `customers[][dob]`, an optional `plan_id`, and one or more `files[]`
(JPEG, PNG or PDF, each up to 10 MB). From
[`UploadPrescription.java`](../examples/api/UploadPrescription.java):

```java
static JsonNode digitizePrescription(ApiClient api, Path path, String idempotencyKey) throws IOException, InterruptedException {
  byte[] content = Files.readAllBytes(path); // bytes, so the retry after a 401 can resend the file
  String filename = path.getFileName().toString();
  String contentType = filename.toLowerCase().endsWith(".pdf") ? "application/pdf" : "image/jpeg";
  ApiClient.Multipart form = new ApiClient.Multipart() // bracketed field names, exactly one customer
      .field("customers[][name]", "Jane Doe")
      .field("customers[][api_customer_id]", "CUST1001")
      .field("customers[][mobile_number]", "9999999999")
      .field("customers[][gender]", "female")
      .field("customers[][dob]", "1990-08-24")
      .file("files[]", filename, contentType, content);
  return api.post("/api/prescriptions/digitize")
      .multipart(form)
      .header("Idempotency-Key", idempotencyKey) // retries return the same request
      .send(); // 202 {"request_id": ..., "status": "pending"}
}

static JsonNode waitForDigitization(ApiClient api, String requestId, Duration interval) throws IOException, InterruptedException {
  while (true) {
    JsonNode result = api.get("/api/prescriptions/digitize/" + requestId).send();
    if (FINISHED.contains(result.path("status").asText())) return result; // completed, partial, rejected, failed
    Thread.sleep(interval.toMillis()); // or wait for the digitization_results webhook
  }
}
```

Send a unique `Idempotency-Key` per upload so a retry returns the original request instead of
creating a duplicate. `completed` and `partial` results carry the extracted `items` and a
ready `cart_id`.

Other uploads follow the same pattern: consultation documents
(`POST /api/consultations/{appointment_id}/upload_documents`, as `files[][name]` +
`files[][value]` pairs) and cart prescriptions (`PATCH /api/epharmacy/cart` with
`customer_details[api_customer_id]` and `prescription_files[]`, each under 7 MB).

## Pre-registration

Pre-registration registers, updates and deactivates members and their dependants in bulk. It
has **its own credentials** (an account email and password, separate from the booking API's)
and its own token:

1. `GET /api/pre_registration/access-token` with HTTP Basic auth (the account email as the
   username) returns
   `{"access_token": "YOUR_PRE_REGISTRATION_TOKEN", "expires_at": "2026-01-15 07:16:21 UTC"}`.
   The token lasts one hour. `expires_at` is UTC but formatted `YYYY-MM-DD HH:MM:SS UTC`, not
   ISO 8601 (`parseExpiresAt` above handles both).
2. Send the token **without a `Bearer` prefix** in the `X-EKINCARE-KEY` header. The
   `API-ENTITY-ID` / `API-SECRET-KEY` headers are not used; each row names its company with
   `company_id`.

A missing, invalid or expired token returns `401` with an empty body, as does any token once
your company's contract has ended; `403` with an empty body means the account's role may not
use this API. In `ApiClient` a second `AccessToken` holds this token, and
`api.preRegistration(method, path)` sends it:

```java
preRegistrationToken = new AccessToken(
    "/api/pre_registration/access-token",
    env.getOrDefault("HLTHSTACK_PREREG_USERNAME", ""),
    env.getOrDefault("HLTHSTACK_PREREG_PASSWORD", ""));

/** A call to a pre-registration endpoint: the raw token in {@code X-EKINCARE-KEY}, no Bearer. */
public Request preRegistration(String method, String path) {
  return new Request(method, path, preRegistrationToken, true);
}
```

Each write takes at most **250 rows** (`employees` and `dependants` together) and returns a
`request_id`; poll `GET /v1/corporate/pre_registrations/{request_id}/status` until `status` is
`completed`, then read `success_records` and `failure_records`. On create, **every required
key must be present in every row**, even when empty: a row missing one is dropped silently.
From [`PreRegistration.java`](../examples/api/PreRegistration.java), where `employee()`
returns a row with every required key (`employee_code`, `company_id`, `first_name`,
`last_name`, `email`, `date_of_birth` as `DD-MM-YYYY`, `gender`, `mobile_number`, `vip`,
`entity_name`, `employee_office_location`, `department`, `grade`, `date_of_joining`):

```java
static final int MAX_ROWS = 250; // employees + dependants per call

static String submit(ApiClient api, String path, Map<String, List<?>> body) throws IOException, InterruptedException {
  int rows = body.getOrDefault("employees", List.of()).size() + body.getOrDefault("dependants", List.of()).size();
  if (rows > MAX_ROWS) throw new IllegalArgumentException("at most " + MAX_ROWS + " rows per call, got " + rows);
  JsonNode accepted = api.preRegistration("POST", path).json(body).send();
  return accepted.get("request_id").asText();
}

static JsonNode waitFor(ApiClient api, String requestId, Duration interval) throws IOException, InterruptedException {
  while (true) {
    JsonNode status = api.preRegistration("GET", "/v1/corporate/pre_registrations/" + requestId + "/status").send();
    if (status.path("status").asText().equals("completed")) return status;
    Thread.sleep(interval.toMillis());
  }
}

String requestId = submit(api, "/v1/corporate/pre_registrations", Map.of("employees", List.of(employee())));
JsonNode status = waitFor(api, requestId, interval);
for (JsonNode record : status.path("failure_records")) {
  System.out.println("failed: " + record.path("email").asText() + " " + record.get("errors"));
}

Map<String, List<?>> update = Map.of("employees", List.of(Map.of("email", "jane.doe@example.com", "grade", "C")));
waitFor(api, submit(api, "/v1/corporate/pre_registrations/update", update), interval);

Map<String, List<?>> deactivate = Map.of("employees", List.of("jane.doe@example.com")); // emails; dependants by mobile
waitFor(api, submit(api, "/v1/corporate/pre_registrations/deactivate", deactivate), interval);
```

Field rules, dependants, voluntary plans and row errors are in the
[pre-registration guide](https://github.com/hlthstack/integration-guide/blob/main/docs/pre-registration.md).

## Errors

Check the HTTP status first, then read the body defensively: error bodies are not uniform
across endpoints.

| Shape | Example | Where |
|---|---|---|
| Field errors | `{"errors": {"package_ids": ["should be an integer array"]}}` | Validation (usually 400). Nested fields are prefixed: `customer_mobile_number`, `address_pincode` |
| Messages | `{"errors": {"messages": ["This booking cannot be rescheduled"]}}` | Business-rule failures (400/422) and unexpected errors (422) |
| Plain message | `{"message": "Required details are missing"}` | Auth, entity headers, slots |
| Bare list | `{"errors": ["Invalid status"]}` | Staging simulation endpoints |
| Success flag | `{"success": false, "message": "Order not found"}` | ePharmacy |
| Booking failure | `{"msg": "...", "recall": false}` | Booking could not be placed |
| Single error | `{"error": "Invalid company slug for authenticated partner."}` | Wallet endpoints |
| Plain text | `HTTP Token: Access denied.` | 401 for a missing, invalid or expired bearer token |
| Empty body | | Pre-registration 401 and 403 |

`ApiException` keeps the status and parsed body, and collects the readable messages from any
of these shapes:

```java
/** A non-2xx response. {@code body} is the parsed JSON, the text as a text node, or null. */
public static final class ApiException extends RuntimeException {
  public final int status;
  public final transient JsonNode body;
  public final transient List<String> messages;

  ApiException(int status, JsonNode body) {
    super("HTTP " + status + ": " + (errorMessages(body).isEmpty() ? "no error message" : String.join("; ", errorMessages(body))));
    this.status = status;
    this.body = body;
    this.messages = errorMessages(body);
  }
}

/** Pulls the human-readable messages out of any of the API's error shapes. */
static List<String> errorMessages(JsonNode body) {
  List<String> out = new ArrayList<>();
  if (body == null) return out;
  if (body.isTextual()) { // HTTP Token: Access denied.
    if (!body.asText().isBlank()) out.add(body.asText());
    return out;
  }
  JsonNode errors = body.path("errors");
  if (errors.isArray()) { // {"errors": ["..."]}
    errors.forEach(e -> out.add(e.asText()));
  } else if (errors.isObject()) { // {"errors": {"messages": [...]}}, {"errors": {"field": [...]}}
    errors.fields().forEachRemaining(e -> {
      String field = e.getKey();
      boolean generic = field.equals("messages") || field.equals("base") || field.equals("msg");
      List<JsonNode> values = new ArrayList<>();
      if (e.getValue().isArray()) e.getValue().forEach(values::add);
      else values.add(e.getValue());
      for (JsonNode value : values) {
        if (!value.isTextual()) continue; // {"errors": {"msg": "...", "recall": false}}
        out.add(generic ? value.asText() : field + ": " + value.asText());
      }
    });
  }
  for (String key : new String[] {"message", "msg", "error"}) { // {"message"}, {"msg", "recall"}, {"error"}
    if (body.path(key).isTextual()) out.add(body.get(key).asText());
  }
  return out;
}
```

```java
try {
  BookHealthCheckup.reschedule(api, "APT1A2B3C4D5", "2030-01-16T02:30:00Z");
} catch (ApiClient.ApiException e) {
  System.out.println(e.status + " " + e.messages + " " + e.body);
} catch (IOException e) { // connection error or timeout (HttpTimeoutException): no response
  System.out.println("network error: " + e);
}
```

| Status | Meaning |
|---|---|
| 400 | Invalid parameters, invalid plan or subscription, bad entity headers, entity not enabled for API booking, or an action not allowed in the current state |
| 401 | Missing, invalid or expired token (the helper already retried once with a new token) |
| 403 | The booking or appointment isn't yours, or the service isn't enabled on your plan |
| 404 | Member, cart or order not found; a staging-only endpoint called in production |
| 409 | Concurrent partner-payment confirmation |
| 422 | Business rule or processing failure, or an unexpected error |
| 5xx | Server error, or a provider's system couldn't be reached |

Retrying:

- Retry 5xx, 409 and network errors with backoff. Don't retry other 4xx responses unchanged:
  fix the request.
- **Don't blindly retry booking creation** (`POST /api/appointments` or a consultation
  create). A timeout doesn't mean the booking failed: check `GET /api/appointments` or your
  webhooks first.
- Prescription digitization (with the same `Idempotency-Key`) and partner-payment
  confirmation are safe to retry.
- Some ePharmacy responses are HTTP 200 with `"success": false` nested inside (for example
  fulfillment options); check those flags.

See [errors](https://github.com/hlthstack/integration-guide/blob/main/docs/errors.md) for the
common messages and their fixes.

## Validating inputs yourself

The API reports invalid input as 400 or 422. To catch problems before you call it, check the
input rules yourself. The most important ones:

| Rule | Where |
|---|---|
| `pincode` is 6 digits (a booking address's pincode cannot start with 0) | Providers and slots queries, booking and consultation addresses, cart address |
| `pincode`, or both `latitude` and `longitude`, is required | `GET /api/providers`, in-clinic consultation providers |
| `api_customer_id` is alphanumeric (`^[a-zA-Z0-9]+$`) and unique within the request | Booking and consultation `customers[]`, prescription digitization |
| `gender` is `male`, `female` or `non-binary`, lowercase | Booking and consultation `customers[]`, prescription digitization (pre-registration uses `Male` / `Female` / `Non-binary`, any case) |
| `mobile_number` is 10 digits, no `+91` | Customers, cart `customer_details`, pre-registration rows |
| `package_ids` is a comma-separated list of ids in queries (`1001,1002`), an array in bodies | Providers, slots, bookings, consultations |
| `dob` is `YYYY-MM-DD` and at least one year ago; not in the future for digitization | Booking and consultation customers, prescription digitization |
| `appointment_date_time` is ISO 8601 UTC, a slot's `utc_slot_time` | Bookings, consultations, reschedules |
| A consultation's `appointment_date_time` is in the future (a time without an offset is read as IST) | Consultation create and reschedule |
| `address` is required for `appointment_type: home_collection`; its text fields must not contain any other 6-digit pincode | `POST /api/appointments`, reschedule with an address |
| `address` (with `pincode`) is required | In-clinic consultations |
| At most 250 rows in `employees` + `dependants`; dates as `DD-MM-YYYY` or `DD/MM/YYYY` | Pre-registration |

The full list is in the
[OpenAPI spec](https://github.com/hlthstack/integration-guide/blob/main/openapi/openapi.yaml):
each request schema encodes the per-field checks (`required`, `enum`, `pattern`, `minLength`,
`maximum`, `minItems`, ...), and rules that span fields or depend on today's date are listed on
each operation under `x-hlthstack-rules` (kinds described in `x-hlthstack-rule-kinds`). Dates
are compared with today's date in India (IST). Checks that need stored data (plan benefits,
wallet balances, slot availability, provider capabilities) are made only by the server.

The SDK runs all of these checks automatically before sending a request and reports every
problem at once ([SDK validation](sdk-integration.md#validation)).

## Webhooks

hlthstack POSTs JSON to the URLs you register at onboarding. Every request carries a
`Signature` header: an HS256 JWT signed with your webhook secret. The JWT has no `exp` claim
and does **not** cover the body, so check all three:

1. The signature is valid for your secret (HS256 only).
2. The ids in the claims match the ids in the body: `order_id` and `webhook_type` (= the
   body's `type`) for pharmacy events, `request_id` for prescription digitization, otherwise
   `appointment_id` and `booking_id`.
3. The `timestamp` claim (Unix seconds) is within your tolerance window, 5 minutes here, to
   block replays.

[`WebhookReceiver.java`](../examples/api/WebhookReceiver.java) does this with the standard
library only (`javax.crypto.Mac`, no JWT package), and serves the endpoint with the JDK's
`com.sun.net.httpserver.HttpServer`:

```java
static final long TOLERANCE_SECONDS = 300;

/** Checks the JWT's signature, that its ids match the body, and that it is recent. */
static JsonNode verifySignature(String signature, JsonNode body, String secret) throws InvalidSignature {
  String[] parts = signature == null ? new String[0] : signature.split("\\.", -1);
  if (parts.length != 3) throw new InvalidSignature("malformed signature");
  JsonNode header;
  JsonNode claims;
  byte[] received;
  try {
    header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
    claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    received = Base64.getUrlDecoder().decode(parts[2]);
  } catch (IllegalArgumentException | IOException e) { // bad base64 or bad JSON
    throw new InvalidSignature("malformed signature");
  }
  if (header == null || !"HS256".equals(header.path("alg").asText())) throw new InvalidSignature("algorithm must be HS256");
  byte[] expected;
  try {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
  } catch (GeneralSecurityException e) {
    throw new IllegalStateException(e);
  }
  if (!MessageDigest.isEqual(expected, received)) throw new InvalidSignature("bad signature"); // constant-time
  if (claims == null || !claims.isObject()) throw new InvalidSignature("claims must be an object");

  // The JWT has no exp claim: reject old (replayed) signatures with its timestamp.
  JsonNode timestamp = claims.get("timestamp");
  if (timestamp == null || !timestamp.isNumber()) throw new InvalidSignature("missing timestamp");
  if (Math.abs(System.currentTimeMillis() / 1000.0 - timestamp.asDouble()) > TOLERANCE_SECONDS) {
    throw new InvalidSignature("stale timestamp");
  }

  // The JWT does not cover the body: the ids in the claims must match the body's.
  boolean ok;
  if (claims.hasNonNull("order_id")) { // pharmacy
    ok = claims.get("order_id").equals(body.get("order_id")) && claims.path("webhook_type").equals(body.path("type"));
  } else if (claims.hasNonNull("request_id")) { // prescription digitization
    ok = claims.get("request_id").equals(body.get("request_id"));
  } else { // appointment, consultation, lab results
    ok = claims.hasNonNull("appointment_id")
        && claims.get("appointment_id").equals(body.get("appointment_id"))
        && claims.path("booking_id").equals(body.path("booking_id"));
  }
  if (!ok) throw new InvalidSignature("claims do not match the body");
  return claims;
}
```

```java
/** Starts the receiver; {@code enqueue} gets each verified body. */
static HttpServer start(int port, String secret, Consumer<JsonNode> enqueue) throws IOException {
  ExecutorService queue = Executors.newSingleThreadExecutor(); // process outside the request
  HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
  server.createContext("/hlthstack/webhooks", exchange -> {
    try {
      if (!exchange.getRequestMethod().equals("POST")) {
        respond(exchange, 405);
        return;
      }
      JsonNode body;
      try (InputStream in = exchange.getRequestBody()) {
        body = JSON.readTree(in);
      } catch (IOException e) {
        body = null;
      }
      if (body == null || !body.isObject()) {
        respond(exchange, 400);
        return;
      }
      try {
        verifySignature(exchange.getRequestHeaders().getFirst("Signature"), body, secret);
      } catch (InvalidSignature e) {
        respond(exchange, 401);
        return;
      }
      respond(exchange, 200); // acknowledge fast (the timeout is 30 seconds)
      JsonNode event = body;
      queue.submit(() -> enqueue.accept(event)); // process asynchronously and deduplicate
    } finally {
      exchange.close();
    }
  });
  server.start();
  return server;
}
```

```bash
HLTHSTACK_WEBHOOK_SECRET=... PORT=8080 ./mvnw -q test-compile exec:java -Dexec.mainClass=WebhookReceiver
```

- Reply 2xx quickly (the timeout is 30 seconds) and process asynchronously.
- Delivery is at-least-once and unordered: deduplicate, use the claims' `timestamp` (and
  `event_timestamp` on appointments) to drop older events, and treat
  `GET /api/appointments` / `GET /api/epharmacy/orders/{order_id}` as the source of truth.
- Failed deliveries are retried only on connection errors, timeouts and HTTP 408, 502, 503 and
  504. Other non-2xx responses (including 401 and 500) are not retried.

Payload shapes and the claims per event are in the
[webhooks guide](https://github.com/hlthstack/integration-guide/blob/main/docs/webhooks.md).

## Moving to the SDK

The SDK wraps everything above: token caching and refresh (including the pre-registration
token), entity headers, one method per endpoint, multipart uploads, exception classes with the
messages extracted, input validation and webhook verification. Install it from GitHub through
JitPack ([SDK guide: Install](sdk-integration.md#install)).

| Plain API | SDK |
|---|---|
| `api.get("/api/providers").query(...).send()` | `client.appointments().listProviders(Map.of(...))` |
| `api.post("/api/appointments").json(body).send()` | `client.appointments().create(body)` |
| `api.patch("/api/appointments/" + id + "/reschedule").json(body).send()` | `client.appointments().reschedule(id, body)` |
| `api.get("/api/appointments/" + id + "/voucher").sendForBytes()` | `client.appointments().getVoucher(id)` |
| `api.post("/api/prescriptions/digitize").multipart(form)...` | `client.pharmacy().digitizePrescription(Map.of("customers", ..., "files", ...))` |
| `api.preRegistration("POST", "/v1/corporate/pre_registrations").json(body).send()` | `client.preRegistration().create(body)` |
| `ApiClient.ApiException` | `HlthstackException` and its subclasses |
| `WebhookReceiver.verifySignature(signature, body, secret)` | `Webhooks.verifySignature(body, signature, secret)` |

Request and response fields keep their API names, so the maps you build today carry over, and
responses are the same Jackson `JsonNode`s. See the
[SDK integration guide](sdk-integration.md).
