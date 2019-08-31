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
package org.apache.dubbo.remoting;

import java.net.InetSocketAddress;

/**
 * Channel. (API/SPI, Prototype, ThreadSafe)
 * 继承 Endpoint 接口，通道接口
 * 和 Netty Channel 一致，通讯的载体。
 * 我们会看到在 dubbo-remoting-netty4 项目中，NettyChannel 是 Dubbo Channel 的实现，内部有真正的 Netty Channel 属性，用于通讯。
 * @see org.apache.dubbo.remoting.Client
 * @see org.apache.dubbo.remoting.Server#getChannels()
 * @see org.apache.dubbo.remoting.Server#getChannel(InetSocketAddress)
 */
public interface Channel extends Endpoint {

    /**
     * get remote address.
     * 得到远程套接字 远程地址
     * @return remote address.
     */
    InetSocketAddress getRemoteAddress();

    /**
     * is connected.
     * 判断是否连接
     * @return connected
     */
    boolean isConnected();

    /**
     * has attribute.
     * 是否存在属性
     * @param key key.
     * @return has or has not.
     */
    boolean hasAttribute(String key);

    /**
     * get attribute.
     * 得到属性
     * @param key key.
     * @return value.
     */
    Object getAttribute(String key);

    /**
     * set attribute.
     * 设置属性
     * @param key   key.
     * @param value value.
     */
    void setAttribute(String key, Object value);

    /**
     * remove attribute.
     * 移除属性
     * @param key key.
     */
    void removeAttribute(String key);
}