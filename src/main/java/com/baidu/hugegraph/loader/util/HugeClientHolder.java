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

package com.baidu.hugegraph.loader.util;

import com.baidu.hugegraph.driver.HugeClient;
import com.baidu.hugegraph.exception.ServerException;
import com.baidu.hugegraph.loader.constant.Constants;
import com.baidu.hugegraph.loader.exception.LoadException;
import com.baidu.hugegraph.loader.executor.LoadOptions;
import com.baidu.hugegraph.rest.ClientException;

public final class HugeClientHolder {

    public static HugeClient create(LoadOptions options) {
        String address;
        if (!options.host.startsWith(Constants.HTTP_PREFIX) &&
            !options.host.startsWith(Constants.HTTPS_PREFIX)) {
            address = Constants.HTTP_PREFIX + options.host + ":" + options.port;
            if (options.protocol != null &&
                options.protocol.equals("https")) {
                address = Constants.HTTPS_PREFIX + options.host + ":" + options.port;
            }
        } else {
            address = options.host + ":" + options.port;
        }

        String username = (options.username != null) ?
                          options.username :
                          options.graph;
        try {
            return HugeClient.builder(address, options.graph)
                             .configUser(username, options.token)
                             .configTimeout(options.timeout)
                             .configPool(options.maxConnections,
                                         options.maxConnectionsPerRoute)
                             .configSSL(options.protocol, options.trustStoreFile,
                                        options.trustStorePassword)
                             .build();
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            if (message != null && message.startsWith("The version")) {
                throw new LoadException("The version of hugegraph-client and " +
                                        "hugegraph-server don't match", e);
            }
            throw e;
        } catch (ServerException e) {
            String message = e.getMessage();
            if (Constants.STATUS_UNAUTHORIZED == e.status() ||
                (message != null && message.startsWith("Authentication"))) {
                throw new LoadException("Incorrect username or password", e);
            }
            throw e;
        } catch (ClientException e) {
            Throwable cause = e.getCause();
            if (cause == null || cause.getMessage() == null) {
                throw e;
            }
            String message = cause.getMessage();
            if (message.contains("Connection refused")) {
                throw new LoadException("The service %s:%s is unavailable", e,
                                        options.host, options.port);
            } else if (message.contains("java.net.UnknownHostException") ||
                       message.contains("Host name may not be null")) {
                throw new LoadException("The host %s is unknown", e,
                                        options.host);
            } else if (message.contains("connect timed out")) {
                throw new LoadException("Connect service %s:%s timeout, " +
                                        "please check service is available " +
                                        "and network is unobstructed", e,
                                        options.host, options.port);
            }
            throw e;
        }
    }
}
