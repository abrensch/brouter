package btools.mapaccess;

import java.io.Closeable;
import java.io.IOException;

/**
 * Abstraction for random file access, allowing pluggable implementations.
 * Supports both standard RandomAccessFile and optimized BufferedRandomAccessFile.
 */
public interface AbstractRandomAccessFile extends Closeable {

  /**
   * Sets the file-pointer offset from the beginning of the file.
   */
  void seek(long pos) throws IOException;

  /**
   * Reads up to b.length bytes from the file into the byte array.
   */
  void readFully(byte[] b, int off, int len) throws IOException;

  /**
   * Returns the length of this file.
   */
  long length() throws IOException;

  /**
   * Closes this file and releases any system resources.
   */
  @Override
  void close() throws IOException;
}