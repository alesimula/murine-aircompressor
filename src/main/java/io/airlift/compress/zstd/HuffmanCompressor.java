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

import sun.misc.Unsafe;

import static io.airlift.compress.UnsafeUtil.SPLIT_LONGS;
import static io.airlift.compress.UnsafeUtil.UNSAFE;
import static io.airlift.compress.zstd.Constants.SIZE_OF_LONG;
import static io.airlift.compress.zstd.Constants.SIZE_OF_SHORT;

class HuffmanCompressor
{
    private static final long ARRAY_LONG_BASE_OFFSET = UNSAFE.arrayBaseOffset(long[].class);

    private HuffmanCompressor()
    {
    }

    public static int compress4streams(Object outputBase, long outputAddress, int outputSize, Object inputBase, long inputAddress, int inputSize, HuffmanCompressionTable table)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        long input = inputAddress;
        long inputLimit = inputAddress + inputSize;
        long output = outputAddress;
        long outputLimit = outputAddress + outputSize;

        int segmentSize = (inputSize + 3) / 4;

        if (outputSize < 6 /* jump table */ + 1 /* first stream */ + 1 /* second stream */ + 1 /* third stream */ + 8 /* 8 bytes minimum needed by the bitstream encoder */) {
            return 0; // minimum space to compress successfully
        }

        if (inputSize <= 6 + 1 + 1 + 1) { // jump table + one byte per stream
            return 0;  // no saving possible: input too small
        }

        output += SIZE_OF_SHORT + SIZE_OF_SHORT + SIZE_OF_SHORT; // jump table

        int compressedSize;

        // first segment
        compressedSize = compressSingleStream(outputBase, output, (int) (outputLimit - output), inputBase, input, segmentSize, table);
        if (compressedSize == 0) {
            return 0;
        }
        unsafe.putShort(outputBase, outputAddress, (short) compressedSize);
        output += compressedSize;
        input += segmentSize;

        // second segment
        compressedSize = compressSingleStream(outputBase, output, (int) (outputLimit - output), inputBase, input, segmentSize, table);
        if (compressedSize == 0) {
            return 0;
        }
        unsafe.putShort(outputBase, outputAddress + SIZE_OF_SHORT, (short) compressedSize);
        output += compressedSize;
        input += segmentSize;

        // third segment
        compressedSize = compressSingleStream(outputBase, output, (int) (outputLimit - output), inputBase, input, segmentSize, table);
        if (compressedSize == 0) {
            return 0;
        }
        unsafe.putShort(outputBase, outputAddress + SIZE_OF_SHORT + SIZE_OF_SHORT, (short) compressedSize);
        output += compressedSize;
        input += segmentSize;

        // fourth segment
        compressedSize = compressSingleStream(outputBase, output, (int) (outputLimit - output), inputBase, input, (int) (inputLimit - input), table);
        if (compressedSize == 0) {
            return 0;
        }
        output += compressedSize;

