package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Member benefit wallets and plan subscriptions. */
public final class Wallet {
  private final Transport transport;

  Wallet(Transport transport) {
    this.transport = transport;
  }

  /**
   * {@code GET /api/{company_slug}/customers/wallet} (getCustomerWallet). Identify the member by
   * {@code api_customer_id} or {@code customer_id}.
   */
  public JsonNode getCustomerWallet(String companySlug, Map<String, ?> query) {
    return getCustomerWallet(companySlug, query, RequestOptions.NONE);
  }

  public JsonNode getCustomerWallet(String companySlug, Map<String, ?> query, RequestOptions options) {
    return transport.json(
        new Transport.Call("getCustomerWallet", "GET", "/api/{company_slug}/customers/wallet", companySlug).query(query),
        options);
  }

  /** {@code GET /api/{company_slug}/customers/plans} (listCustomerPlans). */
  public JsonNode listCustomerPlans(String companySlug, Map<String, ?> query) {
    return listCustomerPlans(companySlug, query, RequestOptions.NONE);
  }

  public JsonNode listCustomerPlans(String companySlug, Map<String, ?> query, RequestOptions options) {
    return transport.json(
        new Transport.Call("listCustomerPlans", "GET", "/api/{company_slug}/customers/plans", companySlug).query(query),
        options);
  }
}
