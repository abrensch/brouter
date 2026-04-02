# RD5 File Reader Options

BRouter supports two different strategies for reading RD5 segment files, selectable at runtime via system properties.

## Standard Reader (Default)

Uses Java's `RandomAccessFile` directly for all file operations.

**Best for:**
- Local fast disk (SSD, NVMe)
- Small files or infrequent access

**Usage:** No configuration needed (default behavior)

## Buffered Reader

Uses a large in-memory buffer (1MB default) to reduce the number of I/O operations.

**Best for:**
- Cloud storage (S3, GCS, Azure Blob)
- Network file systems
- High-latency storage backends

**Usage:**
```bash
-Drd5.useBufferedReader=true
```

**Optional buffer size configuration:**
```bash
-Drd5.bufferSize=1048576  # 1 MB (default)
-Drd5.bufferSize=2097152  # 2 MB
-Drd5.bufferSize=4194304  # 4 MB
```

## Configuration Examples

### Standard Mode (Default)
```bash
java -jar brouter-server.jar <segmentdir> <profiledir> <customprofiledir> <port> <maxthreads>
```

### Buffered Mode for Cloud Storage
```bash
java -Drd5.useBufferedReader=true \
     -jar brouter-server.jar <segmentdir> <profiledir> <customprofiledir> <port> <maxthreads>
```

### Buffered Mode with Custom Buffer Size
```bash
java -Drd5.useBufferedReader=true \
     -Drd5.bufferSize=2097152 \
     -jar brouter-server.jar <segmentdir> <profiledir> <customprofiledir> <port> <maxthreads>
```

### Docker Example
```bash
docker run --rm \
  -v ./segments4:/segments4 \
  -p 17777:17777 \
  -e JAVA_OPTS="-Drd5.useBufferedReader=true -Drd5.bufferSize=1048576" \
  --name brouter \
  brouter
```

## How It Works

### Standard Reader
- Each read operation directly accesses the file
- Good for fast local storage where I/O latency is low
- Lower memory usage

### Buffered Reader
- Reads large chunks (1MB) into memory buffer
- Subsequent reads served from buffer if data is available
- Reduces number of I/O operations by ~90% for typical routing
- Higher memory usage but much better for high-latency storage

## Choosing the Right Strategy

| Storage Type | Recommended | Buffer Size |
|--------------|-------------|-------------|
| Local SSD/NVMe | Standard | N/A |
| Local HDD | Standard | N/A |
| NAS/SMB/NFS | Buffered | 1-2 MB |
| Cloud (S3/GCS) | Buffered | 1-4 MB |

## Implementation Details

The implementation uses an interface-based design that allows switching between strategies:

- **`Rd5RandomAccessFile`** - Interface for file access
- **`StandardRd5RandomAccessFile`** - Standard implementation
- **`BufferedRandomAccessFile`** - Buffered implementation with configurable buffer

Both implementations provide the same interface and are fully backward compatible.

## Notes

- The selection is made once at startup based on system properties
- All RD5 files use the same strategy for a given process
- Buffer size should not exceed available JVM heap memory divided by number of open files
- Larger buffers are not always better - test with your specific setup