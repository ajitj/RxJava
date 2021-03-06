/**
 * Copyright 2014 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.operators;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import rx.Observable.Operator;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.schedulers.ImmediateScheduler;
import rx.schedulers.TrampolineScheduler;

/**
 * Delivers events on the specified Scheduler asynchronously via an unbounded buffer.
 * 
 * <img width="640" src="https://github.com/Netflix/RxJava/wiki/images/rx-operators/observeOn.png">
 * 
 * @param <T> the transmitted value type
 */
public final class OperatorObserveOn<T> implements Operator<T, T> {

    private final Scheduler scheduler;

    /**
     * @param scheduler
     */
    public OperatorObserveOn(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Subscriber<? super T> call(Subscriber<? super T> child) {
        if (scheduler instanceof ImmediateScheduler) {
            // avoid overhead, execute directly
            return child;
        } else if (scheduler instanceof TrampolineScheduler) {
            // avoid overhead, execute directly
            return child;
        } else {
            return new ObserveOnSubscriber<T>(scheduler, child);
        }
    }

    /** Observe through individual queue per observer. */
    private static final class ObserveOnSubscriber<T> extends Subscriber<T> {
        final Subscriber<? super T> observer;
        private final Scheduler.Worker recursiveScheduler;
        private final ScheduledUnsubscribe scheduledUnsubscribe;
        final NotificationLite<T> on = NotificationLite.instance();
        /** Guarded by this. */
        private FastList queue = new FastList();
        
        volatile long counter;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<ObserveOnSubscriber> COUNTER_UPDATER
                = AtomicLongFieldUpdater.newUpdater(ObserveOnSubscriber.class, "counter");

        public ObserveOnSubscriber(Scheduler scheduler, Subscriber<? super T> subscriber) {
            super(subscriber);
            this.observer = subscriber;
            this.recursiveScheduler = scheduler.createWorker();
            this.scheduledUnsubscribe = new ScheduledUnsubscribe(recursiveScheduler);
            subscriber.add(scheduledUnsubscribe);
        }

        @Override
        public void onNext(final T t) {
            if (scheduledUnsubscribe.isUnsubscribed()) {
                return;
            }
            synchronized (this) {
                queue.add(on.next(t));
            }
            schedule();
        }

        @Override
        public void onCompleted() {
            if (scheduledUnsubscribe.isUnsubscribed()) {
                return;
            }
            synchronized (this) {
                queue.add(on.completed());
            }
            schedule();
        }

        @Override
        public void onError(final Throwable e) {
            if (scheduledUnsubscribe.isUnsubscribed()) {
                return;
            }
            synchronized (this) {
                queue.add(on.error(e));
            }
            schedule();
        }

        protected void schedule() {
            if (COUNTER_UPDATER.getAndIncrement(this) == 0) {
                recursiveScheduler.schedule(new Action0() {

                    @Override
                    public void call() {
                        pollQueue();
                    }

                });
            }
        }

        private void pollQueue() {
            do {
                FastList vs;
                synchronized (this) {
                    vs = queue;
                    queue = new FastList();
                }
                for (Object v : vs.array) {
                    if (v == null) {
                        break;
                    }
                    on.accept(observer, v);
                }
                if (COUNTER_UPDATER.addAndGet(this, -vs.size) == 0) {
                    break;
                }
            } while (true);
        }

    }

    static final class FastList {
        Object[] array;
        int size;

        public void add(Object o) {
            int s = size;
            Object[] a = array;
            if (a == null) {
                a = new Object[16];
                array = a;
            } else if (s == a.length) {
                Object[] array2 = new Object[s + (s >> 2)];
                System.arraycopy(a, 0, array2, 0, s);
                a = array2;
                array = a;
            }
            a[s] = o;
            size = s + 1;
        }
    }
    static final class ScheduledUnsubscribe implements Subscription {
        final Scheduler.Worker worker;
        volatile int once;
        static final AtomicIntegerFieldUpdater<ScheduledUnsubscribe> ONCE_UPDATER
                = AtomicIntegerFieldUpdater.newUpdater(ScheduledUnsubscribe.class, "once");

        public ScheduledUnsubscribe(Scheduler.Worker worker) {
            this.worker = worker;
        }

        @Override
        public boolean isUnsubscribed() {
            return once != 0;
        }

        @Override
        public void unsubscribe() {
            if (ONCE_UPDATER.getAndSet(this, 1) == 0) {
                worker.schedule(new Action0() {
                    @Override
                    public void call() {
                        worker.unsubscribe();
                    }
                });
            }
        }
        
    }
}