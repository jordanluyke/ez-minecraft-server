package com.jordanluyke.ezminecraftserver;

import io.reactivex.Completable;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
public interface MainManager {

    Completable start();
}
