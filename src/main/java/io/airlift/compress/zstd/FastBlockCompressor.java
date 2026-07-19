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

import static io.airlift.compress.UnsafeUtil.UNSAFE;
import static io.airlift.compress.zstd.Constants.SIZE_OF_INT;
import static io.airlift.compress.zstd.Constants.SIZE_OF_LONG;

/**
 * FAST strategy (levels 1-2): one hash table, one probe per position.
 */
class FastBlockCompressor
        implements BlockCompressor
{
    private static final int MIN_MATCH = 3;
    private static final int SEARCH_STRENGTH = 8;
    private static final int REP_MOVE = Constants.REPEATED_OFFSET_COUNT - 1;

    private static final int CURSOR_INPUT = 0;
    private static final int CURSOR_ANCHOR = 1;
    private static final int CURSOR_OFFSET_1 = 2;
    private static final int CURSOR_OFFSET_2 = 3;

    private final long[] cursor = new long[4];

    public int compressBlock(Object inputBase, final long inputAddress, int inputSize, SequenceStore output, BlockCompressionState state, RepeatedOffsets offsets, CompressionParameters parameters)
    {
        int matchSearchLength = Math.max(parameters.getSearchLength(), 4);
        int hashBits = parameters.getHashLog();
        int[] hashTable = state.hashTable;

        // acceleration: targetLength doubles as the "fast" step for negative levels
        int step = Math.max(1, parameters.getTargetLength());

        final long baseAddress = state.getBaseAddress();
        final long windowBaseAddress = baseAddress + state.getWindowBaseOffset();
        final long inputEnd = inputAddress + inputSize;
        final long inputLimit = inputEnd - SIZE_OF_LONG;

        long input = inputAddress;
        long anchor = inputAddress;

        int offset1 = offsets.getOffset0();
        int offset2 = offsets.getOffset1();
        int savedOffset = 0;

        if (input - windowBaseAddress == 0) {
            input++;
        }
        int maxRep = (int) (input - windowBaseAddress);
        if (offset2 > maxRep) {
            savedOffset = offset2;
            offset2 = 0;
        }
        if (offset1 > maxRep) {
            savedOffset = offset1;
            offset1 = 0;
        }

        // loop-invariant hash selection (no per-position switch)
        final boolean intHash = matchSearchLength == 4;
        final long hashPrime;
        final int hashLeftShift;
        switch (matchSearchLength) {
            case 5:
                hashPrime = PRIME_5_BYTES;
                hashLeftShift = Long.SIZE - 40;
                break;
            case 6:
                hashPrime = PRIME_6_BYTES;
                hashLeftShift = Long.SIZE - 48;
                break;
            case 7:
                hashPrime = PRIME_7_BYTES;
                hashLeftShift = Long.SIZE - 56;
                break;
            default:
                hashPrime = PRIME_8_BYTES;
                hashLeftShift = 0;
                break;
        }
        final int hashRightShift = Long.SIZE - hashBits;
        final int intHashRightShift = Integer.SIZE - hashBits;

        long[] cursor = this.cursor;

        while (input < inputLimit) {
            long currentLong = UNSAFE.getLong(inputBase, input);

            int hash = intHash
                    ? ((int) currentLong * PRIME_4_BYTES) >>> intHashRightShift
                    : (int) (((currentLong << hashLeftShift) * hashPrime) >>> hashRightShift);

            long matchAddress = baseAddress + hashTable[hash];
            int current = (int) (input - baseAddress);
            hashTable[hash] = current;

            boolean repcode = offset1 > 0 && UNSAFE.getInt(inputBase, input + 1 - offset1) == (int) (currentLong >>> 8);
            if (!repcode && !(matchAddress > windowBaseAddress && UNSAFE.getInt(inputBase, matchAddress) == (int) currentLong)) {
                input += ((input - anchor) >> SEARCH_STRENGTH) + step;
                continue;
            }

            handleMatch(repcode, inputBase, input, anchor, inputEnd, inputLimit,
                    matchAddress, offset1, offset2, windowBaseAddress, baseAddress, current,
                    hashTable, intHash, hashPrime, hashLeftShift, hashRightShift, intHashRightShift,
                    output, cursor);

            input = cursor[CURSOR_INPUT];
            anchor = cursor[CURSOR_ANCHOR];
            offset1 = (int) cursor[CURSOR_OFFSET_1];
            offset2 = (int) cursor[CURSOR_OFFSET_2];
        }

        offsets.saveOffset0(offset1 != 0 ? offset1 : savedOffset);
        offsets.saveOffset1(offset2 != 0 ? offset2 : savedOffset);

        return (int) (inputEnd - anchor);
    }

    private static void handleMatch(boolean repcode, Object inputBase, long input, long anchor, long inputEnd, long inputLimit,
            long matchAddress, int offset1, int offset2, long windowBaseAddress, long baseAddress, int current,
            int[] hashTable, boolean intHash, long hashPrime, int hashLeftShift, int hashRightShift, int intHashRightShift,
            SequenceStore output, long[] cursor)
    {
        int matchLength;
        int offset;

        if (repcode) {
            matchLength = DoubleFastBlockCompressor.count(inputBase, input + 1 + SIZE_OF_INT, inputEnd, input + 1 + SIZE_OF_INT - offset1) + SIZE_OF_INT;
            input++;
            output.storeSequence(inputBase, anchor, (int) (input - anchor), 0, matchLength - MIN_MATCH);
        }
        else {
            matchLength = DoubleFastBlockCompressor.count(inputBase, input + SIZE_OF_INT, inputEnd, matchAddress + SIZE_OF_INT) + SIZE_OF_INT;
            offset = (int) (input - matchAddress);
            while (input > anchor && matchAddress > windowBaseAddress && UNSAFE.getByte(inputBase, input - 1) == UNSAFE.getByte(inputBase, matchAddress - 1)) {
                input--;
                matchAddress--;
                matchLength++;
            }
            offset2 = offset1;
            offset1 = offset;
            output.storeSequence(inputBase, anchor, (int) (input - anchor), offset + REP_MOVE, matchLength - MIN_MATCH);
        }

        input += matchLength;
        anchor = input;

        if (input <= inputLimit) {
            // fill table at matchStart + 2 and matchEnd - 2
            long fillA = UNSAFE.getLong(inputBase, baseAddress + current + 2);
            hashTable[hashOf(fillA, intHash, hashPrime, hashLeftShift, hashRightShift, intHashRightShift)] = current + 2;

            long fillB = UNSAFE.getLong(inputBase, input - 2);
            hashTable[hashOf(fillB, intHash, hashPrime, hashLeftShift, hashRightShift, intHashRightShift)] = (int) (input - 2 - baseAddress);

            while (input <= inputLimit && offset2 > 0 && UNSAFE.getInt(inputBase, input) == UNSAFE.getInt(inputBase, input - offset2)) {
                int repetitionLength = DoubleFastBlockCompressor.count(inputBase, input + SIZE_OF_INT, inputEnd, input + SIZE_OF_INT - offset2) + SIZE_OF_INT;

                int temp = offset2;
                offset2 = offset1;
                offset1 = temp;

                hashTable[hashOf(UNSAFE.getLong(inputBase, input), intHash, hashPrime, hashLeftShift, hashRightShift, intHashRightShift)] = (int) (input - baseAddress);

                output.storeSequence(inputBase, anchor, 0, 0, repetitionLength - MIN_MATCH);

                input += repetitionLength;
                anchor = input;
            }
        }

        cursor[CURSOR_INPUT] = input;
        cursor[CURSOR_ANCHOR] = anchor;
        cursor[CURSOR_OFFSET_1] = offset1;
        cursor[CURSOR_OFFSET_2] = offset2;
    }

    private static int hashOf(long value, boolean intHash, long prime, int leftShift, int rightShift, int intRightShift)
    {
        return intHash
                ? ((int) value * PRIME_4_BYTES) >>> intRightShift
                : (int) (((value << leftShift) * prime) >>> rightShift);
    }

    private static final int PRIME_4_BYTES = 0x9E3779B1;
    private static final long PRIME_5_BYTES = 0xCF1BBCDCBBL;
    private static final long PRIME_6_BYTES = 0xCF1BBCDCBF9BL;
    private static final long PRIME_7_BYTES = 0xCF1BBCDCBFA563L;
    private static final long PRIME_8_BYTES = 0xCF1BBCDCB7A56463L;
}
