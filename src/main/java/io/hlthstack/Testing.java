package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * <b>Staging only.</b> Moves bookings and orders through their lifecycle and fires the same
 * webhooks real updates would. These routes do not exist in production, so the client refuses to
 * call them when its environment is {@link Environment#PRODUCTION}.
 */
public final class Testing {
  private final Transport transport;

  Testing(Transport transport) {
    this.transport = transport;
  }

  private void requireStaging() {
    if (transport.environment == Environment.PRODUCTION) {
      throw new IllegalStateException(
          "client.testing() endpoints exist only on staging; build the client with Environment.STAGING");
    }
  }

  /** {@code POST /api/appointments/{appointment_id}/update_status} (simulateAppointmentStatus). */
  public JsonNode simulateAppointmentStatus(String appointmentId, Map<String, ?> body) {
    return simulateAppointmentStatus(appointmentId, body, RequestOptions.NONE);
  }

  public JsonNode simulateAppointmentStatus(String appointmentId, Map<String, ?> body, RequestOptions options) {
    requireStaging();
    return transport.json(
        new Transport.Call("simulateAppointmentStatus", "POST", "/api/appointments/{appointment_id}/update_status", appointmentId)
            .body(body),
        options);
  }

  /** {@code PATCH /api/consultations/{appointment_id}/update_status} (simulateConsultationStatus). */
  public JsonNode simulateConsultationStatus(String appointmentId, Map<String, ?> body) {
    return simulateConsultationStatus(appointmentId, body, RequestOptions.NONE);
  }

  public JsonNode simulateConsultationStatus(String appointmentId, Map<String, ?> body, RequestOptions options) {
    requireStaging();
    return transport.json(
        new Transport.Call("simulateConsultationStatus", "PATCH", "/api/consultations/{appointment_id}/update_status", appointmentId)
            .body(body),
        options);
  }

  /** {@code PATCH /api/epharmacy/orders/{order_id}/update_status} (simulateOrderStatus). */
  public JsonNode simulateOrderStatus(String orderId, Map<String, ?> body) {
    return simulateOrderStatus(orderId, body, RequestOptions.NONE);
  }

  public JsonNode simulateOrderStatus(String orderId, Map<String, ?> body, RequestOptions options) {
    requireStaging();
    return transport.json(
        new Transport.Call("simulateOrderStatus", "PATCH", "/api/epharmacy/orders/{order_id}/update_status", orderId).body(body),
        options);
  }
}
