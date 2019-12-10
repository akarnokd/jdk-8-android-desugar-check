/*
 * Copyright 2019 David Karnok
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hu.akarnokd.android.desugarcheck;

import java.lang.annotation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import java.util.stream.*;

public abstract class FluentAPI<T> {

    public abstract void produce(Consumer<T> consumer);

    public static <T> FluentAPI<T> create(Consumer<Consumer<T>> action) {
        return new FluentAPI<T>() {
            @Override
            public void produce(Consumer<T> consumer) {
                action.accept(consumer);
            }
        };
    }

    public static <T> FluentAPI<T> just(T item) {
        return create(consumer -> consumer.accept(item));
    }

    public static <T> FluentAPI<T> empty() {
        return create(consumer -> { });
    }

    // Enter the flow

    public static <T> FluentAPI<T> fromOptional(Optional<T> option) {
        return option.<FluentAPI<T>>map(FluentAPI::just).orElseGet(FluentAPI::empty);
    }

    public static <T> FluentAPI<T> fromStream(Stream<? extends T> stream) {
        return create(consumer -> stream.forEach(consumer));
    }

    public static <T> FluentAPI<T> fromCompletionStage(CompletionStage<? extends T> stage) {
        return create(consumer -> {
            stage.whenComplete((v, e) -> {
                System.out.println(e);
                if (v != null) {
                    consumer.accept(v);
                }
            });
        });
    }

    // Stay in the flow

    public final FluentAPI<T> delay(Duration t) {
        return create(consumer -> {
            produce(v -> {
                try {
                    Thread.sleep(t.toMillis());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                consumer.accept(v);
            });
        });
    }

    public final <R> FluentAPI<R> flatMapStream(Function<? super T, ? extends Stream<? extends R>> mapper) {
        return create(consumer -> {
            produce(v -> mapper.apply(v).forEach(consumer));
        });
    }

    public final <R> FluentAPI<R> mapNotNull(Function<@NN ? super T, @NN ? extends R> mapper) {
        return create(consumer -> {
            produce(v -> consumer.accept(mapper.apply(v)));
        });
    }


    public final <R> FluentAPI<R> flatMapOptional(Function<? super T, Optional<? extends R>> mapper) {
        return create(consumer -> {
            produce(v -> mapper.apply(v).ifPresent(consumer));
        });
    }

    public final <R> FluentAPI<R> flatMapStage(Function<? super T, CompletionStage<? extends R>> mapper) {
        return create(consumer -> {
            produce(v -> {
                try {
                    consumer.accept(mapper.apply(v).toCompletableFuture().get());
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            });
        });
    }

    public final <A, R> FluentAPI<R> collect(Collector<T, A, R> collector) {
        return create(consumer -> {
            BiConsumer<A, T> acc = collector.accumulator();
            System.out.println(collector.combiner());
            Function<A, R> finisher = collector.finisher();
            Supplier<A> a = collector.supplier();

            A accumulator = a.get();

            produce(v -> acc.accept(accumulator, v));

            consumer.accept(finisher.apply(accumulator));
        });
    }

    // Leave the flow

    public final CompletionStage<T> first() {
        CompletableFuture<T> cf = new CompletableFuture<>();
        produce(v -> cf.complete(v));
        return cf;
    }

    public final Optional<T> firstOptional() {
        AtomicReference<T> ref = new AtomicReference<>();
        produce(v -> ref.set(v));
        return Optional.ofNullable(ref.get());
    }

    public final Stream<T> toStream() {
        try {
            return collect(Collectors.toList()).first().toCompletableFuture().get().stream();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    };

    // JDK 7-8 features

    public final Exception exceptionWithSuppressed() {
        Exception ex = new Exception("outer");
        ex.addSuppressed(new Exception("suppressed"));
        return ex;
    }

    public final void nullCheck(FluentAPI<T> source) {
        Objects.requireNonNull(source);
    }

    public final void nullCheckWithMessage(FluentAPI<T> source) {
        Objects.requireNonNull(source, "source is null");
    }

    public final void tryWithAutoCloseable() {
        try (AutoCloseable c = () -> { }) {
            System.out.println(c);
        } catch (Throwable ex) {
            ex.printStackTrace();
        }
    }

    public final void forkJoinPool() throws Exception {
        ForkJoinPool.commonPool().submit(() -> {
            System.out.println("ForkJoinPool");
        }).join();
    }

    public final ScheduledExecutorService executorRemoveOnCancelPolicy() {
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(1);
        ((ScheduledThreadPoolExecutor)exec).setRemoveOnCancelPolicy(true);
        return exec;
    }

    @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @interface NN {
    }
}
