# hlthstack for Java

Integrate Java backends with the [hlthstack](https://www.hlthstack.io/) partner REST API: book
health checkups and lab tests, in-clinic and online doctor consultations and ePharmacy orders
for your members, read their benefit wallets, pre-register members and their dependants, and
verify the webhooks hlthstack sends you.

This repository holds the Java SDK (`io.hlthstack`, Java 11+) and two integration guides: one
for the SDK, one for calling the REST API directly with `java.net.http.HttpClient`.

> **Server-side only.** Your API credentials, access tokens, entity secret key and webhook
> secret must never be shipped to a browser or mobile app. Call hlthstack from your backend.

## Choose how to integrate

| | SDK | Plain REST API |
|---|---|---|
| Guide | [docs/sdk-integration.md](docs/sdk-integration.md) | [docs/api-integration.md](docs/api-integration.md) |
| Install | From this GitHub repository via JitPack (Maven or Gradle) | Nothing from hlthstack: the JDK's `HttpClient` plus a JSON library (the guide uses Jackson) |
| Access tokens | Fetched, cached and refreshed for you; one retry after a 401 | You fetch, cache and refresh them (the guide has a helper) |
| Input validation | Every request checked against the API's rules before it is sent | You check inputs yourself, or rely on the API's 400/422 responses |
| Requests and responses | `Map` bodies and query parameters with the API's field names; responses as Jackson `JsonNode` | Whatever you build and parse |
| Errors | One exception class per status, with the messages extracted | You parse the API's several error shapes |
| Webhooks | `Webhooks.verifySignature` | Verify the HS256 JWT yourself (`javax.crypto.Mac`, shown in the guide) |
| Dependencies | Jackson databind | None beyond your JSON library |

Pick the SDK unless you can't add a dependency or want full control over every request. Both
talk to the same API with the same JSON, so you can start with one and move to the other.

## SDK quickstart

Add JitPack and the dependency (Maven shown; Gradle and building from source are in the
[SDK guide](docs/sdk-integration.md#install)). Pin a release tag or a commit:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.hlthstack</groupId>
  <artifactId>java</artifactId>
  <version>TAG-OR-COMMIT</version>
</dependency>
```

```java
import com.fasterxml.jackson.databind.JsonNode;
import io.hlthstack.Environment;
import io.hlthstack.Hlthstack;
import java.util.Map;

Hlthstack client = Hlthstack.builder()
    .username(System.getenv("HLTHSTACK_USERNAME"))
    .password(System.getenv("HLTHSTACK_PASSWORD"))
    .environment(Environment.STAGING)
    .build();

JsonNode providers = client.appointments().listProviders(
    Map.of("service_name", "ahc", "package_ids", "1001", "pincode", "500081"));
for (JsonNode provider : providers.path("providers")) {
  System.out.println(provider.path("provider_id") + " " + provider.path("provider_name").asText());
}
```

Continue with the [SDK integration guide](docs/sdk-integration.md): booking, configuration,
multi-entity partners, uploads, pre-registration, validation, errors and webhooks.

## Plain REST API quickstart

```bash
curl -u "$HLTHSTACK_USERNAME:$HLTHSTACK_PASSWORD" \
  https://staging-api.hlthstack.io/api/get-access-token
```

Then send `Authorization: Bearer <access_token>` on every other call. The
[plain REST API guide](docs/api-integration.md) walks through token caching, a full booking
with `java.net.http.HttpClient` and Jackson, uploads, pre-registration, errors, input checks
and webhook verification. Runnable classes are in [`examples/api/`](examples/api/).

## Reference

- REST API guides: https://github.com/hlthstack/integration-guide
- OpenAPI spec: https://github.com/hlthstack/integration-guide/blob/main/openapi/openapi.yaml
  (a copy is in [`openapi/openapi.yaml`](openapi/openapi.yaml))

| Environment | Base URL |
|---|---|
| Production | `https://api.hlthstack.io` |
| Staging | `https://staging-api.hlthstack.io` |

## Development

```bash
./mvnw verify    # compile for Java 11, run the tests (needs only a JDK 11+)
```

See [Development](docs/sdk-integration.md#development) in the SDK guide.

## License

MIT
