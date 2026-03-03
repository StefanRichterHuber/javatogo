package io.github.stefanrichterhuber.javatogo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import io.github.stefanrichterhuber.javatogo.wasm.GoCompilerWasm;
import io.github.stefanrichterhuber.javatogo.wasm.GoLinkerWasm;

/**
 * Go compiler for java, with Chicory as the runtime
 */
public class GoCompiler {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Record to store source files for the build
     */
    public record SourceFile(String name, String source, boolean mainFile) {
    }

    /**
     * Gets the context class loader
     * 
     * @return Context class loader
     */
    private static ClassLoader getContextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    /**
     * Gets a resource as an input stream
     * 
     * @param resource Resource to get
     * @return Input stream for the resource
     */
    private static InputStream getResourceAsStream(String resource) {
        final InputStream in = getContextClassLoader().getResourceAsStream(resource);

        return in == null ? GoCompiler.class.getResourceAsStream(resource) : in;
    }

    /**
     * Extracts a zip file to the target directory
     * 
     * @param zipStream Zip file to extract
     * @param targetDir Target directory to extract to
     * @throws IOException
     */
    private static void extractZip(InputStream zipStream, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path targetPath = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(zis, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Compiles the given go source to a wasm file
     * 
     * @param src Source to build
     * @return WasmFile containing the result
     * @throws IOException
     */
    public GoWasm compile(final String src) throws IOException {
        return compile(new SourceFile("main.go", src, true));
    }

    /**
     * Compiles the given go source file to a wasm file
     * 
     * @param file Source file to build
     * @return WasmFile containing the result
     * @throws IOException
     */
    public GoWasm compile(final SourceFile file) throws IOException {
        final List<SourceFile> files = List.of(new SourceFile(file.name(), file.source(), true));
        return compile(files);
    }

    private static volatile FileSystem CACHED_GOROOT_VFS;
    private static volatile Path CACHED_GOROOT_PATH;

    /**
     * Gets the cached go root path, extracting it if necessary
     * 
     * @return Go root path
     * @throws IOException
     */
    private static Path getGoRoot() throws IOException {
        if (CACHED_GOROOT_VFS == null) {
            synchronized (GoCompiler.class) {
                if (CACHED_GOROOT_VFS == null) {
                    final FileSystem vfs = Jimfs.newFileSystem(Configuration.unix().toBuilder()
                            .setAttributeViews("basic", "owner", "posix", "unix").build());
                    final Path vfsGoroot = vfs.getPath("/usr/local/go");
                    Files.createDirectories(vfsGoroot);

                    LOGGER.debug("Extracting go root to the cached vfs...");
                    try (InputStream is = getResourceAsStream("goroot.zip")) {
                        if (is == null) {
                            throw new IOException("Could not find goroot.zip in resources");
                        }
                        extractZip(is, vfsGoroot);
                    }
                    LOGGER.debug("Go root extraction complete.");
                    CACHED_GOROOT_PATH = vfsGoroot;
                    CACHED_GOROOT_VFS = vfs;
                }
            }
        }
        return CACHED_GOROOT_PATH;
    }

    /**
     * Compiles the given go source files to a wasm file
     * 
     * @param files Source files for the build
     * @return WasmFile containing the result
     * @throws IOException
     */
    public GoWasm compile(final List<SourceFile> files) throws IOException {
        final Path vfsGoroot = getGoRoot();

        // Boot up the in-memory File System for workspace
        try (FileSystem vfs = Jimfs.newFileSystem(Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix", "unix").build())) {

            // 1. Setup an in-memory workspace for our project files
            LOGGER.debug("Setup workspace in the vfs with the source file");
            final Path vfsWorkspace = vfs.getPath("/workspace");
            Files.createDirectories(vfsWorkspace);

            String mainFile = null;
            for (SourceFile file : files) {
                final Path sourceFile = vfsWorkspace.resolve(file.name());
                Files.writeString(sourceFile, file.source());
                LOGGER.debug("Source file written to the vfs: {}", sourceFile);
                if (file.mainFile()) {
                    mainFile = file.name();
                }
            }

            if (mainFile == null) {
                throw new IllegalStateException("No main file defined for the build!");
            }

            // 3. Generate importcfg.txt dynamically inside the VFS
            LOGGER.debug("Load importcfg.txt from resources and write it to the vfs");
            try (InputStream is = getResourceAsStream("importcfg.txt")) {
                if (is == null) {
                    throw new IOException("Could not find importcfg.txt in resources");
                }
                Files.copy(is, vfsWorkspace.resolve("importcfg.txt"), StandardCopyOption.REPLACE_EXISTING);
            }

            final String aFile = mainFile.replace(".go", ".a");
            final String wasmFile = mainFile.replace(".go", ".wasm");

            LOGGER.debug("Invoke compiler");
            compile(
                    vfsWorkspace,
                    vfsGoroot,
                    List.of("gocompiler.wasm", "-p", "main", "-complete", "-importcfg", "/workspace/importcfg.txt",
                            "-pack", "-o", String.format("/workspace/%s", aFile),
                            String.format("/workspace/%s", mainFile)));
            LOGGER.debug("Invoke linker");
            link(
                    vfsWorkspace,
                    vfsGoroot,
                    List.of("golinker.wasm", "-L", "/usr/local/go/pkg/wasip1_wasm", "-importcfg",
                            "/workspace/importcfg.txt", "-o", String.format("/workspace/%s", wasmFile),
                            String.format("/workspace/%s", aFile)));

            LOGGER.debug("Wasm file created");
            final Path appWasmVfs = vfsWorkspace.resolve(wasmFile);
            final byte[] code = Files.readAllBytes(appWasmVfs);
            return new GoWasm(wasmFile, code);
        }
    }

    /**
     * Helper method to execute the compiler as WASM file
     * but strictly sandbox their I/O within the Jimfs VFS.
     * 
     * @param vfsWorkspace Workspace for the build
     * @param vfsGoroot    Go root directory
     * @param args         Call args
     * @throws IOException
     */
    private static void compile(Path vfsWorkspace, Path vfsGoroot, List<String> args) throws IOException {
        final WasiOptions wasiOpts = WasiOptions.builder()
                .withDirectory("/workspace", vfsWorkspace)
                .withDirectory("/usr/local/go", vfsGoroot)
                .withEnvironment("GOROOT", "/usr/local/go")
                .withArguments(args)
                .withStdout(System.out)
                .withStderr(System.err)
                .withThrowOnExit0(false)
                .build();

        final WasiPreview1 wasi = WasiPreview1.builder()
                .withOptions(wasiOpts)
                .build();
        final Store store = new Store().addFunction(wasi.toHostFunctions());
        final WasmModule module = GoCompilerWasm.load();
        final Instance instance = Instance.builder(module).withImportValues(store.toImportValues())
                .withMachineFactory(GoCompilerWasm::create).build();

        // Start function is called during instantiation of the module anyway
        // instance.export("_start").apply();
    }

    /**
     * Helper method to execute the linker as WASM files
     * but strictly sandbox their I/O within the Jimfs VFS.
     * 
     * @param vfsWorkspace Workspace for the build
     * @param vfsGoroot    Go root directory
     * @param args         Call args
     * @throws IOException
     */
    private static void link(Path vfsWorkspace, Path vfsGoroot, List<String> args) throws IOException {
        final WasiOptions wasiOpts = WasiOptions.builder()
                .withDirectory("/workspace", vfsWorkspace)
                .withDirectory("/usr/local/go", vfsGoroot)
                .withEnvironment("GOROOT", "/usr/local/go")
                .withArguments(args)
                .withStdout(System.out)
                .withStderr(System.err)
                .withThrowOnExit0(false)
                .build();

        final WasiPreview1 wasi = WasiPreview1.builder()
                .withOptions(wasiOpts)
                .build();
        final Store store = new Store().addFunction(wasi.toHostFunctions());
        final WasmModule module = GoLinkerWasm.load();
        final Instance instance = Instance.builder(module).withImportValues(store.toImportValues())
                .withMachineFactory(GoLinkerWasm::create).build();

        // Start function is called during instantiation of the module anyway
        // instance.export("_start").apply();
    }
}
