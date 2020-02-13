package com.jordanluyke.ezminecraftserver;


import io.reactivex.rxjava3.core.Completable;

/**
 * @author Jordan Luyke <jordanluyke@gmail.com>
 */
public interface MainManager {

    Completable start();
}
