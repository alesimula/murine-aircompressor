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

import io.airlift.compress.MalformedInputException;
import sun.misc.Unsafe;

import java.util.Arrays;

import static io.airlift.compress.UnsafeUtil.ARRAY_BYTE_BASE_OFFSET;
import static io.airlift.compress.UnsafeUtil.SPLIT_LONGS;
import static io.airlift.compress.UnsafeUtil.UNSAFE;
import static io.airlift.compress.UnsafeUtil.copyMemory;
import static io.airlift.compress.zstd.Constants.COMPRESSED_BLOCK;
import static io.airlift.compress.zstd.Constants.COMPRESSED_LITERALS_BLOCK;
import static io.airlift.compress.zstd.Constants.DEFAULT_MAX_OFFSET_CODE_SYMBOL;
import static io.airlift.compress.zstd.Constants.LITERALS_LENGTH_BITS;
import static io.airlift.compress.zstd.Constants.LITERAL_LENGTH_TABLE_LOG;
import static io.airlift.compress.zstd.Constants.LONG_NUMBER_OF_SEQUENCES;
import static io.airlift.compress.zstd.Constants.MAGIC_NUMBER;
import static io.airlift.compress.zstd.Constants.MATCH_LENGTH_BITS;
import static io.airlift.compress.zstd.Constants.MATCH_LENGTH_TABLE_LOG;
import static io.airlift.compress.zstd.Constants.MAX_BLOCK_SIZE;
import static io.airlift.compress.zstd.Constants.MAX_LITERALS_LENGTH_SYMBOL;
import static io.airlift.compress.zstd.Constants.MAX_MATCH_LENGTH_SYMBOL;
import static io.airlift.compress.zstd.Constants.MIN_BLOCK_SIZE;
import static io.airlift.compress.zstd.Constants.MIN_SEQUENCES_SIZE;
import static io.airlift.compress.zstd.Constants.MIN_WINDOW_LOG;
import static io.airlift.compress.zstd.Constants.OFFSET_TABLE_LOG;
import static io.airlift.compress.zstd.Constants.RAW_BLOCK;
import static io.airlift.compress.zstd.Constants.RAW_LITERALS_BLOCK;
import static io.airlift.compress.zstd.Constants.RLE_BLOCK;
import static io.airlift.compress.zstd.Constants.RLE_LITERALS_BLOCK;
import static io.airlift.compress.zstd.Constants.SEQUENCE_ENCODING_BASIC;
import static io.airlift.compress.zstd.Constants.SEQUENCE_ENCODING_COMPRESSED;
import static io.airlift.compress.zstd.Constants.SEQUENCE_ENCODING_REPEAT;
import static io.airlift.compress.zstd.Constants.SEQUENCE_ENCODING_RLE;
import static io.airlift.compress.zstd.Constants.SIZE_OF_BLOCK_HEADER;
import static io.airlift.compress.zstd.Constants.SIZE_OF_BYTE;
import static io.airlift.compress.zstd.Constants.SIZE_OF_INT;
import static io.airlift.compress.zstd.Constants.SIZE_OF_LONG;
import static io.airlift.compress.zstd.Constants.SIZE_OF_SHORT;
import static io.airlift.compress.zstd.Constants.TREELESS_LITERALS_BLOCK;
import static io.airlift.compress.zstd.Util.fail;
import static io.airlift.compress.zstd.Util.get24BitLittleEndian;
import static io.airlift.compress.zstd.Util.mask;
import static io.airlift.compress.zstd.Util.verify;
import static java.lang.String.format;

class ZstdFrameDecompressor
{
    private static final int[] DEC_32_TABLE = {4, 1, 2, 1, 4, 4, 4, 4};
    private static final int[] DEC_64_TABLE = {0, 0, 0, -1, 0, 1, 2, 3};

    private static final int V07_MAGIC_NUMBER = 0xFD2FB527;

    static final int MAX_WINDOW_SIZE = 1 << 23;

