package btools.mapcreator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MapterhornOutputMetadataTest {

  private static final MapterhornOutputMetadata.Config CONFIG_A =
    new MapterhornOutputMetadata.Config(
      "archive-a", 12, 3600, 512, "legacy", "full", 0, 45);

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void decodeHeaderReadsGeometryWithoutAllocatingPixels() throws IOException {
    ElevationRaster original = new ElevationRaster();
    original.ncols = 3;
    original.nrows = 2;
    original.halfcol = false;
    original.xllcorner = -0.125;
    original.yllcorner = 44.875;
    original.cellsize = 0.25;
    original.noDataValue = Short.MIN_VALUE;
    original.eval_array = new short[]{1, 2, 3, 4, 5, 6};

    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    new ElevationRasterCoder().encodeRaster(original, encoded);
    ElevationRaster header = new ElevationRasterCoder().decodeHeader(
      new ByteArrayInputStream(encoded.toByteArray()));

    assertEquals(original.ncols, header.ncols);
    assertEquals(original.nrows, header.nrows);
    assertFalse(header.halfcol);
    assertEquals(original.xllcorner, header.xllcorner, 0.0);
    assertEquals(original.yllcorner, header.yllcorner, 0.0);
    assertEquals(original.cellsize, header.cellsize, 0.0);
    assertEquals(original.noDataValue, header.noDataValue);
    assertNull(header.eval_array);
  }

  @Test
  public void decodeRasterRejectsPixelCountOverflowBeforeAllocation() throws IOException {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(encoded)) {
      out.writeInt(50_000);
      out.writeInt(50_000);
      out.writeBoolean(false);
      out.writeDouble(0.0);
      out.writeDouble(0.0);
      out.writeDouble(1.0);
      out.writeShort(Short.MIN_VALUE);
    }

    try {
      new ElevationRasterCoder().decodeRaster(
        new ByteArrayInputStream(encoded.toByteArray()));
      fail("expected pixel-count overflow to fail");
    } catch (IOException expected) {
      assertEquals("raster dimensions are too large: 50000 x 50000", expected.getMessage());
    }
  }

  @Test
  public void freshOutputCreatesPendingMetadataAndReturnsWrite() throws IOException {
    FilePair files = files("fresh.bef");

    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, CONFIG_A, false));

    assertFalse(files.output.exists());
    assertTrue(files.metadata.isFile());
    Properties metadata = load(files.metadata);
    assertEquals("1", metadata.getProperty("schema"));
    assertEquals("1", metadata.getProperty("converter"));
    assertEquals("pending", metadata.getProperty("state"));
    assertEquals("-1", metadata.getProperty("length"));
    assertEquals("-1", metadata.getProperty("modified"));
    assertEquals("", metadata.getProperty("sha256"));
    assertNoTemporaryFiles();
  }

  @Test
  public void staleMetadataTempNamesDoNotBlockRecovery() throws IOException {
    FilePair files = files("stale-sidecar-temp.bef");
    for (int i = 1; i <= 256; i++) {
      Files.write(new File(files.metadata.getPath() + ".tmp" + i).toPath(),
        new byte[]{1});
    }

    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, CONFIG_A, false));
    assertEquals("pending", load(files.metadata).getProperty("state"));
  }

  @Test
  public void matchingCompleteOutputReturnsSkip() throws IOException {
    FilePair files = complete("complete.bef", CONFIG_A, (byte) 7);

    assertEquals(MapterhornOutputMetadata.Decision.SKIP,
      MapterhornOutputMetadata.prepare(files.output, CONFIG_A, false));
    assertEquals("complete", load(files.metadata).getProperty("state"));
  }

  @Test
  public void everyConfigFieldParticipatesInProvenance() throws IOException {
    FilePair files = complete("mismatch.bef", CONFIG_A, (byte) 8);
    MapterhornOutputMetadata.Config[] mismatches = {
      new MapterhornOutputMetadata.Config(
        "archive-b", 12, 3600, 512, "legacy", "full", 0, 45),
      new MapterhornOutputMetadata.Config(
        "archive-a", 11, 3600, 512, "legacy", "full", 0, 45),
      new MapterhornOutputMetadata.Config(
        "archive-a", 12, 1200, 512, "legacy", "full", 0, 45),
      new MapterhornOutputMetadata.Config(
        "archive-a", 12, 3600, 256, "legacy", "full", 0, 45),
      new MapterhornOutputMetadata.Config(
        "archive-a", 12, 3600, 512, "rd5", "full", 0, 45),
      new MapterhornOutputMetadata.Config(
        "archive-a", 12, 3600, 512, "legacy", "bbox=1,46,2,47", 0, 45),
      new MapterhornOutputMetadata.Config(
        "archive-a", 12, 3600, 512, "legacy", "full", 5, 45),
      new MapterhornOutputMetadata.Config(
        "archive-a", 12, 3600, 512, "legacy", "full", 0, 40)
    };

    for (MapterhornOutputMetadata.Config mismatch : mismatches) {
      assertMismatch(files.output, mismatch);
    }
    assertEquals("complete", load(files.metadata).getProperty("state"));
  }

  @Test
  public void matchingPendingOutputReturnsWriteForCrashRecovery() throws IOException {
    FilePair files = files("pending.bef");
    writeRaster(files.output, CONFIG_A, (byte) 1);
    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, CONFIG_A, true));

    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, CONFIG_A, false));
    assertEquals("pending", load(files.metadata).getProperty("state"));
  }

  @Test
  public void matchingCompleteOutputWithWrongLengthReturnsWrite() throws IOException {
    FilePair files = complete("wrong-length.bef", CONFIG_A, (byte) 2);
    Files.write(files.output.toPath(), new byte[]{99}, java.nio.file.StandardOpenOption.APPEND);

    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, CONFIG_A, false));
    assertEquals("pending", load(files.metadata).getProperty("state"));
  }

  @Test
  public void matchingCompleteOutputWithWrongHeaderReturnsWrite() throws IOException {
    FilePair files = complete("wrong-header.bef", CONFIG_A, (byte) 3);
    writeRaster(files.output, new MapterhornOutputMetadata.Config(
      "archive-a", 12, 3600, 512, "legacy", "full", 5, 45), (byte) 3);

    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, CONFIG_A, false));
    assertEquals("pending", load(files.metadata).getProperty("state"));
  }

  @Test
  public void changedMtimeWithMatchingShaSkipsAndRefreshesMetadata() throws IOException {
    FilePair files = complete("retimed.bef", CONFIG_A, (byte) 4);
    long recorded = Long.parseLong(load(files.metadata).getProperty("modified"));
    long changed = recorded + 5_000L;
    Files.setLastModifiedTime(files.output.toPath(), FileTime.fromMillis(changed));
    long actualChanged = files.output.lastModified();
    assertNotEquals("test needs a changed modified time", recorded, actualChanged);

    assertEquals(MapterhornOutputMetadata.Decision.SKIP,
      MapterhornOutputMetadata.prepare(files.output, CONFIG_A, false));

    assertEquals(Long.toString(actualChanged),
      load(files.metadata).getProperty("modified"));
    assertNoTemporaryFiles();
  }

  @Test
  public void changedSameLengthBytesReturnWriteAfterShaMismatch() throws IOException {
    FilePair files = complete("changed.bef", CONFIG_A, (byte) 5);
    byte[] bytes = Files.readAllBytes(files.output.toPath());
    bytes[bytes.length - 1] ^= 0x7f;
    Files.write(files.output.toPath(), bytes);
    Files.setLastModifiedTime(files.output.toPath(),
      FileTime.fromMillis(files.output.lastModified() + 5_000L));

    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, CONFIG_A, false));
    assertEquals("pending", load(files.metadata).getProperty("state"));
  }

  @Test
  public void preflightRejectsUnknownOutputBeforeCreatingAnyPendingMetadata()
      throws IOException {
    FilePair fresh = files("a-fresh.bef");
    FilePair unknown = files("b-unknown.bef");
    Files.write(unknown.output.toPath(), new byte[]{1, 2, 3});
    Map<File, MapterhornOutputMetadata.Config> cells = new LinkedHashMap<>();
    cells.put(fresh.output, CONFIG_A);
    cells.put(unknown.output, new MapterhornOutputMetadata.Config(
      "archive-a", 12, 3600, 512, "legacy", "full", 5, 45));

    try {
      MapterhornOutputMetadata.preflightAll(cells);
      fail("expected an unknown existing output to fail all-mode preflight");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("provenance"));
    }
    assertFalse(fresh.metadata.exists());
    assertFalse(unknown.metadata.exists());
  }

  @Test
  public void successfulPreflightCreatesPendingMetadataOnlyAfterAllCellsValidate()
      throws IOException {
    FilePair first = files("preflight-a.bef");
    FilePair second = files("preflight-b.bef");
    Map<File, MapterhornOutputMetadata.Config> cells = new LinkedHashMap<>();
    cells.put(first.output, CONFIG_A);
    cells.put(second.output, new MapterhornOutputMetadata.Config(
      "archive-a", 12, 3600, 512, "legacy", "full", 5, 45));

    MapterhornOutputMetadata.preflightAll(cells);

    assertEquals("pending", load(first.metadata).getProperty("state"));
    assertEquals("pending", load(second.metadata).getProperty("state"));
  }

  @Test
  public void bboxCannotClaimAnExistingOutput() throws IOException {
    FilePair files = files("bbox.bef");
    Files.write(files.output.toPath(), new byte[]{1});
    MapterhornOutputMetadata.Config bbox = new MapterhornOutputMetadata.Config(
      "archive-a", 12, 3600, 512, "legacy", "bbox=1.0,46.0,2.0,47.0", 0, 45);

    try {
      MapterhornOutputMetadata.prepare(files.output, bbox, true);
      fail("expected bbox conversion to refuse an existing output");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("-bbox"));
    }
    assertFalse(files.metadata.exists());
  }

  @Test
  public void bboxCanRecoverItsMatchingPendingPublishedOutput() throws IOException {
    FilePair files = files("bbox-pending.bef");
    MapterhornOutputMetadata.Config bbox = new MapterhornOutputMetadata.Config(
      "archive-a", 12, 3600, 512, "legacy", "bbox=1.0,46.0,2.0,47.0", 0, 45);
    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, bbox, false));
    writeRaster(files.output, bbox, (byte) 4);

    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, bbox, false));
    assertEquals("pending", load(files.metadata).getProperty("state"));
  }

  @Test
  public void missingMalformedAndDuplicateRequiredKeysAreMismatches() throws IOException {
    FilePair files = complete("strict.bef", CONFIG_A, (byte) 6);
    String valid = new String(Files.readAllBytes(files.metadata.toPath()),
      StandardCharsets.ISO_8859_1);

    Files.write(files.metadata.toPath(),
      valid.replaceFirst("(?m)^zoom=.*\\R", "").getBytes(StandardCharsets.ISO_8859_1));
    assertMismatch(files.output, CONFIG_A);

    Files.write(files.metadata.toPath(),
      valid.replaceFirst("(?m)^zoom=.*$", "zoom=not-a-number")
        .getBytes(StandardCharsets.ISO_8859_1));
    assertMismatch(files.output, CONFIG_A);

    Files.write(files.metadata.toPath(),
      (valid + "zoom=12\n").getBytes(StandardCharsets.ISO_8859_1));
    assertMismatch(files.output, CONFIG_A);
  }

  @Test
  public void escapedDuplicateVersionStateAndDigestViolationsAreMismatches()
      throws IOException {
    FilePair files = complete("strict-values.bef", CONFIG_A, (byte) 9);
    String valid = new String(Files.readAllBytes(files.metadata.toPath()),
      StandardCharsets.ISO_8859_1);
    String[] invalid = {
      valid + "\\u007aoom=12\n",
      replaceProperty(valid, "schema", "2"),
      replaceProperty(valid, "converter", "2"),
      replaceProperty(valid, "state", "unknown"),
      replaceProperty(valid, "sha256",
        "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg")
    };

    for (String metadata : invalid) {
      Files.write(files.metadata.toPath(), metadata.getBytes(StandardCharsets.ISO_8859_1));
      assertMismatch(files.output, CONFIG_A);
    }
  }

  @Test
  public void outputAndSidecarSymbolicLinksAreRejected() throws IOException {
    File externalOutput = tmp.newFile("external-output.bef");
    FilePair outputLink = files("output-link.bef");
    Files.createSymbolicLink(outputLink.output.toPath(), externalOutput.toPath());
    assertSymlinkRejected(outputLink.output, CONFIG_A);

    FilePair sidecarLink = files("sidecar-link.bef");
    File externalMetadata = tmp.newFile("external.properties");
    Files.createSymbolicLink(sidecarLink.metadata.toPath(), externalMetadata.toPath());
    assertSymlinkRejected(sidecarLink.output, CONFIG_A);
  }

  private FilePair complete(String name, MapterhornOutputMetadata.Config config,
                            byte payload) throws IOException {
    FilePair files = files(name);
    writeRaster(files.output, config, payload);
    assertEquals(MapterhornOutputMetadata.Decision.WRITE,
      MapterhornOutputMetadata.prepare(files.output, config, true));
    MapterhornOutputMetadata.complete(files.output, config,
      new MapterhornOutputMetadata.CompletedFile(files.output.length(),
        files.output.lastModified(), sha256(files.output)));
    return files;
  }

  private FilePair files(String name) {
    File output = new File(tmp.getRoot(), name);
    return new FilePair(output,
      new File(output.getPath() + MapterhornOutputMetadata.SUFFIX));
  }

  private static void writeRaster(File file,
                                  MapterhornOutputMetadata.Config config,
                                  byte payload) throws IOException {
    ElevationRaster expected = new ElevationRaster();
    ElevationCells.configureCellRaster(
      expected, config.lonStart, config.latStart, config.rowLength);
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file.toPath()))) {
      out.writeInt(expected.ncols);
      out.writeInt(expected.nrows);
      out.writeBoolean(expected.halfcol);
      out.writeDouble(expected.xllcorner);
      out.writeDouble(expected.yllcorner);
      out.writeDouble(expected.cellsize);
      out.writeShort(expected.noDataValue);
      out.writeByte(payload);
    }
  }

  private static Properties load(File file) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(file.toPath())) {
      properties.load(in);
    }
    return properties;
  }

  private static String sha256(File file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
    try (InputStream in = Files.newInputStream(file.toPath())) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return MapterhornOutputMetadata.hex(digest.digest());
  }

  private static String replaceProperty(String properties, String key, String value) {
    return properties.replaceFirst("(?m)^" + key + "=.*$", key + "=" + value);
  }

  private static void assertMismatch(File output,
                                     MapterhornOutputMetadata.Config config)
      throws IOException {
    try {
      MapterhornOutputMetadata.prepare(output, config, false);
      fail("expected provenance mismatch");
    } catch (IOException expected) {
      assertFalse(expected.getMessage().isEmpty());
    }
  }

  private static void assertSymlinkRejected(File output,
                                            MapterhornOutputMetadata.Config config)
      throws IOException {
    try {
      MapterhornOutputMetadata.prepare(output, config, true);
      fail("expected symbolic link rejection");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("symbolic link"));
    }
  }

  private void assertNoTemporaryFiles() {
    String[] temporaries = tmp.getRoot().list((dir, name) -> name.contains(".tmp"));
    assertEquals(0, temporaries == null ? 0 : temporaries.length);
  }

  private static final class FilePair {
    final File output;
    final File metadata;

    FilePair(File output, File metadata) {
      this.output = output;
      this.metadata = metadata;
    }
  }
}
