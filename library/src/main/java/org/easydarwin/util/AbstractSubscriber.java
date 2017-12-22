package org.easydarwin.util;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Created by apple on 2017/10/21.
 */

public abstract class AbstractSubscriber<T> implements Subscriber<T> {
    @Override
    public void onSubscribe(Subscription s) {

    }

    @Override
    public void onNext(T t) {

    }

    @Override
    public void onError(Throwable t) {
        t.printStackTrace();
    }

    @Override
    public void onComplete() {

    }
}
