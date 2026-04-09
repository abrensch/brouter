package btools.mapaccess;

import java.io.Closeable;
import java.io.IOException;

/**
 * Abstraction for random file access, allowing pluggable implementations.
 * Supports both standard RandomAccessFile and optimized BufferedRandomAccessFile.
 */
public interface AbstractRandomAccessFile extends Closeable {

  void seek(long pos) throws IOException;

  void readFully(byte[] b, int off, int len) throws IOException;

  long length() throws IOException;

  @Override
  void close() throws IOException;
}
