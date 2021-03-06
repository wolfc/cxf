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

package org.apache.cxf.systest.ws.cache;

import java.net.URL;

import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Service;

import org.apache.cxf.Bus;
import org.apache.cxf.bus.spring.SpringBusFactory;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.systest.ws.cache.server.Server;
import org.apache.cxf.systest.ws.common.SecurityTestUtil;
import org.apache.cxf.testutil.common.AbstractBusClientServerTestBase;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.tokenstore.TokenStore;
import org.example.contract.doubleit.DoubleItPortType;
import org.junit.BeforeClass;

/**
 * A set of tests for token caching on the client side
 */
public class CachingTest extends AbstractBusClientServerTestBase {
    public static final String PORT = allocatePort(Server.class);

    private static final String NAMESPACE = "http://www.example.org/contract/DoubleIt";
    private static final QName SERVICE_QNAME = new QName(NAMESPACE, "DoubleItService");
    
    @BeforeClass
    public static void startServers() throws Exception {
        assertTrue(
                "Server failed to launch",
                // run the server in the same process
                // set this to false to fork
                launchServer(Server.class, true)
        );
    }
    
    @org.junit.AfterClass
    public static void cleanup() throws Exception {
        SecurityTestUtil.cleanup();
        stopAllServers();
    }

    @org.junit.Test
    public void testSymmetric() throws Exception {

        SpringBusFactory bf = new SpringBusFactory();
        URL busFile = CachingTest.class.getResource("client/client.xml");

        Bus bus = bf.createBus(busFile.toString());
        SpringBusFactory.setDefaultBus(bus);
        SpringBusFactory.setThreadDefaultBus(bus);
        
        URL wsdl = CachingTest.class.getResource("DoubleItCache.wsdl");
        Service service = Service.create(wsdl, SERVICE_QNAME);
        QName portQName = new QName(NAMESPACE, "DoubleItCacheSymmetricPort");
        
        // First invocation
        DoubleItPortType port = 
                service.getPort(portQName, DoubleItPortType.class);
        updateAddressPort(port, PORT);
        
        port.doubleIt(25);

        Client client = ClientProxy.getClient(port);
        TokenStore tokenStore = 
            (TokenStore)client.getEndpoint().getEndpointInfo().getProperty(
                SecurityConstants.TOKEN_STORE_CACHE_INSTANCE
            );
        assertNotNull(tokenStore);
        // We expect 1 token
        assertEquals(1, tokenStore.getTokenIdentifiers().size());
        
        
        
        // Second invocation
        DoubleItPortType port2 = service.getPort(portQName, DoubleItPortType.class);
        updateAddressPort(port2, PORT);
        
        port2.doubleIt(35);

        client = ClientProxy.getClient(port2);
        tokenStore = 
            (TokenStore)client.getEndpoint().getEndpointInfo().getProperty(
                SecurityConstants.TOKEN_STORE_CACHE_INSTANCE
            );

        assertNotNull(tokenStore);
        // There should now be 2 tokens as both proxies share the same TokenStore
        assertEquals(2, tokenStore.getTokenIdentifiers().size());
        
        ((java.io.Closeable)port).close();
        //port2 is still holding onto the cache, thus, this should still be 2
        assertEquals(2, tokenStore.getTokenIdentifiers().size());
        ((java.io.Closeable)port2).close();
        //port2 is now closed, this should be null
        assertNull(tokenStore.getTokenIdentifiers());       
        bus.shutdown(true);
    }
    
    @org.junit.Test
    public void testCachePerProxySymmetric() throws Exception {

        SpringBusFactory bf = new SpringBusFactory();
        URL busFile = CachingTest.class.getResource("client/client.xml");

        Bus bus = bf.createBus(busFile.toString());
        SpringBusFactory.setDefaultBus(bus);
        SpringBusFactory.setThreadDefaultBus(bus);
        
        URL wsdl = CachingTest.class.getResource("DoubleItCache.wsdl");
        Service service = Service.create(wsdl, SERVICE_QNAME);
        QName portQName = new QName(NAMESPACE, "DoubleItCachePerProxySymmetricPort");
        
        // First invocation
        DoubleItPortType port = 
                service.getPort(portQName, DoubleItPortType.class);
        updateAddressPort(port, PORT);
        
        ((BindingProvider)port).getRequestContext().put(
            SecurityConstants.CACHE_IDENTIFIER, "proxy1"
        );
        ((BindingProvider)port).getRequestContext().put(
            SecurityConstants.CACHE_CONFIG_FILE, "client/per-proxy-cache.xml"
        );
        
        port.doubleIt(25);

        Client client = ClientProxy.getClient(port);
        TokenStore tokenStore = 
            (TokenStore)client.getEndpoint().getEndpointInfo().getProperty(
                SecurityConstants.TOKEN_STORE_CACHE_INSTANCE
            );
        assertNotNull(tokenStore);
        // We expect 1 token
        assertEquals(1, tokenStore.getTokenIdentifiers().size());
        
        
        // Second invocation
        DoubleItPortType port2 = service.getPort(portQName, DoubleItPortType.class);
        updateAddressPort(port2, PORT);
        
        ((BindingProvider)port2).getRequestContext().put(
            SecurityConstants.CACHE_IDENTIFIER, "proxy2"
        );
        ((BindingProvider)port2).getRequestContext().put(
            SecurityConstants.CACHE_CONFIG_FILE, "client/per-proxy-cache.xml"
        );
        
        port2.doubleIt(35);

        client = ClientProxy.getClient(port2);
        tokenStore = 
            (TokenStore)client.getEndpoint().getEndpointInfo().getProperty(
                SecurityConstants.TOKEN_STORE_CACHE_INSTANCE
            );
        assertNotNull(tokenStore);
        // We expect 1 token
        assertEquals(1, tokenStore.getTokenIdentifiers().size());
        
        ((java.io.Closeable)port).close();
        ((java.io.Closeable)port2).close();
        bus.shutdown(true);
    }
    
}
