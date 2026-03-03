package io.github.stefanrichterhuber.javatogo;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

public class GoCompilerTest {

    private static final Logger LOGGER = LogManager.getLogger();

    private static String EXPORT_FUNC_CODE = """
            package main

            import "fmt"

            //go:wasmexport MyFunc
            func MyFunc() {
                fmt.Println("MyFunc")
            }

            func main() {
                // do nothing -> dummy necessary for init
            }
            """;

    @Test
    public void testExportFunc() throws IOException {
        GoCompiler c = new GoCompiler();

        GoWasm result = c.compile(EXPORT_FUNC_CODE);
        assertNotNull(result);

        // The byte code for the wasm files is cached, and the cache could be written to
        // a file
        File cacheFile = new File("bytecode.cache");
        if (cacheFile.exists()) {
            try (FileInputStream fis = new FileInputStream(cacheFile)) {
                GoWasm.loadByteCodeCache(fis);
            }
        }

        GoWasmInstance instance = result.createInstance();
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            GoWasm.saveByteCodeCache(fos);
        }

        LOGGER.info("Invoking wasm");
        long[] r = instance.invoke("MyFunc", new long[] {});

    }

    private static String EXPORT_FUNC_WITH_STRING_PARAMETER_CODE = """
            package main

            import ( "fmt"
            "unsafe"
            )
            // We use a map to "pin" the memory so the GC sees an active reference.
            // The key is the pointer address (uintptr).
            var activeAllocations = make(map[uintptr][]byte)

            //go:wasmexport allocate
            func allocate(size uint32) uintptr {
                // 1. Create the buffer
                buf := make([]byte, size)

                // 2. Get the pointer to the first element
                ptr := uintptr(unsafe.Pointer(&buf[0]))

                // 3. STORE it in the global map.
                // As long as it's in this map, the GC will NOT collect it.
                activeAllocations[ptr] = buf

                return ptr
            }

            //go:wasmexport deallocate
            func deallocate(ptr uintptr) {
                // 4. Remove it from the map.
                // Once removed, if no other Go code is using it, the next GC cycle
                // will safely reclaim this memory.
                delete(activeAllocations, ptr)
            }

            // Receive a string from Java.
            // Java calls this with the pointer and length it just used.
            //go:wasmexport greet
            func greet(ptr uintptr, size uint32) {
                // 1. Retrieve the buffer from our "pinned" map
                buf, autoExists := activeAllocations[ptr]
                if !autoExists {
                    // Fallback: If for some reason it's not in the map,
                    // reconstruct it from raw memory (riskier if GC ran)
                    buf = unsafe.Slice((*byte)(unsafe.Pointer(ptr)), size)
                }

                // 2. Use the data (e.g., convert to string)
                input := string(buf[:size])
                fmt.Println("Go received: " + input)
            }

            //go:wasmexport get_message_packed
            func get_message_packed() uint64 {
                msg := "Hello"
                buf := []byte(msg)
                ptr := uint32(uintptr(unsafe.Pointer(&buf[0])))
                length := uint32(len(buf))

                activeAllocations[uintptr(ptr)] = buf

                // Pack: Pointer in high 32 bits, Length in low 32 bits
                return (uint64(ptr) << 32) | uint64(length)
            }

            func main() {
                // do nothing -> dummy necessary for init
            }
                """;

    /**
     * Tests exporting a string value into the wasm memory
     * 
     * @throws IOException
     */
    @Test
    public void testExportWithStringParameter() throws IOException {
        GoCompiler c = new GoCompiler();

        GoWasm result = c.compile(EXPORT_FUNC_WITH_STRING_PARAMETER_CODE);
        assertNotNull(result);
        GoWasmInstance instance = result.createInstance();

        LOGGER.info("Invoking wasm");
        String helloWorld = "Hello World";
        byte[] helloWorldBytes = helloWorld.getBytes(StandardCharsets.UTF_8);
        long len = helloWorldBytes.length;
        long[] r = instance.invoke("allocate", new long[] { len });
        long ptr = r[0];

        instance.memory().writeString((int) ptr, helloWorld, StandardCharsets.UTF_8);

        long[] r2 = instance.invoke("greet", new long[] { ptr, len });

        // Properly cleanup
        instance.invoke("deallocate", new long[] { ptr });
    }

    /**
     * Tests importing a string value from the wasm memory
     * 
     * @throws IOException
     */
    @Test
    public void testReturnOfStringResult() throws IOException {
        GoCompiler c = new GoCompiler();

        GoWasm result = c.compile(EXPORT_FUNC_WITH_STRING_PARAMETER_CODE);
        assertNotNull(result);
        GoWasmInstance instance = result.createInstance();

        long[] r = instance.invoke("get_message_packed", new long[] {});
        long ptr = r[0] >> 32;
        long len = r[0] & 0xFFFFFFFF;

        String message = instance.memory().readString((int) ptr, (int) len, StandardCharsets.UTF_8);
        LOGGER.info("Message: " + message);

        // Properly cleanup
        instance.invoke("deallocate", new long[] { ptr });
    }
}
