// Copyright (c) Microsoft Corporation.
// Licensed under the MIT license.

package com.microsoft.copilot.eclipse.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import org.eclipse.lsp4j.WorkDoneProgressKind;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.microsoft.copilot.eclipse.core.lsp.protocol.ChatProgressValue;
import com.microsoft.copilot.eclipse.core.lsp.protocol.ConversationError;

class ChatRequestRetryTests {
  private final BlockingQueue<String> tokens = new LinkedBlockingQueue<>();
  private final BlockingQueue<CompletableFuture<String>> requests = new LinkedBlockingQueue<>();
  private final List<ChatProgressValue> delivered = new ArrayList<>();

  @AfterEach
  void tearDown() {
    ChatRequestRetry.cancelAll();
  }

  @Test
  void testRetryable_httpAndNetworkFailures_classifiedCorrectly() {
    for (int code : new int[] { 400, 404, 408, 409, 425, 429, 500, 502, 503, 504 }) {
      assertTrue(ChatRequestRetry.isRetryable(code, null, "request failed"), "HTTP " + code);
    }
    for (int code : new int[] { 401, 402, 403, 407, 422 }) {
      assertFalse(ChatRequestRetry.isRetryable(code, null, "request failed"), "HTTP " + code);
    }
    assertTrue(ChatRequestRetry.isRetryable(new CompletionException(new TimeoutException())));
    assertTrue(ChatRequestRetry.isRetryable(new IOException("connection reset")));
    assertTrue(ChatRequestRetry.isRetryable(new ResponseErrorException(
        new ResponseError(-32603, "fetch failed: HTTP 404 from proxy", null))));
    assertFalse(ChatRequestRetry.isRetryable(new CancellationException()));
    assertFalse(ChatRequestRetry.isRetryable(new IllegalArgumentException("invalid input")));
    assertFalse(ChatRequestRetry.isRetryable(400, "model_not_supported", "bad model"));
    assertFalse(ChatRequestRetry.isRetryable(429, "quota_exceeded", "quota exceeded"));
  }

  @Test
  void testExecute_requestFailures_stopsAfterConfiguredRetryCount() {
    AtomicInteger attempts = new AtomicInteger();
    final CompletableFuture<String> result = ChatRequestRetry.execute("failed", token -> {
      attempts.incrementAndGet();
      return CompletableFuture.failedFuture(new TimeoutException("timed out"));
    }, 2, 0);

    ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
    assertTrue(error.getCause() instanceof TimeoutException);
    assertEquals(3, attempts.get());
  }

  @Test
  void testExecute_retriesDisabled_onlyOneAttempt() {
    AtomicInteger attempts = new AtomicInteger();
    final CompletableFuture<String> result = ChatRequestRetry.execute("disabled", token -> {
      attempts.incrementAndGet();
      return CompletableFuture.failedFuture(new IOException("network unavailable"));
    }, 0, 0);
    assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
    assertEquals(1, attempts.get());
  }

  @Test
  void testProgress_failedAttempt_hiddenUntilSuccessfulAttempt() throws Exception {
    final CompletableFuture<String> result = start(2, 0);
    String firstToken = nextToken();
    emit(firstToken, event(WorkDoneProgressKind.begin));
    requests.take().complete("first response");
    assertFalse(result.isDone());
    emit(firstToken, failedEnd(404));
    assertTrue(delivered.isEmpty());

    String secondToken = nextToken();
    assertNotEquals(firstToken, secondToken);
    assertTrue(ChatRequestRetry.interceptProgress(firstToken, failedEnd(500), delivered::add));
    emit(secondToken, event(WorkDoneProgressKind.begin));
    requests.take().complete("success");
    ChatProgressValue report = event(WorkDoneProgressKind.report);
    report.setReply("Hello");
    emit(secondToken, report);
    emit(secondToken, event(WorkDoneProgressKind.end));

    assertEquals("success", result.get(3, TimeUnit.SECONDS));
    assertEquals(List.of(WorkDoneProgressKind.begin, WorkDoneProgressKind.report, WorkDoneProgressKind.end),
        delivered.stream().map(ChatProgressValue::getKind).toList());
  }

