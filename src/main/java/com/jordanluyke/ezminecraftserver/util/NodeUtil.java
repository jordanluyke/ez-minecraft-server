package com.jordanluyke.ezminecraftserver.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.reactivex.Observable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Optional;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
public class NodeUtil {
    private static final Logger logger = LogManager.getLogger(NodeUtil.class);

    public static ObjectMapper mapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS, false);

    public static boolean isValidJSON(byte[] json) {
        try {
            return !mapper.readTree(json).isNull();
        } catch(IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public static JsonNode getJsonNode(byte[] json) {
        try {
            return mapper.readTree(json);
        } catch(IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public static <T> Observable<T> parseObjectNodeInto(JsonNode body, Class<T> clazz) {
        try {
            return Observable.just(mapper.treeToValue(body, clazz));
        } catch (Exception e) {
            logger.error("Json serialize fail: {}", e.getMessage());
            e.printStackTrace();
            for(Field field : clazz.getFields()) {
                field.setAccessible(true);
                String name = field.getName();
                if(body.get(name) == null)
                    return Observable.error(new RuntimeException("Field required: " + field));
            }
            return Observable.error(new RuntimeException("Parse fail"));
        }
    }

    public static <T> Observable<T> parseObjectNodeInto(Optional<JsonNode> body, Class<T> clazz) {
        return body.map(jsonNode -> parseObjectNodeInto(jsonNode, clazz)).orElseGet(() -> Observable.error(new RuntimeException("Empty body")));
    }

    public static Optional<String> get(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        if(fieldNode == null || fieldNode.isNull())
            return Optional.empty();
        return Optional.of(fieldNode.asText());
    }

    public static String getOrThrow(JsonNode node, String field) {
        return get(node, field).orElseThrow(() -> new RuntimeException("Unable to get: " + field));
    }

    public static Optional<Boolean> getBoolean(JsonNode node, String field) {
        return get(node, field).map(Boolean::valueOf);
    }

    public static Optional<Integer> getInteger(JsonNode node, String field) {
        return get(node, field).map(Integer::parseInt);
    }
}
