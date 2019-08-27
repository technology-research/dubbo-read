/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.service;

import java.util.concurrent.CompletableFuture;

/**
 * Generic service interface
 *
 * @export
 */
public interface GenericService {

    /**
     * Generic invocation
     * 泛化调用
     * @param method         Method name, e.g. findPerson. If there are overridden methods, parameter info is
     *                       required, e.g. findPerson(java.lang.String)
     * @param parameterTypes Parameter types 变量类型数组
     * @param args           Arguments 参数数组
     * @return invocation return value 调用结果
     * @throws GenericException potential exception thrown from the invocation
     */
    Object $invoke(String method, String[] parameterTypes, Object[] args) throws GenericException;

    /**
     * 异步调用
     * @param method
     * @param parameterTypes
     * @param args
     * @return
     * @throws GenericException
     */
    default CompletableFuture<Object> $invokeAsync(String method, String[] parameterTypes, Object[] args) throws GenericException {
        //调用$invoke
        Object object = $invoke(method, parameterTypes, args);
        //如果结果为CompletableFuture,返回CompletableFuture
        if (object instanceof CompletableFuture) {
            return (CompletableFuture<Object>) object;
        }
        return CompletableFuture.completedFuture(object);
    }

}