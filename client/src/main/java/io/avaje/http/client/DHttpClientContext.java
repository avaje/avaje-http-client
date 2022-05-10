package io.avaje.http.client;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

final class DHttpClientContext implements HttpClientContext {

  /**
   * HTTP Authorization header.
   */
  static final String AUTHORIZATION = "Authorization";
  private static final String BEARER = "Bearer ";

  private final HttpClient httpClient;
  private final String baseUrl;
  private final Duration requestTimeout;
  private final BodyAdapter bodyAdapter;
  private final RequestListener requestListener;
  private final RequestIntercept requestIntercept;
  private final RetryHandler retryHandler;
  private final boolean withAuthToken;
  private final AuthTokenProvider authTokenProvider;
  private final AtomicReference<AuthToken> tokenRef = new AtomicReference<>();
  private final Executor executor;
  private final AtomicLong activeAsync = new AtomicLong();
  private int loggingMaxBody = 1_000;

  private final LongAdder metricResTotal = new LongAdder();
  private final LongAdder metricResError = new LongAdder();
  private final LongAdder metricResBytes = new LongAdder();
  private final LongAdder metricResMicros = new LongAdder();
  private final LongAccumulator metricResMaxMicros = new LongAccumulator(Math::max, 0);

  DHttpClientContext(HttpClient httpClient, String baseUrl, Duration requestTimeout, BodyAdapter bodyAdapter, RetryHandler retryHandler, RequestListener requestListener, AuthTokenProvider authTokenProvider, RequestIntercept intercept, Executor executor) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
    this.requestTimeout = requestTimeout;
    this.bodyAdapter = bodyAdapter;
    this.retryHandler = retryHandler;
    this.requestListener = requestListener;
    this.authTokenProvider = authTokenProvider;
    this.withAuthToken = authTokenProvider != null;
    this.requestIntercept = intercept;
    this.executor = executor;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T create(Class<T> clientInterface) {
    if (!clientInterface.isInterface()) {
      throw new IllegalArgumentException("API declarations must be interfaces.");
    }
    HttpApiProvider<T> apiProvider = DHttpApi.get(clientInterface);
    if (apiProvider != null) {
      return apiProvider.provide(this);
    }
    String implClassName = clientImplementationClassName(clientInterface);
    try {
      Class<?> serviceClass = Class.forName(implClassName);
      Constructor<?> constructor = serviceClass.getConstructor(HttpClientContext.class);
      Object service = constructor.newInstance(this);
      return (T) service;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create http client service " + implClassName, e);
    }
  }

  private <T> String clientImplementationClassName(Class<T> clientInterface) {
    String packageName = clientInterface.getPackageName();
    String simpleName = clientInterface.getSimpleName();
    return packageName + ".httpclient." + simpleName + "$HttpClient";
  }

  @Override
  public HttpClientRequest request() {
    return retryHandler == null
      ? new DHttpClientRequest(this, requestTimeout)
      : new DHttpClientRequestWithRetry(this, requestTimeout, retryHandler);
  }

  @Override
  public BodyAdapter converters() {
    return bodyAdapter;
  }

  @Override
  public UrlBuilder url() {
    return new UrlBuilder(baseUrl);
  }

  @Override
  public HttpClient httpClient() {
    return httpClient;
  }

  @Override
  public Metrics metrics() {
    return metrics(false);
  }

  @Override
  public Metrics metrics(boolean reset) {
    if (reset) {
      return new DMetrics(metricResTotal.sumThenReset(), metricResError.sumThenReset(), metricResBytes.sumThenReset(), metricResMicros.sumThenReset(), metricResMaxMicros.getThenReset());
    } else {
      return new DMetrics(metricResTotal.sum(), metricResError.sum(), metricResBytes.sum(), metricResMicros.sum(), metricResMaxMicros.get());
    }
  }

  void metricsString(int stringBody) {
    metricResBytes.add(stringBody);
  }

  static final class DMetrics implements Metrics {

    private final long totalCount;
    private final long errorCount;
    private final long responseBytes;
    private final long totalMicros;
    private final long maxMicros;

    DMetrics(long totalCount, long errorCount, long responseBytes, long totalMicros, long maxMicros) {
      this.totalCount = totalCount;
      this.errorCount = errorCount;
      this.responseBytes = responseBytes;
      this.totalMicros = totalMicros;
      this.maxMicros = maxMicros;
    }

    @Override
    public String toString() {
      return "totalCount:" + totalCount + " errorCount:" + errorCount + " responseBytes:" + responseBytes + " totalMicros:" + totalMicros + " avgMicros:" + avgMicros()+ " maxMicros:" + maxMicros;
    }

    @Override
    public long totalCount() {
      return totalCount;
    }

    @Override
    public long errorCount() {
      return errorCount;
    }

    @Override
    public long responseBytes() {
      return responseBytes;
    }

    @Override
    public long totalMicros() {
      return totalMicros;
    }

    @Override
    public long maxMicros() {
      return maxMicros;
    }

    @Override
    public long avgMicros() {
      return totalCount == 0 ? 0 : totalMicros / totalCount;
    }
  }

  @Override
  public void checkResponse(HttpResponse<?> response) {
    if (response.statusCode() >= 300) {
      throw new HttpException(response, this);
    }
  }

  void checkMaybeThrow(HttpResponse<byte[]> response) {
    if (response.statusCode() >= 300) {
      throw new HttpException(this, response);
    }
  }

