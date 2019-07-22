package com.jordanluyke.ezminecraftserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.jordanluyke.ezminecraftserver.util.ErrorHandlingObserver;
import com.jordanluyke.ezminecraftserver.util.NettyHttpClient;
import com.jordanluyke.ezminecraftserver.util.NodeUtil;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
public class MainManagerImpl implements MainManager {
    private static final Logger logger = LogManager.getLogger(MainManager.class);
    private static final long updateInterval = 30;
    private static final TimeUnit updateUnit = TimeUnit.SECONDS;

    private Process minecraftProcess;

    private Config config;

    @Inject
    public MainManagerImpl(Config config) {
        this.config = config;
    }

    @Override
    public Completable start() {
        return config.load()
                .onErrorResumeNext(Observable.just(false))
                .flatMapCompletable(success -> {
                    if(!success)
                        return setup();
                    return Completable.complete();
                })
                .andThen(Completable.defer(this::runMinecraft))
                .doOnComplete(() -> {
                    Observable.interval(updateInterval, updateInterval, updateUnit)
                            .flatMapCompletable(Void1 -> update())
                            .blockingAwait();
                });
    }

    private Completable setup() {
        Scanner scanner = new Scanner(System.in);

        System.out.print(String.format("Path: (%s) ", Config.defaultMinecraftPath));
        String pathInput = scanner.nextLine().trim();
        String path = pathInput.isEmpty() ? Config.defaultMinecraftPath : pathInput;
        if(!Files.exists(Paths.get(path))) {
            boolean created = Paths.get(path).toFile().mkdir();
            if(!created) {
                logger.error("Failed to create path: {}", path);
                return Completable.error(new RuntimeException("Unable to create path"));
            }
        }
        config.setPath(path);

        System.out.print(String.format("Memory allocation in GB: (%s) ", Config.defaultMemoryAllocation));
        String memoryInput = scanner.nextLine().trim();
        String memoryAllocation = memoryInput.isEmpty() ? Config.defaultMemoryAllocation : memoryInput;
        config.setMemoryAllocation(memoryAllocation);

        return update();
    }

    private Completable update() {
        return NettyHttpClient.get("https://launchermeta.mojang.com/mc/game/version_manifest.json")
                .map(res -> NodeUtil.getJsonNode(res.getRawBody()))
                .flatMap(versionBody -> {
                    JsonNode versions = versionBody.get("versions");
                    Optional<String> url = Optional.empty();
                    for(JsonNode version : versions) {
                        String id = NodeUtil.getOrThrow(version, "id");
                        String type = NodeUtil.getOrThrow(version, "type");
                        url = NodeUtil.get(version, "url");
                        if(type.equals("release")) {
                            if(id.equals(config.getVersion()))
                                return Observable.empty();
                            logger.info("{} is latest version", id);
                            config.setVersion(id);
                            config.save();
                            break;
                        }
                    }
                    if(!url.isPresent())
                        return Observable.error(new RuntimeException("url not found"));
                    return NettyHttpClient.get(url.get());
                })
                .map(res -> NodeUtil.getJsonNode(res.getRawBody()))
                .flatMap(packageBody -> {
                    String version = NodeUtil.getOrThrow(packageBody, "id");
                    JsonNode downloads = packageBody.get("downloads");
                    if(downloads == null)
                        return Observable.error(new RuntimeException("Bad response"));
                    JsonNode server = downloads.get("server");
                    if(server == null)
                        return Observable.error(new RuntimeException("Bad response"));
                    String url = NodeUtil.getOrThrow(server, "url");
                    logger.info("Fetching server version: {}", version);
                    return NettyHttpClient.get(url);
                })
                .flatMapCompletable(res -> {
                    Path serverPath = Paths.get(config.getPath(), "minecraft_server.jar");
                    Files.write(serverPath, res.getRawBody());

                    if(minecraftProcess != null) {
                        logger.info("Restarting Minecraft..");
                        minecraftProcess.destroy();
                        return runMinecraft();
                    }
                    return Completable.complete();
                });
    }

    private Completable runMinecraft() {
        Path minecraftJarPath = Paths.get(config.getPath(), "minecraft_server.jar");
        String[] cmd = String.format("java -server -Xmx%sG -Xms%sG -jar %s nogui", config.getMemoryAllocation(), config.getMemoryAllocation(), minecraftJarPath)
                .split(" ");
        Process proc;
        try {
            proc = new ProcessBuilder()
                    .directory(new File(config.getPath()))
                    .command(cmd)
                    .start();
        } catch(IOException e) {
            return Completable.error(new RuntimeException(e.getMessage()));
        }
        minecraftProcess = proc;

        BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()));

        fromBufferedReader(in)
                .doOnNext(System.out::println)
                .subscribeOn(Schedulers.newThread())
                .subscribe(new ErrorHandlingObserver<>());

        fromBufferedReader(err)
                .doOnNext(System.err::println)
                .subscribeOn(Schedulers.newThread())
                .subscribe(new ErrorHandlingObserver<>());

        return Completable.complete();
    }

    private Observable<String> fromBufferedReader(BufferedReader reader) {
        return Observable.create(e -> {
            String line;
            while (!e.isDisposed() && (line = reader.readLine()) != null) {
                e.onNext(line);
            }
            e.onComplete();
        });
    }
}
