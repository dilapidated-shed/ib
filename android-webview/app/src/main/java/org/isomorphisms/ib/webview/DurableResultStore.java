package org.isomorphisms.ib.webview;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;

/** Small app-private immutable result store shared by Longview and provider experiments. */
public final class DurableResultStore {
    public static final String RESULT_ID = "hello-v1";
    public static final byte[] RESULT_BYTES = "hello\n".getBytes(StandardCharsets.UTF_8);
    public static final int MAX_RESULT_BYTES = 4096;

    private final Path root;

    public DurableResultStore(Path files_root) {
        root = files_root.resolve("durable-results");
    }

    public Path commit_fixture() throws IOException {
        return commit_immutable(RESULT_ID, RESULT_BYTES);
    }

    public Path commit_immutable(String result_id, byte[] bytes) throws IOException {
        validate_result_id(result_id);
        if (bytes == null) {
            throw new IllegalArgumentException("durable result bytes are required");
        }
        if (bytes.length > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("durable result exceeds byte limit");
        }

        Files.createDirectories(root);
        Path target = result_path(result_id);
        if (Files.exists(target)) {
            verify_equal(target, bytes);
            return target;
        }

        Path temporary = root.resolve("." + result_id + "." + UUID.randomUUID() + ".tmp");
        write_synced(temporary, bytes);
        try {
            move_atomically(temporary, target, false);
        } catch (java.nio.file.FileAlreadyExistsException exception) {
            Files.deleteIfExists(temporary);
        }
        verify_equal(target, bytes);
        return target;
    }

    public Path require_result(String result_id) throws IOException {
        Path result = result_path(result_id);
        if (!Files.isRegularFile(result)) {
            throw new IOException("durable result is not committed: " + result_id);
        }
        return result;
    }

    public long next_provider_generation() throws IOException {
        Files.createDirectories(root);
        Path generation_path = root.resolve("provider-generation");
        long previous = 0;
        if (Files.exists(generation_path)) {
            String text = new String(
                Files.readAllBytes(generation_path),
                StandardCharsets.US_ASCII
            ).trim();
            if (!text.isEmpty()) {
                previous = Long.parseLong(text);
            }
        }

        long next = Math.addExact(previous, 1);
        Path temporary = root.resolve(".provider-generation." + UUID.randomUUID() + ".tmp");
        write_synced(
            temporary,
            (Long.toString(next) + "\n").getBytes(StandardCharsets.US_ASCII)
        );
        move_atomically(temporary, generation_path, true);
        return next;
    }

    private Path result_path(String result_id) {
        validate_result_id(result_id);
        return root.resolve(result_id + ".txt");
    }

    private static void validate_result_id(String result_id) {
        if (result_id == null
            || result_id.isEmpty()
            || ".".equals(result_id)
            || "..".equals(result_id)
            || !result_id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid durable result id");
        }
    }

    private static void verify_equal(Path target, byte[] expected) throws IOException {
        byte[] actual = Files.readAllBytes(target);
        if (!Arrays.equals(actual, expected)) {
            throw new IOException("existing durable result differs from committed bytes");
        }
    }

    private static void write_synced(Path path, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(path.toFile())) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static void move_atomically(Path from, Path to, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(
                    from,
                    to,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } else {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(from, to);
            }
        }
    }
}