  @Test
  void testProgress_retryBudgetExhausted_finalErrorIsDelivered() throws Exception {
    final CompletableFuture<String> result = start(1, 0);
    String firstToken = nextToken();
    requests.take().complete("first");
    emit(firstToken, event(WorkDoneProgressKind.begin));
    emit(firstToken, failedEnd(400));
    String secondToken = nextToken();
    requests.take().complete("second");
    emit(secondToken, event(WorkDoneProgressKind.begin));
    emit(secondToken, failedEnd(400));
    assertEquals("second", result.get(3, TimeUnit.SECONDS));
    assertEquals(2, delivered.size());
    assertEquals(400, delivered.get(1).getCode());
    assertTrue(tokens.isEmpty());
  }

  @Test
  void testExecute_authenticationFailure_doesNotRetry() {
    AtomicInteger attempts = new AtomicInteger();
    final CompletableFuture<String> result = ChatRequestRetry.execute("auth", token -> {
      attempts.incrementAndGet();
      return CompletableFuture.failedFuture(new ResponseErrorException(new ResponseError(401, "Unauthorized", null)));
    }, 3, 0);
    assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
    assertEquals(1, attempts.get());
  }

  @Test
  void testProgress_filteredOrCancelledFailure_doesNotRetry() throws Exception {
    for (String reason : List.of("filtered", "incomplete", "cancelled")) {
      final CompletableFuture<String> result = start(3, 0);
      final String token = nextToken();
      requests.take().complete("response");
      ChatProgressValue end = failedEnd(500);
      if (reason.equals("filtered")) {
        end.getConversationError().setResponseIsFiltered(true);
      } else if (reason.equals("incomplete")) {
        end.getConversationError().setResponseIsIncomplete(true);
      } else {
        end.setCancellationReason("user");
      }
      emit(token, end);
      assertEquals("response", result.get(3, TimeUnit.SECONDS));
      assertTrue(tokens.isEmpty());
    }
  }

  @Test
  void testProgress_endsBeforeAcknowledgement_waitsForResponse() throws Exception {
    final CompletableFuture<String> result = start(3, 0);
    final String token = nextToken();
    emit(token, event(WorkDoneProgressKind.begin));
    emit(token, event(WorkDoneProgressKind.end));
    assertFalse(result.isDone());
    requests.take().complete("response");
    assertEquals("response", result.get(3, TimeUnit.SECONDS));
  }

  @Test
  void testProgress_partialResponse_preventsReplay() throws Exception {
    final CompletableFuture<String> result = start(3, 0);
    final String token = nextToken();
    requests.take().complete("response");
    emit(token, event(WorkDoneProgressKind.begin));
    ChatProgressValue report = event(WorkDoneProgressKind.report);
    report.setReply("Partial response");
    emit(token, report);
    emit(token, failedEnd(502));
    assertEquals("response", result.get(3, TimeUnit.SECONDS));
    assertEquals(502, delivered.get(2).getCode());
    assertTrue(tokens.isEmpty());
  }

  @Test
  void testProgress_emptyAgentRound_stillRetriesNetworkFailure() throws Exception {
    final CompletableFuture<String> result = start(1, 0);
    final String firstToken = nextToken();
    requests.take().complete("first");
    emit(firstToken, event(WorkDoneProgressKind.begin));
    // The wire DTO has no setters for agent rounds; use the same Gson mapping as LSP4J.
    ChatProgressValue report = new Gson().fromJson("""
        {"kind":"report","conversationId":"conversation","turnId":"turn","editAgentRounds":[{}]}
        """, ChatProgressValue.class);
    emit(firstToken, report);
    emit(firstToken, failedEnd(503));
    final String secondToken = nextToken();
    requests.take().complete("success");
    emit(secondToken, event(WorkDoneProgressKind.end));
    assertEquals("success", result.get(3, TimeUnit.SECONDS));
  }

