 /**
  * Copyright 2014 Netflix, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License"); you may not
  * use this file except in compliance with the License. You may obtain a copy of
  * the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
  * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
  * License for the specific language governing permissions and limitations under
  * the License.
  */
package rx.operators;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import rx.Observable;
import rx.Observable.Operator;
import rx.Subscriber;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.observers.SerializedSubscriber;
import rx.subscriptions.CompositeSubscription;

/**
 * Observable that pairs up the source values and all the derived collection
 * values and projects them via the selector.
 *
 * @param <T> the input value type
 * @param <U> the derived collection value type
 * @param <R> the result type
 */
public final class OperatorMergeMapPair<T, U, R> implements Operator<R, T> {
    
    public static <T, U> Func1<T, Observable<U>> convertSelector(final Func1<? super T, ? extends Iterable<? extends U>> selector) {
        return new Func1<T, Observable<U>>() {
            @Override
            public Observable<U> call(T t1) {
                return Observable.from(selector.call(t1));
            }
        };
    }
    
    final Func1<? super T, ? extends Observable<? extends U>> collectionSelector;
    final Func2<? super T, ? super U, ? extends R> resultSelector;
    
    public OperatorMergeMapPair(Func1<? super T, ? extends Observable<? extends U>> collectionSelector,
            Func2<? super T, ? super U, ? extends R> resultSelector) {
        this.collectionSelector = collectionSelector;
        this.resultSelector = resultSelector;
    }
    
    @Override
    public Subscriber<? super T> call(Subscriber<? super R> child) {
        final SerializedSubscriber<R> s = new SerializedSubscriber<R>(child);
        final CompositeSubscription csub = new CompositeSubscription();
        child.add(csub);
     
        return new SourceSubscriber<T, U, R>(s, csub, collectionSelector, resultSelector);
    }
    static final class SourceSubscriber<T, U, R> extends Subscriber<T> {
        final Subscriber<R> s;
        final CompositeSubscription csub;
        final Func1<? super T, ? extends Observable<? extends U>> collectionSelector;
        final Func2<? super T, ? super U, ? extends R> resultSelector;

        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<SourceSubscriber> WIP_UPDATER
                = AtomicIntegerFieldUpdater.newUpdater(SourceSubscriber.class, "wip");

        public SourceSubscriber(Subscriber<R> s, CompositeSubscription csub, 
                Func1<? super T, ? extends Observable<? extends U>> collectionSelector, 
                Func2<? super T, ? super U, ? extends R> resultSelector) {
            super(s);
            this.s = s;
            this.csub = csub;
            this.collectionSelector = collectionSelector;
            this.resultSelector = resultSelector;
            this.wip = 1;
        }
        
        @Override
        public void onNext(final T t) {
            Observable<? extends U> collection;
            try {
                collection = collectionSelector.call(t);
            } catch (Throwable e) {
                onError(e);
                return;
            }
            
            Subscriber<U> collectionSub = new Subscriber<U>() {
                
                @Override
                public void onNext(U u) {
                    try {
                        s.onNext(resultSelector.call(t, u));
                    } catch (Throwable e) {
                        onError(e);
                    }
                }
                
                @Override
                public void onError(Throwable e) {
                    SourceSubscriber.this.onError(e);
                }
                
                @Override
                public void onCompleted() {
                    try {
                        SourceSubscriber.this.onCompleted();
                    } finally {
                        csub.remove(this);
                    }
                }
            };
            csub.add(collectionSub);
            WIP_UPDATER.incrementAndGet(this);
            
            collection.unsafeSubscribe(collectionSub);
        }
        
        @Override
        public void onError(Throwable e) {
            s.onError(e);
            unsubscribe();
        }
        
        @Override
        public void onCompleted() {
            if (WIP_UPDATER.decrementAndGet(this) == 0) {
                s.onCompleted();
            }
        }
        
    }
}
