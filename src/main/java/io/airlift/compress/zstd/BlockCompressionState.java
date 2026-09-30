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

import static io.airlift.compress.zstd.Constants.SIZE_OF_LONG;

class BlockCompressionState
{
    // Rewrite the tables only once positions pass this bias (zstd's overflow correction)
    private static final long MAX_INDEX_BIAS = 1L << 30;

    public final int[] hashTable;
    public final int[] chainTable;

    // address of the first byte of the caller's buffer
    private long bufferAddress;
    // Table entries are positions relative to baseAddress. A window slide moves the data back by
    // `slide` bytes and baseAddress back by the same amount, so every entry still points at the
    // same bytes without being rewritten (as zstd moves window.base). baseAddress = bufferAddress - indexBias.
    private long baseAddress;
    private long indexBias;

    // starting point of the window with respect to baseAddress
    private int windowBaseOffset;

    // above every position the tables hold (see reset(long, int))
    private long highestIndex;

    public BlockCompressionState(CompressionParameters parameters, long baseAddress)
    {
        this.bufferAddress = baseAddress;
        this.baseAddress = baseAddress;
        hashTable = new int[1 << parameters.getHashLog()];
        // FAST only uses hashTable; DFAST uses chainTable as its short hash table
        chainTable = parameters.getStrategy() == CompressionParameters.Strategy.FAST ? new int[0] : new int[1 << parameters.getChainLog()];
    }

    public void slideWindow(int slideWindowSize, boolean rebaseWindowBase)
    {
        // Entries older than the slide now point before the buffer: they are below the window base
        // (which never goes below bufferAddress), so the match finders reject them, exactly as
        // they rejected the entries the old per-slide rewrite clamped to 0.
        baseAddress -= slideWindowSize;
        indexBias += slideWindowSize;
        if (rebaseWindowBase) {
            // same window as before the slide, clamped to the start of the buffer
            windowBaseOffset = (int) Math.max(windowBaseOffset, indexBias);
        }
        else {
            // the window base keeps its address, so it moves forward relative to the moved data
            windowBaseOffset += slideWindowSize;
        }

        if (indexBias > MAX_INDEX_BIAS) {
            removeIndexBias();
        }
    }

    // Bring positions back to bufferAddress before they overflow an int: the per-slide rewrite
    // this class used to do, now done once every ~1 GB of input.
    private void removeIndexBias()
    {
        int bias = (int) indexBias;
        windowBaseOffset = Math.max(0, windowBaseOffset - bias);
        reduceTable(hashTable, bias);
        reduceTable(chainTable, bias);
        baseAddress = bufferAddress;
        indexBias = 0;
        highestIndex = Math.max(0, highestIndex - bias);
    }

    private static void reduceTable(int[] table, int reduction)
    {
        for (int i = 0; i < table.length; i++) {
            int newValue = table[i] - reduction;
            // if new value is negative, set it to zero branchless
            newValue = newValue & (~(newValue >> 31));
            table[i] = newValue;
        }
    }

    // As new, for a buffer at baseAddress and inputSize bytes (Integer.MAX_VALUE: a stream). The
    // tables are not cleared: positions continue past the highest one the tables hold, and the window
    // starts at the buffer, so every old entry points before the window and the match finders reject
    // it, exactly as they reject the zeros of a new table (zstd's own trick to skip the clearing).
    // Cleared only when the positions would come too close to the int range.
    public void reset(long baseAddress, int inputSize)
    {
        long bias = highestIndex + 1;
        long limit = inputSize == Integer.MAX_VALUE ? MAX_INDEX_BIAS : Integer.MAX_VALUE - (long) inputSize - SIZE_OF_LONG;
        if (bias > limit) {
            reset();
            bias = 0;
        }
        this.bufferAddress = baseAddress;
        this.indexBias = bias;
        this.baseAddress = baseAddress - bias;
        this.windowBaseOffset = (int) bias;
        this.highestIndex = bias;
    }

    public void reset()
    {
        Arrays.fill(hashTable, 0);
        Arrays.fill(chainTable, 0);
    }

    public void enforceMaxDistance(long inputLimit, int maxDistance)
    {
        int distance = (int) (inputLimit - baseAddress);
        highestIndex = Math.max(highestIndex, distance);

        int newOffset = distance - maxDistance;
        if (windowBaseOffset < newOffset) {
            windowBaseOffset = newOffset;
        }
    }

    public long getBaseAddress()
    {
        return baseAddress;
    }

    public int getWindowBaseOffset()
    {
        return windowBaseOffset;
    }
}
