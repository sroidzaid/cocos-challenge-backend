package com.cocos.challenge.shared.infrastructure.web;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void reusesWellFormedIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123_X.y");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringRequest));

        assertThat(mdcDuringRequest.get()).isEqualTo("abc-123_X.y");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123_X.y");
    }

    @Test
    void generatesIdWhenHeaderIsAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response, capturingMdc(mdcDuringRequest));

        assertThat(mdcDuringRequest.get()).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(mdcDuringRequest.get());
    }

    @Test
    void replacesMalformedIdToPreventLogInjection() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "evil\nFAKE LOG LINE");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, capturingMdc(mdcDuringRequest));

        assertThat(mdcDuringRequest.get()).doesNotContain("evil").doesNotContain("\n");
    }

    @Test
    void replacesOverlongId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "a".repeat(65));
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingMdc(mdcDuringRequest));

        assertThat(mdcDuringRequest.get()).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void clearsMdcAfterRequestEvenOnFailure() {
        FilterChain failingChain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failingChain);
        } catch (Exception ignored) {
            // expected
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private static FilterChain capturingMdc(AtomicReference<String> holder) {
        return (req, res) -> holder.set(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
