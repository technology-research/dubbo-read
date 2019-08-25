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
package org.apache.dubbo.rpc.proxy.wrapper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.service.GenericService;

import java.lang.reflect.Constructor;

import static org.apache.dubbo.rpc.Constants.*;

/**
 * StubProxyFactoryWrapper
 * 实现 ProxyFactory 接口，存根代理工厂包装器实现类
 */
public class StubProxyFactoryWrapper implements ProxyFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(StubProxyFactoryWrapper.class);

    /**
     * ProxyFactory$Adaptive对象
     */
    private final ProxyFactory proxyFactory;

    /**
     * Protocol$Adaptive 对象
     */
    private Protocol protocol;

    public StubProxyFactoryWrapper(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException {
        //通过自适应代理工厂得到代理类，是否泛化调用
        return proxyFactory.getProxy(invoker, generic);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T getProxy(Invoker<T> invoker) throws RpcException {
        //获得 Service Proxy对象
        T proxy = proxyFactory.getProxy(invoker);
        //不是泛化调用
        if (GenericService.class != invoker.getInterface()) {
            URL url = invoker.getUrl();
            //得到stub配置项
            String stub = url.getParameter(STUB_KEY, url.getParameter(LOCAL_KEY));
            if (ConfigUtils.isNotEmpty(stub)) {
                //得到接口类
                Class<?> serviceType = invoker.getInterface();
                //stub为true或者default的情况，使用接口+`Stub`字符串为全路径
                if (ConfigUtils.isDefault(stub)) {
                    //判断url中是否有stub
                    if (url.hasParameter(STUB_KEY)) {
                        //stub名称为接口名+Stub
                        stub = serviceType.getName() + "Stub";
                    } else {
                        //否则stub为接口名+Local
                        stub = serviceType.getName() + "Local";
                    }
                }
                try {
                    //得到stub类
                    Class<?> stubClass = ReflectUtils.forName(stub);
                    //判断接口和stub类是否有相同父类或者父接口，或者serviceType类是否是stubClass的父类
                    //有两个Class类型的类象，一个是调用isAssignableFrom方法的类对象（后称对象A），以及方法中作为参数的这个类对象（称之为对象B），这两个对象如果满足以下条件则返回true，否则返回false：
                    //A对象所对应类信息是B对象所对应的类信息的父类或者是父接口，简单理解即A是B的父类或接口
                    //A对象所对应类信息与B对象所对应的类信息相同，简单理解即A和B为同一个类或同一个接口
                    if (!serviceType.isAssignableFrom(stubClass)) {
                        throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + serviceType.getName());
                    }
                    try {
                        //创建Stub对象，使用带Service Proxy对象的构造方法
                        //创建 Stub 对象，使用带 Service Proxy 对象作为参数的构造方法。例如，public DemoServiceStub(DemoService demoService)
                        // 通过这样的方式，我们的 Stub 对象，就将 Proxy Service 对象，包装在内部，可以实现各种 OOXX 啦。
                        Constructor<?> constructor = ReflectUtils.findConstructor(stubClass, serviceType);
                        proxy = (T) constructor.newInstance(new Object[]{proxy});
                        //export stub service 暴露stub 服务
                        URLBuilder urlBuilder = URLBuilder.from(url);
                        if (url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT)) {
                            urlBuilder.addParameter(STUB_EVENT_METHODS_KEY, StringUtils.join(Wrapper.getWrapper(proxy.getClass()).getDeclaredMethodNames(), ","));
                            urlBuilder.addParameter(IS_SERVER_KEY, Boolean.FALSE.toString());
                            try {
                                export(proxy, (Class) invoker.getInterface(), urlBuilder.build());
                            } catch (Exception e) {
                                LOGGER.error("export a stub service error.", e);
                            }
                        }
                    } catch (NoSuchMethodException e) {
                        throw new IllegalStateException("No such constructor \"public " + stubClass.getSimpleName() + "(" + serviceType.getName() + ")\" in stub implementation class " + stubClass.getName(), e);
                    }
                } catch (Throwable t) {
                    LOGGER.error("Failed to create stub implementation class " + stub + " in consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", cause: " + t.getMessage(), t);
                    // ignore
                }
            }
        }
        return proxy;
    }


    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException {
        return proxyFactory.getInvoker(proxy, type, url);
    }

    private <T> Exporter<T> export(T instance, Class<T> type, URL url) {
        return protocol.export(proxyFactory.getInvoker(instance, type, url));
    }

}
