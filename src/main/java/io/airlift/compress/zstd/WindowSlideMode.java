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
 * Controls how {@link ZstdOutputStream} slides its match window when its buffer fills.
 *
 * <p>This is a compression-ratio vs. speed trade-off. When the stream slides the window it can
 * either re-anchor the window to the data it just kept (so that history stays matchable) or leave
 * the anchor where it was (so older history drops out of matching sooner). The first gives better
 * compression; the second is faster.
 */
public enum WindowSlideMode
{
    /**
     * Re-anchor the match window on every slide so that all data still inside the window remains
     * matchable. This yields the best compression ratio, at some cost in throughput because the
     * compressor discovers and encodes more long-range matches. This is the default.
     */
    HIGH_COMPRESSION(true),

    /**
     * Do not re-anchor the window when it slides. Faster, because older data drops out of matching
     * as the window advances and less work is spent per block, but the compression ratio is lower
     * on inputs whose repetition reaches back beyond the most recent slide.
     */
    HIGH_SPEED(false);

    private final boolean rebaseWindow;

    WindowSlideMode(boolean rebaseWindow)
    {
        this.rebaseWindow = rebaseWindow;
    }

    /**
     * Whether the window base offset should be rebased on slide (true for {@link #HIGH_COMPRESSION}).
     */
    boolean rebasesWindow()
    {
        return rebaseWindow;
    }
}