  @SuppressWarnings("unchecked")
  public BodyContent readErrorContent(boolean responseAsBytes, HttpResponse<?> httpResponse) {
    if (responseAsBytes) {
      return readContent((HttpResponse<byte[]>) httpResponse);
    }
    final String contentType = getContentType(httpResponse);
    final Object body = httpResponse.body();
    if (body instanceof String) {
      return new BodyContent(contentType, ((String) body).getBytes(StandardCharsets.UTF_8));
    }
    String type = (body == null) ? "null" : body.getClass().toString();
    throw new IllegalStateException("Unable to translate response body to bytes? Maybe use HttpResponse directly instead?  Response body type: " + type);
  }

  @Override
  public BodyContent readContent(HttpResponse<byte[]> httpResponse) {
    final byte[] body = httpResponse.body();
    if (body != null && body.length > 0) {
      metricResBytes.add(body.length);
    }
    byte[] bodyBytes = decodeContent(httpResponse);
    final String contentType = getContentType(httpResponse);
    return new BodyContent(contentType, bodyBytes);
  }

  String getContentType(HttpResponse<?> httpResponse) {
    return firstHeader(httpResponse.headers(), "Content-Type", "content-type");
  }

  String getContentEncoding(HttpResponse<?> httpResponse) {
    return firstHeader(httpResponse.headers(), "Content-Encoding", "content-encoding");
  }

  @Override
  public byte[] decodeContent(String encoding, byte[] body) {
    if (encoding.equals("gzip")) {
      return GzipUtil.gzipDecode(body);
    }
    // todo: register decoders with context and use them
    return body;
  }

  public byte[] decodeContent(HttpResponse<byte[]> httpResponse) {
    String encoding = getContentEncoding(httpResponse);
    return encoding == null ? httpResponse.body() : decodeContent(encoding, httpResponse.body());
  }

  String firstHeader(HttpHeaders headers, String... names) {
    final Map<String, List<String>> map = headers.map();
    for (String key : names) {
      final List<String> values = map.get(key);
      if (values != null && !values.isEmpty()) {
        return values.get(0);
      }
    }
    return null;
  }

  <T> HttpResponse<T> send(HttpRequest.Builder requestBuilder, HttpResponse.BodyHandler<T> bodyHandler) {
    try {
      return httpClient.send(requestBuilder.build(), bodyHandler);
    } catch (IOException e) {
      throw new HttpException(499, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HttpException(499, e);
    }
  }

  <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest.Builder requestBuilder, HttpResponse.BodyHandler<T> bodyHandler) {
    activeAsync.incrementAndGet();
    if (executor == null) {
      // defaults to ForkJoinPool.commonPool()
      return httpClient.sendAsync(requestBuilder.build(), bodyHandler);
    } else {
      return httpClient.sendAsync(requestBuilder.build(), bodyHandler).whenCompleteAsync((r, t)-> {}, executor);
    }
  }

  <T> BodyContent write(T bean, String contentType) {
    return bodyAdapter.beanWriter(bean.getClass()).write(bean, contentType);
  }

  <T> BodyReader<T> beanReader(Class<T> cls) {
    return bodyAdapter.beanReader(cls);
  }

  <T> T readBean(Class<T> cls, BodyContent content) {
    return bodyAdapter.beanReader(cls).read(content);
  }

  <T> List<T> readList(Class<T> cls, BodyContent content) {
    return bodyAdapter.listReader(cls).read(content);
  }

  @Override
  public boolean shutdown(long timeout, TimeUnit timeUnit) {
    long timeoutMillis = TimeUnit.MILLISECONDS.convert(timeout, timeUnit);
    if (!waitForActiveAsync(timeoutMillis)) {
      return false;
    }
    if (executor instanceof ExecutorService) {
      ExecutorService es = (ExecutorService)executor;
      es.shutdown();
      try {
        return es.awaitTermination(timeout, timeUnit);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        System.getLogger("io.avaje.http.client").log(System.Logger.Level.WARNING, "Interrupt on shutdown", e);
        return false;
      }
    }
    return true;
  }

  private boolean waitForActiveAsync(long millis) {
    final long until = System.currentTimeMillis() + millis;
    do {
      if (activeAsync.get() <= 0) {
        return true;
      }
      LockSupport.parkNanos(10_000_000);
    } while (System.currentTimeMillis() < until);
    return false;
  }

  void afterResponse(DHttpClientRequest request) {
    metricResTotal.add(1);
    metricResMicros.add(request.responseTimeMicros());
    metricResMaxMicros.accumulate(request.responseTimeMicros());
    if (request.response().statusCode() >= 300) {
      metricResError.add(1);
    }
    if (requestListener != null) {
      requestListener.response(request.listenerEvent());
    }
    if (requestIntercept != null) {
      requestIntercept.afterResponse(request.response(), request);
    }
    if (request.startAsyncNanos > 0) {
      activeAsync.decrementAndGet();
    }
  }

  void beforeRequest(DHttpClientRequest request) {
    if (withAuthToken && !request.isSkipAuthToken()) {
      request.header(AUTHORIZATION, BEARER + authToken());
    }
    if (requestIntercept != null) {
      requestIntercept.beforeRequest(request);
    }
  }

  private String authToken() {
    AuthToken authToken = tokenRef.get();
    if (authToken == null || authToken.isExpired()) {
      authToken = authTokenProvider.obtainToken(request().skipAuthToken());
      tokenRef.set(authToken);
    }
    return authToken.token();
  }

  String maxResponseBody(String body) {
    return body.length() > loggingMaxBody ? body.substring(0, loggingMaxBody) + " <truncated> ..." : body;
  }
}
