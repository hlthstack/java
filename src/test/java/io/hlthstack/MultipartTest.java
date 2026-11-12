package io.hlthstack;

import static io.hlthstack.TestSupport.list;
import static io.hlthstack.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultipartTest {
  private final MockServer server = new MockServer();
  private final Hlthstack client = TestSupport.client(server);

  @AfterEach
  void close() {
    server.close();
  }

  static Map<String, Object> member() {
    return map("name", "Jane Doe", "api_customer_id", "CUST1001", "mobile_number", "9999999999", "gender", "female", "dob", "1990-08-24");
  }

  static List<String> parts(MockServer.Request request) {
    List<String> out = new ArrayList<>();
    for (MockServer.Part p : request.form()) out.add(p.toString());
    return out;
  }

  @Test
  void digitizePrescriptionSendsBracketedFieldsAndResendsAfterA401(@TempDir Path dir) throws IOException {
    Path rx = dir.resolve("rx.pdf");
    Files.write(rx, "%PDF-1.4 rx".getBytes(StandardCharsets.UTF_8));
    server.reply("POST", "/api/prescriptions/digitize",
        new MockServer.Reply(401, "HTTP Token: Access denied."),
        new MockServer.Reply(202, map("request_id", "DIGI_RQ_A1B2C3D4E", "status", "pending")));
    String requestId = client.pharmacy()
        .digitizePrescription(
            map("customers", list(member()), "plan_id", "EXAMPLE_PLAN", "files", list(FileInput.of(rx), FileInput.of(new byte[] {1, 2}, "page2.jpg"))),
            RequestOptions.builder().idempotencyKey("rx-upload-0001").build())
        .get("request_id").asText();
    assertEquals("DIGI_RQ_A1B2C3D4E", requestId);

    List<MockServer.Request> calls = server.calls("/api/prescriptions/digitize");
    assertEquals(2, calls.size());
    List<String> expected = List.of(
        "customers[][name]=Jane Doe", "customers[][api_customer_id]=CUST1001", "customers[][mobile_number]=9999999999",
        "customers[][gender]=female", "customers[][dob]=1990-08-24", "plan_id=EXAMPLE_PLAN",
        "files[]=@rx.pdf(application/pdf):%PDF-1.4 rx", "files[]=@page2.jpg(image/jpeg):\u0001\u0002");
    for (MockServer.Request call : calls) {
      assertTrue(call.header("content-type").startsWith("multipart/form-data; boundary="));
      assertEquals("rx-upload-0001", call.header("idempotency-key"));
      assertEquals(expected, parts(call));
    }
    assertEquals("Bearer token-2", calls.get(1).header("authorization"));
  }

  @Test
  void uploadDocumentsSendsNameAndValuePairs() {
    client.consultations().uploadDocuments("APT1A2B3C4D5", List.of(
        ConsultationDocument.of("Blood report", FileInput.of("pdf".getBytes(StandardCharsets.UTF_8), "blood.pdf")),
        ConsultationDocument.of("X-ray", FileInput.of("png".getBytes(StandardCharsets.UTF_8), "xray.png"))));
    MockServer.Request call = server.calls("/api/consultations/APT1A2B3C4D5/upload_documents").get(0);
    assertEquals("POST", call.method);
    assertEquals(
        List.of(
            "files[][name]=Blood report", "files[][value]=@blood.pdf(application/pdf):pdf",
            "files[][name]=X-ray", "files[][value]=@xray.png(image/png):png"),
        parts(call));
  }

  @Test
  void updateCartWithPrescriptionFilesIsMultipart() {
    client.pharmacy().updateCart(map(
        "customer_details", map("api_customer_id", "CUST1001"), "plan_id", "EXAMPLE_PLAN",
        "prescription_files", list(FileInput.of("x".getBytes(StandardCharsets.UTF_8), "rx.pdf"))));
    MockServer.Request call = server.calls("/api/epharmacy/cart").get(0);
    assertEquals("PATCH", call.method);
    assertEquals(
        List.of("customer_details[api_customer_id]=CUST1001", "plan_id=EXAMPLE_PLAN", "prescription_files[]=@rx.pdf(application/pdf):x"),
        parts(call));
  }

  @Test
  void updateCartRefusesToMixFilesWithOtherChanges() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> client.pharmacy().updateCart(map(
        "customer_details", map("api_customer_id", "CUST1001"),
        "products", list(map("id", 50001, "quantity", 1)),
        "prescription_files", list(FileInput.of(new byte[1], "rx.pdf")))));
    assertTrue(e.getMessage().contains("[products]"));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void fileInputInfersTheContentType(@TempDir Path dir) throws IOException {
    assertEquals("application/pdf", FileInput.of(new byte[0], "a.PDF").contentType());
    assertEquals("image/jpeg", FileInput.of(new byte[0], "a.jpeg").contentType());
    assertEquals("image/png", FileInput.of(new byte[0], "a.png").contentType());
    assertEquals(null, FileInput.of(new byte[0], "a.gif").contentType());
    assertEquals("file", FileInput.of(new byte[0], null).filename());
    assertEquals("image/gif", FileInput.of(new byte[0], "a.gif", "image/gif").contentType());
    Path path = dir.resolve("scan.jpg");
    Files.write(path, new byte[] {1, 2, 3});
    FileInput file = FileInput.of(path);
    assertEquals("scan.jpg", file.filename());
    assertEquals(3, file.size());
    assertThrows(java.io.UncheckedIOException.class, () -> FileInput.of(dir.resolve("missing.pdf")));
  }

  @Test
  void unknownContentTypesAreSentAsOctetStream() {
    client.pharmacy().digitizePrescription(
        map("customers", list(member()), "files", list(FileInput.of(new byte[] {7}, "scan"))),
        RequestOptions.builder().validate(false).build());
    MockServer.Part file = server.calls("/api/prescriptions/digitize").get(0).form().get(5);
    assertEquals("files[]", file.name);
    assertEquals("application/octet-stream", file.contentType);
  }
}
