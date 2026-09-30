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
 * Memory kept by zstd between (de)compressions, see
 * {@link io.airlift.compress.AircompressorConfig#setZstdMemoryReuse(boolean)}.
 */
public final class ZstdMemory
{
    private ZstdMemory()
    {
    }

    /**
     * Releases the compression context and the window buffer kept so far (if any), e.g. when the
     * app goes to the background or before a memory-heavy task. Safe to call at any time, from any
     * thread; (de)compressions running meanwhile are not affected. With memory reuse still on, the
     * next finished (de)compression keeps new ones; with it off (default) nothing is kept, so this does nothing.
     */
    public static void release()
    {
        CompressionContext.clearSpare();
        ZstdIncrementalFrameDecompressor.clearSpareWindow();
    }
}
