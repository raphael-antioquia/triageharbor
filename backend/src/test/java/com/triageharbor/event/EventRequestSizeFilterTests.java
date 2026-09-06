package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EventRequestSizeFilterTests {

    private final EventRequestSizeFilter filter = new EventRequestSizeFilter();

    @Test
    void eventRequestAtLimitPassesThroughWithBodyIntact() throws Exception {
        byte[] body = new byte[EventRequestSizeFilter.MAX_REQUEST_BYTES];
        MockHttpServletRequest request = eventRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> downstreamBody = new AtomicReference<>();

        filter.doFilter(
                request,
                response,
                (wrappedRequest, wrappedResponse) ->
                        downstreamBody.set(wrappedRequest.getInputStream().readAllBytes()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(downstreamBody.get()).isEqualTo(body);
    }

    @Test
    void eventRequestOverLimitReturnsPayloadTooLarge() throws Exception {
        byte[] body = new byte[EventRequestSizeFilter.MAX_REQUEST_BYTES + 1];
        MockHttpServletRequest request = eventRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(
                request,
                response,
                (wrappedRequest, wrappedResponse) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chainCalled).isFalse();
    }

    @Test
    void limitIsEnforcedWithoutConsultingContentLength() throws Exception {
        byte[] body = new byte[EventRequestSizeFilter.MAX_REQUEST_BYTES + 1];
        AtomicBoolean lengthConsulted = new AtomicBoolean();

        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/events") {
                    @Override
                    public int getContentLength() {
                        lengthConsulted.set(true);
                        return super.getContentLength();
                    }

                    @Override
                    public long getContentLengthLong() {
                        lengthConsulted.set(true);
                        return super.getContentLengthLong();
                    }
                };
        request.setContent(body);

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(
                request,
                response,
                (wrappedRequest, wrappedResponse) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chainCalled).isFalse();
        assertThat(lengthConsulted).isFalse();
    }

    private MockHttpServletRequest eventRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/events");
        request.setContent(body);
        return request;
    }
}
