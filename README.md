# JavaToGo

JavaToGo is a **totally not production-ready** java library to run Go code in the JVM. It has **no native dependencies at runtime**, but leverages WASM and [Chicory](https://chicory.dev/) to compile Go code to JVM bytecode. 

## Why?

Proof of concept for embedding Go code in the JVM, without native dependencies at runtime, avoiding the need for a full Go runtime. More specifically, to avoid the need for JNI / Foreign Function and Memory API and the need to bundle platform-specific native libraries.

## Concept

* Compile the Go compiler, linker and standard library to WASM (wasip1)
* Optimize the wasm files with `wasm-opt`
* Compile the go compiler and linker wasm to bytecode using chicory build-time compiler
* In the java code use the go compiler and linker to build go files to wasm at runtime. Then compile the wasm files to bytecode at runtime with chicory

## Open challenges

* **Performance**: A wasm based go compiler is quite slow, even most simple code takes several seconds to be compiled. Compiling the generated wasm file to bytecode takes further seconds. All in all, caching is definitly necessary to get usable performance. 

* **Size**: Since the final library jar contains the go compiler and linker as well as the whole go standard library it is around 160 Mb, which is quite heavy. Moreover, even most simple go programs, when compiled to wasm and transpiled to byte code produce at least 700 kb of byte code due to the whole go runtime included.

## Architecture

The heavy lifting is done in the [Dockerfile.gowasm](./src/main/docker/Dockerfile.gowasm).
It builds the go standard library, the go compiler and linker to wasm. Afterwards all wasm files are optimized with `wasm-opt` and compiled to byte code (with interpreter fallback) using chicory. The standard library is packed into a zip file. The wasm code is compiled to bytecode right in the dockerfile and not in the main maven project, because this is a very costly process, and the change detection works far better for the Docker build than for maven. 

The main pom.xml invokes the docker file and copies the necessary files to the target directory. 

The class `io.github.stefanrichterhuber.javatogo.GoCompiler` uses both the linker and compiler byte code to build go files to wasm at runtime. It prepares a virtual file system (using [Jimfs](https://github.com/google/jimfs)) with the go root files and the source file(s), then invokes the compiler and linker byte code to build the wasm file.

This library uses log4j2 for logging on the java side and MessagePack to efficiently save the bytecode cache.

## How to build

The project is a maven project, so you can build it with `mvn clean install`. It requires Java 21+ and Docker. 

## How to use

Import the library (not yet published on maven central)

```xml
<dependency>
    <groupId>io.github.stefanrichterhuber</groupId>
    <artifactId>javatogo</artifactId>
    <version>[current version]</version>
</dependency>
```

And just use the class `io.github.stefanrichterhuber.javatogo.GoCompiler` to compile some go code to wasm.

```java
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
                // do nothing, but required.
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


```

## License

Licensed under MIT License ([LICENSE](LICENSE) or <http://opensource.org/licenses/MIT>)
