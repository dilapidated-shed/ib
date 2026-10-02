package org.isomorphisms.ib.webview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class DurableResultStoreTest {
    @Test
    public void fixture_is_immutable_and_reopenable() throws Exception {
        Path root = Files.createTempDirectory("ib-durable-result");
        DurableResultStore first = new DurableResultStore(root);
        Path result = first.commit_fixture();

        assertArrayEquals(DurableResultStore.RESULT_BYTES, Files.readAllBytes(result));

        DurableResultStore second = new DurableResultStore(root);
        assertEquals(result, second.commit_fixture());
        assertArrayEquals(
            DurableResultStore.RESULT_BYTES,
            Files.readAllBytes(second.require_result(DurableResultStore.RESULT_ID))
        );
    }

    @Test
    public void bounded_named_results_are_immutable_and_reopenable() throws Exception {
        Path root = Files.createTempDirectory("ib-durable-named-result");
        DurableResultStore store = new DurableResultStore(root);
        byte[] expected = "schema\tib-longview-useful-v1\nstatus\tuseful\n"
            .getBytes(StandardCharsets.UTF_8);

        Path result = store.commit_immutable("longview-heavy-v1", expected);
        assertArrayEquals(expected, Files.readAllBytes(result));
        assertEquals(result, store.commit_immutable("longview-heavy-v1", expected));

        try {
            store.commit_immutable(
                "longview-heavy-v1",
                "different\n".getBytes(StandardCharsets.UTF_8)
            );
            fail("conflicting result bytes must be rejected");
        } catch (IOException expected_conflict) {
            // Expected: immutable result identity cannot silently change bytes.
        }

        try {
            store.commit_immutable("../escape", expected);
            fail("unsafe result id must be rejected");
        } catch (IllegalArgumentException expected_invalid_id) {
            // Expected.
        }
    }

    @Test
    public void provider_generation_survives_store_reconstruction() throws Exception {
        Path root = Files.createTempDirectory("ib-durable-generation");

        assertEquals(1, new DurableResultStore(root).next_provider_generation());
        assertEquals(2, new DurableResultStore(root).next_provider_generation());
        assertEquals(3, new DurableResultStore(root).next_provider_generation());
    }
}
