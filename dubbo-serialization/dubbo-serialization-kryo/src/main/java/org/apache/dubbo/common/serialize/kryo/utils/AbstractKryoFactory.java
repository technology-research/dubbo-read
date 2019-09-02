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
package org.apache.dubbo.common.serialize.kryo.utils;

import org.apache.dubbo.common.serialize.kryo.CompatibleKryo;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.pool.KryoFactory;
import com.esotericsoftware.kryo.serializers.DefaultSerializers;
import de.javakaffee.kryoserializers.ArraysAsListSerializer;
import de.javakaffee.kryoserializers.BitSetSerializer;
import de.javakaffee.kryoserializers.GregorianCalendarSerializer;
import de.javakaffee.kryoserializers.JdkProxySerializer;
import de.javakaffee.kryoserializers.RegexSerializer;
import de.javakaffee.kryoserializers.SynchronizedCollectionsSerializer;
import de.javakaffee.kryoserializers.URISerializer;
import de.javakaffee.kryoserializers.UUIDSerializer;
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer;

import java.lang.reflect.InvocationHandler;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public abstract class AbstractKryoFactory implements KryoFactory {

    //需要注册的类集合
    private final Set<Class> registrations = new LinkedHashSet<Class>();
    //是否必须注册
    private boolean registrationRequired;
    //kryo是否创建
    private volatile boolean kryoCreated;

    public AbstractKryoFactory() {

    }

    /**
     * only supposed to be called at startup time
     *
     *  later may consider adding support for custom serializer, custom id, etc
     *  Kryo 支持对注册行为，如 kryo.register(SomeClazz.class); ，这会赋予该 Class 一个从 0 开始的编号，但 Kryo 使用注册行为最大的问题在于，其不保证同一个 Class 每一次注册的号码相同，这与注册的顺序有关，也就意味着在不同的机器、同一个机器重启前后都有可能拥有不同的编号，
     *  这会导致序列化产生问题，所以在分布式项目中，一般关闭注册行为。
     */
    public void registerClass(Class clazz) {
        //如果kryo已经创建抛出异常
        if (kryoCreated) {
            throw new IllegalStateException("Can't register class after creating kryo instance");
        }
        //否则加入需要注册类中
        registrations.add(clazz);
    }

    @Override
    public Kryo create() {
        //如果kryo没有被创建
        if (!kryoCreated) {
            kryoCreated = true;
        }
        //创建kryo
        Kryo kryo = new CompatibleKryo();

        // TODO
//        kryo.setReferences(false);
        //设置是否必须注册
        kryo.setRegistrationRequired(registrationRequired);
        // 注册常用类
        kryo.register(Arrays.asList("").getClass(), new ArraysAsListSerializer());
        kryo.register(GregorianCalendar.class, new GregorianCalendarSerializer());
        kryo.register(InvocationHandler.class, new JdkProxySerializer());
        kryo.register(BigDecimal.class, new DefaultSerializers.BigDecimalSerializer());
        kryo.register(BigInteger.class, new DefaultSerializers.BigIntegerSerializer());
        kryo.register(Pattern.class, new RegexSerializer());
        kryo.register(BitSet.class, new BitSetSerializer());
        kryo.register(URI.class, new URISerializer());
        kryo.register(UUID.class, new UUIDSerializer());
        UnmodifiableCollectionsSerializer.registerSerializers(kryo);
        SynchronizedCollectionsSerializer.registerSerializers(kryo);
        // 注册常用数据结构
        // now just added some very common classes
        // TODO optimization
        kryo.register(HashMap.class);
        kryo.register(ArrayList.class);
        kryo.register(LinkedList.class);
        kryo.register(HashSet.class);
        kryo.register(TreeSet.class);
        kryo.register(Hashtable.class);
        kryo.register(Date.class);
        kryo.register(Calendar.class);
        kryo.register(ConcurrentHashMap.class);
        kryo.register(SimpleDateFormat.class);
        kryo.register(GregorianCalendar.class);
        kryo.register(Vector.class);
        kryo.register(BitSet.class);
        kryo.register(StringBuffer.class);
        kryo.register(StringBuilder.class);
        kryo.register(Object.class);
        kryo.register(Object[].class);
        kryo.register(String[].class);
        kryo.register(byte[].class);
        kryo.register(char[].class);
        kryo.register(int[].class);
        kryo.register(float[].class);
        kryo.register(double[].class);
        // `registrations` 的注册
        for (Class clazz : registrations) {
            kryo.register(clazz);
        }
        // SerializableClassRegistry 的注册
        SerializableClassRegistry.getRegisteredClasses().forEach((clazz, ser) -> {
            if (ser == null) {
                kryo.register(clazz);
            } else {
                kryo.register(clazz, (Serializer) ser);
            }
        });

        return kryo;
    }

    public void setRegistrationRequired(boolean registrationRequired) {
        this.registrationRequired = registrationRequired;
    }

    public abstract void returnKryo(Kryo kryo);

    public abstract Kryo getKryo();
}
