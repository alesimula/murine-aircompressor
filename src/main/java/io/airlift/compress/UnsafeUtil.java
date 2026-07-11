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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
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
    // only the 3-arg absolute-address form. Both the OpenJDK 5-arg method and Android's
    // hidden heap/native memcpy natives are probed ONCE below (they can't be compiled
    // against from the other platform) and invoked through static final MethodHandles
    // with invokeExact: no boxing, no per-call reflection, native memcpy inside.
    private static final MethodHandle COPY_MEMORY =       // OpenJDK (Object, long, Object, long, long) -> void
            unsafeHandle("copyMemory", Object.class, long.class, Object.class, long.class, long.class);
    private static final MethodHandle COPY_TO_ARRAY =     // Android (long srcAddr, Object dst, long dstOffset, long bytes) -> void
            unsafeHandle("copyMemoryToPrimitiveArray", long.class, Object.class, long.class, long.class);
    private static final MethodHandle COPY_FROM_ARRAY =   // Android (Object src, long srcOffset, long dstAddr, long bytes) -> void
            unsafeHandle("copyMemoryFromPrimitiveArray", Object.class, long.class, long.class, long.class);

    private static MethodHandle unsafeHandle(String name, Class<?>... parameterTypes)
    {
        try {
            return MethodHandles.lookup().unreflect(Unsafe.class.getMethod(name, parameterTypes)).bindTo(UNSAFE);
        }
        catch (Throwable ignored) {
            // method absent on this runtime (or hidden-API restricted): callers fall back
            return null;
        }
    }

    public static void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long length)
    {
        try {
            // OpenJDK (and any Android that grows the method): the real thing, all cases
            if (COPY_MEMORY != null) {
                COPY_MEMORY.invokeExact(srcBase, srcOffset, destBase, destOffset, length);
                return;
            }
            if (srcBase instanceof byte[] && destBase instanceof byte[]) {
                System.arraycopy((byte[]) srcBase, (int) (srcOffset - ARRAY_BYTE_BASE_OFFSET),
                        (byte[]) destBase, (int) (destOffset - ARRAY_BYTE_BASE_OFFSET), (int) length);
                return;
            }
            if (srcBase == null && destBase == null) {
                UNSAFE.copyMemory(srcOffset, destOffset, length);
                return;
            }
            // Mixed heap/native: Android's dedicated memcpy natives
            if (COPY_TO_ARRAY != null && srcBase == null && destBase instanceof byte[]) {
                COPY_TO_ARRAY.invokeExact(srcOffset, destBase, destOffset - ARRAY_BYTE_BASE_OFFSET, length);
                return;
            }
            if (COPY_FROM_ARRAY != null && srcBase instanceof byte[] && destBase == null) {
                COPY_FROM_ARRAY.invokeExact(srcBase, srcOffset - ARRAY_BYTE_BASE_OFFSET, destOffset, length);
                return;
            }
        }
        catch (Throwable t) {
            throw new AssertionError(t);
        }
        // Fallback (restricted lookups): null base means absolute address, which needs the
        // address-only accessors on ART (OpenJDK's null-base-as-absolute is not portable).
        long i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            long value = (srcBase == null) ? UNSAFE.getLong(srcOffset + i) : UNSAFE.getLong(srcBase, srcOffset + i);
            if (destBase == null) {
                UNSAFE.putLong(destOffset + i, value);
            }
            else {
                UNSAFE.putLong(destBase, destOffset + i, value);
            }
        }
        for (; i < length; i++) {
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
