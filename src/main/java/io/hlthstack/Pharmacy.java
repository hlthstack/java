package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ePharmacy: medicine search, prescription digitization, cart, pricing and orders. On the GET
 * endpoints pass the member as {@code customer_details: {api_customer_id: ...}} (a nested map),
 * sent as {@code customer_details[api_customer_id]}.
 */
public final class Pharmacy {
  private static final Set<String> CART_UPLOAD_FIELDS = Set.of("customer_details", "plan_id", "prescription_files");

  private final Transport transport;

  Pharmacy(Transport transport) {
    this.transport = transport;
  }

  /** {@code GET /api/epharmacy/medicines/search} (searchMedicines). {@code name} needs at least 3 characters. */
  public JsonNode searchMedicines(Map<String, ?> query) {
    return searchMedicines(query, RequestOptions.NONE);
  }

  public JsonNode searchMedicines(Map<String, ?> query, RequestOptions options) {
    return transport.json(new Transport.Call("searchMedicines", "GET", "/api/epharmacy/medicines/search").query(query), options);
  }

  /**
   * {@code POST /api/prescriptions/digitize} (digitizePrescription). Multipart: pass {@code
   * customers} (a list with exactly one member map: {@code name}, {@code api_customer_id}, {@code
   * mobile_number}, {@code gender}, {@code dob}), optionally {@code plan_id}, and {@code files} (a
   * list of {@link FileInput}: JPEG, PNG or PDF, each up to 10 MB). Sent as {@code
   * customers[][name]}, ..., {@code files[]}. Returns the {@code 202} body. Make retries safe with
   * {@link RequestOptions.Builder#idempotencyKey}.
   */
  public JsonNode digitizePrescription(Map<String, ?> form) {
    return digitizePrescription(form, RequestOptions.NONE);
  }

  public JsonNode digitizePrescription(Map<String, ?> form, RequestOptions options) {
    Objects.requireNonNull(form, "form");
    return transport.json(
        new Transport.Call("digitizePrescription", "POST", "/api/prescriptions/digitize").form(Transport.formTree(form)),
        options);
  }

  /** {@code GET /api/prescriptions/digitize/{request_id}} (getPrescriptionDigitization). */
  public JsonNode getPrescriptionDigitization(String requestId) {
    return getPrescriptionDigitization(requestId, RequestOptions.NONE);
  }

  public JsonNode getPrescriptionDigitization(String requestId, RequestOptions options) {
    return transport.json(
        new Transport.Call("getPrescriptionDigitization", "GET", "/api/prescriptions/digitize/{request_id}", requestId),
        options);
  }

  /** {@code GET /api/epharmacy/cart} (getCart). One cart per member per plan. */
  public JsonNode getCart(Map<String, ?> query) {
    return getCart(query, RequestOptions.NONE);
  }

  public JsonNode getCart(Map<String, ?> query, RequestOptions options) {
    return transport.json(new Transport.Call("getCart", "GET", "/api/epharmacy/cart").query(query), options);
  }

  /**
   * {@code PATCH /api/epharmacy/cart} (updateCart). Find-or-create and merge; atomic.
   *
   * <p>With {@code prescription_files} (a list of {@link FileInput}, each under 7 MB) the request
   * is sent as {@code multipart/form-data} with {@code customer_details[api_customer_id]}, {@code
   * plan_id} and {@code prescription_files[]}, the only multipart fields the API defines: send
   * other changes in a separate call.
   *
   * @throws IllegalArgumentException when {@code prescription_files} is combined with other fields
   */
  public JsonNode updateCart(Map<String, ?> params) {
    return updateCart(params, RequestOptions.NONE);
  }

