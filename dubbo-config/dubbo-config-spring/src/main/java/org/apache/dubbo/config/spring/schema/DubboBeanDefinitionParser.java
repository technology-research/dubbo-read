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
package org.apache.dubbo.config.spring.schema;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ArgumentConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.HIDE_KEY_PREFIX;

/**
 * AbstractBeanDefinitionParser
 * 实现 org.springframework.beans.factory.xml.BeanDefinitionParser 接口，Dubbo Bean 定义解析器。
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    /**
     * Bean 对象的类
     */
    private final Class<?> beanClass;
    /**
     * 是否需要在 Bean 对象的编号( id ) 不存在时，自动生成编号。无需被其他应用引用的配置对象，无需自动生成编号。例如有 <dubbo:reference />
     */
    private final boolean required;

    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    @SuppressWarnings("unchecked")
    /**
     * 解析xml配置
     */
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        //创建RootBeanDefinition
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        //默认设置lazyInit=false，不懒加载
        /**
         * 引用缺省是延迟初始化的，只有引用被注入到其它 Bean，或被 getBean() 获取，才会初始化。
         * 如果需要饥饿加载，即没有人引用也立即生成动态代理，可以配置：<dubbo:reference ... init="true" />
         */
        beanDefinition.setLazyInit(false);
        //解析配置对象id。若果不存在并且必须，则进行生成
        String id = element.getAttribute("id");
        if (StringUtils.isEmpty(id) && required) {
            //得到生成bean的名称
            String generatedBeanName = element.getAttribute("name");
            if (StringUtils.isEmpty(generatedBeanName)) {
                if (ProtocolConfig.class.equals(beanClass)) {
                    //当beanClass是协议配置时为dubbo默认
                    generatedBeanName = "dubbo";
                } else {
                    //否则为接口名称
                    generatedBeanName = element.getAttribute("interface");
                }
            }
            /**
             * ？
             */
            if (StringUtils.isEmpty(generatedBeanName)) {
                generatedBeanName = beanClass.getName();
            }
            id = generatedBeanName;
            int counter = 2;
            //若id已存在，通过自增序列，解决重复
            while (parserContext.getRegistry().containsBeanDefinition(id)) {
                id = generatedBeanName + (counter++);
            }
        }
        //如果不为空
        if (StringUtils.isNotEmpty(id)) {
            //如果bean已经存在，抛出spring bean id重复
            if (parserContext.getRegistry().containsBeanDefinition(id)) {
                throw new IllegalStateException("Duplicate spring bean id " + id);
            }
            //添加到spring注册表中
            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);
            //设置bean的id属性值
            beanDefinition.getPropertyValues().addPropertyValue("id", id);
        }
        //如果beanClass为协议配置，处理 <dubbo:protocol/> 特殊情况
        if (ProtocolConfig.class.equals(beanClass)) {
            // 需要满足第 220 至 233 行。
            // 例如：【顺序要这样】
            // <dubbo:service interface="com.alibaba.dubbo.demo.DemoService" protocol="dubbo" ref="demoService"/>
            // <dubbo:protocol id="dubbo" name="dubbo" port="20880"/>
            //遍历bean定义名称
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
                //得到bean定义
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
                //根据协议得到属性值
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
                if (property != null) {
                    Object value = property.getValue();
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        //向协议的属性值加入运行时bean引用
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
                    }
                }
            }
            //处理 <dubbo:service /> 的 class 属性
        } else if (ServiceBean.class.equals(beanClass)) {
            //处理`class`属性。例如 <dubbo:service id="sa" interface="com.alibaba.dubbo.demo.DemoService" class="com.alibaba.dubbo.demo.provider.DemoServiceImpl" >
            String className = element.getAttribute("class");
            if (className != null && className.length() > 0) {
                //新建class的根定义
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                //根据反射设置bean的class
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                //设置延迟初始化为false
                classDefinition.setLazyInit(false);
                //解析Service Bean对象的属性,内含<property/>属性时
                parseProperties(element.getChildNodes(), classDefinition);
                //设置引用对象属性，bean的id为id+Impl
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));
            }
            //特殊处理,解析 `<dubbo:provider />` 的内嵌子元素 `<dubbo:service />`
        } else if (ProviderConfig.class.equals(beanClass)) {
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
            //解析 <dubbo:consumer /> 的内嵌子元素 <dubbo:reference />
        } else if (ConsumerConfig.class.equals(beanClass)) {
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }
        //循环 Bean 对象的 setting 方法，将属性赋值到 Bean 对象
        //已解析的属性集合
        Set<String> props = new HashSet<>();
        ManagedMap parameters = null;
        //循环Bean对象的setting方法，将属性添加到Bean对象的属性中
        for (Method setter : beanClass.getMethods()) {
            //得到setter方法的名称
            String name = setter.getName();
            //判断时set方法长度大于3并且为public，唯一参数
            if (name.length() > 3 && name.startsWith("set")
                    && Modifier.isPublic(setter.getModifiers())
                    && setter.getParameterTypes().length == 1) {
                Class<?> type = setter.getParameterTypes()[0];
                //解析bena属性
                String beanProperty = name.substring(3, 4).toLowerCase() + name.substring(4);
                //添加`props`
                String property = StringUtils.camelToSplitName(beanProperty, "-");
                props.add(property);
                // check the setter/getter whether match

                Method getter = null;
                try {
                    //得到get方法
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        //特殊情况get方法为is开头的
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                        //其他情况的get方法忽略
                        // ignore, there is no need any log here since some class implement the interface: EnvironmentAware,
                        // ApplicationAware, etc. They only have setter method, otherwise will cause the error log during application start up.
                    }
                }
                //getting&&public&&属性值类型统一
                if (getter == null
                        || !Modifier.isPublic(getter.getModifiers())
                        || !type.equals(getter.getReturnType())) {
                    continue;
                }
                // 解析 `<dubbo:parameters />`
                if ("parameters".equals(property)) {
                    parameters = parseParameters(element.getChildNodes(), beanDefinition);
                    //解析`<dubbo:method/>`
                } else if ("methods".equals(property)) {
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
                    //解析`<dubbo:argument/>`
                } else if ("arguments".equals(property)) {
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
                } else {
                    String value = element.getAttribute(property);
                    if (value != null) {
                        value = value.trim();
                        if (value.length() > 0) {
                            // 不向注册到注册中心的情况，即 `registry=N/A` 。
                            if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                                //创建新的注册中心配置，地址设置不可用，并且将属性复制给beanProperty
                                RegistryConfig registryConfig = new RegistryConfig();
                                registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty, registryConfig);
                                // 多注册中心、多服务提供者、多协议并且beanClass为ServiceBean
                            } else if ("provider".equals(property) || "registry".equals(property) || ("protocol".equals(property) && ServiceBean.class.equals(beanClass))) {
                                /**
                                 * For 'provider' 'protocol' 'registry', keep literal value (should be id/name) and set the value to 'registryIds' 'providerIds' protocolIds'
                                 * The following process should make sure each id refers to the corresponding instance, here's how to find the instance for different use cases:
                                 * 1. Spring, check existing bean by id, see{@link ServiceBean#afterPropertiesSet()}; then try to use id to find configs defined in remote Config Center
                                 * 2. API, directly use id to find configs defined in remote Config Center; if all config instances are defined locally, please use {@link org.apache.dubbo.config.ServiceConfig#setRegistries(List)}
                                 */
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty + "Ids", value);
                            } else {
                                Object reference;
                                if (isPrimitive(type)) {
                                    //兼容性处理，异步等等属性
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {
                                        // backward compatibility for the default value in old version's xsd
                                        value = null;
                                    }
                                    reference = value;
                                } else if ("onreturn".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String returnRef = value.substring(0, index);
                                    String returnMethod = value.substring(index + 1);
                                    //创建RuntimeBeanReference指向回调的对象
                                    reference = new RuntimeBeanReference(returnRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);
                                } else if ("onthrow".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String throwRef = value.substring(0, index);
                                    String throwMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(throwRef);
                                    // 设置 `onreturnMethod` 到 BeanDefinition 的属性值
                                    beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);
                                } else if ("oninvoke".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String invokeRef = value.substring(0, index);
                                    String invokeRefMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(invokeRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("oninvokeMethod", invokeRefMethod);
                                    //通用解析
                                } else {
                                    // 指向的 Service 的 Bean 对象，必须是单例
                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        if (!refBean.isSingleton()) {
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                        }
                                    }
                                    reference = new RuntimeBeanReference(value);
                                }
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty, reference);
                            }
                        }
                    }
                }
            }
        }
        // 将 XML 元素，未在上面遍历到的属性，添加到 `parameters` 集合中。目前测试下来，不存在这样的情况。
        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!props.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        return beanDefinition;
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }
    /**
     * 解析内嵌的指向的子 XML 元素
     *
     * @param element 父 XML 元素
     * @param parserContext Spring 解析上下文
     * @param beanClass 内嵌解析子元素的 Bean 的类
     * @param required 是否需要 Bean 的 `id` 属性
     * @param tag 标签
     * @param property 父 Bean 对象在子元素中的属性名
     * @param ref 指向
     * @param beanDefinition 父 Bean 定义对象
     */
    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList != null && nodeList.getLength() > 0) {
            boolean first = true;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    //判断是否为指定要解析的子元素
                    if (tag.equals(node.getNodeName())
                            || tag.equals(node.getLocalName())) {
                        if (first) {
                            first = false;
                            String isDefault = element.getAttribute("default");
                            if (StringUtils.isEmpty(isDefault)) {
                                beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                            }
                        }
                        // 解析子元素，创建 BeanDefinition 对象
                        BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
                        // 设置子 BeanDefinition ，指向父 BeanDefinition 。
                        if (subDefinition != null && ref != null && ref.length() > 0) {
                            subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析属性，解析 <dubbo:service class="" /> 情况下，内涵的 `<property />` 的赋值。
     * @param nodeList 子元素数组
     * @param beanDefinition rootbean定义
     */
    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if ("property".equals(node.getNodeName())
                            || "property".equals(node.getLocalName())) {
                        String name = ((Element) node).getAttribute("name");
                        if (name != null && name.length() > 0) {
                            String value = ((Element) node).getAttribute("value");
                            String ref = ((Element) node).getAttribute("ref");
                            if (value != null && value.length() > 0) {
                                beanDefinition.getPropertyValues().addPropertyValue(name, value);
                            } else if (ref != null && ref.length() > 0) {
                                beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                            } else {
                                throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * 解析 `<dubbo:parameter />`
     *
     * @param nodeList 子元素节点数组
     * @param beanDefinition Bean 定义对象
     * @return 参数集合
     */
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedMap parameters = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    //只解析子元素中的<dubbo:parameter/>
                    if ("parameter".equals(node.getNodeName())
                            || "parameter".equals(node.getLocalName())) {
                        if (parameters == null) {
                            parameters = new ManagedMap();
                        }
                        //添加到参数集合
                        String key = ((Element) node).getAttribute("key");
                        String value = ((Element) node).getAttribute("value");
                        // <dubbo:parameter hide=“” /> 的用途
                        boolean hide = "true".equals(((Element) node).getAttribute("hide"));
                        if (hide) {
                            //设置hidekey前缀
                            key = HIDE_KEY_PREFIX + key;
                        }
                        parameters.put(key, new TypedStringValue(value, String.class));
                    }
                }
            }
            return parameters;
        }
        return null;
    }


    /**
     * 解析 `<dubbo:method />`
     *
     * @param id Bean 的 `id` 属性。
     * @param nodeList 子元素节点数组
     * @param beanDefinition Bean 定义对象
     * @param parserContext 解析上下文
     */
    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                     ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            //解析的方法数组
            ManagedList methods = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                   // 判断值解析 `<dubbo:method />`
                    if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {
                        String methodName = element.getAttribute("name");
                        if (StringUtils.isEmpty(methodName)) {
                            throw new IllegalStateException("<dubbo:method> name attribute == null");
                        }
                        if (methods == null) {
                            methods = new ManagedList();
                        }
                        // 解析 `<dubbo:method />`，创建 BeanDefinition 对象
                        BeanDefinition methodBeanDefinition = parse(((Element) node),
                                parserContext, MethodConfig.class, false);
                        //添加到methods中
                        String name = id + "." + methodName;
                        BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                                methodBeanDefinition, name);
                        methods.add(methodBeanDefinitionHolder);
                    }
                }
            }
            if (methods != null) {
                //将methods集合复制给methods
                beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
            }
        }
    }
    /**
     * 解析 `<dubbo:argument />`
     *
     * @param id Bean 的 `id` 属性。
     * @param nodeList 子元素节点数组
     * @param beanDefinition Bean 定义对象
     * @param parserContext 解析上下文
     */
    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            //解析的参数数组
            ManagedList arguments = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    //判断只解析<dubbo:argument>
                    if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) {
                        String argumentIndex = element.getAttribute("index");
                        if (arguments == null) {
                            arguments = new ManagedList();
                        }
                        // 解析 `<dubbo:argument />`，创建 BeanDefinition 对象
                        BeanDefinition argumentBeanDefinition = parse(((Element) node),
                                parserContext, ArgumentConfig.class, false);
                        // 添加到 `arguments` 中
                        String name = id + "." + argumentIndex;
                        BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                                argumentBeanDefinition, name);
                        arguments.add(argumentBeanDefinitionHolder);
                    }
                }
            }
            if (arguments != null) {
                beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
            }
        }
    }

    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        return parse(element, parserContext, beanClass, required);
    }

}
