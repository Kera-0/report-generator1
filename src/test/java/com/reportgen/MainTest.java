package com.reportgen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void runReturnsUsageCodeWithoutArgs() {
        assertThat(Main.run(new String[0])).isEqualTo(1);
    }
}
