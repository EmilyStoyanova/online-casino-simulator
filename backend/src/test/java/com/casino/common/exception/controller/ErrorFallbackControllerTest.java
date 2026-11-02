package com.casino.common.exception.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Edge cases of the {@code /error} fallback with the attributes the servlet container sets on a
 * forward. The forwarding itself (including the security filter chain on the error dispatch) is covered
 * by {@code ErrorHandlingIT}.
 */
@WebMvcTest(ErrorFallbackController.class)
@AutoConfigureMockMvc(addFilters = false)
class ErrorFallbackControllerTest {

    private static final String SECRET = "secret container detail";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void keepsTheForwardedStatusAndHidesTheExceptionAndMessage() throws Exception {
        mockMvc.perform(post("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 413)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/uploads")
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, SECRET)
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(SECRET))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is(413))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CONTENT_TOO_LARGE"))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.instance").value("/api/v1/uploads"))
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void forwardedStatusThatIsNotAnErrorBecomesAnInternalError() throws Exception {
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 200))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void directRequestIsNotFound() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/error"));
    }
}
