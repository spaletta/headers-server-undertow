package lol.headers.server.undertow;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Starter {
    private static final Logger LOGGER = LoggerFactory.getLogger(Starter.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpString TRACE_HEADER = new HttpString("X-Trace");
    private static String hostname;

    static {
        System.setErr(new PrintStream(System.err, true));
        System.setOut(new PrintStream(System.out, true));
    }

    public static void main(final String[] args) {
        LOGGER.trace("ENTRY");

        final int listenPort = Integer.parseInt(System.getProperty("listen.port", "8080"));
        final String listenHost = System.getProperty("listen.host", "::");

        try {
            hostname = System.getenv("HOSTNAME"); // POSIX
            if (hostname == null || hostname.equals("")) {
                hostname = System.getenv("COMPUTERNAME"); // WINDOWS
                if (hostname == null || hostname.equals("")) {
                    hostname = "<unknown>";
                }
            }
            OBJECT_MAPPER.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                    .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                    .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                    .registerModule(new JavaTimeModule());

            final RoutingHandler root = new RoutingHandler();

            root.get("/*", Starter::handle);
            root.get("/healthz", Starter::healthz);

            final Undertow server = Undertow.builder()
                    .addHttpListener(listenPort, listenHost)
                    .setHandler(Handlers.trace(root))
                    .build();
            server.start();
            System.err.println("Server started.");
        } catch (Exception e) {
            LOGGER.error("Bootstrap failed!", e);
        }

        LOGGER.trace("RETURN");
    }

    private static void healthz(final HttpServerExchange exchange) {
        LOGGER.trace("ENTRY");
        exchange.setStatusCode(StatusCodes.OK).endExchange();
        LOGGER.trace("RETURN");
    }

    private static void handle(final HttpServerExchange exchange) {
        LOGGER.trace("ENTRY");
        final Map<String, Float> accepted = new HashMap<>();

        final HeaderValues acceptHeader = exchange.getRequestHeaders().get("Accept");
        if (acceptHeader != null) {
            final String[] acceptValues = String.join(",", acceptHeader.toArray()).split(",");

            for (String value : acceptValues) {
                final String[] parts = value.split(";");
                final String type = parts.length > 0 ? parts[0].trim() : null;
                final String q = parts.length > 1 ? parts[1].trim() : "q=1";
                if (type != null && q.startsWith("q=")) {
                    try {
                        accepted.put(type.trim(), Float.parseFloat(q.substring(2)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        String contentType = "text/plain";
        if (accepted.containsKey("application/json") || accepted.containsKey("application/*")) {
            float jsonQ = 0F;
            float plainQ = 0F;
            if (accepted.containsKey("application/json")) {
                jsonQ = accepted.get("application/json");
            } else {
                if (accepted.containsKey("application/*")) {
                    jsonQ = accepted.get("application/*");
                }
            }
            if (accepted.containsKey("text/plain")) {
                plainQ = accepted.get("text/plain");
            } else {
                if (accepted.containsKey("text/*")) {
                    plainQ = accepted.get("text/*");
                }
            }
            if (jsonQ > plainQ) {
                contentType = "application/json";
            }
        }

        exchange.getResponseHeaders().put(TRACE_HEADER, hostname);
        exchange.getResponseHeaders().put(Headers.VARY, "Accept");
        respond(exchange, contentType);
        LOGGER.trace("RETURN");
    }

    private static void respond(final HttpServerExchange exchange, final String contentType) {
        LOGGER.trace("ENTRY");

        String json = null;
        try {
            final HeadersResponse response = new HeadersResponse();
            response.setTime(ZonedDateTime.now());
            response.setMe(hostname);
            response.setYou(exchange.getSourceAddress().getAddress().getHostAddress());
            response.setRequest(exchange.getRequestMethod() + " " + exchange.getRequestURI() +
                    " " + exchange.getProtocol().toString());
            response.setHeaders(fromHeaders(exchange.getRequestHeaders()));
            json = OBJECT_MAPPER.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Could not map JSON", e);
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR).endExchange();
        }

        if (json != null) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseSender().send(json);
        }

        LOGGER.trace("RETURN");
    }

    private static Map<String, Object> fromHeaders(final HeaderMap src) {
        final Map<String, Object> ret = new HashMap<>();

        for (HttpString name : src.getHeaderNames()) {
            final List<String> entries = src.get(name);
            if (entries.size() == 1) {
                ret.put(name.toString(), entries.get(0));
            } else {
                ret.put(name.toString(), entries);
            }
        }

        return ret;
    }

}
