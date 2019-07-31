package com.jordanluyke.ezminecraftserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jordanluyke.ezminecraftserver.util.NodeUtil;
import io.reactivex.Completable;
import io.reactivex.Observable;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
@Getter
@Setter
@Singleton
public class Config {
    private static final Logger logger = LogManager.getLogger(Config.class);
    public static final String defaultMinecraftPath = System.getProperty("user.home") + "/minecraft";
    public static final String defaultMemoryAllocation = "1";
    public static final String cwd = System.getProperty("user.dir");
    public static final String configFile = "ez-config.json";
    public static final Path defaultConfigFilePath = Paths.get(cwd, configFile);

    private String path;
    private String version;
    private String memoryAllocation;

    public Observable<Boolean> load() {
        try {
            Optional<Path> configFilePath = getConfigFilePath();
            if(!configFilePath.isPresent())
                return Observable.just(false);
            byte[] bytes = Files.readAllBytes(configFilePath.get());
            JsonNode body = NodeUtil.getJsonNode(bytes);
            Optional<String> path = NodeUtil.get(body, "path");
            Optional<String> version = NodeUtil.get(body, "version");
            Optional<String> memoryAllocation = NodeUtil.get(body, "memoryAllocation");
            if(Stream.of(path, version, memoryAllocation).anyMatch(param -> !param.isPresent()))
                return Observable.just(false);
            setPath(path.get());
            setVersion(version.get());
            setMemoryAllocation(memoryAllocation.get());
            logger.info("Config loaded");
            return Observable.just(true);
        } catch(IOException e) {
            return Observable.error(new RuntimeException(e.getMessage()));
        }
    }

    public Completable save() {
        ObjectNode node = NodeUtil.mapper.createObjectNode();
        node.put("path", path);
        node.put("version", version);
        node.put("memoryAllocation", memoryAllocation);
        try {
            Path configFilePath = getConfigFilePath().orElse(defaultConfigFilePath);
            Files.write(configFilePath, NodeUtil.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
            logger.info("Config updated");
            return Completable.complete();
        } catch(IOException e) {
            return Completable.error(new RuntimeException("IOException"));
        }
    }

    private Optional<Path> getConfigFilePath() {
        return Stream.of(
                Paths.get(cwd, configFile),
                Paths.get(defaultMinecraftPath, configFile)
        )
                .filter(path -> Files.exists(path))
                .findFirst();
    }
}
