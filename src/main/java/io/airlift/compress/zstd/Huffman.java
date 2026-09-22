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

import java.util.Arrays;

import static io.airlift.compress.UnsafeUtil.SPLIT_LONGS;
import static io.airlift.compress.UnsafeUtil.UNSAFE;
import static io.airlift.compress.zstd.BitInputStream.isEndOfStream;
import static io.airlift.compress.zstd.BitInputStream.peekBitsFast;
import static io.airlift.compress.zstd.Constants.SIZE_OF_INT;
import static io.airlift.compress.zstd.Constants.SIZE_OF_LONG;
import static io.airlift.compress.zstd.Constants.SIZE_OF_SHORT;
import static io.airlift.compress.zstd.Util.isPowerOf2;
import static io.airlift.compress.zstd.Util.verify;

class Huffman
{
    public static final int MAX_SYMBOL = 255;
    public static final int MAX_SYMBOL_COUNT = MAX_SYMBOL + 1;

    public static final int MAX_TABLE_LOG = 12;
    public static final int MIN_TABLE_LOG = 5;
    public static final int MAX_FSE_TABLE_LOG = 6;

    // stats
    private final byte[] weights = new byte[MAX_SYMBOL + 1];
    private final int[] ranks = new int[MAX_TABLE_LOG + 1];

    // table
    private int tableLog = -1;
    // symbol | numberOfBits << 8: one load per decoded symbol instead of two
    private final short[] entries = new short[1 << MAX_TABLE_LOG];

    private final FseTableReader reader = new FseTableReader();
    private final FiniteStateEntropy.Table fseTable = new FiniteStateEntropy.Table(MAX_FSE_TABLE_LOG);

    public boolean isLoaded()
    {
        return tableLog != -1;
    }

    public int readTable(final Object inputBase, final long inputAddress, final int size)
    {
        Arrays.fill(ranks, 0);
        long input = inputAddress;

        // read table header
        verify(size > 0, input, "Not enough input bytes");
        int inputSize = UNSAFE.getByte(inputBase, input++) & 0xFF;

        int outputSize;
        if (inputSize >= 128) {
            outputSize = inputSize - 127;
            inputSize = ((outputSize + 1) / 2);

            verify(inputSize + 1 <= size, input, "Not enough input bytes");
            verify(outputSize <= MAX_SYMBOL + 1, input, "Input is corrupted");

            for (int i = 0; i < outputSize; i += 2) {
                int value = UNSAFE.getByte(inputBase, input + i / 2) & 0xFF;
                weights[i] = (byte) (value >>> 4);
                weights[i + 1] = (byte) (value & 0b1111);
            }
        }
        else {
            verify(inputSize + 1 <= size, input, "Not enough input bytes");

            long inputLimit = input + inputSize;
            input += reader.readFseTable(fseTable, inputBase, input, inputLimit, FiniteStateEntropy.MAX_SYMBOL, MAX_FSE_TABLE_LOG);
            outputSize = FiniteStateEntropy.decompress(fseTable, inputBase, input, inputLimit, weights);
        }

        int totalWeight = 0;
        for (int i = 0; i < outputSize; i++) {
            ranks[weights[i]]++;
            totalWeight += (1 << weights[i]) >> 1;   // TODO same as 1 << (weights[n] - 1)?
        }
        verify(totalWeight != 0, input, "Input is corrupted");

        tableLog = Util.highestBit(totalWeight) + 1;
        verify(tableLog <= MAX_TABLE_LOG, input, "Input is corrupted");

        int total = 1 << tableLog;
        int rest = total - totalWeight;
        verify(isPowerOf2(rest), input, "Input is corrupted");

        int lastWeight = Util.highestBit(rest) + 1;

        weights[outputSize] = (byte) lastWeight;
        ranks[lastWeight]++;

        int numberOfSymbols = outputSize + 1;

        // populate table
        int nextRankStart = 0;
        for (int i = 1; i < tableLog + 1; ++i) {
            int current = nextRankStart;
            nextRankStart += ranks[i] << (i - 1);
            ranks[i] = current;
        }

        for (int n = 0; n < numberOfSymbols; n++) {
            int weight = weights[n];
            int length = (1 << weight) >> 1;  // TODO: 1 << (weight - 1) ??

            short entry = (short) (n | (tableLog + 1 - weight) << 8);
            for (int i = ranks[weight]; i < ranks[weight] + length; i++) {
                entries[i] = entry;
            }
            ranks[weight] += length;
        }

        verify(ranks[1] >= 2 && (ranks[1] & 1) == 0, input, "Input is corrupted");

        return inputSize + 1;
    }

