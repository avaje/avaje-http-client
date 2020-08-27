package io.avaje.http.client;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class HttpException extends RuntimeException {

  private final int statusCode;
  private HttpClientContext context;
  private HttpResponse<?> httpResponse;

  public HttpException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public HttpException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public HttpException(int statusCode, Throwable cause) {
    super(cause);
    this.statusCode = statusCode;
  }

  HttpException(HttpResponse<?> httpResponse, HttpClientContext context) {
    super();
    this.httpResponse = httpResponse;
    this.statusCode = httpResponse.statusCode();
    this.context = context;
  }

  HttpException(HttpClientContext context, HttpResponse<byte[]> httpResponse) {
    super();
    this.httpResponse = httpResponse;
    this.statusCode = httpResponse.statusCode();
    this.context = context;
  }

  /**
   * Return the response body content as a bean
   *
   * @param cls The type of bean to convert the response to
   * @return The response as a bean
   */
  @SuppressWarnings("unchecked")
  public <T> T bean(Class<T> cls) {
    final BodyContent body = context.readContent((HttpResponse<byte[]>) httpResponse);
    return context.converters().beanReader(cls).read(body);
  }

  /**
   * Return the response body content as a UTF8 string.
   */
  @SuppressWarnings("unchecked")
  public String bodyAsString() {
    final BodyContent body = context.readContent((HttpResponse<byte[]>) httpResponse);
    return new String(body.content(), StandardCharsets.UTF_8);
  }

  /**
   * Return the response body content as raw bytes.
   */
  @SuppressWarnings("unchecked")
  public byte[] bodyAsBytes() {
    final BodyContent body = context.readContent((HttpResponse<byte[]>) httpResponse);
    return body.content();
  }

  /**
   * Return the HTTP status code.
   */
  public int getStatusCode() {
    return statusCode;
  }

  /**
   * Return the underlying HttpResponse.
   */
  public HttpResponse<?> getHttpResponse() {
    return httpResponse;
  }

}
