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

import static io.airlift.compress.UnsafeUtil.ARRAY_BYTE_BASE_OFFSET;
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

    public final byte[] literalLengthCodes;
    public final byte[] matchLengthCodes;
    public final byte[] offsetCodes;

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

    public SequenceStore(int blockSize, int maxSequences)
    {
        offsets = new int[maxSequences];
        literalLengths = new int[maxSequences];
        matchLengths = new int[maxSequences];

        literalLengthCodes = new byte[maxSequences];
        matchLengthCodes = new byte[maxSequences];
        offsetCodes = new byte[maxSequences];

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
        long input = literalAddress;
        long output = ARRAY_BYTE_BASE_OFFSET + literalsLength;
        int copied = 0;
        do {
            UNSAFE.putLong(literalsBuffer, output, UNSAFE.getLong(literalBase, input));
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

    public void generateCodes()
    {
        // ARM/ART: arrays hoisted into locals so the loop does no reference-field loads per
        // iteration (read-barrier cost on ART; HotSpot hoists these itself).
        int count = sequenceCount;
        byte[] llCodes = literalLengthCodes;
        byte[] offCodes = offsetCodes;
        byte[] mlCodes = matchLengthCodes;
        int[] llValues = literalLengths;
        int[] offValues = offsets;
        int[] mlValues = matchLengths;
        // ARM/ART: static code tables hoisted (barriered reference load per access on ART);
        // helper logic inlined - identical results
        byte[] literalLengthCode = LITERAL_LENGTH_CODE;
        byte[] matchLengthCode = MATCH_LENGTH_CODE;
        for (int i = 0; i < count; ++i) {
            int literalLength = llValues[i];
            llCodes[i] = literalLength >= 64 ? (byte) (Util.highestBit(literalLength) + 19) : literalLengthCode[literalLength];
            offCodes[i] = (byte) Util.highestBit(offValues[i]);
            int matchLengthBase = mlValues[i];
            mlCodes[i] = matchLengthBase >= 128 ? (byte) (Util.highestBit(matchLengthBase) + 36) : matchLengthCode[matchLengthBase];
        }

        if (longLengthField == LongField.LITERAL) {
            literalLengthCodes[longLengthPosition] = Constants.MAX_LITERALS_LENGTH_SYMBOL;
        }
        if (longLengthField == LongField.MATCH) {
            matchLengthCodes[longLengthPosition] = Constants.MAX_MATCH_LENGTH_SYMBOL;
        }
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
