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
package org.apache.dubbo.rpc.protocol.http;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.remoting.http.HttpServer;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.httpinvoker.HttpComponentsHttpInvokerRequestExecutor;
import org.springframework.remoting.httpinvoker.HttpInvokerProxyFactoryBean;
import org.springframework.remoting.httpinvoker.HttpInvokerServiceExporter;
import org.springframework.remoting.httpinvoker.SimpleHttpInvokerRequestExecutor;
import org.springframework.remoting.support.RemoteInvocation;
import org.springframework.remoting.support.RemoteInvocationFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.RELEASE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.remoting.Constants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;

/**
 * HttpProtocol
 * 继承 AbstractProxyProtocol 抽象类
 */
public class HttpProtocol extends AbstractProxyProtocol {

    public static final int DEFAULT_PORT = 80;

    /**
     * 服务集合
     * key ip:port
     */
    private final Map<String, HttpServer> serverMap = new ConcurrentHashMap<String, HttpServer>();

    /**
     * Spring HttpInvokerServiceExporter 集合
     *
     * key：path 服务名
     */
    private final Map<String, HttpInvokerServiceExporter> skeletonMap = new ConcurrentHashMap<String, HttpInvokerServiceExporter>();

    /**
     * HttpBinder$Adaptive 对象
     */
    private HttpBinder httpBinder;

    public HttpProtocol() {
        super(RemoteAccessException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 暴露
     * @param impl 服务 Proxy 对象
     * @param type 服务接口
     * @param url URL
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        //获得服务器地址
        String addr = getAddr(url);
        //获得HttpService对象，若不存在，进行创建
        HttpServer server = serverMap.get(addr);
        if (server == null) {
            //绑定url和网络处理器
            server = httpBinder.bind(url, new InternalHandler());
            serverMap.put(addr, server);
        }
        final String path = url.getAbsolutePath();
        skeletonMap.put(path, createExporter(impl, type));
        //泛化路径
        final String genericPath = path + "/" + GENERIC_KEY;

        skeletonMap.put(genericPath, createExporter(impl, GenericService.class));
        return new Runnable() {
            @Override
            public void run() {
                skeletonMap.remove(path);
                skeletonMap.remove(genericPath);
            }
        };
    }

    private <T> HttpInvokerServiceExporter createExporter(T impl, Class<?> type) {
        final HttpInvokerServiceExporter httpServiceExporter = new HttpInvokerServiceExporter();
        httpServiceExporter.setServiceInterface(type);
        httpServiceExporter.setService(impl);
        try {
            httpServiceExporter.afterPropertiesSet();
        } catch (Exception e) {
            throw new RpcException(e.getMessage(), e);
        }
        return httpServiceExporter;
    }

    /**
     * 引用服务
     * @param serviceType
     * @param url URL
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(final Class<T> serviceType, final URL url) throws RpcException {
        //得到泛化接口名称
        final String generic = url.getParameter(GENERIC_KEY);
        //判断是否泛化请求
        final boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);
        //创建HttpInvokerProxyFactoryBean
        final HttpInvokerProxyFactoryBean httpProxyFactoryBean = new HttpInvokerProxyFactoryBean();
        //设置远程调用工程
        httpProxyFactoryBean.setRemoteInvocationFactory(new RemoteInvocationFactory() {
            @Override
            public RemoteInvocation createRemoteInvocation(MethodInvocation methodInvocation) {
                RemoteInvocation invocation;
                /*
                  package was renamed to 'org.apache.dubbo' in v2.7.0, so only provider versions after v2.7.0 can
                  recognize org.apache.xxx.HttpRemoteInvocation'.
                 */
                if (Version.isRelease270OrHigher(url.getParameter(RELEASE_KEY))) {
                    invocation = new HttpRemoteInvocation(methodInvocation);
                } else {
                    /*
                      The customized 'com.alibaba.dubbo.rpc.protocol.http.HttpRemoteInvocation' was firstly introduced
                      in v2.6.3. The main purpose is to support transformation of attachments in HttpProtocol, see
                      https://github.com/apache/dubbo/pull/1827. To guarantee interoperability with lower
                      versions, we need to check if the provider is v2.6.3 or higher before sending customized
                      HttpRemoteInvocation.
                     */
                    if (Version.isRelease263OrHigher(url.getParameter(DUBBO_VERSION_KEY))) {
                        invocation = new com.alibaba.dubbo.rpc.protocol.http.HttpRemoteInvocation(methodInvocation);
                    } else {
                        invocation = new RemoteInvocation(methodInvocation);
                    }
                }
                //如果是泛化调用
                if (isGeneric) {
                    invocation.addAttribute(GENERIC_KEY, generic);
                }
                return invocation;
            }
        });

        String key = url.toIdentityString();
        if (isGeneric) {
            key = key + "/" + GENERIC_KEY;
        }
        //设置服务url
        httpProxyFactoryBean.setServiceUrl(key);
        //设置服务接口
        httpProxyFactoryBean.setServiceInterface(serviceType);
        //得到客户端全路径
        String client = url.getParameter(Constants.CLIENT_KEY);
        if (StringUtils.isEmpty(client) || "simple".equals(client)) {
            SimpleHttpInvokerRequestExecutor httpInvokerRequestExecutor = new SimpleHttpInvokerRequestExecutor() {
                @Override
                protected void prepareConnection(HttpURLConnection con,
                                                 int contentLength) throws IOException {
                    super.prepareConnection(con, contentLength);
                    //设置读超时
                    con.setReadTimeout(url.getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT));
                    //设置连接超时
                    con.setConnectTimeout(url.getParameter(Constants.CONNECT_TIMEOUT_KEY, Constants.DEFAULT_CONNECT_TIMEOUT));
                }
            };
            //设置调用请求执行器
            httpProxyFactoryBean.setHttpInvokerRequestExecutor(httpInvokerRequestExecutor);
            //如果客户端为commons
        } else if ("commons".equals(client)) {
            HttpComponentsHttpInvokerRequestExecutor httpInvokerRequestExecutor = new HttpComponentsHttpInvokerRequestExecutor();
            httpInvokerRequestExecutor.setReadTimeout(url.getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT));
            httpInvokerRequestExecutor.setConnectTimeout(url.getParameter(Constants.CONNECT_TIMEOUT_KEY, Constants.DEFAULT_CONNECT_TIMEOUT));
            httpProxyFactoryBean.setHttpInvokerRequestExecutor(httpInvokerRequestExecutor);
        } else {
            throw new IllegalStateException("Unsupported http protocol client " + client + ", only supported: simple, commons");
        }
        httpProxyFactoryBean.afterPropertiesSet();
        return (T) httpProxyFactoryBean.getObject();
    }

    @Override
    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null) {
            Class<?> cls = e.getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                return RpcException.SERIALIZATION_EXCEPTION;
            }
        }
        return super.getErrorCode(e);
    }

    private class InternalHandler implements HttpHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            String uri = request.getRequestURI();
            // 获得 HttpInvokerServiceExporter 对象
            HttpInvokerServiceExporter skeleton = skeletonMap.get(uri);
            //必须为post请求
            if (!"POST".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(500);
            } else {
                //设置远程地址
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                try {
                    //处理请求
                    skeleton.handleRequest(request, response);
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            }
        }

    }

}
