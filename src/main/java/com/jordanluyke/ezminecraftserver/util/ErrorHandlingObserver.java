package com.jordanluyke.ezminecraftserver.util;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import org.apache.logging.log4j.LogManager;

public class ErrorHandlingObserver<T extends Object> implements Observer<T> {
    private Class<?> loggerClass;

    public ErrorHandlingObserver() {
        loggerClass = getClass();
    }

    @Override
    public void onComplete() {
    }

    @Override
    public void onNext(Object o) {
    }

    @Override
    public void onError(Throwable e) {
        LogManager.getLogger(loggerClass).error("Error", e);
    }

    @Override
    public void onSubscribe(Disposable disposable) {
    }
}