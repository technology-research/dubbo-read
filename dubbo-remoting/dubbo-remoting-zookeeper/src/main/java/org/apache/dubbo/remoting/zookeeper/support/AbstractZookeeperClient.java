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
package org.apache.dubbo.remoting.zookeeper.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.DataListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

/**
 * 实现 ZookeeperClient 接口，Zookeeper 客户端抽象类，实现通用的逻辑。
 * @param <TargetDataListener>
 * @param <TargetChildListener>
 */
public abstract class AbstractZookeeperClient<TargetDataListener, TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);
    /**
     * 注册中心URL
     */
    private final URL url;
    /**
     * 状态监听器Set集合，读多写少，并且保证写线程安全
     */
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();

    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();
    /**
     * 目前子节点监听器集合
     * key1:子节点路径
     * key2:数据监听器对象
     * value:子节点监听器具体对象。不同Zookeeper客户端，实现会不同
     */
    private final ConcurrentMap<String, ConcurrentMap<DataListener, TargetDataListener>> listeners = new ConcurrentHashMap<String, ConcurrentMap<DataListener, TargetDataListener>>();

    private volatile boolean closed = false;

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void create(String path, boolean ephemeral) {
        //如果是创建持久节点
        if (!ephemeral) {
            //校验是否存在
            if (checkExists(path)) {
                return;
            }
        }
        //找到最后一个/的index
        int i = path.lastIndexOf('/');
        if (i > 0) {
            //递归截取，知道i<=0，找到父节点为止
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            //创建临时节点
            createEphemeral(path);
        } else {
            //创建持久化节点
            createPersistent(path);
        }
    }

    @Override
    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    /**
     * 得到状态监听器集合
     * @return
     */
    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    @Override
    public List<String> addChildListener(String path, final ChildListener listener) {
        //根据节点得到listeners
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners == null) {
            //新创建
            childListeners.putIfAbsent(path, new ConcurrentHashMap<ChildListener, TargetChildListener>());
            //重新给listeners
            listeners = childListeners.get(path);
        }
        //得到目标子节点监听器
        TargetChildListener targetListener = listeners.get(listener);
        if (targetListener == null) {
            //根据传入的监听器为key，创建目标子节点监听器
            listeners.putIfAbsent(listener, createTargetChildListener(path, listener));
            //得到目标监听器
            targetListener = listeners.get(listener);
        }
        //添加到目标子监听器集合中， 向 Zookeeper ，真正发起订阅
        return addTargetChildListener(path, targetListener);
    }

    @Override
    public void addDataListener(String path, DataListener listener) {
        this.addDataListener(path, listener, null);
    }

    @Override
    public void addDataListener(String path, DataListener listener, Executor executor) {
        //根据子节点路径，得到dataListenerMap
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        //如果为空这新建
        if (dataListenerMap == null) {
            listeners.putIfAbsent(path, new ConcurrentHashMap<DataListener, TargetDataListener>());
            dataListenerMap = listeners.get(path);
        }
        TargetDataListener targetListener = dataListenerMap.get(listener);
        if (targetListener == null) {
            dataListenerMap.putIfAbsent(listener, createTargetDataListener(path, listener));
            targetListener = dataListenerMap.get(listener);
        }
        addTargetDataListener(path, targetListener, executor);
    }

    @Override
    public void removeDataListener(String path, DataListener listener ){
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        if (dataListenerMap != null) {
            TargetDataListener targetListener = dataListenerMap.remove(listener);
            if(targetListener != null){
                removeTargetDataListener(path, targetListener);
            }
        }
    }

    @Override
    public void removeChildListener(String path, ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            //将缓存移除
            TargetChildListener targetListener = listeners.remove(listener);
            //移除zk
            if (targetListener != null) {
                //再次移除
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    /**
     * 状态变更回调
     * @param state 状态值
     */
    protected void stateChanged(int state) {
        for (StateListener sessionListener : getSessionListeners()) {
            //设置状态
            sessionListener.stateChanged(state);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public void create(String path, String content, boolean ephemeral) {
        if (checkExists(path)) {
            delete(path);
        }
        int i = path.lastIndexOf('/');
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            createEphemeral(path, content);
        } else {
            createPersistent(path, content);
        }
    }

    @Override
    public String getContent(String path) {
        if (!checkExists(path)) {
            return null;
        }
        return doGetContent(path);
    }

    protected abstract void doClose();

    /**
     * 创建持久化节点
     * @param path 路径
     */
    protected abstract void createPersistent(String path);

    /**
     * 创建临时节点
     * @param path 路径
     */
    protected abstract void createEphemeral(String path);

    protected abstract void createPersistent(String path, String data);

    protected abstract void createEphemeral(String path, String data);

    protected abstract boolean checkExists(String path);

    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    /**
     * 添加目标子监听器
     * @param path
     * @param listener
     * @return
     */
    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    protected abstract TargetDataListener createTargetDataListener(String path, DataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener, Executor executor);

    protected abstract void removeTargetDataListener(String path, TargetDataListener listener);

    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

    protected abstract String doGetContent(String path);

}
