package com.ridehailing.driver.security;

import com.ridehailing.driver.exception.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * N03 (T07-11): giới hạn kích thước body 64 KiB cho /api/** và /internal/**.
 * Content-Length > 65536 -> trả 413 PAYLOAD_TOO_LARGE ngay (không đọc body).
 * Body chunked không có Content-Length: đếm số byte đã đọc; vượt 64 KiB ->
 * PayloadTooLargeException -> GlobalExceptionHandler trả 413 JSON.
 */
@Component
@Order(2)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = 65_536; // 64 KiB

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path == null || !(path.startsWith("/api/") || path.startsWith("/internal/"))) {
            chain.doFilter(request, response);
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            write413(response, CorrelationIdFilter.resolve(request));
            return;
        }
        if (contentLength < 0) {
            // Chunked/unknown length: đếm byte khi đọc.
            chain.doFilter(new CountingRequestWrapper(request), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private void write413(HttpServletResponse response, String correlationId) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Correlation-ID", correlationId);
        response.getWriter().write("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body exceeds 64 KiB.\","
                + "\"correlationId\":\"" + correlationId + "\",\"details\":null}");
    }

    /** Wrapper đếm byte đọc; vượt MAX_BODY_BYTES -> PayloadTooLargeException. */
    private static final class CountingRequestWrapper extends HttpServletRequestWrapper {
        private final CountingInputStream stream;

        CountingRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.stream = new CountingInputStream(request.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return stream.eof;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("setReadListener not supported");
                }

                @Override
                public int read() throws IOException {
                    return stream.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return stream.read(b, off, len);
                }
            };
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream in;
        private long count = 0;
        boolean eof = false;

        CountingInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value == -1) {
                eof = true;
            } else {
                bump(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n == -1) {
                eof = true;
            } else if (n > 0) {
                bump(n);
            }
            return n;
        }

        private void bump(int n) {
            count += n;
            if (count > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException("Request body exceeds 64 KiB.");
            }
        }
    }
}
