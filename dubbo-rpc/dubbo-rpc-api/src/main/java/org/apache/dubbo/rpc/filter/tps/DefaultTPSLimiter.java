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
package org.apache.dubbo.rpc.filter.tps;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.rpc.Constants.TPS_LIMIT_RATE_KEY;
import static org.apache.dubbo.rpc.Constants.TPS_LIMIT_INTERVAL_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_TPS_LIMIT_INTERVAL;

/**
 * DefaultTPSLimiter is a default implementation for tps filter. It is an in memory based implementation for storing
 * tps information. It internally use
 *
 * @see org.apache.dubbo.rpc.filter.TpsLimitFilter
 */
public class DefaultTPSLimiter implements TPSLimiter {

    private final ConcurrentMap<String, StatItem> stats = new ConcurrentHashMap<String, StatItem>();

    @Override
    public boolean isAllowable(URL url, Invocation invocation) {
        //得到tps配置，限流
        int rate = url.getParameter(TPS_LIMIT_RATE_KEY, -1);
        //得到tps.interval配置 获得 TPS 周期配置项，默认 60 秒
        long interval = url.getParameter(TPS_LIMIT_INTERVAL_KEY, DEFAULT_TPS_LIMIT_INTERVAL);
        //得到服务唯一key
        String serviceKey = url.getServiceKey();
        if (rate > 0) {
            //根据key去本地缓存中拿StatItem
            StatItem statItem = stats.get(serviceKey);
            //不存在，创建
            if (statItem == null) {
                //将参数设置
                //stats.putIfAbsent(serviceKey, new StatItem(serviceKey, rate, interval));
                //在根据serviceKey得到
                //statItem = stats.get(serviceKey);
                //FIXME 重构为如下方法代替
                statItem = stats.computeIfAbsent(serviceKey, t -> new StatItem(serviceKey, rate, interval));

            } else {
                //rate or interval has changed, rebuild
                //如果statItem的rate不等于得到tps配置或者interval不等于获得 TPS 周期配置项
                if (statItem.getRate() != rate || statItem.getInterval() != interval) {
                    //新设置进行
                    stats.put(serviceKey, new StatItem(serviceKey, rate, interval));
                    //然后得到statItem
                    statItem = stats.get(serviceKey);
                }
            }
            //是否限流
            return statItem.isAllowable();
        } else {
            StatItem statItem = stats.get(serviceKey);
            if (statItem != null) {
                stats.remove(serviceKey);
            }
        }

        return true;
    }

}
