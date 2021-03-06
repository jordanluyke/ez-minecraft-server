package com.jordanluyke.ezminecraftserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.jordanluyke.ezminecraftserver.util.ErrorHandlingObserver;
import com.jordanluyke.ezminecraftserver.util.NettyHttpClient;
import com.jordanluyke.ezminecraftserver.util.NodeUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
public class MainManagerImpl implements MainManager {
    private static final Logger logger = LogManager.getLogger(MainManager.class);
    private static final long updateInterval = 30;
    private static final TimeUnit updateUnit = TimeUnit.MINUTES;

    private Process minecraftProcess;

    private Config config;

    @Inject
    public MainManagerImpl(Config config) {
        this.config = config;
    }

    @Override
    public Completable start() {
        return config.load()
                .andThen(runMinecraft())
                .doOnComplete(() -> {
                    Observable.interval(updateInterval, updateInterval, updateUnit)
                            .flatMapCompletable(Void1 -> update())
                            .blockingAwait();
                });
    }

    private Completable update() {
        return NettyHttpClient.get("https://launchermeta.mojang.com/mc/game/version_manifest.json")
                .map(res -> NodeUtil.getJsonNode(res.getRawBody()))
                .flatMapMaybe(versionBody -> {
                    JsonNode versions = versionBody.get("versions");
                    Optional<String> url = Optional.empty();
                    for(JsonNode version : versions) {
                        String id = NodeUtil.getOrThrow("id", version);
                        String type = NodeUtil.getOrThrow("type", version);
                        url = NodeUtil.get("url", version);
                        if(type.equals("release")) {
                            if(id.equals(config.getVersion()))
                                return Maybe.empty();
                            logger.info("{} is latest version", id);
                            config.setVersion(id);
                            break;
                        }
                    }
                    if(!url.isPresent())
                        return Maybe.error(new RuntimeException("url not found"));
                    return NettyHttpClient.get(url.get())
                            .toMaybe();
                })
                .map(res -> NodeUtil.getJsonNode(res.getRawBody()))
                .flatMapSingle(packageBody -> {
                    String version = NodeUtil.getOrThrow("id", packageBody);
                    JsonNode downloads = packageBody.get("downloads");
                    if(downloads == null)
                        return Single.error(new RuntimeException("Bad response"));
                    JsonNode server = downloads.get("server");
                    if(server == null)
                        return Single.error(new RuntimeException("Bad response"));
                    String url = NodeUtil.getOrThrow("url", server);
                    logger.info("Fetching server version: {}", version);
                    return NettyHttpClient.get(url);
                })
                .flatMapCompletable(res -> {
                    Path serverPath = Paths.get(config.getPath(), "minecraft_server.jar");
                    Files.write(serverPath, res.getRawBody());
                    config.save();

                    if(minecraftProcess != null) {
                        logger.info("Stopping Minecraft Server");
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
            logger.info("Starting Minecraft Server");
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
