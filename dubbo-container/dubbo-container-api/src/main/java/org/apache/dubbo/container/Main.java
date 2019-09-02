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
package org.apache.dubbo.container;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ConfigUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;

/**
 * Main. (API, Static, ThreadSafe)
 *
 * This class is entry point loading containers.
 */
public class Main {

    public static final String CONTAINER_KEY = "dubbo.container";

    public static final String SHUTDOWN_HOOK_KEY = "dubbo.shutdown.hook";

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    //Container 拓展点对应的 ExtensionLoader 对象
    private static final ExtensionLoader<Container> loader = ExtensionLoader.getExtensionLoader(Container.class);
    private static final ReentrantLock LOCK = new ReentrantLock();

    //对象间同学
    private static final Condition STOP = LOCK.newCondition();

    public static void main(String[] args) {
        try {
            // 若 main 函数参数传入为空，从配置中加载。
            if (ArrayUtils.isEmpty(args)) {
                // 默认 "spring"
                String config = ConfigUtils.getProperty(CONTAINER_KEY, loader.getDefaultExtensionName());
                args = COMMA_SPLIT_PATTERN.split(config);
            }
            // 加载容器数组
            final List<Container> containers = new ArrayList<Container>();
            for (int i = 0; i < args.length; i++) {
                containers.add(loader.getExtension(args[i]));
            }
            logger.info("Use container type(" + Arrays.toString(args) + ") to run dubbo serivce.");
            // ShutdownHook
            if ("true".equals(System.getProperty(SHUTDOWN_HOOK_KEY))) {
                Runtime.getRuntime().addShutdownHook(new Thread("dubbo-container-shutdown-hook") {
                    @Override
                    public void run() {
                        for (Container container : containers) {
                            try {
                                // 关闭容器
                                container.stop();
                                logger.info("Dubbo " + container.getClass().getSimpleName() + " stopped!");
                            } catch (Throwable t) {
                                logger.error(t.getMessage(), t);
                            }
                            try {
                                // 获得 ReentrantLock
                                LOCK.lock();
                                // 唤醒 Main 主线程的等待
                                STOP.signal();
                            } finally {
                                // 释放 ReentrantLock
                                LOCK.unlock();
                            }
                        }
                    }
                });
            }
            //遍历启动容器
            for (Container container : containers) {
                container.start();
                logger.info("Dubbo " + container.getClass().getSimpleName() + " started!");
            }
            System.out.println(new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss]").format(new Date()) + " Dubbo service server started!");
        } catch (RuntimeException e) {
            logger.error(e.getMessage(), e);
            System.exit(1);
        }
        try {
            //加锁
            LOCK.lock();
            //等待
            STOP.await();
        } catch (InterruptedException e) {
            logger.warn("Dubbo service server stopped, interrupted by other thread!", e);
        } finally {
            LOCK.unlock();
        }
    }

}
