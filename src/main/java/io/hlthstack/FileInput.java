package io.hlthstack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * A file to upload: its bytes, a filename and a content type.
 *
 * <p>The API accepts PDF, JPEG and PNG. The content type is inferred from the filename's
 * extension ({@code .pdf}, {@code .png}, {@code .jpg}, {@code .jpeg}) unless you give one, so give
 * raw bytes a filename with the right extension. The bytes are held in memory (not copied), so the
 * request can be sent again after a token refresh.
 */
public final class FileInput {
  private final byte[] data;
  private final String filename;
  private final String contentType;

  private FileInput(byte[] data, String filename, String contentType) {
    this.data = Objects.requireNonNull(data, "data");
    this.filename = filename == null || filename.isEmpty() ? "file" : filename;
    this.contentType = contentType != null ? contentType : guessType(this.filename);
  }

  /** Reads the file; the filename is the path's last element. */
  public static FileInput of(Path path) {
    return of(path, null);
  }

  /** Reads the file, with an explicit content type. */
  public static FileInput of(Path path, String contentType) {
    try {
      return new FileInput(Files.readAllBytes(path), path.getFileName().toString(), contentType);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }

  /** Raw bytes with a filename (its extension sets the content type). */
  public static FileInput of(byte[] data, String filename) {
    return new FileInput(data, filename, null);
  }

  /** Raw bytes with a filename and an explicit content type. */
  public static FileInput of(byte[] data, String filename, String contentType) {
    return new FileInput(data, filename, contentType);
  }

  public byte[] data() {
    return data;
  }

  public String filename() {
    return filename;
  }

  /** The given or inferred content type, or null when it is unknown. */
  public String contentType() {
    return contentType;
  }

  public long size() {
    return data.length;
  }

  static String guessType(String filename) {
    int dot = filename.lastIndexOf('.');
    if (dot < 0) return null;
    switch (filename.substring(dot + 1).toLowerCase(Locale.ROOT)) {
      case "pdf":
        return "application/pdf";
      case "png":
        return "image/png";
      case "jpg":
      case "jpeg":
        return "image/jpeg";
      default:
        return null;
    }
  }

  @Override
  public String toString() {
    return "FileInput(" + filename + ", " + data.length + " bytes, " + contentType + ")";
  }
}
