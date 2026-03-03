package io.github.stefanrichterhuber.javatogo;

import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;

/**
 * Actual instance of an wasm file
 */
public final class GoWasmInstance {
    private static final Logger LOGGER = LogManager.getLogger();

    private final Instance instance;

    public GoWasmInstance(Instance instance) {
        this.instance = instance;
    }

    /**
     * Invokes a function exported by wasm
     * 
     * @param name Name of the function
     * @param args Arguments for the call
     * @return result of the call
     */
    public long[] invoke(String name, long[] args) {
        final long[] result = instance.export(name).apply(args);
        LOGGER.debug("Invoked wasm function {}({}) -> {}", () -> name,
                () -> args != null && args.length > 0
                        ? LongStream.of(args).mapToObj(l -> Long.toString(l)).collect(Collectors.joining(", "))
                        : "",
                () -> result != null && result.length > 0
                        ? LongStream.of(result).mapToObj(l -> Long.toString(l)).collect(Collectors.joining(", "))
                        : "Void");
        return result;
    }

    /**
     * Memory used by this instance. Necessary to read / write shared data
     * 
     * @return Memory instance
     */
    public Memory memory() {
        return instance.memory();
    }
}
