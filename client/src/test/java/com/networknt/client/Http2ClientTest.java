/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.networknt.client;

import com.networknt.client.circuitbreaker.CircuitBreaker;
import com.networknt.client.http.Http2ClientConnectionPool;
import com.networknt.client.simplepool.SimpleConnectionHolder;
import com.networknt.config.Config;
import com.networknt.httpstring.HttpStringConstants;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.client.*;
import io.undertow.io.Receiver;
import io.undertow.io.Sender;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.*;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.*;
import org.xnio.ssl.XnioSsl;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class Http2ClientTest extends Http2ClientBase {
    static final Logger logger = LoggerFactory.getLogger(Http2ClientTest.class);
    public static final String SLOW = "/slow";
    static Undertow server = null;
    static SSLContext sslContext;
    private static final String message = "Hello World!";
    public static final String MESSAGE = "/message";
    public static final String SLOW_MESSAGE = "/slowMessage";
    public static final String POST = "/post";
    public static final String FORM = "/form";
    public static final String TOKEN = "/oauth2/token";
    public static final String API = "/api";
    public static final String KEY = "/oauth2/key";

    private static final String SERVER_KEY_STORE = "server.keystore";
    private static final String SERVER_TRUST_STORE = "server.truststore";
    private static final String CLIENT_KEY_STORE = "client.keystore";
    private static final String CLIENT_TRUST_STORE = "client.truststore";
    private static final char[] STORE_PASSWORD = "password".toCharArray();

    private static XnioWorker worker;

    private static final URI ADDRESS;

    public static final String CONFIG_NAME = "client";
    static ClientConfig config;

    static {
        try {
            ADDRESS = new URI("http://localhost:7777");
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static int slowCount;

    static void sendMessage(final HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, message.length() + "");
        final Sender sender = exchange.getResponseSender();
        sender.send(message);
    }

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Before
    public void setUp() {
        slowCount = 0;
    }

    @BeforeClass
    public static void beforeClass() throws IOException {
        config = ClientConfig.get(CONFIG_NAME);
        // Create xnio worker
        final Xnio xnio = Xnio.getInstance();
        final XnioWorker xnioWorker = xnio.createWorker(null, Http2Client.DEFAULT_OPTIONS);
        worker = xnioWorker;

        if(server == null) {
            System.out.println("starting server");
            Undertow.Builder builder = Undertow.builder();

            sslContext = createSSLContext(loadKeyStore(SERVER_KEY_STORE), loadKeyStore(SERVER_TRUST_STORE), false);
            builder.addHttpsListener(7778, "localhost", sslContext);
            builder.addHttpListener(7777, "localhost");

            builder.setServerOption(UndertowOptions.ENABLE_HTTP2, true);


            server = builder
                    .setBufferSize(1024 * 16)
                    .setIoThreads(Runtime.getRuntime().availableProcessors() * 2) //this seems slightly faster in some configurations
                    .setSocketOption(Options.BACKLOG, 10000)
                    .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, false) //don't send a keep-alive header for HTTP/1.1 requests, as it is not required
                    .setServerOption(UndertowOptions.ALWAYS_SET_DATE, true)
                    .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, false)
                    .setHandler(new PathHandler()
                            .addExactPath(MESSAGE, exchange -> {
                                sendMessage(exchange);
                            })
                            .addExactPath(SLOW_MESSAGE, exchange -> {
                                Thread.sleep(20);
                                sendMessage(exchange);
                            })
                            .addExactPath(KEY, exchange -> sendMessage(exchange))
                            .addExactPath(API, (exchange) -> {
                                boolean hasScopeToken = exchange.getRequestHeaders().contains(HttpStringConstants.SCOPE_TOKEN);
                                Assert.assertTrue(hasScopeToken);
                                String scopeToken = exchange.getRequestHeaders().get(HttpStringConstants.SCOPE_TOKEN, 0);
                                boolean expired = isTokenExpired(scopeToken);
                                Assert.assertFalse(expired);
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                exchange.getResponseSender().send(ByteBuffer.wrap(
                                        Config.getInstance().getMapper().writeValueAsBytes(
                                                Collections.singletonMap("message", "OK!"))));

                            })
                            .addExactPath(FORM, exchange -> exchange.getRequestReceiver().receiveFullString(new Receiver.FullStringCallback() {
                                @Override
                                public void handle(HttpServerExchange exchange, String message) {
                                    exchange.getResponseSender().send(message);
                                }
                            }))
                            .addExactPath(TOKEN, exchange -> exchange.getRequestReceiver().receiveFullString(new Receiver.FullStringCallback() {
                                @Override
                                public void handle(HttpServerExchange exchange, String message) {
                                    try {
                                        int sleepTime = randInt(1, 3) * 1000;
                                        if(sleepTime >= 2000) {
                                            sleepTime = 3000;
                                        } else {
                                            sleepTime = 1000;
                                        }
                                        Thread.sleep(sleepTime);
                                        // create a token that expired in 5 seconds.
                                        Map<String, Object> map = new HashMap<>();
                                        String token = getJwt(5);
                                        map.put("access_token", token);
                                        map.put("token_type", "Bearer");
                                        map.put("expires_in", 5);
                                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                        exchange.getResponseSender().send(ByteBuffer.wrap(
                                                Config.getInstance().getMapper().writeValueAsBytes(map)));
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }))
                            .addExactPath(POST, exchange -> exchange.getRequestReceiver().receiveFullString(new Receiver.FullStringCallback() {
                                @Override
                                public void handle(HttpServerExchange exchange, String message) {
                                    exchange.getResponseSender().send(message);
                                }
                            }))
                            .addExactPath(SLOW, exchange -> exchange.getRequestReceiver().receiveFullString((exchange2, message) -> {
                                try {
                                    if (slowCount < 2) {
                                        Thread.sleep(4000);
                                    }
                                } catch (InterruptedException e) {
                                }
                                exchange2.getResponseSender().send(message);
                            })))
                    .setWorkerThreads(200)
                    .build();

            server.start();
        }
    }

    @AfterClass
    public static void afterClass() {
        worker.shutdown();
        if(server != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            server.stop();
            System.out.println("The server is stopped.");
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }

    }

    @Test
    public void testAddNullToken() {
        final Http2Client client = Http2Client.getInstance();
        final ClientRequest request = new ClientRequest().setMethod(Methods.POST).setPath(POST);
        client.addAuthToken(request, null);
        Assert.assertNull(request.getRequestHeaders().getFirst(Headers.AUTHORIZATION));
    }

    @Test
    public void testAddToken() {
        final Http2Client client = Http2Client.getInstance();
        final ClientRequest request = new ClientRequest().setMethod(Methods.POST).setPath(POST);
        client.addAuthToken(request, "token");
        Assert.assertEquals("Bearer token", request.getRequestHeaders().getFirst(Headers.AUTHORIZATION));
    }

    @Test(expected = TimeoutException.class)
    public void shouldThrowTimeoutExceptionIfTimeoutHasBeenReached() throws URISyntaxException, ExecutionException, InterruptedException, TimeoutException {
        Http2ClientConnectionPool.getInstance().clear();
        Http2Client client = createClient();

        final ClientRequest request = new ClientRequest().setMethod(Methods.POST).setPath(SLOW);
        request.getRequestHeaders().put(Headers.HOST, "localhost");
        request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, "chunked");

        client.getRequestService(new URI("https://localhost:7778"), request, Optional.empty()).call();
    }

    @Test
    public void shouldCircuitBeOpenIfThresholdIsReached() throws URISyntaxException, ExecutionException, InterruptedException, TimeoutException {
        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("circuit is opened.");

        Http2ClientConnectionPool.getInstance().clear();
        Http2Client client = createClient();

        final ClientRequest request = new ClientRequest().setMethod(Methods.POST).setPath(SLOW);
        request.getRequestHeaders().put(Headers.HOST, "localhost");
        request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, "chunked");

        CircuitBreaker service = client.getRequestService(new URI("https://localhost:7778"), request, Optional.empty());
        try {
            service.call();
            fail();
        } catch (TimeoutException e) {
            try {
                service.call();
                fail();
            } catch (TimeoutException ex) {
                service.call();
            }
        }
    }


    @Test
    public void shouldCircuitBeCloseIfResetTimeoutIsReached() throws URISyntaxException, ExecutionException, InterruptedException, TimeoutException {
        Http2ClientConnectionPool.getInstance().clear();
        Http2Client client = createClient();

        final ClientRequest request = new ClientRequest().setMethod(Methods.POST).setPath(SLOW);
        request.getRequestHeaders().put(Headers.HOST, "localhost");
        request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, "chunked");

        CircuitBreaker service = client.getRequestService(new URI("https://localhost:7778"), request, Optional.empty());
        try {
            service.call();
            fail();
        } catch (TimeoutException e) {
            slowCount++;
            try {
                service.call();
                fail();
            } catch (TimeoutException ex) {
                slowCount++;
                Thread.sleep(7100);
                service.call();
            }
        }
    }

    @Test
    public void shouldCallRequestAsync() throws URISyntaxException, ExecutionException, InterruptedException, TimeoutException {
        Http2ClientConnectionPool.getInstance().clear();
        Http2Client client = createClient();

        final ClientRequest request = new ClientRequest().setMethod(Methods.POST).setPath(POST);
        request.getRequestHeaders().put(Headers.HOST, "localhost");
        request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, "chunked");

        ClientResponse clientResponse = client.getRequestService(new URI("https://localhost:7778"), request, Optional.empty()).call();
        Assert.assertEquals(200, clientResponse.getResponseCode());
    }

    @Test
    public void testSingleHttp2PostSsl() throws Exception {
        final Http2Client client = createClient();
        final String postMessage = "This is a post request";

        final List<String> responses = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        SSLContext context = Http2Client.createSSLContext();
        XnioSsl ssl = new UndertowXnioSsl(worker.getXnio(), OptionMap.EMPTY, Http2Client.BUFFER_POOL, context);

        final ClientConnection connection = client.connect(new URI("https://localhost:7778"), worker, ssl, Http2Client.BUFFER_POOL, OptionMap.create(UndertowOptions.ENABLE_HTTP2, true)).get();
        try {
            connection.getIoThread().execute(new Runnable() {
                @Override
                public void run() {
                    final ClientRequest request = new ClientRequest().setMethod(Methods.POST).setPath(POST);
                    request.getRequestHeaders().put(Headers.HOST, "localhost");
                    request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, "chunked");
                    connection.sendRequest(request, new ClientCallback<ClientExchange>() {
                        @Override
                        public void completed(ClientExchange result) {
                            new StringWriteChannelListener(postMessage).setup(result.getRequestChannel());
                            result.setResponseListener(new ClientCallback<ClientExchange>() {
                                @Override
                                public void completed(ClientExchange result) {
                                    new StringReadChannelListener(Http2Client.BUFFER_POOL) {

                                        @Override
                                        protected void stringDone(String string) {
                                            responses.add(string);
                                            latch.countDown();
                                        }

                                        @Override
                                        protected void error(IOException e) {
                                            e.printStackTrace();
                                            latch.countDown();
                                        }
                                    }.setup(result.getResponseChannel());
                                }

                                @Override
                                public void failed(IOException e) {
                                    e.printStackTrace();
                                    latch.countDown();
                                }
                            });
                        }

                        @Override
                        public void failed(IOException e) {
                            e.printStackTrace();
                            latch.countDown();
                        }
                    });
                }
            });

            latch.await(10, TimeUnit.SECONDS);

            Assert.assertEquals(1, responses.size());
            for (final String response : responses) {
                Assert.assertEquals(postMessage, response);
            }
        } finally {
            IoUtils.safeClose(connection);
        }
    }

    @Test
    public void testSingleHttp2FormSsl() throws Exception {
        //
        final Http2Client client = createClient();
        Map<String, String> params = new HashMap<>();
        params.put("key1", "value1");
        params.put("key2", "value2");

        final String postMessage = Http2Client.getFormDataString(params);

        final List<String> responses = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        SSLContext context = Http2Client.createSSLContext();
        XnioSsl ssl = new UndertowXnioSsl(worker.getXnio(), OptionMap.EMPTY, Http2Client.BUFFER_POOL, context);

        final ClientConnection connection = client.connect(new URI("https://localhost:7778"), worker, ssl, Http2Client.BUFFER_POOL, OptionMap.create(UndertowOptions.ENABLE_HTTP2, true)).get();
        try {
            connection.getIoThread().execute(new Runnable() {
                @Override
                public void run() {
                    final ClientRequest request = new ClientRequest().setMethod(Methods.POST).setPath(FORM);
                    request.getRequestHeaders().put(Headers.HOST, "localhost");
                    request.getRequestHeaders().put(Headers.TRANSFER_ENCODING, "chunked");
                    request.getRequestHeaders().put(Headers.CONTENT_TYPE, "application/x-www-form-urlencoded");
                    connection.sendRequest(request, new ClientCallback<ClientExchange>() {
                        @Override
                        public void completed(ClientExchange result) {
                            new StringWriteChannelListener(postMessage).setup(result.getRequestChannel());
                            result.setResponseListener(new ClientCallback<ClientExchange>() {
                                @Override
                                public void completed(ClientExchange result) {
                                    new StringReadChannelListener(Http2Client.BUFFER_POOL) {

                                        @Override
                                        protected void stringDone(String string) {
                                            System.out.println("string = " + string);
                                            responses.add(string);
                                            latch.countDown();
                                        }

                                        @Override
                                        protected void error(IOException e) {
                                            e.printStackTrace();
                                            latch.countDown();
                                        }
                                    }.setup(result.getResponseChannel());
                                }

                                @Override
                                public void failed(IOException e) {
                                    e.printStackTrace();
                                    latch.countDown();
                                }
                            });
                        }

                        @Override
                        public void failed(IOException e) {
                            e.printStackTrace();
                            latch.countDown();
                        }
                    });
                }
            });

            latch.await(10, TimeUnit.SECONDS);

            Assert.assertEquals(1, responses.size());
            for (final String response : responses) {
                Assert.assertEquals(postMessage, response);
            }
        } finally {
            IoUtils.safeClose(connection);
        }
    }

    @Test
    public void testConnectionClose() throws Exception {
        //
        final Http2Client client = createClient();

        final CountDownLatch latch = new CountDownLatch(1);
        final ClientConnection connection = client.connect(ADDRESS, worker, Http2Client.BUFFER_POOL, OptionMap.EMPTY).get();
        try {
            ClientRequest request = new ClientRequest().setPath(MESSAGE).setMethod(Methods.GET);
            request.getRequestHeaders().put(Headers.HOST, "localhost");
            final AtomicReference<ClientResponse> reference = new AtomicReference<>();
            request.getRequestHeaders().add(Headers.CONNECTION, Headers.CLOSE.toString());
            connection.sendRequest(request, client.createClientCallback(reference, latch));
            latch.await();
            final ClientResponse response = reference.get();
            Assert.assertEquals(message, response.getAttachment(Http2Client.RESPONSE_BODY));
            Assert.assertEquals(false, connection.isOpen());
        } finally {
            IoUtils.safeClose(connection);
        }

    }

    @Test
    public void testResponseTime() throws Exception {
        //
        final Http2Client client = createClient();

        final CountDownLatch latch = new CountDownLatch(1);
        final ClientConnection connection = client.connect(ADDRESS, worker, Http2Client.BUFFER_POOL, OptionMap.EMPTY).get();
        try {
            ClientRequest request = new ClientRequest().setPath(MESSAGE).setMethod(Methods.GET);
            request.getRequestHeaders().put(Headers.HOST, "localhost");
            final AtomicReference<AsyncResult<AsyncResponse>> reference = new AtomicReference<>();
            request.getRequestHeaders().add(Headers.CONNECTION, Headers.CLOSE.toString());
            connection.sendRequest(request, client.createFullCallback(reference, latch));
            latch.await();
            final AsyncResult<AsyncResponse> ar = reference.get();
            if(ar.succeeded()) {
                Assert.assertEquals(message, ar.result().getResponseBody());
                Assert.assertTrue(ar.result().getResponseTime() > 0);
                System.out.println("responseTime = " + ar.result().getResponseTime());
            } else {
                ar.cause().printStackTrace();
            }
            Assert.assertEquals(false, connection.isOpen());
        } finally {
            IoUtils.safeClose(connection);
        }
    }


    @Test
    @Ignore
    public void testSingleAsych() throws Exception {
        callApiAsync();
    }

    @Test
    public void testGetFormDataString() throws UnsupportedEncodingException {
        // This is to reproduce and fix #172
        Map<String, String> params = new HashMap<>();
        params.put("scope", "a b c d");
        String s = Http2Client.getFormDataString(params);
        Assert.assertEquals("scope=a%20b%20c%20d", s);
    }

    public String callApiAsync() throws Exception {
        final Http2Client client = createClient();
        final CountDownLatch latch = new CountDownLatch(1);
        final ClientConnection connection = client.connect(ADDRESS, worker, Http2Client.BUFFER_POOL, OptionMap.EMPTY).get();
        final AtomicReference<ClientResponse> reference = new AtomicReference<>();
        try {
            ClientRequest request = new ClientRequest().setPath(API).setMethod(Methods.GET);
            request.getRequestHeaders().put(Headers.HOST, "localhost");
            client.populateHeader(request, "Bearer token", "cid", "tid");
            request.getRequestHeaders().add(Headers.CONNECTION, Headers.CLOSE.toString());
            connection.sendRequest(request, client.createClientCallback(reference, latch));
            latch.await();
            final ClientResponse response = reference.get();
            Assert.assertEquals("{\"message\":\"OK!\"}", response.getAttachment(Http2Client.RESPONSE_BODY));
            Assert.assertEquals(false, connection.isOpen());
        } finally {
            IoUtils.safeClose(connection);
        }
        return reference.get().getAttachment(Http2Client.RESPONSE_BODY);
    }

    @Test
    public void callApiWithHttpConnectionPoolAsync() throws Exception {
        Http2ClientConnectionPool.getInstance().clear();

        int asyncRequestNumber = 100;
        final Http2Client client = createClient();
        AtomicInteger countComplete = new AtomicInteger(0);
        ClientRequest request = new ClientRequest().setPath(SLOW_MESSAGE).setMethod(Methods.GET);
        request.getRequestHeaders().put(Headers.HOST, "localhost");
        config.setRequestEnableHttp2(false);
        CountDownLatch latch = new CountDownLatch(asyncRequestNumber);
        for (int i = 0; i < asyncRequestNumber; i++) {
            client.callService(ADDRESS, request, Optional.empty()).thenAcceptAsync(clientResponse -> {
                Assert.assertEquals(clientResponse.getAttachment(Http2Client.RESPONSE_BODY), "Hello World!");
                countComplete.getAndIncrement();
                latch.countDown();
            });
            Thread.sleep(5);
        }
        latch.await(5, TimeUnit.SECONDS);

        Assert.assertTrue(Http2ClientConnectionPool.getInstance().numberOfConnections() > 1);

        System.out.println("Number of connections: " + Http2ClientConnectionPool.getInstance().numberOfConnections());
        System.out.println("Completed: " + countComplete.get());

        // Reset to default
        config.setRequestEnableHttp2(true);
    }

    @Test
    public void callApiWithHttp2ConnectionPoolAsync() throws Exception {
        Http2ClientConnectionPool.getInstance().clear();

        int asyncRequestNumber = 100;
        final Http2Client client = createClient();
        AtomicInteger countComplete = new AtomicInteger(0);
        ClientRequest request = new ClientRequest().setPath(SLOW_MESSAGE).setMethod(Methods.GET);
        request.getRequestHeaders().put(Headers.HOST, "localhost");
        CountDownLatch latch = new CountDownLatch(asyncRequestNumber);
        for (int i = 0; i < asyncRequestNumber; i++) {
            client.callService(new URI("https://localhost:7778"), request, Optional.empty()).thenAcceptAsync(clientResponse -> {
                Assert.assertEquals(clientResponse.getAttachment(Http2Client.RESPONSE_BODY), "Hello World!");
                countComplete.getAndIncrement();
                latch.countDown();
            });
            Thread.sleep(5);
        }
        latch.await(5, TimeUnit.SECONDS);

        Assert.assertTrue(Http2ClientConnectionPool.getInstance().numberOfConnections() == 1);

        System.out.println("Number of connections: " + Http2ClientConnectionPool.getInstance().numberOfConnections());
        System.out.println("Completed: " + countComplete.get());
    }

    @Test
    public void testAsyncAboutToExpire() throws InterruptedException, ExecutionException {
        for(int i = 0; i < 1; i++) {
            callApiAsyncMultiThread(4);
            logger.info("called times: " + i);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Test
    public void testAsyncExpired() throws InterruptedException, ExecutionException {
        for(int i = 0; i < 1; i++) {
            callApiAsyncMultiThread(4);
            logger.info("called times: " + i);
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Test
    public void testMixed() throws InterruptedException, ExecutionException {
        for(int i = 0; i < 1; i++) {
            callApiAsyncMultiThread(4
            );
            logger.info("called times: " + i);
            try {
                int sleepTime = randInt(1, 6) * 1000;
                if (sleepTime > 3000) {
                    sleepTime = 6000;
                } else {
                    sleepTime = 1000;
                }
                Thread.sleep(sleepTime);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void callApiAsyncMultiThread(final int threadCount) throws InterruptedException, ExecutionException {
        Callable<String> task = this::callApiAsync;
        List<Callable<String>> tasks = Collections.nCopies(threadCount, task);
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = executorService.invokeAll(tasks);
        List<String> resultList = new ArrayList<>(futures.size());
        for (Future<String> future : futures) {
            resultList.add(future.get());
        }
        System.out.println("resultList = " + resultList);
    }

    /*
    private ClientCallback<ClientExchange> createClientCallback(final AtomicReference<ClientResponse> reference, final CountDownLatch latch) {
        return new ClientCallback<ClientExchange>() {
            @Override
            public void completed(ClientExchange result) {
                result.setResponseListener(new ClientCallback<ClientExchange>() {
                    @Override
                    public void completed(final ClientExchange result) {
                        reference.set(result.getResponse());
                        new StringReadChannelListener(result.getConnection().getBufferPool()) {

                            @Override
                            protected void stringDone(String string) {
                                result.getResponse().putAttachment(Http2Client.RESPONSE_BODY, string);
                                latch.countDown();
                            }

                            @Override
                            protected void error(IOException e) {
                                e.printStackTrace();

                                latch.countDown();
                            }
                        }.setup(result.getResponseChannel());
                    }

                    @Override
                    public void failed(IOException e) {
                        e.printStackTrace();

                        latch.countDown();
                    }
                });
                try {
                    result.getRequestChannel().shutdownWrites();
                    if(!result.getRequestChannel().flush()) {
                        result.getRequestChannel().getWriteSetter().set(ChannelListeners.<StreamSinkChannel>flushingChannelListener(null, null));
                        result.getRequestChannel().resumeWrites();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    latch.countDown();
                }
            }

            @Override
            public void failed(IOException e) {
                e.printStackTrace();
                latch.countDown();
            }
        };
    }
    */

    private static int randInt(int min, int max) {
        Random rand = new Random();
        return rand.nextInt((max-min) + 1) + min;
    }



    @Test
    public void server_identity_check_positive_case() throws Exception{
        final Http2Client client = createClient();
        SSLContext context = Http2Client.createSSLContext();
        XnioSsl ssl = new UndertowXnioSsl(worker.getXnio(), OptionMap.EMPTY, Http2Client.BUFFER_POOL, context);

        final ClientConnection connection = client.connect(new URI("https://localhost:7778"), worker, ssl, Http2Client.BUFFER_POOL, OptionMap.create(UndertowOptions.ENABLE_HTTP2, true)).get();
        
        assertTrue(connection.isOpen());
        
        IoUtils.safeClose(connection);
    }

    // For these three tests, the behaviour is different between jdk9 and jdk10/11/12
    // For jdk8 and 9, ClosedChannelException will be thrown.
    // For jdk10 and up, not exception is thrown but the connection is not open.
    @Ignore
    @Test(expected=ClosedChannelException.class)
    public void server_identity_check_negative_case() throws Exception{
        final Http2Client client = createClient();
        SSLContext context = Http2Client.createSSLContext();
        XnioSsl ssl = new UndertowXnioSsl(worker.getXnio(), OptionMap.EMPTY, Http2Client.BUFFER_POOL, context);

        final ClientConnection connection = client.connect(new URI("https://localhost:7778"), worker, ssl, Http2Client.BUFFER_POOL, OptionMap.create(UndertowOptions.ENABLE_HTTP2, true)).get();
        //should not be reached
        //assertFalse(connection.isOpen());
        fail();
    }
    @Ignore
    @Test(expected=ClosedChannelException.class)
    public void standard_https_hostname_check_kicks_in_if_trustednames_are_empty() throws Exception{
        final Http2Client client = createClient();
        SSLContext context = Http2Client.createSSLContext();
        XnioSsl ssl = new UndertowXnioSsl(worker.getXnio(), OptionMap.EMPTY, Http2Client.BUFFER_POOL, context);

        final ClientConnection connection = client.connect(new URI("https://127.0.0.1:7778"), worker, ssl, Http2Client.BUFFER_POOL, OptionMap.create(UndertowOptions.ENABLE_HTTP2, true)).get();
        //should not be reached
        //assertFalse(connection.isOpen());
        fail();
    }
    @Ignore
    @Test(expected=ClosedChannelException.class)
    public void standard_https_hostname_check_kicks_in_if_trustednames_are_not_used_or_not_provided() throws Exception{
        final Http2Client client = createClient();
        SSLContext context = Http2Client.createSSLContext();
        XnioSsl ssl = new UndertowXnioSsl(worker.getXnio(), OptionMap.EMPTY, Http2Client.BUFFER_POOL, context);

        final ClientConnection connection = client.connect(new URI("https://127.0.0.1:7778"), worker, ssl, Http2Client.BUFFER_POOL, OptionMap.create(UndertowOptions.ENABLE_HTTP2, true)).get();
        //should not be reached
        //assertFalse(connection.isOpen());
        fail();
    }

    @Test
    public void default_group_key_is_used_in_Http2Client_SSL() throws Exception{
        final Http2Client client = createClient();
        final ClientConnection connection = client.connect(new URI("https://localhost:7778"), worker, Http2Client.SSL, Http2Client.BUFFER_POOL, OptionMap.create(UndertowOptions.ENABLE_HTTP2, true)).get();

        assertTrue(connection.isOpen());

        IoUtils.safeClose(connection);
    }

    @Test
    public void simple_pool_return_null_token_if_api_is_not_available() {
        final Http2Client client = Http2Client.getInstance();
        SimpleConnectionHolder.ConnectionToken connectionToken = null;
        try {
            connectionToken = client.borrow(new URI("https://localhost:27778"), Http2Client.WORKER, client.getDefaultXnioSsl(), Http2Client.BUFFER_POOL, OptionMap.create(UndertowOptions.ENABLE_HTTP2, true));
            assertNull(connectionToken);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            client.restore(connectionToken);
        }
    }
}
