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

import java.util.Arrays;

import static io.airlift.compress.UnsafeUtil.ARRAY_BYTE_BASE_OFFSET;
import static io.airlift.compress.UnsafeUtil.SPLIT_LONGS;
import static io.airlift.compress.UnsafeUtil.UNSAFE;
import static io.airlift.compress.UnsafeUtil.copyMemory;
import static io.airlift.compress.zstd.Constants.SIZE_OF_LONG;

class SequenceStore
{
    public final byte[] literalsBuffer;
    public int literalsLength;

    public final int[] offsets;
    public final int[] literalLengths;
    public final int[] matchLengths;
    public int sequenceCount;

    // only [0] and [sequenceCount - 1] are written (SequenceEncoder's RLE mode and FSE table build
    // read those); the codes of every sequence are in packedCodes
    public final byte[] literalLengthCodes;
    public final byte[] matchLengthCodes;
    public final byte[] offsetCodes;

    // ARM/ART: per sequence, literal length code | offset code << 6 | match length code << 11 |
    // literal length extra bits << 17 | match length extra bits << 22 (SequenceEncoder reads one int
    // per sequence instead of three codes and two bit-table entries)
    public final int[] packedCodes;
    static final int OFFSET_CODE_SHIFT = 6;
    static final int MATCH_LENGTH_CODE_SHIFT = 11;
    static final int LITERAL_LENGTH_BITS_SHIFT = 17;
    static final int MATCH_LENGTH_BITS_SHIFT = 22;

    private static final long ARRAY_INT_BASE_OFFSET = UNSAFE.arrayBaseOffset(int[].class);

    public LongField longLengthField;
    public int longLengthPosition;

    public enum LongField
    {
        LITERAL, MATCH
    }

    private static final byte[] LITERAL_LENGTH_CODE = {0, 1, 2, 3, 4, 5, 6, 7,
                                                       8, 9, 10, 11, 12, 13, 14, 15,
                                                       16, 16, 17, 17, 18, 18, 19, 19,
                                                       20, 20, 20, 20, 21, 21, 21, 21,
                                                       22, 22, 22, 22, 22, 22, 22, 22,
                                                       23, 23, 23, 23, 23, 23, 23, 23,
                                                       24, 24, 24, 24, 24, 24, 24, 24,
                                                       24, 24, 24, 24, 24, 24, 24, 24};

