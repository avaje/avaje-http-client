package org.example.github;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.avaje.http.client.HttpApi;
import io.avaje.http.client.HttpClientContext;
import io.avaje.http.client.JacksonBodyAdapter;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class GithubTest {

  @Test @Disabled
  void test() {

    JacksonBodyAdapter bodyAdapter = new JacksonBodyAdapter(new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

    final HttpClientContext clientContext = HttpClientContext.newBuilder()
      .withBaseUrl("https://api.github.com")
      .withBodyAdapter(bodyAdapter)
      .build();

    final Simple simple = HttpApi.provide(Simple.class, clientContext);

    final List<Repo> repos = simple.listRepos("rbygrave", "junk");
    assertThat(repos).isNotEmpty();
  }
}