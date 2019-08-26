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

package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;

/**
 * AbstractProxyProtocol
 * 继承 AbstractProtocol 抽象类，Proxy 协议抽象类。
 * 为 HttpProtocol 、RestProtocol 等子类，提供公用的服务暴露、服务引用的公用方法，同时定义了如下抽象方法，用于不同子类协议实现类的自定义的逻辑
 */
public abstract class AbstractProxyProtocol extends AbstractProtocol {
    /**
     * 需要抛出的异常类集合，详见 {@link #refer(Class, URL)} 方法。
     */
    private final List<Class<?>> rpcExceptions = new CopyOnWriteArrayList<Class<?>>();

    private ProxyFactory proxyFactory;

    public AbstractProxyProtocol() {
    }

    public AbstractProxyProtocol(Class<?>... exceptions) {
        //循环异常，添加到rpc异常中
        for (Class<?> exception : exceptions) {
            //rpcExceptions 属性，不同协议的远程调用，会抛出的异常是不同的。在 #refer(Class, URL) 方法中，我们会看到对这个属性的使用，理解会更清晰一些。
            addRpcException(exception);
        }
    }

    public void addRpcException(Class<?> exception) {
        this.rpcExceptions.add(exception);
    }

    public ProxyFactory getProxyFactory() {
        return proxyFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }


    @Override
    @SuppressWarnings("unchecked")
    public <T> Exporter<T> export(final Invoker<T> invoker) throws RpcException {
        //得到服务键
        final String uri = serviceKey(invoker.getUrl());
        //获得exporter对象，若依据暴露直接返回
        Exporter<T> exporter = (Exporter<T>) exporterMap.get(uri);
        if (exporter != null) {
            // When modifying the configuration through override, you need to re-expose the newly modified service.
            //通过覆盖修改配置时，需要重新公开新修改的服务。
            if (Objects.equals(exporter.getInvoker().getUrl(), invoker.getUrl())) {
                return exporter;
            }
        }
        // 执行暴露服务，并返回取消暴露的回调 Runnable
        final Runnable runnable = doExport(proxyFactory.getProxy(invoker, true), invoker.getInterface(), invoker.getUrl());
        //创建Exporter对象
        exporter = new AbstractExporter<T>(invoker) {
            //取消暴露
            @Override
            public void unexport() {
                super.unexport();
                exporterMap.remove(uri);
                if (runnable != null) {
                    try {
                        // 执行取消暴露的回调
                        runnable.run();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            }
        };
        exporterMap.put(uri, exporter);
        return exporter;
    }

    /**
     * 协议绑定引用
     * @param type
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> Invoker<T> protocolBindingRefer(final Class<T> type, final URL url) throws RpcException {
        //得带目标invoker
        final Invoker<T> target = proxyFactory.getInvoker(doRefer(type, url), type, url);
        //根据接口和url得到invoker
        Invoker<T> invoker = new AbstractInvoker<T>(type, url) {
            //执行调用
            @Override
            protected Result doInvoke(Invocation invocation) throws Throwable {
                try {
                    //目标执行得到结果集
                    Result result = target.invoke(invocation);
                    // FIXME result is an AsyncRpcResult instance.
                    //得到异常
                    Throwable e = result.getException();
                    if (e != null) {
                        for (Class<?> rpcException : rpcExceptions) {
                            if (rpcException.isAssignableFrom(e.getClass())) {
                                throw getRpcException(type, url, invocation, e);
                            }
                        }
                    }
                    return result;
                } catch (RpcException e) {
                    if (e.getCode() == RpcException.UNKNOWN_EXCEPTION) {
                        e.setCode(getErrorCode(e.getCause()));
                    }
                    throw e;
                } catch (Throwable e) {
                    throw getRpcException(type, url, invocation, e);
                }
            }
        };
        invokers.add(invoker);
        return invoker;
    }

    protected RpcException getRpcException(Class<?> type, URL url, Invocation invocation, Throwable e) {
        RpcException re = new RpcException("Failed to invoke remote service: " + type + ", method: "
                + invocation.getMethodName() + ", cause: " + e.getMessage(), e);
        re.setCode(getErrorCode(e));
        return re;
    }

    /**
     * 获得addr
     * @param url
     * @return
     */
    protected String getAddr(URL url) {
        //得到ip
        String bindIp = url.getParameter(Constants.BIND_IP_KEY, url.getHost());
        if (url.getParameter(ANYHOST_KEY, false)) {
            bindIp = ANYHOST_VALUE;
        }
        //返回host:port
        return NetUtils.getIpByHost(bindIp) + ":" + url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
    }

    protected int getErrorCode(Throwable e) {
        return RpcException.UNKNOWN_EXCEPTION;
    }
    /**
     * 执行暴露，并返回取消暴露的回调 Runnable
     *
     * @param impl 服务 Proxy 对象
     * @param type 服务接口
     * @param url URL
     * @param <T> 服务接口
     * @return 取消暴露的回调 Runnable
     * @throws RpcException 当发生异常
     */
    protected abstract <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException;


    /**
     * 执行引用，并返回调用远程服务的 Service 对象
     *
     * @param type 服务接口
     * @param url URL
     * @param <T> 服务接口
     * @return 调用远程服务的 Service 对象
     * @throws RpcException 当发生异常
     */
    protected abstract <T> T doRefer(Class<T> type, URL url) throws RpcException;

}
