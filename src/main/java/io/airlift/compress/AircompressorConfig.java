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
package io.airlift.compress;

import io.airlift.compress.zstd.ZstdMemory;

/**
 * Global settings, for all compressors, decompressors and streams.
 */
public final class AircompressorConfig
{
    private static volatile boolean zstdMemoryReuse;

    private AircompressorConfig()
    {
    }

    /**
     * Whether zstd keeps memory between (de)compressions. Off by default.
     */
    public static boolean isZstdMemoryReuse()
    {
        return zstdMemoryReuse;
    }

    /**
     * Keep zstd buffers between (de)compressions, or not (the default): the last compression
     * context (about 1 MB at the default level) and the last stream window (up to a few MB), one
     * each, softly referenced. Same output; much faster on small inputs. Kept buffers hold the last
     * data until reused, released or collected. Thread-safe. Off also calls {@link ZstdMemory#release()}.
     */
    public static void setZstdMemoryReuse(boolean enabled)
    {
        zstdMemoryReuse = enabled;
        if (!enabled) {
            ZstdMemory.release();
        }
    }
}
