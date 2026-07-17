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

/**
 * Controls how {@link ZstdOutputStream} buffers uncompressed data. Both modes produce standard
 * zstd frames and honour {@link WindowSlideMode}.
 */
public enum BufferMode
{
    /**
     * Fixed ring of two windows plus one block (~2.1 MB at level 3). The footprint never grows,
     * there are no reallocations, and the window slide is always exactly one window every eight
     * blocks — a deterministic, evenly spread cost. This makes throughput far more consistent on
     * devices where the old growing buffer (~4.2 MB working set, variable-size copy spikes)
     * straddles the cache. This is the default.
     */
    RING_BUFFER,

    /**
     * The original growing buffer (up to four windows, compacted with a variable-size arraycopy
     * after each chunk). Kept as a byte-identical fallback for comparison.
     */
    SLIDING_WINDOW;
}
