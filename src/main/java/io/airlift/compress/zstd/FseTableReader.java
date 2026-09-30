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

import static io.airlift.compress.UnsafeUtil.UNSAFE;
import static io.airlift.compress.zstd.FiniteStateEntropy.MAX_SYMBOL;
import static io.airlift.compress.zstd.FiniteStateEntropy.MIN_TABLE_LOG;
import static io.airlift.compress.zstd.Util.highestBit;
import static io.airlift.compress.zstd.Util.verify;

class FseTableReader
{
    private final short[] nextSymbol = new short[MAX_SYMBOL + 1];
    private final short[] normalizedCounters = new short[MAX_SYMBOL + 1];
    private final long[] symbolEntries = new long[MAX_SYMBOL + 1];
    private static final long PACKED_BASE_OFFSET = UNSAFE.arrayBaseOffset(long[].class);
    private final byte[] spread = new byte[1 << 9];

    public int readFseTable(FiniteStateEntropy.Table table, Object inputBase, long inputAddress, long inputLimit, int maxSymbol, int maxTableLog)
    {
        return readFseTable(table, null, 0, null, null, inputBase, inputAddress, inputLimit, maxSymbol, maxTableLog);
    }

    // packed != null: sequence decoding table. The states are written straight into the packed layout
    // of ZstdFrameDecompressor (base << 32 | extraBits << 24 | numberOfBits << 16 | newState), no second
    // pass; table.numberOfBits / table.newState are then left alone (only symbol / log2Size are set).
    public int readFseTable(FiniteStateEntropy.Table table, long[] packed, int[] baseTable, int[] extraBitsTable, Object inputBase, long inputAddress, long inputLimit, int maxSymbol, int maxTableLog)
    {
        return readFseTable(table, packed, 0, baseTable, extraBitsTable, inputBase, inputAddress, inputLimit, maxSymbol, maxTableLog);
    }

    // packed tables are written at packed[packedStart...], their newState values offset by packedStart
    public int readFseTable(FiniteStateEntropy.Table table, long[] packed, int packedStart, int[] baseTable, int[] extraBitsTable, Object inputBase, long inputAddress, long inputLimit, int maxSymbol, int maxTableLog)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        // ARM/ART: fields in locals - ART reloads fields around the Unsafe reads below
        short[] normalizedCounters = this.normalizedCounters;
        short[] nextSymbol = this.nextSymbol;
        byte[] symbols = table.symbol;

        // read table headers
        long input = inputAddress;
        verify(inputLimit - inputAddress >= 4, input, "Not enough input bytes");

        int threshold;
        int symbolNumber = 0;
        boolean previousIsZero = false;

        int bitStream = unsafe.getInt(inputBase, input);

        int tableLog = (bitStream & 0xF) + MIN_TABLE_LOG;

        int numberOfBits = tableLog + 1;
        bitStream >>>= 4;
        int bitCount = 4;

        verify(tableLog <= maxTableLog, input, "FSE table size exceeds maximum allowed size");

        int remaining = (1 << tableLog) + 1;
        threshold = 1 << tableLog;

        while (remaining > 1 && symbolNumber <= maxSymbol) {
            if (previousIsZero) {
                int n0 = symbolNumber;
                while ((bitStream & 0xFFFF) == 0xFFFF) {
                    n0 += 24;
                    if (input < inputLimit - 5) {
                        input += 2;
                        bitStream = (unsafe.getInt(inputBase, input) >>> bitCount);
                    }
                    else {
                        // end of bit stream
                        bitStream >>>= 16;
                        bitCount += 16;
                    }
                }
                while ((bitStream & 3) == 3) {
                    n0 += 3;
                    bitStream >>>= 2;
                    bitCount += 2;
                }
                n0 += bitStream & 3;
                bitCount += 2;

                verify(n0 <= maxSymbol, input, "Symbol larger than max value");

                while (symbolNumber < n0) {
                    normalizedCounters[symbolNumber++] = 0;
                }
                if ((input <= inputLimit - 7) || (input + (bitCount >>> 3) <= inputLimit - 4)) {
                    input += bitCount >>> 3;
                    bitCount &= 7;
                    bitStream = unsafe.getInt(inputBase, input) >>> bitCount;
                }
                else {
                    bitStream >>>= 2;
                }
            }

            short max = (short) ((2 * threshold - 1) - remaining);
            short count;

            if ((bitStream & (threshold - 1)) < max) {
                count = (short) (bitStream & (threshold - 1));
                bitCount += numberOfBits - 1;
            }
            else {
                count = (short) (bitStream & (2 * threshold - 1));
                if (count >= threshold) {
                    count -= max;
                }
                bitCount += numberOfBits;
            }
            count--;  // extra accuracy

            remaining -= Math.abs(count);
            normalizedCounters[symbolNumber++] = count;
            previousIsZero = count == 0;
            while (remaining < threshold) {
                numberOfBits--;
                threshold >>>= 1;
            }

            if ((input <= inputLimit - 7) || (input + (bitCount >> 3) <= inputLimit - 4)) {
                input += bitCount >>> 3;
                bitCount &= 7;
            }
            else {
                bitCount -= (int) (8 * (inputLimit - 4 - input));
                input = inputLimit - 4;
            }
            bitStream = unsafe.getInt(inputBase, input) >>> (bitCount & 31);
        }