  public JsonNode updateCart(Map<String, ?> params, RequestOptions options) {
    Objects.requireNonNull(params, "params");
    Transport.Call call = new Transport.Call("updateCart", "PATCH", "/api/epharmacy/cart");
    if (params.containsKey("prescription_files")) {
      List<String> others = new ArrayList<>();
      for (String key : params.keySet()) if (!CART_UPLOAD_FIELDS.contains(key)) others.add(key);
      if (!others.isEmpty()) {
        throw new IllegalArgumentException(
            "updateCart with prescription_files is sent as multipart/form-data, which takes only customer_details,"
                + " plan_id and prescription_files; send " + others + " in a separate call");
      }
      return transport.json(call.form(Transport.formTree(params)), options);
    }
    return transport.json(call.body(params), options);
  }

  /**
   * {@code GET /api/epharmacy/fulfillment_options} (getFulfillmentOptions). Slow (3-8 s). Check
   * the nested {@code success} flags, not just the HTTP status.
   */
  public JsonNode getFulfillmentOptions(Map<String, ?> query) {
    return getFulfillmentOptions(query, RequestOptions.NONE);
  }

  public JsonNode getFulfillmentOptions(Map<String, ?> query, RequestOptions options) {
    return transport.json(
        new Transport.Call("getFulfillmentOptions", "GET", "/api/epharmacy/fulfillment_options").query(query), options);
  }

  /** {@code POST /api/epharmacy/orders/payment_details} (getOrderPaymentDetails). Note: {@code selected_delivery_option}. */
  public JsonNode getOrderPaymentDetails(Map<String, ?> body) {
    return getOrderPaymentDetails(body, RequestOptions.NONE);
  }

  public JsonNode getOrderPaymentDetails(Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("getOrderPaymentDetails", "POST", "/api/epharmacy/orders/payment_details").body(body), options);
  }

  /** {@code POST /api/epharmacy/orders} (createOrder). Note: {@code delivery_option}. */
  public JsonNode createOrder(Map<String, ?> body) {
    return createOrder(body, RequestOptions.NONE);
  }

  public JsonNode createOrder(Map<String, ?> body, RequestOptions options) {
    return transport.json(new Transport.Call("createOrder", "POST", "/api/epharmacy/orders").body(body), options);
  }

  /** {@code GET /api/epharmacy/orders} (listOrders). Newest first. */
  public JsonNode listOrders(Map<String, ?> query) {
    return listOrders(query, RequestOptions.NONE);
  }

  public JsonNode listOrders(Map<String, ?> query, RequestOptions options) {
    return transport.json(new Transport.Call("listOrders", "GET", "/api/epharmacy/orders").query(query), options);
  }

  /** {@code GET /api/epharmacy/orders/{order_id}} (getOrder). The source of truth for an order. */
  public JsonNode getOrder(String orderId, Map<String, ?> query) {
    return getOrder(orderId, query, RequestOptions.NONE);
  }

  public JsonNode getOrder(String orderId, Map<String, ?> query, RequestOptions options) {
    return transport.json(
        new Transport.Call("getOrder", "GET", "/api/epharmacy/orders/{order_id}", orderId).query(query), options);
  }

  /** {@code PATCH /api/epharmacy/orders/{order_id}/cancel} (cancelOrder). */
  public JsonNode cancelOrder(String orderId, Map<String, ?> body) {
    return cancelOrder(orderId, body, RequestOptions.NONE);
  }

  public JsonNode cancelOrder(String orderId, Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("cancelOrder", "PATCH", "/api/epharmacy/orders/{order_id}/cancel", orderId).body(body), options);
  }

  /**
   * {@code POST /api/epharmacy/orders/{order_id}/confirm} (confirmOrder). Partner-collected payment
   * flow only. Safe to retry; on a 409 back off and retry.
   */
  public JsonNode confirmOrder(String orderId, Map<String, ?> body) {
    return confirmOrder(orderId, body, RequestOptions.NONE);
  }

  public JsonNode confirmOrder(String orderId, Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("confirmOrder", "POST", "/api/epharmacy/orders/{order_id}/confirm", orderId).body(body), options);
  }
}
