/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.ws.security.cache;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.ConfigurationFactory;

import org.apache.cxf.Bus;
import org.apache.cxf.common.classloader.ClassLoaderUtils;
import org.apache.cxf.resource.ResourceManager;

/**
 * We need to reference count the EHCacheManager things
 */
public final class EHCacheManagerHolder {
    public static final String GLOBAL_EHCACHE_MANAGER_NAME = 
            "ws-security.global.ehcachemanager";
    
    private static final ConcurrentHashMap<String, AtomicInteger> COUNTS 
        = new ConcurrentHashMap<String, AtomicInteger>(8, 0.75f, 2);
    
    private static Method cacheManagerCreateMethodNoArg;
    private static Method createMethodURLArg;
    private static Method cacheManagerCreateMethodConfigurationArg;
    static {
        // these methods are either completely available or absent (valid assumption from 2.5.0 to 2.7.2 so far)
        try {
            // from 2.5.2
            cacheManagerCreateMethodNoArg = CacheManager.class.getMethod("newInstance", (Class<?>[])null);
            createMethodURLArg = CacheManager.class.getMethod("newInstance", URL.class);
            cacheManagerCreateMethodConfigurationArg = CacheManager.class.getMethod("newInstance", Configuration.class);
        } catch (NoSuchMethodException e) {
            try {
                // before 2.5.2
                cacheManagerCreateMethodNoArg = CacheManager.class.getMethod("create", (Class<?>[])null);
                createMethodURLArg = CacheManager.class.getMethod("create", URL.class);
                cacheManagerCreateMethodConfigurationArg = CacheManager.class.getMethod("create", Configuration.class);
            } catch (Throwable t) {
                // ignore
            }
        }
    }
    
    private EHCacheManagerHolder() {
        //utility
    }
    
    
    public static CacheConfiguration getCacheConfiguration(String key,
                                                           CacheManager cacheManager) {
        CacheConfiguration cc = cacheManager.getConfiguration().getCacheConfigurations().get(key);
        if (cc == null && key.contains("-")) {
            cc = cacheManager.getConfiguration().getCacheConfigurations().get(
                    key.substring(0, key.lastIndexOf('-')));
        }
        if (cc == null) {
            cc = cacheManager.getConfiguration().getDefaultCacheConfiguration();
        }
        if (cc == null) {
            cc = new CacheConfiguration();
        } else {
            cc = (CacheConfiguration)cc.clone();
        }
        cc.setName(key);
        return cc;
    }
    
    public static CacheManager getCacheManager(Bus bus, URL configFileURL) {
        CacheManager cacheManager = null;
        
        String globalCacheManagerName = getGlobalCacheManagerName(bus);
        if (globalCacheManagerName != null) {
            cacheManager = CacheManager.getCacheManager(globalCacheManagerName);
        }
        
        // notice for a global cache manager, we skip the count stuff which
        // means the release cache manager method below is a no op, as the
        // COUNT will not have been initialised.
        if (cacheManager == null) {
            if (configFileURL == null) {
                //using the default
                cacheManager = findDefaultCacheManager(bus);
            }
            
            if (cacheManager == null) {
                if (configFileURL == null) {
                    cacheManager = createCacheManager();
                } else {
                    cacheManager = createCacheManager(configFileURL);
                }
            }
            
            AtomicInteger a = COUNTS.get(cacheManager.getName());
            if (a == null) {
                COUNTS.putIfAbsent(cacheManager.getName(), new AtomicInteger());
                a = COUNTS.get(cacheManager.getName());
            }
            if (a.incrementAndGet() == 1) {
                //System.out.println("Create!! " + cacheManager.getName());
            }
        }
        return cacheManager;
    }

    private static String getGlobalCacheManagerName(Bus bus) {
        if (bus != null) {
            return (String) bus.getProperty(GLOBAL_EHCACHE_MANAGER_NAME);
        } else {
            return null;
        }
    }

    private static CacheManager findDefaultCacheManager(Bus bus) {
        String defaultConfigFile = "/cxf-ehcache.xml";
        URL configFileURL = null;
        String busId = "";
        if (bus != null) {
            ResourceManager rm = bus.getExtension(ResourceManager.class);
            configFileURL = rm.resolveResource(defaultConfigFile, URL.class);
            busId = bus.getId();
        }
        try {
            if (configFileURL == null) {
                configFileURL = 
                    ClassLoaderUtils.getResource(defaultConfigFile, EHCacheReplayCacheFactory.class);
            }
            if (configFileURL == null) {
                configFileURL = new URL(defaultConfigFile);
            }
        } catch (IOException e) {
            // Do nothing
        }
        try {
            Configuration conf = ConfigurationFactory.parseConfiguration(configFileURL);
            /*
            String perBus = (String)bus.getProperty("ws-security.cachemanager.per.bus");
            if (perBus == null) {
                perBus = "true";
            }
            if (Boolean.parseBoolean(perBus)) {
            */
            conf.setName(busId);
            if ("java.io.tmpdir".equals(conf.getDiskStoreConfiguration().getOriginalPath())) {
                String path = conf.getDiskStoreConfiguration().getPath() + File.separator
                    + busId;
                conf.getDiskStoreConfiguration().setPath(path);
            }
            return createCacheManager(conf);
        } catch (Throwable t) {
            return null;
        }
    }


    public static void releaseCacheManger(CacheManager cacheManager) {
        AtomicInteger a = COUNTS.get(cacheManager.getName());
        if (a == null) {
            return;
        }
        if (a.decrementAndGet() == 0) {
            //System.out.println("Shutdown!! " + cacheManager.getName());
            cacheManager.shutdown();
        }
    }
    
    static CacheManager createCacheManager() throws CacheException {
        try {
            return (CacheManager)cacheManagerCreateMethodNoArg.invoke(null, (Object[])null);
        } catch (Exception e) {
            throw new CacheException(e);
        }
    }

    static CacheManager createCacheManager(URL url) throws CacheException {
        try {
            return (CacheManager)createMethodURLArg.invoke(null, new Object[]{url});
        } catch (Exception e) {
            throw new CacheException(e);
        }
    }

    static CacheManager createCacheManager(Configuration conf) throws CacheException {
        try {
            return (CacheManager)cacheManagerCreateMethodConfigurationArg.invoke(null, new Object[]{conf});
        } catch (Exception e) {
            throw new CacheException(e);
        }
    }
}
