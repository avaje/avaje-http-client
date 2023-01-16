package org.example.github;

import org.example.github.httpclient.Simple$HttpClient;

import io.avaje.http.client.HttpApiProvider;
import io.avaje.http.client.HttpClient;

public class SimpleProvider implements HttpApiProvider<Simple> {

  @Override
  public Class<Simple> type() {
    return Simple.class;
  }

  @Override
  public Simple provide(HttpClient client) {
    return new Simple$HttpClient(client);
  }
}
