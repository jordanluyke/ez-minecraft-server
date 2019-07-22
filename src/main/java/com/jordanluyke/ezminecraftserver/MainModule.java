package com.jordanluyke.ezminecraftserver;

import com.google.inject.AbstractModule;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
public class MainModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(MainManager.class).to(MainManagerImpl.class);
    }
}