    public void decodeSingleStream(final Object inputBase, final long inputAddress, final long inputLimit, final Object outputBase, final long outputAddress, final long outputLimit)
    {
        final boolean split = SPLIT_LONGS;
        long[] scratch = new long[2]; // one per call; only the cold refill path below uses it
        int bitsConsumed = BitInputStream.initializeBits(inputBase, inputAddress, inputLimit, scratch);
        long bits = scratch[0];
        long currentAddress = scratch[1];

        int tableLog = this.tableLog;
        short[] entries = this.entries;

        // 4 symbols at a time
        long output = outputAddress;
        long fastOutputLimit = outputLimit - 4;
        while (output < fastOutputLimit) {
            // ARM/ART: BitInputStream.loadBits is too big for ART to inline; its common case
            // (>= 8 bytes left) is done here, the rest still goes through the call
            if (currentAddress >= inputAddress + SIZE_OF_LONG) {
                currentAddress -= bitsConsumed >>> 3;
                bits = (split ? ((UNSAFE.getInt(inputBase, currentAddress) & 0xFFFFFFFFL) | ((long) UNSAFE.getInt(inputBase, currentAddress + 4) << 32)) : UNSAFE.getLong(inputBase, currentAddress));
                bitsConsumed &= 0b111;
            }
            else {
                int loaded = BitInputStream.loadBits(inputBase, inputAddress, currentAddress, bits, bitsConsumed, scratch);
                bits = scratch[0];
                currentAddress = scratch[1];
                bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
                if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                    break;
                }
            }

            // ARM/ART: entry = symbol | numberOfBits << 8 (one table load per symbol); the 4
            // symbols are collected in a register and written with one putInt - putByte is a JNI
            // call on ART builds that don't intrinsify it (pre-Android 15 ART module)
            int e0 = entries[(int) peekBitsFast(bitsConsumed, bits, tableLog)];
            bitsConsumed += e0 >>> 8;
            int e1 = entries[(int) peekBitsFast(bitsConsumed, bits, tableLog)];
            bitsConsumed += e1 >>> 8;
            int e2 = entries[(int) peekBitsFast(bitsConsumed, bits, tableLog)];
            bitsConsumed += e2 >>> 8;
            int e3 = entries[(int) peekBitsFast(bitsConsumed, bits, tableLog)];
            bitsConsumed += e3 >>> 8;
            UNSAFE.putInt(outputBase, output, (e0 & 0xFF) | (e1 & 0xFF) << 8 | (e2 & 0xFF) << 16 | e3 << 24);
            output += SIZE_OF_INT;
        }

