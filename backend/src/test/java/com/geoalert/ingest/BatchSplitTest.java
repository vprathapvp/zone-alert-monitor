package com.geoalert.ingest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BatchSplitTest {

    @Test
    void numbersOnlyNonEmptyLinesAndDropsCarriageReturns() {
        var lines = IngestController.split("a\r\n\n  \nb\nc".getBytes(StandardCharsets.UTF_8));
        assertThat(lines).extracting(BatchLine::lineNo).containsExactly(1, 2, 3);
        assertThat(lines).extracting(BatchLine::text).containsExactly("a", "b", "c");
    }

    @Test
    void invalidUtf8BecomesAReplacementCharacterNotAnError() {
        byte[] body = {'{', (byte) 0xC3, (byte) 0x28, '}', '\n'};
        var lines = IngestController.split(body);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).raw()).containsExactly('{', (byte) 0xC3, (byte) 0x28, '}');
    }
}
