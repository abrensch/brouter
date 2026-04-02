package btools.mapaccess;

/**
 * Configuration for RD5 file reading strategies.
 * Optimized for cloud storage (S3, GCS, etc.) where network latency is high.
 */
public class Rd5FileConfig {

  /**
   * Enable buffered reading strategy optimized for cloud storage.
   * Default: false (use standard RandomAccessFile)
   *
   * Set via system property: -Drd5.useBufferedReader=true
   */
  public static final boolean USE_BUFFERED_READER =
      Boolean.getBoolean("rd5.useBufferedReader");

  /**
   * Buffer size for buffered reading strategy (in bytes).
   * Default: 1 MB (good balance for cloud storage)
   *
   * Larger buffers reduce number of network round-trips but use more memory.
   * Set via system property: -Drd5.bufferSize=1048576
   */
  public static final int BUFFER_SIZE =
      Integer.getInteger("rd5.bufferSize", 1024 * 1024); // 1 MB default
}