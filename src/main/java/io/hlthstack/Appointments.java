package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Health checkups, lab tests, dental, vision and vaccinations. Query parameters and JSON bodies
 * are maps with the API's own field names; responses are JSON trees.
 */
public final class Appointments {
  private final Transport transport;

  Appointments(Transport transport) {
    this.transport = transport;
  }

  /**
   * {@code GET /api/providers} (listProviders). Requires {@code service_name}, {@code package_ids}
   * and {@code pincode}, or both {@code latitude} and {@code longitude}.
   */
  public JsonNode listProviders(Map<String, ?> query) {
    return listProviders(query, RequestOptions.NONE);
  }

  public JsonNode listProviders(Map<String, ?> query, RequestOptions options) {
    return transport.json(new Transport.Call("listProviders", "GET", "/api/providers").query(query), options);
  }

  /**
   * {@code GET /api/providers/{provider_id}/{benefit_type}/slots} (listSlots). Read {@code
   * booking_slots} and book with a slot's {@code utc_slot_time}.
   */
  public JsonNode listSlots(long providerId, String benefitType, Map<String, ?> query) {
    return listSlots(providerId, benefitType, query, RequestOptions.NONE);
  }

  public JsonNode listSlots(long providerId, String benefitType, Map<String, ?> query, RequestOptions options) {
    return transport.json(
        new Transport.Call("listSlots", "GET", "/api/providers/{provider_id}/{benefit_type}/slots", providerId, benefitType)
            .query(query),
        options);
  }

  /**
   * {@code POST /api/appointments} (createAppointment). Do not blindly retry a failure or timeout:
   * check with {@link #get} or your webhooks whether the booking was created first.
   */
  public JsonNode create(Map<String, ?> body) {
    return create(body, RequestOptions.NONE);
  }

  public JsonNode create(Map<String, ?> body, RequestOptions options) {
    return transport.json(new Transport.Call("createAppointment", "POST", "/api/appointments").body(body), options);
  }

  /** {@code GET /api/appointments} (getAppointment). Pass {@code booking_id}, optionally {@code appointment_id}. */
  public JsonNode get(Map<String, ?> query) {
    return get(query, RequestOptions.NONE);
  }

  public JsonNode get(Map<String, ?> query, RequestOptions options) {
    return transport.json(new Transport.Call("getAppointment", "GET", "/api/appointments").query(query), options);
  }

  /**
   * {@code GET /api/appointments/customer_appointments} (listCustomerAppointments). Up to 50,
   * newest first. Pass {@code api_customer_id} or {@code employee_id}.
   */
  public JsonNode listForCustomer(Map<String, ?> query) {
    return listForCustomer(query, RequestOptions.NONE);
  }

  public JsonNode listForCustomer(Map<String, ?> query, RequestOptions options) {
    return transport.json(
        new Transport.Call("listCustomerAppointments", "GET", "/api/appointments/customer_appointments").query(query),
        options);
  }

  /** {@code POST /api/appointments/payment_details} (getAppointmentPaymentDetails). Quotes without booking. */
  public JsonNode getPaymentDetails(Map<String, ?> body) {
    return getPaymentDetails(body, RequestOptions.NONE);
  }

  public JsonNode getPaymentDetails(Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("getAppointmentPaymentDetails", "POST", "/api/appointments/payment_details").body(body),
        options);
  }

  /** {@code PATCH /api/bookings/{booking_id}/reschedule} (rescheduleBooking). May partially succeed. */
  public JsonNode rescheduleBooking(String bookingId, Map<String, ?> body) {
    return rescheduleBooking(bookingId, body, RequestOptions.NONE);
  }

  public JsonNode rescheduleBooking(String bookingId, Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("rescheduleBooking", "PATCH", "/api/bookings/{booking_id}/reschedule", bookingId).body(body),
        options);
  }

  /** {@code PATCH /api/bookings/{booking_id}/cancel} (cancelBooking). May partially succeed. */
  public JsonNode cancelBooking(String bookingId) {
    return cancelBooking(bookingId, RequestOptions.NONE);
  }

  public JsonNode cancelBooking(String bookingId, RequestOptions options) {
    return transport.json(new Transport.Call("cancelBooking", "PATCH", "/api/bookings/{booking_id}/cancel", bookingId), options);
  }

  /** {@code PATCH /api/appointments/{appointment_id}/reschedule} (rescheduleAppointment). */
  public JsonNode reschedule(String appointmentId, Map<String, ?> body) {
    return reschedule(appointmentId, body, RequestOptions.NONE);
  }

  public JsonNode reschedule(String appointmentId, Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("rescheduleAppointment", "PATCH", "/api/appointments/{appointment_id}/reschedule", appointmentId)
            .body(body),
        options);
  }

  /** {@code PATCH /api/appointments/{appointment_id}/cancel} (cancelAppointment). */
  public JsonNode cancel(String appointmentId) {
    return cancel(appointmentId, RequestOptions.NONE);
  }

  public JsonNode cancel(String appointmentId, RequestOptions options) {
    return transport.json(
        new Transport.Call("cancelAppointment", "PATCH", "/api/appointments/{appointment_id}/cancel", appointmentId),
        options);
  }

  /**
   * {@code GET /api/appointments/{appointment_id}/voucher} (getAppointmentVoucher). Returns the PDF
   * bytes; available once the appointment is confirmed.
   */
  public byte[] getVoucher(String appointmentId) {
    return getVoucher(appointmentId, RequestOptions.NONE);
  }

  public byte[] getVoucher(String appointmentId, RequestOptions options) {
    return transport.bytes(
        new Transport.Call("getAppointmentVoucher", "GET", "/api/appointments/{appointment_id}/voucher", appointmentId),
        options);
  }

  /**
   * {@code GET /api/appointments/{appointment_id}/reports} (getAppointmentReports). {@code reports}
   * once verified, otherwise a {@code message}.
   */
  public JsonNode getReports(String appointmentId) {
    return getReports(appointmentId, RequestOptions.NONE);
  }

  public JsonNode getReports(String appointmentId, RequestOptions options) {
    return transport.json(
        new Transport.Call("getAppointmentReports", "GET", "/api/appointments/{appointment_id}/reports", appointmentId),
        options);
  }

  /** {@code GET /api/appointments/{appointment_id}/lab_results} (getAppointmentLabResults). Enabled on request. */
  public JsonNode getLabResults(String appointmentId) {
    return getLabResults(appointmentId, RequestOptions.NONE);
  }

  public JsonNode getLabResults(String appointmentId, RequestOptions options) {
    return transport.json(
        new Transport.Call("getAppointmentLabResults", "GET", "/api/appointments/{appointment_id}/lab_results", appointmentId),
        options);
  }
}
