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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机，按权重设置速记概率
 * This class select one provider from multiple providers randomly.
 * You can define weights for each provider:
 * If the weights are all the same then it will use random.nextInt(number of invokers).
 * If the weights are different then it will use random.nextInt(w1 + w2 + ... + wn)
 * Note that if the performance of the machine is better than others, you can set a larger weight.
 * If the performance is not so good, you can set a smaller weight.
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    /**
     * Select one invoker between a list using a random criteria
     * @param invokers List of possible invokers
     * @param url URL
     * @param invocation Invocation
     * @param <T>
     * @return The selected invoker
     *
     * 假定有3台dubbo provider:
     *
     * 10.0.0.1:20884, weight=2
     * 10.0.0.1:20886, weight=3
     * 10.0.0.1:20888, weight=4
     * 随机算法的实现：
     * totalWeight=9;
     *
     * 假设offset=1（即random.nextInt(9)=1）
     * 1-2=-1<0？是，所以选中 10.0.0.1:20884, weight=2
     * 假设offset=4（即random.nextInt(9)=4）
     * 4-2=2<0？否，这时候offset=2， 2-3<0？是，所以选中 10.0.0.1:20886, weight=3
     * 假设offset=7（即random.nextInt(9)=7）
     * 7-2=5<0？否，这时候offset=5， 5-3=2<0？否，这时候offset=2， 2-4<0？是，所以选中 10.0.0.1:20888, weight=4
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        //得到服务提供者invoker集合总数
        int length = invokers.size();
        // Every invoker has the same weight?
        //默认任何invoker都有相同的权重
        boolean sameWeight = true;
        // the weight of every invokers
        //任何invokers的权重数组
        int[] weights = new int[length];
        // the first invoker's weight
        //得到第一次权重，
        int firstWeight = getWeight(invokers.get(0), invocation);
        //第一次权重为firstWeight
        weights[0] = firstWeight;
        // The sum of weights
        //总权重为第一次权重
        int totalWeight = firstWeight;
        for (int i = 1; i < length; i++) {
            // 循环遍历得到不同invoker的权重
            int weight = getWeight(invokers.get(i), invocation);
            // save for later use
            //保存在数组中
            weights[i] = weight;
            // Sum
            //算的总权重
            totalWeight += weight;
            //判断是否所有invoker具有相同权重
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }
        // 权重不相等，随机后，判断在哪个 Invoker 的权重区间中
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            //随机得到offset
            //如果(不是每个调用者都有相同的权重&至少有一个调用者的权重>0)，根据总权重随机选择。
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                // 区间判断
                if (offset < 0) {
                    //如果offset小于0就得到这个invoker
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 权重相等，平均随机
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

}
