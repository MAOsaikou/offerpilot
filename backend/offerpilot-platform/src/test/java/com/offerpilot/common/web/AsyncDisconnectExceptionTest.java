package com.offerpilot.common.web;

import com.offerpilot.common.error.GlobalExceptionHandler;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncDisconnectExceptionTest {
    @Test void asyncBrokenPipeDoesNotAttemptAnotherResponseButOrdinaryIoFailureKeepsHttp500() {
        var handler = new GlobalExceptionHandler();
        var request = new MockHttpServletRequest();
        var failure = new IOException("Broken pipe");
        request.setDispatcherType(DispatcherType.ASYNC);
        assertThat(handler.handleUnknown(failure, request)).isNull();
        request.setDispatcherType(DispatcherType.REQUEST);
        assertThat(handler.handleUnknown(failure, request).getStatusCode().value()).isEqualTo(500);
    }
}
