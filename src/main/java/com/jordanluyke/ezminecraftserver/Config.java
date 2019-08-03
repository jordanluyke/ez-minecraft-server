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
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
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

    public Completable load() {
        try {
            Optional<Path> configFilePath = getConfigFilePath();
            if(!configFilePath.isPresent())
                return setup();
            byte[] bytes = Files.readAllBytes(configFilePath.get());
            JsonNode body = NodeUtil.getJsonNode(bytes);
            NodeUtil.get(body, "path").ifPresent(p -> path = p);
            NodeUtil.get(body, "version").ifPresent(v -> version = v);
            NodeUtil.get(body, "memoryAllocation").ifPresent(m -> memoryAllocation = m);
            if(Stream.of(path, version, memoryAllocation).anyMatch(Objects::isNull))
                return setup();
            logger.info("Config loaded");
            return Completable.complete();
        } catch(IOException e) {
            return Completable.error(new RuntimeException(e.getMessage()));
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

    private Completable setup() {
        Scanner scanner = new Scanner(System.in);

        System.out.print(String.format("Path: (~/minecraft)"));
        String pathInput = scanner.nextLine().trim();
        path = pathInput.isEmpty() ? Config.defaultMinecraftPath : pathInput;
        if(!Files.exists(Paths.get(path))) {
            boolean created = Paths.get(path).toFile().mkdir();
            if(!created) {
                logger.error("Failed to create path: {}", path);
                return Completable.error(new RuntimeException("Unable to create path"));
            }
        }

        System.out.print(String.format("Memory allocation in GB: (%s) ", Config.defaultMemoryAllocation));
        String memoryInput = scanner.nextLine().trim();
        memoryAllocation = memoryInput.isEmpty() ? Config.defaultMemoryAllocation : memoryInput;

        return Completable.complete();
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
