package com.jordanluyke.ezminecraftserver.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
public class NodeUtil {
    private static final Logger logger = LogManager.getLogger(NodeUtil.class);

    public static ObjectMapper mapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static boolean isValidJSON(byte[] json) {
        try {
            return !mapper.readTree(json).isNull();
        } catch(IOException e) {
            return false;
        }
    }

    public static JsonNode getJsonNode(byte[] json) {
        try {
            return mapper.readTree(json);
        } catch(IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public static byte[] writeValueAsBytes(Object o) {
        try {
            return mapper.writeValueAsBytes(o);
        } catch(JsonProcessingException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public static Optional<String> get(String field, JsonNode node) {
        JsonNode fieldNode = node.get(field);
        if(fieldNode == null || fieldNode.isNull())
            return Optional.empty();
        return Optional.of(fieldNode.asText());
    }

    public static String getOrThrow(String field, JsonNode node) {
        return get(field, node).orElseThrow(() -> new RuntimeException(field + " not found"));
    }

    public static Optional<Boolean> getBoolean(String field, JsonNode node) {
        return get(field, node).map(Boolean::valueOf);
    }

    public static Optional<Integer> getInteger(String field, JsonNode node) {
        return get(field, node).map(Integer::parseInt);
    }
}