    private static final int[] LITERALS_LENGTH_BASE = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 0x80, 0x100, 0x200, 0x400, 0x800, 0x1000,
            0x2000, 0x4000, 0x8000, 0x10000};

    private static final int[] MATCH_LENGTH_BASE = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
            35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 0x83, 0x103, 0x203, 0x403, 0x803,
            0x1003, 0x2003, 0x4003, 0x8003, 0x10003};

    private static final int[] OFFSET_CODES_BASE = {
            0, 1, 1, 5, 0xD, 0x1D, 0x3D, 0x7D,
            0xFD, 0x1FD, 0x3FD, 0x7FD, 0xFFD, 0x1FFD, 0x3FFD, 0x7FFD,
            0xFFFD, 0x1FFFD, 0x3FFFD, 0x7FFFD, 0xFFFFD, 0x1FFFFD, 0x3FFFFD, 0x7FFFFD,
            0xFFFFFD, 0x1FFFFFD, 0x3FFFFFD, 0x7FFFFFD, 0xFFFFFFD};

    private static final FiniteStateEntropy.Table DEFAULT_LITERALS_LENGTH_TABLE = new FiniteStateEntropy.Table(
            6,
            new int[] {
                    0, 16, 32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0, 0, 0, 32, 0, 0, 32, 0, 32, 0, 32, 0, 0, 32, 0, 32, 0, 32, 0, 0, 16, 32, 0, 0, 48, 16, 32, 32, 32,
                    32, 32, 32, 32, 32, 0, 32, 32, 32, 32, 32, 32, 0, 0, 0, 0},
            new byte[] {
                    0, 0, 1, 3, 4, 6, 7, 9, 10, 12, 14, 16, 18, 19, 21, 22, 24, 25, 26, 27, 29, 31, 0, 1, 2, 4, 5, 7, 8, 10, 11, 13, 16, 17, 19, 20, 22, 23, 25, 25, 26, 28, 30, 0,
                    1, 2, 3, 5, 6, 8, 9, 11, 12, 15, 17, 18, 20, 21, 23, 24, 35, 34, 33, 32},
            new byte[] {
                    4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 6, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 4, 4, 5, 5, 5, 5, 5, 5, 5, 6, 5, 5, 5, 5, 5, 5, 4, 4, 5, 6, 6, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5,
                    6, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6});

    private static final FiniteStateEntropy.Table DEFAULT_OFFSET_CODES_TABLE = new FiniteStateEntropy.Table(
            5,
            new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16, 0, 0, 0, 0, 16, 0, 0, 0, 16, 0, 0, 0, 0, 0, 0, 0},
            new byte[] {0, 6, 9, 15, 21, 3, 7, 12, 18, 23, 5, 8, 14, 20, 2, 7, 11, 17, 22, 4, 8, 13, 19, 1, 6, 10, 16, 28, 27, 26, 25, 24},
            new byte[] {5, 4, 5, 5, 5, 5, 4, 5, 5, 5, 5, 4, 5, 5, 5, 4, 5, 5, 5, 5, 4, 5, 5, 5, 4, 5, 5, 5, 5, 5, 5, 5});

    private static final FiniteStateEntropy.Table DEFAULT_MATCH_LENGTH_TABLE = new FiniteStateEntropy.Table(
            6,
            new int[] {
                    0, 0, 32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16, 0, 32, 0, 32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 48, 16, 32, 32, 32, 32,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            new byte[] {
                    0, 1, 2, 3, 5, 6, 8, 10, 13, 16, 19, 22, 25, 28, 31, 33, 35, 37, 39, 41, 43, 45, 1, 2, 3, 4, 6, 7, 9, 12, 15, 18, 21, 24, 27, 30, 32, 34, 36, 38, 40, 42, 44, 1,
                    1, 2, 4, 5, 7, 8, 11, 14, 17, 20, 23, 26, 29, 52, 51, 50, 49, 48, 47, 46},
            new byte[] {
                    6, 4, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6,
                    6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6});

    private static final long[] DEFAULT_LITERALS_LENGTH_PACKED = pack(DEFAULT_LITERALS_LENGTH_TABLE, LITERALS_LENGTH_BASE, LITERALS_LENGTH_BITS, new long[1 << DEFAULT_LITERALS_LENGTH_TABLE.log2Size]);
    private static final long[] DEFAULT_OFFSET_CODES_PACKED = pack(DEFAULT_OFFSET_CODES_TABLE, OFFSET_CODES_BASE, null, new long[1 << DEFAULT_OFFSET_CODES_TABLE.log2Size]);
    private static final long[] DEFAULT_MATCH_LENGTH_PACKED = pack(DEFAULT_MATCH_LENGTH_TABLE, MATCH_LENGTH_BASE, MATCH_LENGTH_BITS, new long[1 << DEFAULT_MATCH_LENGTH_TABLE.log2Size]);

    // offset codes: the code is its own number of extra bits (packed tables built by FseTableReader)
    private static final int[] OFFSET_CODES_BITS = new int[DEFAULT_MAX_OFFSET_CODE_SYMBOL + 1];

    static {
        for (int code = 0; code < OFFSET_CODES_BITS.length; code++) {
            OFFSET_CODES_BITS[code] = code;
        }
    }

    private final byte[] literals = new byte[MAX_BLOCK_SIZE + 2 * SIZE_OF_LONG]; // extra space to allow for long-at-a-time copy

    // current buffer containing literals
    private Object literalsBase;
    private long literalsAddress;
    private long literalsLimit;

    private final int[] previousOffsets = new int[3];

    private final FiniteStateEntropy.Table literalsLengthTable = new FiniteStateEntropy.Table(LITERAL_LENGTH_TABLE_LOG);
    private final FiniteStateEntropy.Table offsetCodesTable = new FiniteStateEntropy.Table(OFFSET_TABLE_LOG);
    private final FiniteStateEntropy.Table matchLengthTable = new FiniteStateEntropy.Table(MATCH_LENGTH_TABLE_LOG);

    private FiniteStateEntropy.Table currentLiteralsLengthTable;
    private FiniteStateEntropy.Table currentOffsetCodesTable;
    private FiniteStateEntropy.Table currentMatchLengthTable;

    // ARM/ART: sequence decoding tables packed like native zstd's ZSTD_seqSymbol, one long per state:
    //   bits  0..15 next state base (newState), 16..23 state bits (numberOfBits),
    //   bits 24..31 extra bits of the code, 32..63 base value of the code.
    // decompressSequences then does 3 table reads per sequence instead of ~14 (each a bounds check
    // on ART). Rebuilt only when a block brings a new table (RLE / compressed); repeat mode reuses it.
    // The fields below bit 32 are read as (int) entry, so ARM32 extracts them from one register.
    private final long[] literalsLengthPacked = new long[1 << LITERAL_LENGTH_TABLE_LOG];
    private final long[] offsetCodesPacked = new long[1 << OFFSET_TABLE_LOG];
    private final long[] matchLengthPacked = new long[1 << MATCH_LENGTH_TABLE_LOG];

    // ARM/ART + register pressure: the three current packed tables side by side in one array, read with
    // Unsafe in decompressSequences. States are absolute indexes into it (newState already carries the
    // table's start), so the loop keeps one reference and does no bounds checks. A state is always
    // < its table size by FSE construction (newState + (1 << numberOfBits) - 1 < tableSize), also for
    // corrupt input, since the tables are built (and validated) here.
    private static final int LITERALS_LENGTH_TABLE_START = 0;
    private static final int OFFSET_CODES_TABLE_START = 1 << LITERAL_LENGTH_TABLE_LOG;
    private static final int MATCH_LENGTH_TABLE_START = OFFSET_CODES_TABLE_START + (1 << OFFSET_TABLE_LOG);
    private static final long ARRAY_LONG_BASE_OFFSET = UNSAFE.arrayBaseOffset(long[].class);
    private final long[] sequenceTables = new long[MATCH_LENGTH_TABLE_START + (1 << MATCH_LENGTH_TABLE_LOG)];

    private long[] currentLiteralsLengthPacked;
    private long[] currentOffsetCodesPacked;
    private long[] currentMatchLengthPacked;

    private long remainingLiteralsInput;

    private final Huffman huffman = new Huffman();
    private final FseTableReader fse = new FseTableReader();

    public int decompress(
            final Object inputBase,
            final long inputAddress,
            final long inputLimit,
            final Object outputBase,
            final long outputAddress,
            final long outputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        if (outputAddress == outputLimit) {
            return 0;
        }

        long input = inputAddress;
        long output = outputAddress;

        while (input < inputLimit) {
            reset();
            long outputStart = output;
            input += verifyMagic(inputBase, input, inputLimit);

            FrameHeader frameHeader = readFrameHeader(inputBase, input, inputLimit);
            input += frameHeader.headerSize;
            // checksum per block while the block is still in cache, like native zstd (one pass over the
            // whole output at the end read it back from memory)
            XxHash64 hasher = frameHeader.hasChecksum ? new XxHash64() : null;

            boolean lastBlock;
            do {
                verify(input + SIZE_OF_BLOCK_HEADER <= inputLimit, input, "Not enough input bytes");

                // read block header
                int header = get24BitLittleEndian(inputBase, input);
                input += SIZE_OF_BLOCK_HEADER;

                lastBlock = (header & 1) != 0;
                int blockType = (header >>> 1) & 0b11;
                int blockSize = (header >>> 3) & 0x1F_FFFF; // 21 bits

                int decodedSize;
                switch (blockType) {
                    case RAW_BLOCK:
                        verify(input + blockSize <= inputLimit, input, "Not enough input bytes");
                        decodedSize = decodeRawBlock(inputBase, input, blockSize, outputBase, output, outputLimit);
                        input += blockSize;
                        break;
                    case RLE_BLOCK:
                        verify(input + 1 <= inputLimit, input, "Not enough input bytes");
                        decodedSize = decodeRleBlock(blockSize, inputBase, input, outputBase, output, outputLimit);
                        input += 1;
                        break;
                    case COMPRESSED_BLOCK:
                        verify(input + blockSize <= inputLimit, input, "Not enough input bytes");
                        decodedSize = decodeCompressedBlock(inputBase, input, blockSize, outputBase, output, outputLimit, frameHeader.windowSize, outputAddress);
                        input += blockSize;
                        break;
                    default:
                        throw fail(input, "Invalid block type");
                }

                if (hasher != null) {
                    hasher.update(outputBase, output, decodedSize);
                }
                output += decodedSize;
            }
            while (!lastBlock);

            if (frameHeader.hasChecksum) {
                int decodedFrameSize = (int) (output - outputStart);

                long hash = hasher.hash();

                verify(input + SIZE_OF_INT <= inputLimit, input, "Not enough input bytes");
                int checksum = unsafe.getInt(inputBase, input);
                if (checksum != (int) hash) {
                    throw new MalformedInputException(input, format("Bad checksum. Expected: %s, actual: %s", Integer.toHexString(checksum), Integer.toHexString((int) hash)));
                }

                input += SIZE_OF_INT;
            }
        }

        return (int) (output - outputAddress);
    }

    void reset()
    {
        previousOffsets[0] = 1;
        previousOffsets[1] = 4;
        previousOffsets[2] = 8;

        currentLiteralsLengthTable = null;
        currentOffsetCodesTable = null;
        currentMatchLengthTable = null;

        currentLiteralsLengthPacked = null;
        currentOffsetCodesPacked = null;
        currentMatchLengthPacked = null;
    }

    // offsets: extraBitsTable == null, the code itself is the number of extra bits
    private static void place(long[] tables, long[] packed, int log2Size, int start)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final long arrayLongBaseOffset = ARRAY_LONG_BASE_OFFSET;
        int size = 1 << log2Size;
        for (int i = 0; i < size; i++) {
            tables[start + i] = packed[i] + start; // newState (low 16 bits) becomes an absolute index
            // ... stored as a byte offset: (index << 3) + the long[] base offset (see decompressSequences)
            tables[start + i] = (tables[start + i] & ~0xFFFFL) | (((tables[start + i] & 0xFFFF) << 3) + arrayLongBaseOffset);
        }
    }

    private static long[] pack(FiniteStateEntropy.Table table, int[] baseTable, int[] extraBitsTable, long[] packed)
    {
        int size = 1 << table.log2Size;
        for (int state = 0; state < size; state++) {
            int code = table.symbol[state];
            int extraBits = extraBitsTable == null ? code : extraBitsTable[code];
            packed[state] = ((long) baseTable[code] << 32)
                    | (extraBits << 24)
                    | ((table.numberOfBits[state] & 0xFF) << 16)
                    | (table.newState[state] & 0xFFFF);
        }
        return packed;
    }

    static int decodeRawBlock(Object inputBase, long inputAddress, int blockSize, Object outputBase, long outputAddress, long outputLimit)
    {
        verify(outputAddress + blockSize <= outputLimit, inputAddress, "Output buffer too small");

        copyMemory(inputBase, inputAddress, outputBase, outputAddress, blockSize);
        return blockSize;
    }

    static int decodeRleBlock(int size, Object inputBase, long inputAddress, Object outputBase, long outputAddress, long outputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        verify(outputAddress + size <= outputLimit, inputAddress, "Output buffer too small");

        long output = outputAddress;
        long value = unsafe.getByte(inputBase, inputAddress) & 0xFFL;

        int remaining = size;
        if (remaining >= SIZE_OF_LONG) {
            long packed = value
                    | (value << 8)
                    | (value << 16)
                    | (value << 24)
                    | (value << 32)
                    | (value << 40)
                    | (value << 48)
                    | (value << 56);

            do {
                if (split) {
                    unsafe.putInt(outputBase, output, (int) packed);
                    unsafe.putInt(outputBase, output + 4, (int) (packed >>> 32));
                }
                else {
                    unsafe.putLong(outputBase, output, packed);
                }
                output += SIZE_OF_LONG;
                remaining -= SIZE_OF_LONG;
            }
            while (remaining >= SIZE_OF_LONG);
        }

        for (int i = 0; i < remaining; i++) {
            unsafe.putByte(outputBase, output, (byte) value);
            output++;
        }

        return size;
    }

    int decodeCompressedBlock(
            Object inputBase,
            final long inputAddress,
            int blockSize,
            Object outputBase,
            long outputAddress,
            long outputLimit,
            int windowSize,
            long outputAbsoluteBaseAddress)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        long inputLimit = inputAddress + blockSize;
        long input = inputAddress;

        verify(blockSize <= MAX_BLOCK_SIZE, input, "Expected match length table to be present");
        verify(blockSize >= MIN_BLOCK_SIZE, input, "Compressed block size too small");

        // decode literals
        int literalsBlockType = unsafe.getByte(inputBase, input) & 0b11;

        switch (literalsBlockType) {
            case RAW_LITERALS_BLOCK: {
                input += decodeRawLiterals(inputBase, input, inputLimit);
                break;
            }
            case RLE_LITERALS_BLOCK: {
                input += decodeRleLiterals(inputBase, input, blockSize);
                break;
            }
            case TREELESS_LITERALS_BLOCK:
                verify(huffman.isLoaded(), input, "Dictionary is corrupted");
            case COMPRESSED_LITERALS_BLOCK: {
                input += decodeCompressedLiterals(inputBase, input, blockSize, literalsBlockType);
                break;
            }
            default:
                throw fail(input, "Invalid literals block encoding type");
        }

        verify(windowSize <= MAX_WINDOW_SIZE, input, "Window size too large (not yet supported)");

        return decompressSequences(
                inputBase, input, inputAddress + blockSize,
                outputBase, outputAddress, outputLimit,
                literalsBase, literalsAddress, literalsLimit,
                outputAbsoluteBaseAddress);
    }

    private int decompressSequences(
            final Object inputBase, final long inputAddress, final long inputLimit,
            final Object outputBase, final long outputAddress, final long outputLimit,
            final Object literalsBase, final long literalsAddress, final long literalsLimit,
            long outputAbsoluteBaseAddress)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final long arrayLongBaseOffset = ARRAY_LONG_BASE_OFFSET;
        // ARM/ART: peekBits / peekBitsFast / verify are written out in this method. ART's inliner stops
        // inlining into a method once it passes ~1024 IR instructions (kMaximumNumberOfTotalInstructions;
        // only callees of <= 3 instructions still get inlined), and this method is past that, so each
        // of them was a real call per decoded sequence / symbol. HotSpot inlined them, hence no x86 gap.
        final boolean split = SPLIT_LONGS;
        final long fastOutputLimit = outputLimit - SIZE_OF_LONG;
        final long fastMatchOutputLimit = fastOutputLimit - SIZE_OF_LONG;
        final long wildOutputLimit = outputLimit - 4 * SIZE_OF_LONG;

        long input = inputAddress;
        long output = outputAddress;

        long literalsInput = literalsAddress;

        int size = (int) (inputLimit - inputAddress);
        if (!(size >= MIN_SEQUENCES_SIZE)) {
            throw fail(input, "Not enough input bytes");
        }

        // decode header
        int sequenceCount = unsafe.getByte(inputBase, input++) & 0xFF;
        if (sequenceCount != 0) {
            if (sequenceCount == 255) {
                if (!(input + SIZE_OF_SHORT <= inputLimit)) {
                    throw fail(input, "Not enough input bytes");
                }
                sequenceCount = (unsafe.getShort(inputBase, input) & 0xFFFF) + LONG_NUMBER_OF_SEQUENCES;
                input += SIZE_OF_SHORT;
            }
            else if (sequenceCount > 127) {
                if (!(input < inputLimit)) {
                    throw fail(input, "Not enough input bytes");
                }
                sequenceCount = ((sequenceCount - 128) << 8) + (unsafe.getByte(inputBase, input++) & 0xFF);
            }

            if (!(input + SIZE_OF_INT <= inputLimit)) {
                throw fail(input, "Not enough input bytes");
            }

            byte type = unsafe.getByte(inputBase, input++);

            int literalsLengthType = (type & 0xFF) >>> 6;
            int offsetCodesType = (type >>> 4) & 0b11;
            int matchLengthType = (type >>> 2) & 0b11;

            input = computeLiteralsTable(literalsLengthType, inputBase, input, inputLimit);
            input = computeOffsetsTable(offsetCodesType, inputBase, input, inputLimit);
            input = computeMatchLengthTable(matchLengthType, inputBase, input, inputLimit);

            // decompress sequences
            long[] scratch = new long[2]; // one per block; per-sequence refills below are allocation-free
            int bitsConsumed = BitInputStream.initializeBits(inputBase, input, inputLimit, scratch);
            long bits = scratch[0];
            long currentAddress = scratch[1];

            FiniteStateEntropy.Table currentLiteralsLengthTable = this.currentLiteralsLengthTable;
            FiniteStateEntropy.Table currentOffsetCodesTable = this.currentOffsetCodesTable;
            FiniteStateEntropy.Table currentMatchLengthTable = this.currentMatchLengthTable;

            long literalsLengthState = (((bits << bitsConsumed) >>> 1) >>> (63 - currentLiteralsLengthTable.log2Size));
            bitsConsumed += currentLiteralsLengthTable.log2Size;

            long offsetCodesState = (((bits << bitsConsumed) >>> 1) >>> (63 - currentOffsetCodesTable.log2Size));
            bitsConsumed += currentOffsetCodesTable.log2Size;

            long matchLengthState = (((bits << bitsConsumed) >>> 1) >>> (63 - currentMatchLengthTable.log2Size));
            bitsConsumed += currentMatchLengthTable.log2Size;

            // ARM/ART: hoist the static code tables - a static array access is a barriered
            // reference load per use on ART; these were read up to 5x per decoded sequence
            // (the code tables are now folded into the packed entries below)
            // one packed entry per state (see literalsLengthPacked)
            long[] sequenceTables = this.sequenceTables;
            // (the tables are placed in sequenceTables when read, see computeLiteralsTable & co.)
            literalsLengthState = ((literalsLengthState + LITERALS_LENGTH_TABLE_START) << 3) + arrayLongBaseOffset;
            offsetCodesState = ((offsetCodesState + OFFSET_CODES_TABLE_START) << 3) + arrayLongBaseOffset;
            matchLengthState = ((matchLengthState + MATCH_LENGTH_TABLE_START) << 3) + arrayLongBaseOffset;

            // ARM/ART: the three repeat offsets live in locals for the whole block and are written
            // back once after the loop. As array elements they were re-read and re-written with
            // bounds checks on every sequence: each copy below is an Unsafe call, which ART treats
            // as possibly changing any memory, so it could not keep them in registers.
            int[] previousOffsets = this.previousOffsets;
            int repeatOffset0 = previousOffsets[0];
            int repeatOffset1 = previousOffsets[1];
            int repeatOffset2 = previousOffsets[2];

            // ARM/ART: the main loop has no calls, not even on rare paths. ART's register allocator spills a
            // value that is live across any call at its definition, so one helper call made the states,
            // bits and repeat offsets go through the stack on every sequence. A sequence too close to the
            // end of the output for wild copies is left (decoded) to the general loop below.
            int[] decrement32 = DEC_32_TABLE;
            int[] decrement64 = DEC_64_TABLE;
            boolean pending = false;
            int pendingLiteralsLength = 0;
            int pendingMatchLength = 0;
            int pendingOffset = 0;
            if (!split) {
                while (sequenceCount > 0) {
                    sequenceCount--;

                    // ARM/ART: BitInputStream.loadBits inlined here - removes a call and the long[]
                    // scratch round-trip per sequence, so bits/currentAddress/bitsConsumed stay in
                    // registers. Logic is identical to loadBits; LOAD_DONE was never read by this
                    // caller, and the overflow case breaks out before any state is used.
                    if (bitsConsumed > 64) {
                        if (!(sequenceCount == 0)) {
                            throw fail(input, "Not all sequences were consumed");
                        }
                        break;
                    }
                    if (currentAddress >= input + SIZE_OF_LONG) {
                        // common case, >= 8 bytes left: like native BIT_reloadDStream, reload unconditionally
                        // (a reload of 0 bytes reads the same word again)
                        currentAddress -= bitsConsumed >>> 3;
                        bits = unsafe.getLong(inputBase, currentAddress);
                        bitsConsumed &= 0b111;
                    }
                    else if (currentAddress != input) {
                        int refillBytes = bitsConsumed >>> 3;
                        if (currentAddress - refillBytes < input) {
                            refillBytes = (int) (currentAddress - input);
                            currentAddress = input;
                            bitsConsumed -= refillBytes * SIZE_OF_LONG;
                            bits = unsafe.getLong(inputBase, input);
                        }
                        else {
                            currentAddress -= refillBytes;
                            bitsConsumed -= refillBytes * SIZE_OF_LONG;
                            bits = unsafe.getLong(inputBase, currentAddress);
                        }
                    }

                    // decode sequence
                    // one packed entry per table (see literalsLengthPacked)
                    long literalsLengthEntry = unsafe.getLong(sequenceTables, literalsLengthState);
                    long matchLengthEntry = unsafe.getLong(sequenceTables, matchLengthState);
                    long offsetCodesEntry = unsafe.getLong(sequenceTables, offsetCodesState);

                    int literalsLengthBits = (int) literalsLengthEntry >>> 24;
                    int matchLengthBits = (int) matchLengthEntry >>> 24;
                    int offsetBits = (int) offsetCodesEntry >>> 24; // == offset code

                    int offset = (int) (offsetCodesEntry >>> 32);
                    if (offsetBits > 0) { // offset code > 0
                        offset += (int) ((bits << bitsConsumed) >>> (64 - offsetBits)); // offsetBits > 0: two shifts
                        bitsConsumed += offsetBits;
                    }

                    int literalsLengthBase = (int) (literalsLengthEntry >>> 32);
                    if (offsetBits <= 1) { // offset code <= 1
                        if (literalsLengthBase == 0) { // literals length code 0 is the only code with base 0
                            offset++;
                        }

                        if (offset != 0) {
                            int temp;
                            if (offset == 3) {
                                temp = repeatOffset0 - 1;
                            }
                            else {
                                temp = offset == 1 ? repeatOffset1 : repeatOffset2; // offset is 1 or 2 here
                            }

                            if (temp == 0) {
                                temp = 1;
                            }

                            if (offset != 1) {
                                repeatOffset2 = repeatOffset1;
                            }
                            repeatOffset1 = repeatOffset0;
                            repeatOffset0 = temp;

                            offset = temp;
                        }
                        else {
                            offset = repeatOffset0;
                        }
                    }
                    else {
                        repeatOffset2 = repeatOffset1;
                        repeatOffset1 = repeatOffset0;
                        repeatOffset0 = offset;
                    }

                    int matchLength = (int) (matchLengthEntry >>> 32);
                    if (matchLengthBits > 0) { // match length codes > 31 are the ones with extra bits
                        matchLength += (int) ((bits << bitsConsumed) >>> (64 - matchLengthBits)); // matchLengthBits > 0: two shifts
                        bitsConsumed += matchLengthBits;
                    }

                    int literalsLength = literalsLengthBase;
                    if (literalsLengthBits > 0) { // literals length codes > 15 are the ones with extra bits
                        literalsLength += (int) ((bits << bitsConsumed) >>> (64 - literalsLengthBits)); // literalsLengthBits > 0: two shifts
                        bitsConsumed += literalsLengthBits;
                    }

                    int totalBits = literalsLengthBits + matchLengthBits + offsetBits;
                    if (totalBits > 64 - 7 - (LITERAL_LENGTH_TABLE_LOG + MATCH_LENGTH_TABLE_LOG + OFFSET_TABLE_LOG)
                            && bitsConsumed <= 64 && currentAddress != input) {
                        // loadBits inlined (see above). Overflow / at-start both leave state untouched,
                        // which is exactly what the guard conditions above express.
                        int refillBytes = bitsConsumed >>> 3;
                        if (currentAddress >= input + SIZE_OF_LONG) {
                            if (refillBytes > 0) {
                                currentAddress -= refillBytes;
                                bits = unsafe.getLong(inputBase, currentAddress);
                            }
                            bitsConsumed &= 0b111;
                        }
                        else if (currentAddress - refillBytes < input) {
                            refillBytes = (int) (currentAddress - input);
                            currentAddress = input;
                            bitsConsumed -= refillBytes * SIZE_OF_LONG;
                            bits = unsafe.getLong(inputBase, input);
                        }
                        else {
                            currentAddress -= refillBytes;
                            bitsConsumed -= refillBytes * SIZE_OF_LONG;
                            bits = unsafe.getLong(inputBase, currentAddress);
                        }
                    }

                    int numberOfBits;

                    numberOfBits = ((int) literalsLengthEntry >>> 16) & 0xFF;
                    literalsLengthState = (literalsLengthEntry & 0xFFFF) + ((((bits << bitsConsumed) >>> 1) >>> (63 - numberOfBits)) << 3); // <= 9 bits
                    bitsConsumed += numberOfBits;

                    numberOfBits = ((int) matchLengthEntry >>> 16) & 0xFF;
                    matchLengthState = (matchLengthEntry & 0xFFFF) + ((((bits << bitsConsumed) >>> 1) >>> (63 - numberOfBits)) << 3); // <= 9 bits
                    bitsConsumed += numberOfBits;

                    numberOfBits = ((int) offsetCodesEntry >>> 16) & 0xFF;
                    offsetCodesState = (offsetCodesEntry & 0xFFFF) + ((((bits << bitsConsumed) >>> 1) >>> (63 - numberOfBits)) << 3); // <= 8 bits
                    bitsConsumed += numberOfBits;

                    final long literalOutputLimit = output + literalsLength;
                    final long matchOutputLimit = literalOutputLimit + matchLength;

                    long literalEnd = literalsInput + literalsLength;
                    if (!(literalEnd <= literalsLimit)) {
                        throw fail(input, "Input is corrupted");
                    }

                    long matchAddress = literalOutputLimit - offset;
                    if (!(matchAddress >= outputAbsoluteBaseAddress)) {
                        throw fail(input, "Input is corrupted");
                    }

                    if (matchOutputLimit > wildOutputLimit) {
                        // near the end of the output: the general loop below executes it
                        pendingLiteralsLength = literalsLength;
                        pendingMatchLength = matchLength;
                        pendingOffset = offset;
                        pending = true;
                        break;
                    }
                    // ---- begin inlined literal / match copies (no calls, see above) ----
                    {
                        long v = unsafe.getLong(literalsBase, literalsInput);
                        unsafe.putLong(outputBase, output, v);
                    }
                    {
                        long v = unsafe.getLong(literalsBase, literalsInput + SIZE_OF_LONG);
                        unsafe.putLong(outputBase, output + SIZE_OF_LONG, v);
                    }
                    if (literalsLength > 2 * SIZE_OF_LONG) {
                        long copyOutput = output + 2 * SIZE_OF_LONG;
                        long copyInput = literalsInput + 2 * SIZE_OF_LONG;
                        do {
                            {
                                long v = unsafe.getLong(literalsBase, copyInput);
                                unsafe.putLong(outputBase, copyOutput, v);
                            }
                            copyOutput += SIZE_OF_LONG;
                            copyInput += SIZE_OF_LONG;
                        }
                        while (copyOutput < literalOutputLimit);
                    }
                    output = literalOutputLimit;
                    if (offset >= 2 * SIZE_OF_LONG) {
                        {
                            long v = unsafe.getLong(outputBase, matchAddress);
                            unsafe.putLong(outputBase, output, v);
                        }
                        {
                            long v = unsafe.getLong(outputBase, matchAddress + SIZE_OF_LONG);
                            unsafe.putLong(outputBase, output + SIZE_OF_LONG, v);
                        }
                        if (matchLength > 2 * SIZE_OF_LONG) {
                            long copyOutput = output + 2 * SIZE_OF_LONG;
                            long copyInput = matchAddress + 2 * SIZE_OF_LONG;
                            do {
                                {
                                    long v = unsafe.getLong(outputBase, copyInput);
                                    unsafe.putLong(outputBase, copyOutput, v);
                                }
                                copyOutput += SIZE_OF_LONG;
                                copyInput += SIZE_OF_LONG;
                            }
                            while (copyOutput < matchOutputLimit);
                        }
                    }
                    else {
                        long copyInput = matchAddress;
                        long copyOutput = output;
                        if (offset < SIZE_OF_LONG) {
                            // the first 8 bytes repeat the first `offset` bytes (see copyMatchHead); after them the
                            // source is moved back so that it trails the output by a multiple of offset >= 8
                            long pattern = unsafe.getLong(outputBase, matchAddress);
                            int period = offset * Byte.SIZE;
                            pattern &= (1L << period) - 1;
                            pattern |= pattern << period;
                            if (offset < 4) {
                                pattern |= pattern << (period * 2);
                                if (offset < 2) {
                                    pattern |= pattern << (period * 4);
                                }
                            }
                            unsafe.putLong(outputBase, output, pattern);
                            copyInput += decrement32[offset] - decrement64[offset];
                            copyOutput += SIZE_OF_LONG;
                        }
                        while (copyOutput < matchOutputLimit) {
                            {
                                long v = unsafe.getLong(outputBase, copyInput);
                                unsafe.putLong(outputBase, copyOutput, v);
                            }
                            copyOutput += SIZE_OF_LONG;
                            copyInput += SIZE_OF_LONG;
                        }
                    }
                    // ---- end inlined literal / match copies ----
                    output = matchOutputLimit;
                    literalsInput = literalEnd;
                }
            }
            else {
                while (sequenceCount > 0) {
                    sequenceCount--;

                    // ARM/ART: BitInputStream.loadBits inlined here - removes a call and the long[]
                    // scratch round-trip per sequence, so bits/currentAddress/bitsConsumed stay in
                    // registers. Logic is identical to loadBits; LOAD_DONE was never read by this
                    // caller, and the overflow case breaks out before any state is used.
                    if (bitsConsumed > 64) {
                        if (!(sequenceCount == 0)) {
                            throw fail(input, "Not all sequences were consumed");
                        }
                        break;
                    }
                    if (currentAddress >= input + SIZE_OF_LONG) {
                        // common case, >= 8 bytes left: like native BIT_reloadDStream, reload unconditionally
                        // (a reload of 0 bytes reads the same word again)
                        currentAddress -= bitsConsumed >>> 3;
                        bits = (split ? ((unsafe.getInt(inputBase, currentAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, currentAddress + 4) << 32)) : unsafe.getLong(inputBase, currentAddress));
                        bitsConsumed &= 0b111;
                    }
                    else if (currentAddress != input) {
                        int refillBytes = bitsConsumed >>> 3;
                        if (currentAddress - refillBytes < input) {
                            refillBytes = (int) (currentAddress - input);
                            currentAddress = input;
                            bitsConsumed -= refillBytes * SIZE_OF_LONG;
                            bits = (split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input));
                        }
                        else {
                            currentAddress -= refillBytes;
                            bitsConsumed -= refillBytes * SIZE_OF_LONG;
                            bits = (split ? ((unsafe.getInt(inputBase, currentAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, currentAddress + 4) << 32)) : unsafe.getLong(inputBase, currentAddress));
                        }
                    }

                    // decode sequence
                    // one packed entry per table (see literalsLengthPacked)
                    long literalsLengthEntry = unsafe.getLong(sequenceTables, literalsLengthState);
                    long matchLengthEntry = unsafe.getLong(sequenceTables, matchLengthState);
                    long offsetCodesEntry = unsafe.getLong(sequenceTables, offsetCodesState);

                    int literalsLengthBits = (int) literalsLengthEntry >>> 24;
                    int matchLengthBits = (int) matchLengthEntry >>> 24;
                    int offsetBits = (int) offsetCodesEntry >>> 24; // == offset code

                    int offset = (int) (offsetCodesEntry >>> 32);
                    if (offsetBits > 0) { // offset code > 0
                        offset += (int) ((bits << bitsConsumed) >>> (64 - offsetBits)); // offsetBits > 0: two shifts
                        bitsConsumed += offsetBits;
                    }

                    int literalsLengthBase = (int) (literalsLengthEntry >>> 32);
                    if (offsetBits <= 1) { // offset code <= 1
                        if (literalsLengthBase == 0) { // literals length code 0 is the only code with base 0
                            offset++;
                        }

                        if (offset != 0) {
                            int temp;
                            if (offset == 3) {
                                temp = repeatOffset0 - 1;
                            }
                            else {
                                temp = offset == 1 ? repeatOffset1 : repeatOffset2; // offset is 1 or 2 here
                            }

                            if (temp == 0) {
                                temp = 1;
                            }

                            if (offset != 1) {
                                repeatOffset2 = repeatOffset1;
                            }
                            repeatOffset1 = repeatOffset0;
                            repeatOffset0 = temp;

                            offset = temp;
                        }
                        else {
                            offset = repeatOffset0;
                        }
                    }
                    else {
                        repeatOffset2 = repeatOffset1;
                        repeatOffset1 = repeatOffset0;
                        repeatOffset0 = offset;
                    }

                    int matchLength = (int) (matchLengthEntry >>> 32);
                    if (matchLengthBits > 0) { // match length codes > 31 are the ones with extra bits
                        matchLength += (int) ((bits << bitsConsumed) >>> (64 - matchLengthBits)); // matchLengthBits > 0: two shifts
                        bitsConsumed += matchLengthBits;
                    }

                    int literalsLength = literalsLengthBase;
                    if (literalsLengthBits > 0) { // literals length codes > 15 are the ones with extra bits
                        literalsLength += (int) ((bits << bitsConsumed) >>> (64 - literalsLengthBits)); // literalsLengthBits > 0: two shifts
                        bitsConsumed += literalsLengthBits;
                    }

                    int totalBits = literalsLengthBits + matchLengthBits + offsetBits;
                    if (totalBits > 64 - 7 - (LITERAL_LENGTH_TABLE_LOG + MATCH_LENGTH_TABLE_LOG + OFFSET_TABLE_LOG)
                            && bitsConsumed <= 64 && currentAddress != input) {
                        // loadBits inlined (see above). Overflow / at-start both leave state untouched,
                        // which is exactly what the guard conditions above express.
                        int refillBytes = bitsConsumed >>> 3;
                        if (currentAddress >= input + SIZE_OF_LONG) {
                            if (refillBytes > 0) {
                                currentAddress -= refillBytes;
                                bits = (split ? ((unsafe.getInt(inputBase, currentAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, currentAddress + 4) << 32)) : unsafe.getLong(inputBase, currentAddress));
                            }
                            bitsConsumed &= 0b111;
                        }
                        else if (currentAddress - refillBytes < input) {
                            refillBytes = (int) (currentAddress - input);
                            currentAddress = input;
                            bitsConsumed -= refillBytes * SIZE_OF_LONG;
                            bits = (split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input));
                        }
                        else {
                            currentAddress -= refillBytes;
                            bitsConsumed -= refillBytes * SIZE_OF_LONG;
                            bits = (split ? ((unsafe.getInt(inputBase, currentAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, currentAddress + 4) << 32)) : unsafe.getLong(inputBase, currentAddress));
                        }
                    }

                    int numberOfBits;

                    numberOfBits = ((int) literalsLengthEntry >>> 16) & 0xFF;
                    literalsLengthState = (literalsLengthEntry & 0xFFFF) + ((((bits << bitsConsumed) >>> 1) >>> (63 - numberOfBits)) << 3); // <= 9 bits
                    bitsConsumed += numberOfBits;

                    numberOfBits = ((int) matchLengthEntry >>> 16) & 0xFF;
                    matchLengthState = (matchLengthEntry & 0xFFFF) + ((((bits << bitsConsumed) >>> 1) >>> (63 - numberOfBits)) << 3); // <= 9 bits
                    bitsConsumed += numberOfBits;

                    numberOfBits = ((int) offsetCodesEntry >>> 16) & 0xFF;
                    offsetCodesState = (offsetCodesEntry & 0xFFFF) + ((((bits << bitsConsumed) >>> 1) >>> (63 - numberOfBits)) << 3); // <= 8 bits
                    bitsConsumed += numberOfBits;

                    final long literalOutputLimit = output + literalsLength;
                    final long matchOutputLimit = literalOutputLimit + matchLength;

                    long literalEnd = literalsInput + literalsLength;
                    if (!(literalEnd <= literalsLimit)) {
                        throw fail(input, "Input is corrupted");
                    }

                    long matchAddress = literalOutputLimit - offset;
                    if (!(matchAddress >= outputAbsoluteBaseAddress)) {
                        throw fail(input, "Input is corrupted");
                    }

                    if (matchOutputLimit > wildOutputLimit) {
                        // near the end of the output: the general loop below executes it
                        pendingLiteralsLength = literalsLength;
                        pendingMatchLength = matchLength;
                        pendingOffset = offset;
                        pending = true;
                        break;
                    }
                    // ---- begin inlined literal / match copies (no calls, see above) ----
                    {
                        long v = (split ? ((unsafe.getInt(literalsBase, literalsInput) & 0xFFFFFFFFL) | ((long) unsafe.getInt(literalsBase, literalsInput + 4) << 32)) : unsafe.getLong(literalsBase, literalsInput));
                        if (split) {
                            unsafe.putInt(outputBase, output, (int) v);
                            unsafe.putInt(outputBase, output + 4, (int) (v >>> 32));
                        }
                        else {
                            unsafe.putLong(outputBase, output, v);
                        }
                    }
                    {
                        long v = (split ? ((unsafe.getInt(literalsBase, literalsInput + SIZE_OF_LONG) & 0xFFFFFFFFL) | ((long) unsafe.getInt(literalsBase, literalsInput + SIZE_OF_LONG + 4) << 32)) : unsafe.getLong(literalsBase, literalsInput + SIZE_OF_LONG));
                        if (split) {
                            unsafe.putInt(outputBase, output + SIZE_OF_LONG, (int) v);
                            unsafe.putInt(outputBase, output + SIZE_OF_LONG + 4, (int) (v >>> 32));
                        }
                        else {
                            unsafe.putLong(outputBase, output + SIZE_OF_LONG, v);
                        }
                    }
                    if (literalsLength > 2 * SIZE_OF_LONG) {
                        long copyOutput = output + 2 * SIZE_OF_LONG;
                        long copyInput = literalsInput + 2 * SIZE_OF_LONG;
                        do {
                            {
                                long v = (split ? ((unsafe.getInt(literalsBase, copyInput) & 0xFFFFFFFFL) | ((long) unsafe.getInt(literalsBase, copyInput + 4) << 32)) : unsafe.getLong(literalsBase, copyInput));
                                if (split) {
                                    unsafe.putInt(outputBase, copyOutput, (int) v);
                                    unsafe.putInt(outputBase, copyOutput + 4, (int) (v >>> 32));
                                }
                                else {
                                    unsafe.putLong(outputBase, copyOutput, v);
                                }
                            }
                            copyOutput += SIZE_OF_LONG;
                            copyInput += SIZE_OF_LONG;
                        }
                        while (copyOutput < literalOutputLimit);
                    }
                    output = literalOutputLimit;
                    if (offset >= 2 * SIZE_OF_LONG) {
                        {
                            long v = (split ? ((unsafe.getInt(outputBase, matchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + 4) << 32)) : unsafe.getLong(outputBase, matchAddress));
                            if (split) {
                                unsafe.putInt(outputBase, output, (int) v);
                                unsafe.putInt(outputBase, output + 4, (int) (v >>> 32));
                            }
                            else {
                                unsafe.putLong(outputBase, output, v);
                            }
                        }
                        {
                            long v = (split ? ((unsafe.getInt(outputBase, matchAddress + SIZE_OF_LONG) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + SIZE_OF_LONG + 4) << 32)) : unsafe.getLong(outputBase, matchAddress + SIZE_OF_LONG));
                            if (split) {
                                unsafe.putInt(outputBase, output + SIZE_OF_LONG, (int) v);
                                unsafe.putInt(outputBase, output + SIZE_OF_LONG + 4, (int) (v >>> 32));
                            }
                            else {
                                unsafe.putLong(outputBase, output + SIZE_OF_LONG, v);
                            }
                        }
                        if (matchLength > 2 * SIZE_OF_LONG) {
                            long copyOutput = output + 2 * SIZE_OF_LONG;
                            long copyInput = matchAddress + 2 * SIZE_OF_LONG;
                            do {
                                {
                                    long v = (split ? ((unsafe.getInt(outputBase, copyInput) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, copyInput + 4) << 32)) : unsafe.getLong(outputBase, copyInput));
                                    if (split) {
                                        unsafe.putInt(outputBase, copyOutput, (int) v);
                                        unsafe.putInt(outputBase, copyOutput + 4, (int) (v >>> 32));
                                    }
                                    else {
                                        unsafe.putLong(outputBase, copyOutput, v);
                                    }
                                }
                                copyOutput += SIZE_OF_LONG;
                                copyInput += SIZE_OF_LONG;
                            }
                            while (copyOutput < matchOutputLimit);
                        }
                    }
                    else {
                        long copyInput = matchAddress;
                        long copyOutput = output;
                        if (offset < SIZE_OF_LONG) {
                            // the first 8 bytes repeat the first `offset` bytes (see copyMatchHead); after them the
                            // source is moved back so that it trails the output by a multiple of offset >= 8
                            long pattern = (split ? ((unsafe.getInt(outputBase, matchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + 4) << 32)) : unsafe.getLong(outputBase, matchAddress));
                            int period = offset * Byte.SIZE;
                            pattern &= (1L << period) - 1;
                            pattern |= pattern << period;
                            if (offset < 4) {
                                pattern |= pattern << (period * 2);
                                if (offset < 2) {
                                    pattern |= pattern << (period * 4);
                                }
                            }
                            if (split) {
                                unsafe.putInt(outputBase, output, (int) pattern);
                                unsafe.putInt(outputBase, output + 4, (int) (pattern >>> 32));
                            }
                            else {
                                unsafe.putLong(outputBase, output, pattern);
                            }
                            copyInput += decrement32[offset] - decrement64[offset];
                            copyOutput += SIZE_OF_LONG;
                        }
                        while (copyOutput < matchOutputLimit) {
                            {
                                long v = (split ? ((unsafe.getInt(outputBase, copyInput) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, copyInput + 4) << 32)) : unsafe.getLong(outputBase, copyInput));
                                if (split) {
                                    unsafe.putInt(outputBase, copyOutput, (int) v);
                                    unsafe.putInt(outputBase, copyOutput + 4, (int) (v >>> 32));
                                }
                                else {
                                    unsafe.putLong(outputBase, copyOutput, v);
                                }
                            }
                            copyOutput += SIZE_OF_LONG;
                            copyInput += SIZE_OF_LONG;
                        }
                    }
                    // ---- end inlined literal / match copies ----
                    output = matchOutputLimit;
                    literalsInput = literalEnd;
                }
            }

            previousOffsets[0] = repeatOffset0;
            previousOffsets[1] = repeatOffset1;
            previousOffsets[2] = repeatOffset2;
            if (pending || sequenceCount > 0) {
                // ARM/ART: in its own method, so that its calls don't make ART spill the loop above
                output = decompressRemainingSequences(inputBase, input, currentAddress, bits, bitsConsumed,
                        literalsLengthState, offsetCodesState, matchLengthState, sequenceTables,
                        repeatOffset0, repeatOffset1, repeatOffset2, sequenceCount,
                        pending, pendingLiteralsLength, pendingMatchLength, pendingOffset,
                        outputBase, output, outputLimit, literalsBase, literalsInput, literalsLimit, outputAbsoluteBaseAddress);
                literalsInput = remainingLiteralsInput;
            }
        }

        // last literal segment
        output = copyLastLiteral(input, literalsBase, literalsInput, literalsLimit, outputBase, output, outputLimit);

        return (int) (output - outputAddress);
    }

    // the rest of a block's sequences once one ends too near the end of the output for wild copies (see
    // decompressSequences); writes back the repeat offsets and remainingLiteralsInput, returns the output
    private long decompressRemainingSequences(Object inputBase, long input, long currentAddress, long bits, int bitsConsumed,
            long literalsLengthState, long offsetCodesState, long matchLengthState, long[] sequenceTables,
            int repeatOffset0, int repeatOffset1, int repeatOffset2, int sequenceCount,
            boolean pending, int pendingLiteralsLength, int pendingMatchLength, int pendingOffset,
            Object outputBase, long output, long outputLimit, Object literalsBase, long literalsInput, long literalsLimit, long outputAbsoluteBaseAddress)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        final long fastOutputLimit = outputLimit - SIZE_OF_LONG;
        final long fastMatchOutputLimit = fastOutputLimit - SIZE_OF_LONG;
        final long wildOutputLimit = outputLimit - 4 * SIZE_OF_LONG;
        while (pending || sequenceCount > 0) {
            int offset;
            int matchLength;
            int literalsLength;
            if (pending) {
                pending = false;
                offset = pendingOffset;
                matchLength = pendingMatchLength;
                literalsLength = pendingLiteralsLength;
            }
            else {
                sequenceCount--;

                // ARM/ART: BitInputStream.loadBits inlined here - removes a call and the long[]
                // scratch round-trip per sequence, so bits/currentAddress/bitsConsumed stay in
                // registers. Logic is identical to loadBits; LOAD_DONE was never read by this
                // caller, and the overflow case breaks out before any state is used.
                if (bitsConsumed > 64) {
                    if (!(sequenceCount == 0)) {
                        throw fail(input, "Not all sequences were consumed");
                    }
                    break;
                }
                if (currentAddress >= input + SIZE_OF_LONG) {
                    // common case, >= 8 bytes left: like native BIT_reloadDStream, reload unconditionally
                    // (a reload of 0 bytes reads the same word again)
                    currentAddress -= bitsConsumed >>> 3;
                    bits = (split ? ((unsafe.getInt(inputBase, currentAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, currentAddress + 4) << 32)) : unsafe.getLong(inputBase, currentAddress));
                    bitsConsumed &= 0b111;
                }
                else if (currentAddress != input) {
                    int refillBytes = bitsConsumed >>> 3;
                    if (currentAddress - refillBytes < input) {
                        refillBytes = (int) (currentAddress - input);
                        currentAddress = input;
                        bitsConsumed -= refillBytes * SIZE_OF_LONG;
                        bits = (split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input));
                    }
                    else {
                        currentAddress -= refillBytes;
                        bitsConsumed -= refillBytes * SIZE_OF_LONG;
                        bits = (split ? ((unsafe.getInt(inputBase, currentAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, currentAddress + 4) << 32)) : unsafe.getLong(inputBase, currentAddress));
                    }
                }

                // decode sequence
                // one packed entry per table (see literalsLengthPacked)
                long literalsLengthEntry = unsafe.getLong(sequenceTables, literalsLengthState);
                long matchLengthEntry = unsafe.getLong(sequenceTables, matchLengthState);
                long offsetCodesEntry = unsafe.getLong(sequenceTables, offsetCodesState);

                int literalsLengthBits = (int) literalsLengthEntry >>> 24;
                int matchLengthBits = (int) matchLengthEntry >>> 24;
                int offsetBits = (int) offsetCodesEntry >>> 24; // == offset code

                offset = (int) (offsetCodesEntry >>> 32);
                if (offsetBits > 0) { // offset code > 0
                    offset += (int) ((bits << bitsConsumed) >>> (64 - offsetBits)); // offsetBits > 0: two shifts
                    bitsConsumed += offsetBits;
                }

                int literalsLengthBase = (int) (literalsLengthEntry >>> 32);
                if (offsetBits <= 1) { // offset code <= 1
                    if (literalsLengthBase == 0) { // literals length code 0 is the only code with base 0
                        offset++;
                    }

                    if (offset != 0) {
                        int temp;
                        if (offset == 3) {
                            temp = repeatOffset0 - 1;
                        }
                        else {
                            temp = offset == 1 ? repeatOffset1 : repeatOffset2; // offset is 1 or 2 here
                        }

                        if (temp == 0) {
                            temp = 1;
                        }

                        if (offset != 1) {
                            repeatOffset2 = repeatOffset1;
                        }
                        repeatOffset1 = repeatOffset0;
                        repeatOffset0 = temp;

                        offset = temp;
                    }
                    else {
                        offset = repeatOffset0;
                    }
                }
                else {
                    repeatOffset2 = repeatOffset1;
                    repeatOffset1 = repeatOffset0;
                    repeatOffset0 = offset;
                }

                matchLength = (int) (matchLengthEntry >>> 32);
                if (matchLengthBits > 0) { // match length codes > 31 are the ones with extra bits
                    matchLength += (int) ((bits << bitsConsumed) >>> (64 - matchLengthBits)); // matchLengthBits > 0: two shifts
                    bitsConsumed += matchLengthBits;
                }

                literalsLength = literalsLengthBase;
                if (literalsLengthBits > 0) { // literals length codes > 15 are the ones with extra bits
                    literalsLength += (int) ((bits << bitsConsumed) >>> (64 - literalsLengthBits)); // literalsLengthBits > 0: two shifts
                    bitsConsumed += literalsLengthBits;
                }

                int totalBits = literalsLengthBits + matchLengthBits + offsetBits;
                if (totalBits > 64 - 7 - (LITERAL_LENGTH_TABLE_LOG + MATCH_LENGTH_TABLE_LOG + OFFSET_TABLE_LOG)
                        && bitsConsumed <= 64 && currentAddress != input) {
                    // loadBits inlined (see above). Overflow / at-start both leave state untouched,
                    // which is exactly what the guard conditions above express.
                    int refillBytes = bitsConsumed >>> 3;
                    if (currentAddress >= input + SIZE_OF_LONG) {
                        if (refillBytes > 0) {
                            currentAddress -= refillBytes;
                            bits = (split ? ((unsafe.getInt(inputBase, currentAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, currentAddress + 4) << 32)) : unsafe.getLong(inputBase, currentAddress));
                        }
                        bitsConsumed &= 0b111;
                    }
                    else if (currentAddress - refillBytes < input) {
                        refillBytes = (int) (currentAddress - input);
                        currentAddress = input;
                        bitsConsumed -= refillBytes * SIZE_OF_LONG;
                        bits = (split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input));
                    }
                    else {
                        currentAddress -= refillBytes;
                        bitsConsumed -= refillBytes * SIZE_OF_LONG;
                        bits = (split ? ((unsafe.getInt(inputBase, currentAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, currentAddress + 4) << 32)) : unsafe.getLong(inputBase, currentAddress));
                    }
                }

                int numberOfBits;

                numberOfBits = ((int) literalsLengthEntry >>> 16) & 0xFF;
                literalsLengthState = (literalsLengthEntry & 0xFFFF) + ((((bits << bitsConsumed) >>> 1) >>> (63 - numberOfBits)) << 3); // <= 9 bits
                bitsConsumed += numberOfBits;

                numberOfBits = ((int) matchLengthEntry >>> 16) & 0xFF;
                matchLengthState = (matchLengthEntry & 0xFFFF) + ((((bits << bitsConsumed) >>> 1) >>> (63 - numberOfBits)) << 3); // <= 9 bits
                bitsConsumed += numberOfBits;

                numberOfBits = ((int) offsetCodesEntry >>> 16) & 0xFF;
                offsetCodesState = (offsetCodesEntry & 0xFFFF) + ((((bits << bitsConsumed) >>> 1) >>> (63 - numberOfBits)) << 3); // <= 8 bits
                bitsConsumed += numberOfBits;
            }
            final long literalOutputLimit = output + literalsLength;
            final long matchOutputLimit = literalOutputLimit + matchLength;

            long literalEnd = literalsInput + literalsLength;
            if (!(literalEnd <= literalsLimit)) {
                throw fail(input, "Input is corrupted");
            }

            long matchAddress = literalOutputLimit - offset;
            if (!(matchAddress >= outputAbsoluteBaseAddress)) {
                throw fail(input, "Input is corrupted");
            }

            if (matchOutputLimit <= wildOutputLimit) {
                // ---- begin inlined literal / match copies (common case) ----
                // Like native zstd's ZSTD_execSequence: one limit check (matchOutputLimit + 32 <=
                // outputLimit) covers every over-copy below, so literals of <= 16 bytes and matches of
                // <= 16 bytes at an offset >= 16 take no further bounds checks and no helper call.
                // The second literal word is only copied when needed (it costs 2 extra Unsafe
                // accesses per word on ARM32).
                {
                    long v = (split ? ((unsafe.getInt(literalsBase, literalsInput) & 0xFFFFFFFFL) | ((long) unsafe.getInt(literalsBase, literalsInput + 4) << 32)) : unsafe.getLong(literalsBase, literalsInput));
                    if (split) {
                        unsafe.putInt(outputBase, output, (int) v);
                        unsafe.putInt(outputBase, output + 4, (int) (v >>> 32));
                    }
                    else {
                        unsafe.putLong(outputBase, output, v);
                    }
                }
                if (literalsLength > SIZE_OF_LONG) {
                    {
                        long v = (split ? ((unsafe.getInt(literalsBase, literalsInput + SIZE_OF_LONG) & 0xFFFFFFFFL) | ((long) unsafe.getInt(literalsBase, literalsInput + SIZE_OF_LONG + 4) << 32)) : unsafe.getLong(literalsBase, literalsInput + SIZE_OF_LONG));
                        if (split) {
                            unsafe.putInt(outputBase, output + SIZE_OF_LONG, (int) v);
                            unsafe.putInt(outputBase, output + SIZE_OF_LONG + 4, (int) (v >>> 32));
                        }
                        else {
                            unsafe.putLong(outputBase, output + SIZE_OF_LONG, v);
                        }
                    }
                    if (literalsLength > 2 * SIZE_OF_LONG) {
                        copyLiterals(outputBase, literalsBase, output + 2 * SIZE_OF_LONG, literalsInput + 2 * SIZE_OF_LONG, literalOutputLimit);
                    }
                }
                output = literalOutputLimit;
                if (offset >= 2 * SIZE_OF_LONG) {
                    {
                        long v = (split ? ((unsafe.getInt(outputBase, matchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + 4) << 32)) : unsafe.getLong(outputBase, matchAddress));
                        if (split) {
                            unsafe.putInt(outputBase, output, (int) v);
                            unsafe.putInt(outputBase, output + 4, (int) (v >>> 32));
                        }
                        else {
                            unsafe.putLong(outputBase, output, v);
                        }
                    }
                    {
                        long v = (split ? ((unsafe.getInt(outputBase, matchAddress + SIZE_OF_LONG) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + SIZE_OF_LONG + 4) << 32)) : unsafe.getLong(outputBase, matchAddress + SIZE_OF_LONG));
                        if (split) {
                            unsafe.putInt(outputBase, output + SIZE_OF_LONG, (int) v);
                            unsafe.putInt(outputBase, output + SIZE_OF_LONG + 4, (int) (v >>> 32));
                        }
                        else {
                            unsafe.putLong(outputBase, output + SIZE_OF_LONG, v);
                        }
                    }
                    if (matchLength > 2 * SIZE_OF_LONG) {
                        copyMatchTail(outputBase, fastOutputLimit, output + 2 * SIZE_OF_LONG, matchOutputLimit, matchAddress + 2 * SIZE_OF_LONG, matchLength - 2 * SIZE_OF_LONG, fastMatchOutputLimit);
                    }
                }
                else {
                    long tailAddress = copyMatchHead(outputBase, output, offset, matchAddress);
                    copyMatchTail(outputBase, fastOutputLimit, output + SIZE_OF_LONG, matchOutputLimit, tailAddress, matchLength - SIZE_OF_LONG, fastMatchOutputLimit);
                }
                // ---- end inlined literal / match copies ----
            }
            else if (!(matchOutputLimit <= outputLimit)) {
                throw fail(input, "Output buffer too small");
            }
            else if (literalOutputLimit > fastOutputLimit) {
                executeLastSequence(outputBase, output, literalOutputLimit, matchOutputLimit, fastOutputLimit, literalsInput, matchAddress);
            }
            else {
                // copy literals. literalOutputLimit <= fastOutputLimit, so we can copy
                // long at a time with over-copy
                // ---- begin inlined copyLiterals / copyMatchHead / copyMatchTail (common case) ----
                // ARM/ART: the three helpers contain loops and are too big for ART to inline, so
                // they were three real calls per sequence. Most sequences have <= 8 literal bytes,
                // a match offset >= 8 and a match of <= 16 bytes: those take three 8-byte copies
                // here. Anything longer or closer continues in the helpers. Same bytes written.
                long literalsValue = (split ? ((unsafe.getInt(literalsBase, literalsInput) & 0xFFFFFFFFL) | ((long) unsafe.getInt(literalsBase, literalsInput + 4) << 32)) : unsafe.getLong(literalsBase, literalsInput));
                if (split) {
                    unsafe.putInt(outputBase, output, (int) literalsValue);
                    unsafe.putInt(outputBase, output + 4, (int) (literalsValue >>> 32));
                }
                else {
                    unsafe.putLong(outputBase, output, literalsValue);
                }
                if (output + SIZE_OF_LONG < literalOutputLimit) {
                    copyLiterals(outputBase, literalsBase, output + SIZE_OF_LONG, literalsInput + SIZE_OF_LONG, literalOutputLimit);
                }
                output = literalOutputLimit;

                long tailAddress;
                if (offset >= SIZE_OF_LONG) {
                    long matchValue = (split ? ((unsafe.getInt(outputBase, matchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + 4) << 32)) : unsafe.getLong(outputBase, matchAddress));
                    if (split) {
                        unsafe.putInt(outputBase, output, (int) matchValue);
                        unsafe.putInt(outputBase, output + 4, (int) (matchValue >>> 32));
                    }
                    else {
                        unsafe.putLong(outputBase, output, matchValue);
                    }
                    tailAddress = matchAddress + SIZE_OF_LONG;
                }
                else {
                    tailAddress = copyMatchHead(outputBase, output, offset, matchAddress);
                }

                if (matchLength <= 2 * SIZE_OF_LONG && matchOutputLimit < fastMatchOutputLimit) {
                    long tailValue = (split ? ((unsafe.getInt(outputBase, tailAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, tailAddress + 4) << 32)) : unsafe.getLong(outputBase, tailAddress));
                    if (split) {
                        unsafe.putInt(outputBase, output + SIZE_OF_LONG, (int) tailValue);
                        unsafe.putInt(outputBase, output + SIZE_OF_LONG + 4, (int) (tailValue >>> 32));
                    }
                    else {
                        unsafe.putLong(outputBase, output + SIZE_OF_LONG, tailValue);
                    }
                }
                else {
                    copyMatchTail(outputBase, fastOutputLimit, output + SIZE_OF_LONG, matchOutputLimit, tailAddress, matchLength - SIZE_OF_LONG, fastMatchOutputLimit);
                }
                // ---- end inlined copyLiterals / copyMatchHead / copyMatchTail ----
            }
            output = matchOutputLimit;
            literalsInput = literalEnd;
        }
        int[] previousOffsets = this.previousOffsets;
        previousOffsets[0] = repeatOffset0;
        previousOffsets[1] = repeatOffset1;
        previousOffsets[2] = repeatOffset2;
        remainingLiteralsInput = literalsInput;
        return output;
    }

    private static long copyLastLiteral(long input, Object literalsBase, long literalsInput, long literalsLimit, Object outputBase, long output, long outputLimit)
    {
        long lastLiteralsSize = literalsLimit - literalsInput;
        verify(output + lastLiteralsSize <= outputLimit, input, "Output buffer too small");
        copyMemory(literalsBase, literalsInput, outputBase, output, lastLiteralsSize);
        output += lastLiteralsSize;
        return output;
    }

    private static void copyMatchTail(Object outputBase, long fastOutputLimit, long output, long matchOutputLimit, long matchAddress, int matchLength, long fastMatchOutputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        // fastMatchOutputLimit is just fastOutputLimit - SIZE_OF_LONG. It needs to be passed in so that it can be computed once for the
        // whole invocation to decompressSequences. Otherwise, we'd just compute it here.
        // If matchOutputLimit is < fastMatchOutputLimit, we know that even after the head (8 bytes) has been copied, the output pointer
        // will be within fastOutputLimit, so it's safe to copy blindly before checking the limit condition
        if (matchOutputLimit < fastMatchOutputLimit) {
            int copied = 0;
            do {
                if (split) {
                    long splitValue = ((unsafe.getInt(outputBase, matchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + 4) << 32));
                    unsafe.putInt(outputBase, output, (int) splitValue);
                    unsafe.putInt(outputBase, output + 4, (int) (splitValue >>> 32));
                }
                else {
                    unsafe.putLong(outputBase, output, unsafe.getLong(outputBase, matchAddress));
                }
                output += SIZE_OF_LONG;
                matchAddress += SIZE_OF_LONG;
                copied += SIZE_OF_LONG;
            }
            while (copied < matchLength);
        }
        else {
            while (output < fastOutputLimit) {
                if (split) {
                    long splitValue = ((unsafe.getInt(outputBase, matchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + 4) << 32));
                    unsafe.putInt(outputBase, output, (int) splitValue);
                    unsafe.putInt(outputBase, output + 4, (int) (splitValue >>> 32));
                }
                else {
                    unsafe.putLong(outputBase, output, unsafe.getLong(outputBase, matchAddress));
                }
                matchAddress += SIZE_OF_LONG;
                output += SIZE_OF_LONG;
            }

            while (output < matchOutputLimit) {
                unsafe.putByte(outputBase, output++, unsafe.getByte(outputBase, matchAddress++));
            }
        }
    }

    private static long copyMatchHead(Object outputBase, long output, int offset, long matchAddress)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        // copy match
        if (offset < 8) {
            // 8 bytes apart so that we can copy long-at-a-time below
            // ARM/ART: the 8 output bytes repeat the first `offset` bytes; build that pattern in a
            // register and write it once, instead of 4 getByte/putByte pairs (JNI calls on ART
            // builds that don't intrinsify them). Reading 8 bytes at matchAddress stays below
            // output + 8, which the caller guarantees is writable.
            long pattern = (split ? ((unsafe.getInt(outputBase, matchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + 4) << 32)) : unsafe.getLong(outputBase, matchAddress));
            int period = offset * Byte.SIZE;
            pattern &= (1L << period) - 1;
            pattern |= pattern << period;              // 2 * offset bytes: enough for offset >= 4
            if (offset < 4) {
                pattern |= pattern << (period * 2);    // 4 * offset: enough for offset 2..3
                if (offset < 2) {
                    pattern |= pattern << (period * 4); // offset 1
                }
            }
            if (split) {
                unsafe.putInt(outputBase, output, (int) pattern);
                unsafe.putInt(outputBase, output + 4, (int) (pattern >>> 32));
            }
            else {
                unsafe.putLong(outputBase, output, pattern);
            }
            matchAddress += DEC_32_TABLE[offset] - DEC_64_TABLE[offset];
        }
        else {
            if (split) {
                long splitValue = ((unsafe.getInt(outputBase, matchAddress) & 0xFFFFFFFFL) | ((long) unsafe.getInt(outputBase, matchAddress + 4) << 32));
                unsafe.putInt(outputBase, output, (int) splitValue);
                unsafe.putInt(outputBase, output + 4, (int) (splitValue >>> 32));
            }
            else {
                unsafe.putLong(outputBase, output, unsafe.getLong(outputBase, matchAddress));
            }
            matchAddress += SIZE_OF_LONG;
        }
        return matchAddress;
    }

    private static long copyLiterals(Object outputBase, Object literalsBase, long output, long literalsInput, long literalOutputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        long literalInput = literalsInput;
        do {
            if (split) {
                long splitValue = ((unsafe.getInt(literalsBase, literalInput) & 0xFFFFFFFFL) | ((long) unsafe.getInt(literalsBase, literalInput + 4) << 32));
                unsafe.putInt(outputBase, output, (int) splitValue);
                unsafe.putInt(outputBase, output + 4, (int) (splitValue >>> 32));
            }
            else {
                unsafe.putLong(outputBase, output, unsafe.getLong(literalsBase, literalInput));
            }
            output += SIZE_OF_LONG;
            literalInput += SIZE_OF_LONG;
        }
        while (output < literalOutputLimit);
        output = literalOutputLimit; // correction in case we over-copied
        return output;
    }

    private long computeMatchLengthTable(int matchLengthType, Object inputBase, long input, long inputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        switch (matchLengthType) {
            case SEQUENCE_ENCODING_RLE:
                verify(input < inputLimit, input, "Not enough input bytes");

                byte value = unsafe.getByte(inputBase, input++);
                verify(value <= MAX_MATCH_LENGTH_SYMBOL, input, "Value exceeds expected maximum value");

                FseTableReader.initializeRleTable(matchLengthTable, value);
                currentMatchLengthTable = matchLengthTable;
                currentMatchLengthPacked = pack(matchLengthTable, MATCH_LENGTH_BASE, MATCH_LENGTH_BITS, matchLengthPacked);
                place(sequenceTables, currentMatchLengthPacked, 0, MATCH_LENGTH_TABLE_START);
                break;
            case SEQUENCE_ENCODING_BASIC:
                currentMatchLengthTable = DEFAULT_MATCH_LENGTH_TABLE;
                currentMatchLengthPacked = DEFAULT_MATCH_LENGTH_PACKED;
                place(sequenceTables, currentMatchLengthPacked, DEFAULT_MATCH_LENGTH_TABLE.log2Size, MATCH_LENGTH_TABLE_START);
                break;
            case SEQUENCE_ENCODING_REPEAT:
                verify(currentMatchLengthTable != null, input, "Expected match length table to be present");
                break;
            case SEQUENCE_ENCODING_COMPRESSED:
                input += fse.readFseTable(matchLengthTable, sequenceTables, MATCH_LENGTH_TABLE_START, MATCH_LENGTH_BASE, MATCH_LENGTH_BITS, inputBase, input, inputLimit, MAX_MATCH_LENGTH_SYMBOL, MATCH_LENGTH_TABLE_LOG);
                currentMatchLengthTable = matchLengthTable;
                currentMatchLengthPacked = matchLengthPacked;
                break;
            default:
                throw fail(input, "Invalid match length encoding type");
        }
        return input;
    }

    private long computeOffsetsTable(int offsetCodesType, Object inputBase, long input, long inputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        switch (offsetCodesType) {
            case SEQUENCE_ENCODING_RLE:
                verify(input < inputLimit, input, "Not enough input bytes");

                byte value = unsafe.getByte(inputBase, input++);
                verify(value <= DEFAULT_MAX_OFFSET_CODE_SYMBOL, input, "Value exceeds expected maximum value");

                FseTableReader.initializeRleTable(offsetCodesTable, value);
                currentOffsetCodesTable = offsetCodesTable;
                currentOffsetCodesPacked = pack(offsetCodesTable, OFFSET_CODES_BASE, null, offsetCodesPacked);
                place(sequenceTables, currentOffsetCodesPacked, 0, OFFSET_CODES_TABLE_START);
                break;
            case SEQUENCE_ENCODING_BASIC:
                currentOffsetCodesTable = DEFAULT_OFFSET_CODES_TABLE;
                currentOffsetCodesPacked = DEFAULT_OFFSET_CODES_PACKED;
                place(sequenceTables, currentOffsetCodesPacked, DEFAULT_OFFSET_CODES_TABLE.log2Size, OFFSET_CODES_TABLE_START);
                break;
            case SEQUENCE_ENCODING_REPEAT:
                verify(currentOffsetCodesTable != null, input, "Expected match length table to be present");
                break;
            case SEQUENCE_ENCODING_COMPRESSED:
                input += fse.readFseTable(offsetCodesTable, sequenceTables, OFFSET_CODES_TABLE_START, OFFSET_CODES_BASE, OFFSET_CODES_BITS, inputBase, input, inputLimit, DEFAULT_MAX_OFFSET_CODE_SYMBOL, OFFSET_TABLE_LOG);
                currentOffsetCodesTable = offsetCodesTable;
                currentOffsetCodesPacked = offsetCodesPacked;
                break;
            default:
                throw fail(input, "Invalid offset code encoding type");
        }
        return input;
    }

    private long computeLiteralsTable(int literalsLengthType, Object inputBase, long input, long inputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        switch (literalsLengthType) {
            case SEQUENCE_ENCODING_RLE:
                verify(input < inputLimit, input, "Not enough input bytes");

                byte value = unsafe.getByte(inputBase, input++);
                verify(value <= MAX_LITERALS_LENGTH_SYMBOL, input, "Value exceeds expected maximum value");

                FseTableReader.initializeRleTable(literalsLengthTable, value);
                currentLiteralsLengthTable = literalsLengthTable;
                currentLiteralsLengthPacked = pack(literalsLengthTable, LITERALS_LENGTH_BASE, LITERALS_LENGTH_BITS, literalsLengthPacked);
                place(sequenceTables, currentLiteralsLengthPacked, 0, LITERALS_LENGTH_TABLE_START);
                break;
            case SEQUENCE_ENCODING_BASIC:
                currentLiteralsLengthTable = DEFAULT_LITERALS_LENGTH_TABLE;
                currentLiteralsLengthPacked = DEFAULT_LITERALS_LENGTH_PACKED;
                place(sequenceTables, currentLiteralsLengthPacked, DEFAULT_LITERALS_LENGTH_TABLE.log2Size, LITERALS_LENGTH_TABLE_START);
                break;
            case SEQUENCE_ENCODING_REPEAT:
                verify(currentLiteralsLengthTable != null, input, "Expected match length table to be present");
                break;
            case SEQUENCE_ENCODING_COMPRESSED:
                input += fse.readFseTable(literalsLengthTable, sequenceTables, LITERALS_LENGTH_TABLE_START, LITERALS_LENGTH_BASE, LITERALS_LENGTH_BITS, inputBase, input, inputLimit, MAX_LITERALS_LENGTH_SYMBOL, LITERAL_LENGTH_TABLE_LOG);
                currentLiteralsLengthTable = literalsLengthTable;
                currentLiteralsLengthPacked = literalsLengthPacked;
                break;
            default:
                throw fail(input, "Invalid literals length encoding type");
        }
        return input;
    }

    private void executeLastSequence(Object outputBase, long output, long literalOutputLimit, long matchOutputLimit, long fastOutputLimit, long literalInput, long matchAddress)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        // copy literals
        if (output < fastOutputLimit) {
            // wild copy
            do {
                if (split) {
                    long splitValue = ((unsafe.getInt(literalsBase, literalInput) & 0xFFFFFFFFL) | ((long) unsafe.getInt(literalsBase, literalInput + 4) << 32));
                    unsafe.putInt(outputBase, output, (int) splitValue);
                    unsafe.putInt(outputBase, output + 4, (int) (splitValue >>> 32));
                }
                else {
                    unsafe.putLong(outputBase, output, unsafe.getLong(literalsBase, literalInput));
                }
                output += SIZE_OF_LONG;
                literalInput += SIZE_OF_LONG;
            }
            while (output < fastOutputLimit);

            literalInput -= output - fastOutputLimit;
            output = fastOutputLimit;
        }

        while (output < literalOutputLimit) {
            unsafe.putByte(outputBase, output, unsafe.getByte(literalsBase, literalInput));
            output++;
            literalInput++;
        }

        // copy match
        while (output < matchOutputLimit) {
            unsafe.putByte(outputBase, output, unsafe.getByte(outputBase, matchAddress));
            output++;
            matchAddress++;
        }
    }

    private int decodeCompressedLiterals(Object inputBase, final long inputAddress, int blockSize, int literalsBlockType)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        long input = inputAddress;
        verify(blockSize >= 5, input, "Not enough input bytes");

        // compressed
        int compressedSize;
        int uncompressedSize;
        boolean singleStream = false;
        int headerSize;
        int type = (unsafe.getByte(inputBase, input) >> 2) & 0b11;
        switch (type) {
            case 0:
                singleStream = true;
            case 1: {
                int header = unsafe.getInt(inputBase, input);

                headerSize = 3;
                uncompressedSize = (header >>> 4) & mask(10);
                compressedSize = (header >>> 14) & mask(10);
                break;
            }
            case 2: {
                int header = unsafe.getInt(inputBase, input);

                headerSize = 4;
                uncompressedSize = (header >>> 4) & mask(14);
                compressedSize = (header >>> 18) & mask(14);
                break;
            }
            case 3: {
                // read 5 little-endian bytes
                long header = unsafe.getByte(inputBase, input) & 0xFF |
                        (unsafe.getInt(inputBase, input + 1) & 0xFFFF_FFFFL) << 8;

                headerSize = 5;
                uncompressedSize = (int) ((header >>> 4) & mask(18));
                compressedSize = (int) ((header >>> 22) & mask(18));
                break;
            }
            default:
                throw fail(input, "Invalid literals header size type");
        }

        verify(uncompressedSize <= MAX_BLOCK_SIZE, input, "Block exceeds maximum size");
        verify(headerSize + compressedSize <= blockSize, input, "Input is corrupted");

        input += headerSize;

        long inputLimit = input + compressedSize;
        if (literalsBlockType != TREELESS_LITERALS_BLOCK) {
            input += huffman.readTable(inputBase, input, compressedSize);
        }

        literalsBase = literals;
        literalsAddress = ARRAY_BYTE_BASE_OFFSET;
        literalsLimit = ARRAY_BYTE_BASE_OFFSET + uncompressedSize;

        if (singleStream) {
            huffman.decodeSingleStream(inputBase, input, inputLimit, literals, literalsAddress, literalsLimit);
        }
        else {
            huffman.decode4Streams(inputBase, input, inputLimit, literals, literalsAddress, literalsLimit);
        }

        return headerSize + compressedSize;
    }

    private int decodeRleLiterals(Object inputBase, final long inputAddress, int blockSize)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        long input = inputAddress;
        int outputSize;

        int type = (unsafe.getByte(inputBase, input) >> 2) & 0b11;
        switch (type) {
            case 0:
            case 2:
                outputSize = (unsafe.getByte(inputBase, input) & 0xFF) >>> 3;
                input++;
                break;
            case 1:
                outputSize = (unsafe.getShort(inputBase, input) & 0xFFFF) >>> 4;
                input += 2;
                break;
            case 3:
                // we need at least 4 bytes (3 for the header, 1 for the payload)
                verify(blockSize >= SIZE_OF_INT, input, "Not enough input bytes");
                outputSize = (unsafe.getInt(inputBase, input) & 0xFF_FFFF) >>> 4;
                input += 3;
                break;
            default:
                throw fail(input, "Invalid RLE literals header encoding type");
        }

        verify(outputSize <= MAX_BLOCK_SIZE, input, "Output exceeds maximum block size");

        byte value = unsafe.getByte(inputBase, input++);
        Arrays.fill(literals, 0, outputSize + 2 * SIZE_OF_LONG, value);

        literalsBase = literals;
        literalsAddress = ARRAY_BYTE_BASE_OFFSET;
        literalsLimit = ARRAY_BYTE_BASE_OFFSET + outputSize;

        return (int) (input - inputAddress);
    }

    private int decodeRawLiterals(Object inputBase, final long inputAddress, long inputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        long input = inputAddress;
        int type = (unsafe.getByte(inputBase, input) >> 2) & 0b11;

        int literalSize;
        switch (type) {
            case 0:
            case 2:
                literalSize = (unsafe.getByte(inputBase, input) & 0xFF) >>> 3;
                input++;
                break;
            case 1:
                literalSize = (unsafe.getShort(inputBase, input) & 0xFFFF) >>> 4;
                input += 2;
                break;
            case 3:
                // read 3 little-endian bytes
                int header = ((unsafe.getByte(inputBase, input) & 0xFF) |
                        ((unsafe.getShort(inputBase, input + 1) & 0xFFFF) << 8));

                literalSize = header >>> 4;
                input += 3;
                break;
            default:
                throw fail(input, "Invalid raw literals header encoding type");
        }

        verify(input + literalSize <= inputLimit, input, "Not enough input bytes");

        // Set literals pointer to [input, literalSize], but only if we can copy 8 bytes at a time during sequence decoding
        // Otherwise, copy literals into buffer that's big enough to guarantee that
        // A block without sequences (sequence count byte 0 right after the literals) only copies its
        // literals out exactly (copyLastLiteral): they need no slack, so they are never copied twice.
        boolean literalsOnly = input + literalSize < inputLimit && unsafe.getByte(inputBase, input + literalSize) == 0;
        if (literalSize > (inputLimit - input) - 2 * SIZE_OF_LONG && !literalsOnly) {
            literalsBase = literals;
            literalsAddress = ARRAY_BYTE_BASE_OFFSET;
            literalsLimit = ARRAY_BYTE_BASE_OFFSET + literalSize;

            copyMemory(inputBase, input, literals, literalsAddress, literalSize);
            Arrays.fill(literals, literalSize, literalSize + 2 * SIZE_OF_LONG, (byte) 0);
        }
        else {
            literalsBase = inputBase;
            literalsAddress = input;
            literalsLimit = literalsAddress + literalSize;
        }
        input += literalSize;

        return (int) (input - inputAddress);
    }

    static FrameHeader readFrameHeader(final Object inputBase, final long inputAddress, final long inputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        final boolean split = SPLIT_LONGS;
        long input = inputAddress;
        verify(input < inputLimit, input, "Not enough input bytes");

        int frameHeaderDescriptor = unsafe.getByte(inputBase, input++) & 0xFF;
        boolean singleSegment = (frameHeaderDescriptor & 0b100000) != 0;
        int dictionaryDescriptor = frameHeaderDescriptor & 0b11;
        int contentSizeDescriptor = frameHeaderDescriptor >>> 6;

        int headerSize = 1 +
                (singleSegment ? 0 : 1) +
                (dictionaryDescriptor == 0 ? 0 : (1 << (dictionaryDescriptor - 1))) +
                (contentSizeDescriptor == 0 ? (singleSegment ? 1 : 0) : (1 << contentSizeDescriptor));

        verify(headerSize <= inputLimit - inputAddress, input, "Not enough input bytes");

        // decode window size
        int windowSize = -1;
        if (!singleSegment) {
            int windowDescriptor = unsafe.getByte(inputBase, input++) & 0xFF;
            int exponent = windowDescriptor >>> 3;
            int mantissa = windowDescriptor & 0b111;

            int base = 1 << (MIN_WINDOW_LOG + exponent);
            windowSize = base + (base / 8) * mantissa;
        }

        // decode dictionary id
        long dictionaryId = -1;
        switch (dictionaryDescriptor) {
            case 1:
                dictionaryId = unsafe.getByte(inputBase, input) & 0xFF;
                input += SIZE_OF_BYTE;
                break;
            case 2:
                dictionaryId = unsafe.getShort(inputBase, input) & 0xFFFF;
                input += SIZE_OF_SHORT;
                break;
            case 3:
                dictionaryId = unsafe.getInt(inputBase, input) & 0xFFFF_FFFFL;
                input += SIZE_OF_INT;
                break;
        }
        verify(dictionaryId == -1, input, "Custom dictionaries not supported");

        // decode content size
        long contentSize = -1;
        switch (contentSizeDescriptor) {
            case 0:
                if (singleSegment) {
                    contentSize = unsafe.getByte(inputBase, input) & 0xFF;
                    input += SIZE_OF_BYTE;
                }
                break;
            case 1:
                contentSize = unsafe.getShort(inputBase, input) & 0xFFFF;
                contentSize += 256;
                input += SIZE_OF_SHORT;
                break;
            case 2:
                contentSize = unsafe.getInt(inputBase, input) & 0xFFFF_FFFFL;
                input += SIZE_OF_INT;
                break;
            case 3:
                contentSize = (split ? ((unsafe.getInt(inputBase, input) & 0xFFFFFFFFL) | ((long) unsafe.getInt(inputBase, input + 4) << 32)) : unsafe.getLong(inputBase, input));
                input += SIZE_OF_LONG;
                break;
        }

        boolean hasChecksum = (frameHeaderDescriptor & 0b100) != 0;

        return new FrameHeader(
                input - inputAddress,
                windowSize,
                contentSize,
                dictionaryId,
                hasChecksum);
    }

    public static long getDecompressedSize(final Object inputBase, final long inputAddress, final long inputLimit)
    {
        long input = inputAddress;
        input += verifyMagic(inputBase, input, inputLimit);
        return readFrameHeader(inputBase, input, inputLimit).contentSize;
    }

    static int verifyMagic(Object inputBase, long inputAddress, long inputLimit)
    {
        // ARM/ART: static fields in locals - AOT-compiled code (dex2oat) reloads a static final
        // (with class-init, read-barrier and null checks) at every use, since Unsafe calls
        // count as writing any memory
        final Unsafe unsafe = UNSAFE;
        verify(inputLimit - inputAddress >= 4, inputAddress, "Not enough input bytes");

        int magic = unsafe.getInt(inputBase, inputAddress);
        if (magic != MAGIC_NUMBER) {
            if (magic == V07_MAGIC_NUMBER) {
                throw new MalformedInputException(inputAddress, "Data encoded in unsupported ZSTD v0.7 format");
            }
            throw new MalformedInputException(inputAddress, "Invalid magic prefix: " + Integer.toHexString(magic));
        }

        return SIZE_OF_INT;
    }
}
