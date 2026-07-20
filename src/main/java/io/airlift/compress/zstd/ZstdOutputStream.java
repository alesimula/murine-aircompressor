/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.compress.zstd;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import static io.airlift.compress.UnsafeUtil.ARRAY_BYTE_BASE_OFFSET;
import static io.airlift.compress.zstd.CompressionParameters.DEFAULT_COMPRESSION_LEVEL;
import static io.airlift.compress.zstd.Constants.SIZE_OF_BLOCK_HEADER;
import static io.airlift.compress.zstd.Constants.SIZE_OF_LONG;
import static io.airlift.compress.zstd.Util.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

/**
 * Zstd compressing stream. See {@link BufferMode} for the two buffering strategies; both honour
 * {@link WindowSlideMode} and produce standard zstd frames.
 */
public class ZstdOutputStream
        extends OutputStream
{
    // Ring mode: windows of history kept in the ring before a slide is needed. 2 bounds the
    // footprint to ~2.1 MB at level 3 (vs ~4.2 MB for the sliding buffer) while amortizing the
    // slide to exactly one window-sized arraycopy every 8 blocks - a fixed, evenly spread cost,
    // chosen for run-to-run consistency on cache-constrained devices.
    private static final int RING_WINDOW_MULTIPLIER = 2;

    private final OutputStream outputStream;
    private final CompressionContext context;
    private final WindowSlideMode windowSlideMode;
    private final BufferMode bufferMode; // Only here to pass to parallel constructor
    private final int compressionLevel; // Only here to pass to parallel constructor
    private final boolean ringMode;
    private final byte[] compressed;
    private final int windowSize;
    private final int blockSize;

    private XxHash64 partialHash;
    private boolean closed;

    // SLIDING_WINDOW mode state (original implementation, unchanged)
    private final int maxBufferSize;
    private byte[] uncompressed = new byte[0];
    // start of unprocessed data in uncompressed buffer
    private int uncompressedOffset;
    // end of unprocessed data in uncompressed buffer
    private int uncompressedPosition;

    // RING_BUFFER mode state
    // grows on demand up to ringCapacity: a 100 KB stream should not allocate and zero 2.2 MB
    private byte[] ring;
    private final int ringCapacity;
    private boolean ringHeaderWritten;
    private int ringPosition;
    private int ringBlockBegin;
    private int ringBlockEnd;

    public ZstdOutputStream(OutputStream outputStream)
            throws IOException
    {
        this(outputStream, DEFAULT_COMPRESSION_LEVEL);
    }

    // Murine: expose the compression level (0-22, default 3). Upstream v3 only exposes
    // this on the native compressor; the Java engine has always supported it internally.
    public ZstdOutputStream(OutputStream outputStream, int compressionLevel)
            throws IOException
    {
        this(outputStream, compressionLevel, WindowSlideMode.HIGH_COMPRESSION);
    }

    public ZstdOutputStream(OutputStream outputStream, WindowSlideMode windowSlideMode)
            throws IOException
    {
        this(outputStream, DEFAULT_COMPRESSION_LEVEL, windowSlideMode);
    }

    public ZstdOutputStream(OutputStream outputStream, BufferMode bufferMode)
            throws IOException
    {
        this(outputStream, DEFAULT_COMPRESSION_LEVEL, WindowSlideMode.HIGH_COMPRESSION, bufferMode);
    }

    public ZstdOutputStream(OutputStream outputStream, int compressionLevel, BufferMode bufferMode)
            throws IOException
    {
        this(outputStream, compressionLevel, WindowSlideMode.HIGH_COMPRESSION, bufferMode);
    }

    public ZstdOutputStream(OutputStream outputStream, int compressionLevel, WindowSlideMode windowSlideMode)
            throws IOException
    {
        this(outputStream, compressionLevel, windowSlideMode, BufferMode.RING_BUFFER);
    }

    /**
     * Creates a compressing stream with zstd
     * @param outputStream the wrapped output stream
     * @param compressionLevel compression level (0-22, default 3)
     * @param windowSlideMode see {@link WindowSlideMode}
     * @param bufferMode see {@link BufferMode}; {@link BufferMode#RING_BUFFER} is the default
     */
    public ZstdOutputStream(OutputStream outputStream, int compressionLevel, WindowSlideMode windowSlideMode, BufferMode bufferMode)
            throws IOException
    {
        this.outputStream = requireNonNull(outputStream, "outputStream is null");
        this.windowSlideMode = requireNonNull(windowSlideMode, "windowSlideMode is null");
        this.compressionLevel = compressionLevel;
        this.bufferMode = requireNonNull(bufferMode, "bufferMode is null");
        this.ringMode = bufferMode == BufferMode.RING_BUFFER;
        CompressionParameters parameters = CompressionParameters.compute(compressionLevel, -1);
        CompressionParameters.checkLevelSupported(parameters, compressionLevel);
        this.context = new CompressionContext(parameters, ARRAY_BYTE_BASE_OFFSET, Integer.MAX_VALUE);
        this.windowSize = parameters.getWindowSize();
        this.blockSize = parameters.getBlockSize();

        // create output buffer large enough for a single block
        int bufferSize = blockSize + SIZE_OF_BLOCK_HEADER;
        // todo is the "+ (bufferSize >>> 8)" required here?
        // add extra long to give code more leeway
        this.compressed = new byte[bufferSize + (bufferSize >>> 8) + SIZE_OF_LONG];

        if (ringMode) {
            // everything allocated once, up front: no growth reallocations, no per-write work
            this.maxBufferSize = 0;
            this.ringCapacity = windowSize * RING_WINDOW_MULTIPLIER + blockSize;
            // Sized on the first write (see sizeRing): a 100 KB stream must not allocate and
            // zero 2.2 MB. Slides still occur at ringCapacity, so output is unaffected.
            this.ring = null;
            this.ringBlockEnd = blockSize;
        }
        else {
            this.maxBufferSize = windowSize * 4;
            this.ring = null;
            this.ringCapacity = 0;
        }
    }

    /**
     * Returns a stream that compresses with this stream's settings (level, window-slide mode)
     * across {@code workerThreads} parallel workers, writing independent zstd frames in order to
     * the same underlying stream.
     * May ever so slightly affect compression ratio, this is mostly negligible, but can be
     * mitigated by passing a bigger chunk with the {@link #parallel(int, int)} method.
     *
     * <p>Must be called before anything is written. This instance is consumed by the call
     * (further writes throw, {@code close()} becomes a no-op) - use only the returned stream.
     */
    public OutputStream parallel(int workerThreads)
            throws IOException
    {
        return parallel(workerThreads, ZstdParallelOutputStream.DEFAULT_CHUNK_SIZE);
    }

    /**
     * @param chunkSize bytes per independent frame; larger chunks = marginally better ratio,
     *                  more memory in flight
     * @see #parallel(int)
     */
    public OutputStream parallel(int workerThreads, int chunkSize)
            throws IOException
    {
        checkState(!ringHeaderWritten && ringPosition == 0 && partialHash == null && uncompressedPosition == 0,
                "parallel() must be called before writing any data");
        checkState(!closed, "Stream is closed");
        if (workerThreads <= 1) {
            return this;
        }
        closed = true; // consume this instance; the returned stream owns the underlying output
        return new ZstdParallelOutputStream(outputStream, compressionLevel, windowSlideMode, bufferMode, workerThreads, chunkSize);
    }

    @Override
    public void write(int b)
            throws IOException
    {
        if (closed) {
            throw new IOException("Stream is closed");
        }

        if (ringMode) {
            if (ringPosition == ringBlockEnd) {
                completeRingBlock();
            }
            ring[ringPosition++] = (byte) b;
            return;
        }

        growBufferIfNecessary(1);

        uncompressed[uncompressedPosition++] = (byte) b;

        compressIfNecessary();
    }

    @Override
    public void write(byte[] buffer)
            throws IOException
    {
        write(buffer, 0, buffer.length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length)
            throws IOException
    {
        if (closed) {
            throw new IOException("Stream is closed");
        }

        if (ringMode) {
            // ARM/ART hot path: a plain bounded copy loop with all state in locals; everything
            // that happens once per block lives in the outlined completeRingBlock() (hot/cold
            // split - ART compiles a method as one register-allocation unit)
            byte[] ring = this.ring;
            if (ring == null) {
                ring = this.ring = new byte[sizeRing(length)];
            }
            int position = this.ringPosition;
            int blockEnd = this.ringBlockEnd;
            while (length > 0) {
                int space = blockEnd - position;
                if (space == 0) {
                    this.ringPosition = position;
                    completeRingBlock();
                    ring = this.ring; // completeRingBlock may have grown it
                    position = this.ringPosition;
                    blockEnd = this.ringBlockEnd;
                    space = blockEnd - position;
                }
                int writeSize = min(space, length);
                System.arraycopy(buffer, offset, ring, position, writeSize);
                position += writeSize;
                offset += writeSize;
                length -= writeSize;
            }
            this.ringPosition = position;
            return;
        }

        growBufferIfNecessary(length);

        while (length > 0) {
            int writeSize = min(length, uncompressed.length - uncompressedPosition);
            System.arraycopy(buffer, offset, uncompressed, uncompressedPosition, writeSize);

            uncompressedPosition += writeSize;
            length -= writeSize;
            offset += writeSize;

            compressIfNecessary();
        }
    }

    // visible for Hadoop stream
    void finishWithoutClosingSource()
            throws IOException
    {
        if (!closed) {
            if (ringMode) {
                finishRing();
            }
            else {
                writeChunk(true);
            }
            closed = true;
        }
    }

    @Override
    public void close()
            throws IOException
    {
        if (!closed) {
            if (ringMode) {
                finishRing();
            }
            else {
                writeChunk(true);
            }

            closed = true;
            outputStream.close();
        }
    }

    // ------------------------------------------------------------------------------------------
    // RING_BUFFER mode. Data is compressed in place from a fixed ring; when the ring wraps, the
    // last window is kept as match history (arraycopy to the front) and the compression state is
    // re-anchored through CompressionContext.slideWindow honouring WindowSlideMode.
    // ------------------------------------------------------------------------------------------

    // cold path, runs once per completed block
    /**
     * Ring size for a stream whose first write is {@code firstWrite} bytes: enough for that write
     * plus one block, rounded to a whole block, capped at the full ring. Streams that fit stay
     * small; larger ones take a single growth to capacity.
     */
    private int sizeRing(int firstWrite)
    {
        long wanted = (long) firstWrite + blockSize;
        long rounded = ((wanted + blockSize - 1) / blockSize) * blockSize;
        return (int) Math.max(blockSize, Math.min(ringCapacity, rounded));
    }

    private void completeRingBlock()
            throws IOException
    {
        compressRingBlock(false);

        // advance to the next block slot (only full blocks reach here)
        int blockBegin = ringBlockBegin + blockSize;
        int blockEnd = ringBlockEnd + blockSize;
        if (blockEnd > ring.length && ring.length < ringCapacity) {
            // Not full yet: grow instead of sliding, so slides still land exactly where a
            // pre-allocated ring would put them and the output is unchanged. Go straight to
            // full capacity - doubling would copy the buffer repeatedly on mid-sized streams.
            ring = Arrays.copyOf(ring, ringCapacity);
        }

        if (blockEnd > ring.length) {
            int slide = blockBegin - windowSize;
            if (slide > 0) {
                context.slideWindow(slide, windowSlideMode.rebasesWindow());
                System.arraycopy(ring, slide, ring, 0, windowSize);
                blockBegin = windowSize;
                blockEnd = windowSize + blockSize;
            }
            else {
                blockBegin = 0;
                blockEnd = blockSize;
            }
        }
        ringBlockBegin = blockBegin;
        ringBlockEnd = blockEnd;
        ringPosition = blockBegin;
    }

    private void compressRingBlock(boolean lastBlock)
            throws IOException
    {
        if (!ringHeaderWritten) {
            ringHeaderWritten = true;
            partialHash = new XxHash64();

            int outputAddress = ARRAY_BYTE_BASE_OFFSET;
            outputAddress += ZstdFrameCompressor.writeMagic(compressed, outputAddress, outputAddress + 4);
            outputAddress += ZstdFrameCompressor.writeFrameHeader(compressed, outputAddress, outputAddress + 14, -1, windowSize);
            outputStream.write(compressed, 0, outputAddress - ARRAY_BYTE_BASE_OFFSET);
        }

        int blockContentSize = ringPosition - ringBlockBegin;
        if (blockContentSize > 0) {
            partialHash.update(ring, ringBlockBegin, blockContentSize);
        }

        int compressedSize = ZstdFrameCompressor.writeCompressedBlock(
                ring,
                ARRAY_BYTE_BASE_OFFSET + ringBlockBegin,
                blockContentSize,
                compressed,
                ARRAY_BYTE_BASE_OFFSET,
                compressed.length,
                context,
                lastBlock);
        outputStream.write(compressed, 0, compressedSize);
    }

    private void finishRing()
            throws IOException
    {
        compressRingBlock(true);

        // write checksum
        int hash = (int) partialHash.hash();
        outputStream.write(hash);
        outputStream.write(hash >> 8);
        outputStream.write(hash >> 16);
        outputStream.write(hash >> 24);
    }

    // ------------------------------------------------------------------------------------------
    // SLIDING_WINDOW mode (original implementation, unchanged behaviour)
    // ------------------------------------------------------------------------------------------

    private void growBufferIfNecessary(int length)
    {
        if (uncompressedPosition + length <= uncompressed.length || uncompressed.length >= maxBufferSize) {
            return;
        }

        // assume we will need double the current required space
        int newSize = (uncompressed.length + length) * 2;
        // limit to max buffer size
        newSize = min(newSize, maxBufferSize);
        // allocate at least a minimal buffer to start;
        newSize = max(newSize, blockSize);
        uncompressed = Arrays.copyOf(uncompressed, newSize);
    }

    private void compressIfNecessary()
            throws IOException
    {
        // only flush when the buffer if is max size, full, and the buffer is larger than the window and one additional block
        if (uncompressed.length >= maxBufferSize &&
                uncompressedPosition == uncompressed.length &&
                uncompressed.length - windowSize > blockSize) {
            writeChunk(false);
        }
    }

    private void writeChunk(boolean lastChunk)
            throws IOException
    {
        int chunkSize;
        if (lastChunk) {
            // write all the data
            chunkSize = uncompressedPosition - uncompressedOffset;
        }
        else {
            chunkSize = uncompressedPosition - uncompressedOffset - windowSize - blockSize;
            checkState(chunkSize > blockSize, "Must write at least one full block");
            // only write full blocks
            chunkSize = (chunkSize / blockSize) * blockSize;
        }

        // if first write
        if (partialHash == null) {
            partialHash = new XxHash64();

            // if this is also the last chunk we know the exact size, otherwise, this is traditional streaming
            int inputSize = lastChunk ? chunkSize : -1;

            int outputAddress = ARRAY_BYTE_BASE_OFFSET;
            outputAddress += ZstdFrameCompressor.writeMagic(compressed, outputAddress, outputAddress + 4);
            outputAddress += ZstdFrameCompressor.writeFrameHeader(compressed, outputAddress, outputAddress + 14, inputSize, windowSize);
            outputStream.write(compressed, 0, outputAddress - ARRAY_BYTE_BASE_OFFSET);
        }

        partialHash.update(uncompressed, uncompressedOffset, chunkSize);

        // write one block at a time
        // note this is a do while to ensure that zero length input gets at least one block written
        do {
            int size = min(chunkSize, blockSize);
            int compressedSize = ZstdFrameCompressor.writeCompressedBlock(
                    uncompressed,
                    ARRAY_BYTE_BASE_OFFSET + uncompressedOffset,
                    size,
                    compressed,
                    ARRAY_BYTE_BASE_OFFSET,
                    compressed.length,
                    context,
                    lastChunk && size == chunkSize);
            outputStream.write(compressed, 0, compressedSize);
            uncompressedOffset += size;
            chunkSize -= size;
        }
        while (chunkSize > 0);

        if (lastChunk) {
            // write checksum
            int hash = (int) partialHash.hash();
            outputStream.write(hash);
            outputStream.write(hash >> 8);
            outputStream.write(hash >> 16);
            outputStream.write(hash >> 24);
        }
        else {
            // slide window forward, leaving the entire window and the unprocessed data
            int slideWindowSize = uncompressedOffset - windowSize;
            context.slideWindow(slideWindowSize, windowSlideMode.rebasesWindow());

            System.arraycopy(uncompressed, slideWindowSize, uncompressed, 0, windowSize + (uncompressedPosition - uncompressedOffset));
            uncompressedOffset -= slideWindowSize;
            uncompressedPosition -= slideWindowSize;
        }
    }
}
