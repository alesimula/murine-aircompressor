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
import java.nio.charset.Charset;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reads USTAR (POSIX.1-1988) archives with the java.util.zip.ZipInputStream
 * call sequence: getNextEntry until null, reading each entry's data in between.
 * <p>
 * PAX extended headers (path, size, mtime) and GNU long names are applied to
 * the entry they precede; other extension records are consumed and ignored.
 */
public class TarInputStream
        extends FilterInputStream
{
    private final byte[] block = new byte[512];
    private long remaining;
    private int padding;
    private boolean eof;
    private String paxPath;
    private String paxSize;
    private String paxMtime;
    private Charset nameCharset = UTF_8;

    public TarInputStream(InputStream in)
    {
        super(in);
    }

    /**
     * The charset of the ustar name fields and of GNU long names, UTF-8 by default. USTAR does not
     * record what character set its name fields hold, so an archive written by a tar that used the
     * system locale needs that locale passed in here to read back unmangled. Applies to entries read
     * after this call.
     * <p>
     * PAX records are not affected: POSIX.1-2001 defines them as UTF-8 and they are always read as
     * such, so a name carried in a PAX record is decoded correctly whatever this is set to.
     */
    public TarInputStream withNameCharset(Charset nameCharset)
    {
        this.nameCharset = TarEntry.checkNameCharset(nameCharset);
        return this;
    }

    /**
     * Skips whatever is left of the current entry and returns the next one,
     * or null at the end of the archive.
     */
    public TarEntry getNextEntry()
            throws IOException
    {
        if (eof) {
            return null;
        }
        long toSkip = remaining + padding;
        while (toSkip > 0) {
            int read = in.read(block, 0, (int) Math.min(toSkip, 512));
            if (read < 0) {
                throw new EOFException("Truncated tar archive");
            }
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
        paxPath = null;
        paxSize = null;
        paxMtime = null;
        String gnuName = null;
        while (true) {
            TarEntry entry = new TarEntry(block, nameCharset);
            char type = entry.getType();
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                byte[] data = readEntryData(entry);
                if (type == 'x') {
                    for (int at = 0; at < data.length; ) {
                        at = parsePaxRecord(data, at);
                    }
                }
                else if (type == 'L') {
                    int end = 0;
                    while (end < data.length && data[end] != 0) {
                        end++;
                    }
                    gnuName = new String(data, 0, end, nameCharset);
                }
                // 'g' (global) and 'K' (long link) records are ignored
                if (!readBlock()) {
                    throw new EOFException("Truncated tar archive");
                }
                continue;
            }
            if (gnuName != null) {
                entry.setName(gnuName);
            }
            if (paxPath != null) {
                entry.setName(paxPath);
            }
            if (paxSize != null) {
                try {
                    entry.setSize(Long.parseLong(paxSize));
                }
                catch (IllegalArgumentException e) {
                    throw new IOException("Corrupt PAX header (bad size)");
                }
            }
            if (paxMtime != null) {
                try {
                    entry.setModTime((long) (Double.parseDouble(paxMtime) * 1000));
                }
                catch (NumberFormatException e) {
                    throw new IOException("Corrupt PAX header (bad mtime)");
                }
            }
            remaining = entry.getSize();
            padding = (int) (-remaining & 511);
            return entry;
        }
    }

    /**
     * Parses one "&lt;len&gt; &lt;key&gt;=&lt;value&gt;\n" record, where len spans the whole
     * record including its own digits, and returns the offset of the next one.
     */
    private int parsePaxRecord(byte[] data, int at)
            throws IOException
    {
        int digits = at;
        int length = 0;
        while (digits < data.length && data[digits] != ' ') {
            byte b = data[digits];
            if (b < '0' || b > '9') {
                throw new IOException("Corrupt PAX header (non-digit in record length)");
            }
            length = length * 10 + (b - '0');
            if (length > data.length) {
                throw new IOException("Corrupt PAX header (record length exceeds header size)");
            }
            digits++;
        }
        int end = at + length;
        // needs at least the digits, the space, one byte of "key=value" and the newline
        if (digits >= data.length || length <= digits - at + 2 || end > data.length) {
            throw new IOException("Corrupt PAX header (bad record length)");
        }
        if (data[end - 1] != '\n') {
            throw new IOException("Corrupt PAX header (record does not end with a newline)");
        }
        String record = new String(data, digits + 1, end - digits - 2, UTF_8);
        int equals = record.indexOf('=');
        if (equals < 0) {
            throw new IOException("Corrupt PAX header (record has no '=')");
        }
        String key = record.substring(0, equals);
        String value = record.substring(equals + 1);
        if (value.isEmpty()) {
            return end; // empty value unsets the keyword
        }
        if (key.equals("path")) {
            paxPath = value;
        }
        else if (key.equals("size")) {
            paxSize = value;
        }
        else if (key.equals("mtime")) {
            paxMtime = value;
        }
        return end;
    }

    /** Reads an extension entry's data in full, including its padding. */
    private byte[] readEntryData(TarEntry entry)
            throws IOException
    {
        if (entry.getSize() > 1 << 20) {
            throw new IOException("Oversized tar extension record");
        }
        int size = (int) entry.getSize();
        byte[] data = new byte[size];
        int total = 0;
        while (total < size) {
            int read = in.read(data, total, size - total);
            if (read < 0) {
                throw new EOFException("Truncated tar archive");
            }
            total += read;
        }
        long pad = -entry.getSize() & 511;
        while (pad > 0) {
            int read = in.read(block, 0, (int) pad);
            if (read < 0) {
                throw new EOFException("Truncated tar archive");
            }
            pad -= read;
        }
        return data;
    }

    @Override
    public int read()
            throws IOException
    {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length)
            throws IOException
    {
        if (remaining == 0) {
            return -1;
        }
        int read = in.read(buffer, offset, (int) Math.min(length, remaining));
        if (read < 0) {
            throw new EOFException("Truncated tar archive");
        }
        remaining -= read;
        return read;
    }

    private boolean readBlock()
            throws IOException
    {
        int total = 0;
        while (total < 512) {
            int read = in.read(block, total, 512 - total);
            if (read < 0) {
                if (total == 0) {
                    return false;
                }
                throw new EOFException("Truncated tar header");
            }
            total += read;
        }
        return true;
    }

    private boolean isZeroBlock()
    {
        for (int i = 0; i < 512; i++) {
            if (block[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
