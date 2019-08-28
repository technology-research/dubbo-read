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

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.AccessLogData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.ACCESS_LOG_KEY;

/**
 * Record access log for the service.
 * <p>
 * Logger key is <code><b>dubbo.accesslog</b></code>.
 * In order to configure access log appear in the specified appender only, additivity need to be configured in log4j's
 * config file, for example:
 * <code>
 * <pre>
 * &lt;logger name="<b>dubbo.accesslog</b>" <font color="red">additivity="false"</font>&gt;
 *    &lt;level value="info" /&gt;
 *    &lt;appender-ref ref="foo" /&gt;
 * &lt;/logger&gt;
 * </pre></code>
 */
@Activate(group = PROVIDER, value = ACCESS_LOG_KEY)
public class AccessLogFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AccessLogFilter.class);

    private static final String LOG_KEY = "dubbo.accesslog";
    //日志最大缓存
    private static final int LOG_MAX_BUFFER = 5000;

    private static final long LOG_OUTPUT_INTERVAL = 5000;

    private static final String FILE_DATE_FORMAT = "yyyyMMdd";
    //将它声明为单例是安全的，因为它只在单线程上运行
    // It's safe to declare it as singleton since it runs on single thread only
    private static final DateFormat FILE_NAME_FORMATTER = new SimpleDateFormat(FILE_DATE_FORMAT);

    private static final Map<String, Set<AccessLogData>> LOG_ENTRIES = new ConcurrentHashMap<String, Set<AccessLogData>>();

    private static final ScheduledExecutorService LOG_SCHEDULED = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Dubbo-Access-Log", true));

    /**
     * Default constructor initialize demon thread for writing into access log file with names with access log key
     * defined in url <b>accesslog</b>
     */
    public AccessLogFilter() {
        //初始化延迟5s执行，随后每5秒执行一次
        LOG_SCHEDULED.scheduleWithFixedDelay(this::writeLogToFile, LOG_OUTPUT_INTERVAL, LOG_OUTPUT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * This method logs the access log for service method invocation call.
     *
     * @param invoker service
     * @param inv     Invocation service method.
     * @return Result from service method.
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        try {
            //得到accessLogKey配置哦
            String accessLogKey = invoker.getUrl().getParameter(ACCESS_LOG_KEY);
            if (ConfigUtils.isNotEmpty(accessLogKey)) {
                //构造AccessLogData
                AccessLogData logData = buildAccessLogData(invoker, inv);
                log(accessLogKey, logData);
            }
        } catch (Throwable t) {
            logger.warn("Exception in AccessLogFilter of service(" + invoker + " -> " + inv + ")", t);
        }
        //调用方法
        return invoker.invoke(inv);
    }

    /**
     * 记录日志
     * @param accessLog
     * @param accessLogData
     */
    private void log(String accessLog, AccessLogData accessLogData) {
        //如果accessLog不存在，就新建一个ConcurrentHashSet
        Set<AccessLogData> logSet = LOG_ENTRIES.computeIfAbsent(accessLog, k -> new ConcurrentHashSet<>());

        if (logSet.size() < LOG_MAX_BUFFER) {
            //得到或者新建的ConcurrentHashSet中set accessLogData数据
            logSet.add(accessLogData);
        } else {
            //TODO we needs use force writing to file so that buffer gets clear and new log can be written.
            logger.warn("AccessLog buffer is full skipping buffer ");
        }
    }

    /**
     * 每隔5s去执行讲日志写入文件操作
     */
    private void writeLogToFile() {
        /**
         * 日志队列不为空
         */
        if (!LOG_ENTRIES.isEmpty()) {

            for (Map.Entry<String, Set<AccessLogData>> entry : LOG_ENTRIES.entrySet()) {
                try {
                    //得到accessLog和AccessLogData的set集合
                    String accessLog = entry.getKey();
                    Set<AccessLogData> logSet = entry.getValue();
                    //如果accessLog为true或者default 使用日志组件，例如 Log4j 等写
                    if (ConfigUtils.isDefault(accessLog)) {
                        //执行服务日志
                        processWithServiceLogger(logSet);
                    } else {
                        //否则新建一个文件
                        File file = new File(accessLog);
                        //将日志写入 判断日志文件夹是否存在，不存在就创建，存在就使用原来的
                        createIfLogDirAbsent(file);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Append log to " + accessLog);
                        }
                        //修改文件名
                        renameFile(file);
                        //写入
                        processWithAccessKeyLogger(logSet, file);
                    }

                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 日志写入文件
     * @param logSet
     * @param file
     * @throws IOException
     */
    private void processWithAccessKeyLogger(Set<AccessLogData> logSet, File file) throws IOException {
        try (FileWriter writer = new FileWriter(file, true)) {
            for (Iterator<AccessLogData> iterator = logSet.iterator();
                 iterator.hasNext();
                 iterator.remove()) {
                //写入文件中
                writer.write(iterator.next().getLogMessage());
                //分割符，
                writer.write(System.getProperty("line.separator"));
            }
            writer.flush();
        }
    }

    //构造访问日志数据
    private AccessLogData buildAccessLogData(Invoker<?> invoker, Invocation inv) {
        AccessLogData logData = AccessLogData.newLogData();
        logData.setServiceName(invoker.getInterface().getName());
        logData.setMethodName(inv.getMethodName());
        logData.setVersion(invoker.getUrl().getParameter(VERSION_KEY));
        logData.setGroup(invoker.getUrl().getParameter(GROUP_KEY));
        logData.setInvocationTime(new Date());
        logData.setTypes(inv.getParameterTypes());
        logData.setArguments(inv.getArguments());
        return logData;
    }

    /**
     * 执行服务日志
     * @param logSet
     */
    private void processWithServiceLogger(Set<AccessLogData> logSet) {
        //迭代器遍历
        //初始化为iterator,这里使用Iterator而不用foreach循环是否了防止remove的时候出现并发修改异常
        for (Iterator<AccessLogData> iterator = logSet.iterator();
             //是否还有下一个
             iterator.hasNext();
             //移除上一个
             iterator.remove()) {
            //得到AccessLogData数据
            AccessLogData logData = iterator.next();
            LoggerFactory.getLogger(LOG_KEY + "." + logData.getServiceName()).info(logData.getLogMessage());
        }
    }

    private void createIfLogDirAbsent(File file) {
        File dir = file.getParentFile();
        if (null != dir && !dir.exists()) {
            dir.mkdirs();
        }
    }

    private void renameFile(File file) {
        if (file.exists()) {
            //日期格式
            String now = FILE_NAME_FORMATTER.format(new Date());
            String last = FILE_NAME_FORMATTER.format(new Date(file.lastModified()));
            if (!now.equals(last)) {
                File archive = new File(file.getAbsolutePath() + "." + last);
                file.renameTo(archive);
            }
        }
    }
}
