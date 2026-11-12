package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** In-clinic consultations and online specialist consultations (scheduled and priority). */
public final class Consultations {
  private final Transport transport;

  Consultations(Transport transport) {
    this.transport = transport;
  }

  /** {@code GET /api/consultations/inclinic_consultation/providers} (listInclinicConsultationProviders). */
  public JsonNode listInclinicProviders(Map<String, ?> query) {
    return listInclinicProviders(query, RequestOptions.NONE);
  }

  public JsonNode listInclinicProviders(Map<String, ?> query, RequestOptions options) {
    return transport.json(
        new Transport.Call("listInclinicConsultationProviders", "GET", "/api/consultations/inclinic_consultation/providers")
            .query(query),
        options);
  }

  /**
   * {@code GET /api/consultations/specialist_consultation/providers}
   * (listSpecialistConsultationProviders). Send {@code default_provider: true} to list
   * priority-consultation providers.
   */
  public JsonNode listSpecialistProviders(Map<String, ?> query) {
    return listSpecialistProviders(query, RequestOptions.NONE);
  }

  public JsonNode listSpecialistProviders(Map<String, ?> query, RequestOptions options) {
    return transport.json(
        new Transport.Call("listSpecialistConsultationProviders", "GET", "/api/consultations/specialist_consultation/providers")
            .query(query),
        options);
  }

  /**
   * {@code GET /api/providers/{provider_id}/{benefit_type}/slots} (listSlots) with {@code
   * inclinic_consultation} or {@code specialist_consultation}. Same as {@link Appointments#listSlots}.
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

  /** {@code POST /api/consultations/inclinic_consultation} (createInclinicConsultation). */
  public JsonNode createInclinic(Map<String, ?> body) {
    return createInclinic(body, RequestOptions.NONE);
  }

  public JsonNode createInclinic(Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("createInclinicConsultation", "POST", "/api/consultations/inclinic_consultation").body(body),
        options);
  }

  /** {@code POST /api/consultations/specialist_consultation} (createSpecialistConsultation). */
  public JsonNode createSpecialist(Map<String, ?> body) {
    return createSpecialist(body, RequestOptions.NONE);
  }

  public JsonNode createSpecialist(Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("createSpecialistConsultation", "POST", "/api/consultations/specialist_consultation").body(body),
        options);
  }

  /** {@code GET /api/consultations/inclinic_consultation/{appointment_id}} (getInclinicConsultation). */
  public JsonNode getInclinic(String appointmentId) {
    return getInclinic(appointmentId, RequestOptions.NONE);
  }

  public JsonNode getInclinic(String appointmentId, RequestOptions options) {
    return transport.json(
        new Transport.Call("getInclinicConsultation", "GET", "/api/consultations/inclinic_consultation/{appointment_id}", appointmentId),
        options);
  }

  /** {@code GET /api/consultations/specialist_consultation/{appointment_id}} (getSpecialistConsultation). */
  public JsonNode getSpecialist(String appointmentId) {
    return getSpecialist(appointmentId, RequestOptions.NONE);
  }

  public JsonNode getSpecialist(String appointmentId, RequestOptions options) {
    return transport.json(
        new Transport.Call("getSpecialistConsultation", "GET", "/api/consultations/specialist_consultation/{appointment_id}", appointmentId),
        options);
  }

  /** {@code PATCH /api/consultations/{appointment_id}/reschedule} (rescheduleConsultation). Same doctor only. */
  public JsonNode reschedule(String appointmentId, Map<String, ?> body) {
    return reschedule(appointmentId, body, RequestOptions.NONE);
  }

  public JsonNode reschedule(String appointmentId, Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("rescheduleConsultation", "PATCH", "/api/consultations/{appointment_id}/reschedule", appointmentId)
            .body(body),
        options);
  }

  /** {@code PATCH /api/consultations/{appointment_id}/cancel} (cancelConsultation). The response key is {@code msg}. */
  public JsonNode cancel(String appointmentId) {
    return cancel(appointmentId, RequestOptions.NONE);
  }

  public JsonNode cancel(String appointmentId, RequestOptions options) {
    return transport.json(
        new Transport.Call("cancelConsultation", "PATCH", "/api/consultations/{appointment_id}/cancel", appointmentId),
        options);
  }

  /**
   * {@code GET /api/consultations/{appointment_id}/summary_and_prescription}
   * (getConsultationSummaryAndPrescription). File links expire after a few minutes.
   */
  public JsonNode getSummaryAndPrescription(String appointmentId) {
    return getSummaryAndPrescription(appointmentId, RequestOptions.NONE);
  }

  public JsonNode getSummaryAndPrescription(String appointmentId, RequestOptions options) {
    return transport.json(
        new Transport.Call(
            "getConsultationSummaryAndPrescription", "GET", "/api/consultations/{appointment_id}/summary_and_prescription", appointmentId),
        options);
  }

  /** {@code GET /api/consultations/{appointment_id}/voucher} (getConsultationVoucher). In-clinic only; the PDF bytes. */
  public byte[] getVoucher(String appointmentId) {
    return getVoucher(appointmentId, RequestOptions.NONE);
  }

  public byte[] getVoucher(String appointmentId, RequestOptions options) {
    return transport.bytes(
        new Transport.Call("getConsultationVoucher", "GET", "/api/consultations/{appointment_id}/voucher", appointmentId),
        options);
  }

  /**
   * {@code POST /api/consultations/{appointment_id}/upload_documents}
   * (uploadConsultationDocuments). Multipart: one {@code files[][name]} + {@code files[][value]}
   * pair per document. PDF, JPEG or PNG.
   */
  public JsonNode uploadDocuments(String appointmentId, List<ConsultationDocument> documents) {
    return uploadDocuments(appointmentId, documents, RequestOptions.NONE);
  }

  public JsonNode uploadDocuments(String appointmentId, List<ConsultationDocument> documents, RequestOptions options) {
    Objects.requireNonNull(documents, "documents");
    List<Map<String, Object>> files = new ArrayList<>();
    for (ConsultationDocument document : documents) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", document.name());
      entry.put("value", document.file());
      files.add(entry);
    }
    Map<String, Object> form = new LinkedHashMap<>();
    form.put("files", files);
    return transport.json(
        new Transport.Call("uploadConsultationDocuments", "POST", "/api/consultations/{appointment_id}/upload_documents", appointmentId)
            .form(Transport.formTree(form))
            // Validated as the wire fields, reported with this method's names.
            .renamePaths(p -> p.replaceFirst("^files", "documents").replaceFirst("^(documents\\[\\d+\\])\\.value", "$1.file")),
        options);
  }
}
