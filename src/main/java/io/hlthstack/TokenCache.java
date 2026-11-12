package io.hlthstack;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

/**
 * Caches one kind of access token. Thread-safe: concurrent callers share one token request, and
 * the token is refreshed {@link #REFRESH_MARGIN} before it expires.
 */
final class TokenCache {
  /** Refresh the cached token this long before its {@code expires_at}. */
  static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
  /** Used when {@code expires_at} cannot be parsed: the documented token lifetime. */
  static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);

  private static final DateTimeFormatter PRE_REGISTRATION_EXPIRY =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

  private final Supplier<AccessToken> fetch;
  private final Clock clock;
  private final Object lock = new Object();
  private volatile AccessToken token;
  private volatile Instant refreshAt;

  TokenCache(Supplier<AccessToken> fetch, Clock clock) {
    this.fetch = fetch;
    this.clock = clock;
  }

  /** A valid token: the cached one, or a new one when none is cached or it is about to expire. */
  AccessToken get(boolean forceRefresh) {
    if (!forceRefresh) {
      AccessToken cached = fresh();
      if (cached != null) return cached;
    }
    synchronized (lock) {
      if (!forceRefresh) {
        AccessToken cached = fresh(); // another thread may have refreshed while this one waited
        if (cached != null) return cached;
      }
      AccessToken fetched = fetch.get();
      Instant now = clock.instant();
      Instant expiresAt = parseExpiry(fetched.expiresAt());
      refreshAt = (expiresAt == null ? now.plus(DEFAULT_LIFETIME) : expiresAt).minus(REFRESH_MARGIN);
      token = fetched;
      return fetched;
    }
  }

  private AccessToken fresh() {
    synchronized (lock) {
      AccessToken cached = token;
      return cached != null && clock.instant().isBefore(refreshAt) ? cached : null;
    }
  }

  /** Drops the cached token if it is the one that was just rejected. */
  void invalidate(String rejected) {
    synchronized (lock) {
      if (token != null && token.accessToken().equals(rejected)) token = null;
    }
  }

  /** ISO 8601 ({@code 2026-01-15T07:16:21Z}) or {@code 2026-01-15 07:16:21 UTC}; null if neither. */
  static Instant parseExpiry(String value) {
    if (value == null) return null;
    String v = value.trim();
    try {
      return OffsetDateTime.parse(v).toInstant();
    } catch (DateTimeParseException e) {
      // not ISO 8601 with an offset
    }
    try {
      return LocalDateTime.parse(v, PRE_REGISTRATION_EXPIRY).toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
