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

// ARM/ART: Hot/cold split. Outlined heavy per-sequence match handling from the hot
// position-scan loop into handleMatch(). Reduces register pressure and prevents
// performance regressions caused by ART compiling methods as a single unit.
class DoubleFastBlockCompressor
        implements BlockCompressor
{
    private static final long ARRAY_INT_BASE_OFFSET = UNSAFE.arrayBaseOffset(int[].class);

    private static final int MIN_MATCH = 3;
    private static final int SEARCH_STRENGTH = 8;
    private static final int REP_MOVE = Constants.REPEATED_OFFSET_COUNT - 1;

    private static final int MATCH_KIND_REPCODE = 0;
    private static final int MATCH_KIND_LONG = 1;
    private static final int MATCH_KIND_SHORT = 2;

    // handleMatch() result slots (avoids allocating a holder per sequence)
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

        // Offsets in hash tables are relative to baseAddress. Hash tables can be reused across calls to compressBlock as long as
        // baseAddress is kept constant.
        // We don't want to generate sequences that point before the current window limit, so we "filter" out all results from looking up in the hash tables
        // beyond that point.
        final long baseAddress = state.getBaseAddress();
        final long windowBaseAddress = baseAddress + state.getWindowBaseOffset();

        int[] longHashTable = state.hashTable;
        int longHashBits = parameters.getHashLog();

        int[] shortHashTable = state.chainTable;
        int shortHashBits = parameters.getChainLog();

        final long inputEnd = inputAddress + inputSize;
        final long inputLimit = inputEnd - SIZE_OF_LONG; // We read a long at a time for computing the hashes

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

        final long[] cursor = new long[4]; // one tiny allocation per 128 KB block

        // ARM/ART: resolve the short-hash function ONCE (it is loop-invariant). A switch-based hash()
        // helper would re-read the 8 bytes through Unsafe and dispatch on every call; C2 inlines and
        // folds that away, ART may not. hash5..hash8 share the shape
        // ((value << leftShift) * prime) >>> (64 - bits), so the scan loop computes the hash
        // directly from currentLong; searchLength 4 (int hash) keeps its own formula.
        final boolean intShortHash = matchSearchLength == 4;
        final long shortHashPrime;
        final int shortHashLeftShift;
        switch (matchSearchLength) {
            case 5:
                shortHashPrime = PRIME_5_BYTES;
                shortHashLeftShift = Long.SIZE - 40;
                break;
            case 6:
                shortHashPrime = PRIME_6_BYTES;
                shortHashLeftShift = Long.SIZE - 48;
                break;
            case 7:
                shortHashPrime = PRIME_7_BYTES;
                shortHashLeftShift = Long.SIZE - 56;
                break;
            default:
                shortHashPrime = PRIME_8_BYTES;
                shortHashLeftShift = 0;
                break;
        }
        final int shortHashRightShift = Long.SIZE - shortHashBits;
        final int intHashRightShift = Integer.SIZE - shortHashBits;
        // long hash (hash8) inlined as ((value * PRIME_8_BYTES) >>> longHashRightShift)
        final int longHashRightShift = Long.SIZE - longHashBits;

        while (input < inputLimit) {   // < instead of <=, because repcode check at (input+1)
            // single read of the 8 bytes at `input`, reused for the long hash, the long-match
            // compare and the repcode compare ((int) (currentLong >>> 8) == getInt at input + 1
            // on this little-endian-only library)
            long currentLong = (split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input));

            int shortHash = intShortHash
                    ? ((int) currentLong * PRIME_4_BYTES) >>> intHashRightShift
                    : (int) (((currentLong << shortHashLeftShift) * shortHashPrime) >>> shortHashRightShift);
            long shortMatchAddress = baseAddress + shortHashTable[shortHash];

            int longHash = (int) ((currentLong * PRIME_8_BYTES) >>> longHashRightShift);
            long longMatchAddress = baseAddress + longHashTable[longHash];

            // update hash tables
            int current = (int) (input - baseAddress);
            longHashTable[longHash] = current;
            shortHashTable[shortHash] = current;

            int matchKind;
            if (offset1 > 0 && unsafe.getInt(inputBase, input + 1 - offset1) == (int) (currentLong >>> 8)) {
                matchKind = MATCH_KIND_REPCODE;
            }
            else if (longMatchAddress > windowBaseAddress && (split ? ((unsafe.getInt(inputBase, longMatchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, longMatchAddress + 4) << 32)) : unsafe.getLong(inputBase, longMatchAddress)) == currentLong) {
                matchKind = MATCH_KIND_LONG;
            }
            else if (shortMatchAddress > windowBaseAddress && unsafe.getInt(inputBase, shortMatchAddress) == (int) currentLong) {
                matchKind = MATCH_KIND_SHORT;
            }
            else {
                input += ((input - anchor) >> SEARCH_STRENGTH) + 1;
                continue;
            }

            handleMatch(matchKind, inputBase, input, anchor, inputEnd, inputLimit,
                    longMatchAddress, shortMatchAddress, offset1, offset2,
                    windowBaseAddress, baseAddress, current,
                    longHashTable, longHashBits, shortHashTable, shortHashBits, matchSearchLength,
                    output, cursor);

            input = cursor[CURSOR_INPUT];
            anchor = cursor[CURSOR_ANCHOR];
            offset1 = (int) cursor[CURSOR_OFFSET_1];
            offset2 = (int) cursor[CURSOR_OFFSET_2];
        }

        // save reps for next block
        offsets.saveOffset0(offset1 != 0 ? offset1 : savedOffset);
        offsets.saveOffset1(offset2 != 0 ? offset2 : savedOffset);

        // return the last literals size
        return (int) (inputEnd - anchor);
    }

    // ARM/ART: 64-bit (no split longs) DFAST path, shaped like FastBlockCompressor.compressBlock64
    // (see there): the positions without a match are scanned by scan(), a small method of its own,
    // and everything done per match is written out here. Same algorithm and output as the generic
    // loop above.
    private static int compressBlock64(Object inputBase, final long inputAddress, int inputSize, SequenceStore output, BlockCompressionState state, RepeatedOffsets offsets, CompressionParameters parameters)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        int matchSearchLength = Math.max(parameters.getSearchLength(), 4);


        final long baseAddress = state.getBaseAddress();
        final long windowBaseAddress = baseAddress + state.getWindowBaseOffset();
        final long inputEnd = inputAddress + inputSize;
        final long inputLimit = inputEnd - SIZE_OF_LONG; // We read a long at a time for computing the hashes

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

        // short hash: the 4-byte hash is the long hash of the value shifted left by 32 (the top 32
        // bits of (v << 32) * P are those of (int) v * (int) P), and (v << L) * P is v * (P << L)
        final long shortHashPrime;
        final int shortHashLeftShift;
        switch (matchSearchLength) {
            case 4:
                shortHashPrime = PRIME_4_BYTES & 0xFFFFFFFFL;
                shortHashLeftShift = Long.SIZE - 32;
                break;
            case 5:
                shortHashPrime = PRIME_5_BYTES;
                shortHashLeftShift = Long.SIZE - 40;
                break;
            case 6:
                shortHashPrime = PRIME_6_BYTES;
                shortHashLeftShift = Long.SIZE - 48;
                break;
            case 7:
                shortHashPrime = PRIME_7_BYTES;
                shortHashLeftShift = Long.SIZE - 56;
                break;
            default:
                shortHashPrime = PRIME_8_BYTES;
                shortHashLeftShift = 0;
                break;
        }

        final long shortHashMultiplier = shortHashPrime << shortHashLeftShift;
        final int shortHashRightShift = Long.SIZE - parameters.getChainLog();
        final int longHashRightShift = Long.SIZE - parameters.getHashLog();
        final int[] longHashTable = state.hashTable;
        final int[] shortHashTable = state.chainTable;
        final long hashTableBase = ARRAY_INT_BASE_OFFSET;
        final long literalsBufferBase = ARRAY_BYTE_BASE_OFFSET;
        final int[] seqLiteralLengths = output.literalLengths;
        final int[] seqOffsets = output.offsets;
        final int[] seqMatchLengths = output.matchLengths;
        final byte[] literalsBuffer = output.literalsBuffer;
        int sequenceCount = output.sequenceCount;

        while (true) {
            // positions without a match: scan, a small loop in a method of its own
            long found = scan(unsafe, inputBase, input, anchor, inputLimit, longHashTable, shortHashTable, hashTableBase, baseAddress, windowBaseAddress, offset1,
                    shortHashMultiplier, shortHashRightShift, longHashRightShift);
            if (found < 0) {
                break;
            }
            input = windowBaseAddress + (found >>> 32);
            int matchKind = (int) found & 0b11;
            int matchOffset = (int) found >>> 2;
            long longMatchAddress = input - matchOffset;
            long shortMatchAddress = input - matchOffset;
            int current = (int) (input - baseAddress);

            long matchAddress;
            int prefixLength;
            if (matchKind == MATCH_KIND_REPCODE) {
                // found a repeated sequence of at least 4 bytes, separated by offset1
                input++;
                matchAddress = input - offset1;
                prefixLength = SIZE_OF_INT;
            }
            else if (matchKind == MATCH_KIND_LONG) {
                // prefix long match
                matchAddress = longMatchAddress;
                prefixLength = SIZE_OF_LONG;
            }
            else {
                // prefix short match
                long nextLong = unsafe.getLong(inputBase, input + 1);
                int nextOffsetHash = (int) ((nextLong * PRIME_8_BYTES) >>> longHashRightShift);
                long nextOffsetMatchAddress = baseAddress + longHashTable[nextOffsetHash];
                longHashTable[nextOffsetHash] = current + 1;

                // check prefix long +1 match
                if (nextOffsetMatchAddress > windowBaseAddress && unsafe.getLong(inputBase, nextOffsetMatchAddress) == nextLong) {
                    input++;
                    matchAddress = nextOffsetMatchAddress;
                    prefixLength = SIZE_OF_LONG;
                }
                else {
                    // if no long +1 match, explore the short match we found
                    matchAddress = shortMatchAddress;
                    prefixLength = SIZE_OF_INT;
                }
            }

            // ---- begin inlined count ----
            long countInput = input + prefixLength;
            long countMatch = matchAddress + prefixLength;
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
            matchLength += prefixLength;

            int offsetCode;
            if (matchKind == MATCH_KIND_REPCODE) {
                offsetCode = 0;
            }
            else {
                int offset = (int) (input - matchAddress);
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
                offset1 = offset;
                offsetCode = offset + REP_MOVE;
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
                // Fill Table
                long fillValue = unsafe.getLong(inputBase, baseAddress + current + 2);
                longHashTable[(int) ((fillValue * PRIME_8_BYTES) >>> longHashRightShift)] = current + 2;
                shortHashTable[(int) ((fillValue * shortHashMultiplier) >>> shortHashRightShift)] = current + 2;

                fillValue = unsafe.getLong(inputBase, input - 2);
                longHashTable[(int) ((fillValue * PRIME_8_BYTES) >>> longHashRightShift)] = (int) (input - 2 - baseAddress);
                shortHashTable[(int) ((fillValue * shortHashMultiplier) >>> shortHashRightShift)] = (int) (input - 2 - baseAddress);

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

                    // swap offset2 <=> offset1
                    int temp = offset2;
                    offset2 = offset1;
                    offset1 = temp;

                    long repeatValue = unsafe.getLong(inputBase, input);
                    shortHashTable[(int) ((repeatValue * shortHashMultiplier) >>> shortHashRightShift)] = (int) (input - baseAddress);
                    longHashTable[(int) ((repeatValue * PRIME_8_BYTES) >>> longHashRightShift)] = (int) (input - baseAddress);

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

        // save reps for next block
        offsets.saveOffset0(offset1 != 0 ? offset1 : savedOffset);
        offsets.saveOffset1(offset2 != 0 ? offset2 : savedOffset);

        // return the last literals size
        return (int) (inputEnd - anchor);
    }

    // The per-position loop: from input, the first position with a match, as
    // (position - windowBaseAddress) << 32 | (position - match address) << 2 | match kind (the
    // distance is 0 for a repcode match; both table entries of that position are written), or -1
    // at inputLimit. Alone in a method, with no call inside, so its values stay in registers.
    private static long scan(final Unsafe unsafe, Object inputBase, long input, long anchor, long inputLimit, int[] longHashTable, int[] shortHashTable, long hashTableBase,
            long baseAddress, long windowBaseAddress, int offset1, long shortHashMultiplier, int shortHashRightShift, int longHashRightShift)
    {
        while (input < inputLimit) {   // < instead of <=, because repcode check at (input+1)
            long currentLong = unsafe.getLong(inputBase, input);

            // ARM/ART: the tables through Unsafe: the hashes are always in range (shifted down to the
            // tables' logs), and ART would bounds-check the four accesses at every position
            long shortHashAddress = hashTableBase + (((currentLong * shortHashMultiplier) >>> shortHashRightShift) << 2);
            long shortMatchAddress = baseAddress + unsafe.getInt(shortHashTable, shortHashAddress);

            long longHashAddress = hashTableBase + (((currentLong * PRIME_8_BYTES) >>> longHashRightShift) << 2);
            long longMatchAddress = baseAddress + unsafe.getInt(longHashTable, longHashAddress);

            // update hash tables
            int position = (int) (input - baseAddress);
            unsafe.putInt(longHashTable, longHashAddress, position);
            unsafe.putInt(shortHashTable, shortHashAddress, position);

            if (offset1 > 0 && unsafe.getInt(inputBase, input + 1 - offset1) == (int) (currentLong >>> 8)) {
                return (input - windowBaseAddress) << 32 | MATCH_KIND_REPCODE;
            }
            if (longMatchAddress > windowBaseAddress && unsafe.getLong(inputBase, longMatchAddress) == currentLong) {
                return (input - windowBaseAddress) << 32 | (input - longMatchAddress) << 2 | MATCH_KIND_LONG;
            }
            if (shortMatchAddress > windowBaseAddress && unsafe.getInt(inputBase, shortMatchAddress) == (int) currentLong) {
                return (input - windowBaseAddress) << 32 | (input - shortMatchAddress) << 2 | MATCH_KIND_SHORT;
            }
            input += ((input - anchor) >> SEARCH_STRENGTH) + 1;
        }
        return -1;
    }

    // Runs once per discovered sequence: match extension, sequence store, table refill and the
    // repcode repeat loop - verbatim the bodies of the original in-loop branches. Returns the
    // updated scan state through `cursor`.
    private static void handleMatch(int matchKind, Object inputBase, long input, long anchor, long inputEnd, long inputLimit,
            long longMatchAddress, long shortMatchAddress, int offset1, int offset2,
            long windowBaseAddress, long baseAddress, int current,
            int[] longHashTable, int longHashBits, int[] shortHashTable, int shortHashBits, int matchSearchLength,
            SequenceStore output, long[] cursor)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        // long hash (hash8) inlined as ((value * PRIME_8_BYTES) >>> longHashRightShift)
        final int longHashRightShift = Long.SIZE - longHashBits;
        // ARM/ART: SequenceStore internals hoisted ONCE per sequence; the inlined appends below do
        // no reference-field loads (each carries a GC read barrier on ART) and no calls. This is
        // the round-3 idea applied at the correct layer: in the outlined per-sequence method,
        // not the per-position scan loop (where the extra live values caused spills).
        final byte[] literalsBuffer = output.literalsBuffer;
        final int[] seqLiteralLengths = output.literalLengths;
        final int[] seqOffsets = output.offsets;
        final int[] seqMatchLengths = output.matchLengths;
        int literalsLength = output.literalsLength;
        int sequenceCount = output.sequenceCount;

        int matchLength;
        int offset;

        if (matchKind == MATCH_KIND_REPCODE) {
            // found a repeated sequence of at least 4 bytes, separated by offset1
            matchLength = count(inputBase, input + 1 + SIZE_OF_INT, inputEnd, input + 1 + SIZE_OF_INT - offset1) + SIZE_OF_INT;
            input++;
            {
                // inlined SequenceStore.storeSequence (same stores, same order)
                int literalLen = (int) (input - anchor);
                long copySource = anchor;
                long copyTarget = ARRAY_BYTE_BASE_OFFSET + literalsLength;
                int copied = 0;
                do {
                    if (split) {
                        long splitValue = ((unsafe.getInt(inputBase, copySource) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, copySource + 4) << 32));
                        unsafe.putInt(literalsBuffer, copyTarget, (int) splitValue);
                        unsafe.putInt(literalsBuffer, copyTarget + 4, (int) (splitValue >>> 32));
                    }
                    else {
                        unsafe.putLong(literalsBuffer, copyTarget, unsafe.getLong(inputBase, copySource));
                    }
                    copySource += SIZE_OF_LONG;
                    copyTarget += SIZE_OF_LONG;
                    copied += SIZE_OF_LONG;
                }
                while (copied < literalLen);
                literalsLength += literalLen;
                if (literalLen > 65535) {
                    output.longLengthField = SequenceStore.LongField.LITERAL;
                    output.longLengthPosition = sequenceCount;
                }
                seqLiteralLengths[sequenceCount] = literalLen;
                seqOffsets[sequenceCount] = 0 + 1;
                int matchLengthBase = matchLength - MIN_MATCH;
                if (matchLengthBase > 65535) {
                    output.longLengthField = SequenceStore.LongField.MATCH;
                    output.longLengthPosition = sequenceCount;
                }
                seqMatchLengths[sequenceCount] = matchLengthBase;
                sequenceCount++;
            }
        }
        else {
            if (matchKind == MATCH_KIND_LONG) {
                // prefix long match
                matchLength = count(inputBase, input + SIZE_OF_LONG, inputEnd, longMatchAddress + SIZE_OF_LONG) + SIZE_OF_LONG;
                offset = (int) (input - longMatchAddress);
                // ---- begin inlined extendBackward (backward match extension, "catch up") ----
                // ARM/ART: 8 bytes per compare instead of two getByte per byte (JNI calls on ART builds that
                // don't intrinsify them); the byte loop handles the last < 8 bytes before anchor / window start.
                // Same result as the original byte-at-a-time loop.
                catchUp:
                {
                    while (input - SIZE_OF_LONG >= anchor && longMatchAddress - SIZE_OF_LONG >= windowBaseAddress) {
                        long diff = (split ? ((unsafe.getInt(inputBase, (input - SIZE_OF_LONG)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (input - SIZE_OF_LONG) + 4) << 32)) : unsafe.getLong(inputBase, (input - SIZE_OF_LONG))) ^ (split ? ((unsafe.getInt(inputBase, (longMatchAddress - SIZE_OF_LONG)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (longMatchAddress - SIZE_OF_LONG) + 4) << 32)) : unsafe.getLong(inputBase, (longMatchAddress - SIZE_OF_LONG)));
                        if (diff != 0) {
                            int equalBytes = Long.numberOfLeadingZeros(diff) >>> 3;
                            input -= equalBytes;
                            matchLength += equalBytes;
                            break catchUp;
                        }
                        input -= SIZE_OF_LONG;
                        longMatchAddress -= SIZE_OF_LONG;
                        matchLength += SIZE_OF_LONG;
                    }
                    while (input > anchor && longMatchAddress > windowBaseAddress && unsafe.getByte(inputBase, input - 1) == unsafe.getByte(inputBase, longMatchAddress - 1)) {
                        input--;
                        longMatchAddress--;
                        matchLength++;
                    }
                }
                // ---- end inlined extendBackward ----
            }
            else {
                // prefix short match
                long nextLong = (split ? ((unsafe.getInt(inputBase, (input + 1)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (input + 1) + 4) << 32)) : unsafe.getLong(inputBase, input + 1));
                int nextOffsetHash = (int) ((nextLong * PRIME_8_BYTES) >>> longHashRightShift);
                long nextOffsetMatchAddress = baseAddress + longHashTable[nextOffsetHash];
                longHashTable[nextOffsetHash] = current + 1;

                // check prefix long +1 match
                if (nextOffsetMatchAddress > windowBaseAddress && (split ? ((unsafe.getInt(inputBase, nextOffsetMatchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, nextOffsetMatchAddress + 4) << 32)) : unsafe.getLong(inputBase, nextOffsetMatchAddress)) == nextLong) {
                    matchLength = count(inputBase, input + 1 + SIZE_OF_LONG, inputEnd, nextOffsetMatchAddress + SIZE_OF_LONG) + SIZE_OF_LONG;
                    input++;
                    offset = (int) (input - nextOffsetMatchAddress);
                    // ---- begin inlined extendBackward (backward match extension, "catch up") ----
                    // ARM/ART: 8 bytes per compare instead of two getByte per byte (JNI calls on ART builds that
                    // don't intrinsify them); the byte loop handles the last < 8 bytes before anchor / window start.
                    // Same result as the original byte-at-a-time loop.
                    catchUp:
                    {
                        while (input - SIZE_OF_LONG >= anchor && nextOffsetMatchAddress - SIZE_OF_LONG >= windowBaseAddress) {
                            long diff = (split ? ((unsafe.getInt(inputBase, (input - SIZE_OF_LONG)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (input - SIZE_OF_LONG) + 4) << 32)) : unsafe.getLong(inputBase, (input - SIZE_OF_LONG))) ^ (split ? ((unsafe.getInt(inputBase, (nextOffsetMatchAddress - SIZE_OF_LONG)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (nextOffsetMatchAddress - SIZE_OF_LONG) + 4) << 32)) : unsafe.getLong(inputBase, (nextOffsetMatchAddress - SIZE_OF_LONG)));
                            if (diff != 0) {
                                int equalBytes = Long.numberOfLeadingZeros(diff) >>> 3;
                                input -= equalBytes;
                                matchLength += equalBytes;
                                break catchUp;
                            }
                            input -= SIZE_OF_LONG;
                            nextOffsetMatchAddress -= SIZE_OF_LONG;
                            matchLength += SIZE_OF_LONG;
                        }
                        while (input > anchor && nextOffsetMatchAddress > windowBaseAddress && unsafe.getByte(inputBase, input - 1) == unsafe.getByte(inputBase, nextOffsetMatchAddress - 1)) {
                            input--;
                            nextOffsetMatchAddress--;
                            matchLength++;
                        }
                    }
                    // ---- end inlined extendBackward ----
                }
                else {
                    // if no long +1 match, explore the short match we found
                    matchLength = count(inputBase, input + SIZE_OF_INT, inputEnd, shortMatchAddress + SIZE_OF_INT) + SIZE_OF_INT;
                    offset = (int) (input - shortMatchAddress);
                    // ---- begin inlined extendBackward (backward match extension, "catch up") ----
                    // ARM/ART: 8 bytes per compare instead of two getByte per byte (JNI calls on ART builds that
                    // don't intrinsify them); the byte loop handles the last < 8 bytes before anchor / window start.
                    // Same result as the original byte-at-a-time loop.
                    catchUp:
                    {
                        while (input - SIZE_OF_LONG >= anchor && shortMatchAddress - SIZE_OF_LONG >= windowBaseAddress) {
                            long diff = (split ? ((unsafe.getInt(inputBase, (input - SIZE_OF_LONG)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (input - SIZE_OF_LONG) + 4) << 32)) : unsafe.getLong(inputBase, (input - SIZE_OF_LONG))) ^ (split ? ((unsafe.getInt(inputBase, (shortMatchAddress - SIZE_OF_LONG)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (shortMatchAddress - SIZE_OF_LONG) + 4) << 32)) : unsafe.getLong(inputBase, (shortMatchAddress - SIZE_OF_LONG)));
                            if (diff != 0) {
                                int equalBytes = Long.numberOfLeadingZeros(diff) >>> 3;
                                input -= equalBytes;
                                matchLength += equalBytes;
                                break catchUp;
                            }
                            input -= SIZE_OF_LONG;
                            shortMatchAddress -= SIZE_OF_LONG;
                            matchLength += SIZE_OF_LONG;
                        }
                        while (input > anchor && shortMatchAddress > windowBaseAddress && unsafe.getByte(inputBase, input - 1) == unsafe.getByte(inputBase, shortMatchAddress - 1)) {
                            input--;
                            shortMatchAddress--;
                            matchLength++;
                        }
                    }
                    // ---- end inlined extendBackward ----
                }
            }

            offset2 = offset1;
            offset1 = offset;

            {
                // inlined SequenceStore.storeSequence (same stores, same order)
                int literalLen = (int) (input - anchor);
                long copySource = anchor;
                long copyTarget = ARRAY_BYTE_BASE_OFFSET + literalsLength;
                int copied = 0;
                do {
                    if (split) {
                        long splitValue = ((unsafe.getInt(inputBase, copySource) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, copySource + 4) << 32));
                        unsafe.putInt(literalsBuffer, copyTarget, (int) splitValue);
                        unsafe.putInt(literalsBuffer, copyTarget + 4, (int) (splitValue >>> 32));
                    }
                    else {
                        unsafe.putLong(literalsBuffer, copyTarget, unsafe.getLong(inputBase, copySource));
                    }
                    copySource += SIZE_OF_LONG;
                    copyTarget += SIZE_OF_LONG;
                    copied += SIZE_OF_LONG;
                }
                while (copied < literalLen);
                literalsLength += literalLen;
                if (literalLen > 65535) {
                    output.longLengthField = SequenceStore.LongField.LITERAL;
                    output.longLengthPosition = sequenceCount;
                }
                seqLiteralLengths[sequenceCount] = literalLen;
                seqOffsets[sequenceCount] = offset + REP_MOVE + 1;
                int matchLengthBase = matchLength - MIN_MATCH;
                if (matchLengthBase > 65535) {
                    output.longLengthField = SequenceStore.LongField.MATCH;
                    output.longLengthPosition = sequenceCount;
                }
                seqMatchLengths[sequenceCount] = matchLengthBase;
                sequenceCount++;
            }
        }

        input += matchLength;
        anchor = input;

        if (input <= inputLimit) {
            // ARM/ART: short hash resolved once per match (same formulas the removed hash() switch
            // helper used, which was too big for ART to inline); each refill position is read once
            // for both hashes.
            final boolean intShortHash;
            final long shortHashPrime;
            final int shortHashLeftShift;
            switch (matchSearchLength) {
                case 8:
                    intShortHash = false;
                    shortHashPrime = PRIME_8_BYTES;
                    shortHashLeftShift = 0;
                    break;
                case 7:
                    intShortHash = false;
                    shortHashPrime = PRIME_7_BYTES;
                    shortHashLeftShift = Long.SIZE - 56;
                    break;
                case 6:
                    intShortHash = false;
                    shortHashPrime = PRIME_6_BYTES;
                    shortHashLeftShift = Long.SIZE - 48;
                    break;
                case 5:
                    intShortHash = false;
                    shortHashPrime = PRIME_5_BYTES;
                    shortHashLeftShift = Long.SIZE - 40;
                    break;
                default:
                    intShortHash = true;
                    shortHashPrime = 0;
                    shortHashLeftShift = 0;
                    break;
            }
            final int shortHashRightShift = Long.SIZE - shortHashBits;
            final int intHashRightShift = Integer.SIZE - shortHashBits;

            // Fill Table
            long fillValue = (split ? ((unsafe.getInt(inputBase, (baseAddress + current + 2)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (baseAddress + current + 2) + 4) << 32)) : unsafe.getLong(inputBase, baseAddress + current + 2));
            longHashTable[(int) ((fillValue * PRIME_8_BYTES) >>> longHashRightShift)] = current + 2;
            shortHashTable[(intShortHash ? ((int) fillValue * PRIME_4_BYTES) >>> intHashRightShift : (int) (((fillValue << shortHashLeftShift) * shortHashPrime) >>> shortHashRightShift))] = current + 2;

            fillValue = (split ? ((unsafe.getInt(inputBase, (input - 2)) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, (input - 2) + 4) << 32)) : unsafe.getLong(inputBase, input - 2));
            longHashTable[(int) ((fillValue * PRIME_8_BYTES) >>> longHashRightShift)] = (int) (input - 2 - baseAddress);
            shortHashTable[(intShortHash ? ((int) fillValue * PRIME_4_BYTES) >>> intHashRightShift : (int) (((fillValue << shortHashLeftShift) * shortHashPrime) >>> shortHashRightShift))] = (int) (input - 2 - baseAddress);

            while (input <= inputLimit && offset2 > 0 && unsafe.getInt(inputBase, input) == unsafe.getInt(inputBase, input - offset2)) {
                int repetitionLength = count(inputBase, input + SIZE_OF_INT, inputEnd, input + SIZE_OF_INT - offset2) + SIZE_OF_INT;

                // swap offset2 <=> offset1
                int temp = offset2;
                offset2 = offset1;
                offset1 = temp;

                long repeatValue = (split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input));
                shortHashTable[(intShortHash ? ((int) repeatValue * PRIME_4_BYTES) >>> intHashRightShift : (int) (((repeatValue << shortHashLeftShift) * shortHashPrime) >>> shortHashRightShift))] = (int) (input - baseAddress);
                longHashTable[(int) ((repeatValue * PRIME_8_BYTES) >>> longHashRightShift)] = (int) (input - baseAddress);

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
                anchor = input;
            }
        }

        output.literalsLength = literalsLength;
        output.sequenceCount = sequenceCount;

        cursor[CURSOR_INPUT] = input;
        cursor[CURSOR_ANCHOR] = anchor;
        cursor[CURSOR_OFFSET_1] = offset1;
        cursor[CURSOR_OFFSET_2] = offset2;
    }

    // TODO: same as LZ4RawCompressor.count

    /**
     * matchAddress must be < inputAddress
     */
    public static int count(Object inputBase, final long inputAddress, final long inputLimit, final long matchAddress)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        long input = inputAddress;
        long match = matchAddress;

        int remaining = (int) (inputLimit - inputAddress);

        // first, compare long at a time
        int count = 0;
        while (count < remaining - (SIZE_OF_LONG - 1)) {
            long diff = (split ? ((unsafe.getInt(inputBase, match) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, match + 4) << 32)) : unsafe.getLong(inputBase, match)) ^ (split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input));
            if (diff != 0) {
                return count + (Long.numberOfTrailingZeros(diff) >> 3);
            }

            count += SIZE_OF_LONG;
            input += SIZE_OF_LONG;
            match += SIZE_OF_LONG;
        }

        while (count < remaining && unsafe.getByte(inputBase, match) == unsafe.getByte(inputBase, input)) {
            count++;
            input++;
            match++;
        }

        return count;
    }

    private static final int PRIME_4_BYTES = 0x9E3779B1;
    private static final long PRIME_5_BYTES = 0xCF1BBCDCBBL;
    private static final long PRIME_6_BYTES = 0xCF1BBCDCBF9BL;
    private static final long PRIME_7_BYTES = 0xCF1BBCDCBFA563L;
    private static final long PRIME_8_BYTES = 0xCF1BBCDCB7A56463L;
}
