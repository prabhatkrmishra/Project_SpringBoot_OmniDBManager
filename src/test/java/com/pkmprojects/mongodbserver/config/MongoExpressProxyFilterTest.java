package com.pkmprojects.mongodbserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MongoExpressProxyFilter}: a request path that cannot
 * form a valid upstream URI is answered with 400 (never an unhandled 500),
 * without any outbound call.
 */
class MongoExpressProxyFilterTest {

    private final MongoExpressProxyFilter filter =
            new MongoExpressProxyFilter("http://127.0.0.1:9814/mongo-express", "admin", "admin",
                    java.net.http.HttpClient.newHttpClient());

    @Test
    void rejectsUnparseableRequestPathWith400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mongo-express/a b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("cannot be proxied");
    }

    @Test
    void rejectsInvalidPercentSequenceWith400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mongo-express/%zz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
    }
}
