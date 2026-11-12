package io.hlthstack;

import java.util.Objects;

/**
 * One document for {@link Consultations#uploadDocuments}: sent as a {@code files[][name]} +
 * {@code files[][value]} pair.
 */
public final class ConsultationDocument {
  private final String name;
  private final FileInput file;

  private ConsultationDocument(String name, FileInput file) {
    this.name = name;
    this.file = Objects.requireNonNull(file, "file");
  }

  /** A document titled {@code name} ({@code files[][name]}) with its file ({@code files[][value]}). */
  public static ConsultationDocument of(String name, FileInput file) {
    return new ConsultationDocument(name, file);
  }

  public String name() {
    return name;
  }

  public FileInput file() {
    return file;
  }
}
