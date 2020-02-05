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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.baidu.hugegraph.loader.builder.EdgeBuilder;
import com.baidu.hugegraph.loader.builder.ElementBuilder;
import com.baidu.hugegraph.loader.builder.Record;
import com.baidu.hugegraph.loader.builder.VertexBuilder;
import com.baidu.hugegraph.loader.constant.Constants;
import com.baidu.hugegraph.loader.exception.ParseException;
import com.baidu.hugegraph.loader.executor.LoadContext;
import com.baidu.hugegraph.loader.executor.LoadOptions;
import com.baidu.hugegraph.loader.failure.FailLogger;
import com.baidu.hugegraph.loader.mapping.EdgeMapping;
import com.baidu.hugegraph.loader.mapping.ElementMapping;
import com.baidu.hugegraph.loader.mapping.InputStruct;
import com.baidu.hugegraph.loader.mapping.VertexMapping;
import com.baidu.hugegraph.loader.metrics.LoadMetrics;
import com.baidu.hugegraph.loader.reader.line.Line;
import com.baidu.hugegraph.loader.util.Printer;
import com.baidu.hugegraph.structure.GraphElement;
import com.baidu.hugegraph.util.Log;

public final class ParseTaskBuilder {

    private static final Logger LOG = Log.logger(ParseTaskBuilder.class);

    private final LoadContext context;
    private final InputStruct struct;
    private final List<ElementBuilder> builders;

    public ParseTaskBuilder(InputStruct struct) {
        this.context = LoadContext.get();
        this.struct = struct;
        this.builders = new ArrayList<>();
        for (VertexMapping mapping : struct.vertices()) {
            this.builders.add(new VertexBuilder(struct, mapping));
        }
        for (EdgeMapping mapping : struct.edges()) {
            this.builders.add(new EdgeBuilder(struct, mapping));
        }
    }

    public InputStruct struct() {
        return this.struct;
    }

    public List<ParseTask> build(List<Line> lines) {
        List<ParseTask> tasks = new ArrayList<>(this.builders.size());
        for (ElementBuilder builder : this.builders) {
            if (builder.mapping().skip()) {
                continue;
            }
            tasks.add(this.buildTask(builder, lines));
        }
        return tasks;
    }

    private ParseTask buildTask(ElementBuilder builder, List<Line> lines) {
        final LoadMetrics metrics = this.context.summary().metrics(this.struct);
        final int batchSize = this.context.options().batchSize;
        final ElementMapping mapping = builder.mapping();
        return new ParseTask(mapping, () -> {
            // One batch records
            List<Record> records = new ArrayList<>(batchSize);
            int count = 0;
            for (Line line : lines) {
                String rawLine = line.rawLine();
                try {
                    // NOTE: don't remove entry in keyValues
                    GraphElement element = builder.build(line.keyValues());
                    if (this.isVirtual(element)) {
                        continue;
                    }
                    records.add(new Record(rawLine, element));
                    count++;
                } catch (IllegalArgumentException e) {
                    metrics.increaseParseFailure(mapping);
                    ParseException pe = new ParseException(rawLine, e);
                    this.handleParseFailure(mapping, pe);
                }
            }
            metrics.plusParseSuccess(mapping, count);
            return records;
        });
    }

    private boolean isVirtual(GraphElement element) {
        return Constants.VIRTUAL_LABEL.equals(element.label());
    }

    private void handleParseFailure(ElementMapping mapping, ParseException e) {
        LOG.error("Parse {} error", mapping.type(), e);
        // Write to current mapping's parse failure log
        FailLogger logger = this.context.failureLogger(this.struct);
        logger.write(e);

        long failures = this.context.summary().totalParseFailures();
        LoadOptions options = this.context.options();
        if (failures < options.maxParseErrors) {
            return;
        }
        if (!this.context.stopped()) {
            synchronized(LoadContext.class) {
                if (!this.context.stopped()) {
                    Printer.printError("More than %s %s parsing error, " +
                                       "stop parsing and waiting all " +
                                       "insert tasks finished",
                                       options.maxParseErrors,
                                       mapping.type().string());
                    this.context.stopLoading();
                }
            }
        }
    }

    public static class ParseTask implements Supplier<List<Record>> {

        private final ElementMapping mapping;
        private final Supplier<List<Record>> supplier;

        public ParseTask(ElementMapping mapping,
                         Supplier<List<Record>> supplier) {
            this.mapping = mapping;
            this.supplier = supplier;
        }

        public ElementMapping mapping() {
            return this.mapping;
        }

        @Override
        public List<Record> get() {
            return this.supplier.get();
        }
    }
}