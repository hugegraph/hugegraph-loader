/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.loader.task;

import static com.baidu.hugegraph.loader.constant.Constants.BATCH_WORKER;
import static com.baidu.hugegraph.loader.constant.Constants.SINGLE_WORKER;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.baidu.hugegraph.loader.constant.ElemType;
import com.baidu.hugegraph.loader.exception.LoadException;
import com.baidu.hugegraph.loader.executor.LoadContext;
import com.baidu.hugegraph.loader.executor.LoadOptions;
import com.baidu.hugegraph.loader.struct.ElementStruct;
import com.baidu.hugegraph.structure.GraphElement;
import com.baidu.hugegraph.util.ExecutorUtil;
import com.baidu.hugegraph.util.Log;

public final class TaskManager {

    private static final Logger LOG = Log.logger(TaskManager.class);

    private final LoadContext context;
    private final LoadOptions options;

    private final Semaphore batchSemaphore;
    private final Semaphore singleSemaphore;
    private final ExecutorService batchService;
    private final ExecutorService singleService;

    public TaskManager(LoadContext context) {
        this.context = context;
        this.options = context.options();
        this.batchSemaphore = new Semaphore(this.options.numThreads + 1);
        this.singleSemaphore = new Semaphore(this.options.numThreads + 1);
        /*
         * In principle, unbounded synchronization queue(which may lead to OOM)
         * should not be used, but there the task manager uses semaphores to
         * limit the number of tasks added. When there are no idle threads in
         * the thread pool, the producer will be blocked, so OOM will not occur.
         */
        this.batchService = ExecutorUtil.newFixedThreadPool(options.numThreads,
                                                            BATCH_WORKER);
        this.singleService = ExecutorUtil.newFixedThreadPool(options.numThreads,
                                                             SINGLE_WORKER);
    }

    public void waitFinished(ElemType type) {
        try {
            // Wait batch mode task finished
            this.batchSemaphore.acquire(this.options.numThreads);
            LOG.info("Batch-mode tasks of {} finished", type.string());
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting batch-mode tasks");
        } finally {
            this.batchSemaphore.release(this.options.numThreads);
        }

        try {
            // Wait single mode task finished
            this.singleSemaphore.acquire(this.options.numThreads);
            LOG.info("Single-mode tasks of {} finished", type.string());
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting batch-mode tasks");
        } finally {
            this.singleSemaphore.release(this.options.numThreads);
        }
    }

    public void shutdown() {
        long timeout = this.options.shutdownTimeout;
        LOG.debug("Attempt to shutdown batch-mode tasks executor");
        try {
            this.batchService.shutdown();
            this.batchService.awaitTermination(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.error("The batch-mode tasks are interrupted");
        } finally {
            if (!this.batchService.isTerminated()) {
                LOG.error("Cancel unfinished batch-mode tasks");
            }
            this.batchService.shutdownNow();
        }

        LOG.debug("Attempt to shutdown single-mode tasks executor");
        try {
            this.singleService.shutdown();
            this.singleService.awaitTermination(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.error("The single-mode task are interrupted");
        } finally {
            if (!this.singleService.isTerminated()) {
                LOG.error("Cancel unfinished single-mode tasks");
            }
            this.singleService.shutdownNow();
        }
    }

    public <GE extends GraphElement> void submitBatch(ElementStruct struct,
                                                      List<GE> batch) {
        ElemType type = struct.type();
        try {
            this.batchSemaphore.acquire();
        } catch (InterruptedException e) {
            throw new LoadException("Interrupted while waiting to submit %s " +
                                    "batch in batch mode", e, type);
        }

        InsertTask<GE> task = new BatchInsertTask<>(this.context, struct,
                                                    batch);
        CompletableFuture.runAsync(task, this.batchService).exceptionally(e -> {
            LOG.warn("Batch insert {} error, try single insert", type, e);
            this.submitInSingle(struct, batch);
            return null;
        }).whenComplete((r, e) -> this.batchSemaphore.release());
    }

    private <GE extends GraphElement> void submitInSingle(ElementStruct struct,
                                                          List<GE> batch) {
        ElemType type = struct.type();
        try {
            this.singleSemaphore.acquire();
        } catch (InterruptedException e) {
            throw new LoadException("Interrupted while waiting to submit %s " +
                                    "batch in single mode", e, type);
        }

        InsertTask<GE> task = new SingleInsertTask<>(this.context, struct,
                                                     batch);
        CompletableFuture.runAsync(task, this.singleService)
                         .whenComplete((r, e) -> this.singleSemaphore.release());
    }
}
