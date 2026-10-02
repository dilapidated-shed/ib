package org.isomorphisms.ib.webview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
    public void provider_generation_survives_store_reconstruction() throws Exception {
        Path root = Files.createTempDirectory("ib-durable-generation");

        assertEquals(1, new DurableResultStore(root).next_provider_generation());
        assertEquals(2, new DurableResultStore(root).next_provider_generation());
        assertEquals(3, new DurableResultStore(root).next_provider_generation());
    }
}