  @Test
  void testProgress_toolInvocationBeforeReport_preventsReplay() throws Exception {
    final CompletableFuture<String> result = start(3, 0);
    final String token = nextToken();
    requests.take().complete("response");
    emit(token, event(WorkDoneProgressKind.begin));
    ChatRequestRetry.markToolActivity("conversation");
    emit(token, failedEnd(500));
    assertEquals("response", result.get(3, TimeUnit.SECONDS));
    assertEquals(2, delivered.size());
    assertTrue(tokens.isEmpty());
  }

  @Test
  void testProgress_subagentEnd_doesNotCompleteMainRequest() throws Exception {
    final CompletableFuture<String> result = start(3, 0);
    final String token = nextToken();
    requests.take().complete("response");
    emit(token, event(WorkDoneProgressKind.begin));
    ChatProgressValue subagentEnd = event(WorkDoneProgressKind.end);
    subagentEnd.setParentTurnId("parent");
    emit(token, subagentEnd);
    assertFalse(result.isDone());
    emit(token, event(WorkDoneProgressKind.end));
    assertEquals("response", result.get(3, TimeUnit.SECONDS));
  }

  @Test
  void testCancel_pendingDelay_noFurtherAttempts() throws Exception {
    final CompletableFuture<String> result = start(3, 60000);
    final String token = nextToken();
    requests.take().completeExceptionally(new TimeoutException());
    assertTrue(result.cancel(false));
    assertTrue(result.isCancelled());
    assertTrue(ChatRequestRetry.interceptProgress(token, event(WorkDoneProgressKind.begin), delivered::add));
    ChatRequestRetry.cancelAll();
    assertTrue(tokens.isEmpty());
  }

  @Test
  void testCancel_activeRequest_cancelsUnderlyingFuture() throws Exception {
    final CompletableFuture<String> result = start(3, 0);
    nextToken();
    CompletableFuture<String> request = requests.take();
    result.cancel(false);
    assertTrue(request.isCancelled());
  }

  @Test
  void testCancelAll_pendingRetry_cancelsResult() throws Exception {
    final CompletableFuture<String> result = start(3, 60000);
    nextToken();
    requests.take().completeExceptionally(new TimeoutException());
    ChatRequestRetry.cancelAll();
    assertTrue(result.isCancelled());
    assertTrue(tokens.isEmpty());
  }

  @Test
  void testExecute_retryInterval_delaysNextAttempt() throws Exception {
    final CompletableFuture<String> result = start(1, 100);
    nextToken();
    long started = System.nanoTime();
    requests.take().completeExceptionally(new TimeoutException());
    String next = nextToken();
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 90);
    requests.take().complete("success");
    emit(next, event(WorkDoneProgressKind.end));
    assertEquals("success", result.get(3, TimeUnit.SECONDS));
  }

  private CompletableFuture<String> start(int retries, long interval) {
    return ChatRequestRetry.execute("request", token -> {
      CompletableFuture<String> request = new CompletableFuture<>();
      requests.add(request);
      tokens.add(token);
      return request;
    }, retries, interval);
  }

  private String nextToken() throws InterruptedException {
    String token = tokens.poll(3, TimeUnit.SECONDS);
    assertNotNull(token);
    return token;
  }

  private void emit(String token, ChatProgressValue value) {
    if (!ChatRequestRetry.interceptProgress(token, value, delivered::add)) {
      delivered.add(value);
    }
  }

  private static ChatProgressValue event(WorkDoneProgressKind kind) {
    ChatProgressValue value = new ChatProgressValue();
    value.setKind(kind);
    value.setConversationId("conversation");
    value.setTurnId("turn");
    return value;
  }

  private static ChatProgressValue failedEnd(int code) {
    ChatProgressValue value = event(WorkDoneProgressKind.end);
    ConversationError error = new ConversationError();
    error.setCode(code);
    error.setMessage("service failed");
    value.setConversationError(error);
    return value;
  }
}
