package com.nexusai.application.agent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Native installer backed by the XDG data directory. */
@Component
public class NativeInstaller {

    private static final Logger log = LoggerFactory.getLogger(NativeInstaller.class);
    private static final ConcurrentHashMap<String, CompletableFuture<InstallResult>> IN_FLIGHT =
        new ConcurrentHashMap<>();

    @Autowired private PidLock pidLock;
    @Autowired private Download download;
    @Autowired private PackageManagers packageManagers;

    public record Manifest(String version, String url, String expectedSha256, long size) {}
    public record InstallResult(String version, String path, long installedAt) {}
    public record InstalledVersion(String version, String path, boolean active, long installedAt) {}

    public CompletableFuture<InstallResult> installLatest(Manifest manifest) {
        return IN_FLIGHT.computeIfAbsent(manifest.version(), version ->
            CompletableFuture.supplyAsync(() -> installManifest(manifest))
                .whenComplete((result, failure) -> IN_FLIGHT.remove(version)));
    }

    private InstallResult installManifest(Manifest manifest) {
        PidLock.AcquiredLock lock = pidLock.acquire(manifest.version());
        Path staging = stagingDir().resolve(manifest.version() + ".tmp");
        Path target = versionsDir().resolve(manifest.version()).resolve(executableName());
        try {
            Files.createDirectories(staging.getParent());
            Files.deleteIfExists(staging);
            packageManagers.detect();
            download.download(manifest.url(), staging, manifest.expectedSha256());
            if (manifest.size() >= 0 && Files.size(staging) != manifest.size()) {
                throw new IOException("download size mismatch: expected=" + manifest.size()
                    + " actual=" + Files.size(staging));
            }
            Files.createDirectories(target.getParent());
            moveAtomically(staging, target);
            makeExecutable(target);
            long installedAt = Files.getLastModifiedTime(target).toMillis();
            log.info("NativeInstaller installed version={} path={}", manifest.version(), target);
            return new InstallResult(manifest.version(), target.toString(), installedAt);
        } catch (IOException ioe) {
            throw new CompletionException(ioe);
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ioe) {
                log.warn("NativeInstaller staging cleanup failed path={}", staging, ioe);
            }
            pidLock.release(lock);
        }
    }

    public void setActive(String version) {
        Path executable = versionsDir().resolve(version).resolve(executableName());
        if (!Files.isRegularFile(executable)) {
            throw new IllegalArgumentException("version not installed: " + version);
        }
        try {
            Files.createDirectories(baseDir());
            Path temporary = baseDir().resolve("active.tmp");
            Files.writeString(temporary, version, StandardCharsets.UTF_8);
            moveAtomically(temporary, activeFile());
            log.info("NativeInstaller activated version={}", version);
        } catch (IOException ioe) {
            throw new IllegalStateException("failed to activate version=" + version, ioe);
        }
    }

    public InstalledVersion getActive() {
        try {
            if (!Files.isRegularFile(activeFile())) return null;
            String version = Files.readString(activeFile(), StandardCharsets.UTF_8).trim();
            return listInstalled().get(version);
        } catch (IOException ioe) {
            throw new IllegalStateException("failed to read active version", ioe);
        }
    }

    public Map<String, InstalledVersion> listInstalled() {
        Map<String, InstalledVersion> result = new LinkedHashMap<>();
        String active = readActiveVersion();
        if (!Files.isDirectory(versionsDir())) return Map.of();
        try (var paths = Files.list(versionsDir())) {
            paths.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).forEach(dir -> {
                Path executable = dir.resolve(executableName());
                if (Files.isRegularFile(executable)) {
                    try {
                        String version = dir.getFileName().toString();
                        result.put(version, new InstalledVersion(version, executable.toString(),
                            version.equals(active), Files.getLastModifiedTime(executable).toMillis()));
                    } catch (IOException ioe) {
                        throw new CompletionException(ioe);
                    }
                }
            });
            return Map.copyOf(result);
        } catch (IOException ioe) {
            throw new IllegalStateException("failed to list installed versions", ioe);
        }
    }

    private String readActiveVersion() {
        try {
            return Files.isRegularFile(activeFile())
                ? Files.readString(activeFile(), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException ioe) {
            throw new IllegalStateException("failed to read active version", ioe);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | java.nio.file.AccessDeniedException e) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(source);
        }
    }

    private static void makeExecutable(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            if (!path.toFile().setExecutable(true, false)) {
                throw new IOException("failed to mark executable: " + path);
            }
        }
    }

    private static Path baseDir() {
        String xdg = System.getenv("XDG_DATA_HOME");
        Path dataHome = xdg == null || xdg.isBlank()
            ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(xdg);
        return dataHome.resolve("nexusai").resolve("native-installer");
    }

    private static Path versionsDir() { return baseDir().resolve("versions"); }
    private static Path stagingDir() { return baseDir().resolve("staging"); }
    private static Path activeFile() { return baseDir().resolve("active"); }
    private static String executableName() { return PackageManagers.isWindows() ? "nexusai.exe" : "nexusai"; }
}
