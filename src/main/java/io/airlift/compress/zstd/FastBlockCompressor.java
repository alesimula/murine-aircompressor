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

import static io.airlift.compress.UnsafeUtil.ARRAY_BYTE_BASE_OFFSET;
import static io.airlift.compress.UnsafeUtil.SPLIT_LONGS;
import static io.airlift.compress.UnsafeUtil.UNSAFE;
import static io.airlift.compress.zstd.Constants.SIZE_OF_INT;
import static io.airlift.compress.zstd.Constants.SIZE_OF_LONG;

/**
 * FAST strategy (levels 1-2): one hash table, one probe per position.
 */
class FastBlockCompressor
        implements BlockCompressor
{
    private static final long ARRAY_INT_BASE_OFFSET = UNSAFE.arrayBaseOffset(int[].class);

    private static final int MIN_MATCH = 3;
    private static final int SEARCH_STRENGTH = 8;
    private static final int REP_MOVE = Constants.REPEATED_OFFSET_COUNT - 1;

    private static final int CURSOR_INPUT = 0;
    private static final int CURSOR_ANCHOR = 1;
    private static final int CURSOR_OFFSET_1 = 2;
    private static final int CURSOR_OFFSET_2 = 3;

    public int compressBlock(Object inputBase, final long inputAddress, int inputSize, SequenceStore output, BlockCompressionState state, RepeatedOffsets offsets, CompressionParameters parameters)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        if (!split) {
            return compressBlock64(inputBase, inputAddress, inputSize, output, state, offsets, parameters);
        }
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

        final long[] cursor = new long[4]; // per call: cannot be global as Strategy is share across threads

        while (input < inputLimit) {
            long currentLong = (split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input));

            int hash = intHash
                    ? ((int) currentLong * PRIME_4_BYTES) >>> intHashRightShift
                    : (int) (((currentLong << hashLeftShift) * hashPrime) >>> hashRightShift);

            long matchAddress = baseAddress + hashTable[hash];
            int current = (int) (input - baseAddress);
            hashTable[hash] = current;

            boolean repcode = offset1 > 0 && unsafe.getInt(inputBase, input + 1 - offset1) == (int) (currentLong >>> 8);
            if (!repcode && !(matchAddress > windowBaseAddress && unsafe.getInt(inputBase, matchAddress) == (int) currentLong)) {
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

    // ARM/ART: 64-bit (no split longs) FAST path. Same algorithm and output as the generic loop above;
    // only the code shape changes, for ART's register allocator:
    // - the positions without a match are scanned by scan(), a small method of its own; everything
    //   done per match (extend, store, follow) is written out here, with no other call (a loop that
    //   also holds the per-position scan, or a call per match with a long argument list, makes ART
    //   spill and reshuffle values at every position or match)
    // - count, extendBackward, hashOf and SequenceStore.storeSequence written out
    // - one hash formula: the 4-byte hash is the long hash of the value shifted left by 32 (the top
    //   32 bits of (v << 32) * P are those of (int) v * (int) P), and (v << L) * P is v * (P << L)
    // - backward extension compares the 8 bytes before input and match even when fewer than 8 are
    //   left and keeps only the available ones (Unsafe.getByte is a JNI call on older ART)
    private static int compressBlock64(Object inputBase, final long inputAddress, int inputSize, SequenceStore output, BlockCompressionState state, RepeatedOffsets offsets, CompressionParameters parameters)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        int matchSearchLength = Math.max(parameters.getSearchLength(), 4);
        int hashBits = parameters.getHashLog();


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

        final long hashPrime;
        final int hashLeftShift;
        switch (matchSearchLength) {
            case 4:
                hashPrime = PRIME_4_BYTES & 0xFFFFFFFFL;
                hashLeftShift = Long.SIZE - 32;
                break;
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

        // (v << L) * P == v * (P << L)
        final long hashMultiplier = hashPrime << hashLeftShift;
        final int hashRightShift = Long.SIZE - hashBits;
        // acceleration: targetLength doubles as the "fast" step for negative levels
        final int step = Math.max(1, parameters.getTargetLength());
        final int[] hashTable = state.hashTable;
        final long hashTableBase = ARRAY_INT_BASE_OFFSET;
        final long literalsBufferBase = ARRAY_BYTE_BASE_OFFSET;
        final int[] seqLiteralLengths = output.literalLengths;
        final int[] seqOffsets = output.offsets;
        final int[] seqMatchLengths = output.matchLengths;
        final byte[] literalsBuffer = output.literalsBuffer;
        int sequenceCount = output.sequenceCount;

        while (true) {
            // positions without a match: scan, a small loop in a method of its own
            long found = scan(unsafe, inputBase, input, anchor, inputLimit, hashTable, hashTableBase, baseAddress, windowBaseAddress, offset1, hashMultiplier, hashRightShift, step);
            if (found < 0) {
                break;
            }
            input = windowBaseAddress + (found >>> 32);
            int matchOffset = (int) found;
            boolean repcode = matchOffset == 0;
            long matchAddress = input - matchOffset;
            int current = (int) (input - baseAddress);

            int offsetCode;
            if (repcode) {
                input++;
                matchAddress = input - offset1;
                offsetCode = 0;
            }
            else {
                offsetCode = (int) (input - matchAddress) + REP_MOVE;
            }

            // ---- begin inlined count ----
            long countInput = input + SIZE_OF_INT;
            long countMatch = matchAddress + SIZE_OF_INT;
            int remaining = (int) (inputEnd - countInput);
            int matchLength = 0;
            countLoop:
            {
                while (matchLength < remaining - (SIZE_OF_LONG - 1)) {
                    long diff = unsafe.getLong(inputBase, countMatch) ^ unsafe.getLong(inputBase, countInput);
                    if (diff != 0) {
                        matchLength += Long.numberOfTrailingZeros(diff) >> 3;
                        break countLoop;
                    }
                    matchLength += SIZE_OF_LONG;
                    countInput += SIZE_OF_LONG;
                    countMatch += SIZE_OF_LONG;
                }
                while (matchLength < remaining && unsafe.getByte(inputBase, countMatch) == unsafe.getByte(inputBase, countInput)) {
                    matchLength++;
                    countInput++;
                    countMatch++;
                }
            }
            // ---- end inlined count ----
            matchLength += SIZE_OF_INT;

            if (!repcode) {
                // ---- begin inlined extendBackward (backward match extension, "catch up") ----
                catchUp:
                {
                    while (input - SIZE_OF_LONG >= anchor && matchAddress - SIZE_OF_LONG >= windowBaseAddress) {
                        long diff = unsafe.getLong(inputBase, input - SIZE_OF_LONG) ^ unsafe.getLong(inputBase, matchAddress - SIZE_OF_LONG);
                        if (diff != 0) {
                            int equalBytes = Long.numberOfLeadingZeros(diff) >>> 3;
                            input -= equalBytes;
                            matchLength += equalBytes;
                            break catchUp;
                        }
                        input -= SIZE_OF_LONG;
                        matchAddress -= SIZE_OF_LONG;
                        matchLength += SIZE_OF_LONG;
                    }
                    // fewer than 8 bytes left before the anchor or the window start: compare the 8 bytes
                    // before both anyway and keep the equal ones that are available. Both reads stay in the
                    // buffer when the match is 8 bytes past the window start (input is after the match).
                    long available = Math.min(input - anchor, matchAddress - windowBaseAddress);
                    if (available > 0) {
                        if (matchAddress - SIZE_OF_LONG >= windowBaseAddress) {
                            long diff = unsafe.getLong(inputBase, input - SIZE_OF_LONG) ^ unsafe.getLong(inputBase, matchAddress - SIZE_OF_LONG);
                            int equalBytes = Long.numberOfLeadingZeros(diff) >>> 3;
                            if (equalBytes > available) {
                                equalBytes = (int) available;
                            }
                            input -= equalBytes;
                            matchLength += equalBytes;
                        }
                        else {
                            while (input > anchor && matchAddress > windowBaseAddress && unsafe.getByte(inputBase, input - 1) == unsafe.getByte(inputBase, matchAddress - 1)) {
                                input--;
                                matchAddress--;
                                matchLength++;
                            }
                        }
                    }
                }
                // ---- end inlined extendBackward ----
                offset2 = offset1;
                offset1 = offsetCode - REP_MOVE;
            }

            {
                // ---- begin inlined SequenceStore.storeSequence (same stores, same order) ----
                int literalsLength = output.literalsLength;
                int literalLength = (int) (input - anchor);
                long copySource = anchor;
                long copyTarget = literalsBufferBase + literalsLength;
                int copied = 0;
                do {
                    unsafe.putLong(literalsBuffer, copyTarget, unsafe.getLong(inputBase, copySource));
                    copySource += SIZE_OF_LONG;
                    copyTarget += SIZE_OF_LONG;
                    copied += SIZE_OF_LONG;
                }
                while (copied < literalLength);
                output.literalsLength = literalsLength + literalLength;
                if (literalLength > 65535) {
                    output.longLengthField = SequenceStore.LongField.LITERAL;
                    output.longLengthPosition = sequenceCount;
                }
                seqLiteralLengths[sequenceCount] = literalLength;
                seqOffsets[sequenceCount] = offsetCode + 1;
                int matchLengthBase = matchLength - MIN_MATCH;
                if (matchLengthBase > 65535) {
                    output.longLengthField = SequenceStore.LongField.MATCH;
                    output.longLengthPosition = sequenceCount;
                }
                seqMatchLengths[sequenceCount] = matchLengthBase;
                sequenceCount++;
                // ---- end inlined SequenceStore.storeSequence ----
            }

            input += matchLength;

            if (input <= inputLimit) {
                // fill table at matchStart + 2 and matchEnd - 2
                long fillA = unsafe.getLong(inputBase, baseAddress + current + 2);
                hashTable[(int) ((fillA * hashMultiplier) >>> hashRightShift)] = current + 2;

                long fillB = unsafe.getLong(inputBase, input - 2);
                hashTable[(int) ((fillB * hashMultiplier) >>> hashRightShift)] = (int) (input - 2 - baseAddress);

                while (input <= inputLimit && offset2 > 0 && unsafe.getInt(inputBase, input) == unsafe.getInt(inputBase, input - offset2)) {
                    // ---- begin inlined count ----
                    countInput = input + SIZE_OF_INT;
                    countMatch = countInput - offset2;
                    remaining = (int) (inputEnd - countInput);
                    int count = 0;
                    repeatCountLoop:
                    {
                        while (count < remaining - (SIZE_OF_LONG - 1)) {
                            long diff = unsafe.getLong(inputBase, countMatch) ^ unsafe.getLong(inputBase, countInput);
                            if (diff != 0) {
                                count += Long.numberOfTrailingZeros(diff) >> 3;
                                break repeatCountLoop;
                            }
                            count += SIZE_OF_LONG;
                            countInput += SIZE_OF_LONG;
                            countMatch += SIZE_OF_LONG;
                        }
                        while (count < remaining && unsafe.getByte(inputBase, countMatch) == unsafe.getByte(inputBase, countInput)) {
                            count++;
                            countInput++;
                            countMatch++;
                        }
                    }
                    // ---- end inlined count ----
                    int repetitionLength = count + SIZE_OF_INT;

                    int temp = offset2;
                    offset2 = offset1;
                    offset1 = temp;

                    long repeatValue = unsafe.getLong(inputBase, input);
                    hashTable[(int) ((repeatValue * hashMultiplier) >>> hashRightShift)] = (int) (input - baseAddress);

                    {
                        // inlined SequenceStore.storeSequence with no literals: nothing to copy, and a
                        // literal length of 0 can't hit the long-length case
                        seqLiteralLengths[sequenceCount] = 0;
                        seqOffsets[sequenceCount] = 0 + 1;
                        int matchLengthBase = repetitionLength - MIN_MATCH;
                        if (matchLengthBase > 65535) {
                            output.longLengthField = SequenceStore.LongField.MATCH;
                            output.longLengthPosition = sequenceCount;
                        }
                        seqMatchLengths[sequenceCount] = matchLengthBase;
                        sequenceCount++;
                    }

                    input += repetitionLength;
                }
            }
            anchor = input;
        }

        output.sequenceCount = sequenceCount;

        offsets.saveOffset0(offset1 != 0 ? offset1 : savedOffset);
        offsets.saveOffset1(offset2 != 0 ? offset2 : savedOffset);

        return (int) (inputEnd - anchor);
    }

    // The per-position loop: from input, the first position with a repcode or a hash match, as
    // (position - windowBaseAddress) << 32 | (position - match address), the low half 0 for a
    // repcode match (the hash table entry of that position is written), or -1 at inputLimit.
    // Alone in a method, with no call inside, so its values stay in registers.
    private static long scan(final Unsafe unsafe, Object inputBase, long input, long anchor, long inputLimit, int[] hashTable, long hashTableBase, long baseAddress, long windowBaseAddress,
            int offset1, long hashMultiplier, int hashRightShift, int step)
    {
        while (input < inputLimit) {
            long currentLong = unsafe.getLong(inputBase, input);
            // ARM/ART: the table through Unsafe: the hash is always in range (it is shifted down to
            // the table's log), and ART would bounds-check both accesses at every position
            long hashAddress = hashTableBase + (((currentLong * hashMultiplier) >>> hashRightShift) << 2);
            long matchAddress = baseAddress + unsafe.getInt(hashTable, hashAddress);
            unsafe.putInt(hashTable, hashAddress, (int) (input - baseAddress));
            if (offset1 > 0 && unsafe.getInt(inputBase, input + 1 - offset1) == (int) (currentLong >>> 8)) {
                return (input - windowBaseAddress) << 32;
            }
            if (matchAddress > windowBaseAddress && unsafe.getInt(inputBase, matchAddress) == (int) currentLong) {
                return (input - windowBaseAddress) << 32 | (input - matchAddress);
            }
            input += ((input - anchor) >> SEARCH_STRENGTH) + step;
        }
        return -1;
    }

    private static void handleMatch(boolean repcode, Object inputBase, long input, long anchor, long inputEnd, long inputLimit,
            long matchAddress, int offset1, int offset2, long windowBaseAddress, long baseAddress, int current,
            int[] hashTable, boolean intHash, long hashPrime, int hashLeftShift, int hashRightShift, int intHashRightShift,
            SequenceStore output, long[] cursor)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
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
            // ---- begin inlined extendBackward (backward match extension, "catch up") ----
            // ARM/ART: 8 bytes per compare instead of two getByte per byte (JNI calls on ART builds that
            // don't intrinsify them); the byte loop handles the last < 8 bytes before anchor / window start.
            // Same result as the original byte-at-a-time loop.
            catchUp:
            {
                while (input - SIZE_OF_LONG >= anchor && matchAddress - SIZE_OF_LONG >= windowBaseAddress) {
                    long diff = (split ? ((unsafe.getInt(inputBase, (input - SIZE_OF_LONG)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (input - SIZE_OF_LONG) + 4) << 32)) : unsafe.getLong(inputBase, (input - SIZE_OF_LONG))) ^ (split ? ((unsafe.getInt(inputBase, (matchAddress - SIZE_OF_LONG)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (matchAddress - SIZE_OF_LONG) + 4) << 32)) : unsafe.getLong(inputBase, (matchAddress - SIZE_OF_LONG)));
                    if (diff != 0) {
                        int equalBytes = Long.numberOfLeadingZeros(diff) >>> 3;
                        input -= equalBytes;
                        matchLength += equalBytes;
                        break catchUp;
                    }
                    input -= SIZE_OF_LONG;
                    matchAddress -= SIZE_OF_LONG;
                    matchLength += SIZE_OF_LONG;
                }
                while (input > anchor && matchAddress > windowBaseAddress && unsafe.getByte(inputBase, input - 1) == unsafe.getByte(inputBase, matchAddress - 1)) {
                    input--;
                    matchAddress--;
                    matchLength++;
                }
            }
            // ---- end inlined extendBackward ----
            offset2 = offset1;
            offset1 = offset;
            output.storeSequence(inputBase, anchor, (int) (input - anchor), offset + REP_MOVE, matchLength - MIN_MATCH);
        }

        input += matchLength;
        anchor = input;

        if (input <= inputLimit) {
            // fill table at matchStart + 2 and matchEnd - 2
            long fillA = (split ? ((unsafe.getInt(inputBase, (baseAddress + current + 2)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (baseAddress + current + 2) + 4) << 32)) : unsafe.getLong(inputBase, baseAddress + current + 2));
            hashTable[hashOf(fillA, intHash, hashPrime, hashLeftShift, hashRightShift, intHashRightShift)] = current + 2;

            long fillB = (split ? ((unsafe.getInt(inputBase, (input - 2)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (input - 2) + 4) << 32)) : unsafe.getLong(inputBase, input - 2));
            hashTable[hashOf(fillB, intHash, hashPrime, hashLeftShift, hashRightShift, intHashRightShift)] = (int) (input - 2 - baseAddress);

            while (input <= inputLimit && offset2 > 0 && unsafe.getInt(inputBase, input) == unsafe.getInt(inputBase, input - offset2)) {
                int repetitionLength = DoubleFastBlockCompressor.count(inputBase, input + SIZE_OF_INT, inputEnd, input + SIZE_OF_INT - offset2) + SIZE_OF_INT;

                int temp = offset2;
                offset2 = offset1;
                offset1 = temp;

                hashTable[hashOf((split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input)), intHash, hashPrime, hashLeftShift, hashRightShift, intHashRightShift)] = (int) (input - baseAddress);

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
