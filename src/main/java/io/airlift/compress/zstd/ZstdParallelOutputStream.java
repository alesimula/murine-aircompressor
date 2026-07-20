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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

/**
 * Implementation behind {@link ZstdOutputStream#parallel(int)} (the design zstdmt uses natively):
 * input is split into fixed-size chunks, each compressed as an INDEPENDENT zstd frame on a worker
 * pool, and the frames are written to the underlying stream in order. Concatenated frames are
 * standard zstd: the output decompresses with {@link ZstdInputStream} (which already handles
 * multi-frame input) and any other zstd implementation's streaming decoder.
 */
class ZstdParallelOutputStream
        extends OutputStream
{
    static final int DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024;

    private final OutputStream outputStream;
    private final int compressionLevel;
    private final WindowSlideMode windowSlideMode;
    private final BufferMode bufferMode;
    private final int chunkSize;
    private final int maxPendingChunks;
    private final ExecutorService executor;
    private final ArrayDeque<PendingChunk> pending = new ArrayDeque<>();
    // chunk byte[]s are recycled once their frame has been written (bounded by the pending limit),
    // so steady-state chunk allocation is zero regardless of stream length
    private final ArrayDeque<byte[]> recycledChunks = new ArrayDeque<>();

    // ByteArrayOutputStream.toByteArray() copies the whole frame again;
    // No thread safety is needed for this stream.
    private static final class FrameBuffer
            extends ByteArrayOutputStream
    {
        FrameBuffer(int size)
        {
            super(size);
        }

        byte[] array()
        {
            return buf;
        }
    }

    private static final class PendingChunk
    {
        final Future<FrameBuffer> compressedFrame;
        final byte[] chunkBuffer;

        PendingChunk(Future<FrameBuffer> compressedFrame, byte[] chunkBuffer)
        {
            this.compressedFrame = compressedFrame;
            this.chunkBuffer = chunkBuffer;
        }
    }

    private byte[] chunk;
    private int chunkPosition;
    private boolean anyChunkSubmitted;
    private boolean closed;

    ZstdParallelOutputStream(OutputStream outputStream, int compressionLevel, WindowSlideMode windowSlideMode, BufferMode bufferMode, int workerThreads, int chunkSize)
            throws IOException
    {
        this.outputStream = requireNonNull(outputStream, "outputStream is null");
        this.windowSlideMode = requireNonNull(windowSlideMode, "windowSlideMode is null");
        this.bufferMode = requireNonNull(bufferMode, "bufferMode is null");
        Util.checkArgument(workerThreads >= 1, "workerThreads must be >= 1");
        Util.checkArgument(chunkSize >= 128 * 1024, "chunkSize must be >= 128 KB");
        // validates the level eagerly, like ZstdOutputStream
        CompressionParameters.checkLevelSupported(CompressionParameters.compute(compressionLevel, -1), compressionLevel);
        this.compressionLevel = compressionLevel;
        this.chunkSize = chunkSize;
        this.maxPendingChunks = workerThreads + 1; // bounded: keeps memory finite and workers busy
        this.executor = Executors.newFixedThreadPool(workerThreads, runnable -> {
            Thread thread = new Thread(runnable, "zstd-parallel-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.chunk = new byte[chunkSize];
    }

    @Override
    public void write(int b)
            throws IOException
    {
        ensureOpen();
        if (chunkPosition == chunkSize) {
            submitChunk();
        }
        chunk[chunkPosition++] = (byte) b;
    }

    @Override
    public void write(byte[] buffer, int offset, int length)
            throws IOException
    {
        ensureOpen();
        while (length > 0) {
            if (chunkPosition == chunkSize) {
                submitChunk();
            }
            int writeSize = min(chunkSize - chunkPosition, length);
            System.arraycopy(buffer, offset, chunk, chunkPosition, writeSize);
            chunkPosition += writeSize;
            offset += writeSize;
            length -= writeSize;
        }
    }

    @Override
    public void flush()
            throws IOException
    {
        // buffered chunk data is not forced out (that would fragment frames); only what has
        // already been compressed and written is flushed
        outputStream.flush();
    }

    @Override
    public void close()
            throws IOException
    {
        if (closed) {
            return;
        }
        try {
            // the final (possibly empty, if the stream is empty) chunk still emits a valid frame
            if (chunkPosition > 0 || !anyChunkSubmitted) {
                submitChunk();
            }
            while (!pending.isEmpty()) {
                writeOldestResult();
            }
            outputStream.close();
        }
        finally {
            closed = true;
            executor.shutdown();
        }
    }

    private void ensureOpen()
            throws IOException
    {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }

    private void submitChunk()
            throws IOException
    {
        if (pending.size() >= maxPendingChunks) {
            writeOldestResult();
        }

        final byte[] data = chunk;
        final int length = chunkPosition;
        final int level = compressionLevel;
        final WindowSlideMode mode = windowSlideMode;
        final BufferMode buffering = bufferMode;
        pending.addLast(new PendingChunk(executor.submit(() -> {
            FrameBuffer compressedFrame = new FrameBuffer(min(length + 64, length / 2 + 1024));
            // one plain ZstdOutputStream per frame, with all the settings of the original;
            // The chunk buffer is recycled.
            try (ZstdOutputStream frame = new ZstdOutputStream(compressedFrame, level, mode, buffering)) {
                frame.write(data, 0, length);
            }
            return compressedFrame;
        }), data));
        anyChunkSubmitted = true;

        byte[] recycled = recycledChunks.pollFirst();
        chunk = recycled != null ? recycled : new byte[chunkSize];
        chunkPosition = 0;
    }

    private void writeOldestResult()
            throws IOException
    {
        PendingChunk oldest = pending.removeFirst();
        try {
            FrameBuffer frame = oldest.compressedFrame.get();
            outputStream.write(frame.array(), 0, frame.size());
            recycledChunks.addLast(oldest.chunkBuffer); // safe: the worker is done with it
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while compressing", e);
        }
        catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException ? (IOException) cause : new IOException("Compression failed", cause);
        }
    }
}
