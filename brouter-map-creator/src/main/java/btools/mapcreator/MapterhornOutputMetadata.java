package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Persistent per-cell provenance and crash-recovery state for Mapterhorn output. */
final class MapterhornOutputMetadata {

  static final int SCHEMA_VERSION = 1;
  static final int CONVERTER_VERSION = 1;
  static final String SUFFIX = ".mapterhorn.properties";

  private static final long MAX_METADATA_BYTES = 64L * 1024L;
  private static final Set<String> REQUIRED_KEYS = Set.of(
    "schema", "converter", "archiveId", "zoom", "rowLength", "tileSize",
    "naming", "coverage", "lonStart", "latStart", "state", "length",
    "modified", "sha256");

  enum Decision {
    SKIP,
    WRITE
  }

  @FunctionalInterface
  interface AtomicMoveOperation {
    void move(Path from, Path to) throws IOException;
  }

  static final class Config {
    final String archiveId;
    final int zoom;
    final int rowLength;
    final int tileSize;
    final String naming;
    final String coverage;
    final int lonStart;
    final int latStart;

    Config(String archiveId, int zoom, int rowLength, int tileSize, String naming,
           String coverage, int lonStart, int latStart) {
      this.archiveId = requireText(archiveId, "archive ID");
      if (zoom < 0) {
        throw new IllegalArgumentException("zoom must not be negative");
      }
      if (rowLength <= 0) {
        throw new IllegalArgumentException("row length must be positive");
      }
      if (tileSize <= 0) {
        throw new IllegalArgumentException("tile size must be positive");
      }
      this.zoom = zoom;
      this.rowLength = rowLength;
      this.tileSize = tileSize;
      this.naming = requireText(naming, "naming mode");
      this.coverage = requireText(coverage, "coverage");
      this.lonStart = lonStart;
      this.latStart = latStart;
    }

