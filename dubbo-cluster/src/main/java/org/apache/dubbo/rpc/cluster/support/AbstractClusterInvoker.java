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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.dubbo.rpc.cluster.Constants.*;

/**
 * AbstractClusterInvoker
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractClusterInvoker.class);
    /**
     * directory对象，invoker对象列表
     */
    protected final Directory<T> directory;
    /**
     * 集群时是否排除非可用(available)的Invoker
     */
    protected final boolean availablecheck;
    /**
     * 是否已经被销毁
     */
    private AtomicBoolean destroyed = new AtomicBoolean(false);
    /**
     * 粘滞连接 Invoker
     * <p>
     * http://dubbo.apache.org/zh-cn/docs/user/demos/stickiness.html
     * 粘滞连接用于有状态服务，尽可能让客户端总是向同一提供者发起调用，除非该提供者挂了，再连另一台。
     * 粘滞连接将自动开启延迟连接，以减少长连接数。
     */
    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        //初始化directory
        if (directory == null) {
            throw new IllegalArgumentException("service directory == null");
        }

        this.directory = directory;
        //初始化集群时是否排除非可用(available)的Invoker
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(CLUSTER_AVAILABLE_CHECK_KEY, DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        // 如有粘滞连接 Invoker ，基于它判断。
        Invoker<T> invoker = stickyInvoker;// 指向，避免并发
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * 选择一个invoker使用负载均衡的策略
     * Select a invoker using loadbalance policy.</br>
     * a) Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or,
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * <p>
     * b) Reselection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also
     * guarantees this invoker is available.
     *
     * @param loadbalance load balance policy
     * @param invocation  invocation
     * @param invokers    invoker candidates
     * @param selected    exclude selected invokers or not
     * @return the invoker which will final to do invoke.
     * @throws RpcException exception
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        //所有候选的invoker集合
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        //得到方法名
        String methodName = invocation == null ? StringUtils.EMPTY : invocation.getMethodName();
        //得到sticky配置项
        boolean sticky = invokers.get(0).getUrl()
                .getMethodParameter(methodName, CLUSTER_STICKY_KEY, DEFAULT_CLUSTER_STICKY);

        //ignore overloaded method 忽略重载方法
        if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
            stickyInvoker = null;
        }
        //ignore concurrency problem 忽略并发问题
        //若开启粘滞连接的特性，且 stickyInvoker 不存在于 selected 中，则返回 stickyInvoker 这个 Invoker 对象
        if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
            //若开启排除费可用的Invoker的特性，则校验stickyInvoker是否可用，若可用则进行返回
            if (availablecheck && stickyInvoker.isAvailable()) {
                return stickyInvoker;
            }
        }
        //执行选
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);
        //若开启粘滞连接的特性，记录最终选择的 Invoker 到 stickyInvoker
        if (sticky) {
            stickyInvoker = invoker;
        }
        return invoker;
    }

    /**
     * @param loadbalance 负载均衡策略
     * @param invocation  会话域
     * @param invokers    待选择的invoker集合
     * @param selected    已经选择完的invoker集合
     * @return
     * @throws RpcException
     */
    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        //如果invokers只有一个直接返回这一个
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        //否则执行负载均衡策略选择一个服务调用invoker对象
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //If the `invoker` is in the  `selected` or invoker is unavailable && availablecheck is true, reselect.
        //如果已经选择执行对象不为空并且selected包含选择出来的这个invoker 或者invoker不可用，并且集群时排除非可用(available)的Invoker
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                //降级方案，重新选择invoker
                Invoker<T> rInvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rInvoker != null) {
                    invoker = rInvoker;
                } else {
                    //Check the index of current selected invoker, if it's not the last one, choose the one at index+1.
                    //得到invoker在invokers集合的位置
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision
                        //然后第一次选择的invoker位置+1取模list总数，防止碰撞
                        invoker = invokers.get((index + 1) % invokers.size());
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`,
     * just pick an available one using loadbalance policy.
     * 重新选择
     *
     * @param loadbalance    load balance policy
     * @param invocation     invocation
     * @param invokers       invoker candidates
     * @param selected       exclude selected invokers or not
     * @param availablecheck check invoker available if true
     * @return the reselect result to do invoke
     * @throws RpcException exception
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck) throws RpcException {

        //Allocating one in advance, this list is certain to be used.
        List<Invoker<T>> reselectInvokers = new ArrayList<>(
                invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        // First, try picking a invoker not in `selected`.
        for (Invoker<T> invoker : invokers) {
            //排除如果集群中排除不可用invoker属性开启后还有invoker不可用则直接跳到下一次循环
            if (availablecheck && !invoker.isAvailable()) {
                continue;
            }
            //如果被选中集合为空，并且不包含改invoker
            if (selected == null || !selected.contains(invoker)) {
                //加入重新选择的invoker集合中
                reselectInvokers.add(invoker);
            }
        }

        if (!reselectInvokers.isEmpty()) {
            //执行负载均衡选择invoker
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        // Just pick an available invoker using loadbalance policy
        if (selected != null) {
            //fixme refactor stream
            selected.stream()
                    .filter(invoker -> invoker.isAvailable() && !reselectInvokers.contains(invoker))
                    .forEach(reselectInvokers::add);
          /*  for (Invoker<T> invoker : selected) {
                if ((invoker.isAvailable()) // available first
                        && !reselectInvokers.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }*/
        }
        if (!reselectInvokers.isEmpty()) {
            //执行负载均衡
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        return null;
    }

    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        checkWhetherDestroyed();

        // binding attachments into invocation. 绑定隐式参数集合在会话域中
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        //如果contextAttachments不为空
        if (contextAttachments != null && contextAttachments.size() != 0) {
            //将attachments加入invocation中
            ((RpcInvocation) invocation).addAttachments(contextAttachments);
        }
        //得到所有候选服务提供者列表
        List<Invoker<T>> invokers = list(invocation);
        //初始化负载均衡策略
        LoadBalance loadbalance = initLoadBalance(invokers, invocation);
        //幂等操作:默认情况下，调用id将添加到异步操作中
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        //执行invoke调用
        return doInvoke(invocation, invokers, loadbalance);
    }

    /**
     * 校验此时提供者集合 detory对象是否被销毁
     */
    protected void checkWhetherDestroyed() {
        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            throw new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        return directory.list(invocation);
    }

    /**
     * Init LoadBalance.
     * <p>
     * if invokers is not empty, init from the first invoke's url and invocation
     * if invokes is empty, init a default LoadBalance(RandomLoadBalance)
     * </p>
     *
     * @param invokers   invokers
     * @param invocation invocation
     * @return LoadBalance instance. if not need init, return null.
     */
    protected LoadBalance initLoadBalance(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isNotEmpty(invokers)) {
            //得到其中一个提供者得到对应的负载均衡策略，默认为随机
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(RpcUtils.getMethodName(invocation), LOADBALANCE_KEY, DEFAULT_LOADBALANCE));
        } else {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(DEFAULT_LOADBALANCE);
        }
    }
}
