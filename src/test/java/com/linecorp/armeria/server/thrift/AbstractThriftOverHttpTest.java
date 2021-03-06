/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.thrift;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.concurrent.TimeUnit;

import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.AsyncIface;
import com.linecorp.armeria.service.test.thrift.main.SleepService;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.GlobalEventExecutor;

public abstract class AbstractThriftOverHttpTest {

    private static final Server server;

    private static int httpPort;
    private static int httpsPort;

    static {
        final SelfSignedCertificate ssc;
        final ServerBuilder sb = new ServerBuilder();

        try {
            ssc = new SelfSignedCertificate("127.0.0.1");

            sb.port(0, SessionProtocol.HTTP);
            sb.port(0, SessionProtocol.HTTPS);

            final VirtualHostBuilder defaultVirtualHost =
                    new VirtualHostBuilder().sslContext(SessionProtocol.HTTPS, ssc.certificate(), ssc.privateKey());

            defaultVirtualHost.serviceAt("/hello", ThriftService.of(
                    (AsyncIface) (name, resultHandler) ->
                            resultHandler.onComplete("Hello, " + name + '!')).decorate(LoggingService::new));

            defaultVirtualHost.serviceAt("/sleep", ThriftService.of(
                    (SleepService.AsyncIface) (milliseconds, resultHandler) -> {
                        // FIXME: Provide a way to access the current executor.
                        GlobalEventExecutor.INSTANCE.schedule(
                                () -> resultHandler.onComplete(milliseconds),
                                milliseconds, TimeUnit.MILLISECONDS);
                    }).decorate(LoggingService::new));

            sb.defaultVirtualHost(defaultVirtualHost.build());
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().sync();

        httpPort = server.activePorts().values().stream()
                .filter(p -> p.protocol() == SessionProtocol.HTTP).findAny().get().localAddress().getPort();
        httpsPort = server.activePorts().values().stream()
                .filter(p -> p.protocol() == SessionProtocol.HTTPS).findAny().get().localAddress().getPort();
    }

    @AfterClass
    public static void destroy() throws Exception {
        server.stop();
    }

    @Test
    public void testHttpInvocation() throws Exception {
        try (TTransport transport = newTransport("http", "/hello")) {
            HelloService.Client client =
                    new HelloService.Client.Factory().getClient(ThriftProtocolFactories.BINARY.getProtocol(transport));
            String res = client.hello("Trustin");
            assertThat(res, is("Hello, Trustin!"));
        }
    }

    @Test
    public void testHttpsInvocation() throws Exception {
        try (TTransport transport = newTransport("https", "/hello")) {
            HelloService.Client client =
                    new HelloService.Client.Factory().getClient(ThriftProtocolFactories.BINARY.getProtocol(transport));
            String res = client.hello("Trustin");
            assertThat(res, is("Hello, Trustin!"));
        }
    }

    protected final TTransport newTransport(String scheme, String path) throws TTransportException {
        return newTransport(newUri(scheme, path));
    }

    protected static String newUri(String scheme, String path) {
        switch (scheme) {
        case "http":
            return scheme + "://127.0.0.1:" + httpPort + path;
        case "https":
            return scheme + "://127.0.0.1:" + httpsPort + path;
        }

        throw new Error();
    }

    protected abstract TTransport newTransport(String uri) throws TTransportException;
}
