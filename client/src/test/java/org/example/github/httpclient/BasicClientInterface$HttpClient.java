package org.example.github.httpclient;

import io.avaje.http.client.HttpClientContext;
import io.avaje.http.client.HttpException;
import org.example.github.BasicClientInterface;
import org.example.github.Repo;

import java.util.Collections;
import java.util.List;

/**
 * Placeholder for code generated by avaje-http-client-generator.
 */
public class BasicClientInterface$HttpClient implements BasicClientInterface {

  public BasicClientInterface$HttpClient(HttpClientContext context) {

  }
  @Override
  public List<Repo> listRepos(String user, String other) throws HttpException {
    return Collections.emptyList();
  }
}