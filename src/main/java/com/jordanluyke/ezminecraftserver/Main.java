package com.jordanluyke.ezminecraftserver;

import com.google.inject.Guice;
import com.jordanluyke.ezminecraftserver.util.ErrorHandlingCompletableObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
    private static final Logger logger = LogManager.getLogger(Main.class);

    public static void main(String[] args) {
        logger.info("Initializing");
        Guice.createInjector(new MainModule())
                .getInstance(MainManager.class)
                .start()
                .subscribe(new ErrorHandlingCompletableObserver());
    }
}
