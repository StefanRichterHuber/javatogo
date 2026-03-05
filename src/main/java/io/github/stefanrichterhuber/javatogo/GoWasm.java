package io.github.stefanrichterhuber.javatogo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import com.dylibso.chicory.compiler.Cache;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

/**
 * Result of a successful build: A go file compiled to wasm and ready to run
 * 
 */
public final class GoWasm {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Simple map-based cache
     */
    private static class InternalCache implements Cache {
        private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

        @Override
        public byte[] get(String key) throws IOException {
            return cache.get(key);
        }

        @Override
        public void putIfAbsent(String key, byte[] data) throws IOException {
            cache.put(key, data);
        }

        /**
         * Saves the cache content to the given OutputStream
         * 
         * @param os
         * @throws IOException
         */
        public void save(OutputStream os) throws IOException {
            if (os == null) {
                throw new IllegalArgumentException("Outputstream must not be null");
            }
            try (MessagePacker packer = MessagePack.newDefaultPacker(os)) {
                packer.packMapHeader(cache.size());
                for (Map.Entry<String, byte[]> entry : cache.entrySet()) {
                    packer.packString(entry.getKey());
                    packer.packBinaryHeader(entry.getValue().length);
                    packer.addPayload(entry.getValue());
                }
            }
        }

        /**
         * Loads cache contents from the given InputStream
         * 
         * @param is InputStream to load from
         * @throws IOException
         */
        public void load(InputStream is) throws IOException {
            if (is == null) {
                return;
            }
            try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(is)) {
                final int entries = unpacker.unpackMapHeader();
                for (int i = 0; i < entries; i++) {
                    final String key = unpacker.unpackString();
                    final int valueLen = unpacker.unpackBinaryHeader();
                    final byte[] value = unpacker.readPayload(valueLen);
                    this.cache.put(key, value);
                }
            }
        }
    }

    private final byte[] wasm;
    private final String name;
    private static final InternalCache cache = new InternalCache();

    public GoWasm(String name, byte[] wasm) {
        this.name = name;
        this.wasm = wasm;
    }

    /**
     * Name of this wasm file, might be null
     * 
     * @return Name
     */
    public String getName() {
        return this.name;
    }

    /**
     * Raw wasm file
     * 
     * @return
     */
    public byte[] getWasm() {
        return this.wasm;
    }

    /**
     * Creates a new compiled instance of this wasm module, with stdout, stderr and
     * stdio
     * bound to System.out, System.err and System.in
     * 
     * @return
     */
    public GoWasmInstance createInstance() {
        return createInstance(null, null, null);
    }

    /**
     * Creates a new compiled instance of this wasm module
     * 
     * @param stdout The output stream to use for stdout, if null System.out is
     *               used
     * @param stderr The output stream to use for stderr, if null System.err is
     *               used
     * @param stdin  The input stream to use for stdin, if null System.in is used
     * @return
     */
    public GoWasmInstance createInstance(OutputStream stdout, OutputStream stderr,
            InputStream stdin) {

        LOGGER.debug("Create instance of wasm file {}", getName());
        final WasiOptions wasiOpts = WasiOptions.builder()
                .withStdout(stdout == null ? System.out : stdout)
                .withStderr(stderr == null ? System.err : stderr)
                .withStdin(stdin == null ? System.in : stdin)
                .withRandom(new Random())
                .withThrowOnExit0(false)
                .build();

        final WasiPreview1 wasi = WasiPreview1.builder()
                .withOptions(wasiOpts)
                .build();
        final Store store = new Store().addFunction(wasi.toHostFunctions());

        final WasmModule module = Parser.parse(wasm);

        final Instance instance = Instance.builder(module).withImportValues(store.toImportValues())
                .withMachineFactory(
                        MachineFactoryCompiler.builder(module).withCache(cache).compile())
                .build();
        return new GoWasmInstance(instance);
    }

    /**
     * Stores the cache for the wasm compiled to bytecode
     * 
     * @param os OutputStream to write to
     * @throws IOException
     */
    public static void saveByteCodeCache(OutputStream os) throws IOException {
        cache.save(os);
    }

    /**
     * Loads the cache for the wasm compiled to bytecode
     * 
     * @param is InputStream to read from
     * @throws IOException
     */
    public static void loadByteCodeCache(InputStream is) throws IOException {
        cache.load(is);
    }

    /**
     * Saves the wasm content to the given OutputStream
     * 
     * @param os OutputStream to write
     * @throws IOException
     */
    public void save(OutputStream os) throws IOException {
        os.write(wasm);
    }

    /**
     * Creates a GoWasm instance from the wasm in the given InputStream
     * 
     * @param is InputStream to read
     * @return GoWasm instance creates
     * @throws IOException
     */
    public static GoWasm load(InputStream is) throws IOException {
        byte[] content = is.readAllBytes();
        return new GoWasm(null, content);
    }
}
