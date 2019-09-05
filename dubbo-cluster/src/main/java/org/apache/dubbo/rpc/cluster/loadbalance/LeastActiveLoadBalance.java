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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LeastActiveLoadBalance
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        //得到invoker数量
        int length = invokers.size();
        // The least active value of all invokers
        // 默认最小活跃为全部invoker
        int leastActive = -1;
        //具有相同最少活动值的调用者数量(最少活动)
        // The number of invokers having the same least active value (leastActive)
        int leastCount = 0;
        //具有相同最少活动值(最少活动)的调用者的索引
        // The index of invokers having the same least active value (leastActive)
        int[] leastIndexes = new int[length];
        // the weight of every invokers
        //权重集合
        int[] weights = new int[length];
        // The sum of the warmup weights of all the least active invokes
        //所有最少活动者的预热权值的和
        int totalWeight = 0;
        // The weight of the first least active invoke
        //第一次最小活跃invoke的权重
        int firstWeight = 0;
        // Every least active invoker has the same weight value?
        //每个最少活动的调用程序都具有相同的权重值?
        boolean sameWeight = true;


        // Filter out all the least active invokers
        //过滤掉所有最不活跃的调用者
        for (int i = 0; i < length; i++) {
            //得到invoker
            Invoker<T> invoker = invokers.get(i);
            // Get the active number of the invoke
            //得到active数
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // Get the weight of the invoke configuration. The default value is 100.
            //得到权重 获取调用配置的权重。默认值是100。
            int afterWarmup = getWeight(invoker, invocation);
            //保存第一次
            // save for later use
            weights[i] = afterWarmup;
            // If it is the first invoker or the active number of the invoker is less than the current least active number
            //默认最小活跃为-1或者活跃数小于最小活跃数
            if (leastActive == -1 || active < leastActive) {
                // Reset the active number of the current invoker to the least active number
                //重置最小活跃数为活跃数
                leastActive = active;
                // Reset the number of least active invokers
                //具有相同最少活动值的调用者数量(最少活动)
                leastCount = 1;
                // Put the first least active invoker first in leastIndexes
                //将第一个最少活动的调用程序放在最少索引中
                leastIndexes[0] = i;
                // Reset totalWeight
                //重置总权重
                totalWeight = afterWarmup;
                // Record the weight the first least active invoker
                //设置第一次invoker权重数
                firstWeight = afterWarmup;
                // Each invoke has the same weight (only one invoker here)
                sameWeight = true;
                // If current invoker's active value equals with leaseActive, then accumulating.
                //如果当前调用程序的活动值与leaseActive相等，则进行累加。
            } else if (active == leastActive) {
                // Record the index of the least active invoker in leastIndexes order
                //以最少索引顺序记录最少活动调用程序的索引
                leastIndexes[leastCount++] = i;
                // Accumulate the total weight of the least active invoker
                totalWeight += afterWarmup;
                // If every invoker has the same weight?
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // Choose an invoker from all the least active invokers
        if (leastCount == 1) {
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexes[0]);
        }
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on 
            // totalWeight.
            // 如果权重不相同且权重大于0则按总权重数随机
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            // 并确定随机值落在哪个片断上
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i];
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}