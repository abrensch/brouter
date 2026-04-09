package btools.mapaccess;

/**
 * Configuration for buffered file reading strategies.
 */
public class BufferedFileReaderConfig {

  public static final boolean USE_BUFFERED_READER =
      Boolean.getBoolean("useBufferedReader");

  /**
   * Buffer size for buffered reading strategy (in bytes).
   * Default: 1m (1 MB - good balance for cloud storage)
   *
   * Examples:
   *   -DreadBufferSize=512k   (512 KB)
   *   -DreadBufferSize=2m     (2 MB)
   */
  public static final int BUFFER_SIZE = parseBufferSize(System.getProperty("readBufferSize", "1m"));

  private static int parseBufferSize(String value) {
    if (value == null || value.isEmpty()) {
      return 1024 * 1024; // 1 MB default
    }

    value = value.trim();

    char lastChar = value.charAt(value.length() - 1);
    int multiplier = 1;
    String numericPart = value;

    if (Character.isLetter(lastChar)) {
      numericPart = value.substring(0, value.length() - 1).trim();
      switch (Character.toLowerCase(lastChar)) {
        case 'k':
          multiplier = 1024;
          break;
        case 'm':
          multiplier = 1024 * 1024;
          break;
        case 'g':
          multiplier = 1024 * 1024 * 1024;
          break;
        default:
          throw new IllegalArgumentException("Invalid buffer size unit: " + lastChar + ". Use k, m, or g.");
      }
    }

    try {
      long bytes = Long.parseLong(numericPart) * multiplier;
      if (bytes > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("Buffer size too large: " + value + " (max: 2g)");
      }
      if (bytes <= 0) {
        throw new IllegalArgumentException("Buffer size must be positive: " + value);
      }
      return (int) bytes;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid buffer size format: " + value, e);
    }
  }
}
