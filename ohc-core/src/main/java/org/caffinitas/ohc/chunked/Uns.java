/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.ohc.chunked;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.caffinitas.ohc.OHCacheBuilder;
import org.caffinitas.ohc.alloc.IAllocator;
import org.caffinitas.ohc.alloc.JNANativeAllocator;
import org.caffinitas.ohc.alloc.UnsafeAllocator;

final class Uns
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Uns.class);

    private static final IAllocator allocator;

    private static final boolean __DEBUG_OFF_HEAP_MEMORY_ACCESS = Boolean.parseBoolean(System.getProperty(OHCacheBuilder.SYSTEM_PROPERTY_PREFIX + "debugOffHeapAccess", "false"));
    private static final String __ALLOCATOR = System.getProperty(OHCacheBuilder.SYSTEM_PROPERTY_PREFIX + "allocator");

    //
    // #ifdef __DEBUG_OFF_HEAP_MEMORY_ACCESS
    //
    private static final ConcurrentMap<Long, AllocInfo> ohDebug = __DEBUG_OFF_HEAP_MEMORY_ACCESS ? new ConcurrentHashMap<>(16384) : null;
    private static final Map<Long, Throwable> ohFreeDebug = __DEBUG_OFF_HEAP_MEMORY_ACCESS ? new ConcurrentHashMap<>(16384) : null;

    private static final class AllocInfo
    {
        final long size;
        final Throwable trace;

        AllocInfo(Long size, Throwable trace)
        {
            this.size = size;
            this.trace = trace;
        }
    }

    static void clearUnsDebugForTest()
    {
        if (__DEBUG_OFF_HEAP_MEMORY_ACCESS)
        {
            try
            {
                if (!ohDebug.isEmpty())
                {
                    for (Map.Entry<Long, AllocInfo> addrSize : ohDebug.entrySet())
                    {
                        System.err.printf("  still allocated: address=%d, size=%d%n", addrSize.getKey(), addrSize.getValue().size);
                        addrSize.getValue().trace.printStackTrace();
                    }
                    throw new RuntimeException("Not all allocated memory has been freed!");
                }
            }
            finally
            {
                ohDebug.clear();
                ohFreeDebug.clear();
            }
        }
    }

    private static void freed(long address)
    {
        if (__DEBUG_OFF_HEAP_MEMORY_ACCESS)
        {
            AllocInfo allocInfo = ohDebug.remove(address);
            if (allocInfo == null)
            {
                Throwable freedAt = ohFreeDebug.get(address);
                throw new IllegalStateException("Free of unallocated region " + address, freedAt);
            }
            ohFreeDebug.put(address, new Exception("free backtrace - t=" + System.nanoTime()));
        }
    }

    private static void allocated(long address, long bytes)
    {
        if (__DEBUG_OFF_HEAP_MEMORY_ACCESS)
        {
            AllocInfo allocatedLen = ohDebug.putIfAbsent(address, new AllocInfo(bytes, new Exception("Thread: "+Thread.currentThread())));
            if (allocatedLen != null)
                throw new Error("Oops - allocate() got duplicate address");
            ohFreeDebug.remove(address);
        }
    }
    //
    // #endif
    //

    static
    {
        if (__DEBUG_OFF_HEAP_MEMORY_ACCESS)
            LOGGER.warn("Degraded performance due to off-heap memory allocations and access guarded by debug code enabled via system property " + OHCacheBuilder.SYSTEM_PROPERTY_PREFIX + "debugOffHeapAccess=true");

        IAllocator alloc;
        String allocType = __ALLOCATOR != null ? __ALLOCATOR : "jna";
        switch (allocType)
        {
            case "unsafe":
                alloc = new UnsafeAllocator();
                LOGGER.info("OHC using sun.misc.Unsafe memory allocation");
                break;
            case "jna":
            default:
                alloc = new JNANativeAllocator();
                LOGGER.info("OHC using JNA OS native malloc/free");
        }

        allocator = alloc;
    }

    private Uns()
    {
    }

    static void copyMemory(byte[] arr, int off, long address, long offset, long len)
    {
        MemorySegment.copy(MemorySegment.ofArray(arr), off,
                           MemorySegment.ofAddress(address + offset).reinterpret(len), 0L, len);
    }

    static void copyMemory(long address, long offset, byte[] arr, int off, long len)
    {
        MemorySegment.copy(MemorySegment.ofAddress(address + offset).reinterpret(len), 0L,
                           MemorySegment.ofArray(arr), off, len);
    }

    static long getTotalAllocated()
    {
        return allocator.getTotalAllocated();
    }

    static long allocateAddress(long bytes, boolean throwOOME)
    {
        long address = allocator.allocate(bytes);
        if (address != 0L)
        {
            allocated(address, bytes);
            return address;
        }
        if (throwOOME)
            throw new OutOfMemoryError("unable to allocate " + bytes + " in off-heap");
        return 0L;
    }

    static void free(long address)
    {
        if (address == 0L)
            return;
        freed(address);
        allocator.free(address);
    }

    static ByteBuffer directBufferFor(long address, long len)
    {
        if (len > Integer.MAX_VALUE || len < 0L)
            throw new IllegalArgumentException();
        // FFM zero-copy view; GLOBAL scope, lifetime managed by the allocator (explicit free),
        // replacing the previous sun.misc.Unsafe.allocateInstance + Buffer-field-offset hack.
        ByteBuffer bb = MemorySegment.ofAddress(address).reinterpret(len).asByteBuffer();
        bb.order(ByteOrder.BIG_ENDIAN);
        return bb;
    }
}
