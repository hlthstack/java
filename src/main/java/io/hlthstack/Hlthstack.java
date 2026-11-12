package io.hlthstack;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Client for the hlthstack partner REST API. Server-side only: never ship your credentials,
 * tokens or secret keys to a browser or mobile app.
 *
 * <pre>{@code
 * Hlthstack client = Hlthstack.builder()
 *     .username(System.getenv("HLTHSTACK_USERNAME"))
 *     .password(System.getenv("HLTHSTACK_PASSWORD"))
 *     .environment(Environment.STAGING)
 *     .build();
 * JsonNode providers = client.appointments().listProviders(
 *     Map.of("service_name", "ahc", "package_ids", "1001", "pincode", "500081"));
 * }</pre>
 *
 * <p>Thread-safe: create one client per set of credentials and share it. Access tokens are
 * fetched, cached and refreshed for you.
 */
public final class Hlthstack {
  /** This library's version, sent in the {@code User-Agent} header. */
  public static final String VERSION = "0.1.0";
  /** The default per-request timeout. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  private final Transport transport;
  private final Auth auth;
  private final Appointments appointments;
  private final Consultations consultations;
  private final Pharmacy pharmacy;
  private final Wallet wallet;
  private final PreRegistration preRegistration;
  private final Testing testing;

  private Hlthstack(Builder builder) {
    this.transport = new Transport(builder);
    this.auth = new Auth(transport);
    this.appointments = new Appointments(transport);
    this.consultations = new Consultations(transport);
    this.pharmacy = new Pharmacy(transport);
    this.wallet = new Wallet(transport);
    this.preRegistration = new PreRegistration(transport);
    this.testing = new Testing(transport);
  }

  public static Builder builder() {
    return new Builder();
  }

  public Environment environment() {
    return transport.environment;
  }

  /** The base URL calls go to, without a trailing slash. */
  public String baseUrl() {
    return transport.baseUrl;
  }

  /** Access tokens. Fetched, cached and refreshed automatically; you rarely need this. */
  public Auth auth() {
    return auth;
  }

  /** Health checkups, lab tests, dental, vision and vaccinations. */
  public Appointments appointments() {
    return appointments;
  }

  /** In-clinic and online specialist consultations. */
  public Consultations consultations() {
    return consultations;
  }

  /** ePharmacy: medicines, prescriptions, cart and orders. */
  public Pharmacy pharmacy() {
    return pharmacy;
  }

  /** Benefit wallets and plan subscriptions. */
  public Wallet wallet() {
    return wallet;
  }

  /** Bulk member and dependant registration (separate credentials). */
  public PreRegistration preRegistration() {
    return preRegistration;
  }

  /** Staging-only lifecycle simulators. */
  public Testing testing() {
    return testing;
  }

  /** Configures a {@link Hlthstack} client. */
  public static final class Builder {
    String username;
    String password;
    String preRegistrationUsername;
    String preRegistrationPassword;
    Environment environment = Environment.PRODUCTION;
    String baseUrl;
    String entityId;
    String secretKey;
    Duration timeout;
    boolean validate = true;
    HttpClient httpClient;
    Clock clock;

    private Builder() {}

    /** API username issued by hlthstack. Required unless you only use pre-registration. */
    public Builder username(String username) {
      this.username = username;
      return this;
    }

    /** API password issued by hlthstack. */
    public Builder password(String password) {
      this.password = password;
      return this;
    }

    /** Pre-registration login (an email), separate from {@link #username}. */
    public Builder preRegistrationUsername(String username) {
      this.preRegistrationUsername = username;
      return this;
    }

    /** Pre-registration password. */
    public Builder preRegistrationPassword(String password) {
      this.preRegistrationPassword = password;
      return this;
    }

    /** {@link Environment#PRODUCTION} (the default) or {@link Environment#STAGING}. */
    public Builder environment(Environment environment) {
      this.environment = Objects.requireNonNull(environment, "environment");
      return this;
    }

    /** Overrides the environment's base URL (a proxy, or a mock server in tests). */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Multi-entity partners: the entity to act for, sent as {@code API-ENTITY-ID} on every call
     * except the token requests. Requires {@link #secretKey}. Leave unset to act for the entity
     * tied to the credentials.
     */
    public Builder entityId(String entityId) {
      this.entityId = entityId;
      return this;
    }

    /** The secret key shared at onboarding, sent as {@code API-SECRET-KEY} with the entity id. */
    public Builder secretKey(String secretKey) {
      this.secretKey = secretKey;
      return this;
    }

    /** Per-request timeout (default 60 seconds); also the connect timeout of the default client. */
    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Check every call's input against the API's rules before sending it (default true). Turn off
     * only as an escape hatch, for example if the API accepts something this version rejects.
     */
    public Builder validate(boolean validate) {
      this.validate = validate;
      return this;
    }

    /** The {@link HttpClient} to send requests with (proxy, SSL or executor settings). */
    public Builder httpClient(HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    /**
     * The clock for token expiry and date validation rules (default {@link Clock#systemUTC()}).
     * Useful in tests.
     */
    public Builder clock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Builds the client.
     *
     * @throws IllegalArgumentException when neither set of credentials is complete, or {@code
     *     entityId} is set without {@code secretKey}
     */
    public Hlthstack build() {
      return new Hlthstack(this);
    }
  }
}