        decodeTail(inputBase, inputAddress, currentAddress, bitsConsumed, bits, outputBase, output, outputLimit);
    }

    public void decode4Streams(final Object inputBase, final long inputAddress, final long inputLimit, final Object outputBase, final long outputAddress, final long outputLimit)
    {
        final boolean split = SPLIT_LONGS;
        verify(inputLimit - inputAddress >= 10, inputAddress, "Input is corrupted"); // jump table + 1 byte per stream

        long start1 = inputAddress + 3 * SIZE_OF_SHORT; // for the shorts we read below
        long start2 = start1 + (UNSAFE.getShort(inputBase, inputAddress) & 0xFFFF);
        long start3 = start2 + (UNSAFE.getShort(inputBase, inputAddress + 2) & 0xFFFF);
        long start4 = start3 + (UNSAFE.getShort(inputBase, inputAddress + 4) & 0xFFFF);

        verify(start2 < start3 && start3 < start4 && start4 < inputLimit, inputAddress, "Input is corrupted");

        long[] scratch = new long[2]; // one per call; only the cold refill paths below use it
        int stream1bitsConsumed = BitInputStream.initializeBits(inputBase, start1, start2, scratch);
        long stream1bits = scratch[0];
        long stream1currentAddress = scratch[1];

        int stream2bitsConsumed = BitInputStream.initializeBits(inputBase, start2, start3, scratch);
        long stream2bits = scratch[0];
        long stream2currentAddress = scratch[1];

        int stream3bitsConsumed = BitInputStream.initializeBits(inputBase, start3, start4, scratch);
        long stream3bits = scratch[0];
        long stream3currentAddress = scratch[1];

        int stream4bitsConsumed = BitInputStream.initializeBits(inputBase, start4, inputLimit, scratch);
        long stream4bits = scratch[0];
        long stream4currentAddress = scratch[1];

        int segmentSize = (int) ((outputLimit - outputAddress + 3) / 4);

        long outputStart2 = outputAddress + segmentSize;
        long outputStart3 = outputStart2 + segmentSize;
        long outputStart4 = outputStart3 + segmentSize;

        long output1 = outputAddress;
        long output2 = outputStart2;
        long output3 = outputStart3;
        long output4 = outputStart4;

        long fastOutputLimit = outputLimit - 7;
        int tableLog = this.tableLog;
        short[] entries = this.entries;

        while (output4 < fastOutputLimit) {
            // ARM/ART: 4 symbols per stream, each stream's bytes collected in a register and written
            // with one putInt (see decodeSingleStream); entry = symbol | numberOfBits << 8
            int e;
            e = entries[(int) peekBitsFast(stream1bitsConsumed, stream1bits, tableLog)];
            stream1bitsConsumed += e >>> 8;
            int out1 = e & 0xFF;
            e = entries[(int) peekBitsFast(stream2bitsConsumed, stream2bits, tableLog)];
            stream2bitsConsumed += e >>> 8;
            int out2 = e & 0xFF;
            e = entries[(int) peekBitsFast(stream3bitsConsumed, stream3bits, tableLog)];
            stream3bitsConsumed += e >>> 8;
            int out3 = e & 0xFF;
            e = entries[(int) peekBitsFast(stream4bitsConsumed, stream4bits, tableLog)];
            stream4bitsConsumed += e >>> 8;
            int out4 = e & 0xFF;

            e = entries[(int) peekBitsFast(stream1bitsConsumed, stream1bits, tableLog)];
            stream1bitsConsumed += e >>> 8;
            out1 |= (e & 0xFF) << 8;
            e = entries[(int) peekBitsFast(stream2bitsConsumed, stream2bits, tableLog)];
            stream2bitsConsumed += e >>> 8;
            out2 |= (e & 0xFF) << 8;
            e = entries[(int) peekBitsFast(stream3bitsConsumed, stream3bits, tableLog)];
            stream3bitsConsumed += e >>> 8;
            out3 |= (e & 0xFF) << 8;
            e = entries[(int) peekBitsFast(stream4bitsConsumed, stream4bits, tableLog)];
            stream4bitsConsumed += e >>> 8;
            out4 |= (e & 0xFF) << 8;

            e = entries[(int) peekBitsFast(stream1bitsConsumed, stream1bits, tableLog)];
            stream1bitsConsumed += e >>> 8;
            out1 |= (e & 0xFF) << 16;
            e = entries[(int) peekBitsFast(stream2bitsConsumed, stream2bits, tableLog)];
            stream2bitsConsumed += e >>> 8;
            out2 |= (e & 0xFF) << 16;
            e = entries[(int) peekBitsFast(stream3bitsConsumed, stream3bits, tableLog)];
            stream3bitsConsumed += e >>> 8;
            out3 |= (e & 0xFF) << 16;
            e = entries[(int) peekBitsFast(stream4bitsConsumed, stream4bits, tableLog)];
            stream4bitsConsumed += e >>> 8;
            out4 |= (e & 0xFF) << 16;

            e = entries[(int) peekBitsFast(stream1bitsConsumed, stream1bits, tableLog)];
            stream1bitsConsumed += e >>> 8;
            out1 |= e << 24;
            e = entries[(int) peekBitsFast(stream2bitsConsumed, stream2bits, tableLog)];
            stream2bitsConsumed += e >>> 8;
            out2 |= e << 24;
            e = entries[(int) peekBitsFast(stream3bitsConsumed, stream3bits, tableLog)];
            stream3bitsConsumed += e >>> 8;
            out3 |= e << 24;
            e = entries[(int) peekBitsFast(stream4bitsConsumed, stream4bits, tableLog)];
            stream4bitsConsumed += e >>> 8;
            out4 |= e << 24;

            UNSAFE.putInt(outputBase, output1, out1);
            UNSAFE.putInt(outputBase, output2, out2);
            UNSAFE.putInt(outputBase, output3, out3);
            UNSAFE.putInt(outputBase, output4, out4);

            output1 += SIZE_OF_INT;
            output2 += SIZE_OF_INT;
            output3 += SIZE_OF_INT;
            output4 += SIZE_OF_INT;

            // ARM/ART: common case of BitInputStream.loadBits inlined per stream (see decodeSingleStream)
            if (stream1currentAddress >= start1 + SIZE_OF_LONG) {
                stream1currentAddress -= stream1bitsConsumed >>> 3;
                stream1bits = (split ? ((UNSAFE.getInt(inputBase, stream1currentAddress) & 0xFFFFFFFFL) | ((long) UNSAFE.getInt(inputBase, stream1currentAddress + 4) << 32)) : UNSAFE.getLong(inputBase, stream1currentAddress));
                stream1bitsConsumed &= 0b111;
            }
            else {
                int loaded = BitInputStream.loadBits(inputBase, start1, stream1currentAddress, stream1bits, stream1bitsConsumed, scratch);
                stream1bits = scratch[0];
                stream1currentAddress = scratch[1];
                stream1bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
                if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                    break;
                }
            }

            if (stream2currentAddress >= start2 + SIZE_OF_LONG) {
                stream2currentAddress -= stream2bitsConsumed >>> 3;
                stream2bits = (split ? ((UNSAFE.getInt(inputBase, stream2currentAddress) & 0xFFFFFFFFL) | ((long) UNSAFE.getInt(inputBase, stream2currentAddress + 4) << 32)) : UNSAFE.getLong(inputBase, stream2currentAddress));
                stream2bitsConsumed &= 0b111;
            }
            else {
                int loaded = BitInputStream.loadBits(inputBase, start2, stream2currentAddress, stream2bits, stream2bitsConsumed, scratch);
                stream2bits = scratch[0];
                stream2currentAddress = scratch[1];
                stream2bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
                if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                    break;
                }
            }

            if (stream3currentAddress >= start3 + SIZE_OF_LONG) {
                stream3currentAddress -= stream3bitsConsumed >>> 3;
                stream3bits = (split ? ((UNSAFE.getInt(inputBase, stream3currentAddress) & 0xFFFFFFFFL) | ((long) UNSAFE.getInt(inputBase, stream3currentAddress + 4) << 32)) : UNSAFE.getLong(inputBase, stream3currentAddress));
                stream3bitsConsumed &= 0b111;
            }
            else {
                int loaded = BitInputStream.loadBits(inputBase, start3, stream3currentAddress, stream3bits, stream3bitsConsumed, scratch);
                stream3bits = scratch[0];
                stream3currentAddress = scratch[1];
                stream3bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
                if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                    break;
                }
            }

            if (stream4currentAddress >= start4 + SIZE_OF_LONG) {
                stream4currentAddress -= stream4bitsConsumed >>> 3;
                stream4bits = (split ? ((UNSAFE.getInt(inputBase, stream4currentAddress) & 0xFFFFFFFFL) | ((long) UNSAFE.getInt(inputBase, stream4currentAddress + 4) << 32)) : UNSAFE.getLong(inputBase, stream4currentAddress));
                stream4bitsConsumed &= 0b111;
            }
            else {
                int loaded = BitInputStream.loadBits(inputBase, start4, stream4currentAddress, stream4bits, stream4bitsConsumed, scratch);
                stream4bits = scratch[0];
                stream4currentAddress = scratch[1];
                stream4bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
                if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                    break;
                }
            }
        }

        verify(output1 <= outputStart2 && output2 <= outputStart3 && output3 <= outputStart4, inputAddress, "Input is corrupted");

        /// finish streams one by one
        decodeTail(inputBase, start1, stream1currentAddress, stream1bitsConsumed, stream1bits, outputBase, output1, outputStart2);
        decodeTail(inputBase, start2, stream2currentAddress, stream2bitsConsumed, stream2bits, outputBase, output2, outputStart3);
        decodeTail(inputBase, start3, stream3currentAddress, stream3bitsConsumed, stream3bits, outputBase, output3, outputStart4);
        decodeTail(inputBase, start4, stream4currentAddress, stream4bitsConsumed, stream4bits, outputBase, output4, outputLimit);
    }

    private void decodeTail(final Object inputBase, final long startAddress, long currentAddress, int bitsConsumed, long bits, final Object outputBase, long outputAddress, final long outputLimit)
    {
        long[] scratch = new long[2]; // one per call; refills below are allocation-free
        int tableLog = this.tableLog;
        short[] entries = this.entries;

        // closer to the end
        while (outputAddress < outputLimit) {
            int loaded = BitInputStream.loadBits(inputBase, startAddress, currentAddress, bits, bitsConsumed, scratch);
            bits = scratch[0];
            currentAddress = scratch[1];
            bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
            if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                break;
            }

            int e = entries[(int) peekBitsFast(bitsConsumed, bits, tableLog)];
            UNSAFE.putByte(outputBase, outputAddress++, (byte) e);
            bitsConsumed += e >>> 8;
        }

        // not more data in bit stream, so no need to reload
        while (outputAddress < outputLimit) {
            int e = entries[(int) peekBitsFast(bitsConsumed, bits, tableLog)];
            UNSAFE.putByte(outputBase, outputAddress++, (byte) e);
            bitsConsumed += e >>> 8;
        }

        verify(isEndOfStream(startAddress, currentAddress, bitsConsumed), startAddress, "Bit stream is not fully consumed");
    }
}
