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

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteOrder;

import static java.lang.String.format;

/**
 * Murine: single shared replacement for the four identical per-package UnsafeUtil
 * classes, extended with Android compatibility shims (see ARRAY_BYTE_BASE_OFFSET
 * and copyMemory below).
 */
public final class UnsafeUtil
{
    public static final Unsafe UNSAFE;
    private static final long ADDRESS_OFFSET;

    private UnsafeUtil() {}

    static {
        ByteOrder order = ByteOrder.nativeOrder();
        if (!order.equals(ByteOrder.LITTLE_ENDIAN)) {
            throw new IncompatibleJvmException(format("Aircompressor requires a little endian platform (found %s)", order));
        }

        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
        }
        catch (Exception e) {
            throw new IncompatibleJvmException("Aircompressor requires access to sun.misc.Unsafe");
        }

        try {
            // fetch the address field for direct buffers
            ADDRESS_OFFSET = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("address"));
        }
        catch (NoSuchFieldException e) {
            throw new IncompatibleJvmException("Aircompressor requires access to java.nio.Buffer raw address field");
        }
    }

    // Murine: Android's sun.misc.Unsafe has no ARRAY_BYTE_BASE_OFFSET field (OpenJDK
    // initializes it at runtime, so it compiles to a field read that throws
    // NoSuchFieldError on ART). The method that computes it exists on all Android versions.
    public static final int ARRAY_BYTE_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);

    public static long getAddress(Buffer buffer)
    {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("buffer is not direct");
        }

        return UNSAFE.getLong(buffer, ADDRESS_OFFSET);
    }

    // Murine: Android's sun.misc.Unsafe has no copyMemory(Object, long, Object, long, long),
    // only the 3-arg absolute-address form. Heap arrays go through System.arraycopy (an ART
    // intrinsic with memmove semantics); the byte loop only runs for direct-buffer bases,
    // which the stream APIs never use.
    public static void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long length)
    {
        if (srcBase instanceof byte[] && destBase instanceof byte[]) {
            System.arraycopy((byte[]) srcBase, (int) (srcOffset - ARRAY_BYTE_BASE_OFFSET),
                    (byte[]) destBase, (int) (destOffset - ARRAY_BYTE_BASE_OFFSET), (int) length);
            return;
        }
        for (long i = 0; i < length; i++) {
            byte value = (srcBase == null) ? UNSAFE.getByte(srcOffset + i) : UNSAFE.getByte(srcBase, srcOffset + i);
            if (destBase == null) {
                UNSAFE.putByte(destOffset + i, value);
            }
            else {
                UNSAFE.putByte(destBase, destOffset + i, value);
            }
        }
    }
}
