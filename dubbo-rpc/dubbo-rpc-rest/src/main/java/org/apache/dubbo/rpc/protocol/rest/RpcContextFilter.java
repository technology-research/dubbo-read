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
package org.apache.dubbo.rpc.protocol.rest;

import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.RpcContext;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import javax.annotation.Priority;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 处理RpcContext的Filter
 */
@Priority(Integer.MIN_VALUE + 1)//排在LoggingFilter的后面
public class RpcContextFilter implements ContainerRequestFilter, ClientRequestFilter {
    //传递Dubbo Attachment 的Header
    private static final String DUBBO_ATTACHMENT_HEADER = "Dubbo-Attachments";
    //目前，我们使用一个头部来保存附件，因此总的附件大小限制大约为8k
    // currently we use a single header to hold the attachments so that the total attachment size limit is about 8k
    private static final int MAX_HEADER_SIZE = 8 * 1024;

    @Override//Server的filter
    public void filter(ContainerRequestContext requestContext) throws IOException {
        //设置RpcContext的Request jboss的api获得http的request
        HttpServletRequest request = ResteasyProviderFactory.getContextData(HttpServletRequest.class);
        RpcContext.getContext().setRequest(request);

        // this only works for servlet containers 这只适用于servlet容器
        if (request != null && RpcContext.getContext().getRemoteAddress() == null) {
            //从request中获取
            RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
        }
        //设置RpcContext的Response
        RpcContext.getContext().setResponse(ResteasyProviderFactory.getContextData(HttpServletResponse.class));
        //得到headers
        String headers = requestContext.getHeaderString(DUBBO_ATTACHMENT_HEADER);
        if (headers != null) {
            //根据,分割循环
            for (String header : headers.split(",")) {
                //寻找=
                int index = header.indexOf("=");

                if (index > 0) {
                    //按照=分割得到key
                    String key = header.substring(0, index);
                    //得到value
                    String value = header.substring(index + 1);
                    if (!StringUtils.isEmpty(key)) {
                        //设置到RpcContext中
                        RpcContext.getContext().setAttachment(key.trim(), value.trim());
                    }
                }
            }
        }
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        int size = 0;
        for (Map.Entry<String, String> entry : RpcContext.getContext().getAttachments().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (illegalForRest(key) || illegalForRest(value)) {
                throw new IllegalArgumentException("The attachments of " + RpcContext.class.getSimpleName() + " must not contain ',' or '=' when using rest protocol");
            }

            // TODO for now we don't consider the differences of encoding and server limit
            if (value != null) {
                size += value.getBytes(StandardCharsets.UTF_8).length;
            }
            if (size > MAX_HEADER_SIZE) {
                throw new IllegalArgumentException("The attachments of " + RpcContext.class.getSimpleName() + " is too big");
            }

            String attachments = key + "=" + value;
            requestContext.getHeaders().add(DUBBO_ATTACHMENT_HEADER, attachments);
        }
    }

    /**
     * If a string value illegal for rest protocol(',' and '=' is illegal for rest protocol).
     *
     * @param v string value
     * @return true for illegal
     */
    private boolean illegalForRest(String v) {
        if (StringUtils.isNotEmpty(v)) {
            return v.contains(",") || v.contains("=");
        }
        return false;
    }
}