    boolean isFullCoverage() {
      return "full".equals(coverage);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Config)) {
        return false;
      }
      Config that = (Config) other;
      return zoom == that.zoom && rowLength == that.rowLength
        && tileSize == that.tileSize && lonStart == that.lonStart
        && latStart == that.latStart && archiveId.equals(that.archiveId)
        && naming.equals(that.naming) && coverage.equals(that.coverage);
    }

    @Override
    public int hashCode() {
      return Objects.hash(archiveId, zoom, rowLength, tileSize, naming, coverage,
        lonStart, latStart);
    }
  }

  static final class CompletedFile {
    final long length;
    final long modified;
    final String sha256;

    CompletedFile(long length, long modified, String sha256) {
      if (length < 0L) {
        throw new IllegalArgumentException("completed file length must not be negative");
      }
      if (modified < 0L) {
        throw new IllegalArgumentException("completed file modified time must not be negative");
      }
      if (!isSha256(sha256)) {
        throw new IllegalArgumentException("completed file SHA-256 must be 64 lowercase hex digits");
      }
      this.length = length;
      this.modified = modified;
      this.sha256 = sha256;
    }
  }

  private MapterhornOutputMetadata() {
  }

  static Decision prepare(File output, Config config, boolean overwriteExisting)
      throws IOException {
    Plan plan = inspect(output, config, overwriteExisting);
    apply(plan);
    return plan.decision;
  }

  static void preflightAll(Map<File, Config> cells) throws IOException {
    if (cells == null) {
      throw new IllegalArgumentException("cell configurations are required");
    }
    List<Plan> plans = new ArrayList<>(cells.size());
    for (Map.Entry<File, Config> cell : cells.entrySet()) {
      plans.add(inspect(cell.getKey(), cell.getValue(), false));
    }
    for (Plan plan : plans) {
      apply(plan);
    }
  }

  static void complete(File output, Config config, CompletedFile completed)
      throws IOException {
    complete(output, config, completed, ConvertMapterhornTile::atomicMove);
  }

  static void complete(File output, Config config, CompletedFile completed,
                       AtomicMoveOperation atomicMoveOperation) throws IOException {
    requireArguments(output, config);
    if (completed == null) {
      throw new IllegalArgumentException("completed file evidence is required");
    }
    if (atomicMoveOperation == null) {
      throw new IllegalArgumentException("atomic move operation is required");
    }
    Path outputPath = output.toPath();
    Path metadataPath = metadataPath(outputPath);
    BasicFileAttributes outputAttributes = attributesIfPresent(outputPath);
    requireRegularOutput(outputPath, outputAttributes);
    if (outputAttributes.size() != completed.length
      || outputAttributes.lastModifiedTime().toMillis() != completed.modified) {
      throw new IOException("completed file evidence no longer matches " + output);
    }
    Metadata current = readMetadata(metadataPath);
    if (current == null || !current.config.equals(config)
      || current.state != State.PENDING) {
      throw new IOException("cannot complete " + output
        + " without matching pending Mapterhorn provenance");
    }
    writeMetadata(metadataPath, Metadata.complete(config, completed), atomicMoveOperation);
  }

  /** True only for a well-formed sidecar bound to this exact conversion config. */
  static boolean isOwned(File output, Config config) throws IOException {
    requireArguments(output, config);
    Path outputPath = output.toPath();
    rejectSymbolicLink(outputPath, attributesIfPresent(outputPath));
    Metadata metadata = readMetadata(metadataPath(outputPath));
    return metadata != null && metadata.config.equals(config);
  }

  /** Remove a matching pending transition after the source probe finds no tiles. */
  static void abandon(File output, Config config, boolean deleteOutput) throws IOException {
    requireArguments(output, config);
    Path outputPath = output.toPath();
    Path metadataPath = metadataPath(outputPath);
    BasicFileAttributes outputAttributes = attributesIfPresent(outputPath);
    rejectSymbolicLink(outputPath, outputAttributes);
    Metadata metadata = readMetadata(metadataPath);
    if (metadata == null || !metadata.config.equals(config)
      || metadata.state != State.PENDING) {
      throw new IOException("cannot abandon " + output
        + " without matching pending Mapterhorn provenance");
    }
    if (deleteOutput && outputAttributes != null) {
      if (!outputAttributes.isRegularFile()) {
        throw new IOException("Mapterhorn output is not a regular file: " + outputPath);
      }
      Files.delete(outputPath);
    }
    Files.delete(metadataPath);
  }

  static String hex(byte[] bytes) {
    char[] result = new char[bytes.length * 2];
    char[] digits = "0123456789abcdef".toCharArray();
    for (int i = 0; i < bytes.length; i++) {
      int value = bytes[i] & 0xff;
      result[2 * i] = digits[value >>> 4];
      result[2 * i + 1] = digits[value & 0xf];
    }
    return new String(result);
  }

  private static Plan inspect(File output, Config config, boolean overwriteExisting)
      throws IOException {
    requireArguments(output, config);
    Path outputPath = output.toPath();
    Path metadataPath = metadataPath(outputPath);
    BasicFileAttributes outputAttributes = attributesIfPresent(outputPath);
    rejectSymbolicLink(outputPath, outputAttributes);
    if (outputAttributes != null && !outputAttributes.isRegularFile()) {
      throw new IOException("Mapterhorn output is not a regular file: " + outputPath);
    }
    Metadata metadata = readMetadata(metadataPath);
    boolean metadataPresent = attributesIfPresent(metadataPath) != null;
    boolean matches = metadata != null && metadata.config.equals(config);

    if (matches && metadata.state == State.COMPLETE && outputAttributes != null) {
      Validation validation = validateCompleted(outputPath, outputAttributes, config, metadata);
      if (validation.valid && (!overwriteExisting || !config.isFullCoverage())) {
        return Plan.skip(metadataPath, validation.refreshed);
      }
    }

    if (!config.isFullCoverage() && outputAttributes != null && !matches) {
      throw new IOException("refusing to overwrite existing " + output
        + " with a -bbox conversion; delete it first or use another output directory");
    }
    if (matches && metadata.state == State.PENDING) {
      return Plan.write(metadataPath, null);
    }
    if (!matches && !overwriteExisting && (outputAttributes != null || metadataPresent)) {
      throw provenanceMismatch(output);
    }
    return Plan.write(metadataPath, Metadata.pending(config));
  }

  private static void apply(Plan plan) throws IOException {
    if (plan.metadata != null) {
      writeMetadata(plan.metadataPath, plan.metadata, ConvertMapterhornTile::atomicMove);
    }
  }

  private static Validation validateCompleted(Path outputPath,
                                              BasicFileAttributes attributes,
                                              Config config, Metadata metadata) {
    if (attributes.size() != metadata.length) {
      return Validation.invalid();
    }
    ElevationRaster expected = new ElevationRaster();
    ElevationCells.configureCellRaster(
      expected, config.lonStart, config.latStart, config.rowLength);
    try (InputStream in = new BufferedInputStream(Channels.newInputStream(
        Files.newByteChannel(outputPath, StandardOpenOption.READ,
          LinkOption.NOFOLLOW_LINKS)))) {
      ElevationRaster actual = new ElevationRasterCoder().decodeHeader(in);
      if (!sameHeader(expected, actual)) {
        return Validation.invalid();
      }
    } catch (IOException e) {
      return Validation.invalid();
    }

    BasicFileAttributes afterHeader;
    try {
      afterHeader = attributesIfPresent(outputPath);
    } catch (IOException e) {
      return Validation.invalid();
    }
    if (!sameFileState(attributes, afterHeader)) {
      return Validation.invalid();
    }
    long modified = afterHeader.lastModifiedTime().toMillis();
    if (modified == metadata.modified) {
      return Validation.valid(null);
    }
    try {
      String actualSha = sha256(outputPath);
      BasicFileAttributes after = attributesIfPresent(outputPath);
      if (!sameFileState(afterHeader, after)
        || !actualSha.equals(metadata.sha256)) {
        return Validation.invalid();
      }
      return Validation.valid(Metadata.complete(config,
        new CompletedFile(after.size(), after.lastModifiedTime().toMillis(), actualSha)));
    } catch (IOException e) {
      return Validation.invalid();
    }
  }

  private static boolean sameHeader(ElevationRaster expected, ElevationRaster actual) {
    return expected.ncols == actual.ncols && expected.nrows == actual.nrows
      && expected.halfcol == actual.halfcol
      && Double.doubleToLongBits(expected.xllcorner)
        == Double.doubleToLongBits(actual.xllcorner)
      && Double.doubleToLongBits(expected.yllcorner)
        == Double.doubleToLongBits(actual.yllcorner)
      && Double.doubleToLongBits(expected.cellsize)
        == Double.doubleToLongBits(actual.cellsize)
      && expected.noDataValue == actual.noDataValue;
  }

  private static boolean sameFileState(BasicFileAttributes before,
                                       BasicFileAttributes after) {
    return before != null && after != null && before.isRegularFile()
      && after.isRegularFile() && before.size() == after.size()
      && before.lastModifiedTime().equals(after.lastModifiedTime())
      && Objects.equals(before.fileKey(), after.fileKey());
  }

  private static Metadata readMetadata(Path metadataPath) throws IOException {
    BasicFileAttributes attributes = attributesIfPresent(metadataPath);
    if (attributes == null) {
      return null;
    }
    rejectSymbolicLink(metadataPath, attributes);
    if (!attributes.isRegularFile()) {
      throw new IOException("Mapterhorn sidecar is not a regular file: " + metadataPath);
    }
    if (attributes.size() > MAX_METADATA_BYTES) {
      return null;
    }
    byte[] bytes;
    try (InputStream in = Channels.newInputStream(Files.newByteChannel(
        metadataPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      bytes = in.readAllBytes();
    }
    if (!hasUniqueRequiredKeys(bytes)) {
      return null;
    }

    Properties properties = new Properties();
    try {
      properties.load(new ByteArrayInputStream(bytes));
      int schema = Integer.parseInt(properties.getProperty("schema"));
      int converter = Integer.parseInt(properties.getProperty("converter"));
      Config config = new Config(
        properties.getProperty("archiveId"),
        Integer.parseInt(properties.getProperty("zoom")),
        Integer.parseInt(properties.getProperty("rowLength")),
        Integer.parseInt(properties.getProperty("tileSize")),
        properties.getProperty("naming"), properties.getProperty("coverage"),
        Integer.parseInt(properties.getProperty("lonStart")),
        Integer.parseInt(properties.getProperty("latStart")));
      State state = State.parse(properties.getProperty("state"));
      long length = Long.parseLong(properties.getProperty("length"));
      long modified = Long.parseLong(properties.getProperty("modified"));
      String sha256 = properties.getProperty("sha256");
      if (schema != SCHEMA_VERSION || converter != CONVERTER_VERSION
        || !validStateFields(state, length, modified, sha256)) {
        return null;
      }
      return new Metadata(config, state, length, modified, sha256);
    } catch (IllegalArgumentException | NullPointerException e) {
      return null;
    }
  }

  private static boolean hasUniqueRequiredKeys(byte[] bytes) {
    Set<String> found = new HashSet<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(
        new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String leading = line.stripLeading();
        if (leading.isEmpty() || leading.startsWith("#") || leading.startsWith("!")) {
          continue;
        }
        if (hasContinuation(line)) {
          return false;
        }
        Properties oneLine = new Properties();
        oneLine.load(new StringReader(line));
        if (oneLine.size() != 1) {
          return false;
        }
        String key = oneLine.stringPropertyNames().iterator().next();
        if (REQUIRED_KEYS.contains(key) && !found.add(key)) {
          return false;
        }
      }
    } catch (IOException | IllegalArgumentException e) {
      return false;
    }
    return found.equals(REQUIRED_KEYS);
  }

  private static boolean hasContinuation(String line) {
    int backslashes = 0;
    for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
      backslashes++;
    }
    return (backslashes & 1) != 0;
  }

  private static boolean validStateFields(State state, long length, long modified,
                                          String sha256) {
    if (state == State.PENDING) {
      return length == -1L && modified == -1L && "".equals(sha256);
    }
    return length >= 0L && modified >= 0L && isSha256(sha256);
  }

  private static void writeMetadata(Path metadataPath, Metadata metadata,
                                    AtomicMoveOperation atomicMoveOperation)
      throws IOException {
    BasicFileAttributes existing = attributesIfPresent(metadataPath);
    rejectSymbolicLink(metadataPath, existing);
    if (existing != null && !existing.isRegularFile()) {
      throw new IOException("Mapterhorn sidecar is not a regular file: " + metadataPath);
    }

    Properties properties = metadata.toProperties();
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    properties.store(encoded, "Mapterhorn output provenance");
    Path parent = metadataPath.getParent() == null ? Path.of(".") : metadataPath.getParent();
    Path temporary = Files.createTempFile(parent,
      metadataPath.getFileName() + ".tmp-", null);
    try {
      try (OutputStream out = Channels.newOutputStream(Files.newByteChannel(temporary,
          StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
          LinkOption.NOFOLLOW_LINKS))) {
        out.write(encoded.toByteArray());
      }
      atomicMoveOperation.move(temporary, metadataPath);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static String sha256(Path path) throws IOException {
    MessageDigest digest = sha256Digest();
    try (InputStream in = Channels.newInputStream(Files.newByteChannel(
        path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return hex(digest.digest());
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is required by the Java runtime", e);
    }
  }

  private static boolean isSha256(String value) {
    if (value == null || value.length() != 64) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  private static Path metadataPath(Path outputPath) {
    Path fileName = outputPath.getFileName();
    if (fileName == null) {
      throw new IllegalArgumentException("output file name is required: " + outputPath);
    }
    return outputPath.resolveSibling(fileName + SUFFIX);
  }

  private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
    try {
      return Files.readAttributes(path, BasicFileAttributes.class,
        LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      return null;
    }
  }

  private static void rejectSymbolicLink(Path path, BasicFileAttributes attributes)
      throws IOException {
    if (attributes != null && attributes.isSymbolicLink()) {
      throw new IOException("refusing symbolic link Mapterhorn output entry: " + path);
    }
  }

  private static void requireRegularOutput(Path path, BasicFileAttributes attributes)
      throws IOException {
    if (attributes == null) {
      throw new IOException("completed Mapterhorn output does not exist: " + path);
    }
    rejectSymbolicLink(path, attributes);
    if (!attributes.isRegularFile()) {
      throw new IOException("Mapterhorn output is not a regular file: " + path);
    }
  }

  private static void requireArguments(File output, Config config) {
    if (output == null) {
      throw new IllegalArgumentException("output file is required");
    }
    if (config == null) {
      throw new IllegalArgumentException("conversion config is required");
    }
  }

  private static String requireText(String value, String description) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(description + " is required");
    }
    return value;
  }

  private static IOException provenanceMismatch(File output) {
    return new IOException("existing output has no matching Mapterhorn provenance: "
      + output + "; use a single full-cell command to overwrite it or another"
      + " output directory");
  }

  private enum State {
    PENDING,
    COMPLETE;

    static State parse(String value) {
      if ("pending".equals(value)) {
        return PENDING;
      }
      if ("complete".equals(value)) {
        return COMPLETE;
      }
      throw new IllegalArgumentException("unknown metadata state");
    }

    String propertyValue() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  private static final class Metadata {
    final Config config;
    final State state;
    final long length;
    final long modified;
    final String sha256;

    Metadata(Config config, State state, long length, long modified, String sha256) {
      this.config = config;
      this.state = state;
      this.length = length;
      this.modified = modified;
      this.sha256 = sha256;
    }

    static Metadata pending(Config config) {
      return new Metadata(config, State.PENDING, -1L, -1L, "");
    }

    static Metadata complete(Config config, CompletedFile completed) {
      return new Metadata(config, State.COMPLETE, completed.length, completed.modified,
        completed.sha256);
    }

    Properties toProperties() {
      Properties properties = new Properties();
      properties.setProperty("schema", Integer.toString(SCHEMA_VERSION));
      properties.setProperty("converter", Integer.toString(CONVERTER_VERSION));
      properties.setProperty("archiveId", config.archiveId);
      properties.setProperty("zoom", Integer.toString(config.zoom));
      properties.setProperty("rowLength", Integer.toString(config.rowLength));
      properties.setProperty("tileSize", Integer.toString(config.tileSize));
      properties.setProperty("naming", config.naming);
      properties.setProperty("coverage", config.coverage);
      properties.setProperty("lonStart", Integer.toString(config.lonStart));
      properties.setProperty("latStart", Integer.toString(config.latStart));
      properties.setProperty("state", state.propertyValue());
      properties.setProperty("length", Long.toString(length));
      properties.setProperty("modified", Long.toString(modified));
      properties.setProperty("sha256", sha256);
      return properties;
    }
  }

  private static final class Plan {
    final Decision decision;
    final Path metadataPath;
    final Metadata metadata;

    Plan(Decision decision, Path metadataPath, Metadata metadata) {
      this.decision = decision;
      this.metadataPath = metadataPath;
      this.metadata = metadata;
    }

    static Plan skip(Path metadataPath, Metadata refreshed) {
      return new Plan(Decision.SKIP, metadataPath, refreshed);
    }

    static Plan write(Path metadataPath, Metadata pending) {
      return new Plan(Decision.WRITE, metadataPath, pending);
    }
  }

  private static final class Validation {
    final boolean valid;
    final Metadata refreshed;

    Validation(boolean valid, Metadata refreshed) {
      this.valid = valid;
      this.refreshed = refreshed;
    }

    static Validation invalid() {
      return new Validation(false, null);
    }

    static Validation valid(Metadata refreshed) {
      return new Validation(true, refreshed);
    }
  }
}
