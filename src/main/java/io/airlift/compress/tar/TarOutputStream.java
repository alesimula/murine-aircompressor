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
package io.airlift.compress.tar;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Writes USTAR (POSIX.1-1988) archives with the java.util.zip.ZipOutputStream
 * call sequence: putNextEntry, write the data, closeEntry, repeat, close.
 * <p>
 * tar stores each entry's size in its header: each entrymust be written in full before it's closed.
 */
public class TarOutputStream extends FilterOutputStream {
    private final byte[] block = new byte[512];
    private long remaining;
    private int padding;
    private boolean entryOpen;
    private boolean finished;

    public TarOutputStream(OutputStream out) {
        super(out);
    }

    public void putNextEntry(TarEntry entry) throws IOException {
        if (entryOpen) throw new IOException("Previous entry was not closed");
        if (finished) throw new IOException("Archive is finished");
        entry.writeHeader(block);
        out.write(block);
        remaining = entry.getSize();
        padding = (int) (-remaining & 511);
        entryOpen = true;
    }

    @Override
    public void write(int b)
            throws IOException
    {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (!entryOpen) throw new IOException("No open entry");
        if (length > remaining) throw new IOException("Write exceeds the declared entry size");
        out.write(buffer, offset, length);
        remaining -= length;
    }

    /**
     * Verifies the entry was written in full and pads it to the block boundary.ù
     */
    public void closeEntry() throws IOException {
        if (!entryOpen) return;
        if (remaining != 0) throw new IOException(remaining + " bytes missing from the current entry");
        Arrays.fill(block, 0, padding, (byte) 0);
        out.write(block, 0, padding);
        entryOpen = false;
    }

    /**
     * Writes the end-of-archive marker (two zero blocks). Called by close().
     */
    public void finish() throws IOException {
        if (entryOpen) throw new IOException("Current entry was not closed");
        if (finished) return;
        Arrays.fill(block, (byte) 0);
        out.write(block);
        out.write(block);
        finished = true;
    }

    @Override
    public void close() throws IOException {
        if (!finished) finish();
        super.close();
    }
}
