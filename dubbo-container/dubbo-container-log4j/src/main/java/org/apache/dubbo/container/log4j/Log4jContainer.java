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
package org.apache.dubbo.container.log4j;

import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.container.Container;

import org.apache.log4j.Appender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.LogManager;
import org.apache.log4j.PropertyConfigurator;

import java.util.Enumeration;
import java.util.Properties;

/**
 * Log4jContainer. (SPI, Singleton, ThreadSafe)
 *
 * The container class implementation for Log4j
 */
public class Log4jContainer implements Container {
    //日志文件路径配置key
    public static final String LOG4J_FILE = "dubbo.log4j.file";

    public static final String LOG4J_LEVEL = "dubbo.log4j.level";
    //日志子目录路径配置 key
    public static final String LOG4J_SUBDIRECTORY = "dubbo.log4j.subdirectory";
    //默认级别错误日志
    public static final String DEFAULT_LOG4J_LEVEL = "ERROR";

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        //得到文件路径
        String file = ConfigurationUtils.getProperty(LOG4J_FILE);
        if (file != null && file.length() > 0) {
            //得到日志级别
            String level = ConfigurationUtils.getProperty(LOG4J_LEVEL);
            if (StringUtils.isEmpty(level)) {
                level = DEFAULT_LOG4J_LEVEL;
            }
            //设置properties属性文件配置
            Properties properties = new Properties();
            //日志级别
            properties.setProperty("log4j.rootLogger", level + ",application");
            //日志应用配置
            properties.setProperty("log4j.appender.application", "org.apache.log4j.DailyRollingFileAppender");
            //日志文件路径
            properties.setProperty("log4j.appender.application.File", file);
            //是否追加
            properties.setProperty("log4j.appender.application.Append", "true");
            //日期格式
            properties.setProperty("log4j.appender.application.DatePattern", "'.'yyyy-MM-dd");
            //布局
            properties.setProperty("log4j.appender.application.layout", "org.apache.log4j.PatternLayout");
            properties.setProperty("log4j.appender.application.layout.ConversionPattern", "%d [%t] %-5p %C{6} (%F:%L) - %m%n");
            //讲属性配置到PropertyConfigurator中
            PropertyConfigurator.configure(properties);
        }
        //得到子目录路径
        String subdirectory = ConfigurationUtils.getProperty(LOG4J_SUBDIRECTORY);
        if (subdirectory != null && subdirectory.length() > 0) {
            //得到当前日志集合
            Enumeration<org.apache.log4j.Logger> ls = LogManager.getCurrentLoggers();
            while (ls.hasMoreElements()) {
                org.apache.log4j.Logger l = ls.nextElement();
                if (l != null) {
                    //循环每一个logger对象的Appender对象
                    Enumeration<Appender> as = l.getAllAppenders();
                    while (as.hasMoreElements()) {
                        Appender a = as.nextElement();
                        if (a instanceof FileAppender) {
                            FileAppender fa = (FileAppender) a;
                            String f = fa.getFile();
                            if (f != null && f.length() > 0) {
                                int i = f.replace('\\', '/').lastIndexOf('/');
                                String path;
                                if (i == -1) {
                                    path = subdirectory;
                                } else {
                                    path = f.substring(0, i);
                                    if (!path.endsWith(subdirectory)) {
                                        path = path + "/" + subdirectory;
                                    }
                                    f = f.substring(i + 1);
                                }
                                fa.setFile(path + "/" + f);
                                fa.activateOptions();
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void stop() {
    }

}