    private static final byte[] MATCH_LENGTH_CODE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                                                     16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
                                                     32, 32, 33, 33, 34, 34, 35, 35, 36, 36, 36, 36, 37, 37, 37, 37,
                                                     38, 38, 38, 38, 38, 38, 38, 38, 39, 39, 39, 39, 39, 39, 39, 39,
                                                     40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40,
                                                     41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41,
                                                     42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42,
                                                     42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42, 42};

    // the code tables as ints, for Unsafe reads (Unsafe.getByte is a JNI call on older ART)
    private static final int[] LITERAL_LENGTH_CODE_INT = toInts(LITERAL_LENGTH_CODE);
    private static final int[] MATCH_LENGTH_CODE_INT = toInts(MATCH_LENGTH_CODE);

    public SequenceStore(int blockSize, int maxSequences)
    {
        offsets = new int[maxSequences];
        literalLengths = new int[maxSequences];
        matchLengths = new int[maxSequences];

        literalLengthCodes = new byte[maxSequences];
        matchLengthCodes = new byte[maxSequences];
        offsetCodes = new byte[maxSequences];
        packedCodes = new int[maxSequences];

        literalsBuffer = new byte[blockSize];

        reset();
    }

    public void appendLiterals(Object inputBase, long inputAddress, int inputSize)
    {
        copyMemory(inputBase, inputAddress, literalsBuffer, ARRAY_BYTE_BASE_OFFSET + literalsLength, inputSize);
        literalsLength += inputSize;
    }

    public void storeSequence(Object literalBase, long literalAddress, int literalLength, int offsetCode, int matchLengthBase)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        long input = literalAddress;
        long output = ARRAY_BYTE_BASE_OFFSET + literalsLength;
        int copied = 0;
        do {
            if (split) {
                long splitValue = ((unsafe.getInt(literalBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(literalBase, input + 4) << 32));
                unsafe.putInt(literalsBuffer, output, (int) splitValue);
                unsafe.putInt(literalsBuffer, output + 4, (int) (splitValue >>> 32));
            }
            else {
                unsafe.putLong(literalsBuffer, output, unsafe.getLong(literalBase, input));
            }
            input += SIZE_OF_LONG;
            output += SIZE_OF_LONG;
            copied += SIZE_OF_LONG;
        }
        while (copied < literalLength);

        literalsLength += literalLength;

        if (literalLength > 65535) {
            longLengthField = LongField.LITERAL;
            longLengthPosition = sequenceCount;
        }
        literalLengths[sequenceCount] = literalLength;

        offsets[sequenceCount] = offsetCode + 1;

        if (matchLengthBase > 65535) {
            longLengthField = LongField.MATCH;
            longLengthPosition = sequenceCount;
        }

        matchLengths[sequenceCount] = matchLengthBase;

        sequenceCount++;
    }

    public void reset()
    {
        literalsLength = 0;
        sequenceCount = 0;
        longLengthField = null;
    }

    // Codes and their histograms in one pass (the encoder used to count each code array again).
    // Leaves counts[code] for every code, as Histogram.count would have.
    // Codes and their histograms in one pass (the encoder used to count each code array again).
    // Leaves counts[code] for every code, as Histogram.count would have.
    // ARM/ART: every array access through Unsafe (ART bounds-checks each of the ~12 per sequence;
    // the indexes are sequence numbers and codes, in range by construction), Util.highestBit
    // written out (31 - numberOfLeadingZeros)
    public void generateCodes(int[] literalLengthCounts, int[] offsetCounts, int[] matchLengthCounts)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final long intBase = ARRAY_INT_BASE_OFFSET;
        Arrays.fill(literalLengthCounts, 0);
        Arrays.fill(offsetCounts, 0);
        Arrays.fill(matchLengthCounts, 0);
        // ARM/ART: arrays hoisted into locals so the loop does no reference-field loads per
        // iteration (read-barrier cost on ART; HotSpot hoists these itself).
        int count = sequenceCount;
        int[] packed = packedCodes;
        int[] llValues = literalLengths;
        int[] offValues = offsets;
        int[] mlValues = matchLengths;
        // ARM/ART: static code tables hoisted (barriered reference load per access on ART);
        // helper logic inlined - identical results
        int[] literalLengthCode = LITERAL_LENGTH_CODE_INT;
        int[] matchLengthCode = MATCH_LENGTH_CODE_INT;
        int[] literalLengthBits = Constants.LITERALS_LENGTH_BITS;
        int[] matchLengthBits = Constants.MATCH_LENGTH_BITS;
        for (int i = 0; i < count; ++i) {
            long sequenceOffset = intBase + ((long) i << 2);
            int literalLength = unsafe.getInt(llValues, sequenceOffset);
            int literalLengthCodeValue = literalLength >= 64 ? (31 - Integer.numberOfLeadingZeros(literalLength)) + 19 : unsafe.getInt(literalLengthCode, intBase + ((long) literalLength << 2));
            int offsetCode = 31 - Integer.numberOfLeadingZeros(unsafe.getInt(offValues, sequenceOffset));
            int matchLengthBase = unsafe.getInt(mlValues, sequenceOffset);
            int matchLengthCodeValue = matchLengthBase >= 128 ? (31 - Integer.numberOfLeadingZeros(matchLengthBase)) + 36 : unsafe.getInt(matchLengthCode, intBase + ((long) matchLengthBase << 2));
            unsafe.putInt(packed, sequenceOffset, literalLengthCodeValue | offsetCode << OFFSET_CODE_SHIFT | matchLengthCodeValue << MATCH_LENGTH_CODE_SHIFT
                    | unsafe.getInt(literalLengthBits, intBase + ((long) literalLengthCodeValue << 2)) << LITERAL_LENGTH_BITS_SHIFT
                    | unsafe.getInt(matchLengthBits, intBase + ((long) matchLengthCodeValue << 2)) << MATCH_LENGTH_BITS_SHIFT);
            long countOffset = intBase + ((long) literalLengthCodeValue << 2);
            unsafe.putInt(literalLengthCounts, countOffset, unsafe.getInt(literalLengthCounts, countOffset) + 1);
            countOffset = intBase + ((long) offsetCode << 2);
            unsafe.putInt(offsetCounts, countOffset, unsafe.getInt(offsetCounts, countOffset) + 1);
            countOffset = intBase + ((long) matchLengthCodeValue << 2);
            unsafe.putInt(matchLengthCounts, countOffset, unsafe.getInt(matchLengthCounts, countOffset) + 1);
        }

        if (longLengthField == LongField.LITERAL) {
            literalLengthCounts[packed[longLengthPosition] & 0x3F]--;
            packed[longLengthPosition] = (packed[longLengthPosition] & ~(0x3F | 0x1F << LITERAL_LENGTH_BITS_SHIFT))
                    | Constants.MAX_LITERALS_LENGTH_SYMBOL | literalLengthBits[Constants.MAX_LITERALS_LENGTH_SYMBOL] << LITERAL_LENGTH_BITS_SHIFT;
            literalLengthCounts[Constants.MAX_LITERALS_LENGTH_SYMBOL]++;
        }
        if (longLengthField == LongField.MATCH) {
            matchLengthCounts[(packed[longLengthPosition] >>> MATCH_LENGTH_CODE_SHIFT) & 0x3F]--;
            packed[longLengthPosition] = (packed[longLengthPosition] & ~(0x3F << MATCH_LENGTH_CODE_SHIFT | 0x1F << MATCH_LENGTH_BITS_SHIFT))
                    | Constants.MAX_MATCH_LENGTH_SYMBOL << MATCH_LENGTH_CODE_SHIFT | matchLengthBits[Constants.MAX_MATCH_LENGTH_SYMBOL] << MATCH_LENGTH_BITS_SHIFT;
            matchLengthCounts[Constants.MAX_MATCH_LENGTH_SYMBOL]++;
        }

        // the byte codes read outside the encoder loop
        if (count > 0) {
            for (int i : new int[] {0, count - 1}) {
                literalLengthCodes[i] = (byte) (packed[i] & 0x3F);
                offsetCodes[i] = (byte) ((packed[i] >>> OFFSET_CODE_SHIFT) & 0x1F);
                matchLengthCodes[i] = (byte) ((packed[i] >>> MATCH_LENGTH_CODE_SHIFT) & 0x3F);
            }
        }
    }

    private static int[] toInts(byte[] bytes)
    {
        int[] ints = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            ints[i] = bytes[i];
        }
        return ints;
    }

    private static int literalLengthToCode(int literalLength)
    {
        if (literalLength >= 64) {
            return Util.highestBit(literalLength) + 19;
        }
        else {
            return LITERAL_LENGTH_CODE[literalLength];
        }
    }

    /*
     * matchLengthBase = matchLength - MINMATCH
     * (that's how it's stored in SequenceStore)
     */
    private static int matchLengthToCode(int matchLengthBase)
    {
        if (matchLengthBase >= 128) {
            return Util.highestBit(matchLengthBase) + 36;
        }
        else {
            return MATCH_LENGTH_CODE[matchLengthBase];
        }
    }
}