        return (int) (output - outputAddress);
    }

    public static int compressSingleStream(Object outputBase, long outputAddress, int outputSize, Object inputBase, long inputAddress, int inputSize, HuffmanCompressionTable table)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        if (!split) {
            return compressSingleStream64(outputBase, outputAddress, outputSize, inputBase, inputAddress, inputSize, table);
        }
        if (outputSize < SIZE_OF_LONG) {
            return 0;
        }

        // ARM/ART: Highly optimized, flattened version of SequenceEncoder.encodeSequences.
        // By moving the Huffman tables and bit container into local variables, it eliminates object-field
        // loads and method calls, generating bit-identical output with maximum performance on ARM/ART.
        // ARM/ART: entry = value | numberOfBits << 16 (one read + bounds check per literal)
        final int[] entries = table.entries;
        final long bosLimit = outputAddress + outputSize - SIZE_OF_LONG;
        long container = 0;
        int bitCount = 0;
        long currentAddress = outputAddress;
        long input = inputAddress;

        int n = inputSize & ~3; // join to mod 4
        int symbol;
        int entry;
        int flushedBytes;

        switch (inputSize & 3) {
            case 3:
                symbol = unsafe.getByte(inputBase, input + n + 2) & 0xFF;
                entry = entries[symbol];
                container |= ((long) (entry & 0xFFFF)) << bitCount;
                bitCount += entry >>> 16;
                // fall-through
            case 2:
                symbol = unsafe.getByte(inputBase, input + n + 1) & 0xFF;
                entry = entries[symbol];
                container |= ((long) (entry & 0xFFFF)) << bitCount;
                bitCount += entry >>> 16;
                // fall-through
            case 1:
                symbol = unsafe.getByte(inputBase, input + n + 0) & 0xFF;
                entry = entries[symbol];
                container |= ((long) (entry & 0xFFFF)) << bitCount;
                bitCount += entry >>> 16;
                // flush
                flushedBytes = bitCount >>> 3;
                if (split) {
                    unsafe.putInt(outputBase, currentAddress, (int) container);
                    unsafe.putInt(outputBase, currentAddress + 4, (int) (container >>> 32));
                }
                else {
                    unsafe.putLong(outputBase, currentAddress, container);
                }
                currentAddress += flushedBytes;
                if (currentAddress > bosLimit) {
                    currentAddress = bosLimit;
                }
                bitCount &= 7;
                container >>>= flushedBytes * 8;
                // fall-through
            case 0: /* fall-through */
            default:
                break;
        }

        for (; n > 0; n -= 4) {  // note: n & 3 == 0 at this stage
            // ARM/ART: one getInt for the 4 symbols (little endian: byte n-1 is the top byte);
            // getByte is a JNI call on ART builds that don't intrinsify it
            int four = unsafe.getInt(inputBase, input + n - 4);
            symbol = four >>> 24;
            entry = entries[symbol];
            container |= ((long) (entry & 0xFFFF)) << bitCount;
            bitCount += entry >>> 16;
            symbol = (four >>> 16) & 0xFF;
            entry = entries[symbol];
            container |= ((long) (entry & 0xFFFF)) << bitCount;
            bitCount += entry >>> 16;
            symbol = (four >>> 8) & 0xFF;
            entry = entries[symbol];
            container |= ((long) (entry & 0xFFFF)) << bitCount;
            bitCount += entry >>> 16;
            symbol = four & 0xFF;
            entry = entries[symbol];
            container |= ((long) (entry & 0xFFFF)) << bitCount;
            bitCount += entry >>> 16;
            // flush
            flushedBytes = bitCount >>> 3;
            if (split) {
                unsafe.putInt(outputBase, currentAddress, (int) container);
                unsafe.putInt(outputBase, currentAddress + 4, (int) (container >>> 32));
            }
            else {
                unsafe.putLong(outputBase, currentAddress, container);
            }
            currentAddress += flushedBytes;
            if (currentAddress > bosLimit) {
                currentAddress = bosLimit;
            }
            bitCount &= 7;
            container >>>= flushedBytes * 8;
        }

        // BitOutputStream.close(): end mark + final flush
        container |= 1L << bitCount;
        bitCount += 1;
        flushedBytes = bitCount >>> 3;
        if (split) {
            unsafe.putInt(outputBase, currentAddress, (int) container);
            unsafe.putInt(outputBase, currentAddress + 4, (int) (container >>> 32));
        }
        else {
            unsafe.putLong(outputBase, currentAddress, container);
        }
        currentAddress += flushedBytes;
        if (currentAddress > bosLimit) {
            currentAddress = bosLimit;
        }
        bitCount &= 7;

        if (currentAddress >= bosLimit) {
            return 0;
        }
        return (int) ((currentAddress - outputAddress) + (bitCount > 0 ? 1 : 0));
    }

    // ARM/ART: 64-bit (no split longs) single-stream encoder, the way native zstd's
    // HUF_compress1X_usingCTable fills its bit container: each symbol's code sits pre-shifted at
    // the top of a long table entry with its bit count in the low byte, so adding a symbol is one
    // read, one shift and one or (the container fills from the top; the entry's low byte only ever
    // lands below the valid bits). Same bits, same order as compressSingleStream.
    private static int compressSingleStream64(Object outputBase, long outputAddress, int outputSize, Object inputBase, long inputAddress, int inputSize, HuffmanCompressionTable table)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        if (outputSize < SIZE_OF_LONG) {
            return 0;
        }

        final long[] entries = table.fastEntries;
        final long entriesBase = ARRAY_LONG_BASE_OFFSET;
        final long bosLimit = outputAddress + outputSize - SIZE_OF_LONG;
        // valid bits: the top bitCount bits of container, oldest lowest
        long container = 0;
        int bitCount = 0;
        long currentAddress = outputAddress;
        long input = inputAddress;

        int n = inputSize & ~3; // join to mod 4
        long entry;
        int flushedBytes;

        switch (inputSize & 3) {
            case 3:
                entry = unsafe.getLong(entries, entriesBase + ((long) (unsafe.getInt(inputBase, input + n) >>> 16 & 0xFF) << 3));
                container = (container >>> entry) | entry;
                bitCount += (int) entry;
                // fall-through
            case 2:
                entry = unsafe.getLong(entries, entriesBase + ((long) (unsafe.getInt(inputBase, input + n) >>> 8 & 0xFF) << 3));
                container = (container >>> entry) | entry;
                bitCount += (int) entry;
                // fall-through
            case 1:
                entry = unsafe.getLong(entries, entriesBase + ((long) (unsafe.getInt(inputBase, input + n) & 0xFF) << 3));
                container = (container >>> entry) | entry;
                bitCount += (int) entry;
                // flush
                flushedBytes = bitCount >>> 3;
                unsafe.putLong(outputBase, currentAddress, container >>> (64 - bitCount));
                currentAddress += flushedBytes;
                if (currentAddress > bosLimit) {
                    currentAddress = bosLimit;
                }
                bitCount &= 7;
                // fall-through
            case 0: /* fall-through */
            default:
                break;
        }

        for (; n > 0; n -= 4) {  // note: n & 3 == 0 at this stage
            // one getInt for the 4 symbols (little endian: byte n-1 is the top byte)
            int four = unsafe.getInt(inputBase, input + n - 4);
            entry = unsafe.getLong(entries, entriesBase + ((long) (four >>> 24) << 3));
            container = (container >>> entry) | entry;
            bitCount += (int) entry;
            entry = unsafe.getLong(entries, entriesBase + ((long) ((four >>> 16) & 0xFF) << 3));
            container = (container >>> entry) | entry;
            bitCount += (int) entry;
            entry = unsafe.getLong(entries, entriesBase + ((long) ((four >>> 8) & 0xFF) << 3));
            container = (container >>> entry) | entry;
            bitCount += (int) entry;
            entry = unsafe.getLong(entries, entriesBase + ((long) (four & 0xFF) << 3));
            container = (container >>> entry) | entry;
            bitCount += (int) entry;
            // flush
            flushedBytes = bitCount >>> 3;
            unsafe.putLong(outputBase, currentAddress, container >>> (64 - bitCount));
            currentAddress += flushedBytes;
            if (currentAddress > bosLimit) {
                currentAddress = bosLimit;
            }
            bitCount &= 7;
        }

        // BitOutputStream.close(): end mark + final flush
        container = (container >>> 1) | (1L << 63);
        bitCount += 1;
        flushedBytes = bitCount >>> 3;
        unsafe.putLong(outputBase, currentAddress, container >>> (64 - bitCount));
        currentAddress += flushedBytes;
        if (currentAddress > bosLimit) {
            currentAddress = bosLimit;
        }
        bitCount &= 7;

        if (currentAddress >= bosLimit) {
            return 0;
        }
        return (int) ((currentAddress - outputAddress) + (bitCount > 0 ? 1 : 0));
    }
}
