package io.avaje.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncExecutorTest extends BaseWebTest {

  final Logger log = LoggerFactory.getLogger(AsyncExecutorTest.class);

  @Test
  void context_shutdown() {

    final HttpClientContext clientContext = HttpClientContext.newBuilder()
      .baseUrl(baseUrl)
      .bodyAdapter(new JacksonBodyAdapter(new ObjectMapper()))
      .executor(Executors.newSingleThreadExecutor())
      .build();

    final CompletableFuture<HttpResponse<Stream<String>>> future = clientContext.request()
      .path("hello").path("stream")
      .GET()
      .async()
      .asLines();

    final AtomicReference<String> threadName = new AtomicReference<>();
    final AtomicBoolean flag = new AtomicBoolean();
    future.whenComplete((hres, throwable) -> {
      flag.set(true);
      threadName.set(Thread.currentThread().getName());
      log.info("processing response");
      LockSupport.parkNanos(600_000_000);
      assertThat(hres.statusCode()).isEqualTo(200);
      List<String> lines = hres.body().collect(Collectors.toList());
      assertThat(lines).hasSize(4);
      assertThat(lines.get(0)).contains("{\"id\":1, \"name\":\"one\"}");
      log.info("processing response complete");
    });

    assertThat(flag).isFalse(); // haven't run the async process yet
    assertThat(clientContext.shutdown(2, TimeUnit.SECONDS)).isTrue();
    assertThat(flag).isTrue();
    assertThat(threadName.get()).endsWith("-thread-1");
  }

}

