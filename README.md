# Compression for Java

[![](https://jitpack.io/v/alesimula/murine-aircompressor.svg)](https://jitpack.io/#alesimula/murine-aircompressor) [![Build Status](https://img.shields.io/badge/build-passing-blue.svg)](https://jitpack.io/#alesimula/murine-aircompressor) [![License](https://img.shields.io/hexpm/l/plug.svg)](https://github.com/alesimula/murine-aircompressor/blob/main/license.txt)

This library provides a set of compression algorithms implemented in pure Java.
The implementations use `sun.misc.Unsafe` to provide fast access to memory and
are typically faster than the JNI wrappers for the native libraries.

This fork of [aircompressor](https://github.com/airlift/aircompressor) 2.x is
**supported on Android** (8.0+ / API 26+): the OpenJDK-only `sun.misc.Unsafe`
members the library relied on are replaced with portable equivalents, and
Android's own heap/native memcpy primitives are used when available (resolved
once, with a pure-Java fallback).

The Android support is a POC, won't quite match the performance of zstd-jni AAR libraries,
but is still way more performant than other integrated algorithms such as gzip;
do not benchmark on debug builds, performance will suffer greatly.

# Installation

**Using Gradle**

Add the JitPack repository to your root `build.gradle` (or `settings.gradle`):

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Then add the dependency:

```gradle
    implementation 'com.github.alesimula:murine-aircompressor:2.0.12'
```

**Using Maven**

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

```xml
<dependency>
    <groupId>com.github.alesimula</groupId>
    <artifactId>murine-aircompressor</artifactId>
    <version>2.0.12</version>
</dependency>
```

# Usage

Each algorithm provides a simple block compression API using the
`io.airlift.compress.Compressor` and `io.airlift.compress.Decompressor`
interfaces. Block compression is the simplest form, which simply compresses
a small block of data provided as a `byte[]`, or more generally a
`java.nio.ByteBuffer`. Some algorithms additionally provide a streaming format
which typically produces a sequence of block compressed chunks.

## byte array API
```java
byte[] data = ...

Compressor compressor = new Lz4Compressor();
byte[] compressed = new byte[compressor.maxCompressedLength(data.length)];
int compressedSize = compressor.compress(data, 0, data.length, compressed, 0, compressed.length);

Decompressor decompressor = new Lz4Decompressor();
byte[] uncompressed = new byte[data.length];
int uncompressedSize = decompressor.decompress(compressed, 0, compressedSize, uncompressed, 0, uncompressed.length);
```

# Archive Formats

Archive formats bundle multiple files into a single stream. They are independent
of compression: wrap the output in one of the streaming compressors (e.g.
`ZstdOutputStream`) to get a compressed archive.

## [Tar](https://en.wikipedia.org/wiki/Tar_(computing))
Tar is the standard utility and file format for data archiving in Unix-like systems.
This implementation writes and reads USTAR (POSIX.1-1988) archives and is deliberately 
modeled on the JDK's `java.util.zip.ZipInputStream` / `ZipOutputStream`, so the call 
sequence is familiar.

The implementation is provided by `TarOutputStream` and `TarInputStream`, with
each member described by a `TarEntry`. Writing follows the zip idiom:
`putNextEntry`, write the data, `closeEntry`, repeat, then `close`; for reading:
call `getNextEntry` until it returns `null`, reading each entry's data in between.

The size of each entry must be known up front and is passed to the `TarEntry` constructor 
(`new TarEntry(name, size)`). Names or sizes too large for the plain USTAR fields are
handled transparently, written as PAX (POSIX.1-2001) extended headers, and read
back from PAX or GNU extension records.

Names with accents or non-Latin characters need no special handling: they are written as
UTF-8, read back as UTF-8, and other tar tools will show them correctly.

Two settings cover unusual cases. `withNameCharset` reads or writes names in a different
charset, and is only needed for archives coming from, or destined for, older tools that
used the system's own encoding. `setUnicodeNames(false)` drops the extra metadata that
records the encoding, for readers too old to understand it.

```java
// only when the archive came from a tool that used the system's own encoding
try (TarInputStream tar = new TarInputStream(in).withNameCharset(Charset.defaultCharset())) {
    ...
}
```

```java
// Writing (optionally wrap `out` in ZstdOutputStream for a compressed .tar.zst)
try (TarOutputStream tar = new TarOutputStream(out)) {
    byte[] data = ...
    tar.putNextEntry(new TarEntry("hello.txt", data.length));
    tar.write(data);
    tar.closeEntry();
}

// Reading (wrap `in` in ZstdInputStream to read a .tar.zst)
try (TarInputStream tar = new TarInputStream(in)) {
    TarEntry entry;
    while ((entry = tar.getNextEntry()) != null) {
        // read entry data from `tar` up to entry.getSize()
    }
}
```

# Algorithms

## [Zstandard (Zstd)](https://facebook.github.io/zstd) **(Recommended)**
Zstandard is the recommended algorithm for most compression. It provides
superior compression and performance at all levels compared to zlib. Zstandard is
an excellent choice for most use cases, especially storage and bandwidth constrained
network transfer.

The implementation is provided by the `ZstdCompressor` and `ZstdDecompressor`
classes. The Zstandard streaming format is supported by `ZstdInputStream` and
`ZstdOutputStream`.

The compression level can be selected, although it's recommended to leave the default level 3
or use 2 for faster compression.

Note that the Java implementation only includes the `FAST` and `DFAST`
strategies: the streaming compressor supports levels 1, 2 (good and fast), 3 (default) 
and 4 (not recommended, nearly identical to 3 but slower); higher 
strategies (`GREEDY` through `BTULTRA`) are not implemented and are rejected
at construction. Decompression is strategy-agnostic and handles frames
produced at any level by any zstd implementation.

Parallel compression can be enabled via the `parallel(workers)` and `parallel(workers, chunkSize)`
methods on the stream, distributing the workload across multiple threads to maximize throughput
on multi-core devices.

The algorithm allows passing WindowSlideMode.HIGH_COMPRESSION (default) for more
compression at the cost of performance, or WindowSlideMode.HIGH_SPEED for a more performant
execution at the cost of compression.

## [LZ4](https://www.lz4.org/)
LZ4 is an extremely fast compression algorithm that provides compression ratios comparable
to Snappy and LZO. LZ4 is an excellent choice for applications that require high-performance
compression and decompression.

The implementation is provided by `Lz4Compressor` and `Lz4Decompressor`.
The acceleration factor (as in `LZ4_compress_fast`, 1–65537) can be selected
through the `Lz4Compressor(int)` constructor; each step trades compression
ratio for speed.

## [Snappy](https://google.github.io/snappy/)
Snappy is not as fast as LZ4, but provides a guarantee on memory usage that makes it a good
choice for extremely resource-limited environments (e.g. embedded systems like a network
switch). If your application is not highly resource constrained, LZ4 is a better choice.

The implementation is provided by `SnappyCompressor` and `SnappyDecompressor`.
The Snappy framed format is supported by `SnappyFramedInputStream` and `SnappyFramedOutputStream`.

## [LZO](https://www.oberhumer.com/opensource/lzo/)
LZO is only provided for compatibility with existing systems that use LZO. We recommend
rewriting LZO data using Zstandard or LZ4.

The Java implementation of LZO is provided by `LzoCompressor` and `LzoDecompressor`.
Due to licensing issues, LZO only has a Java implementation which is based on LZ4.

## Deflate
Deflate is the block compression algorithm used by the `gzip` and `zlib` libraries. Deflate is
provided for compatibility with existing systems that use Deflate. We recommend rewriting
Deflate data using Zstandard which provides superior compression and performance.

Deflate and gzip are available through the Hadoop stream implementations (see below),
backed by the built-in Java libraries which internally use native code.

# Hash Functions

## [XXHash64](https://xxhash.com/)
XXHash64 is an extremely fast non-cryptographic hash function with excellent distribution properties.

The pure Java implementation is provided by `XxHash64JavaHasher`. The `XxHash64Hasher`
interface provides static methods for one-shot hashing and a factory for streaming.

```java
// One-shot hashing
long hash = XxHash64Hasher.hash(data);
long hash = XxHash64Hasher.hash(data, seed);

// Streaming hashing
try (XxHash64Hasher hasher = XxHash64Hasher.create()) {
    hasher.update(chunk1);
    hasher.update(chunk2);
    long hash = hasher.digest();
}
```

# Hadoop Compression

In addition to the raw block encoders, there are implementations of the
Hadoop streams for the above algorithms. In addition, implementations of
gzip and bzip2 are provided so that all standard Hadoop algorithms are available.

The `HadoopStreams` class provides a factory for creating `InputStream` and `OutputStream`
implementations without the need for any Hadoop dependencies. For environments
that have Hadoop dependencies, each algorithm also provides a `CompressionCodec` class.

# Requirements

This library requires a Java 1.8+ virtual machine containing the `sun.misc.Unsafe`
interface running on a little endian platform. Android 8.0+ (API 26+) is supported.

# Users

This library is used in projects such as Trino (https://trino.io), a distributed SQL engine.
