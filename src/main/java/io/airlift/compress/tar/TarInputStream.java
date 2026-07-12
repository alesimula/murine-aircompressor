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

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reads USTAR (POSIX.1-1988) archives with the java.util.zip.ZipInputStream
 * call sequence: getNextEntry until null, reading each entry's data in between.
 * <p>
 * PAX extended headers (path, size, mtime) and GNU long names are applied to
 * the entry they precede; other extension records are consumed and ignored.
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
        String paxPath = null;
        String paxSize = null;
        String paxMtime = null;
        String gnuName = null;
        while (true) {
            TarEntry entry = new TarEntry(block);
            char type = entry.getType();
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                byte[] data = readEntryData(entry);
                if (type == 'x') {
                    // "<len> <key>=<value>\n" records; len spans the whole record
                    int at = 0;
                    while (at < data.length) {
                        int space = at;
                        while (data[space] != ' ') space++;
                        int end = at + Integer.parseInt(new String(data, at, space - at, UTF_8));
                        String record = new String(data, space + 1, end - space - 2, UTF_8); // minus '\n'
                        int eq = record.indexOf('=');
                        String key = record.substring(0, eq);
                        String value = record.substring(eq + 1);
                        if (key.equals("path")) paxPath = value;
                        else if (key.equals("size")) paxSize = value;
                        else if (key.equals("mtime")) paxMtime = value;
                        at = end;
                    }
                }
                else if (type == 'L') {
                    int end = 0;
                    while (end < data.length && data[end] != 0) end++;
                    gnuName = new String(data, 0, end, UTF_8);
                }
                // 'g' (global) and 'K' (long link) records are ignored
                if (!readBlock()) throw new EOFException("Truncated tar archive");
                continue;
            }
            if (gnuName != null) entry.setName(gnuName);
            if (paxPath != null) entry.setName(paxPath);
            if (paxSize != null) entry.setSize(Long.parseLong(paxSize));
            if (paxMtime != null) entry.setModTime((long) (Double.parseDouble(paxMtime) * 1000));
            remaining = entry.getSize();
            padding = (int) (-remaining & 511);
            return entry;
        }
    }

    /** Reads an extension entry's data in full, including its padding. */
    private byte[] readEntryData(TarEntry entry) throws IOException {
        if (entry.getSize() > 1 << 20) throw new IOException("Oversized tar extension record");
        int size = (int) entry.getSize();
        byte[] data = new byte[size];
        int total = 0;
        while (total < size) {
            int read = in.read(data, total, size - total);
            if (read < 0) throw new EOFException("Truncated tar archive");
            total += read;
        }
        long pad = -entry.getSize() & 511;
        while (pad > 0) {
            int read = in.read(block, 0, (int) pad);
            if (read < 0) throw new EOFException("Truncated tar archive");
            pad -= read;
        }
        return data;
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
