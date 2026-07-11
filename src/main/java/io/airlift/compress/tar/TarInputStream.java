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

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads USTAR (POSIX.1-1988) archives with the java.util.zip.ZipInputStream
 * call sequence: getNextEntry until null, reading each entry's data in between.
 * <p>
 * Every header entry is surfaced as-is: PAX and GNU extension records
 * (long names, extended attributes) are returned as plain entries rather
 * than interpreted, and their data can be read or skipped like any other.
 */
public class TarInputStream extends FilterInputStream {
    private final byte[] block = new byte[512];
    private long remaining;
    private int padding;
    private boolean eof;

    public TarInputStream(InputStream in) {
        super(in);
    }

    /**
     * Skips whatever is left of the current entry and returns the next one,
     * or null at the end of the archive.
     */
    public TarEntry getNextEntry() throws IOException {
        if (eof) {
            return null;
        }
        long toSkip = remaining + padding;
        while (toSkip > 0) {
            int read = in.read(block, 0, (int) Math.min(toSkip, 512));
            if (read < 0) throw new EOFException("Truncated tar archive");
            toSkip -= read;
        }
        remaining = 0;
        padding = 0;
        if (!readBlock()) {
            eof = true; // no trailer at all; tolerated
            return null;
        }
        if (isZeroBlock()) {
            eof = true; // end-of-archive marker (second zero block not required)
            return null;
        }
        TarEntry entry = new TarEntry(block);
        remaining = entry.getSize();
        padding = (int) (-remaining & 511);
        return entry;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining == 0) return -1;
        int read = in.read(buffer, offset, (int) Math.min(length, remaining));
        if (read < 0) throw new EOFException("Truncated tar archive");
        remaining -= read;
        return read;
    }

    private boolean readBlock() throws IOException {
        int total = 0;
        while (total < 512) {
            int read = in.read(block, total, 512 - total);
            if (read < 0) {
                if (total == 0) return false;
                throw new EOFException("Truncated tar header");
            }
            total += read;
        }
        return true;
    }

    private boolean isZeroBlock() {
        for (int i = 0; i < 512; i++) if (block[i] != 0) return false;
        return true;
    }
}
