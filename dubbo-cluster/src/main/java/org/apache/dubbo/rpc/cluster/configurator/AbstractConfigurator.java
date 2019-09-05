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
package org.apache.dubbo.rpc.cluster.configurator;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.rpc.cluster.Constants.CONFIG_VERSION_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.OVERRIDE_PROVIDERS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;

/**
 * AbstractOverrideConfigurator
 */
public abstract class AbstractConfigurator implements Configurator {

    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    /**
     * 配置提供者url
     * @param url - old provider url.
     * @return
     */
    @Override
    public URL configure(URL url) {
        //如果覆盖url未启用或无效，则返回。
        // If override url is not enabled or is invalid, just return.
        if (!configuratorUrl.getParameter(ENABLED_KEY, true) || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }
        /**
         * This if branch is created since 2.7.0.
         * 得到配置版本
         */
        String apiVersion = configuratorUrl.getParameter(CONFIG_VERSION_KEY);
        if (StringUtils.isNotEmpty(apiVersion)) {
            //得当当前是消费组还是生产者
            String currentSide = url.getParameter(SIDE_KEY);
            //得到配置所属
            String configuratorSide = configuratorUrl.getParameter(SIDE_KEY);
            //如果来者相同，并且为消费者配置并且端口为0
            if (currentSide.equals(configuratorSide) && CONSUMER.equals(configuratorSide) && 0 == configuratorUrl.getPort()) {
                //消费者url
                url = configureIfMatch(NetUtils.getLocalHost(), url);
                //提供者配置 url
            } else if (currentSide.equals(configuratorSide) && PROVIDER.equals(configuratorSide) && url.getPort() == configuratorUrl.getPort()) {
                url = configureIfMatch(url.getHost(), url);
            }
        }
        /**
         * This else branch is deprecated and is left only to keep compatibility with versions before 2.7.0
         */
        else {
            url = configureDeprecated(url);
        }
        //返回url
        return url;
    }

    @Deprecated
    private URL configureDeprecated(URL url) {
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        if (configuratorUrl.getPort() != 0) {
            if (url.getPort() == configuratorUrl.getPort()) {
                return configureIfMatch(url.getHost(), url);
            }
        } else {// override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0
            // 1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore;
            // 2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider
            if (url.getParameter(SIDE_KEY, PROVIDER).equals(CONSUMER)) {
                return configureIfMatch(NetUtils.getLocalHost(), url);// NetUtils.getLocalHost is the ip address consumer registered to registry.
            } else if (url.getParameter(SIDE_KEY, CONSUMER).equals(PROVIDER)) {
                return configureIfMatch(ANYHOST_VALUE, url);// take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
            }
        }
        return url;
    }

    private URL configureIfMatch(String host, URL url) {
        //如果host为0.0.0.0或者host为配置器URl端口
        if (ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            // TODO, to support wildcards
            //得到提供者们地址
            String providers = configuratorUrl.getParameter(OVERRIDE_PROVIDERS_KEY);
            if (StringUtils.isEmpty(providers) || providers.contains(url.getAddress()) || providers.contains(ANYHOST_VALUE)) {
                //   // 匹配 "application"
                String configApplication = configuratorUrl.getParameter(APPLICATION_KEY,
                        configuratorUrl.getUsername());
                //当前application
                String currentApplication = url.getParameter(APPLICATION_KEY, url.getUsername());
                if (configApplication == null || ANY_VALUE.equals(configApplication)
                        || configApplication.equals(currentApplication)) {
                    // 配置 URL 中的条件 KEYS 集合。其中下面四个 KEY ，不算是条件，而是内置属性。考虑到下面要移除，所以添加到该集合中。
                    Set<String> conditionKeys = new HashSet<String>();
                    conditionKeys.add(CATEGORY_KEY);
                    conditionKeys.add(Constants.CHECK_KEY);
                    conditionKeys.add(DYNAMIC_KEY);
                    conditionKeys.add(ENABLED_KEY);
                    conditionKeys.add(GROUP_KEY);
                    conditionKeys.add(VERSION_KEY);
                    conditionKeys.add(APPLICATION_KEY);
                    conditionKeys.add(SIDE_KEY);
                    conditionKeys.add(CONFIG_VERSION_KEY);
                    conditionKeys.add(COMPATIBLE_CONFIG_KEY);
                    // 判断传入的 url 是否匹配配置规则 URL 的条件。除了 "application" 和 "side" 之外，带有 `"~"` 开头的 KEY ，也是条件。
                    for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        if (key.startsWith("~") || APPLICATION_KEY.equals(key) || SIDE_KEY.equals(key)) {
                            conditionKeys.add(key);
                            if (value != null && !ANY_VALUE.equals(value)
                                    && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                                return url;
                            }
                        }
                    }
                    return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
                }
            }
        }
        return url;
    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