        verify(remaining == 1 && bitCount <= 32, input, "Input is corrupted");

        maxSymbol = symbolNumber - 1;
        verify(maxSymbol <= MAX_SYMBOL, input, "Max symbol value too large (too many symbols for FSE)");

        input += (bitCount + 7) >> 3;

        // populate decoding table
        int symbolCount = maxSymbol + 1;
        int tableSize = 1 << tableLog;
        int highThreshold = tableSize - 1;

        table.log2Size = tableLog;

        for (byte symbol = 0; symbol < symbolCount; symbol++) {
            if (normalizedCounters[symbol] == -1) {
                symbols[highThreshold--] = symbol;
                nextSymbol[symbol] = 1;
            }
            else {
                nextSymbol[symbol] = normalizedCounters[symbol];
            }
        }

        int position;
        if (highThreshold == tableSize - 1 && tableSize <= spread.length) {
            // no low-probability symbols (native ZSTD_buildFSETable's fast path): lay the symbols down in order,
            // then scatter them with the table step - no inner loop per symbol, no skipped positions
            byte[] spread = this.spread;
            int count = 0;
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                int end = count + normalizedCounters[symbol];
                verify(end <= tableSize, input, "Input is corrupted");
                while (count < end) {
                    spread[count++] = (byte) symbol;
                }
            }
            int step = (tableSize >>> 1) + (tableSize >>> 3) + 3;
            int mask = tableSize - 1;
            position = 0;
            for (int i = 0; i < tableSize; i++) {
                symbols[position] = spread[i];
                position = (position + step) & mask;
            }
        }
        else {
            position = FseCompressionTable.spreadSymbols(normalizedCounters, maxSymbol, tableSize, highThreshold, symbols);
        }

        // position must reach all cells once, otherwise normalizedCounter is incorrect
        verify(position == 0, input, "Input is corrupted");

        if (packed == null) {
            byte[] numbersOfBits = table.numberOfBits;
            int[] newStates = table.newState;
            for (int i = 0; i < tableSize; i++) {
                byte symbol = symbols[i];
                short nextState = nextSymbol[symbol]++;
                numbersOfBits[i] = (byte) (tableLog - highestBit(nextState));
                newStates[i] = (short) ((nextState << numbersOfBits[i]) - tableSize);
            }
        }
        else {
            // per-symbol part of the entries (base value, extra bits) computed once; numberOfLeadingZeros (an
            // intrinsic) instead of the highestBit helper, which ART did not inline: a call per table entry
            long[] symbolEntries = this.symbolEntries;
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                symbolEntries[symbol] = ((long) baseTable[symbol] << 32) | (extraBitsTable[symbol] << 24);
            }
            int stateBitsBase = tableLog - 31; // tableLog - highestBit(x) == stateBitsBase + numberOfLeadingZeros(x)
            int newStateBase = packedStart - tableSize;
            // newState as a byte offset into packed: (index << 3) + the long[] base offset
            long packedBaseOffset = PACKED_BASE_OFFSET;
            for (int i = 0; i < tableSize; i++) {
                int symbol = symbols[i];
                int nextState = nextSymbol[symbol]++;
                int stateBits = stateBitsBase + Integer.numberOfLeadingZeros(nextState);
                packed[packedStart + i] = symbolEntries[symbol]
                        | (stateBits << 16)
                        | ((((nextState << stateBits) + newStateBase) << 3) + packedBaseOffset);
            }
        }

        return (int) (input - inputAddress);
    }

    public static void initializeRleTable(FiniteStateEntropy.Table table, byte value)
    {
        table.log2Size = 0;
        table.symbol[0] = value;
        table.newState[0] = 0;
        table.numberOfBits[0] = 0;
    }
}
