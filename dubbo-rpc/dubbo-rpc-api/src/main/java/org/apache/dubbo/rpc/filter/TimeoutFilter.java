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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ListenableFilter;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.Arrays;

/**
 * Log any invocation timeout, but don't stop server from running
 */
@Activate(group = CommonConstants.PROVIDER)
public class TimeoutFilter extends ListenableFilter {

    private static final Logger logger = LoggerFactory.getLogger(TimeoutFilter.class);
    //超时过滤开始的时间
    private static final String TIMEOUT_FILTER_START_TIME = "timeout_filter_start_time";

    public TimeoutFilter() {
        super.listener = new TimeoutListener();
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        //设置超时过滤器开始执行时间
        invocation.setAttachment(TIMEOUT_FILTER_START_TIME, String.valueOf(System.currentTimeMillis()));
        //调用方法
        return invoker.invoke(invocation);
    }

    static class TimeoutListener implements Listener {

        @Override
        public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
            //得到超时开始时间
            String startAttach = invocation.getAttachment(TIMEOUT_FILTER_START_TIME);
            if (startAttach != null) {
                //得到总耗时
                long elapsed = System.currentTimeMillis() - Long.valueOf(startAttach);
                //总耗时和timeout比较，默认为无限超时
                if (invoker.getUrl() != null && elapsed > invoker.getUrl().getMethodParameter(invocation.getMethodName(), "timeout", Integer.MAX_VALUE)) {
                    //打印日志
                    if (logger.isWarnEnabled()) {
                        logger.warn("invoke time out. method: " + invocation.getMethodName() + " arguments: " + Arrays.toString(invocation.getArguments()) + " , url is " + invoker.getUrl() + ", invoke elapsed " + elapsed + " ms.");
                    }
                }
            }
        }

        @Override
        public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {

        }
    }
}
