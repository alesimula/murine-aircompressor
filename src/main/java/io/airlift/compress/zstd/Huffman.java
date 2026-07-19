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

import static io.airlift.compress.UnsafeUtil.UNSAFE;
import static io.airlift.compress.zstd.BitInputStream.isEndOfStream;
import static io.airlift.compress.zstd.BitInputStream.peekBitsFast;
import static io.airlift.compress.zstd.Constants.SIZE_OF_INT;
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
    private final byte[] symbols = new byte[1 << MAX_TABLE_LOG];
    private final byte[] numbersOfBits = new byte[1 << MAX_TABLE_LOG];

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

            byte symbol = (byte) n;
            byte numberOfBits = (byte) (tableLog + 1 - weight);
            for (int i = ranks[weight]; i < ranks[weight] + length; i++) {
                symbols[i] = symbol;
                numbersOfBits[i] = numberOfBits;
            }
            ranks[weight] += length;
        }

        verify(ranks[1] >= 2 && (ranks[1] & 1) == 0, input, "Input is corrupted");

        return inputSize + 1;
    }

    public void decodeSingleStream(final Object inputBase, final long inputAddress, final long inputLimit, final Object outputBase, final long outputAddress, final long outputLimit)
    {
        long[] scratch = new long[2]; // one per call; refills below are allocation-free (see BitInputStream)
        int bitsConsumed = BitInputStream.initializeBits(inputBase, inputAddress, inputLimit, scratch);
        long bits = scratch[0];
        long currentAddress = scratch[1];

        int tableLog = this.tableLog;
        byte[] numbersOfBits = this.numbersOfBits;
        byte[] symbols = this.symbols;

        // 4 symbols at a time
        long output = outputAddress;
        long fastOutputLimit = outputLimit - 4;
        while (output < fastOutputLimit) {
            int loaded = BitInputStream.loadBits(inputBase, inputAddress, currentAddress, bits, bitsConsumed, scratch);
            bits = scratch[0];
            currentAddress = scratch[1];
            bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
            if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                break;
            }

            {
                int index = (int) peekBitsFast(bitsConsumed, bits, tableLog);
                UNSAFE.putByte(outputBase, output, symbols[index]);
                bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(bitsConsumed, bits, tableLog);
                UNSAFE.putByte(outputBase, output + 1, symbols[index]);
                bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(bitsConsumed, bits, tableLog);
                UNSAFE.putByte(outputBase, output + 2, symbols[index]);
                bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(bitsConsumed, bits, tableLog);
                UNSAFE.putByte(outputBase, output + 3, symbols[index]);
                bitsConsumed += numbersOfBits[index];
            }
            output += SIZE_OF_INT;
        }

        decodeTail(inputBase, inputAddress, currentAddress, bitsConsumed, bits, outputBase, output, outputLimit);
    }

    public void decode4Streams(final Object inputBase, final long inputAddress, final long inputLimit, final Object outputBase, final long outputAddress, final long outputLimit)
    {
        verify(inputLimit - inputAddress >= 10, inputAddress, "Input is corrupted"); // jump table + 1 byte per stream

        long start1 = inputAddress + 3 * SIZE_OF_SHORT; // for the shorts we read below
        long start2 = start1 + (UNSAFE.getShort(inputBase, inputAddress) & 0xFFFF);
        long start3 = start2 + (UNSAFE.getShort(inputBase, inputAddress + 2) & 0xFFFF);
        long start4 = start3 + (UNSAFE.getShort(inputBase, inputAddress + 4) & 0xFFFF);

        verify(start2 < start3 && start3 < start4 && start4 < inputLimit, inputAddress, "Input is corrupted");

        long[] scratch = new long[2]; // one per call; refills below are allocation-free (see BitInputStream)
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

        // decodeSymbol() is inlined at every call site below: it was one call per decoded byte.
        long fastOutputLimit = outputLimit - 7;
        int tableLog = this.tableLog;
        byte[] numbersOfBits = this.numbersOfBits;
        byte[] symbols = this.symbols;

        while (output4 < fastOutputLimit) {
            {
                int index = (int) peekBitsFast(stream1bitsConsumed, stream1bits, tableLog);
                UNSAFE.putByte(outputBase, output1, symbols[index]);
                stream1bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream2bitsConsumed, stream2bits, tableLog);
                UNSAFE.putByte(outputBase, output2, symbols[index]);
                stream2bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream3bitsConsumed, stream3bits, tableLog);
                UNSAFE.putByte(outputBase, output3, symbols[index]);
                stream3bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream4bitsConsumed, stream4bits, tableLog);
                UNSAFE.putByte(outputBase, output4, symbols[index]);
                stream4bitsConsumed += numbersOfBits[index];
            }

            {
                int index = (int) peekBitsFast(stream1bitsConsumed, stream1bits, tableLog);
                UNSAFE.putByte(outputBase, output1 + 1, symbols[index]);
                stream1bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream2bitsConsumed, stream2bits, tableLog);
                UNSAFE.putByte(outputBase, output2 + 1, symbols[index]);
                stream2bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream3bitsConsumed, stream3bits, tableLog);
                UNSAFE.putByte(outputBase, output3 + 1, symbols[index]);
                stream3bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream4bitsConsumed, stream4bits, tableLog);
                UNSAFE.putByte(outputBase, output4 + 1, symbols[index]);
                stream4bitsConsumed += numbersOfBits[index];
            }

            {
                int index = (int) peekBitsFast(stream1bitsConsumed, stream1bits, tableLog);
                UNSAFE.putByte(outputBase, output1 + 2, symbols[index]);
                stream1bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream2bitsConsumed, stream2bits, tableLog);
                UNSAFE.putByte(outputBase, output2 + 2, symbols[index]);
                stream2bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream3bitsConsumed, stream3bits, tableLog);
                UNSAFE.putByte(outputBase, output3 + 2, symbols[index]);
                stream3bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream4bitsConsumed, stream4bits, tableLog);
                UNSAFE.putByte(outputBase, output4 + 2, symbols[index]);
                stream4bitsConsumed += numbersOfBits[index];
            }

            {
                int index = (int) peekBitsFast(stream1bitsConsumed, stream1bits, tableLog);
                UNSAFE.putByte(outputBase, output1 + 3, symbols[index]);
                stream1bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream2bitsConsumed, stream2bits, tableLog);
                UNSAFE.putByte(outputBase, output2 + 3, symbols[index]);
                stream2bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream3bitsConsumed, stream3bits, tableLog);
                UNSAFE.putByte(outputBase, output3 + 3, symbols[index]);
                stream3bitsConsumed += numbersOfBits[index];
            }
            {
                int index = (int) peekBitsFast(stream4bitsConsumed, stream4bits, tableLog);
                UNSAFE.putByte(outputBase, output4 + 3, symbols[index]);
                stream4bitsConsumed += numbersOfBits[index];
            }

            output1 += SIZE_OF_INT;
            output2 += SIZE_OF_INT;
            output3 += SIZE_OF_INT;
            output4 += SIZE_OF_INT;

            int loaded = BitInputStream.loadBits(inputBase, start1, stream1currentAddress, stream1bits, stream1bitsConsumed, scratch);
            stream1bits = scratch[0];
            stream1currentAddress = scratch[1];
            stream1bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
            if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                break;
            }

            loaded = BitInputStream.loadBits(inputBase, start2, stream2currentAddress, stream2bits, stream2bitsConsumed, scratch);
            stream2bits = scratch[0];
            stream2currentAddress = scratch[1];
            stream2bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
            if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                break;
            }

            loaded = BitInputStream.loadBits(inputBase, start3, stream3currentAddress, stream3bits, stream3bitsConsumed, scratch);
            stream3bits = scratch[0];
            stream3currentAddress = scratch[1];
            stream3bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
            if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                break;
            }

            loaded = BitInputStream.loadBits(inputBase, start4, stream4currentAddress, stream4bits, stream4bitsConsumed, scratch);
            stream4bits = scratch[0];
            stream4currentAddress = scratch[1];
            stream4bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
            if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                break;
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
        byte[] numbersOfBits = this.numbersOfBits;
        byte[] symbols = this.symbols;

        // closer to the end
        while (outputAddress < outputLimit) {
            int loaded = BitInputStream.loadBits(inputBase, startAddress, currentAddress, bits, bitsConsumed, scratch);
            bits = scratch[0];
            currentAddress = scratch[1];
            bitsConsumed = loaded & BitInputStream.LOAD_BITS_CONSUMED_MASK;
            if ((loaded & BitInputStream.LOAD_DONE) != 0) {
                break;
            }

            {
                int index = (int) peekBitsFast(bitsConsumed, bits, tableLog);
                UNSAFE.putByte(outputBase, outputAddress++, symbols[index]);
                bitsConsumed += numbersOfBits[index];
            }
        }

        // not more data in bit stream, so no need to reload
        while (outputAddress < outputLimit) {
            {
                int index = (int) peekBitsFast(bitsConsumed, bits, tableLog);
                UNSAFE.putByte(outputBase, outputAddress++, symbols[index]);
                bitsConsumed += numbersOfBits[index];
            }
        }

        verify(isEndOfStream(startAddress, currentAddress, bitsConsumed), startAddress, "Bit stream is not fully consumed");
    }

    // NOW REPLACED WITH INLINED CODE
    //private static int decodeSymbol(Object outputBase, long outputAddress, long bitContainer, int bitsConsumed, int tableLog, byte[] numbersOfBits, byte[] symbols)
    //{
    //    int value = (int) peekBitsFast(bitsConsumed, bitContainer, tableLog);
    //    UNSAFE.putByte(outputBase, outputAddress, symbols[value]);
    //    return bitsConsumed + numbersOfBits[value];
    //}
}
