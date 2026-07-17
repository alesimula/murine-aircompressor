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

class Histogram
{
    // size of the reusable lane workspace for the multi-lane count (3 lanes of 256)
    static final int LANES_SIZE = 3 * 256;

    // below this size the setup cost of the multi-lane count outweighs the win
    private static final int PARALLEL_THRESHOLD = 512;

    private Histogram()
    {
    }

    public static int findLargestCount(int[] counts, int maxSymbol)
    {
        int max = 0;
        for (int i = 0; i <= maxSymbol; i++) {
            if (counts[i] > max) {
                max = counts[i];
            }
        }

        return max;
    }

    public static int findMaxSymbol(int[] counts, int maxSymbol)
    {
        while (counts[maxSymbol] == 0) {
            maxSymbol--;
        }
        return maxSymbol;
    }

    public static void count(byte[] input, int length, int[] counts)
    {
        Arrays.fill(counts, 0);

        for (int i = 0; i < length; i++) {
            counts[input[i] & 0xFF]++;
        }
    }

    // Multi-lane histogram (upstream's own "TODO: count parallel heuristic", the design native
    // zstd uses in HIST_count_parallel): the naive loop serializes on store-to-load forwarding
    // every time a symbol repeats - which is precisely what compressible data does. Four count
    // lanes break the dependency chains; lane totals are summed at the end, so the resulting
    // counts are identical to the naive loop. `lanes` is a caller-owned int[LANES_SIZE]
    // workspace: reusing it keeps this allocation-free and its cache lines warm across blocks.
    // Deliberately uses plain array reads, NOT Unsafe: compiled code loads a byte either way,
    // but in ART's interpreter (the first run after an app update, before the JIT/stored profile
    // kicks in) every Unsafe access is a real native call - an Unsafe-based version tanks
    // first-run performance while plain reads cost the same as the old naive loop.
    public static void count(byte[] input, int length, int[] counts, int[] lanes)
    {
        if (length < PARALLEL_THRESHOLD) {
            count(input, length, counts);
            return;
        }

        Arrays.fill(counts, 0);
        Arrays.fill(lanes, 0, LANES_SIZE, 0);

        int i = 0;
        int limit = length & ~3;
        for (; i < limit; i += 4) {
            counts[input[i] & 0xFF]++;
            lanes[input[i + 1] & 0xFF]++;
            lanes[256 + (input[i + 2] & 0xFF)]++;
            lanes[512 + (input[i + 3] & 0xFF)]++;
        }
        for (; i < length; i++) {
            counts[input[i] & 0xFF]++;
        }

        // merge lanes; symbols beyond counts.length cannot occur in valid input (the naive loop
        // would have thrown for them as well), so their lane entries are zero
        int mergeLimit = Math.min(counts.length, 256);
        for (int symbol = 0; symbol < mergeLimit; symbol++) {
            counts[symbol] += lanes[symbol] + lanes[256 + symbol] + lanes[512 + symbol];
        }
    }
}
