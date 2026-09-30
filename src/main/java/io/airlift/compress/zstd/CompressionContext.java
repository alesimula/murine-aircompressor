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

import java.lang.ref.SoftReference;
import java.util.concurrent.atomic.AtomicReference;

import static io.airlift.compress.zstd.Constants.MAX_BLOCK_SIZE;
import static io.airlift.compress.zstd.Util.checkArgument;

class CompressionContext
{
    // One spare context left by a finished compression (see release()). The next compression with
    // tables of the same sizes takes it instead of allocating them again (about 1 MB at the default
    // level; for small inputs the allocation and the garbage collections it causes cost more than the
    // compression itself). Soft reference: the GC can drop it under memory pressure.
    private static final AtomicReference<SoftReference<CompressionContext>> SPARE = new AtomicReference<>();

    public CompressionParameters parameters;
    public final RepeatedOffsets offsets = new RepeatedOffsets();
    public final BlockCompressionState blockCompressionState;
    public final SequenceStore sequenceStore;

    public final SequenceEncodingContext sequenceEncodingContext = new SequenceEncodingContext();

    public final HuffmanCompressionContext huffmanContext = new HuffmanCompressionContext();

    public CompressionContext(CompressionParameters parameters, long baseAddress, int inputSize)
    {
        this.parameters = parameters;

        int windowSize = Math.max(1, Math.min(parameters.getWindowSize(), inputSize));
        int blockSize = Math.min(MAX_BLOCK_SIZE, windowSize);
        int divider = (parameters.getSearchLength() == 3) ? 3 : 4;

        int maxSequences = blockSize / divider;

        sequenceStore = new SequenceStore(blockSize, maxSequences);

        blockCompressionState = new BlockCompressionState(parameters, baseAddress);
    }

    // the spare context when it fits these parameters (reset as if new), else a new one
    static CompressionContext acquire(CompressionParameters parameters, long baseAddress, int inputSize)
    {
        SoftReference<CompressionContext> spareReference = SPARE.getAndSet(null);
        CompressionContext spare = spareReference == null ? null : spareReference.get();
        if (spare != null) {
            if (spare.fits(parameters, inputSize)) {
                spare.reinitialize(parameters, baseAddress, inputSize);
                return spare;
            }
            SPARE.compareAndSet(null, spareReference);
        }
        return new CompressionContext(parameters, baseAddress, inputSize);
    }

    // compression finished: hand the context to the next one (must not be used after this)
    void release()
    {
        SPARE.set(new SoftReference<>(this));
    }

    private boolean fits(CompressionParameters parameters, int inputSize)
    {
        int windowSize = Math.max(1, Math.min(parameters.getWindowSize(), inputSize));
        int blockSize = Math.min(MAX_BLOCK_SIZE, windowSize);
        int divider = (parameters.getSearchLength() == 3) ? 3 : 4;
        int maxSequences = blockSize / divider;
        int chainTableSize = parameters.getStrategy() == CompressionParameters.Strategy.FAST ? 0 : 1 << parameters.getChainLog();
        return sequenceStore.literalsBuffer.length == blockSize
                && sequenceStore.offsets.length == maxSequences
                && blockCompressionState.hashTable.length == 1 << parameters.getHashLog()
                && blockCompressionState.chainTable.length == chainTableSize;
    }

    private void reinitialize(CompressionParameters parameters, long baseAddress, int inputSize)
    {
        this.parameters = parameters;
        offsets.reset();
        blockCompressionState.reset(baseAddress, inputSize);
        sequenceStore.reset();
        huffmanContext.reset();
    }

    public void slideWindow(int slideWindowSize, boolean rebaseWindowBase)
    {
        checkArgument(slideWindowSize > 0, "slideWindowSize must be positive");
        blockCompressionState.slideWindow(slideWindowSize, rebaseWindowBase);
    }

    public void commit()
    {
        offsets.commit();
        huffmanContext.saveChanges();
    }
}
