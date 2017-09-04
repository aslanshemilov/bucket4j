/*
 *
 *   Copyright 2015-2017 Vladimir Bukhtoyarov
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.bucket4j;

import io.github.bucket4j.grid.BucketNotFoundException;
import io.github.bucket4j.grid.ProxyManager;
import io.github.bucket4j.grid.RecoveryStrategy;
import io.github.bucket4j.util.ConsumptionScenario;
import org.junit.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.github.bucket4j.grid.RecoveryStrategy.RECONSTRUCT;
import static io.github.bucket4j.grid.RecoveryStrategy.THROW_BUCKET_NOT_FOUND_EXCEPTION;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public abstract class AbstractDistributedBucketTest<B extends ConfigurationBuilder<B>, E extends Extension<B>> {

    private final String key = UUID.randomUUID().toString();
    private final String anotherKey = UUID.randomUUID().toString();
    private final Class<E> extensionClass = getExtensionClass();

    private B builder = Bucket4j.extension(getExtensionClass()).builder()
            .addLimit(0, Bandwidth.simple(1_000, Duration.ofMinutes(1)))
            .addLimit(0, Bandwidth.simple(200, Duration.ofSeconds(10)));
    private double permittedRatePerSecond = Math.min(1_000d / 60, 200.0 / 10);

    protected abstract Class<E> getExtensionClass();

    protected abstract Bucket build(B builder, String key, RecoveryStrategy recoveryStrategy);

    protected abstract ProxyManager<String> newProxyManager();

    protected abstract void removeBucketFromBackingStorage(String key);

    @Test
    public void testReconstructRecoveryStrategy() {
        B builder = Bucket4j.extension(extensionClass).builder()
                .addLimit(Bandwidth.simple(1_000, Duration.ofMinutes(1)))
                .addLimit(Bandwidth.simple(200, Duration.ofSeconds(10)));
        Bucket bucket = build(builder, key, RECONSTRUCT);

        assertTrue(bucket.tryConsume(1));

        // simulate crash
        removeBucketFromBackingStorage(key);

        assertTrue(bucket.tryConsume(1));
    }

    @Test
    public void testThrowExceptionRecoveryStrategy() {
        B builder = Bucket4j.extension(extensionClass).builder()
                .addLimit(Bandwidth.simple(1_000, Duration.ofMinutes(1)))
                .addLimit(Bandwidth.simple(200, Duration.ofSeconds(10)));
        Bucket bucket = build(builder, key, THROW_BUCKET_NOT_FOUND_EXCEPTION);

        assertTrue(bucket.tryConsume(1));

        // simulate crash
        removeBucketFromBackingStorage(key);

        try {
            bucket.tryConsume(1);
            fail();
        } catch (BucketNotFoundException e) {
            // ok
        }
    }

    @Test
    public void testTryConsume() throws Exception {
        Function<Bucket, Long> action = bucket -> bucket.tryConsume(1)? 1L : 0L;
        Supplier<Bucket> bucketSupplier = () -> build(builder, key, THROW_BUCKET_NOT_FOUND_EXCEPTION);
        ConsumptionScenario scenario = new ConsumptionScenario(4, TimeUnit.SECONDS.toNanos(15), bucketSupplier, action, permittedRatePerSecond);
        scenario.executeAndValidateRate();
    }

    @Test
    public void testConsume() throws Exception {
        Function<Bucket, Long> action = bucket -> {
            bucket.consumeUninterruptibly(1, BlockingStrategy.PARKING);
            return 1L;
        };
        Supplier<Bucket> bucketSupplier = () -> build(builder, key, THROW_BUCKET_NOT_FOUND_EXCEPTION);
        ConsumptionScenario scenario = new ConsumptionScenario(4, TimeUnit.SECONDS.toNanos(15), bucketSupplier, action, permittedRatePerSecond);
        scenario.executeAndValidateRate();
    }

    @Test
    public void testTryConsumeAsync() throws Exception {
        Bucket testBucket = build(builder, anotherKey, RECONSTRUCT);
        if (!testBucket.isAsyncModeSupported()) {
            return;
        }

        Function<Bucket, Long> action = bucket -> bucket.tryConsume(1)? 1L : 0L;
        Supplier<Bucket> bucketSupplier = () -> build(builder, key, THROW_BUCKET_NOT_FOUND_EXCEPTION);
        ConsumptionScenario scenario = new ConsumptionScenario(4, TimeUnit.SECONDS.toNanos(15), bucketSupplier, action, permittedRatePerSecond);
        scenario.executeAndValidateRate();
    }

    @Test
    public void testConsumeAsync() throws Exception {
        Bucket testBucket = build(builder, anotherKey, RECONSTRUCT);
        if (!testBucket.isAsyncModeSupported()) {
            return;
        }

        Function<Bucket, Long> action = bucket -> {
            bucket.consumeUninterruptibly(1, BlockingStrategy.PARKING);
            return 1L;
        };
        Supplier<Bucket> bucketSupplier = () -> build(builder, key, THROW_BUCKET_NOT_FOUND_EXCEPTION);
        ConsumptionScenario scenario = new ConsumptionScenario(4, TimeUnit.SECONDS.toNanos(15), bucketSupplier, action, permittedRatePerSecond);
        scenario.executeAndValidateRate();
    }

    @Test
    public void testBucketRegistryWithKeyIndependentConfiguration() {
        BucketConfiguration configuration = Bucket4j.configurationBuilder()
                .addLimit(Bandwidth.simple(10, Duration.ofDays(1)))
                .buildConfiguration();

        ProxyManager<String> registry = newProxyManager();
        Bucket bucket1 = registry.getProxy(key, () -> configuration);
        assertTrue(bucket1.tryConsume(10));
        assertFalse(bucket1.tryConsume(1));

        Bucket bucket2 = registry.getProxy(anotherKey, () -> configuration);
        assertTrue(bucket2.tryConsume(10));
        assertFalse(bucket2.tryConsume(1));
    }

}